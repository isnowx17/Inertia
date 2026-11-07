package io.github.isnowx17.inertia.util;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

/**
 * Where the water makes noise, measured from one physics step: splashes (water falling, heard where
 * it lands: on the still water of its column when it reached it, else at the bottom of the fall, to
 * be heard only where there is ground right below) and moving water (anything from water
 * stirred a little to a fast current, with how fast it moves). Both are gathered in cells
 * {@link #CELL} blocks wide; the loudest come out as spots. Pure computation, any thread.
 */
public final class WaterAcoustics {
	/** Width of the cells the noise is gathered in, blocks. */
	public static final float CELL = 2f;
	/** Water falling faster than this (blocks per second) splashes where it lands. */
	public static final float FALL_SPEED = 1.8f;
	/** Water moving slower than this (blocks per second) is quiet. */
	public static final float MOVE_SPEED = 0.8f;
	/** At most this many spots of each kind. */
	public static final int MAX_SPOTS = 16;
	/** Floats per spot: x, y, z, amount, speed, 1 if it lands on water (splashes). */
	public static final int SPOT = 6;
	/** Water slower than this (blocks per second) is resting: the water falling water lands on. */
	private static final float REST_SPEED = 1f;
	/** Falling water at most this far above resting water (blocks) has reached it. */
	private static final float LANDED = 1f;
	/** Less water than this (blocks) is no sound: a couple of particles at 512 per block. */
	private static final float MIN_AMOUNT = 0.004f;
	/** At most this many particles are looked at (evenly spread). */
	private static final int SAMPLES = 250_000;

	/** Spots, {@link #SPOT} floats each: x, y, z (local), amount (blocks of water), mean speed (blocks per second), on water. */
	public static final class Result {
		/** Falling water: on the resting water it reached, or at the lowest point it got to in its column. */
		public final float[] splashes = new float[MAX_SPOTS * SPOT];
		public int splashCount;
		/** Moving water, at its mean position in the cell. */
		public final float[] flows = new float[MAX_SPOTS * SPOT];
		public int flowCount;
		/** Origin of the local coordinates. */
		public int originX;
		public int originY;
		public int originZ;
		/** The physics tick it was measured at. */
		public long tick;
	}

	private final Long2IntOpenHashMap cells = new Long2IntOpenHashMap();
	/** Per cell: x, y, z and speed weighted by amount, and the amount. */
	private float[] sums = new float[0];
	/** Per cell: the lowest y seen (splashes are heard where the water lands). */
	private float[] lowest = new float[0];
	/** Per column: the highest resting water at most {@link #LANDED} below its falling water. */
	private float[] restTop = new float[0];
	private int used;

	public WaterAcoustics() {
		cells.defaultReturnValue(-1);
	}

	/**
	 * @param positions      particle positions, {@code stride} floats each, local
	 * @param velocities     particle velocities, blocks per second, same layout
	 * @param particleVolume volume of water one particle stands for, blocks
	 */
	public Result measure(float[] positions, float[] velocities, int count, int stride, float particleVolume, Result out) {
		int step = Math.max(1, count / SAMPLES);
		float weight = step * particleVolume;

		// Splashes: falling water, per column, heard at the bottom of the fall.
		begin();
		for (int i = 0; i < count; i += step) {
			int o = i * stride;
			float vy = velocities[o + 1];
			if (vy < -FALL_SPEED) {
				add(positions[o], positions[o + 1], positions[o + 2], -vy, weight, true);
			}
		}
		// The resting water in those columns: where the falling water lands.
		if (restTop.length < lowest.length) {
			restTop = new float[lowest.length];
		}
		Arrays.fill(restTop, 0, used, Float.NEGATIVE_INFINITY);
		if (used > 0) {
			float restSq = REST_SPEED * REST_SPEED;
			for (int i = 0; i < count; i += step) {
				int o = i * stride;
				float vx = velocities[o];
				float vy = velocities[o + 1];
				float vz = velocities[o + 2];
				if (vx * vx + vy * vy + vz * vz >= restSq || !Float.isFinite(positions[o])) {
					continue;
				}
				int c = cells.get(key((int) Math.floor(positions[o] / CELL), 0, (int) Math.floor(positions[o + 2] / CELL)));
				float y = positions[o + 1];
				if (c >= 0 && y <= lowest[c] + 0.5f && y > restTop[c]) {
					restTop[c] = y;
				}
			}
		}
		out.splashCount = pick(out.splashes, true);

		// Moving water.
		begin();
		float minSq = MOVE_SPEED * MOVE_SPEED;
		for (int i = 0; i < count; i += step) {
			int o = i * stride;
			float vx = velocities[o];
			float vy = velocities[o + 1];
			float vz = velocities[o + 2];
			float sq = vx * vx + vy * vy + vz * vz;
			if (sq > minSq) {
				add(positions[o], positions[o + 1], positions[o + 2], (float) Math.sqrt(sq), weight, false);
			}
		}
		out.flowCount = pick(out.flows, false);
		return out;
	}

	private void begin() {
		cells.clear();
		used = 0;
	}

	/** Adds to the cell of (x, y, z), or with {@code column} to the whole column of (x, z). */
	private void add(float x, float y, float z, float speed, float amount, boolean column) {
		if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
			return;
		}
		long key = key((int) Math.floor(x / CELL), column ? 0 : (int) Math.floor(y / CELL), (int) Math.floor(z / CELL));
		int c = cells.get(key);
		if (c < 0) {
			c = used++;
			cells.put(key, c);
			if (sums.length < used * 5) {
				sums = Arrays.copyOf(sums, Math.max(80, used * 10));
				lowest = Arrays.copyOf(lowest, sums.length / 5);
			}
			Arrays.fill(sums, c * 5, c * 5 + 5, 0f);
			lowest[c] = Float.POSITIVE_INFINITY;
		}
		lowest[c] = Math.min(lowest[c], y);
		int o = c * 5;
		sums[o] += x * amount;
		sums[o + 1] += y * amount;
		sums[o + 2] += z * amount;
		sums[o + 3] += speed * amount;
		sums[o + 4] += amount;
	}

	/** The cells with the most water (at least {@link #MIN_AMOUNT}) into {@code spots}, most first. */
	private int pick(float[] spots, boolean atBottom) {
		int n = 0;
		for (int c = 0; c < used; c++) {
			float amount = sums[c * 5 + 4];
			if (amount < MIN_AMOUNT) {
				continue;
			}
			// Insertion into the short sorted list.
			int at = n;
			while (at > 0 && spots[(at - 1) * SPOT + 3] < amount) {
				at--;
			}
			if (at >= MAX_SPOTS) {
				continue;
			}
			int last = Math.min(n, MAX_SPOTS - 1);
			System.arraycopy(spots, at * SPOT, spots, (at + 1) * SPOT, (last - at) * SPOT);
			int o = at * SPOT;
			boolean onWater = atBottom && lowest[c] - restTop[c] <= LANDED;
			spots[o] = sums[c * 5] / amount;
			spots[o + 1] = !atBottom ? sums[c * 5 + 1] / amount : onWater ? restTop[c] : lowest[c];
			spots[o + 2] = sums[c * 5 + 2] / amount;
			spots[o + 3] = amount;
			spots[o + 4] = sums[c * 5 + 3] / amount;
			spots[o + 5] = onWater ? 1f : 0f;
			n = Math.min(n + 1, MAX_SPOTS);
		}
		return n;
	}

	private static long key(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}
}
