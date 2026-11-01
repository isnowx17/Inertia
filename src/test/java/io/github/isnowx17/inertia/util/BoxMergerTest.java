package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BoxMergerTest {
	private static final int SIZE = 16;

	@Test
	void emptyGridProducesNoBoxes() {
		assertTrue(BoxMerger.merge(new boolean[SIZE * SIZE * SIZE], SIZE).isEmpty());
	}

	@Test
	void fullGridProducesOneBox() {
		boolean[] solid = new boolean[SIZE * SIZE * SIZE];
		java.util.Arrays.fill(solid, true);

		List<int[]> boxes = BoxMerger.merge(solid, SIZE);

		assertEquals(1, boxes.size());
		assertArrayEquals(new int[] {0, 0, 0, SIZE, SIZE, SIZE}, boxes.get(0));
	}

	@Test
	void flatFloorProducesOneBox() {
		boolean[] solid = new boolean[SIZE * SIZE * SIZE];
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				solid[BoxMerger.index(SIZE, x, 0, z)] = true;
			}
		}

		List<int[]> boxes = BoxMerger.merge(solid, SIZE);

		assertEquals(1, boxes.size());
		assertArrayEquals(new int[] {0, 0, 0, SIZE, 1, SIZE}, boxes.get(0));
	}

	@Test
	void randomGridsAreCoveredExactlyOnce() {
		Random random = new Random(1234);
		for (int round = 0; round < 50; round++) {
			boolean[] solid = new boolean[SIZE * SIZE * SIZE];
			double density = random.nextDouble();
			for (int i = 0; i < solid.length; i++) {
				solid[i] = random.nextDouble() < density;
			}

			int[] coverage = new int[solid.length];
			for (int[] box : BoxMerger.merge(solid, SIZE)) {
				for (int y = box[1]; y < box[4]; y++) {
					for (int z = box[2]; z < box[5]; z++) {
						for (int x = box[0]; x < box[3]; x++) {
							coverage[BoxMerger.index(SIZE, x, y, z)]++;
						}
					}
				}
			}

			for (int i = 0; i < solid.length; i++) {
				assertEquals(solid[i] ? 1 : 0, coverage[i], "cell " + i + " in round " + round);
			}
		}
	}
}
