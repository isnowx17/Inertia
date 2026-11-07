package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrozenColumnsTest {
	private static final int PER_AXIS = 16;
	private static final float SPACING = 1f / PER_AXIS;
	private static final float FIRST = 0.5f * SPACING / 0.6f;
	private static final int SIZE = 4;
	private static final int LAYERS = 32;

	/** A 4x4 pool with walls around it and a floor at height 0. */
	private static final FrozenColumns.Blocks POOL = new FrozenColumns.Blocks() {
		@Override
		public boolean solid(int x, int y, int z) {
			return y < 0 || x < 0 || x >= SIZE || z < 0 || z >= SIZE;
		}

		@Override
		public boolean open(int x, int y, int z) {
			return !solid(x, y, z);
		}
	};

	/** Still water filling the pool {@code layers} particle layers deep, resting on the floor. */
	private static float[] water(int layers) {
		float[] p = new float[SIZE * PER_AXIS * SIZE * PER_AXIS * layers * 3];
		int i = 0;
		for (int y = 0; y < layers; y++) {
			for (int x = 0; x < SIZE * PER_AXIS; x++) {
				for (int z = 0; z < SIZE * PER_AXIS; z++) {
					p[i++] = (x + 0.5f) * SPACING;
					p[i++] = FIRST + y * SPACING;
					p[i++] = (z + 0.5f) * SPACING;
				}
			}
		}
		return p;
	}

	private static FrozenColumns frozen() {
		FrozenColumns frozen = new FrozenColumns(SPACING, PER_AXIS, FIRST);
		return frozen;
	}

	private static ParticleScan.Settings settings() {
		ParticleScan.Settings s = new ParticleScan.Settings();
		s.minY = -100f;
		s.maxDistSq = 1e9f;
		s.movingSpeedSq = 0.25f;
		s.perAxis = PER_AXIS;
		s.gridX0 = -2;
		s.gridZ0 = -2;
		s.gridSizeX = SIZE + 4;
		s.gridSizeZ = SIZE + 4;
		return s;
	}

	private static ParticleScan.Part scan(float[] pos, int count, ParticleScan.Settings s) {
		ParticleScan.Part part = new ParticleScan.Part();
		part.reset(0, PER_AXIS, s);
		ParticleScan.scan(pos, new float[pos.length], 3, 0, count, s, part);
		return part;
	}

	/** Freezes the pool's calm water; returns the particles that were taken out. */
	private static IntArrayList freeze(FrozenColumns frozen, float[] pos, ParticleScan.Settings s, FrozenColumns.Context ctx) {
		int count = pos.length / 3;
		for (int step = 0; step < FrozenColumns.CALM_STEPS; step++) {
			frozen.plan(scan(pos, count, s), POOL, ctx);
		}
		assertTrue(frozen.hasPlans());
		frozen.prepareScan(s);
		assertNotNull(s.freezeBelow);
		ParticleScan.Part listed = scan(pos, count, s);
		s.freezeBelow = null;
		IntArrayList remove = new IntArrayList();
		frozen.commit(listed, pos, 3, remove);
		return remove;
	}

	/** The particles that were not taken out. */
	private static float[] without(float[] pos, IntArrayList removed) {
		boolean[] gone = new boolean[pos.length / 3];
		for (int i : removed) {
			gone[i] = true;
		}
		float[] out = new float[(gone.length - removed.size()) * 3];
		int k = 0;
		for (int i = 0; i < gone.length; i++) {
			if (!gone[i]) {
				System.arraycopy(pos, i * 3, out, k, 3);
				k += 3;
			}
		}
		return out;
	}

	@Test
	void calmPoolFreezesAllButItsTopLayers() {
		FrozenColumns frozen = frozen();
		float[] pos = water(LAYERS);
		IntArrayList removed = freeze(frozen, pos, settings(), new FrozenColumns.Context());

		int frozenLayers = LAYERS - FrozenColumns.ACTIVE_LAYERS;
		assertEquals(SIZE * SIZE, frozen.columnCount());
		assertEquals(SIZE * SIZE * frozenLayers * PER_AXIS * PER_AXIS, removed.size());
		assertEquals(removed.size(), frozen.frozenParticles());
		for (int k = 1; k < removed.size(); k++) {
			assertTrue(removed.getInt(k) > removed.getInt(k - 1), "ascending");
		}
		for (FrozenColumns.Column c : frozen.columns()) {
			assertEquals(0, c.floor);
			assertEquals(frozenLayers, c.layers);
		}
		// The rest of the water lies on the frozen water: nothing to thaw.
		float[] rest = without(pos, removed);
		assertTrue(frozen.thaw(scan(rest, rest.length / 3, settings()), new FrozenColumns.Context()).isEmpty());
	}

	@Test
	void thinPoolDoesNotFreeze() {
		FrozenColumns frozen = frozen();
		float[] pos = water(FrozenColumns.ACTIVE_LAYERS + 1);
		ParticleScan.Settings s = settings();
		for (int step = 0; step < FrozenColumns.CALM_STEPS * 2; step++) {
			frozen.plan(scan(pos, pos.length / 3, s), POOL, new FrozenColumns.Context());
		}
		assertFalse(frozen.hasPlans());
	}

	@Test
	void movingWaterDoesNotFreeze() {
		FrozenColumns frozen = frozen();
		float[] pos = water(LAYERS);
		float[] vel = new float[pos.length];
		for (int i = 1; i < vel.length; i += 3 * 20) {
			vel[i] = -1f;
		}
		ParticleScan.Settings s = settings();
		for (int step = 0; step < FrozenColumns.CALM_STEPS * 2; step++) {
			ParticleScan.Part part = new ParticleScan.Part();
			part.reset(0, PER_AXIS, s);
			ParticleScan.scan(pos, vel, 3, 0, pos.length / 3, s, part);
			frozen.plan(part, POOL, new FrozenColumns.Context());
		}
		assertFalse(frozen.hasPlans());
	}

	@Test
	void playerThawsTheWaterAroundThem() {
		FrozenColumns frozen = frozen();
		float[] pos = water(LAYERS);
		IntArrayList removed = freeze(frozen, pos, settings(), new FrozenColumns.Context());
		float[] rest = without(pos, removed);

		FrozenColumns.Context ctx = new FrozenColumns.Context();
		ctx.player = true;
		ctx.playerMinX = 1.2f;
		ctx.playerMaxX = 1.8f;
		ctx.playerMinZ = 1.2f;
		ctx.playerMaxZ = 1.8f;
		ctx.playerMinY = 1.8f;
		ctx.playerMaxY = 3.6f;
		List<FrozenColumns.Thaw> thaws = frozen.thaw(scan(rest, rest.length / 3, settings()), ctx);
		// Columns 0..2 on both axes, down to 1.5 blocks below the feet (0.3 above the floor).
		assertEquals(9, thaws.size());
		int frozenLayers = LAYERS - FrozenColumns.ACTIVE_LAYERS;
		int keep = (int) Math.floor(0.3f / SPACING + 1e-4f);
		for (FrozenColumns.Thaw t : thaws) {
			assertTrue(t.x() <= 2 && t.z() <= 2);
			assertEquals(keep, t.fromLayer());
			assertEquals(frozenLayers, t.toLayer());
			assertEquals((frozenLayers - keep) * PER_AXIS * PER_AXIS, t.particles());
			frozen.applyThaw(t);
		}
		assertEquals(keep, frozen.get(1, 1).layers);
		assertEquals(frozenLayers, frozen.get(3, 3).layers);
	}

	@Test
	void drainedWaterThawsEverything() {
		FrozenColumns frozen = frozen();
		float[] pos = water(LAYERS);
		freeze(frozen, pos, settings(), new FrozenColumns.Context());
		long total = frozen.frozenParticles();
		List<FrozenColumns.Thaw> thaws = frozen.thaw(scan(new float[0], 0, settings()), new FrozenColumns.Context());
		assertEquals(SIZE * SIZE, thaws.size());
		long particles = 0;
		for (FrozenColumns.Thaw t : thaws) {
			assertEquals(0, t.fromLayer());
			particles += t.particles();
			frozen.applyThaw(t);
		}
		assertEquals(total, particles);
		assertTrue(frozen.isEmpty());
		assertEquals(0, frozen.frozenParticles());
	}

	@Test
	void changedBlockThawsTheColumnsNextToIt() {
		FrozenColumns frozen = frozen();
		float[] pos = water(LAYERS);
		IntArrayList removed = freeze(frozen, pos, settings(), new FrozenColumns.Context());
		float[] rest = without(pos, removed);
		assertTrue(frozen.blockChanged(SIZE, 0, 0)); // the wall beside column (3, 0)
		assertFalse(frozen.blockChanged(SIZE + 5, 0, 0));
		List<FrozenColumns.Thaw> thaws = frozen.thaw(scan(rest, rest.length / 3, settings()), new FrozenColumns.Context());
		assertEquals(2, thaws.size()); // (3, 0) and (3, 1)
		for (FrozenColumns.Thaw t : thaws) {
			assertEquals(SIZE - 1, t.x());
			assertEquals(0, t.fromLayer());
		}
	}

	@Test
	void lowerWaterBesideThawsDownToIt() {
		FrozenColumns frozen = frozen();
		float[] pos = water(LAYERS);
		IntArrayList removed = freeze(frozen, pos, settings(), new FrozenColumns.Context());
		float[] rest = without(pos, removed);
		// Column (0, 0) and its water above are gone (say, it thawed and flowed away): its neighbours
		// have nothing beside them any more.
		FrozenColumns.Thaw gone = new FrozenColumns.Thaw(0, 0, 0, 0, frozen.get(0, 0).layers, frozen.get(0, 0).count, false);
		frozen.applyThaw(gone);
		float[] left = new float[rest.length];
		int k = 0;
		for (int i = 0; i < rest.length; i += 3) {
			if (rest[i] >= 1f || rest[i + 2] >= 1f) {
				System.arraycopy(rest, i, left, k, 3);
				k += 3;
			}
		}
		List<FrozenColumns.Thaw> thaws = frozen.thaw(scan(left, k / 3, settings()), new FrozenColumns.Context());
		assertEquals(2, thaws.size()); // (1, 0) and (0, 1)
		for (FrozenColumns.Thaw t : thaws) {
			assertEquals(0, t.fromLayer());
		}
	}

	@Test
	void boxesStartWhereTheParticlesWould() {
		FrozenColumns frozen = frozen();
		freeze(frozen, water(LAYERS), settings(), new FrozenColumns.Context());
		float[] boxes = frozen.boxes();
		assertEquals(SIZE * SIZE * 6, boxes.length);
		float offset = FIRST - 0.5f * SPACING;
		float top = (LAYERS - FrozenColumns.ACTIVE_LAYERS) * SPACING + offset;
		for (int b = 0; b < boxes.length; b += 6) {
			assertEquals(offset, boxes[b + 1], 1e-5f);
			assertEquals(top, boxes[b + 4], 1e-5f);
		}
		assertTrue(boxes == frozen.boxes(), "cached until something changes");
	}

	@Test
	void atTheGpuLimitSlowlyMovingWaterFreezesToo() {
		// Water stirred by a stream (5 % of it a little faster than calm, none restless) does not freeze
		// normally, but does at the GPU's limit.
		FrozenColumns frozen = frozen();
		float[] pos = water(LAYERS);
		float[] vel = new float[pos.length];
		for (int i = 1; i < vel.length; i += 3 * 20) {
			vel[i] = -1f;
		}
		ParticleScan.Settings s = settings();
		FrozenColumns.Context ctx = new FrozenColumns.Context();
		ctx.tight = true;
		ParticleScan.Part part = new ParticleScan.Part();
		part.reset(0, PER_AXIS, s);
		ParticleScan.scan(pos, vel, 3, 0, pos.length / 3, s, part);
		frozen.plan(part, POOL, ctx);
		assertTrue(frozen.hasPlans());
		frozen.prepareScan(s);
		ParticleScan.Part listed = new ParticleScan.Part();
		listed.reset(0, PER_AXIS, s);
		ParticleScan.scan(pos, vel, 3, 0, pos.length / 3, s, listed);
		IntArrayList remove = new IntArrayList();
		frozen.commit(listed, pos, 3, remove);
		assertEquals(SIZE * SIZE, frozen.columnCount());
		assertEquals(SIZE * SIZE * (LAYERS - FrozenColumns.TIGHT_ACTIVE_LAYERS) * PER_AXIS * PER_AXIS, remove.size());
	}

	@Test
	void dropsAboveTheSurfaceDoNotRaiseIt() {
		FrozenColumns frozen = frozen();
		float[] pool = water(LAYERS);
		// A few slow drops high above every column.
		float[] pos = java.util.Arrays.copyOf(pool, pool.length + SIZE * SIZE * 5 * 3);
		int k = pool.length;
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				for (int d = 0; d < 5; d++) {
					pos[k++] = x + 0.5f;
					pos[k++] = 5f + d;
					pos[k++] = z + 0.5f;
				}
			}
		}
		ParticleScan.Part part = scan(pos, pos.length / 3, settings());
		for (int c = 0; c < part.gridSizeX * part.gridSizeZ; c++) {
			if (part.colCount[c] > 0) {
				assertEquals(FIRST + (LAYERS - 1) * SPACING, ParticleScan.surface(part, c), 1e-5f);
			}
		}
		IntArrayList removed = freeze(frozen, pos, settings(), new FrozenColumns.Context());
		assertEquals(SIZE * SIZE * (LAYERS - FrozenColumns.ACTIVE_LAYERS) * PER_AXIS * PER_AXIS, removed.size());
	}
}
