package io.github.isnowx17.inertia.util;

/**
 * The places for particles in the block of a flowing water source: a lattice of
 * {@code perAxis³} slots, one particle each, numbered layer by layer from the bottom (so going
 * through them backwards fills the top layer first). Taken slots are kept in a bit set.
 */
public final class SourceSlots {
	private SourceSlots() {
	}

	/** Longs needed for the bit set of a block. */
	public static int words(int perAxis) {
		return (perAxis * perAxis * perAxis + 63) / 64;
	}

	/**
	 * Slot of a point inside the block, given as fractions of the block (0 to 1) along each axis,
	 * or -1 when the point lies outside the block.
	 */
	public static int slot(float fx, float fy, float fz, int perAxis) {
		if (!(fx >= 0f && fy >= 0f && fz >= 0f && fx < 1f && fy < 1f && fz < 1f)) {
			return -1;
		}
		int ix = Math.min(perAxis - 1, (int) (fx * perAxis));
		int iy = Math.min(perAxis - 1, (int) (fy * perAxis));
		int iz = Math.min(perAxis - 1, (int) (fz * perAxis));
		return (iy * perAxis + iz) * perAxis + ix;
	}

	public static int x(int slot, int perAxis) {
		return slot % perAxis;
	}

	public static int y(int slot, int perAxis) {
		return slot / (perAxis * perAxis);
	}

	public static int z(int slot, int perAxis) {
		return (slot / perAxis) % perAxis;
	}

	public static void mark(long[] taken, int slot) {
		taken[slot >> 6] |= 1L << (slot & 63);
	}

	public static boolean isTaken(long[] taken, int slot) {
		return (taken[slot >> 6] & (1L << (slot & 63))) != 0;
	}
}
