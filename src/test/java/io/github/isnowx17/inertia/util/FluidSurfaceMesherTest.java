package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FluidSurfaceMesherTest {
	private static final float SPACING = 0.125f;

	private static FluidSurfaceMesher mesher() {
		return new FluidSurfaceMesher(SPACING, SPACING * 2f, 0.6f);
	}

	@Test
	void noParticlesGiveEmptyMesh() {
		FluidSurfaceMesher.Mesh mesh = mesher().mesh(new float[0], null, 0, null);
		assertTrue(mesh.isEmpty());
		assertEquals(0, mesh.vertexCount);
	}

	@Test
	void singleParticleGivesClosedBlobAroundIt() {
		float[] p = {0.31f, 5.07f, -2.2f};
		FluidSurfaceMesher.Mesh mesh = mesher().mesh(p, null, 1, null);

		assertTrue(mesh.quadCount > 0);
		assertClosed(mesh);
		int outward = 0;
		for (int v = 0; v < mesh.vertexCount; v++) {
			float dx = vx(mesh, v) - p[0];
			float dy = vy(mesh, v) - p[1];
			float dz = vz(mesh, v) - p[2];
			float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			assertTrue(dist < SPACING * 2f, "vertex outside kernel radius: " + dist);
			// Normals point away from the particle. A lone particle covers only a few grid nodes, so
			// the per-cell gradient is coarse; require the large majority.
			if (dx * nx(mesh, v) + dy * ny(mesh, v) + dz * nz(mesh, v) > 0f) {
				outward++;
			}
		}
		assertTrue(outward >= mesh.vertexCount * 0.75, outward + " of " + mesh.vertexCount);
	}

	@Test
	void blockOfWaterMeltsIntoOneSmoothBox() {
		// One block of water at rest spacing: 8 x 8 x 8 particles in [0, 1).
		int n = 8;
		float[] p = new float[n * n * n * 3];
		int i = 0;
		for (int x = 0; x < n; x++) {
			for (int y = 0; y < n; y++) {
				for (int z = 0; z < n; z++) {
					p[i++] = (x + 0.5f) * SPACING;
					p[i++] = (y + 0.5f) * SPACING;
					p[i++] = (z + 0.5f) * SPACING;
				}
			}
		}

		FluidSurfaceMesher.Mesh mesh = mesher().mesh(p, null, n * n * n, null);

		assertClosed(mesh);
		// No internal surfaces: every vertex lies on the outside of the particle block.
		for (int v = 0; v < mesh.vertexCount; v++) {
			float x = vx(mesh, v);
			float y = vy(mesh, v);
			float z = vz(mesh, v);
			boolean nearOutside = x < 0.1f || x > 0.9f || y < 0.1f || y > 0.9f || z < 0.1f || z > 0.9f;
			assertTrue(nearOutside, "internal vertex at " + x + " " + y + " " + z);
			assertTrue(x > -0.25f && x < 1.25f && y > -0.25f && y < 1.25f && z > -0.25f && z < 1.25f);
		}
		// The top face is flat and points up.
		int top = 0;
		for (int v = 0; v < mesh.vertexCount; v++) {
			float x = vx(mesh, v);
			float z = vz(mesh, v);
			if (x > 0.3f && x < 0.7f && z > 0.3f && z < 0.7f && vy(mesh, v) > 0.5f) {
				top++;
				assertTrue(ny(mesh, v) > 0.95f, "top normal not up: " + ny(mesh, v));
			}
		}
		assertTrue(top > 0);

		// Seen from the top, the water behind the surface is about one block deep.
		for (int v = 0; v < mesh.vertexCount; v++) {
			float x = vx(mesh, v);
			float z = vz(mesh, v);
			if (x > 0.3f && x < 0.7f && z > 0.3f && z < 0.7f && vy(mesh, v) > 0.5f) {
				float thickness = mesh.vertices[v * FluidSurfaceMesher.VERTEX_STRIDE + 9];
				assertTrue(thickness > 0.7f && thickness < 1.3f, "thickness " + thickness);
			}
		}
	}

	@Test
	void quadWindingMatchesNormals() {
		Random random = new Random(42);
		int count = 400;
		float[] p = new float[count * 3];
		for (int i = 0; i < p.length; i++) {
			p[i] = random.nextFloat() * 1.5f;
		}

		FluidSurfaceMesher.Mesh mesh = mesher().mesh(p, null, count, null);

		assertClosed(mesh);
		int agree = 0;
		for (int q = 0; q < mesh.quadCount; q++) {
			int a = mesh.quads[q * 4];
			int b = mesh.quads[q * 4 + 1];
			int c = mesh.quads[q * 4 + 2];
			float[] e1 = {vx(mesh, b) - vx(mesh, a), vy(mesh, b) - vy(mesh, a), vz(mesh, b) - vz(mesh, a)};
			float[] e2 = {vx(mesh, c) - vx(mesh, a), vy(mesh, c) - vy(mesh, a), vz(mesh, c) - vz(mesh, a)};
			float fx = e1[1] * e2[2] - e1[2] * e2[1];
			float fy = e1[2] * e2[0] - e1[0] * e2[2];
			float fz = e1[0] * e2[1] - e1[1] * e2[0];
			if (fx * nx(mesh, a) + fy * ny(mesh, a) + fz * nz(mesh, a) > 0f) {
				agree++;
			}
		}
		// Surface nets quads are not planar, so allow a few borderline cases.
		assertTrue(agree > mesh.quadCount * 0.9, agree + " of " + mesh.quadCount);
	}

	@Test
	void velocityIsAveragedIntoVerticesAndField() {
		float[] p = {0f, 0f, 0f, 0.1f, 0f, 0f};
		float[] v = {4f, 0f, -2f, 4f, 0f, -2f};
		FluidSurfaceMesher mesher = mesher();
		FluidSurfaceMesher.Mesh mesh = mesher.mesh(p, v, 2, null);
		assertTrue(mesh.vertexCount > 0);
		for (int i = 0; i < mesh.vertexCount; i++) {
			int o = i * FluidSurfaceMesher.VERTEX_STRIDE;
			assertEquals(4f, mesh.vertices[o + 6], 1e-3f);
			assertEquals(0f, mesh.vertices[o + 7], 1e-3f);
			assertEquals(-2f, mesh.vertices[o + 8], 1e-3f);
		}
		float[] out = new float[3];
		mesher.velocityAt(0.05f, 0f, 0f, out);
		assertEquals(4f, out[0], 1e-3f);
		assertEquals(-2f, out[2], 1e-3f);
	}

	@Test
	void parallelMeshingMatchesSequential() throws Exception {
		Random random = new Random(7);
		int count = 30_000;
		float[] p = new float[count * 3];
		float[] v = new float[count * 3];
		for (int i = 0; i < count; i++) {
			// A puddle plus a falling column, spread over many bricks (including negative coordinates).
			boolean column = i % 3 == 0;
			p[i * 3] = column ? random.nextFloat() : random.nextFloat() * 12f - 6f;
			p[i * 3 + 1] = column ? random.nextFloat() * 6f : random.nextFloat() * 0.3f;
			p[i * 3 + 2] = column ? random.nextFloat() : random.nextFloat() * 12f - 6f;
			v[i * 3 + 1] = column ? -3f : 0f;
		}

		FluidSurfaceMesher.Mesh sequential = mesher().mesh(p, v, count, null);
		java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(4);
		try {
			FluidSurfaceMesher.Mesh parallel = new FluidSurfaceMesher(SPACING, SPACING * 2f, 0.6f, pool).mesh(p, v, count, null);
			assertEquals(sequential.vertexCount, parallel.vertexCount);
			assertEquals(sequential.quadCount, parallel.quadCount);
			assertEquals(vertexSet(sequential), vertexSet(parallel));
			assertClosed(parallel);
		} finally {
			pool.shutdown();
		}
	}

	@Test
	void sparseParticlesAreTheIsolatedOnes() {
		// A dense block plus one lone droplet far away.
		int n = 8;
		float[] p = new float[(n * n * n + 1) * 3];
		int i = 0;
		for (int x = 0; x < n; x++) {
			for (int y = 0; y < n; y++) {
				for (int z = 0; z < n; z++) {
					p[i++] = (x + 0.5f) * SPACING;
					p[i++] = (y + 0.5f) * SPACING;
					p[i++] = (z + 0.5f) * SPACING;
				}
			}
		}
		p[i++] = 5f;
		p[i++] = 5f;
		p[i] = 5f;
		FluidSurfaceMesher mesher = new FluidSurfaceMesher(SPACING, SPACING * 3f, 0.3f * FluidSurfaceMesher.restDensity(SPACING, SPACING * 3f));
		mesher.mesh(p, null, n * n * n + 1, null);
		int[] sparse = mesher.sparseParticles(p, n * n * n + 1);
		assertTrue(java.util.Arrays.stream(sparse).anyMatch(index -> index == n * n * n), "lone droplet not sparse");
		// At most the eight corner particles of the block may also fall below the cut-off.
		assertTrue(sparse.length <= 9, "too many sparse particles: " + sparse.length);
	}

	private static java.util.Set<String> vertexSet(FluidSurfaceMesher.Mesh mesh) {
		java.util.Set<String> set = new java.util.HashSet<>();
		for (int v = 0; v < mesh.vertexCount; v++) {
			set.add(String.format(java.util.Locale.ROOT, "%.4f %.4f %.4f", vx(mesh, v), vy(mesh, v), vz(mesh, v)));
		}
		return set;
	}

	@Test
	void meshCanBeReused() {
		FluidSurfaceMesher mesher = mesher();
		FluidSurfaceMesher.Mesh mesh = mesher.mesh(new float[] {0f, 0f, 0f}, null, 1, null);
		int quads = mesh.quadCount;
		mesher.mesh(new float[] {100f, -50f, 3f}, null, 1, mesh);
		assertEquals(quads, mesh.quadCount);
		assertTrue(vx(mesh, 0) > 99f);
	}

	@Test
	void restDensityGrowsWithKernelRadius() {
		float small = FluidSurfaceMesher.restDensity(SPACING, SPACING * 2f);
		float large = FluidSurfaceMesher.restDensity(SPACING, SPACING * 3f);
		assertTrue(small > 1f);
		assertTrue(large > small);
	}

	@Test
	void densityAtSeparatesBodyFromSpray() {
		FluidSurfaceMesher mesher = mesher();
		float[] p = {0f, 0f, 0f};
		mesher.mesh(p, null, 1, null);
		assertTrue(mesher.densityAt(0f, 0f, 0f) > 0.9f);
		assertEquals(0f, mesher.densityAt(10f, 10f, 10f));
	}

	/**
	 * The surface is watertight: every edge is shared by an even number of quads. Naive surface nets
	 * may join two sheets at one edge in ambiguous cells (four quads), which is still closed.
	 */
	private static void assertClosed(FluidSurfaceMesher.Mesh mesh) {
		Map<Long, Integer> edges = new HashMap<>();
		for (int q = 0; q < mesh.quadCount; q++) {
			for (int e = 0; e < 4; e++) {
				int a = mesh.quads[q * 4 + e];
				int b = mesh.quads[q * 4 + (e + 1) % 4];
				long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
				edges.merge(key, 1, Integer::sum);
			}
		}
		for (Map.Entry<Long, Integer> entry : edges.entrySet()) {
			assertEquals(0, entry.getValue() % 2, "open edge");
		}
	}

	private static float vx(FluidSurfaceMesher.Mesh m, int v) {
		return m.vertices[v * FluidSurfaceMesher.VERTEX_STRIDE];
	}

	private static float vy(FluidSurfaceMesher.Mesh m, int v) {
		return m.vertices[v * FluidSurfaceMesher.VERTEX_STRIDE + 1];
	}

	private static float vz(FluidSurfaceMesher.Mesh m, int v) {
		return m.vertices[v * FluidSurfaceMesher.VERTEX_STRIDE + 2];
	}

	private static float nx(FluidSurfaceMesher.Mesh m, int v) {
		return m.vertices[v * FluidSurfaceMesher.VERTEX_STRIDE + 3];
	}

	private static float ny(FluidSurfaceMesher.Mesh m, int v) {
		return m.vertices[v * FluidSurfaceMesher.VERTEX_STRIDE + 4];
	}

	private static float nz(FluidSurfaceMesher.Mesh m, int v) {
		return m.vertices[v * FluidSurfaceMesher.VERTEX_STRIDE + 5];
	}
}
