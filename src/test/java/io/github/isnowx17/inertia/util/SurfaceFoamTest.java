package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import org.junit.jupiter.api.Test;

class SurfaceFoamTest {
	private static final float CELL = 0.125f;
	/** 16 x 8 blocks of water at height 1, with a one-column dry rim. */
	private static final int COLS = 128;
	private static final int ROWS = 64;
	private static final float DT = 1f / 40f;

	private static SurfaceFoam foam(ForkJoinPool pool) {
		return new SurfaceFoam(new SurfaceFoam.Settings(100_000, 24, 1f, 2f, 1f, 1f), 42L, pool);
	}

	/** Surface flowing along +x at {@code speed}, with a faster jet in the first {@code jetBlocks}. */
	private static void setFlow(SurfaceFoam foam, float speed, float jetSpeed, float jetBlocks) {
		float[] h = new float[COLS * ROWS];
		float[] vx = new float[COLS * ROWS];
		float[] vy = new float[COLS * ROWS];
		float[] vz = new float[COLS * ROWS];
		for (int j = 0; j < ROWS; j++) {
			for (int i = 0; i < COLS; i++) {
				int c = i + j * COLS;
				boolean rim = i == 0 || j == 0 || i == COLS - 1 || j == ROWS - 1;
				h[c] = rim ? Float.NaN : 1f;
				vx[c] = (i + 0.5f) * CELL < jetBlocks ? jetSpeed : speed;
			}
		}
		foam.setSurface(CELL, 0, 0, COLS, ROWS, h, vx, vy, vz);
	}

	@Test
	void stillWaterStaysClear() {
		SurfaceFoam foam = foam(null);
		setFlow(foam, 0f, 0f, 0f);
		for (int i = 0; i < 200; i++) {
			foam.step(DT, null, 0, 3f);
		}
		assertEquals(0, foam.tracerCount());
		SurfaceFoam.Map map = new SurfaceFoam.Map();
		foam.buildMap(map);
		assertTrue(map.empty);
	}

	@Test
	void churningWaterFoamsAndTheFoamDriftsDownstream() {
		SurfaceFoam foam = foam(null);
		setFlow(foam, 1.0f, 4.0f, 2f);
		for (int i = 0; i < 120; i++) {
			foam.step(DT, null, 0, 3f);
		}
		assertTrue(foam.tracerCount() > 1000, "foam forms where the water churns: " + foam.tracerCount());
		float maxX = 0f;
		for (int i = 0; i < foam.tracerCount(); i++) {
			maxX = Math.max(maxX, foam.tracerX(i));
			assertTrue(foam.wetAt(foam.tracerX(i), foam.tracerZ(i)), "foam stays on the water");
		}
		assertTrue(maxX > 3.5f, "foam drifts with the flow: " + maxX);

		SurfaceFoam.Map map = new SurfaceFoam.Map();
		foam.buildMap(map);
		assertTrue(!map.empty);
		float densest = 0f;
		for (float x = 0.5f; x < 5f; x += 0.05f) {
			for (float z = 1f; z < 7f; z += 0.05f) {
				densest = Math.max(densest, map.amountAt(x, z));
			}
		}
		assertTrue(densest > 0.4f, "dense foam near the churn: " + densest);
		assertEquals(0f, map.amountAt(-5f, 4f), 1e-6f, "no foam off the map");
		assertEquals(map.heightCode(1f), map.heightCodeAt(1f, 4f), "the map stores the surface height");
	}

	@Test
	void foamFadesOnceTheWaterCalmsDown() {
		SurfaceFoam foam = foam(null);
		setFlow(foam, 1.0f, 4.0f, 2f);
		for (int i = 0; i < 80; i++) {
			foam.step(DT, null, 0, 2f);
		}
		assertTrue(foam.tracerCount() > 0);
		setFlow(foam, 0f, 0f, 0f);
		// Tracers live at most 2.3 times the lifetime (2 s).
		for (int i = 0; i < (int) (4.7f / DT); i++) {
			foam.step(DT, null, 0, 2f);
		}
		assertEquals(0, foam.tracerCount());
	}

