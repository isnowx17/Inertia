package io.github.isnowx17.inertia.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy merging of solid cells in a cubic voxel grid into as few axis-aligned boxes as possible.
 *
 * <p>Used to turn the full-cube blocks of a 16x16x16 chunk section into a small number of PhysX
 * box shapes: a section of solid stone becomes a single box instead of 4096.
 */
public final class BoxMerger {
	private BoxMerger() {
	}

	/** Index of cell (x, y, z) in a grid of edge length {@code size}. */
	public static int index(int size, int x, int y, int z) {
		return (y * size + z) * size + x;
	}

	/**
	 * Merges the solid cells of the grid. The input array is not modified.
	 *
	 * @param solid {@code size^3} cells addressed with {@link #index(int, int, int, int)}
	 * @return boxes as {@code {x0, y0, z0, x1, y1, z1}} with exclusive upper bounds; every solid
	 *         cell is covered by exactly one box and no box covers a non-solid cell
	 */
	public static List<int[]> merge(boolean[] solid, int size) {
		if (solid.length != size * size * size) {
			throw new IllegalArgumentException("Expected " + size * size * size + " cells, got " + solid.length);
		}

		boolean[] used = new boolean[solid.length];
		List<int[]> boxes = new ArrayList<>();

		for (int y = 0; y < size; y++) {
			for (int z = 0; z < size; z++) {
				for (int x = 0; x < size; x++) {
					if (!free(solid, used, size, x, y, z)) {
						continue;
					}

					// Grow along x.
					int x1 = x + 1;
					while (x1 < size && free(solid, used, size, x1, y, z)) {
						x1++;
					}

					// Grow along z while the whole x-row is free.
					int z1 = z + 1;
					while (z1 < size && rowFree(solid, used, size, x, x1, y, z1)) {
						z1++;
					}

					// Grow along y while the whole x/z-slab is free.
					int y1 = y + 1;
					while (y1 < size && slabFree(solid, used, size, x, x1, y1, z, z1)) {
						y1++;
					}

					for (int yy = y; yy < y1; yy++) {
						for (int zz = z; zz < z1; zz++) {
							for (int xx = x; xx < x1; xx++) {
								used[index(size, xx, yy, zz)] = true;
							}
						}
					}

					boxes.add(new int[] {x, y, z, x1, y1, z1});
				}
			}
		}

		return boxes;
	}

	private static boolean free(boolean[] solid, boolean[] used, int size, int x, int y, int z) {
		int i = index(size, x, y, z);
		return solid[i] && !used[i];
	}

	private static boolean rowFree(boolean[] solid, boolean[] used, int size, int x0, int x1, int y, int z) {
		for (int x = x0; x < x1; x++) {
			if (!free(solid, used, size, x, y, z)) {
				return false;
			}
		}
		return true;
	}

	private static boolean slabFree(boolean[] solid, boolean[] used, int size, int x0, int x1, int y, int z0, int z1) {
		for (int z = z0; z < z1; z++) {
			if (!rowFree(solid, used, size, x0, x1, y, z)) {
				return false;
			}
		}
		return true;
	}
}
