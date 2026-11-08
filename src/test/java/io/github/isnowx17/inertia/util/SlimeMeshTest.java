package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SlimeMeshTest {
	@Test
	void tetrahedraFillTheCubeAndFaceTheRightWay() {
		SlimeMesh mesh = new SlimeMesh(4);
		assertEquals(125, mesh.particleCount());
		assertEquals(4 * 4 * 4 * 6, mesh.tetCount());
		double total = 0;
		for (float v : mesh.volume) {
			assertTrue(v > 0);
			total += v;
		}
		assertEquals(1.0, total, 1e-5);
		// Dm^-1 times Dm is the identity for every tetrahedron.
		for (int t = 0; t < mesh.tetCount(); t++) {
			int a = mesh.tets[t * 4];
			for (int row = 0; row < 3; row++) {
				for (int col = 0; col < 3; col++) {
					double sum = 0;
					for (int k = 0; k < 3; k++) {
						int other = mesh.tets[t * 4 + 1 + col];
						sum += mesh.invDm[t * 9 + row * 3 + k] * (mesh.rest[other * 3 + k] - mesh.rest[a * 3 + k]);
					}
					assertEquals(row == col ? 1.0 : 0.0, sum, 1e-4);
				}
			}
		}
	}

	@Test
	void surfaceQuadsFaceOutwards() {
		SlimeMesh mesh = new SlimeMesh(4);
		assertEquals(6 * 16, mesh.quadCount());
		float[] r = mesh.rest;
		for (int q = 0; q < mesh.quadCount(); q++) {
			int a = mesh.quads[q * 4], b = mesh.quads[q * 4 + 1], d = mesh.quads[q * 4 + 3];
			float ux = r[b * 3] - r[a * 3], uy = r[b * 3 + 1] - r[a * 3 + 1], uz = r[b * 3 + 2] - r[a * 3 + 2];
			float vx = r[d * 3] - r[a * 3], vy = r[d * 3 + 1] - r[a * 3 + 1], vz = r[d * 3 + 2] - r[a * 3 + 2];
			float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			// Outwards: along the direction from the cube's centre to the quad.
			float cx = (r[a * 3] + r[b * 3] + r[d * 3]) / 3 - 0.5f, cy = (r[a * 3 + 1] + r[b * 3 + 1] + r[d * 3 + 1]) / 3 - 0.5f;
			float cz = (r[a * 3 + 2] + r[b * 3 + 2] + r[d * 3 + 2]) / 3 - 0.5f;
			assertTrue(nx * cx + ny * cy + nz * cz > 0, "quad " + q + " faces inwards");
		}
	}
}
