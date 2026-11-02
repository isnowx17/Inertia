package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FluidSurfaceMesherTest {
	/** Grid cell size; particles are denser than the grid, as in the game (about 4 per cell). */
	private static final float CELL = 0.125f;
	private static final float SPACING = CELL / 1.6f;
	private static final float RADIUS = CELL * 2f;
	private static final float THRESHOLD = 0.3f;

	private static FluidSurfaceMesher mesher() {
		return new FluidSurfaceMesher(CELL, SPACING, RADIUS, THRESHOLD);
	}

	/** Particles on a lattice filling the box [x0, x1) x [y0, y1) x [z0, z1). */
	private static float[] box(float x0, float y0, float z0, float x1, float y1, float z1) {
		int nx = Math.round((x1 - x0) / SPACING);
		int ny = Math.round((y1 - y0) / SPACING);
		int nz = Math.round((z1 - z0) / SPACING);
		float[] p = new float[nx * ny * nz * 3];
		int i = 0;
		for (int x = 0; x < nx; x++) {
			for (int y = 0; y < ny; y++) {
				for (int z = 0; z < nz; z++) {
					p[i++] = x0 + (x + 0.5f) * SPACING;
					p[i++] = y0 + (y + 0.5f) * SPACING;
					p[i++] = z0 + (z + 0.5f) * SPACING;
				}
			}
		}
		return p;
	}

	@Test
	void noParticlesGiveEmptyMesh() {
		FluidSurfaceMesher.Mesh mesh = mesher().mesh(new float[0], null, 0, null);
		assertTrue(mesh.isEmpty());
		assertEquals(0, mesh.vertexCount);
	}

	@Test
	void smallClumpGivesClosedBlobAroundIt() {
		float[] p = box(0.3f, 5f, -2.2f, 0.55f, 5.25f, -1.95f);
		float cx = 0.425f;
		float cy = 5.125f;
		float cz = -2.075f;
		FluidSurfaceMesher.Mesh mesh = mesher().mesh(p, null, p.length / 3, null);

		assertTrue(mesh.quadCount > 0);
		assertClosed(mesh);
		int outward = 0;
		for (int v = 0; v < mesh.vertexCount; v++) {
			float dx = vx(mesh, v) - cx;
			float dy = vy(mesh, v) - cy;
			float dz = vz(mesh, v) - cz;
			float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			assertTrue(dist < 0.45f, "vertex far from the clump: " + dist);
			if (dx * nx(mesh, v) + dy * ny(mesh, v) + dz * nz(mesh, v) > 0f) {
				outward++;
			}
		}
		assertTrue(outward >= mesh.vertexCount * 0.9, outward + " of " + mesh.vertexCount);
	}

	@Test
	void blockOfWaterMeltsIntoOneSmoothBox() {
		// One block of water at rest spacing in [0, 1).
		float[] p = box(0f, 0f, 0f, 1f, 1f, 1f);
		FluidSurfaceMesher.Mesh mesh = mesher().mesh(p, null, p.length / 3, null);

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
		// Random water at about 80 % of still-water density: a lumpy body with holes.
		Random random = new Random(42);
		int count = (int) (0.8f * 1.5f * 1.5f * 1.5f / (SPACING * SPACING * SPACING));
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
		float[] p = box(0f, 0f, 0f, 0.5f, 0.5f, 0.5f);
		float[] v = new float[p.length];
		for (int i = 0; i < v.length; i += 3) {
			v[i] = 4f;
			v[i + 2] = -2f;
		}
		FluidSurfaceMesher mesher = mesher();
		FluidSurfaceMesher.Mesh mesh = mesher.mesh(p, v, p.length / 3, null);
		assertTrue(mesh.vertexCount > 0);
		for (int i = 0; i < mesh.vertexCount; i++) {
			int o = i * FluidSurfaceMesher.VERTEX_STRIDE;
			assertEquals(4f, mesh.vertices[o + 6], 1e-3f);
			assertEquals(0f, mesh.vertices[o + 7], 1e-3f);
			assertEquals(-2f, mesh.vertices[o + 8], 1e-3f);
		}
		float[] out = new float[3];
		mesher.velocityAt(0.25f, 0.25f, 0.25f, out);
		assertEquals(4f, out[0], 1e-3f);
		assertEquals(-2f, out[2], 1e-3f);
	}

	@Test
	void parallelMeshingMatchesSequential() throws Exception {
		Random random = new Random(7);
		int count = 120_000;
		float[] p = new float[count * 3];
		float[] v = new float[count * 3];
		for (int i = 0; i < count; i++) {
			// A puddle plus a falling column, spread over many bricks (including negative coordinates).
			boolean column = i % 2 == 0;
			p[i * 3] = column ? random.nextFloat() : random.nextFloat() * 6f - 3f;
			p[i * 3 + 1] = column ? random.nextFloat() * 6f : random.nextFloat() * 0.4f;
			p[i * 3 + 2] = column ? random.nextFloat() : random.nextFloat() * 6f - 3f;
			v[i * 3 + 1] = column ? -3f : 0f;
		}

		FluidSurfaceMesher.Mesh sequential = mesher().mesh(p, v, count, null);
		java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(4);
		try {
			FluidSurfaceMesher.Mesh parallel = new FluidSurfaceMesher(CELL, SPACING, RADIUS, THRESHOLD, pool).mesh(p, v, count, null);
			assertTrue(sequential.quadCount > 0);
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
		float[] block = box(0f, 0f, 0f, 1f, 1f, 1f);
		int n = block.length / 3;
		float[] p = java.util.Arrays.copyOf(block, block.length + 3);
		p[block.length] = 5f;
		p[block.length + 1] = 5f;
		p[block.length + 2] = 5f;
		FluidSurfaceMesher mesher = mesher();
		mesher.mesh(p, null, n + 1, null);
		int[] sparse = mesher.sparseParticles(p, n + 1);
		assertTrue(java.util.Arrays.stream(sparse).anyMatch(index -> index == n), "lone droplet not sparse");
		// Only particles right at the corners of the block may also fall below the cut-off.
		assertTrue(sparse.length <= 1 + 8 * 4, "too many sparse particles: " + sparse.length);
	}

	@Test
	void sparseParticlesOfTheMeshMatchAFullScan() {
		Random random = new Random(3);
		float[] block = box(0f, 0f, 0f, 2f, 1f, 2f);
		int n = block.length / 3;
		int loose = 400;
		float[] p = java.util.Arrays.copyOf(block, block.length + loose * 3);
		for (int i = 0; i < loose * 3; i++) {
			p[block.length + i] = random.nextFloat() * 6f - 2f;
		}
		FluidSurfaceMesher mesher = mesher();
		mesher.mesh(p, null, n + loose, null);
		int[] fromBins = mesher.sparseParticles(p, n + loose);
		// A copy is not the meshed array, so this takes the full scan.
		int[] fullScan = mesher.sparseParticles(p.clone(), n + loose);
		assertEquals(java.util.Arrays.toString(fullScan), java.util.Arrays.toString(fromBins));
		assertTrue(fromBins.length > loose / 2);
	}

	@Test
	void deepWaterIsSolidInsideAndMeshedOnlyOutside() {
		// 6 x 6 x 6 blocks: the middle bricks are surrounded by full bricks and are not splatted.
		float[] p = box(0f, 0f, 0f, 6f, 6f, 6f);
		FluidSurfaceMesher mesher = new FluidSurfaceMesher(CELL, SPACING, RADIUS, THRESHOLD, null);
		FluidSurfaceMesher.Mesh mesh = mesher.mesh(p, null, p.length / 3, 3, null);
		assertClosed(mesh);
		for (int v = 0; v < mesh.vertexCount; v++) {
			float x = vx(mesh, v);
			float y = vy(mesh, v);
			float z = vz(mesh, v);
			boolean nearOutside = x < 0.2f || x > 5.8f || y < 0.2f || y > 5.8f || z < 0.2f || z > 5.8f;
			assertTrue(nearOutside, "internal vertex at " + x + " " + y + " " + z);
		}
		assertTrue(mesher.densityAt(3f, 3f, 3f) > mesher.threshold());
		// Seen from the top, the water behind the surface is far deeper than a few blocks.
		for (int v = 0; v < mesh.vertexCount; v++) {
			if (vy(mesh, v) > 5.8f && vx(mesh, v) > 2f && vx(mesh, v) < 4f && vz(mesh, v) > 2f && vz(mesh, v) < 4f) {
				assertTrue(mesh.vertices[v * FluidSurfaceMesher.VERTEX_STRIDE + 9] > 4f);
			}
		}
	}

	@Test
	void strideFourInputGivesTheSameSurface() {
		float[] p3 = box(0f, 0f, 0f, 1f, 0.5f, 1f);
		int n = p3.length / 3;
		float[] p4 = new float[n * 4];
		for (int i = 0; i < n; i++) {
			System.arraycopy(p3, i * 3, p4, i * 4, 3);
			p4[i * 4 + 3] = 123f;
		}
		FluidSurfaceMesher.Mesh a = mesher().mesh(p3, null, n, 3, null);
		FluidSurfaceMesher.Mesh b = mesher().mesh(p4, null, n, 4, null);
		assertEquals(vertexSet(a), vertexSet(b));
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
		float[] clump = box(0f, 0f, 0f, 0.25f, 0.25f, 0.25f);
		float[] moved = clump.clone();
		for (int i = 0; i < moved.length; i += 3) {
			moved[i] += 100f;
			moved[i + 1] -= 50f;
			moved[i + 2] += 3f;
		}
		FluidSurfaceMesher.Mesh mesh = mesher.mesh(clump, null, clump.length / 3, null);
		int quads = mesh.quadCount;
		assertTrue(quads > 0);
		mesher.mesh(moved, null, moved.length / 3, mesh);
		assertEquals(quads, mesh.quadCount);
		assertTrue(vx(mesh, 0) > 99f);
	}

	@Test
	void stillWaterHasRestDensity() {
		assertEquals(1.6f * 1.6f * 1.6f, FluidSurfaceMesher.restDensity(SPACING, CELL), 1e-4f);
		float[] p = box(0f, 0f, 0f, 2f, 2f, 2f);
		FluidSurfaceMesher mesher = mesher();
		mesher.mesh(p, null, p.length / 3, null);
		assertEquals(mesher.restDensity(), mesher.densityAt(1f, 1f, 1f), mesher.restDensity() * 0.05f);
		assertEquals(THRESHOLD * mesher.restDensity(), mesher.threshold(), 1e-5f);
	}

	@Test
	void densityAtSeparatesBodyFromSpray() {
		FluidSurfaceMesher mesher = mesher();
		float[] p = box(0f, 0f, 0f, 0.5f, 0.5f, 0.5f);
		mesher.mesh(p, null, p.length / 3, null);
		assertTrue(mesher.densityAt(0.25f, 0.25f, 0.25f) > mesher.threshold());
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