	@Test
	void freshDiffuseParticlesAtTheSurfaceMakeFoam() {
		SurfaceFoam foam = foam(null);
		setFlow(foam, 0f, 0f, 0f);
		float[] diffuse = new float[3 * 4];
		// At the surface and fresh; deep under water; high in the air.
		float[][] particles = {{4f, 1.1f, 4f, 3f}, {10f, -2f, 4f, 3f}, {12f, 4f, 4f, 3f}};
		for (int i = 0; i < particles.length; i++) {
			System.arraycopy(particles[i], 0, diffuse, i * 4, 4);
		}
		for (int i = 0; i < 40; i++) {
			foam.step(DT, diffuse, 3, 3f);
		}
		assertTrue(foam.tracerCount() > 0);
		for (int i = 0; i < foam.tracerCount(); i++) {
			assertTrue(foam.tracerX(i) < 7f, "only the particle at the surface makes foam: " + foam.tracerX(i));
		}
	}

	@Test
	void surfaceFromMeshKeepsTheLowestTopSurface() {
		int stride = FluidSurfaceMesher.VERTEX_STRIDE;
		// A pool at y = 1 flowing +x, and a stream arcing over it at y = 3 flowing -x.
		float[] vertices = new float[2 * 64 * 64 * stride];
		int n = 0;
		for (int j = 0; j < 64; j++) {
			for (int i = 0; i < 64; i++) {
				float x = (i + 0.5f) * CELL;
				float z = (j + 0.5f) * CELL;
				n = vertex(vertices, n, stride, x, 1f, z, 0.5f);
				if (i > 20 && i < 40) {
					n = vertex(vertices, n, stride, x, 3f, z, -3f);
				}
			}
		}
		SurfaceFoam foam = foam(null);
		foam.setSurfaceFromMesh(vertices, n, stride, CELL);
		assertEquals(1f, foam.surfaceHeightAt(3.9f, 4f), 1e-6f);
		assertEquals(0.5f, foam.surfaceVelocityXAt(3.9f, 4f), 1e-6f);
		assertTrue(Float.isNaN(foam.surfaceHeightAt(20f, 4f)), "no water far away");
	}

	private static int vertex(float[] v, int n, int stride, float x, float y, float z, float vx) {
		int o = n * stride;
		Arrays.fill(v, o, o + stride, 0f);
		v[o] = x;
		v[o + 1] = y;
		v[o + 2] = z;
		v[o + 4] = 1f;
		v[o + 6] = vx;
		return n + 1;
	}

	@Test
	void parallelStepsMatchTheTracerBudget() {
		ForkJoinPool pool = new ForkJoinPool(3);
		try {
			SurfaceFoam foam = new SurfaceFoam(new SurfaceFoam.Settings(5_000, 24, 4f, 3f, 1f, 1f), 1L, pool);
			setFlow(foam, 2f, 5f, 8f);
			for (int i = 0; i < 100; i++) {
				foam.step(DT, null, 0, 3f);
				assertTrue(foam.tracerCount() <= 5_000);
			}
			assertTrue(foam.tracerCount() > 4_000, "the budget fills up: " + foam.tracerCount());
			SurfaceFoam.Map map = new SurfaceFoam.Map();
			foam.buildMap(map);
			assertTrue(map.levels > 1 && map.width >= 64 && Integer.bitCount(map.width) == 1 && Integer.bitCount(map.height) == 1);
			int expected = 0;
			for (int level = 0; level < map.levels; level++) {
				assertEquals(expected, map.offsets[level]);
				expected += 2 * (map.width >> level) * (map.height >> level);
			}
			assertTrue(map.data.length >= expected);
		} finally {
			pool.shutdown();
		}
	}
}
