package io.github.isnowx17.inertia.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;

/**
 * Turns fluid particles into one continuous water surface.
 *
 * <p>Every particle adds a smooth kernel {@code (1 - d²/R²)³} to a sparse density grid. The surface
 * is the iso-surface {@code density = threshold} of that field, extracted with naive
 * <em>surface nets</em>: one vertex per grid cell crossed by the surface (at the average of the edge
 * crossings) and one quad per grid edge crossed by the surface. Neighbouring particles therefore
 * melt into a single smooth sheet instead of being drawn as separate balls.
 *
 * <p>Normals come from the density gradient, so the surface is smoothly shaded. Each vertex also
 * carries a "foam" value: the density-weighted average particle speed around it.
 *
 * <p>The grid is stored in 16³ bricks; for extraction each brick is copied into a padded dense
 * array so the inner loops only do array reads. Instances are not thread-safe.
 */
public final class FluidSurfaceMesher {
	/** Floats per output vertex: x, y, z, nx, ny, nz, speed. */
	public static final int VERTEX_STRIDE = 7;

	private static final int BRICK_BITS = 4;
	private static final int BRICK = 1 << BRICK_BITS;
	private static final int BRICK_MASK = BRICK - 1;
	private static final int BRICK_VOLUME = BRICK * BRICK * BRICK;
	private static final int PAD = BRICK + 1;
	private static final int PAD_VOLUME = PAD * PAD * PAD;

	private final float cellSize;
	private final float radius;
	private final float threshold;

	/** Per brick: {@code BRICK_VOLUME} densities followed by {@code BRICK_VOLUME} weighted speeds. */
	private final Long2ObjectOpenHashMap<float[]> bricks = new Long2ObjectOpenHashMap<>();
	/** Per brick: vertex index of every cell whose minimum corner lies in the brick, or -1. */
	private final Long2ObjectOpenHashMap<int[]> brickVertices = new Long2ObjectOpenHashMap<>();
	private final float[] padDensity = new float[PAD_VOLUME];
	private final float[] padSpeed = new float[PAD_VOLUME];

	/**
	 * @param cellSize  grid spacing in blocks
	 * @param radius    kernel radius in blocks; larger values give a smoother, rounder surface
	 * @param threshold iso-value of the surface; see {@link #restDensity}
	 */
	public FluidSurfaceMesher(float cellSize, float radius, float threshold) {
		if (!(cellSize > 0f) || !(radius > 0f) || !(threshold > 0f)) {
			throw new IllegalArgumentException("cellSize, radius and threshold must be positive");
		}
		this.cellSize = cellSize;
		this.radius = radius;
		this.threshold = threshold;
	}

