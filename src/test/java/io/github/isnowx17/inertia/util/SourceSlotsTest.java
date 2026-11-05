package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SourceSlotsTest {
	private static final int PER_AXIS = 16;

	@Test
	void slotsAreNumberedBottomLayerFirst() {
		int slots = PER_AXIS * PER_AXIS * PER_AXIS;
		int lastY = -1;
		for (int slot = 0; slot < slots; slot++) {
			int y = SourceSlots.y(slot, PER_AXIS);
			assertTrue(y >= lastY, "layer order");
			lastY = y;
		}
		assertEquals(0, SourceSlots.y(PER_AXIS * PER_AXIS - 1, PER_AXIS));
		assertEquals(1, SourceSlots.y(PER_AXIS * PER_AXIS, PER_AXIS));
	}

	@Test
	void slotCentresMapBackToTheirSlot() {
		for (int slot = 0; slot < PER_AXIS * PER_AXIS * PER_AXIS; slot += 7) {
			float fx = (SourceSlots.x(slot, PER_AXIS) + 0.5f) / PER_AXIS;
			float fy = (SourceSlots.y(slot, PER_AXIS) + 0.5f) / PER_AXIS;
			float fz = (SourceSlots.z(slot, PER_AXIS) + 0.5f) / PER_AXIS;
			assertEquals(slot, SourceSlots.slot(fx, fy, fz, PER_AXIS));
		}
	}

	@Test
	void pointsOutsideTheBlockHaveNoSlot() {
		assertEquals(-1, SourceSlots.slot(-0.01f, 0.5f, 0.5f, PER_AXIS));
		assertEquals(-1, SourceSlots.slot(0.5f, 1f, 0.5f, PER_AXIS));
		assertEquals(-1, SourceSlots.slot(Float.NaN, 0.5f, 0.5f, PER_AXIS));
	}

	@Test
	void markingTakesExactlyOneSlot() {
		long[] taken = new long[SourceSlots.words(PER_AXIS)];
		int slot = SourceSlots.slot(0.3f, 0.9f, 0.1f, PER_AXIS);
		SourceSlots.mark(taken, slot);
		int count = 0;
		for (int s = 0; s < PER_AXIS * PER_AXIS * PER_AXIS; s++) {
			if (SourceSlots.isTaken(taken, s)) {
				count++;
				assertEquals(slot, s);
			}
		}
		assertEquals(1, count);
		assertFalse(SourceSlots.isTaken(taken, 0));
	}
}
