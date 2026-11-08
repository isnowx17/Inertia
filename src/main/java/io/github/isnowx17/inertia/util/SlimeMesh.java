package io.github.isnowx17.inertia.util;

/**
 * The rest shape of a slime block for the finite element solver: a unit cube cut into {@code n}^3
 * small cubes, each into six tetrahedra around its main diagonal (so neighbouring cubes share
 * their faces' diagonals and the mesh is conforming). Also the cube's outside as quads, with texture
 * coordinates for the slime block texture on every face.
 */
public final class SlimeMesh {
	/** Small cubes along each edge. */
	public final int n;
	/** Particles: (n + 1)^3, rest positions in the unit cube, 3 floats each. */
	public final float[] rest;
	/** Tetrahedra: 4 particle indices each, positively oriented. */
	public final int[] tets;
	/** Per tetrahedron: the inverse of its rest shape matrix (row-major 3 x 3). */
	public final float[] invDm;
	/** Per tetrahedron: its rest volume (unit cube). */
	public final float[] volume;
	/** Outside: 4 particle indices per quad, counter-clockwise seen from outside. */
	public final int[] quads;
	/** Texture coordinates per quad corner (u, v), 8 floats per quad. */
	public final float[] quadUv;

	/** The six tetrahedra of a cube by its corners (bit 0: +x, bit 1: +y, bit 2: +z), all sharing 0-7. */
	private static final int[][] KUHN = {
		{0, 1, 3, 7}, {0, 3, 2, 7}, {0, 2, 6, 7}, {0, 6, 4, 7}, {0, 4, 5, 7}, {0, 5, 1, 7}};

	public SlimeMesh(int n) {
		this.n = n;
		int side = n + 1;
		rest = new float[side * side * side * 3];
		for (int z = 0; z <= n; z++) {
			for (int y = 0; y <= n; y++) {
				for (int x = 0; x <= n; x++) {
					int i = index(x, y, z);
					rest[i * 3] = (float) x / n;
					rest[i * 3 + 1] = (float) y / n;
					rest[i * 3 + 2] = (float) z / n;
				}
			}
		}
		int tetCount = n * n * n * 6;
		tets = new int[tetCount * 4];
		invDm = new float[tetCount * 9];
		volume = new float[tetCount];
		int t = 0;
		for (int z = 0; z < n; z++) {
			for (int y = 0; y < n; y++) {
				for (int x = 0; x < n; x++) {
					int[] corner = new int[8];
					for (int c = 0; c < 8; c++) {
						corner[c] = index(x + (c & 1), y + (c >> 1 & 1), z + (c >> 2 & 1));
					}
					for (int[] k : KUHN) {
						int a = corner[k[0]], b = corner[k[1]], c = corner[k[2]], d = corner[k[3]];
						if (signedVolume(a, b, c, d) < 0) {
							int swap = c;
							c = d;
							d = swap;
						}
						tets[t * 4] = a;
						tets[t * 4 + 1] = b;
						tets[t * 4 + 2] = c;
						tets[t * 4 + 3] = d;
						volume[t] = signedVolume(a, b, c, d);
						invert(a, b, c, d, invDm, t * 9);
						t++;
					}
				}
			}
		}
		// Outside: per face of the cube n x n quads.
		quads = new int[6 * n * n * 4];
		quadUv = new float[6 * n * n * 8];
		int q = 0;
		for (int face = 0; face < 6; face++) {
			for (int b = 0; b < n; b++) {
				for (int a = 0; a < n; a++) {
					for (int k = 0; k < 4; k++) {
						// Corners counter-clockwise seen from outside: (a, b), (a+1, b), (a+1, b+1), (a, b+1)
						// in the face's own (u, v) directions.
						int ua = a + (k == 1 || k == 2 ? 1 : 0);
						int vb = b + (k >= 2 ? 1 : 0);
						quads[q * 4 + k] = facePoint(face, ua, vb);
						quadUv[q * 8 + k * 2] = (float) ua / n;
						quadUv[q * 8 + k * 2 + 1] = 1f - (float) vb / n;
					}
					q++;
				}
			}
		}
	}

	public int particleCount() {
		return rest.length / 3;
	}

	public int tetCount() {
		return volume.length;
	}

	public int quadCount() {
		return quads.length / 4;
	}

	private int index(int x, int y, int z) {
		int side = n + 1;
		return (z * side + y) * side + x;
	}

	/**
	 * Particle at (u, v) on a face, with u, v such that u x v points outwards: -x, +x, -y, +y, -z, +z.
	 */
	private int facePoint(int face, int u, int v) {
		return switch (face) {
			case 0 -> index(0, v, u);         // -x: u along +z, v along +y -> u x v = -x
			case 1 -> index(n, v, n - u);     // +x: u along -z, v along +y -> +x
			case 2 -> index(u, 0, v);         // -y: u along +x, v along +z -> -y
			case 3 -> index(u, n, n - v);     // +y: u along +x, v along -z -> +y
			case 4 -> index(n - u, v, 0);     // -z: u along -x, v along +y -> -z
			default -> index(u, v, n);        // +z: u along +x, v along +y -> +z
		};
	}

	private float signedVolume(int a, int b, int c, int d) {
		float[] r = rest;
		float x1 = r[b * 3] - r[a * 3], y1 = r[b * 3 + 1] - r[a * 3 + 1], z1 = r[b * 3 + 2] - r[a * 3 + 2];
		float x2 = r[c * 3] - r[a * 3], y2 = r[c * 3 + 1] - r[a * 3 + 1], z2 = r[c * 3 + 2] - r[a * 3 + 2];
		float x3 = r[d * 3] - r[a * 3], y3 = r[d * 3 + 1] - r[a * 3 + 1], z3 = r[d * 3 + 2] - r[a * 3 + 2];
		return (x1 * (y2 * z3 - z2 * y3) - y1 * (x2 * z3 - z2 * x3) + z1 * (x2 * y3 - y2 * x3)) / 6f;
	}

	/** Inverse of Dm = [b - a, c - a, d - a] (as columns), row-major, into out[o..o+8]. */
	private void invert(int a, int b, int c, int d, float[] out, int o) {
		float[] r = rest;
		double[][] m = new double[3][3];
		int[] other = {b, c, d};
		for (int col = 0; col < 3; col++) {
			for (int row = 0; row < 3; row++) {
				m[row][col] = r[other[col] * 3 + row] - r[a * 3 + row];
			}
		}
		double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
			+ m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
		double[][] inv = {
			{m[1][1] * m[2][2] - m[1][2] * m[2][1], m[0][2] * m[2][1] - m[0][1] * m[2][2], m[0][1] * m[1][2] - m[0][2] * m[1][1]},
			{m[1][2] * m[2][0] - m[1][0] * m[2][2], m[0][0] * m[2][2] - m[0][2] * m[2][0], m[0][2] * m[1][0] - m[0][0] * m[1][2]},
			{m[1][0] * m[2][1] - m[1][1] * m[2][0], m[0][1] * m[2][0] - m[0][0] * m[2][1], m[0][0] * m[1][1] - m[0][1] * m[1][0]}};
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				out[o + row * 3 + col] = (float) (inv[row][col] / det);
			}
		}
	}
}