	/**
	 * Average field density inside a body of water whose particles sit on a lattice with the given
	 * spacing. Useful to pick a threshold as a fraction of the "full water" density.
	 */
	public static float restDensity(float particleSpacing, float radius) {
		// Average the kernel sum over a few sample points inside the particle lattice.
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

	/**
	 * Builds the surface of {@code count} particles.
	 *
	 * @param positions xyz per particle
	 * @param speeds    speed per particle (blocks/second), or {@code null}
	 * @param target    mesh to fill (reused to avoid allocations), or {@code null} for a new one
	 */
	public Mesh mesh(float[] positions, float[] speeds, int count, Mesh target) {
		Mesh mesh = target != null ? target : new Mesh();
		mesh.vertexCount = 0;
		mesh.quadCount = 0;
		bricks.clear();
		brickVertices.clear();

		splat(positions, speeds, count);
		if (bricks.isEmpty()) {
			return mesh;
		}
		addNegativeNeighbourBricks();
		extract(mesh);
		return mesh;
	}

	/**
	 * Field density at the grid node nearest to a point, valid after {@link #mesh}. Particles in
	 * regions below the threshold are not covered by the surface (isolated spray droplets).
	 */
	public float densityAt(float x, float y, float z) {
		int nx = Math.round(x / cellSize);
		int ny = Math.round(y / cellSize);
		int nz = Math.round(z / cellSize);
		float[] brick = bricks.get(brickKey(nx >> BRICK_BITS, ny >> BRICK_BITS, nz >> BRICK_BITS));
		return brick == null ? 0f : brick[localIndex(nx & BRICK_MASK, ny & BRICK_MASK, nz & BRICK_MASK)];
	}

	public float threshold() {
		return threshold;
	}

	// ------------------------------------------------------------------------------------------
	// Density field
	// ------------------------------------------------------------------------------------------

	private void splat(float[] positions, float[] speeds, int count) {
		float inv = 1f / cellSize;
		float r2 = radius * radius;
		float invR2 = 1f / r2;
		long lastKey = Long.MIN_VALUE;
		float[] lastBrick = null;

		for (int i = 0; i < count; i++) {
			float px = positions[i * 3];
			float py = positions[i * 3 + 1];
			float pz = positions[i * 3 + 2];
			if (!Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(pz)) {
				continue;
			}
			float s = speeds != null ? speeds[i] : 0f;

			int x0 = (int) Math.ceil((px - radius) * inv);
			int x1 = (int) Math.floor((px + radius) * inv);
			int y0 = (int) Math.ceil((py - radius) * inv);
			int y1 = (int) Math.floor((py + radius) * inv);
			int z0 = (int) Math.ceil((pz - radius) * inv);
			int z1 = (int) Math.floor((pz + radius) * inv);

			for (int nz = z0; nz <= z1; nz++) {
				float dz = nz * cellSize - pz;
				float dz2 = dz * dz;
				for (int ny = y0; ny <= y1; ny++) {
					float dy = ny * cellSize - py;
					float dyz = dy * dy + dz2;
					if (dyz >= r2) {
						continue;
					}
					for (int nx = x0; nx <= x1; nx++) {
						float dx = nx * cellSize - px;
						float d2 = dx * dx + dyz;
						if (d2 >= r2) {
							continue;
						}
						float t = 1f - d2 * invR2;
						float w = t * t * t;
						long key = brickKey(nx >> BRICK_BITS, ny >> BRICK_BITS, nz >> BRICK_BITS);
						if (key != lastKey) {
							lastKey = key;
							lastBrick = brick(key);
						}
						int local = localIndex(nx & BRICK_MASK, ny & BRICK_MASK, nz & BRICK_MASK);
						lastBrick[local] += w;
						lastBrick[BRICK_VOLUME + local] += w * s;
					}
				}
			}
		}
	}

	private float[] brick(long key) {
		float[] brick = bricks.get(key);
		if (brick == null) {
			brick = new float[BRICK_VOLUME * 2];
			bricks.put(key, brick);
		}
		return brick;
	}

	/**
	 * Cells are visited by their minimum corner, brick by brick. A cell whose minimum corner lies in
	 * an empty brick can still touch a filled brick on its positive side, so make sure the bricks on
	 * the negative side of every filled brick exist.
	 */
	private void addNegativeNeighbourBricks() {
		LongArrayList keys = new LongArrayList(bricks.keySet());
		for (int i = 0; i < keys.size(); i++) {
			long key = keys.getLong(i);
			int bx = brickX(key);
			int by = brickY(key);
			int bz = brickZ(key);
			for (int dx = -1; dx <= 0; dx++) {
				for (int dy = -1; dy <= 0; dy++) {
					for (int dz = -1; dz <= 0; dz++) {
						brick(brickKey(bx + dx, by + dy, bz + dz));
					}
				}
			}
		}
	}

	/** Copies a brick plus its one-node border on the positive sides into the padded arrays. */
	private void loadPadded(int bx, int by, int bz) {
		Arrays.fill(padDensity, 0f);
		Arrays.fill(padSpeed, 0f);
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
								int s = localIndex(x - xFrom, y - yFrom, z - zFrom);
								int d = padIndex(x, y, z);
								padDensity[d] = src[s];
								padSpeed[d] = src[BRICK_VOLUME + s];
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

	/** Edge flags of a vertex cell: which of its three minimum-corner edges cross the surface. */
	private static final int EDGE_X = 1;
	private static final int EDGE_Y = 2;
	private static final int EDGE_Z = 4;
	/** Set when the minimum corner is inside the water. */
	private static final int INSIDE = 8;

	private void extract(Mesh mesh) {
		float[] corner = new float[8];
		LongArrayList quadCells = new LongArrayList();
		it.unimi.dsi.fastutil.ints.IntArrayList quadFlags = new it.unimi.dsi.fastutil.ints.IntArrayList();

		// Pass 1: one vertex per cell crossed by the surface.
		for (Long2ObjectMap.Entry<float[]> entry : bricks.long2ObjectEntrySet()) {
			long key = entry.getLongKey();
			int bx = brickX(key);
			int by = brickY(key);
			int bz = brickZ(key);
			loadPadded(bx, by, bz);

			boolean anyInside = false;
			boolean anyOutside = false;
			for (int i = 0; i < PAD_VOLUME && !(anyInside && anyOutside); i++) {
				if (padDensity[i] > threshold) {
					anyInside = true;
				} else {
					anyOutside = true;
				}
			}
			if (!anyInside || !anyOutside) {
				continue;
			}

			int[] vertexIndex = null;
			int baseX = bx << BRICK_BITS;
			int baseY = by << BRICK_BITS;
			int baseZ = bz << BRICK_BITS;

			for (int lz = 0; lz < BRICK; lz++) {
				for (int ly = 0; ly < BRICK; ly++) {
					for (int lx = 0; lx < BRICK; lx++) {
						int p = padIndex(lx, ly, lz);
						int mask = 0;
						for (int c = 0; c < 8; c++) {
							float v = padDensity[p + CORNER_OFFSETS[c]];
							corner[c] = v;
							if (v > threshold) {
								mask |= 1 << c;
							}
						}
						if (mask == 0 || mask == 0xFF) {
							continue;
						}

						float speedSum = 0;
						float densitySum = 0;
						for (int c = 0; c < 8; c++) {
							densitySum += corner[c];
							speedSum += padSpeed[p + CORNER_OFFSETS[c]];
						}

						if (vertexIndex == null) {
							vertexIndex = new int[BRICK_VOLUME];
							Arrays.fill(vertexIndex, -1);
							brickVertices.put(key, vertexIndex);
						}
						vertexIndex[localIndex(lx, ly, lz)] =
							addVertex(mesh, baseX + lx, baseY + ly, baseZ + lz, corner, densitySum > 1e-6f ? speedSum / densitySum : 0f);

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
							quadCells.add(cellKey(baseX + lx, baseY + ly, baseZ + lz));
							quadFlags.add(flags);
						}
					}
				}
			}
		}

		// Pass 2: one quad per grid edge crossed by the surface. Each edge is owned by the cell whose
		// minimum corner it starts from; the four cells around the edge provide the quad corners.
		VertexLookup lookup = new VertexLookup();
		for (int i = 0; i < quadCells.size(); i++) {
			long cell = quadCells.getLong(i);
			int flags = quadFlags.getInt(i);
			int x = cellX(cell);
			int y = cellY(cell);
			int z = cellZ(cell);
			boolean flip = (flags & INSIDE) == 0;

			if ((flags & EDGE_X) != 0) {
				addQuad(mesh, flip, lookup.get(x, y, z), lookup.get(x, y - 1, z), lookup.get(x, y - 1, z - 1), lookup.get(x, y, z - 1));
			}
			if ((flags & EDGE_Y) != 0) {
				addQuad(mesh, flip, lookup.get(x, y, z), lookup.get(x, y, z - 1), lookup.get(x - 1, y, z - 1), lookup.get(x - 1, y, z));
			}
			if ((flags & EDGE_Z) != 0) {
				addQuad(mesh, flip, lookup.get(x, y, z), lookup.get(x - 1, y, z), lookup.get(x - 1, y - 1, z), lookup.get(x, y - 1, z));
			}
		}
	}

	/** Looks up the vertex of a cell with a one-brick cache. */
	private final class VertexLookup {
		private long key = Long.MIN_VALUE;
		private int[] indices;

		int get(int x, int y, int z) {
			long k = brickKey(x >> BRICK_BITS, y >> BRICK_BITS, z >> BRICK_BITS);
			if (k != key) {
				key = k;
				indices = brickVertices.get(k);
			}
			return indices == null ? -1 : indices[localIndex(x & BRICK_MASK, y & BRICK_MASK, z & BRICK_MASK)];
		}
	}

	private int addVertex(Mesh mesh, int x, int y, int z, float[] corner, float avgSpeed) {
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

		int index = mesh.vertexCount++;
		int needed = mesh.vertexCount * VERTEX_STRIDE;
		if (mesh.vertices.length < needed) {
			mesh.vertices = Arrays.copyOf(mesh.vertices, Math.max(needed, mesh.vertices.length * 2));
		}
		int o = index * VERTEX_STRIDE;
		mesh.vertices[o] = (x + sx) * cellSize;
		mesh.vertices[o + 1] = (y + sy) * cellSize;
		mesh.vertices[o + 2] = (z + sz) * cellSize;
		mesh.vertices[o + 3] = nx;
		mesh.vertices[o + 4] = ny;
		mesh.vertices[o + 5] = nz;
		mesh.vertices[o + 6] = avgSpeed;
		return index;
	}

	/**
	 * @param flip whether the water lies on the positive side of the edge; front faces always point
	 *             out of the water
	 */
	private static void addQuad(Mesh mesh, boolean flip, int v0, int v1, int v2, int v3) {
		if (v0 < 0 || v1 < 0 || v2 < 0 || v3 < 0) {
			return; // cannot happen for a closed field, but never emit a broken quad
		}

		int needed = (mesh.quadCount + 1) * 4;
		if (mesh.quads.length < needed) {
			mesh.quads = Arrays.copyOf(mesh.quads, Math.max(needed, mesh.quads.length * 2));
		}
		int o = mesh.quadCount * 4;
		if (flip) {
			mesh.quads[o] = v0;
			mesh.quads[o + 1] = v3;
			mesh.quads[o + 2] = v2;
			mesh.quads[o + 3] = v1;
		} else {
			mesh.quads[o] = v0;
			mesh.quads[o + 1] = v1;
			mesh.quads[o + 2] = v2;
			mesh.quads[o + 3] = v3;
		}
		mesh.quadCount++;
	}

	// ------------------------------------------------------------------------------------------
	// Key packing (21 bits per signed coordinate)
	// ------------------------------------------------------------------------------------------

	private static final int COORD_BITS = 21;
	private static final long COORD_MASK = (1L << COORD_BITS) - 1;

	private static long pack(int x, int y, int z) {
		return ((x & COORD_MASK) << (2 * COORD_BITS)) | ((y & COORD_MASK) << COORD_BITS) | (z & COORD_MASK);
	}

	private static int unpack(long key, int shift) {
		int v = (int) ((key >>> shift) & COORD_MASK);
		return (v << (32 - COORD_BITS)) >> (32 - COORD_BITS); // sign-extend
	}

	private static long brickKey(int bx, int by, int bz) {
		return pack(bx, by, bz);
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

	private static long cellKey(int x, int y, int z) {
		return pack(x, y, z);
	}

	private static int cellX(long key) {
		return unpack(key, 2 * COORD_BITS);
	}

	private static int cellY(long key) {
		return unpack(key, COORD_BITS);
	}

	private static int cellZ(long key) {
		return unpack(key, 0);
	}

	private static int localIndex(int x, int y, int z) {
		return (z * BRICK + y) * BRICK + x;
	}

	private static int padIndex(int x, int y, int z) {
		return (z * PAD + y) * PAD + x;
	}
}
