package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WaterAcousticsTest {
	private static final int STRIDE = 4;

	private static void put(float[] pos, float[] vel, int i, float x, float y, float z, float vx, float vy, float vz) {
		pos[i * STRIDE] = x;
		pos[i * STRIDE + 1] = y;
		pos[i * STRIDE + 2] = z;
		vel[i * STRIDE] = vx;
		vel[i * STRIDE + 1] = vy;
		vel[i * STRIDE + 2] = vz;
	}

	@Test
	void fallingWaterSplashesAtTheBottomOfItsColumn() {
		int n = 300;
		float[] pos = new float[n * STRIDE];
		float[] vel = new float[n * STRIDE];
		// A stream falling from y 10 to y 1 at x, z = 0.5, and a smaller one elsewhere.
		for (int i = 0; i < 200; i++) {
			put(pos, vel, i, 0.5f, 1f + i * 0.045f, 0.5f, 0f, -6f, 0f);
		}
		for (int i = 200; i < 260; i++) {
			put(pos, vel, i, 8.5f, 3f + (i - 200) * 0.05f, 8.5f, 0f, -5f, 0f);
		}
		// Still water: quiet, except as the pool the first stream lands on (at y 0.5).
		for (int i = 260; i < 280; i++) {
			put(pos, vel, i, 0.6f, 0.5f, 0.4f, 0.1f, 0f, 0f);
		}
		for (int i = 280; i < n; i++) {
			put(pos, vel, i, 20f, 0.5f, 20f, 0.1f, 0f, 0f);
		}
		WaterAcoustics.Result r = new WaterAcoustics().measure(pos, vel, n, STRIDE, 0.01f, new WaterAcoustics.Result());
		assertEquals(2, r.splashCount);
		assertEquals(0.5f, r.splashes[0], 1e-4f);
		assertEquals(0.5f, r.splashes[1], 1e-4f); // on the pool it reached
		assertEquals(1f, r.splashes[5], 1e-4f);
		assertEquals(2f, r.splashes[3], 1e-4f); // 200 particles of 0.01 blocks
		assertEquals(6f, r.splashes[4], 1e-4f); // mean falling speed
		assertEquals(8.5f, r.splashes[6], 1e-4f);
		assertEquals(3f, r.splashes[7], 1e-4f); // no water below: the bottom of the fall
		assertEquals(0f, r.splashes[11], 1e-4f);
		// The fast water is also flow, in cells 2 blocks high: none of it is the still water.
		for (int i = 0; i < r.flowCount; i++) {
			assertEquals(true, r.flows[i * WaterAcoustics.SPOT] < 10f);
		}
	}
}
