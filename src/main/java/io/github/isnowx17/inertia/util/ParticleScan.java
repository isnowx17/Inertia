package io.github.isnowx17.inertia.util;

import it.unimi.dsi.fastutil.ints.IntArrayList;
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

	/** What a chunk (or, after {@link #merge}, the whole scan) found. */
	public static final class Part {
		/** Indices of the lost particles, ascending. */
		public final IntArrayList lost = new IntArrayList();
		/** Blocks ({@link BlockPos#asLong}) that hold a sampled particle. */
		public final LongOpenHashSet blocks = new LongOpenHashSet();
		/** Particles in the shell around the player and the sum of their velocities. */
		public int inside;
		public double sumX;
		public double sumY;
		public double sumZ;
		/** Per source: the taken places (see {@link SourceSlots}), the particles and their velocity sums. */
		public long[][] sourceSlots = new long[0][];
		public int[] sourceCount = new int[0];
		public float[] sourceSum = new float[0];
		/** Particles that count as moving, and the largest squared distance of a particle from the player. */
		public int moving;
		public float farthestSq;

		/** Empties the part for a scan with {@code sources} source blocks. */
		public void reset(int sources, int perAxis) {
			lost.clear();
			blocks.clear();
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
			} else {
				for (long[] slots : sourceSlots) {
					Arrays.fill(slots, 0L);
				}
				Arrays.fill(sourceCount, 0);
				Arrays.fill(sourceSum, 0f);
			}
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
			if (vel[o] * vel[o] + vel[o + 1] * vel[o + 1] + vel[o + 2] * vel[o + 2] >= movingSq) {
				moving++;
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
			if (sampled) {
				long block = BlockPos.asLong(Mth.floor(x) + s.originX, Mth.floor(y) + s.originY, Mth.floor(z) + s.originZ);
				if (block != lastBlock) {
					lastBlock = block;
					out.blocks.add(block);
				}
			}
		}
		out.moving += moving;
		out.farthestSq = Math.max(out.farthestSq, farthest);
		out.inside += inside;
		out.sumX += sumX;
		out.sumY += sumY;
		out.sumZ += sumZ;
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
			return;
		}
	}

	/** Adds {@code part} (the next chunk in particle order) to {@code total}. */
	public static void merge(Part total, Part part) {
		total.lost.addAll(part.lost);
		total.blocks.addAll(part.blocks);
		total.moving += part.moving;
		total.farthestSq = Math.max(total.farthestSq, part.farthestSq);
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
