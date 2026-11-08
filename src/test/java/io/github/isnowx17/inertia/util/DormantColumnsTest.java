package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.Test;

/** Water far from the player sleeps as still water instead of being removed, and wakes when the player comes back. */
class DormantColumnsTest {
	private static final float SPACING = 0.125f;
	private static final int PER_AXIS = 8;
	/** The ground: everything below y 0 is solid. */
	private static final FrozenColumns.Blocks GROUND = new FrozenColumns.Blocks() {
		@Override
		public boolean solid(int x, int y, int z) {
			return y < 0;
		}

		@Override
		public boolean open(int x, int y, int z) {
			return y >= 0;
		}
	};

	private static FrozenColumns.Context playerAt(float x, float z, float distance) {
		FrozenColumns.Context ctx = new FrozenColumns.Context();
		ctx.playerX = x;
		ctx.playerY = 1f;
		ctx.playerZ = z;
		ctx.maxDistSq = distance * distance;
		return ctx;
	}

	@Test
	void parkedWaterSettlesOnTheGroundAndSleeps() {
		FrozenColumns frozen = new FrozenColumns(SPACING, PER_AXIS, 0.06f);
		// 100 particles of a waterfall that drifted out of reach, lowest at y 5.3, over ground at y 0.
		frozen.park(3, 4, 100, 5.3f, GROUND);
		FrozenColumns.Column c = frozen.get(3, 4);
		assertNotNull(c);
		assertEquals(0, c.floor);
		assertEquals(2, c.layers); // 100 particles: two layers of 64
		assertEquals(100, c.count);
		assertTrue(c.dormant);
		assertEquals(100, frozen.frozenParticles());
		// More water arriving there adds to it.
		frozen.park(3, 4, 60, 0.1f, GROUND);
		assertEquals(160, frozen.get(3, 4).count);
		assertEquals(3, frozen.get(3, 4).layers);
	}

	@Test
	void sleepingWaterStaysWhileFarAndWakesNearby() {
		FrozenColumns frozen = new FrozenColumns(SPACING, PER_AXIS, 0.06f);
		frozen.park(100, 0, 128, 0.1f, GROUND);
		// Far away (simulated distance 96): nothing thaws, nothing is removed.
		assertTrue(frozen.thawAll(playerAt(0f, 0f, 96f)).isEmpty());
		assertEquals(1, frozen.columnCount());
		// Just inside the distance but not past the wake margin: still asleep.
		assertTrue(frozen.thawAll(playerAt(100f - 93f, 0f, 96f)).isEmpty());
		// Close: it wakes and becomes moving water again.
		var thaws = frozen.thawAll(playerAt(95f, 0f, 96f));
		assertEquals(1, thaws.size());
		assertEquals(128, thaws.get(0).particles());
	}

	@Test
	void farColumnsAreTakenOutAndPutBack() {
		FrozenColumns frozen = new FrozenColumns(SPACING, PER_AXIS, 0.06f);
		frozen.park(2, 2, 64, 0.1f, GROUND);
		frozen.park(500, 7, 192, 0.1f, GROUND);
		IntArrayList ints = new IntArrayList();
		FloatArrayList walls = new FloatArrayList();
		assertEquals(1, frozen.extract(0f, 0f, 192f, ints, walls));
		assertEquals(1, frozen.columnCount());
		assertEquals(64, frozen.frozenParticles());
		assertEquals(500, ints.getInt(0));
		assertEquals(7, ints.getInt(1));
		assertEquals(192, ints.getInt(4));
		frozen.restore(ints.getInt(0), ints.getInt(1), ints.getInt(2), ints.getInt(3), ints.getInt(4), walls.toFloatArray(), 0);
		assertEquals(2, frozen.columnCount());
		assertEquals(256, frozen.frozenParticles());
		assertTrue(frozen.get(500, 7).dormant);
	}
}
