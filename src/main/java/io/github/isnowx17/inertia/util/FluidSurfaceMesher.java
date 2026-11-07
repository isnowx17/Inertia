package io.github.isnowx17.inertia.util;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * Turns fluid particles into one continuous water surface.
 *
 * <p>Every particle is spread over the eight surrounding nodes of a sparse density grid with
 * linear (cloud-in-cell) weights, together with its velocity, and the density is then smoothed
 * with a small separable blur. The surface is the iso-surface {@code density = threshold},
 * extracted with naive <em>surface nets</em>: one vertex per grid cell crossed by the surface (at
 * the average of the edge crossings) and one quad per grid edge crossed by the surface.
 * Neighbouring particles therefore melt into a single smooth sheet instead of being drawn as
 * separate balls.
 *
 * <p>Each vertex carries a normal (from the density gradient), the local flow velocity, which the
 * renderer uses to move the surface between rebuilds, and the thickness of the water behind it.
 *
 * <p>The grid is stored in 16³ bricks and every stage is split by brick, so every brick is written
 * by exactly one task and the work runs in parallel on the given pool without locks. Bricks deep
 * inside a body of water (the brick and all its neighbours full of particles) are not splatted at
 * all but share one constant "solid" brick, and the extraction skips bricks the surface cannot
 * cross, so the cost follows the surface rather than the volume of the water.
 * Instances are not thread-safe: one {@link #mesh} call at a time.
 */
public final class FluidSurfaceMesher {
	/** Floats per output vertex: x, y, z, nx, ny, nz, vx, vy, vz, thickness. */
	public static final int VERTEX_STRIDE = 10;
	/** Water thickness is measured in steps of two cells, at most this many. */
	private static final int MAX_THICKNESS_STEPS = 24;

	private static final int BRICK_BITS = 4;
	private static final int BRICK = 1 << BRICK_BITS;
	private static final int BRICK_MASK = BRICK - 1;
	private static final int BRICK_VOLUME = BRICK * BRICK * BRICK;
	/**
	 * Floats per node in a brick: smoothed density, flow velocity (vx, vy, vz) and the unsmoothed
	 * density, which weights the velocity (nodes the blur reaches but no particle does have no flow).
	 */
	private static final int CHANNELS = 5;
	private static final int RAW = 4;
	/** After its nodes every brick stores its smallest and its largest density. */
	private static final int MIN_DENSITY = BRICK_VOLUME * CHANNELS;
	private static final int MAX_DENSITY = MIN_DENSITY + 1;
	private static final int BRICK_FLOATS = MIN_DENSITY + 2;
	private static final int STEP_Y = BRICK * CHANNELS;
	private static final int STEP_Z = BRICK * BRICK * CHANNELS;
	private static final int PAD = BRICK + 1;
	private static final int PAD_VOLUME = PAD * PAD * PAD;
	/** Particles below this fraction of the threshold count as isolated spray. */
	private static final float SPARSE_FRACTION = 0.6f;
	/** A brick touched by at least this fraction of the particles of still water counts as full. */
	private static final float FULL_FRACTION = 0.9f;
	/** Standard deviation, per unit of radius, of the (1 - d²/r²)³ kernel the radius used to describe. */
	private static final float KERNEL_SIGMA = 0.3015f;
	/** Spare brick arrays kept for the next mesh. */
	private static final int MAX_SPARE_BRICKS = 2048;
	/** Particles are binned in at most this many parallel chunks. */
	private static final int MAX_CHUNKS = 64;

	private final float cellSize;
	private final float restDensity;
	private final float threshold;
	private final ForkJoinPool pool;
	/** The density is blurred with [a, 1 - 2a, a] along every axis, this many times. */
	private final float blurWeight;
	private final int blurPasses;
	/** Particles touching a brick of still water, times FULL_FRACTION. */
	private final float fullCount;
	/** Shared brick for water deep inside a body: still-water density, no flow. */
	private final float[] solid;

	/** Per brick: CHANNELS floats per node, node-major, then the density range. */
	private Long2ObjectOpenHashMap<float[]> bricks = new Long2ObjectOpenHashMap<>();
	// The particles of the latest mesh and how many bin maps they were sorted into.
	private float[] meshedPositions;
	private int meshedCount;
	private int meshedStride;
	private int meshedChunks;
	private final ArrayDeque<float[]> spareBricks = new ArrayDeque<>();

	private final ThreadLocal<float[]> padDensity = ThreadLocal.withInitial(() -> new float[PAD_VOLUME]);
	private final ThreadLocal<float[]> padVelocity = ThreadLocal.withInitial(() -> new float[PAD_VOLUME * 3]);
	private final ThreadLocal<float[]> padRaw = ThreadLocal.withInitial(() -> new float[PAD_VOLUME]);
	private final ThreadLocal<float[]> blurPad;
	/** Particle bins per chunk, kept between meshes so their lists keep their capacity. */
	@SuppressWarnings("unchecked")
	private final Long2ObjectOpenHashMap<IntArrayList>[] binMaps = new Long2ObjectOpenHashMap[MAX_CHUNKS];
	private final ThreadLocal<float[]> blurLine;

	/**
	 * @param cellSize          grid spacing in blocks
	 * @param particleSpacing   distance between neighbouring particles in still water, in blocks
	 * @param radius            smoothing radius in blocks; the density is smoothed by about 0.3 of it
	 * @param thresholdFraction surface level as a fraction of the density of still water
	 * @param pool              pool for parallel work, or {@code null} to run on the calling thread
	 */
	public FluidSurfaceMesher(float cellSize, float particleSpacing, float radius, float thresholdFraction, ForkJoinPool pool) {
		if (!(cellSize > 0f) || !(particleSpacing > 0f) || !(radius > 0f) || !(thresholdFraction > 0f)) {
			throw new IllegalArgumentException("cellSize, particleSpacing, radius and thresholdFraction must be positive");
		}
		this.cellSize = cellSize;
		this.restDensity = restDensity(particleSpacing, cellSize);
		this.threshold = thresholdFraction * restDensity;
		this.pool = pool;

		// The linear splat itself smooths with a variance of 1/6 cell²; the blur adds the rest.
		float sigma = KERNEL_SIGMA * radius / cellSize;
		float variance = Math.max(0f, sigma * sigma - 1f / 6f);
		int passes = variance <= 1e-4f ? 0 : Math.min(4, (int) Math.ceil(variance / (2f / 3f)));
		this.blurPasses = passes;
		this.blurWeight = passes == 0 ? 0f : Math.min(1f / 3f, variance / (2f * passes));

		this.fullCount = FULL_FRACTION * BRICK_VOLUME * restDensity;
		this.solid = new float[BRICK_FLOATS];
		for (int i = 0; i < BRICK_VOLUME; i++) {
			solid[i * CHANNELS] = restDensity;
			solid[i * CHANNELS + RAW] = restDensity;
		}
		solid[MIN_DENSITY] = restDensity;
		solid[MAX_DENSITY] = restDensity;

		int padSize = BRICK + 2 * passes;
		this.blurPad = ThreadLocal.withInitial(() -> new float[padSize * padSize * padSize]);
		this.blurLine = ThreadLocal.withInitial(() -> new float[padSize]);
	}

	public FluidSurfaceMesher(float cellSize, float particleSpacing, float radius, float thresholdFraction) {
		this(cellSize, particleSpacing, radius, thresholdFraction, null);
	}

	/** Density of still water: the number of particles per grid cell. */
	public static float restDensity(float particleSpacing, float cellSize) {
		float ratio = cellSize / particleSpacing;
		return ratio * ratio * ratio;
	}

	/** Output of {@link #mesh}. Quads reference vertices by index, four indices per quad. */
	public static final class Mesh {
		public float[] vertices = new float[0];
		public int vertexCount;
		public int[] quads = new int[0];
		public int quadCount;

		public boolean isEmpty() {
			return quadCount == 0;
		}
	}

	/** Density of the surface (particles per grid cell). */
	public float threshold() {
		return threshold;
	}

	/** Density of still water (particles per grid cell). */
	public float restDensity() {
		return restDensity;
	}

	public float cellSize() {
		return cellSize;
	}

	/** Builds the surface of {@code count} particles stored as x, y, z triples. */
	public Mesh mesh(float[] positions, float[] velocities, int count, Mesh target) {
		return mesh(positions, velocities, count, 3, target);
	}

	/**
	 * Builds the surface of {@code count} particles.
	 *
	 * @param positions  x, y, z of particle i at {@code i * stride}
	 * @param velocities x, y, z (blocks/second) of particle i at {@code i * stride}, or {@code null}
	 * @param stride     floats per particle in both arrays, at least 3
	 * @param target     mesh to fill (reused to avoid allocations), or {@code null} for a new one
	 */
	public Mesh mesh(float[] positions, float[] velocities, int count, int stride, Mesh target) {
		return mesh(positions, velocities, count, stride, null, 0, target);
	}

	/**
	 * Builds the surface of {@code count} particles and of water given as boxes: still water kept as a
	 * volume, which counts as evenly spread particles of the same spacing filling each box.
	 *
	 * @param boxes    min x, y, z, max x, y, z of every box, or {@code null}
	 * @param boxCount number of boxes
	 */
	public Mesh mesh(float[] positions, float[] velocities, int count, int stride, float[] boxes, int boxCount, Mesh target) {
		Mesh mesh = target != null ? target : new Mesh();
		mesh.vertexCount = 0;
		mesh.quadCount = 0;

		for (float[] brick : bricks.values()) {
			recycle(brick);
		}
		bricks = splat(positions, velocities, count, stride, boxes, boxes == null ? 0 : boxCount);
		meshedPositions = positions;
		meshedCount = count;
		meshedStride = stride;
		if (!bricks.isEmpty()) {
			extract(mesh);
		}
		return mesh;
	}

	// ------------------------------------------------------------------------------------------
	// Field queries (valid after mesh())
	// ------------------------------------------------------------------------------------------

	/** Field density at the grid node nearest to a point. */
	public float densityAt(float x, float y, float z) {
		int nx = Math.round(x / cellSize);
		int ny = Math.round(y / cellSize);
		int nz = Math.round(z / cellSize);
		float[] brick = bricks.get(brickKey(nx >> BRICK_BITS, ny >> BRICK_BITS, nz >> BRICK_BITS));
		return brick == null ? 0f : brick[localIndex(nx & BRICK_MASK, ny & BRICK_MASK, nz & BRICK_MASK) * CHANNELS];
	}

	/** Flow velocity at the grid node nearest to a point, written to {@code out}. */
	public void velocityAt(float x, float y, float z, float[] out) {
		int nx = Math.round(x / cellSize);
		int ny = Math.round(y / cellSize);
		int nz = Math.round(z / cellSize);
		float[] brick = bricks.get(brickKey(nx >> BRICK_BITS, ny >> BRICK_BITS, nz >> BRICK_BITS));
		if (brick == null) {
			out[0] = out[1] = out[2] = 0f;
			return;
		}
		int o = localIndex(nx & BRICK_MASK, ny & BRICK_MASK, nz & BRICK_MASK) * CHANNELS;
		out[0] = brick[o + 1];
		out[1] = brick[o + 2];
		out[2] = brick[o + 3];
	}

	/** {@link #sparseParticles(float[], int, int)} for x, y, z triples. */
	public int[] sparseParticles(float[] positions, int count) {
		return sparseParticles(positions, count, 3);
	}

	/**
	 * Indices of the particles in regions too sparse to form a surface (isolated spray droplets).
	 * Particles at the corners of a body of water sit just outside the iso-surface too; the lower
	 * cut-off keeps most of those out so they are not drawn twice.
	 */
	public int[] sparseParticles(float[] positions, int count, int stride) {
		float cutoff = threshold * SPARSE_FRACTION;
		if (positions == meshedPositions && count == meshedCount && stride == meshedStride) {
			return sparseFromBins(cutoff);
		}
		int chunks = Math.max(1, Math.min(64, count / 4096));
		IntArrayList[] parts = new IntArrayList[chunks];
		parallelFor(chunks, c -> {
			IntArrayList list = new IntArrayList();
			int from = (int) ((long) count * c / chunks);
			int to = (int) ((long) count * (c + 1) / chunks);
			for (int i = from; i < to; i++) {
				if (densityAt(positions[i * stride], positions[i * stride + 1], positions[i * stride + 2]) < cutoff) {
					list.add(i);
				}
			}
			parts[c] = list;
		});
		int total = 0;
		for (IntArrayList part : parts) {
			total += part.size();
		}
		int[] result = new int[total];
		int o = 0;
		for (IntArrayList part : parts) {
			part.getElements(0, result, o, part.size());
			o += part.size();
		}
		return result;
	}

	/**
	 * {@link #sparseParticles} for the particles of the latest mesh, using its particle bins: bricks
	 * dense enough everywhere (with the neighbours their particles' nearest nodes may lie in) are
	 * skipped as a whole, and within a brick the density lookups stay in the same few bricks.
	 */
	private int[] sparseFromBins(float cutoff) {
		float[] positions = meshedPositions;
		int stride = meshedStride;
		float inv = 1f / cellSize;
		int chunks = meshedChunks;
		IntArrayList[] parts = new IntArrayList[chunks];
		parallelFor(chunks, c -> {
			IntArrayList list = new IntArrayList();
			Long2ObjectOpenHashMap<IntArrayList> map = binMaps[c];
			if (map != null) {
				for (Long2ObjectOpenHashMap.Entry<IntArrayList> entry : map.long2ObjectEntrySet()) {
					long key = entry.getLongKey();
					if (denseAround(key, cutoff)) {
						continue;
					}
					IntArrayList bin = entry.getValue();
					int[] indices = bin.elements();
					long lastKey = Long.MIN_VALUE;
					float[] last = null;
					for (int k = 0, size = bin.size(); k < size; k++) {
						int i = indices[k];
						float gx = positions[i * stride] * inv;
						float gy = positions[i * stride + 1] * inv;
						float gz = positions[i * stride + 2] * inv;
						// Each particle once: in the bin of its own brick, not the ones it reaches into.
						if (brickKey(floor(gx) >> BRICK_BITS, floor(gy) >> BRICK_BITS, floor(gz) >> BRICK_BITS) != key) {
							continue;
						}
						int nx = Math.round(gx);
						int ny = Math.round(gy);
						int nz = Math.round(gz);
						long nodeBrick = brickKey(nx >> BRICK_BITS, ny >> BRICK_BITS, nz >> BRICK_BITS);
						if (nodeBrick != lastKey) {
							lastKey = nodeBrick;
							last = bricks.get(nodeBrick);
						}
						float density = last == null ? 0f
							: last[localIndex(nx & BRICK_MASK, ny & BRICK_MASK, nz & BRICK_MASK) * CHANNELS];
						if (density < cutoff) {
							list.add(i);
						}
					}
				}
			}
			parts[c] = list;
		});
		int total = 0;
		for (IntArrayList part : parts) {
			total += part.size();
		}
		int[] result = new int[total];
		int o = 0;
		for (IntArrayList part : parts) {
			part.getElements(0, result, o, part.size());
			o += part.size();
		}
		Arrays.sort(result);
		return result;
	}

	/** Whether a brick and its positive neighbours have at least {@code level} density everywhere. */
	private boolean denseAround(long key, float level) {
		int bx = brickX(key);
		int by = brickY(key);
		int bz = brickZ(key);
		for (int dz = 0; dz <= 1; dz++) {
			for (int dy = 0; dy <= 1; dy++) {
				for (int dx = 0; dx <= 1; dx++) {
					float[] brick = bricks.get(brickKey(bx + dx, by + dy, bz + dz));
					if (brick == null || brick[MIN_DENSITY] < level) {
						return false;
					}
				}
			}
		}
		return true;
	}

	// ------------------------------------------------------------------------------------------
	// Density field
	// ------------------------------------------------------------------------------------------

	private Long2ObjectOpenHashMap<float[]> splat(float[] positions, float[] velocities, int count, int stride,
		float[] boxes, int boxCount) {
		float inv = 1f / cellSize;

		// 1. Bin particle indices by the brick of their lowest grid node. A particle on the last node
		//    layer of a brick also reaches into the next brick(s), so it is binned there as well. Each
		//    chunk of particles gets its own bin map so binning runs in parallel without merging.
		int chunks = Math.max(1, Math.min(MAX_CHUNKS, count / 8192));
		meshedChunks = chunks;
		for (int c = chunks; c < MAX_CHUNKS; c++) {
			if (binMaps[c] != null) {
				binMaps[c].clear();
			}
		}
		@SuppressWarnings("unchecked")
		Long2ObjectOpenHashMap<IntArrayList>[] bins = new Long2ObjectOpenHashMap[chunks];
		parallelFor(chunks, c -> {
			Long2ObjectOpenHashMap<IntArrayList> map = binMaps[c];
			if (map == null) {
				map = new Long2ObjectOpenHashMap<>();
				binMaps[c] = map;
			}
			for (IntArrayList list : map.values()) {
				list.clear();
			}
			int from = (int) ((long) count * c / chunks);
			int to = (int) ((long) count * (c + 1) / chunks);
			long lastKey = Long.MIN_VALUE;
			IntArrayList last = null;
			for (int i = from; i < to; i++) {
				float px = positions[i * stride];
				float py = positions[i * stride + 1];
				float pz = positions[i * stride + 2];
				if (!Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(pz)) {
					continue;
				}
				int ix = floor(px * inv);
				int iy = floor(py * inv);
				int iz = floor(pz * inv);
				int bx = ix >> BRICK_BITS;
				int by = iy >> BRICK_BITS;
				int bz = iz >> BRICK_BITS;
				long key = brickKey(bx, by, bz);
				if (key != lastKey) {
					lastKey = key;
					last = map.get(key);
					if (last == null) {
						last = new IntArrayList();
						map.put(key, last);
					}
				}
				last.add(i);
				int spill = ((ix & BRICK_MASK) == BRICK_MASK ? 1 : 0)
					| ((iy & BRICK_MASK) == BRICK_MASK ? 2 : 0)
					| ((iz & BRICK_MASK) == BRICK_MASK ? 4 : 0);
				if (spill != 0) {
					for (int s = 1; s < 8; s++) {
						if ((s & ~spill) != 0) {
							continue;
						}
						long next = brickKey(bx + (s & 1), by + ((s >> 1) & 1), bz + ((s >> 2) & 1));
						IntArrayList list = map.get(next);
						if (list == null) {
							list = new IntArrayList();
							map.put(next, list);
						}
						list.add(i);
					}
				}
			}
			// Forget bricks the water has left.
			map.long2ObjectEntrySet().removeIf(entry -> entry.getValue().isEmpty());
			bins[c] = map;
		});

		// 2. Particles per brick.
		Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
		for (Long2ObjectOpenHashMap<IntArrayList> map : bins) {
			for (Long2ObjectOpenHashMap.Entry<IntArrayList> entry : map.long2ObjectEntrySet()) {
				counts.addTo(entry.getLongKey(), entry.getValue().size());
			}
		}
		// The boxes: the particles they stand for per brick, and the boxes reaching each brick's nodes.
		Long2ObjectOpenHashMap<IntArrayList> boxBricks = new Long2ObjectOpenHashMap<>();
		if (boxCount > 0) {
			float perVolume = restDensity * inv * inv * inv;
			float brickSize = cellSize * BRICK;
			for (int b = 0; b < boxCount; b++) {
				int o = b * 6;
				float x0 = boxes[o];
				float y0 = boxes[o + 1];
				float z0 = boxes[o + 2];
				float x1 = boxes[o + 3];
				float y1 = boxes[o + 4];
				float z1 = boxes[o + 5];
				if (!(x1 > x0) || !(y1 > y0) || !(z1 > z0)) {
					continue;
				}
				// Nodes within one cell of the box get some of its density.
				int bx0 = floor(x0 * inv - 1f) >> BRICK_BITS;
				int by0 = floor(y0 * inv - 1f) >> BRICK_BITS;
				int bz0 = floor(z0 * inv - 1f) >> BRICK_BITS;
				int bx1 = floor(x1 * inv + 1f) >> BRICK_BITS;
				int by1 = floor(y1 * inv + 1f) >> BRICK_BITS;
				int bz1 = floor(z1 * inv + 1f) >> BRICK_BITS;
				for (int bx = bx0; bx <= bx1; bx++) {
					float ox = overlap(x0, x1, bx * brickSize, (bx + 1) * brickSize);
					for (int by = by0; by <= by1; by++) {
						float oy = overlap(y0, y1, by * brickSize, (by + 1) * brickSize);
						for (int bz = bz0; bz <= bz1; bz++) {
							float oz = overlap(z0, z1, bz * brickSize, (bz + 1) * brickSize);
							long key = brickKey(bx, by, bz);
							counts.addTo(key, Math.round(ox * oy * oz * perVolume));
							IntArrayList list = boxBricks.get(key);
							if (list == null) {
								list = new IntArrayList();
								boxBricks.put(key, list);
							}
							list.add(b);
						}
					}
				}
			}
		}
		long[] keys = counts.keySet().toLongArray();
		int n = keys.length;

		// 3. Deep bricks (the brick and all 26 neighbours full) need neither splatting nor blurring.
		boolean[] deep = new boolean[n];
		parallelFor(n, t -> {
			long key = keys[t];
			int bx = brickX(key);
			int by = brickY(key);
			int bz = brickZ(key);
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						if (counts.get(brickKey(bx + dx, by + dy, bz + dz)) < fullCount) {
							return;
						}
					}
				}
			}
			deep[t] = true;
		});

		// 4. Linear splat of density and velocity, one task per brick.
		float[][] raw = obtain(n);
		parallelFor(n, t -> {
			if (deep[t]) {
				return;
			}
			float[] brick = raw[t];
			Arrays.fill(brick, 0f);
			long key = keys[t];
			int minX = brickX(key) << BRICK_BITS;
			int minY = brickY(key) << BRICK_BITS;
			int minZ = brickZ(key) << BRICK_BITS;
			for (Long2ObjectOpenHashMap<IntArrayList> map : bins) {
				IntArrayList list = map.get(key);
				if (list == null) {
					continue;
				}
				int[] indices = list.elements();
				for (int k = 0, size = list.size(); k < size; k++) {
					int i = indices[k];
					int o = i * stride;
					float gx = positions[o] * inv - minX;
					float gy = positions[o + 1] * inv - minY;
					float gz = positions[o + 2] * inv - minZ;
					float vx = velocities != null ? velocities[o] : 0f;
					float vy = velocities != null ? velocities[o + 1] : 0f;
					float vz = velocities != null ? velocities[o + 2] : 0f;
					splatParticle(brick, gx, gy, gz, vx, vy, vz);
				}
			}
			IntArrayList inBoxes = boxBricks.get(key);
			if (inBoxes != null) {
				for (int k = 0, size = inBoxes.size(); k < size; k++) {
					splatBox(brick, minX, minY, minZ, boxes, inBoxes.getInt(k));
				}
			}
			// Weighted velocity sums become node velocities; keep the unsmoothed density.
			for (int node = 0; node < BRICK_VOLUME; node++) {
				int o = node * CHANNELS;
				float d = brick[o];
				brick[o + RAW] = d;
				if (d > 1e-6f) {
					float invD = 1f / d;
					brick[o + 1] *= invD;
					brick[o + 2] *= invD;
					brick[o + 3] *= invD;
				} else {
					brick[o + 1] = 0f;
					brick[o + 2] = 0f;
					brick[o + 3] = 0f;
				}
			}
			updateRange(brick);
		});
		for (int t = 0; t < n; t++) {
			if (deep[t]) {
				recycle(raw[t]);
				raw[t] = solid;
			}
		}

		// 5. Smooth the density (velocity is copied); deep bricks stay solid.
		Long2ObjectOpenHashMap<float[]> result = new Long2ObjectOpenHashMap<>(n);
		if (blurPasses == 0) {
			for (int t = 0; t < n; t++) {
				result.put(keys[t], raw[t]);
			}
			return result;
		}
		Long2ObjectOpenHashMap<float[]> rawMap = new Long2ObjectOpenHashMap<>(n);
		for (int t = 0; t < n; t++) {
			rawMap.put(keys[t], raw[t]);
		}
		float[][] smooth = obtain(n);
		parallelFor(n, t -> {
			if (deep[t]) {
				return;
			}
			blur(keys[t], raw[t], rawMap, smooth[t]);
		});
		for (int t = 0; t < n; t++) {
			if (deep[t]) {
				recycle(smooth[t]);
				result.put(keys[t], solid);
			} else {
				recycle(raw[t]);
				result.put(keys[t], smooth[t]);
			}
		}
		return result;
	}

	/** Length of the overlap of {@code [a0, a1]} and {@code [b0, b1]}. */
	private static float overlap(float a0, float a1, float b0, float b1) {
		return Math.max(0f, Math.min(a1, b1) - Math.max(a0, b0));
	}

	private final ThreadLocal<float[]> boxWeights = ThreadLocal.withInitial(() -> new float[BRICK * 3]);

	/**
	 * Adds the density of box {@code b} to the nodes of a brick whose first node is {@code min}: what
	 * evenly spread particles filling the box give with the linear splat, per axis the share of a
	 * node's tent that lies in the box.
	 */
	private void splatBox(float[] brick, int minX, int minY, int minZ, float[] boxes, int b) {
		float inv = 1f / cellSize;
		int o = b * 6;
		float[] w = boxWeights.get();
		int[] range = new int[6];
		for (int axis = 0; axis < 3; axis++) {
			float lo = boxes[o + axis] * inv;
			float hi = boxes[o + 3 + axis] * inv;
			int first = axis == 0 ? minX : axis == 1 ? minY : minZ;
			int from = BRICK;
			int to = -1;
			for (int l = 0; l < BRICK; l++) {
				float g = first + l;
				float share = tentShare(hi - g) - tentShare(lo - g);
				w[axis * BRICK + l] = share;
				if (share > 0f) {
					from = Math.min(from, l);
					to = l;
				}
			}
			if (to < 0) {
				return;
			}
			range[axis * 2] = from;
			range[axis * 2 + 1] = to;
		}
		float rest = restDensity;
		for (int z = range[4]; z <= range[5]; z++) {
			float wz = w[2 * BRICK + z] * rest;
			for (int y = range[2]; y <= range[3]; y++) {
				float wyz = w[BRICK + y] * wz;
				int base = localIndex(0, y, z) * CHANNELS;
				for (int x = range[0]; x <= range[1]; x++) {
					brick[base + x * CHANNELS] += w[x] * wyz;
				}
			}
		}
	}

	/** Share of a node's tent (half-width one cell) that lies below {@code u} cells from the node. */
	private static float tentShare(float u) {
		if (u <= -1f) {
			return 0f;
		}
		if (u < 0f) {
			return 0.5f * (u + 1f) * (u + 1f);
		}
		if (u < 1f) {
			return 1f - 0.5f * (1f - u) * (1f - u);
		}
		return 1f;
	}

	/** Adds one particle at local grid coordinates (g) to the up to eight nodes around it. */
	private static void splatParticle(float[] brick, float gx, float gy, float gz, float vx, float vy, float vz) {
		// Local coordinates are at least -1 (particles binned in from the previous brick).
		int lx = (int) (gx + 1f) - 1;
		int ly = (int) (gy + 1f) - 1;
		int lz = (int) (gz + 1f) - 1;
		float fx = gx - lx;
		float fy = gy - ly;
		float fz = gz - lz;
		float wx0 = 1f - fx;
		float wy0 = 1f - fy;
		float wz0 = 1f - fz;
		if (lx >= 0 && ly >= 0 && lz >= 0 && lx < BRICK - 1 && ly < BRICK - 1 && lz < BRICK - 1) {
			int o = localIndex(lx, ly, lz) * CHANNELS;
			float w00 = wy0 * wz0;
			float w10 = fy * wz0;
			float w01 = wy0 * fz;
			float w11 = fy * fz;
			deposit(brick, o, wx0 * w00, vx, vy, vz);
			deposit(brick, o + CHANNELS, fx * w00, vx, vy, vz);
			deposit(brick, o + STEP_Y, wx0 * w10, vx, vy, vz);
			deposit(brick, o + STEP_Y + CHANNELS, fx * w10, vx, vy, vz);
			deposit(brick, o + STEP_Z, wx0 * w01, vx, vy, vz);
			deposit(brick, o + STEP_Z + CHANNELS, fx * w01, vx, vy, vz);
			deposit(brick, o + STEP_Z + STEP_Y, wx0 * w11, vx, vy, vz);
			deposit(brick, o + STEP_Z + STEP_Y + CHANNELS, fx * w11, vx, vy, vz);
			return;
		}
		// Particle on the border: only the nodes inside this brick.
		for (int c = 0; c < 8; c++) {
			int nx = lx + (c & 1);
			int ny = ly + ((c >> 1) & 1);
			int nz = lz + ((c >> 2) & 1);
			if (nx < 0 || ny < 0 || nz < 0 || nx >= BRICK || ny >= BRICK || nz >= BRICK) {
				continue;
			}
			float w = ((c & 1) == 0 ? wx0 : fx) * ((c & 2) == 0 ? wy0 : fy) * ((c & 4) == 0 ? wz0 : fz);
			deposit(brick, localIndex(nx, ny, nz) * CHANNELS, w, vx, vy, vz);
		}
	}

	/** Floor for coordinates of either sign. */
	private static int floor(float v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}

	private static void deposit(float[] brick, int o, float w, float vx, float vy, float vz) {
		brick[o] += w;
		brick[o + 1] += w * vx;
		brick[o + 2] += w * vy;
		brick[o + 3] += w * vz;
	}

	private static void updateRange(float[] brick) {
		float min = Float.POSITIVE_INFINITY;
		float max = Float.NEGATIVE_INFINITY;
		for (int node = 0; node < BRICK_VOLUME; node++) {
			float d = brick[node * CHANNELS];
			if (d < min) {
				min = d;
			}
			if (d > max) {
				max = d;
			}
		}
		brick[MIN_DENSITY] = min;
		brick[MAX_DENSITY] = max;
	}

	/**
	 * Smooths the density of one brick into {@code target} with the separable blur; the halo comes
	 * from the neighbouring bricks (zero where there is no water). Velocities are copied.
	 */
	private void blur(long key, float[] own, Long2ObjectOpenHashMap<float[]> raw, float[] target) {
		int h = blurPasses;
		int size = BRICK + 2 * h;
		float[] pad = blurPad.get();
		Arrays.fill(pad, 0f);
		int bx = brickX(key);
		int by = brickY(key);
		int bz = brickZ(key);
		for (int dz = -1; dz <= 1; dz++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dx = -1; dx <= 1; dx++) {
					float[] src = dx == 0 && dy == 0 && dz == 0 ? own : raw.get(brickKey(bx + dx, by + dy, bz + dz));
					if (src == null) {
						continue;
					}
					// Source node range inside the neighbour and where it lands in the padded block.
					int sx0 = dx < 0 ? BRICK - h : 0;
					int sx1 = dx > 0 ? h : BRICK;
					int sy0 = dy < 0 ? BRICK - h : 0;
					int sy1 = dy > 0 ? h : BRICK;
					int sz0 = dz < 0 ? BRICK - h : 0;
					int sz1 = dz > 0 ? h : BRICK;
					int ox = h + dx * BRICK;
					int oy = h + dy * BRICK;
					int oz = h + dz * BRICK;
					for (int z = sz0; z < sz1; z++) {
						for (int y = sy0; y < sy1; y++) {
							int row = ((z + oz) * size + (y + oy)) * size + ox;
							int srcRow = localIndex(0, y, z) * CHANNELS;
							for (int x = sx0; x < sx1; x++) {
								pad[row + x] = src[srcRow + x * CHANNELS];
							}
						}
					}
				}
			}
		}
		for (int pass = 0; pass < h; pass++) {
			blurAxis(pad, size, 1);
			blurAxis(pad, size, size);
			blurAxis(pad, size, size * size);
		}
		for (int z = 0; z < BRICK; z++) {
			for (int y = 0; y < BRICK; y++) {
				int row = ((z + h) * size + (y + h)) * size + h;
				for (int x = 0; x < BRICK; x++) {
					int o = localIndex(x, y, z) * CHANNELS;
					target[o] = pad[row + x];
					target[o + 1] = own[o + 1];
					target[o + 2] = own[o + 2];
					target[o + 3] = own[o + 3];
					target[o + RAW] = own[o + RAW];
				}
			}
		}
		updateRange(target);
	}

	/** One pass of [a, 1 - 2a, a] along the axis with the given index step; zero beyond the ends. */
	private void blurAxis(float[] pad, int size, int step) {
		float a = blurWeight;
		float b = 1f - 2f * a;
		float[] line = blurLine.get();
		int lines = size * size;
		for (int l = 0; l < lines; l++) {
			int start;
			if (step == 1) {
				start = l * size;
			} else if (step == size) {
				start = (l / size) * size * size + (l % size);
			} else {
				start = l;
			}
			boolean any = false;
			for (int i = 0, p = start; i < size; i++, p += step) {
				float v = pad[p];
				line[i] = v;
				any |= v != 0f;
			}
			if (!any) {
				continue;
			}
			float left = 0f;
			for (int i = 0, p = start; i < size; i++, p += step) {
				float centre = line[i];
				float right = i + 1 < size ? line[i + 1] : 0f;
				pad[p] = b * centre + a * (left + right);
				left = centre;
			}
		}
	}

	/** Brick arrays for one stage, from the spare pool where possible (contents undefined). */
	private float[][] obtain(int n) {
		float[][] arrays = new float[n][];
		for (int i = 0; i < n; i++) {
			float[] spare = spareBricks.poll();
			arrays[i] = spare != null ? spare : new float[BRICK_FLOATS];
		}
		return arrays;
	}

	private void recycle(float[] brick) {
		if (brick != null && brick != solid && brick.length == BRICK_FLOATS && spareBricks.size() < MAX_SPARE_BRICKS) {
			spareBricks.add(brick);
		}
	}

	/** Copies a brick plus its one-node border on the positive sides into the padded arrays. */
	private void loadPadded(int bx, int by, int bz, float[] density, float[] velocity, float[] raw) {
		Arrays.fill(density, 0f);
		Arrays.fill(velocity, 0f);
		Arrays.fill(raw, 0f);
		for (int dz = 0; dz <= 1; dz++) {
			for (int dy = 0; dy <= 1; dy++) {
				for (int dx = 0; dx <= 1; dx++) {
					float[] src = bricks.get(brickKey(bx + dx, by + dy, bz + dz));
					if (src == null) {
						continue;
					}
					int zFrom = dz * BRICK;
					int zTo = dz == 0 ? BRICK : PAD;
					int yFrom = dy * BRICK;
					int yTo = dy == 0 ? BRICK : PAD;
					int xFrom = dx * BRICK;
					int xTo = dx == 0 ? BRICK : PAD;
					for (int z = zFrom; z < zTo; z++) {
						for (int y = yFrom; y < yTo; y++) {
							for (int x = xFrom; x < xTo; x++) {
								int s = localIndex(x - xFrom, y - yFrom, z - zFrom) * CHANNELS;
								int d = padIndex(x, y, z);
								density[d] = src[s];
								velocity[d * 3] = src[s + 1];
								velocity[d * 3 + 1] = src[s + 2];
								velocity[d * 3 + 2] = src[s + 3];
								raw[d] = src[s + RAW];
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Whether the surface may cross the cells of a brick: the cells reach one node into the
	 * positive neighbours, so all eight bricks must be entirely inside or entirely outside.
	 */
	private boolean mayHaveSurface(long key) {
		int bx = brickX(key);
		int by = brickY(key);
		int bz = brickZ(key);
		boolean allInside = true;
		boolean allOutside = true;
		for (int dz = 0; dz <= 1; dz++) {
			for (int dy = 0; dy <= 1; dy++) {
				for (int dx = 0; dx <= 1; dx++) {
					float[] brick = bricks.get(brickKey(bx + dx, by + dy, bz + dz));
					if (brick == null) {
						allInside = false;
					} else {
						if (brick[MIN_DENSITY] <= threshold) {
							allInside = false;
						}
						if (brick[MAX_DENSITY] > threshold) {
							allOutside = false;
						}
					}
				}
			}
		}
		return !allInside && !allOutside;
	}

	// ------------------------------------------------------------------------------------------
	// Surface nets
	// ------------------------------------------------------------------------------------------

	private static final int[][] CORNERS = {
		{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {1, 1, 0},
		{0, 0, 1}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1}
	};
	private static final int[] CORNER_OFFSETS = new int[8];

	static {
		for (int c = 0; c < 8; c++) {
			CORNER_OFFSETS[c] = padIndex(CORNERS[c][0], CORNERS[c][1], CORNERS[c][2]);
		}
	}

	/** The 12 cube edges as pairs of corner indices. */
	private static final int[][] EDGES = {
		{0, 1}, {2, 3}, {4, 5}, {6, 7},
		{0, 2}, {1, 3}, {4, 6}, {5, 7},
		{0, 4}, {1, 5}, {2, 6}, {3, 7}
	};

	private static final int EDGE_X = 1;
	private static final int EDGE_Y = 2;
	private static final int EDGE_Z = 4;
	/** Set when the minimum corner is inside the water. */
	private static final int INSIDE = 8;

	/** Vertices and quad edges found in one brick. */
	private static final class BrickSurface {
		long key;
		int[] localVertex;            // vertex index (local to this brick) per cell, or -1
		float[] vertices = new float[0];
		int vertexCount;
		int[] quadCells = new int[0];  // local cell index | flags << 12
		int quadCellCount;
		int vertexOffset;
		int[] quads = new int[0];
		int quadCount;
	}

	private void extract(Mesh mesh) {
		// A cell is visited from the brick holding its minimum corner. That brick may be empty while
		// its positive neighbours are not, so also visit the negative neighbours of filled bricks.
		LongOpenHashSet cellBrickSet = new LongOpenHashSet();
		for (long key : bricks.keySet()) {
			int bx = brickX(key);
			int by = brickY(key);
			int bz = brickZ(key);
			for (int dx = -1; dx <= 0; dx++) {
				for (int dy = -1; dy <= 0; dy++) {
					for (int dz = -1; dz <= 0; dz++) {
						cellBrickSet.add(brickKey(bx + dx, by + dy, bz + dz));
					}
				}
			}
		}
		long[] cellBricks = cellBrickSet.toLongArray();

		// Pass 1 (parallel): vertices per brick.
		BrickSurface[] surfaces = new BrickSurface[cellBricks.length];
		parallelFor(cellBricks.length, b -> surfaces[b] = mayHaveSurface(cellBricks[b]) ? vertices(cellBricks[b]) : null);

		// Global vertex numbering.
		Long2IntOpenHashMap surfaceIndex = new Long2IntOpenHashMap();
		surfaceIndex.defaultReturnValue(-1);
		int vertexTotal = 0;
		for (int b = 0; b < surfaces.length; b++) {
			BrickSurface s = surfaces[b];
			if (s != null) {
				s.vertexOffset = vertexTotal;
				vertexTotal += s.vertexCount;
				surfaceIndex.put(s.key, b);
			}
		}

		// Pass 2 (parallel): quads per brick, referencing vertices of neighbouring bricks.
		parallelFor(surfaces.length, b -> {
			if (surfaces[b] != null) {
				quads(surfaces[b], surfaces, surfaceIndex);
			}
		});

		// Concatenate.
		int quadTotal = 0;
		for (BrickSurface s : surfaces) {
			if (s != null) {
				quadTotal += s.quadCount;
			}
		}
		if (mesh.vertices.length < vertexTotal * VERTEX_STRIDE) {
			mesh.vertices = new float[vertexTotal * VERTEX_STRIDE];
		}
		if (mesh.quads.length < quadTotal * 4) {
			mesh.quads = new int[quadTotal * 4];
		}
		int q = 0;
		for (BrickSurface s : surfaces) {
			if (s == null) {
				continue;
			}
			System.arraycopy(s.vertices, 0, mesh.vertices, s.vertexOffset * VERTEX_STRIDE, s.vertexCount * VERTEX_STRIDE);
			System.arraycopy(s.quads, 0, mesh.quads, q * 4, s.quadCount * 4);
			q += s.quadCount;
		}
		mesh.vertexCount = vertexTotal;
		mesh.quadCount = quadTotal;
	}

	private BrickSurface vertices(long key) {
		int bx = brickX(key);
		int by = brickY(key);
		int bz = brickZ(key);
		float[] density = padDensity.get();
		float[] velocity = padVelocity.get();
		float[] raw = padRaw.get();
		loadPadded(bx, by, bz, density, velocity, raw);

		boolean anyInside = false;
		boolean anyOutside = false;
		for (int i = 0; i < PAD_VOLUME && !(anyInside && anyOutside); i++) {
			if (density[i] > threshold) {
				anyInside = true;
			} else {
				anyOutside = true;
			}
		}
		if (!anyInside || !anyOutside) {
			return null;
		}

		BrickSurface surface = null;
		float[] corner = new float[8];
		int baseX = bx << BRICK_BITS;
		int baseY = by << BRICK_BITS;
		int baseZ = bz << BRICK_BITS;

		for (int lz = 0; lz < BRICK; lz++) {
			for (int ly = 0; ly < BRICK; ly++) {
				for (int lx = 0; lx < BRICK; lx++) {
					int p = padIndex(lx, ly, lz);
					int mask = 0;
					for (int c = 0; c < 8; c++) {
						float v = density[p + CORNER_OFFSETS[c]];
						corner[c] = v;
						if (v > threshold) {
							mask |= 1 << c;
						}
					}
					if (mask == 0 || mask == 0xFF) {
						continue;
					}

					if (surface == null) {
						surface = new BrickSurface();
						surface.key = key;
						surface.localVertex = new int[BRICK_VOLUME];
						Arrays.fill(surface.localVertex, -1);
					}
					int local = localIndex(lx, ly, lz);
					surface.localVertex[local] = addVertex(surface, baseX + lx, baseY + ly, baseZ + lz, corner, velocity, raw, p);

					boolean inside = (mask & 1) != 0;
					int flags = inside ? INSIDE : 0;
					if (inside != ((mask & 0b10) != 0)) {
						flags |= EDGE_X;
					}
					if (inside != ((mask & 0b100) != 0)) {
						flags |= EDGE_Y;
					}
					if (inside != ((mask & 0b10000) != 0)) {
						flags |= EDGE_Z;
					}
					if ((flags & (EDGE_X | EDGE_Y | EDGE_Z)) != 0) {
						if (surface.quadCells.length <= surface.quadCellCount) {
							surface.quadCells = Arrays.copyOf(surface.quadCells, Math.max(64, surface.quadCells.length * 2));
						}
						surface.quadCells[surface.quadCellCount++] = local | (flags << 12);
					}
				}
			}
		}
		return surface;
	}

	private int addVertex(BrickSurface surface, int x, int y, int z, float[] corner, float[] velocity, float[] raw, int p) {
		// Average of the edge crossings, in cell-local coordinates.
		float sx = 0;
		float sy = 0;
		float sz = 0;
		int crossings = 0;
		for (int[] edge : EDGES) {
			float a = corner[edge[0]];
			float b = corner[edge[1]];
			if ((a > threshold) == (b > threshold)) {
				continue;
			}
			float t = (threshold - a) / (b - a);
			int[] ca = CORNERS[edge[0]];
			int[] cb = CORNERS[edge[1]];
			sx += ca[0] + t * (cb[0] - ca[0]);
			sy += ca[1] + t * (cb[1] - ca[1]);
			sz += ca[2] + t * (cb[2] - ca[2]);
			crossings++;
		}
		sx /= crossings;
		sy /= crossings;
		sz /= crossings;

		// Density gradient over the cell; the normal points out of the water (towards lower density).
		float gx = (corner[1] - corner[0]) + (corner[3] - corner[2]) + (corner[5] - corner[4]) + (corner[7] - corner[6]);
		float gy = (corner[2] - corner[0]) + (corner[3] - corner[1]) + (corner[6] - corner[4]) + (corner[7] - corner[5]);
		float gz = (corner[4] - corner[0]) + (corner[5] - corner[1]) + (corner[6] - corner[2]) + (corner[7] - corner[3]);
		float len = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
		float nx = 0;
		float ny = 1;
		float nz = 0;
		if (len > 1e-6f) {
			nx = -gx / len;
			ny = -gy / len;
			nz = -gz / len;
		}

		// Average of the flow velocity at the cell corners, weighted by the particles there.
		float densitySum = 0;
		float vx = 0;
		float vy = 0;
		float vz = 0;
		for (int c = 0; c < 8; c++) {
			int i = p + CORNER_OFFSETS[c];
			float w = raw[i];
			densitySum += w;
			vx += w * velocity[i * 3];
			vy += w * velocity[i * 3 + 1];
			vz += w * velocity[i * 3 + 2];
		}
		if (densitySum > 1e-6f) {
			vx /= densitySum;
			vy /= densitySum;
			vz /= densitySum;
		}

		int index = surface.vertexCount++;
		int needed = surface.vertexCount * VERTEX_STRIDE;
		if (surface.vertices.length < needed) {
			surface.vertices = Arrays.copyOf(surface.vertices, Math.max(needed, surface.vertices.length * 2));
		}
		int o = index * VERTEX_STRIDE;
		float[] v = surface.vertices;
		v[o] = (x + sx) * cellSize;
		v[o + 1] = (y + sy) * cellSize;
		v[o + 2] = (z + sz) * cellSize;
		v[o + 3] = nx;
		v[o + 4] = ny;
		v[o + 5] = nz;
		v[o + 6] = vx;
		v[o + 7] = vy;
		v[o + 8] = vz;
		v[o + 9] = thickness(v[o], v[o + 1], v[o + 2], nx, ny, nz);
		return index;
	}

	/**
	 * How far the water extends behind a surface point, measured from the point into the water
	 * (against the normal) in steps of two cells until the field drops below the threshold. On a
	 * pool surface this is the depth of the water; it drives depth-dependent colour and transparency.
	 */
	private float thickness(float x, float y, float z, float nx, float ny, float nz) {
		float step = cellSize * 2f;
		float inv = 1f / cellSize;
		float px = x - nx * cellSize * 0.5f;
		float py = y - ny * cellSize * 0.5f;
		float pz = z - nz * cellSize * 0.5f;
		long lastKey = Long.MIN_VALUE;
		float[] brick = null;
		int steps = 0;
		while (steps < MAX_THICKNESS_STEPS) {
			int gx = Math.round(px * inv);
			int gy = Math.round(py * inv);
			int gz = Math.round(pz * inv);
			long key = brickKey(gx >> BRICK_BITS, gy >> BRICK_BITS, gz >> BRICK_BITS);
			if (key != lastKey) {
				lastKey = key;
				brick = bricks.get(key);
			}
			if (brick == null || brick[localIndex(gx & BRICK_MASK, gy & BRICK_MASK, gz & BRICK_MASK) * CHANNELS] <= threshold) {
				break;
			}
			px -= nx * step;
			py -= ny * step;
			pz -= nz * step;
			steps++;
		}
		return steps * step;
	}

	/**
	 * One quad per grid edge crossed by the surface. Each edge is owned by the cell whose minimum
	 * corner it starts from; the four cells around the edge provide the quad corners.
	 */
	private static void quads(BrickSurface surface, BrickSurface[] surfaces, Long2IntOpenHashMap surfaceIndex) {
		int baseX = brickX(surface.key) << BRICK_BITS;
		int baseY = brickY(surface.key) << BRICK_BITS;
		int baseZ = brickZ(surface.key) << BRICK_BITS;
		VertexLookup lookup = new VertexLookup(surfaces, surfaceIndex);

		for (int n = 0; n < surface.quadCellCount; n++) {
			int packed = surface.quadCells[n];
			int local = packed & 0xFFF;
			int flags = packed >>> 12;
			int x = baseX + (local & BRICK_MASK);
			int y = baseY + ((local >> BRICK_BITS) & BRICK_MASK);
			int z = baseZ + (local >> (2 * BRICK_BITS));
			boolean flip = (flags & INSIDE) == 0;

			if ((flags & EDGE_X) != 0) {
				addQuad(surface, flip, lookup.get(x, y, z), lookup.get(x, y - 1, z), lookup.get(x, y - 1, z - 1), lookup.get(x, y, z - 1));
			}
			if ((flags & EDGE_Y) != 0) {
				addQuad(surface, flip, lookup.get(x, y, z), lookup.get(x, y, z - 1), lookup.get(x - 1, y, z - 1), lookup.get(x - 1, y, z));
			}
			if ((flags & EDGE_Z) != 0) {
				addQuad(surface, flip, lookup.get(x, y, z), lookup.get(x - 1, y, z), lookup.get(x - 1, y - 1, z), lookup.get(x, y - 1, z));
			}
		}
	}

	/** Global vertex index of a cell, with a one-brick cache. Read-only, safe to use concurrently. */
	private static final class VertexLookup {
		private final BrickSurface[] surfaces;
		private final Long2IntOpenHashMap surfaceIndex;
		private long key = Long.MIN_VALUE;
		private BrickSurface current;

		VertexLookup(BrickSurface[] surfaces, Long2IntOpenHashMap surfaceIndex) {
			this.surfaces = surfaces;
			this.surfaceIndex = surfaceIndex;
		}

		int get(int x, int y, int z) {
			long k = brickKey(x >> BRICK_BITS, y >> BRICK_BITS, z >> BRICK_BITS);
			if (k != key) {
				key = k;
				int index = surfaceIndex.get(k);
				current = index < 0 ? null : surfaces[index];
			}
			if (current == null) {
				return -1;
			}
			int local = current.localVertex[localIndex(x & BRICK_MASK, y & BRICK_MASK, z & BRICK_MASK)];
			return local < 0 ? -1 : current.vertexOffset + local;
		}
	}

	/**
	 * @param flip whether the water lies on the positive side of the edge; front faces always point
	 *             out of the water
	 */
	private static void addQuad(BrickSurface surface, boolean flip, int v0, int v1, int v2, int v3) {
		if (v0 < 0 || v1 < 0 || v2 < 0 || v3 < 0) {
			return; // cannot happen for a closed field, but never emit a broken quad
		}
		int needed = (surface.quadCount + 1) * 4;
		if (surface.quads.length < needed) {
			surface.quads = Arrays.copyOf(surface.quads, Math.max(needed, surface.quads.length * 2));
		}
		int o = surface.quadCount * 4;
		if (flip) {
			surface.quads[o] = v0;
			surface.quads[o + 1] = v3;
			surface.quads[o + 2] = v2;
			surface.quads[o + 3] = v1;
		} else {
			surface.quads[o] = v0;
			surface.quads[o + 1] = v1;
			surface.quads[o + 2] = v2;
			surface.quads[o + 3] = v3;
		}
		surface.quadCount++;
	}

	// ------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------

	private void parallelFor(int n, IntConsumer body) {
		if (pool == null || n <= 1) {
			for (int i = 0; i < n; i++) {
				body.accept(i);
			}
			return;
		}
		try {
			pool.submit(() -> IntStream.range(0, n).parallel().forEach(body)).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while meshing", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(cause);
		}
	}

	private static final int COORD_BITS = 21;
	private static final long COORD_MASK = (1L << COORD_BITS) - 1;

	private static long brickKey(int bx, int by, int bz) {
		return ((bx & COORD_MASK) << (2 * COORD_BITS)) | ((by & COORD_MASK) << COORD_BITS) | (bz & COORD_MASK);
	}

	private static int unpack(long key, int shift) {
		int v = (int) ((key >>> shift) & COORD_MASK);
		return (v << (32 - COORD_BITS)) >> (32 - COORD_BITS); // sign-extend
	}

	private static int brickX(long key) {
		return unpack(key, 2 * COORD_BITS);
	}

	private static int brickY(long key) {
		return unpack(key, COORD_BITS);
	}

	private static int brickZ(long key) {
		return unpack(key, 0);
	}

	private static int localIndex(int x, int y, int z) {
		return (z * BRICK + y) * BRICK + x;
	}

	private static int padIndex(int x, int y, int z) {
		return (z * PAD + y) * PAD + x;
	}
}
