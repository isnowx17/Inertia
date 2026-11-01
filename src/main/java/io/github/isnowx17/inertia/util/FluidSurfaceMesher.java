package io.github.isnowx17.inertia.util;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * Turns fluid particles into one continuous water surface.
 *
 * <p>Every particle adds a smooth kernel {@code (1 - d²/R²)³} to a sparse density grid, together
 * with its kernel-weighted velocity. The surface is the iso-surface {@code density = threshold},
 * extracted with naive <em>surface nets</em>: one vertex per grid cell crossed by the surface (at the
 * average of the edge crossings) and one quad per grid edge crossed by the surface. Neighbouring
 * particles therefore melt into a single smooth sheet instead of being drawn as separate balls.
 *
 * <p>Each vertex carries a normal (from the density gradient) and the local flow velocity, which
 * the renderer uses to move the surface between rebuilds and to draw foam.
 *
 * <p>The grid is stored in 16³ bricks. Splatting and extraction are split by brick, so every brick
 * is written by exactly one task and the work runs in parallel on the given pool without locks.
 * Instances are not thread-safe: one {@link #mesh} call at a time.
 */
public final class FluidSurfaceMesher {
	/** Floats per output vertex: x, y, z, nx, ny, nz, vx, vy, vz. */
	public static final int VERTEX_STRIDE = 9;

	private static final int BRICK_BITS = 4;
	private static final int BRICK = 1 << BRICK_BITS;
	private static final int BRICK_MASK = BRICK - 1;
	private static final int BRICK_VOLUME = BRICK * BRICK * BRICK;
	/** Floats per node in a brick: density and the weighted velocity sum. */
	private static final int CHANNELS = 4;
	private static final int PAD = BRICK + 1;
	private static final int PAD_VOLUME = PAD * PAD * PAD;
	/** Particles below this fraction of the threshold count as isolated spray. */
	private static final float SPARSE_FRACTION = 0.5f;

	private final float cellSize;
	private final float radius;
	private final float threshold;
	private final ForkJoinPool pool;

	/** Per brick: CHANNELS floats per node, node-major. */
	private Long2ObjectOpenHashMap<float[]> bricks = new Long2ObjectOpenHashMap<>();

	private final ThreadLocal<float[]> padDensity = ThreadLocal.withInitial(() -> new float[PAD_VOLUME]);
	private final ThreadLocal<float[]> padVelocity = ThreadLocal.withInitial(() -> new float[PAD_VOLUME * 3]);

	/**
	 * @param cellSize  grid spacing in blocks
	 * @param radius    kernel radius in blocks; must be smaller than 16 cells
	 * @param threshold iso-value of the surface; see {@link #restDensity}
	 * @param pool      pool for parallel work, or {@code null} to run on the calling thread
	 */
	public FluidSurfaceMesher(float cellSize, float radius, float threshold, ForkJoinPool pool) {
		if (!(cellSize > 0f) || !(radius > 0f) || !(threshold > 0f)) {
			throw new IllegalArgumentException("cellSize, radius and threshold must be positive");
		}
		if (radius >= cellSize * BRICK) {
			throw new IllegalArgumentException("radius must be smaller than " + BRICK + " cells");
		}
		this.cellSize = cellSize;
		this.radius = radius;
		this.threshold = threshold;
		this.pool = pool;
	}

	public FluidSurfaceMesher(float cellSize, float radius, float threshold) {
		this(cellSize, radius, threshold, null);
	}

	/**
	 * Average field density inside a body of water whose particles sit on a lattice with the given
	 * spacing. Useful to pick a threshold as a fraction of the "full water" density.
	 */
	public static float restDensity(float particleSpacing, float radius) {
		int reach = (int) Math.ceil(radius / particleSpacing) + 1;
		float r2 = radius * radius;
		float total = 0;
		int samples = 0;
		for (float ox = 0f; ox < 1f; ox += 0.25f) {
			for (float oy = 0f; oy < 1f; oy += 0.25f) {
				for (float oz = 0f; oz < 1f; oz += 0.25f) {
					float sum = 0;
					for (int i = -reach; i <= reach; i++) {
						for (int j = -reach; j <= reach; j++) {
							for (int k = -reach; k <= reach; k++) {
								float dx = (i + ox) * particleSpacing;
								float dy = (j + oy) * particleSpacing;
								float dz = (k + oz) * particleSpacing;
								float d2 = dx * dx + dy * dy + dz * dz;
								if (d2 < r2) {
									float t = 1f - d2 / r2;
									sum += t * t * t;
								}
							}
						}
					}
					total += sum;
					samples++;
				}
			}
		}
		return total / samples;
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

	public float threshold() {
		return threshold;
	}

	/**
	 * Builds the surface of {@code count} particles.
	 *
	 * @param positions  xyz per particle
	 * @param velocities xyz per particle (blocks/second), or {@code null}
	 * @param target     mesh to fill (reused to avoid allocations), or {@code null} for a new one
	 */
	public Mesh mesh(float[] positions, float[] velocities, int count, Mesh target) {
		Mesh mesh = target != null ? target : new Mesh();
		mesh.vertexCount = 0;
		mesh.quadCount = 0;

		bricks = splat(positions, velocities, count);
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

	/** Kernel-averaged flow velocity at the grid node nearest to a point, written to {@code out}. */
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
		float d = brick[o];
		if (d < 1e-6f) {
			out[0] = out[1] = out[2] = 0f;
			return;
		}
		out[0] = brick[o + 1] / d;
		out[1] = brick[o + 2] / d;
		out[2] = brick[o + 3] / d;
	}

	/**
	 * Indices of the particles in regions too sparse to form a surface (isolated spray droplets).
	 * Particles at the corners of a body of water sit just outside the iso-surface too; the lower
	 * cut-off keeps those out so they are not drawn twice.
	 */
	public int[] sparseParticles(float[] positions, int count) {
		float cutoff = threshold * SPARSE_FRACTION;
		int chunks = Math.max(1, Math.min(64, count / 4096));
		IntArrayList[] parts = new IntArrayList[chunks];
		parallelFor(chunks, c -> {
			IntArrayList list = new IntArrayList();
			int from = (int) ((long) count * c / chunks);
			int to = (int) ((long) count * (c + 1) / chunks);
			for (int i = from; i < to; i++) {
				if (densityAt(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]) < cutoff) {
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

	// ------------------------------------------------------------------------------------------
	// Density field
	// ------------------------------------------------------------------------------------------

	private Long2ObjectOpenHashMap<float[]> splat(float[] positions, float[] velocities, int count) {
		// 1. Bin particle indices by the brick their position lies in. Each chunk of particles gets
		//    its own bin map so binning runs in parallel without merging.
		float inv = 1f / cellSize;
		int chunks = Math.max(1, Math.min(64, count / 8192));
		@SuppressWarnings("unchecked")
		Long2ObjectOpenHashMap<IntArrayList>[] bins = new Long2ObjectOpenHashMap[chunks];
		parallelFor(chunks, c -> {
			Long2ObjectOpenHashMap<IntArrayList> map = new Long2ObjectOpenHashMap<>();
			int from = (int) ((long) count * c / chunks);
			int to = (int) ((long) count * (c + 1) / chunks);
			long lastKey = Long.MIN_VALUE;
			IntArrayList last = null;
			for (int i = from; i < to; i++) {
				float px = positions[i * 3];
				float py = positions[i * 3 + 1];
				float pz = positions[i * 3 + 2];
				if (!Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(pz)) {
					continue;
				}
				long key = brickKey(
					floorDiv16((int) Math.floor(px * inv)),
					floorDiv16((int) Math.floor(py * inv)),
					floorDiv16((int) Math.floor(pz * inv))
				);
				if (key != lastKey) {
					lastKey = key;
					last = map.get(key);
					if (last == null) {
						last = new IntArrayList();
						map.put(key, last);
					}
				}
				last.add(i);
			}
			bins[c] = map;
		});

		// 2. Every brick within one brick of a particle may receive density.
		LongOpenHashSet targetSet = new LongOpenHashSet();
		for (Long2ObjectOpenHashMap<IntArrayList> map : bins) {
			for (long key : map.keySet()) {
				int bx = brickX(key);
				int by = brickY(key);
				int bz = brickZ(key);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						for (int dz = -1; dz <= 1; dz++) {
							targetSet.add(brickKey(bx + dx, by + dy, bz + dz));
						}
					}
				}
			}
		}
		long[] targets = targetSet.toLongArray();

		// 3. Fill each target brick from the particles in it and its neighbours. One task per brick,
		//    so no two tasks ever write the same memory.
		float[][] filled = new float[targets.length][];
		float r2 = radius * radius;
		float invR2 = 1f / r2;
		parallelFor(targets.length, t -> {
			long key = targets[t];
			int bx = brickX(key);
			int by = brickY(key);
			int bz = brickZ(key);
			int minX = bx << BRICK_BITS;
			int minY = by << BRICK_BITS;
			int minZ = bz << BRICK_BITS;
			int maxX = minX + BRICK - 1;
			int maxY = minY + BRICK - 1;
			int maxZ = minZ + BRICK - 1;
			float[] brick = null;

			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						long source = brickKey(bx + dx, by + dy, bz + dz);
						for (Long2ObjectOpenHashMap<IntArrayList> map : bins) {
							IntArrayList list = map.get(source);
							if (list == null) {
								continue;
							}
							for (int n = 0, size = list.size(); n < size; n++) {
								int i = list.getInt(n);
								float px = positions[i * 3];
								float py = positions[i * 3 + 1];
								float pz = positions[i * 3 + 2];

								int x0 = Math.max(minX, (int) Math.ceil((px - radius) * inv));
								int x1 = Math.min(maxX, (int) Math.floor((px + radius) * inv));
								int y0 = Math.max(minY, (int) Math.ceil((py - radius) * inv));
								int y1 = Math.min(maxY, (int) Math.floor((py + radius) * inv));
								int z0 = Math.max(minZ, (int) Math.ceil((pz - radius) * inv));
								int z1 = Math.min(maxZ, (int) Math.floor((pz + radius) * inv));
								if (x0 > x1 || y0 > y1 || z0 > z1) {
									continue;
								}

								float vx = velocities != null ? velocities[i * 3] : 0f;
								float vy = velocities != null ? velocities[i * 3 + 1] : 0f;
								float vz = velocities != null ? velocities[i * 3 + 2] : 0f;

								for (int nz = z0; nz <= z1; nz++) {
									float ddz = nz * cellSize - pz;
									float dz2 = ddz * ddz;
									for (int ny = y0; ny <= y1; ny++) {
										float ddy = ny * cellSize - py;
										float dyz = ddy * ddy + dz2;
										if (dyz >= r2) {
											continue;
										}
										for (int nx = x0; nx <= x1; nx++) {
											float ddx = nx * cellSize - px;
											float d2 = ddx * ddx + dyz;
											if (d2 >= r2) {
												continue;
											}
											float w = 1f - d2 * invR2;
											w = w * w * w;
											if (brick == null) {
												brick = new float[BRICK_VOLUME * CHANNELS];
											}
											int o = localIndex(nx - minX, ny - minY, nz - minZ) * CHANNELS;
											brick[o] += w;
											brick[o + 1] += w * vx;
											brick[o + 2] += w * vy;
											brick[o + 3] += w * vz;
										}
									}
								}
							}
						}
					}
				}
			}
			filled[t] = brick;
		});

		Long2ObjectOpenHashMap<float[]> result = new Long2ObjectOpenHashMap<>();
		for (int t = 0; t < targets.length; t++) {
			if (filled[t] != null) {
				result.put(targets[t], filled[t]);
			}
		}
		return result;
	}

	/** Copies a brick plus its one-node border on the positive sides into the padded arrays. */
	private void loadPadded(int bx, int by, int bz, float[] density, float[] velocity) {
		Arrays.fill(density, 0f);
		Arrays.fill(velocity, 0f);
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
							}
						}
					}
				}
			}
		}
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
		parallelFor(cellBricks.length, b -> surfaces[b] = vertices(cellBricks[b]));

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
		loadPadded(bx, by, bz, density, velocity);

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
					surface.localVertex[local] = addVertex(surface, baseX + lx, baseY + ly, baseZ + lz, corner, velocity, p);

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

	private int addVertex(BrickSurface surface, int x, int y, int z, float[] corner, float[] velocity, int p) {
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

		// Density-weighted flow velocity around the cell.
		float densitySum = 0;
		float vx = 0;
		float vy = 0;
		float vz = 0;
		for (int c = 0; c < 8; c++) {
			int i = p + CORNER_OFFSETS[c];
			densitySum += corner[c];
			vx += velocity[i * 3];
			vy += velocity[i * 3 + 1];
			vz += velocity[i * 3 + 2];
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
		return index;
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

	private static int floorDiv16(int n) {
		return n >> BRICK_BITS;
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
