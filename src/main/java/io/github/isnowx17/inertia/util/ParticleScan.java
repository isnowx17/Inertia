package io.github.isnowx17.inertia.util;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * The per-particle work of a physics tick on the freshly read-back particles: finds the particles
 * that are lost (fell out of the world, drifted too far from the player or became invalid), sums
 * the water in the shell around the player, notes which places in the blocks of the flowing sources
 * hold a particle and collects the blocks that hold water (from a sample of the particles).
 *
 * <p>The particles are scanned in independent chunks ({@link #scan}), which can run in parallel;
 * {@link #merge} then combines the chunks in order, so the lost particles stay sorted.
 */
public final class ParticleScan {
	private ParticleScan() {
	}

	/** Inputs of one scan, shared by all its chunks (read only while scanning). */
	public static final class Settings {
		/** Particles at least this fast (squared, blocks/second) count as moving. */
		public float movingSpeedSq;
		/** Particles below this height are lost. */
		public float minY;
		/** Particles further than {@code sqrt(maxDistSq)} from this point (the player) are lost. */
		public float playerX;
		public float playerY;
		public float playerZ;
		public float maxDistSq;
		/** Whether to sum the water in the shell box around the player. */
		public boolean measurePlayer;
		public float shellMinX;
		public float shellMinY;
		public float shellMinZ;
		public float shellMaxX;
		public float shellMaxY;
		public float shellMaxZ;
		/** Particles {@code offset, offset + stride, ...} name the block they are in. */
		public int sampleStride = 1;
		public int sampleOffset;
		/** World position of the coordinate origin (block keys are in world coordinates). */
		public int originX;
		public int originY;
		public int originZ;
		/** Particle places per axis in the block of a flowing source. */
		public int perAxis = 1;
		/** Whether to count the particles in every column of blocks (see {@link Part#columns}). */
		public boolean measureColumns;
		/**
		 * The columns of blocks measured one by one (see {@link Part#colCount}): the first one and how
		 * many along x and z, relative to the origin. Size 0 measures none.
		 */
		public int gridX0;
		public int gridZ0;
		public int gridSizeX;
		public int gridSizeZ;
		/**
		 * Per measured column: the particles below this height are listed in {@link Part#freezing};
		 * {@code null} lists none.
		 */
		public float[] freezeBelow;
		/**
		 * Per measured column: the height of the frozen water there (negative infinity without), above
		 * which restless particles are counted (see {@link Part#colNearRestless}); {@code null} for none.
		 */
		public float[] frozenTop;
		/** How far above the frozen water restless particles count. */
		public float nearDepth = 0.5f;
		/**
		 * Shell boxes around other entities in which the water is measured like around the player (min
		 * x, y, z, max x, y, z each, relative to the origin), at most {@link #MAX_BODIES}.
		 */
		public float[] bodyShells = new float[0];
		public int bodyCount;
		/**
		 * Per column of the grid: one bit per body whose shell reaches into it; {@code null} without a
		 * grid, then every particle in the box around all shells is tested against every shell.
		 */
		public long[] cellBodies;
		private float bodiesX0;
		private float bodiesY0;
		private float bodiesZ0;
		private float bodiesX1;
		private float bodiesY1;
		private float bodiesZ1;

		/** Sets the body shells (see {@link #bodyShells}) and, if the grid is set, which columns they reach. */
		public void setBodies(float[] shells, int count) {
			bodyShells = shells;
			bodyCount = Math.min(count, MAX_BODIES);
			cellBodies = null;
			bodiesX0 = Float.POSITIVE_INFINITY;
			bodiesY0 = Float.POSITIVE_INFINITY;
			bodiesZ0 = Float.POSITIVE_INFINITY;
			bodiesX1 = Float.NEGATIVE_INFINITY;
			bodiesY1 = Float.NEGATIVE_INFINITY;
			bodiesZ1 = Float.NEGATIVE_INFINITY;
			for (int b = 0; b < bodyCount; b++) {
				bodiesX0 = Math.min(bodiesX0, shells[b * 6]);
				bodiesY0 = Math.min(bodiesY0, shells[b * 6 + 1]);
				bodiesZ0 = Math.min(bodiesZ0, shells[b * 6 + 2]);
				bodiesX1 = Math.max(bodiesX1, shells[b * 6 + 3]);
				bodiesY1 = Math.max(bodiesY1, shells[b * 6 + 4]);
				bodiesZ1 = Math.max(bodiesZ1, shells[b * 6 + 5]);
			}
			if (bodyCount == 0 || gridSizeX <= 0 || gridSizeZ <= 0) {
				return;
			}
			long[] cells = new long[gridSizeX * gridSizeZ];
			for (int b = 0; b < bodyCount; b++) {
				int x0 = Math.max(0, Mth.floor(shells[b * 6]) - gridX0);
				int z0 = Math.max(0, Mth.floor(shells[b * 6 + 2]) - gridZ0);
				int x1 = Math.min(gridSizeX - 1, Mth.floor(shells[b * 6 + 3]) - gridX0);
				int z1 = Math.min(gridSizeZ - 1, Mth.floor(shells[b * 6 + 5]) - gridZ0);
				for (int x = x0; x <= x1; x++) {
					for (int z = z0; z <= z1; z++) {
						cells[x * gridSizeZ + z] |= 1L << b;
					}
				}
			}
			cellBodies = cells;
		}

		/** Particles at least this fast (squared) count as not calm, and as restless (see {@link Part#colFast}). */
		public float calmSpeedSq = 0.25f;
		public float restlessSpeedSq = 4f;
		/** Blocks of the flowing sources, relative to the origin. */
		private int[] sourceX = new int[0];
		private int[] sourceY = new int[0];
		private int[] sourceZ = new int[0];
		private float boxX0;
		private float boxY0;
		private float boxZ0;
		private float boxX1;
		private float boxY1;
		private float boxZ1;

		public void setSources(int[] x, int[] y, int[] z) {
			sourceX = x;
			sourceY = y;
			sourceZ = z;
			boxX0 = Float.POSITIVE_INFINITY;
			boxY0 = Float.POSITIVE_INFINITY;
			boxZ0 = Float.POSITIVE_INFINITY;
			boxX1 = Float.NEGATIVE_INFINITY;
			boxY1 = Float.NEGATIVE_INFINITY;
			boxZ1 = Float.NEGATIVE_INFINITY;
			for (int k = 0; k < x.length; k++) {
				boxX0 = Math.min(boxX0, x[k]);
				boxY0 = Math.min(boxY0, y[k]);
				boxZ0 = Math.min(boxZ0, z[k]);
				boxX1 = Math.max(boxX1, x[k] + 1);
				boxY1 = Math.max(boxY1, y[k] + 1);
				boxZ1 = Math.max(boxZ1, z[k] + 1);
			}
		}

		public int sourceCount() {
			return sourceX.length;
		}
	}

	/** Most bodies measured in one scan (see {@link Settings#bodyShells}). */
	public static final int MAX_BODIES = 64;

	/** Highest particles kept per column (see {@link Part#colTop}). */
	public static final int TOP = 32;

	/** What a chunk (or, after {@link #merge}, the whole scan) found. */
	public static final class Part {
		/** Indices of the lost particles, ascending. */
		public final IntArrayList lost = new IntArrayList();
		/** Blocks ({@link BlockPos#asLong}) that hold a sampled particle. */
		public final LongOpenHashSet blocks = new LongOpenHashSet();
		/**
		 * Particles per column of blocks ({@link #column}, relative to the origin), how deep the water is
		 * there; only when {@link Settings#measureColumns}.
		 */
		public final Long2IntOpenHashMap columns = new Long2IntOpenHashMap();
		/** Particles in the shell around the player and the sum of their velocities. */
		public int inside;
		public double sumX;
		public double sumY;
		public double sumZ;
		/** Per source: the taken places (see {@link SourceSlots}), the particles and their velocity sums. */
		public long[][] sourceSlots = new long[0][];
		public int[] sourceCount = new int[0];
		public float[] sourceSum = new float[0];
		/**
		 * Per source: the height of the highest particle in the block (0 to 1 from the block's bottom,
		 * negative infinity without particles) and that particle's vertical velocity.
		 */
		public float[] sourceTop = new float[0];
		public float[] sourceTopVy = new float[0];
		/** Particles that count as moving, and the largest squared distance of a particle from the player. */
		public int moving;
		public float farthestSq;
		/** The measured columns (as {@link Settings#gridX0} and so on when the part was reset). */
		public int gridX0;
		public int gridZ0;
		public int gridSizeX;
		public int gridSizeZ;
		/**
		 * Per measured column ({@link #cell}): its particles, the lowest and the highest one (y), and how
		 * many are not calm and how many restless ({@link Settings#calmSpeedSq}, {@link Settings#restlessSpeedSq}).
		 */
		public int[] colCount = new int[0];
		public float[] colMinY = new float[0];
		public float[] colMaxY = new float[0];
		public int[] colFast = new int[0];
		public int[] colRestless = new int[0];
		/** Per measured column: the restless particles right above the frozen water ({@link Settings#frozenTop}). */
		public int[] colNearRestless = new int[0];
		/**
		 * Per measured column, {@link #TOP} floats from {@code cell * TOP}: the heights of its highest
		 * particles that are not restless, lowest first (negative infinity for none). The lowest of them
		 * is where the water's surface is, whatever single drops fly above it.
		 */
		public float[] colTop = new float[0];
		/** Per measured column: of the particles listed in {@link #freezing}, the ones not calm and the restless ones. */
		public int[] colFreezeFast = new int[0];
		public int[] colFreezeRestless = new int[0];
		/** Per body ({@link Settings#bodyShells}): the particles in its shell and the sum of their velocities. */
		public int[] bodyInside = new int[MAX_BODIES];
		public float[] bodySum = new float[MAX_BODIES * 3];
		/** Indices of the particles below {@link Settings#freezeBelow} of their column, ascending. */
		public final IntArrayList freezing = new IntArrayList();
		/** Box around the particles that are not lost. */
		public float minX;
		public float minY;
		public float maxY;
		public float maxX;
		public float minZ;
		public float maxZ;

		/** Index of column {@code x, z} (relative to the origin) in the {@code col} arrays, or -1. */
		public int cell(int x, int z) {
			int gx = x - gridX0;
			int gz = z - gridZ0;
			return gx >= 0 && gx < gridSizeX && gz >= 0 && gz < gridSizeZ ? gx * gridSizeZ + gz : -1;
		}

		/** {@link #reset(int, int)} that measures no columns one by one. */
		public void reset(int sources, int perAxis) {
			reset(sources, perAxis, 0, 0, 0, 0);
		}

		/** Empties the part for a scan with these settings. */
		public void reset(int sources, int perAxis, Settings s) {
			reset(sources, perAxis, s.gridX0, s.gridZ0, s.gridSizeX, s.gridSizeZ);
		}

		private void reset(int sources, int perAxis, int gx0, int gz0, int gsx, int gsz) {
			gridX0 = gx0;
			gridZ0 = gz0;
			gridSizeX = gsx;
			gridSizeZ = gsz;
			int cells = gsx * gsz;
			if (colCount.length < cells) {
				colCount = new int[cells];
				colMinY = new float[cells];
				colMaxY = new float[cells];
				colFast = new int[cells];
				colRestless = new int[cells];
				colNearRestless = new int[cells];
				colTop = new float[cells * TOP];
				colFreezeFast = new int[cells];
				colFreezeRestless = new int[cells];
			}
			Arrays.fill(colCount, 0, cells, 0);
			Arrays.fill(colMinY, 0, cells, Float.POSITIVE_INFINITY);
			Arrays.fill(colMaxY, 0, cells, Float.NEGATIVE_INFINITY);
			Arrays.fill(colFast, 0, cells, 0);
			Arrays.fill(colRestless, 0, cells, 0);
			Arrays.fill(colNearRestless, 0, cells, 0);
			Arrays.fill(colTop, 0, cells * TOP, Float.NEGATIVE_INFINITY);
			Arrays.fill(colFreezeFast, 0, cells, 0);
			Arrays.fill(colFreezeRestless, 0, cells, 0);
			freezing.clear();
			Arrays.fill(bodyInside, 0);
			Arrays.fill(bodySum, 0f);
			minY = Float.POSITIVE_INFINITY;
			maxY = Float.NEGATIVE_INFINITY;
			minX = Float.POSITIVE_INFINITY;
			maxX = Float.NEGATIVE_INFINITY;
			minZ = Float.POSITIVE_INFINITY;
			maxZ = Float.NEGATIVE_INFINITY;
			lost.clear();
			blocks.clear();
			columns.clear();
			inside = 0;
			sumX = 0;
			sumY = 0;
			sumZ = 0;
			moving = 0;
			farthestSq = 0f;
			int words = SourceSlots.words(perAxis);
			if (sourceSlots.length != sources || (sources > 0 && sourceSlots[0].length != words)) {
				sourceSlots = new long[sources][words];
				sourceCount = new int[sources];
				sourceSum = new float[sources * 3];
				sourceTop = new float[sources];
				sourceTopVy = new float[sources];
			} else {
				for (long[] slots : sourceSlots) {
					Arrays.fill(slots, 0L);
				}
				Arrays.fill(sourceCount, 0);
				Arrays.fill(sourceSum, 0f);
			}
			Arrays.fill(sourceTop, Float.NEGATIVE_INFINITY);
			Arrays.fill(sourceTopVy, 0f);
		}
	}

	/**
	 * Scans particles {@code [from, to)} into {@code out} (which must be {@link Part#reset} first).
	 *
	 * @param pos    x, y, z of particle i at {@code i * stride}, relative to the origin
	 * @param vel    velocity of particle i at {@code i * stride}
	 */
	public static void scan(float[] pos, float[] vel, int stride, int from, int to, Settings s, Part out) {
		float minY = s.minY;
		float px = s.playerX;
		float py = s.playerY;
		float pz = s.playerZ;
		float maxDistSq = s.maxDistSq;
		boolean player = s.measurePlayer;
		float sx0 = s.shellMinX;
		float sy0 = s.shellMinY;
		float sz0 = s.shellMinZ;
		float sx1 = s.shellMaxX;
		float sy1 = s.shellMaxY;
		float sz1 = s.shellMaxZ;
		float boxX0 = s.boxX0;
		float boxY0 = s.boxY0;
		float boxZ0 = s.boxZ0;
		float boxX1 = s.boxX1;
		float boxY1 = s.boxY1;
		float boxZ1 = s.boxZ1;
		int stride2 = Math.max(1, s.sampleStride);
		boolean columns = s.measureColumns;
		int gx0 = s.gridX0;
		int gz0 = s.gridZ0;
		int gsx = s.gridSizeX;
		int gsz = s.gridSizeZ;
		boolean grid = gsx > 0 && gsz > 0 && out.gridSizeX == gsx && out.gridSizeZ == gsz;
		float[] freezeBelow = s.freezeBelow;
		int[] colCount = out.colCount;
		float[] colMinY = out.colMinY;
		float[] colMaxY = out.colMaxY;
		int[] colFast = out.colFast;
		int[] colRestless = out.colRestless;
		int[] colNearRestless = out.colNearRestless;
		int[] colFreezeFast = out.colFreezeFast;
		int[] colFreezeRestless = out.colFreezeRestless;
		float[] frozenTop = s.frozenTop;
		float nearDepth = s.nearDepth;
		float calmSq = s.calmSpeedSq;
		float restlessSq = s.restlessSpeedSq;
		float minX = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float minZ = Float.POSITIVE_INFINITY;
		float maxZ = Float.NEGATIVE_INFINITY;
		float lowY = Float.POSITIVE_INFINITY;
		float highY = Float.NEGATIVE_INFINITY;
		int bodies = s.bodyCount;
		float[] shells = s.bodyShells;
		long[] cellBodies = s.cellBodies;
		long allBodies = bodies >= 64 ? -1L : (1L << bodies) - 1L;
		float bx0 = s.bodiesX0;
		float by0 = s.bodiesY0;
		float bz0 = s.bodiesZ0;
		float bx1 = s.bodiesX1;
		float by1 = s.bodiesY1;
		float bz1 = s.bodiesZ1;
		int[] bodyInside = out.bodyInside;
		float[] bodySum = out.bodySum;
		long lastColumn = Long.MIN_VALUE;
		int columnRun = 0;
		// First sampled particle at or after `from`: offset, offset + stride, ...
		int sample = s.sampleOffset;
		if (from > sample) {
			sample += (from - sample + stride2 - 1) / stride2 * stride2;
		}

		int inside = 0;
		double sumX = 0;
		double sumY = 0;
		double sumZ = 0;
		long lastBlock = Long.MIN_VALUE;
		float movingSq = s.movingSpeedSq;
		int moving = 0;
		float farthest = 0f;
		for (int i = from; i < to; i++) {
			int o = i * stride;
			float x = pos[o];
			float y = pos[o + 1];
			float z = pos[o + 2];
			float dx = x - px;
			float dy = y - py;
			float dz = z - pz;
			boolean sampled = i == sample;
			if (sampled) {
				sample += stride2;
			}
			if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || y < minY
				|| dx * dx + dy * dy + dz * dz > maxDistSq) {
				out.lost.add(i);
				continue;
			}
			float distSq = dx * dx + dy * dy + dz * dz;
			if (distSq > farthest) {
				farthest = distSq;
			}
			float speedSq = vel[o] * vel[o] + vel[o + 1] * vel[o + 1] + vel[o + 2] * vel[o + 2];
			if (speedSq >= movingSq) {
				moving++;
			}
			if (y < lowY) {
				lowY = y;
			}
			if (y > highY) {
				highY = y;
			}
			if (bodies > 0 && y >= by0 && y <= by1 && x >= bx0 && x <= bx1 && z >= bz0 && z <= bz1) {
				long mask = allBodies;
				if (cellBodies != null) {
					int gx = Mth.floor(x) - gx0;
					int gz = Mth.floor(z) - gz0;
					mask = gx >= 0 && gx < gsx && gz >= 0 && gz < gsz ? cellBodies[gx * gsz + gz] : 0L;
				}
				while (mask != 0L) {
					int b = Long.numberOfTrailingZeros(mask);
					mask &= mask - 1L;
					int bo = b * 6;
					if (x >= shells[bo] && x <= shells[bo + 3] && y >= shells[bo + 1] && y <= shells[bo + 4]
						&& z >= shells[bo + 2] && z <= shells[bo + 5]) {
						bodyInside[b]++;
						bodySum[b * 3] += vel[o];
						bodySum[b * 3 + 1] += vel[o + 1];
						bodySum[b * 3 + 2] += vel[o + 2];
					}
				}
			}
			if (x < minX) {
				minX = x;
			}
			if (x > maxX) {
				maxX = x;
			}
			if (z < minZ) {
				minZ = z;
			}
			if (z > maxZ) {
				maxZ = z;
			}
			if (grid) {
				int gx = Mth.floor(x) - gx0;
				int gz = Mth.floor(z) - gz0;
				if (gx >= 0 && gx < gsx && gz >= 0 && gz < gsz) {
					int c = gx * gsz + gz;
					colCount[c]++;
					if (y < colMinY[c]) {
						colMinY[c] = y;
					}
					if (y > colMaxY[c]) {
						colMaxY[c] = y;
					}
					boolean fast = speedSq >= calmSq;
					boolean restless = speedSq >= restlessSq;
					if (!restless && y > out.colTop[c * TOP]) {
						insertTop(out.colTop, c * TOP, y);
					}
					if (fast) {
						colFast[c]++;
						if (restless) {
							colRestless[c]++;
							if (frozenTop != null && y < frozenTop[c] + nearDepth) {
								colNearRestless[c]++;
							}
						}
					}
					if (freezeBelow != null && y < freezeBelow[c]) {
						out.freezing.add(i);
						if (fast) {
							colFreezeFast[c]++;
							if (restless) {
								colFreezeRestless[c]++;
							}
						}
					}
				}
			}
			if (player && x >= sx0 && x <= sx1 && y >= sy0 && y <= sy1 && z >= sz0 && z <= sz1) {
				inside++;
				sumX += vel[o];
				sumY += vel[o + 1];
				sumZ += vel[o + 2];
			}
			if (x >= boxX0 && x < boxX1 && y >= boxY0 && y < boxY1 && z >= boxZ0 && z < boxZ1) {
				measureSource(s, out, x, y, z, vel[o], vel[o + 1], vel[o + 2]);
			}
			if (columns) {
				long key = column(x, z);
				if (key != lastColumn) {
					if (columnRun > 0) {
						out.columns.addTo(lastColumn, columnRun);
					}
					lastColumn = key;
					columnRun = 0;
				}
				columnRun++;
			}
			if (sampled) {
				long block = BlockPos.asLong(Mth.floor(x) + s.originX, Mth.floor(y) + s.originY, Mth.floor(z) + s.originZ);
				if (block != lastBlock) {
					lastBlock = block;
					out.blocks.add(block);
				}
			}
		}
		if (columnRun > 0) {
			out.columns.addTo(lastColumn, columnRun);
		}
		out.moving += moving;
		out.farthestSq = Math.max(out.farthestSq, farthest);
		out.minY = Math.min(out.minY, lowY);
		out.maxY = Math.max(out.maxY, highY);
		out.minX = Math.min(out.minX, minX);
		out.maxX = Math.max(out.maxX, maxX);
		out.minZ = Math.min(out.minZ, minZ);
		out.maxZ = Math.max(out.maxZ, maxZ);
		out.inside += inside;
		out.sumX += sumX;
		out.sumY += sumY;
		out.sumZ += sumZ;
	}

	/** Puts {@code y} into the ascending {@link #TOP} heights at {@code o}, dropping the lowest. */
	static void insertTop(float[] top, int o, float y) {
		if (!(y > top[o])) {
			return;
		}
		int k = 0;
		while (k + 1 < TOP && top[o + k + 1] < y) {
			top[o + k] = top[o + k + 1];
			k++;
		}
		top[o + k] = y;
	}

	/** Height of the surface of measured column {@code c}: its {@link #TOP}-th highest calm particle, or negative infinity. */
	public static float surface(Part part, int c) {
		return part.colTop[c * TOP];
	}

	/** Key of the column of blocks that holds position {@code x, z} (relative to the origin). */
	public static long column(float x, float z) {
		return ((long) Mth.floor(x) << 32) | (Mth.floor(z) & 0xFFFFFFFFL);
	}

	/** Notes a particle in the source block it lies in, if any. */
	private static void measureSource(Settings s, Part out, float x, float y, float z, float vx, float vy, float vz) {
		int[] cx = s.sourceX;
		int[] cy = s.sourceY;
		int[] cz = s.sourceZ;
		for (int k = 0; k < cx.length; k++) {
			int slot = SourceSlots.slot(x - cx[k], y - cy[k], z - cz[k], s.perAxis);
			if (slot < 0) {
				continue;
			}
			SourceSlots.mark(out.sourceSlots[k], slot);
			out.sourceCount[k]++;
			out.sourceSum[k * 3] += vx;
			out.sourceSum[k * 3 + 1] += vy;
			out.sourceSum[k * 3 + 2] += vz;
			float height = y - cy[k];
			if (height > out.sourceTop[k]) {
				out.sourceTop[k] = height;
				out.sourceTopVy[k] = vy;
			}
			return;
		}
	}

	/** Adds {@code part} (the next chunk in particle order) to {@code total}. */
	public static void merge(Part total, Part part) {
		total.lost.addAll(part.lost);
		total.blocks.addAll(part.blocks);
		for (Long2IntMap.Entry e : part.columns.long2IntEntrySet()) {
			total.columns.addTo(e.getLongKey(), e.getIntValue());
		}
		total.moving += part.moving;
		total.farthestSq = Math.max(total.farthestSq, part.farthestSq);
		total.minY = Math.min(total.minY, part.minY);
		total.maxY = Math.max(total.maxY, part.maxY);
		for (int b = 0; b < MAX_BODIES; b++) {
			total.bodyInside[b] += part.bodyInside[b];
		}
		for (int k = 0; k < MAX_BODIES * 3; k++) {
			total.bodySum[k] += part.bodySum[k];
		}
		total.minX = Math.min(total.minX, part.minX);
		total.maxX = Math.max(total.maxX, part.maxX);
		total.minZ = Math.min(total.minZ, part.minZ);
		total.maxZ = Math.max(total.maxZ, part.maxZ);
		total.freezing.addAll(part.freezing);
		if (total.gridSizeX == part.gridSizeX && total.gridSizeZ == part.gridSizeZ) {
			int cells = total.gridSizeX * total.gridSizeZ;
			for (int c = 0; c < cells; c++) {
				int n = part.colCount[c];
				if (n == 0) {
					continue;
				}
				total.colCount[c] += n;
				total.colMinY[c] = Math.min(total.colMinY[c], part.colMinY[c]);
				total.colMaxY[c] = Math.max(total.colMaxY[c], part.colMaxY[c]);
				total.colFast[c] += part.colFast[c];
				total.colRestless[c] += part.colRestless[c];
				total.colNearRestless[c] += part.colNearRestless[c];
				for (int k = 0; k < TOP; k++) {
					insertTop(total.colTop, c * TOP, part.colTop[c * TOP + k]);
				}
				total.colFreezeFast[c] += part.colFreezeFast[c];
				total.colFreezeRestless[c] += part.colFreezeRestless[c];
			}
		}
		total.inside += part.inside;
		total.sumX += part.sumX;
		total.sumY += part.sumY;
		total.sumZ += part.sumZ;
		for (int k = 0; k < total.sourceSlots.length; k++) {
			long[] into = total.sourceSlots[k];
			long[] from = part.sourceSlots[k];
			for (int w = 0; w < into.length; w++) {
				into[w] |= from[w];
			}
			total.sourceCount[k] += part.sourceCount[k];
			if (part.sourceTop[k] > total.sourceTop[k]) {
				total.sourceTop[k] = part.sourceTop[k];
				total.sourceTopVy[k] = part.sourceTopVy[k];
			}
		}
		for (int k = 0; k < total.sourceSum.length; k++) {
			total.sourceSum[k] += part.sourceSum[k];
		}
	}

	/**
	 * The ranges to upload after removing particles: the sorted hole indices below {@code active},
	 * holes at most {@code mergeGap} apart joined into one range, and everything from the first to
	 * the last hole as one range when there would be more than {@code maxRuns} ranges.
	 *
	 * @return start (inclusive) and end (exclusive) of every range, one after the other
	 */
	public static int[] uploadRuns(int[] holes, int count, int active, int mergeGap, int maxRuns) {
		IntArrayList runs = new IntArrayList();
		int start = -1;
		int end = -1;
		for (int k = 0; k < count; k++) {
			int i = holes[k];
			if (i >= active) {
				break;
			}
			if (start < 0) {
				start = i;
			} else if (i - end > mergeGap) {
				runs.add(start);
				runs.add(end);
				start = i;
			}
			end = i + 1;
		}
		if (start < 0) {
			return new int[0];
		}
		runs.add(start);
		runs.add(end);
		if (runs.size() / 2 > maxRuns) {
			return new int[] {runs.getInt(0), end};
		}
		return runs.toIntArray();
	}
}
