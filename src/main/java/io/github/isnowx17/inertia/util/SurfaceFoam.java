package io.github.isnowx17.inertia.util;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * Foam floating on the water surface.
 *
 * <p>The foam is made of many small tracers that float on the top surface of the water. They are
 * spawned where the water churns (where the surface flows fast, and where PhysX spawns diffuse
 * particles at the surface), drift with the surface flow, are stirred by small eddies and pushed
 * aside by boils (upwellings that sweep foam outwards, so it gathers into lines and rings between
 * them), and fade out after a few seconds. Stretched and folded by the flow, the tracers form the
 * swirling lines, patches and rings of real foam.
 *
 * <p>After every step the tracers are splatted into a fine top-down {@link Map foam map} with mip
 * levels, which the water shader samples: per texel the amount of foam and the height of the
 * surface the foam floats on.
 *
 * <p>All positions are in blocks relative to the simulation origin. Not thread-safe: one thread
 * calls the methods of an instance (work inside a call may run on the given pool).
 */
public final class SurfaceFoam {
	// Emission ----------------------------------------------------------------------------------

	/** Surface speed (blocks/second) where the water starts to foam, and where it foams fully. */
	private static final float CHURN_MIN_SPEED = 1.4f;
	private static final float CHURN_FULL_SPEED = 3.2f;
	/** Tracers spawned per square block and second where the surface foams fully. */
	private static final float CHURN_RATE = 1500f;
	/** Tracers spawned per second for a fresh PhysX diffuse particle at the surface. */
	private static final float DIFFUSE_RATE = 6f;
	/** Diffuse particles count when they are at most this far below / above the surface. */
	private static final float DIFFUSE_BELOW = 0.35f;
	private static final float DIFFUSE_ABOVE = 0.5f;
	/** At most this many tracers are spawned per square block and second. */
	private static final float MAX_RATE = 4000f;

	// Tracers -----------------------------------------------------------------------------------

	/** Tracer lifetimes are spread over this range, as multiples of the configured lifetime. */
	private static final float LIFE_MIN = 0.8f;
	private static final float LIFE_MAX = 2.3f;
	/** Seconds a new tracer takes to fade in. */
	private static final float FADE_IN = 0.15f;
	/** Tracers fade out over this last fraction of their life. */
	private static final float FADE_OUT = 0.45f;
	/** Tracer brightness is spread between this and 1. */
	private static final float WEIGHT_MIN = 0.5f;
	/** Tracers moved per parallel task. */
	private static final int CHUNK = 8192;

	// Motion ------------------------------------------------------------------------------------

	/** Speed of the small eddies (blocks/second), relative to 0.25 + half the flow speed. */
	private static final float EDDY_STRENGTH = 0.35f;
	/** Strongest outward push of a boil (blocks/second), for the large and the small boils. */
	private static final float[] BOIL_STRENGTH = {0.45f, 0.22f};

	// Surface -----------------------------------------------------------------------------------

	/** Vertices whose normal points up at least this much belong to a top surface. */
	private static final float TOP_FACING = 0.3f;
	/** Per column, top vertices up to this far above the lowest one belong to the same surface. */
	private static final float SURFACE_BAND = 0.6f;
	/** Columns without a vertex are filled from their neighbours this many times. */
	private static final int FILL_PASSES = 2;
	/** Most columns of the surface grid; bigger water uses coarser columns. */
	private static final int MAX_COLUMNS = 2_000_000;

	// Map ---------------------------------------------------------------------------------------

	/** Weighted tracers per square block at which 63 % of the surface is covered (coverage 1). */
	private static final float FULL_DENSITY = 1700f;
	/** Largest foam map side in texels; bigger foam areas use a coarser map. */
	private static final int MAX_MAP_SIZE = 1024;
	private static final int MIN_MAP_SIZE = 64;
	public static final int MAX_LEVELS = 6;
	/** Empty texels kept around the foam. */
	private static final int MAP_MARGIN = 3;
	/** Height code steps per block (the green channel covers 16 blocks). */
	public static final float HEIGHT_STEPS = 16f;
	private static final int EXP_TABLE_SIZE = 2048;
	private static final float EXP_TABLE_RANGE = 8f;
	private static final byte[] COVER_TABLE = new byte[EXP_TABLE_SIZE + 1];

	static {
		for (int i = 0; i <= EXP_TABLE_SIZE; i++) {
			double x = i * (double) EXP_TABLE_RANGE / EXP_TABLE_SIZE;
			COVER_TABLE[i] = (byte) Math.round((1.0 - Math.exp(-x)) * 255.0);
		}
	}

	/**
	 * @param maxTracers     most tracers alive at once
	 * @param texelsPerBlock foam map resolution
	 * @param emission       how much foam forms, 1 = default
	 * @param lifetime       average tracer lifetime in seconds (tracers live 0.8 to 2.3 times this)
	 * @param lineWidth      width of the foam lines, 1 = default
	 * @param coverage       how quickly overlapping foam turns opaque, 1 = default
	 */
	public record Settings(int maxTracers, int texelsPerBlock, float emission, float lifetime, float lineWidth, float coverage) {
		public Settings {
			maxTracers = Math.max(0, maxTracers);
			texelsPerBlock = Math.max(4, Math.min(64, texelsPerBlock));
			emission = Math.max(0f, emission);
			lifetime = Math.max(0.1f, lifetime);
			lineWidth = Math.max(0.25f, lineWidth);
			coverage = Math.max(0.01f, coverage);
		}
	}

	/**
	 * The foam map: two bytes per texel (red: amount of foam, 0..1; green: height of the surface
	 * the foam floats on, see {@link #heightCode}), for every mip level one after the other.
	 * Texel (i, j) of level 0 covers blocks [(originX + i) / texelsPerBlock, ...).
	 */
	public static final class Map {
		public int width;
		public int height;
		public int levels;
		public float texelsPerBlock;
		public int originX;
		public int originZ;
		/** Local y of height code 0. */
		public float baseHeight;
		public byte[] data = new byte[0];
		/** A copy of {@link #data} in native memory, ready for the GPU upload (filled by the renderer's job), or {@code null}. */
		public java.nio.ByteBuffer direct;
		public final int[] offsets = new int[MAX_LEVELS];
		public boolean empty = true;
		/** Increases with every rebuild. */
		public long version;

		/** Texture coordinate of a local x position. */
		public float u(float x) {
			return (x * texelsPerBlock - originX) / width;
		}

		/** Texture coordinate of a local z position. */
		public float v(float z) {
			return (z * texelsPerBlock - originZ) / height;
		}

		/** Height of a local y position as stored in the green channel (0..255). */
		public int heightCode(float y) {
			return Math.max(0, Math.min(255, Math.round((y - baseHeight) * HEIGHT_STEPS)));
		}

		/** Foam amount (0..1) at a local position, bilinear from level 0; 0 outside the map. */
		public float amountAt(float x, float z) {
			if (empty) {
				return 0f;
			}
			float fu = x * texelsPerBlock - originX - 0.5f;
			float fv = z * texelsPerBlock - originZ - 0.5f;
			int i = (int) Math.floor(fu);
			int j = (int) Math.floor(fv);
			float tx = fu - i;
			float tz = fv - j;
			return (texel(i, j) * (1 - tx) * (1 - tz) + texel(i + 1, j) * tx * (1 - tz)
				+ texel(i, j + 1) * (1 - tx) * tz + texel(i + 1, j + 1) * tx * tz) / 255f;
		}

		/** Surface height code (0..255) at a local position, nearest texel of level 0. */
		public int heightCodeAt(float x, float z) {
			if (empty) {
				return 0;
			}
			int i = (int) Math.floor(x * texelsPerBlock - originX);
			int j = (int) Math.floor(z * texelsPerBlock - originZ);
			if (i < 0 || j < 0 || i >= width || j >= height) {
				return 0;
			}
			return data[(i + j * width) * 2 + 1] & 0xFF;
		}

		private int texel(int i, int j) {
			if (i < 0 || j < 0 || i >= width || j >= height) {
				return 0;
			}
			return data[(i + j * width) * 2] & 0xFF;
		}
	}

	private final ForkJoinPool pool;
	private final SplittableRandom random;
	private Settings settings;
	private double time;

	// Surface columns (top view of the water).
	private boolean surfaceValid;
	private float cell = 1f;
	private int colX0;
	private int colZ0;
	private int cols;
	private int rows;
	private float[] colVx = new float[0];
	private float[] colVy = new float[0];
	private float[] colVz = new float[0];
	private float[] colHeight = new float[0];
	private float[] colCount = new float[0];
	private float[] colRate = new float[0];
	/** 0: dry, 1: water seen directly, 2: filled in from neighbours. */
	private byte[] colState = new byte[0];
	private byte[] stateScratch = new byte[0];

	// Tracers.
	private float[] px = new float[0];
	private float[] pz = new float[0];
	private float[] age = new float[0];
	private float[] life = new float[0];
	private float[] weight = new float[0];
	private int count;

	// Turbulence for the current time.
	private final float[] eddies = new float[Turbulence.EDDY_RES * Turbulence.EDDY_RES * 2];
	private final float[] boilEnvelope = new float[Turbulence.BOIL_COUNT];

	// Map scratch.
	private float[] accumulation = new float[0];
	private float[] blurScratch = new float[0];
	private byte[] columnCodes = new byte[0];
	private int[] texelColumns = new int[0];

	public SurfaceFoam(Settings settings, long seed, ForkJoinPool pool) {
		this.pool = pool;
		this.random = new SplittableRandom(seed);
		setSettings(settings);
	}

	public void setSettings(Settings settings) {
		this.settings = settings;
		if (px.length != settings.maxTracers()) {
			int keep = Math.min(count, settings.maxTracers());
			px = Arrays.copyOf(px, settings.maxTracers());
			pz = Arrays.copyOf(pz, settings.maxTracers());
			age = Arrays.copyOf(age, settings.maxTracers());
			life = Arrays.copyOf(life, settings.maxTracers());
			weight = Arrays.copyOf(weight, settings.maxTracers());
			count = keep;
		}
	}

	public Settings settings() {
		return settings;
	}

	/** Removes all foam. */
	public void clear() {
		count = 0;
	}

	public int tracerCount() {
		return count;
	}

	float tracerX(int i) {
		return px[i];
	}

	float tracerZ(int i) {
		return pz[i];
	}

	/** Whether the surface column under (x, z) has water (for tests). */
	boolean wetAt(float x, float z) {
		return surfaceValid && isWet(x, z);
	}

	/** Height of the surface column under (x, z), or NaN where it is dry (for tests). */
	float surfaceHeightAt(float x, float z) {
		if (!wetAt(x, z)) {
			return Float.NaN;
		}
		int i = (int) Math.floor(x / cell) - colX0;
		int j = (int) Math.floor(z / cell) - colZ0;
		return colHeight[i + j * cols];
	}

	/** Horizontal flow velocity of the surface column under (x, z) (for tests). */
	float surfaceVelocityXAt(float x, float z) {
		int i = (int) Math.floor(x / cell) - colX0;
		int j = (int) Math.floor(z / cell) - colZ0;
		return colVx[i + j * cols];
	}

	// ------------------------------------------------------------------------------------------
	// Surface
	// ------------------------------------------------------------------------------------------

	/**
	 * Builds the top view of the water from surface mesh vertices: per column the lowest surface
	 * facing up (so foam stays on a pool even when a stream arcs over it), with its height and the
	 * average flow velocity there.
	 *
	 * @param vertices    x, y, z, nx, ny, nz, vx, vy, vz, ... per vertex
	 * @param vertexCount number of vertices
	 * @param stride      floats per vertex
	 * @param cellSize    column size in blocks (the mesh cell size)
	 */
	public void setSurfaceFromMesh(float[] vertices, int vertexCount, int stride, float cellSize) {
		float minX = Float.POSITIVE_INFINITY;
		float minZ = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float maxZ = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < vertexCount; i++) {
			int o = i * stride;
			if (vertices[o + 4] > TOP_FACING) {
				minX = Math.min(minX, vertices[o]);
				maxX = Math.max(maxX, vertices[o]);
				minZ = Math.min(minZ, vertices[o + 2]);
				maxZ = Math.max(maxZ, vertices[o + 2]);
			}
		}
		if (!(minX <= maxX) || !(minZ <= maxZ)) {
			surfaceValid = false;
			return;
		}
		float c = cellSize;
		while (true) {
			int x0 = (int) Math.floor(minX / c) - FILL_PASSES - 1;
			int z0 = (int) Math.floor(minZ / c) - FILL_PASSES - 1;
			int w = (int) Math.floor(maxX / c) + FILL_PASSES + 2 - x0;
			int h = (int) Math.floor(maxZ / c) + FILL_PASSES + 2 - z0;
			if ((long) w * h <= MAX_COLUMNS) {
				beginSurface(c, x0, z0, w, h);
				break;
			}
			c *= 2f;
		}

		// Pass 1: lowest top surface per column.
		float inv = 1f / cell;
		for (int i = 0; i < vertexCount; i++) {
			int o = i * stride;
			if (vertices[o + 4] > TOP_FACING) {
				int column = column(vertices[o], vertices[o + 2], inv);
				colHeight[column] = Math.min(colHeight[column], vertices[o + 1]);
			}
		}
		// Pass 2: average flow of that surface.
		for (int i = 0; i < vertexCount; i++) {
			int o = i * stride;
			if (vertices[o + 4] > TOP_FACING) {
				int column = column(vertices[o], vertices[o + 2], inv);
				if (vertices[o + 1] <= colHeight[column] + SURFACE_BAND) {
					colVx[column] += vertices[o + 6];
					colVy[column] += vertices[o + 7];
					colVz[column] += vertices[o + 8];
					colCount[column] += 1f;
				}
			}
		}
		finishSurface();
	}

	/**
	 * Sets the surface directly, column by column (for tests and previews). Columns with a
	 * non-finite height are dry.
	 */
	public void setSurface(float cellSize, int x0, int z0, int width, int depth, float[] heights, float[] vx, float[] vy, float[] vz) {
		beginSurface(cellSize, x0, z0, width, depth);
		for (int i = 0; i < width * depth; i++) {
			if (Float.isFinite(heights[i])) {
				colHeight[i] = heights[i];
				colVx[i] = vx[i];
				colVy[i] = vy[i];
				colVz[i] = vz[i];
				colCount[i] = 1f;
			}
		}
		finishSurface();
	}

	private void beginSurface(float cellSize, int x0, int z0, int width, int depth) {
		cell = cellSize;
		colX0 = x0;
		colZ0 = z0;
		cols = width;
		rows = depth;
		int n = width * depth;
		if (colHeight.length < n) {
			int size = Math.max(n, colHeight.length * 3 / 2);
			colVx = new float[size];
			colVy = new float[size];
			colVz = new float[size];
			colHeight = new float[size];
			colCount = new float[size];
			colRate = new float[size];
			colState = new byte[size];
			stateScratch = new byte[size];
		}
		Arrays.fill(colVx, 0, n, 0f);
		Arrays.fill(colVy, 0, n, 0f);
		Arrays.fill(colVz, 0, n, 0f);
		Arrays.fill(colHeight, 0, n, Float.POSITIVE_INFINITY);
		Arrays.fill(colCount, 0, n, 0f);
		Arrays.fill(colState, 0, n, (byte) 0);
	}

	private int column(float x, float z, float inv) {
		int i = (int) Math.floor(x * inv) - colX0;
		int j = (int) Math.floor(z * inv) - colZ0;
		return Math.max(0, Math.min(cols - 1, i)) + Math.max(0, Math.min(rows - 1, j)) * cols;
	}

	private void finishSurface() {
		int n = cols * rows;
		for (int c = 0; c < n; c++) {
			float k = colCount[c];
			if (k > 0f) {
				colVx[c] /= k;
				colVy[c] /= k;
				colVz[c] /= k;
				colState[c] = 1;
			}
		}
		// Fill small gaps (steep spots of the surface have no upward vertex in every column).
		for (int pass = 0; pass < FILL_PASSES; pass++) {
			System.arraycopy(colState, 0, stateScratch, 0, n);
			for (int j = 0; j < rows; j++) {
				for (int i = 0; i < cols; i++) {
					int c = i + j * cols;
					if (stateScratch[c] != 0) {
						continue;
					}
					float sx = 0;
					float sy = 0;
					float sz = 0;
					float sh = 0;
					int k = 0;
					for (int d = 0; d < 4; d++) {
						int ni = i + (d == 0 ? 1 : d == 1 ? -1 : 0);
						int nj = j + (d == 2 ? 1 : d == 3 ? -1 : 0);
						if (ni < 0 || nj < 0 || ni >= cols || nj >= rows) {
							continue;
						}
						int nc = ni + nj * cols;
						if (stateScratch[nc] != 0) {
							sx += colVx[nc];
							sy += colVy[nc];
							sz += colVz[nc];
							sh += colHeight[nc];
							k++;
						}
					}
					if (k >= 2) {
						colVx[c] = sx / k;
						colVy[c] = sy / k;
						colVz[c] = sz / k;
						colHeight[c] = sh / k;
						colState[c] = 2;
					}
				}
			}
		}
		surfaceValid = true;
	}

	// ------------------------------------------------------------------------------------------
	// Simulation
	// ------------------------------------------------------------------------------------------

	/**
	 * Moves, ages and spawns foam for {@code dt} seconds on the current surface.
	 *
	 * @param diffuse         x, y, z and remaining lifetime per PhysX diffuse particle, or {@code null}
	 * @param diffuseCount    number of diffuse particles
	 * @param diffuseLifetime lifetime PhysX gives new diffuse particles, in seconds
	 */
	public void step(float dt, float[] diffuse, int diffuseCount, float diffuseLifetime) {
		if (!(dt > 0f)) {
			return;
		}
		dt = Math.min(dt, 0.1f);
		time += dt;
		if (!surfaceValid) {
			count = 0;
			return;
		}
		updateTurbulence();
		move(dt);
		emit(dt, diffuse, diffuseCount, diffuseLifetime);
	}

	private void updateTurbulence() {
		Turbulence.eddies(time, eddies);
		Turbulence.boilEnvelopes(time, boilEnvelope);
	}

	private void move(float dt) {
		int n = count;
		int chunks = (n + CHUNK - 1) / CHUNK;
		parallelFor(chunks, chunk -> {
			float[] v = new float[2];
			int end = Math.min(n, (chunk + 1) * CHUNK);
			for (int i = chunk * CHUNK; i < end; i++) {
				float a = age[i] + dt;
				float x = px[i];
				float z = pz[i];
				if (a >= life[i] || !velocity(x, z, v)) {
					life[i] = -1f;
					continue;
				}
				float mx = x + 0.5f * dt * v[0];
				float mz = z + 0.5f * dt * v[1];
				if (velocity(mx, mz, v)) {
					float nx = x + dt * v[0];
					float nz = z + dt * v[1];
					// Foam stays where the water ends (against a wall, at the shore) instead of leaving it.
					if (isWet(nx, nz)) {
						px[i] = nx;
						pz[i] = nz;
					}
				}
				age[i] = a;
			}
		});
		int alive = 0;
		for (int i = 0; i < n; i++) {
			if (life[i] > 0f) {
				if (alive != i) {
					px[alive] = px[i];
					pz[alive] = pz[i];
					age[alive] = age[i];
					life[alive] = life[i];
					weight[alive] = weight[i];
				}
				alive++;
			}
		}
		count = alive;
	}

	private boolean isWet(float x, float z) {
		int i = (int) Math.floor(x / cell) - colX0;
		int j = (int) Math.floor(z / cell) - colZ0;
		return i >= 0 && j >= 0 && i < cols && j < rows && colState[i + j * cols] != 0;
	}

	/** Velocity of the foam at (x, z) into {@code out}; false where there is no water. */
	private boolean velocity(float x, float z, float[] out) {
		float gx = x / cell - colX0;
		float gz = z / cell - colZ0;
		int ci = (int) Math.floor(gx);
		int cj = (int) Math.floor(gz);
		if (ci < 0 || cj < 0 || ci >= cols || cj >= rows || colState[ci + cj * cols] == 0) {
			return false;
		}
		// Flow: bilinear between column centres; dry columns count as still water.
		float fx = gx - 0.5f;
		float fz = gz - 0.5f;
		int i0 = (int) Math.floor(fx);
		int j0 = (int) Math.floor(fz);
		float tx = fx - i0;
		float tz = fz - j0;
		float u = 0f;
		float w = 0f;
		for (int k = 0; k < 4; k++) {
			int i = i0 + (k & 1);
			int j = j0 + (k >> 1);
			if (i < 0 || j < 0 || i >= cols || j >= rows) {
				continue;
			}
			int c = i + j * cols;
			if (colState[c] == 0) {
				continue;
			}
			float wt = ((k & 1) == 0 ? 1f - tx : tx) * ((k >> 1) == 0 ? 1f - tz : tz);
			u += colVx[c] * wt;
			w += colVz[c] * wt;
		}
		float speed = (float) Math.sqrt(u * u + w * w);

		// Small eddies: stronger in faster water.
		float amp = EDDY_STRENGTH * (0.25f + 0.5f * Math.min(speed, 3f));
		int res = Turbulence.EDDY_RES;
		float ex = x * (res / Turbulence.TILE) - 0.5f;
		float ez = z * (res / Turbulence.TILE) - 0.5f;
		int ei = (int) Math.floor(ex);
		int ej = (int) Math.floor(ez);
		float etx = ex - ei;
		float etz = ez - ej;
		int i0e = ei & (res - 1);
		int i1e = (ei + 1) & (res - 1);
		int j0e = (ej & (res - 1)) * res;
		int j1e = ((ej + 1) & (res - 1)) * res;
		float w00 = (1 - etx) * (1 - etz);
		float w10 = etx * (1 - etz);
		float w01 = (1 - etx) * etz;
		float w11 = etx * etz;
		float eddyX = eddies[(i0e + j0e) * 2] * w00 + eddies[(i1e + j0e) * 2] * w10
			+ eddies[(i0e + j1e) * 2] * w01 + eddies[(i1e + j1e) * 2] * w11;
		float eddyZ = eddies[(i0e + j0e) * 2 + 1] * w00 + eddies[(i1e + j0e) * 2 + 1] * w10
			+ eddies[(i0e + j1e) * 2 + 1] * w01 + eddies[(i1e + j1e) * 2 + 1] * w11;

		// Boils: push outwards from the two nearest boil centres of each size.
		int bres = Turbulence.BOIL_RES;
		int bi = ((int) Math.floor(x * (bres / Turbulence.TILE))) & (bres - 1);
		int bj = ((int) Math.floor(z * (bres / Turbulence.TILE))) & (bres - 1);
		int base = (bi + bj * bres) * Turbulence.BOIL_SLOTS;
		float bx = 0f;
		float bz = 0f;
		for (int s = 0; s < Turbulence.BOIL_SLOTS; s++) {
			int id = Turbulence.BOIL_IDS[base + s];
			if (id >= 0) {
				float e = boilEnvelope[id] * BOIL_STRENGTH[s >> 1];
				bx += Turbulence.BOIL_VECTORS[(base + s) * 2] * e;
				bz += Turbulence.BOIL_VECTORS[(base + s) * 2 + 1] * e;
			}
		}

		out[0] = u + amp * eddyX + bx;
		out[1] = w + amp * eddyZ + bz;
		return true;
	}

	private void emit(float dt, float[] diffuse, int diffuseCount, float diffuseLifetime) {
		int n = cols * rows;
		float area = cell * cell;
		for (int c = 0; c < n; c++) {
			if (colState[c] == 0) {
				colRate[c] = 0f;
				continue;
			}
			float vx = colVx[c];
			float vy = colVy[c];
			float vz = colVz[c];
			float churn = smoothstep(CHURN_MIN_SPEED, CHURN_FULL_SPEED, (float) Math.sqrt(vx * vx + vy * vy + vz * vz));
			colRate[c] = churn * (float) Math.sqrt(churn) * CHURN_RATE * area;
		}
		if (diffuse != null && diffuseLifetime > 0f) {
			float inv = 1f / cell;
			for (int k = 0; k < diffuseCount; k++) {
				float x = diffuse[k * 4];
				float y = diffuse[k * 4 + 1];
				float z = diffuse[k * 4 + 2];
				float l = diffuse[k * 4 + 3];
				if (!(l > 0f) || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
					continue;
				}
				int i = (int) Math.floor(x * inv) - colX0;
				int j = (int) Math.floor(z * inv) - colZ0;
				if (i < 0 || j < 0 || i >= cols || j >= rows) {
					continue;
				}
				int c = i + j * cols;
				if (colState[c] == 0 || y < colHeight[c] - DIFFUSE_BELOW || y > colHeight[c] + DIFFUSE_ABOVE) {
					continue;
				}
				// Young diffuse particles mark where the water churns right now.
				float f = Math.min(1f, l / diffuseLifetime);
				colRate[c] += DIFFUSE_RATE * f * f * f;
			}
		}

		float cap = MAX_RATE * area;
		float scale = settings.emission();
		double total = 0;
		for (int c = 0; c < n; c++) {
			if (colRate[c] > 0f) {
				colRate[c] = Math.min(colRate[c], cap) * scale;
				total += colRate[c] * dt;
			}
		}
		int room = settings.maxTracers() - count;
		if (room <= 0 || total <= 0) {
			return;
		}
		double thin = total > room ? room / total : 1.0;
		for (int c = 0; c < n && count < settings.maxTracers(); c++) {
			if (colRate[c] > 0f) {
				int spawn = poisson(colRate[c] * dt * thin);
				if (spawn > 0) {
					spawnIn(c, spawn);
				}
			}
		}
	}

	private void spawnIn(int column, int n) {
		int i = column % cols;
		int j = column / cols;
		float lifetime = settings.lifetime();
		int max = settings.maxTracers();
		for (int k = 0; k < n && count < max; k++) {
			px[count] = (colX0 + i + (float) random.nextDouble()) * cell;
			pz[count] = (colZ0 + j + (float) random.nextDouble()) * cell;
			age[count] = 0f;
			life[count] = lifetime * (LIFE_MIN + (LIFE_MAX - LIFE_MIN) * (float) random.nextDouble());
			weight[count] = WEIGHT_MIN + (1f - WEIGHT_MIN) * (float) random.nextDouble();
			count++;
		}
	}

	private int poisson(double lambda) {
		if (lambda <= 0) {
			return 0;
		}
		if (lambda > 30) {
			double g = random.nextGaussian();
			return (int) Math.max(0, Math.round(lambda + Math.sqrt(lambda) * g));
		}
		double limit = Math.exp(-lambda);
		double product = random.nextDouble();
		int k = 0;
		while (product > limit) {
			product *= random.nextDouble();
			k++;
		}
		return k;
	}

	/** Brightness of a tracer: its weight, faded in after birth and out towards the end of its life. */
	private float strength(int i) {
		float a = age[i];
		float l = life[i];
		float in = Math.min(1f, a / FADE_IN);
		float out = Math.min(1f, (l - a) / (FADE_OUT * l));
		return weight[i] * in * Math.max(0f, out);
	}

	// ------------------------------------------------------------------------------------------
	// Map
	// ------------------------------------------------------------------------------------------

	/** Splats the tracers into {@code map} (level 0 and its mip levels). */
	public void buildMap(Map map) {
		map.version++;
		if (count == 0 || !surfaceValid) {
			map.empty = true;
			return;
		}
		float minX = Float.POSITIVE_INFINITY;
		float minZ = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float maxZ = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < count; i++) {
			minX = Math.min(minX, px[i]);
			maxX = Math.max(maxX, px[i]);
			minZ = Math.min(minZ, pz[i]);
			maxZ = Math.max(maxZ, pz[i]);
		}
		int tpb = settings.texelsPerBlock();
		int x0;
		int z0;
		int needW;
		int needH;
		while (true) {
			x0 = (int) Math.floor(minX * tpb) - MAP_MARGIN;
			z0 = (int) Math.floor(minZ * tpb) - MAP_MARGIN;
			needW = (int) Math.ceil(maxX * tpb) + MAP_MARGIN + 1 - x0;
			needH = (int) Math.ceil(maxZ * tpb) + MAP_MARGIN + 1 - z0;
			if ((needW <= MAX_MAP_SIZE && needH <= MAX_MAP_SIZE) || tpb <= 2) {
				break;
			}
			tpb = Math.max(2, tpb / 2);
		}
		int w = Math.min(MAX_MAP_SIZE, Math.max(MIN_MAP_SIZE, Integer.highestOneBit(needW - 1) << 1));
		int h = Math.min(MAX_MAP_SIZE, Math.max(MIN_MAP_SIZE, Integer.highestOneBit(needH - 1) << 1));
		int n = w * h;
		if (accumulation.length < n) {
			accumulation = new float[n];
			blurScratch = new float[n];
		}
		float[] acc = accumulation;
		Arrays.fill(acc, 0, n, 0f);

		// Bilinear splat (texel centres at i + 0.5).
		for (int k = 0; k < count; k++) {
			float s = strength(k);
			if (s <= 0f) {
				continue;
			}
			float u = px[k] * tpb - x0 - 0.5f;
			float v = pz[k] * tpb - z0 - 0.5f;
			int i = (int) Math.floor(u);
			int j = (int) Math.floor(v);
			if (i < 0 || j < 0 || i + 1 >= w || j + 1 >= h) {
				continue;
			}
			float tx = u - i;
			float tz = v - j;
			int o = i + j * w;
			acc[o] += s * (1 - tx) * (1 - tz);
			acc[o + 1] += s * tx * (1 - tz);
			acc[o + w] += s * (1 - tx) * tz;
			acc[o + w + 1] += s * tx * tz;
		}

		// Soften into lines: [1 2 1] blur, more passes for wider lines (at 24 texels per block).
		int passes = Math.max(1, Math.round(settings.lineWidth() * tpb / 24f));
		for (int p = 0; p < passes; p++) {
			blur(acc, blurScratch, w, h);
		}

		// Surface height under the foam.
		float baseHeight = Float.POSITIVE_INFINITY;
		int total = cols * rows;
		for (int c = 0; c < total; c++) {
			if (colState[c] != 0) {
				baseHeight = Math.min(baseHeight, colHeight[c]);
			}
		}
		baseHeight = (float) Math.floor(baseHeight);

		int size = 0;
		int levels = 0;
		for (int level = 0; level < MAX_LEVELS && (w >> level) >= 1 && (h >> level) >= 1; level++) {
			map.offsets[level] = size;
			size += 2 * (w >> level) * (h >> level);
			levels++;
			if ((w >> level) == 1 || (h >> level) == 1) {
				break;
			}
		}
		if (map.data.length < size) {
			map.data = new byte[size];
		}
		byte[] data = map.data;

		// Height code per column, and the column of every texel column.
		if (columnCodes.length < total) {
			columnCodes = new byte[Math.max(total, columnCodes.length * 3 / 2)];
		}
		for (int c = 0; c < total; c++) {
			columnCodes[c] = colState[c] == 0 ? 0
				: (byte) Math.max(0, Math.min(255, Math.round((colHeight[c] - baseHeight) * HEIGHT_STEPS)));
		}
		if (texelColumns.length < w) {
			texelColumns = new int[MAX_MAP_SIZE];
		}
		float invCell = 1f / cell;
		for (int i = 0; i < w; i++) {
			int ci = (int) Math.floor((x0 + i + 0.5f) / tpb * invCell) - colX0;
			texelColumns[i] = ci >= 0 && ci < cols ? ci : -1;
		}

		float densityScale = tpb * tpb * settings.coverage() / FULL_DENSITY * (EXP_TABLE_SIZE / EXP_TABLE_RANGE);
		for (int j = 0; j < h; j++) {
			int cj = (int) Math.floor((z0 + j + 0.5f) / tpb * invCell) - colZ0;
			int rowBase = cj >= 0 && cj < rows ? cj * cols : -1;
			for (int i = 0; i < w; i++) {
				int o = i + j * w;
				float a = acc[o] * densityScale;
				data[o * 2] = COVER_TABLE[a >= EXP_TABLE_SIZE ? EXP_TABLE_SIZE : (int) a];
				int ci = texelColumns[i];
				data[o * 2 + 1] = rowBase >= 0 && ci >= 0 ? columnCodes[rowBase + ci] : 0;
			}
		}

		// Mip levels: 2x2 box filter.
		for (int level = 1; level < levels; level++) {
			int sw = w >> (level - 1);
			int sh = h >> (level - 1);
			int dw = w >> level;
			int dh = h >> level;
			int src = map.offsets[level - 1];
			int dst = map.offsets[level];
			for (int j = 0; j < dh; j++) {
				int j0 = Math.min(2 * j, sh - 1);
				int j1 = Math.min(2 * j + 1, sh - 1);
				for (int i = 0; i < dw; i++) {
					int i0 = Math.min(2 * i, sw - 1);
					int i1 = Math.min(2 * i + 1, sw - 1);
					for (int ch = 0; ch < 2; ch++) {
						int sum = (data[src + (i0 + j0 * sw) * 2 + ch] & 0xFF) + (data[src + (i1 + j0 * sw) * 2 + ch] & 0xFF)
							+ (data[src + (i0 + j1 * sw) * 2 + ch] & 0xFF) + (data[src + (i1 + j1 * sw) * 2 + ch] & 0xFF);
						data[dst + (i + j * dw) * 2 + ch] = (byte) ((sum + 2) >> 2);
					}
				}
			}
		}

		map.width = w;
		map.height = h;
		map.levels = levels;
		map.texelsPerBlock = tpb;
		map.originX = x0;
		map.originZ = z0;
		map.baseHeight = baseHeight;
		map.empty = false;
	}

	private static void blur(float[] a, float[] tmp, int w, int h) {
		for (int j = 0; j < h; j++) {
			int row = j * w;
			for (int i = 0; i < w; i++) {
				float l = a[row + Math.max(0, i - 1)];
				float r = a[row + Math.min(w - 1, i + 1)];
				tmp[row + i] = (l + 2f * a[row + i] + r) * 0.25f;
			}
		}
		for (int j = 0; j < h; j++) {
			int up = Math.max(0, j - 1) * w;
			int down = Math.min(h - 1, j + 1) * w;
			int row = j * w;
			for (int i = 0; i < w; i++) {
				a[row + i] = (tmp[up + i] + 2f * tmp[row + i] + tmp[down + i]) * 0.25f;
			}
		}
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
		return t * t * (3f - 2f * t);
	}

	private void parallelFor(int n, IntConsumer body) {
		if (pool == null || n <= 1) {
			for (int i = 0; i < n; i++) {
				body.accept(i);
			}
			return;
		}
		try {
			pool.submit(() -> IntStream.range(0, n).parallel().forEach(body)).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		} catch (ExecutionException e) {
			throw new IllegalStateException(e.getCause());
		}
	}

	// ------------------------------------------------------------------------------------------
	// Turbulence: a tile of eddies and boils that repeats every 8 blocks and every 12 seconds
	// ------------------------------------------------------------------------------------------

	private static final class Turbulence {
		static final float TILE = 8f;
		static final float LOOP = 12f;

		/** Divergence-free eddies (curl noise), precomputed frames over one loop. */
		static final int EDDY_RES = 64;
		static final int EDDY_FRAMES = 48;
		static final int EDDY_MODES = 16;
		static final float[] EDDY_FRAMES_DATA = new float[EDDY_FRAMES * EDDY_RES * EDDY_RES * 2];

		/** Boils of two sizes on jittered grids: 11 and 28 boils across the tile. */
		static final int BOIL_RES = 128;
		static final int[] BOIL_CELLS = {11, 28};
		/** Two sizes, two nearest boils each. */
		static final int BOIL_SLOTS = 4;
		/** A boil pushes foam up to this many grid cells from its centre. */
		static final float BOIL_REACH = 0.9f;
		static final int BOIL_COUNT = 11 * 11 + 28 * 28;
		static final float[] BOIL_VECTORS = new float[BOIL_RES * BOIL_RES * BOIL_SLOTS * 2];
		static final short[] BOIL_IDS = new short[BOIL_RES * BOIL_RES * BOIL_SLOTS];
		static final float[] BOIL_PERIOD = new float[BOIL_COUNT];
		static final float[] BOIL_PHASE = new float[BOIL_COUNT];

		static {
			SplittableRandom random = new SplittableRandom(0x1A2B3C4DL);
			buildEddies(random);
			buildBoils(random);
		}

		private static void buildEddies(SplittableRandom random) {
			float[] kx = new float[EDDY_MODES];
			float[] kz = new float[EDDY_MODES];
			float[] omega = new float[EDDY_MODES];
			float[] phase = new float[EDDY_MODES];
			for (int m = 0; m < EDDY_MODES; m++) {
				// Integer wave numbers keep the tile seamless: wavelengths of 0.45 to 1.6 blocks.
				int a;
				int b;
				double len;
				do {
					a = random.nextInt(-18, 19);
					b = random.nextInt(-18, 19);
					len = Math.sqrt(a * a + b * b);
				} while (len < 5 || len > 18);
				kx[m] = (float) (2 * Math.PI * a / TILE);
				kz[m] = (float) (2 * Math.PI * b / TILE);
				omega[m] = (float) (2 * Math.PI * random.nextInt(1, 9) / LOOP);
				phase[m] = (float) (random.nextDouble() * 2 * Math.PI);
			}
			float amplitude = (float) (1.0 / Math.sqrt(EDDY_MODES));
			for (int f = 0; f < EDDY_FRAMES; f++) {
				float t = f * LOOP / EDDY_FRAMES;
				int frame = f * EDDY_RES * EDDY_RES * 2;
				for (int j = 0; j < EDDY_RES; j++) {
					float z = (j + 0.5f) * TILE / EDDY_RES;
					for (int i = 0; i < EDDY_RES; i++) {
						float x = (i + 0.5f) * TILE / EDDY_RES;
						float vx = 0f;
						float vz = 0f;
						for (int m = 0; m < EDDY_MODES; m++) {
							float c = (float) Math.cos(kx[m] * x + kz[m] * z + omega[m] * t + phase[m]) * amplitude;
							float k = (float) Math.sqrt(kx[m] * kx[m] + kz[m] * kz[m]);
							// Velocity of the stream function sin(...) / |k|: (d/dz, -d/dx).
							vx += c * kz[m] / k;
							vz -= c * kx[m] / k;
						}
						EDDY_FRAMES_DATA[frame + (i + j * EDDY_RES) * 2] = vx;
						EDDY_FRAMES_DATA[frame + (i + j * EDDY_RES) * 2 + 1] = vz;
					}
				}
			}
		}

		private static void buildBoils(SplittableRandom random) {
			float[][] centreX = new float[2][];
			float[][] centreZ = new float[2][];
			int idBase = 0;
			for (int s = 0; s < 2; s++) {
				int n = BOIL_CELLS[s];
				float grid = TILE / n;
				centreX[s] = new float[n * n];
				centreZ[s] = new float[n * n];
				for (int j = 0; j < n; j++) {
					for (int i = 0; i < n; i++) {
						int id = i + j * n;
						centreX[s][id] = (i + 0.15f + 0.7f * (float) random.nextDouble()) * grid;
						centreZ[s][id] = (j + 0.15f + 0.7f * (float) random.nextDouble()) * grid;
						// Periods divide the loop: large boils last 1.5 to 3 s, small ones 0.9 to 1.7 s.
						int divisions = s == 0 ? random.nextInt(4, 9) : random.nextInt(7, 14);
						BOIL_PERIOD[idBase + id] = LOOP / divisions;
						BOIL_PHASE[idBase + id] = (float) random.nextDouble();
					}
				}
				idBase += n * n;
			}
			Arrays.fill(BOIL_IDS, (short) -1);
			for (int j = 0; j < BOIL_RES; j++) {
				float z = (j + 0.5f) * TILE / BOIL_RES;
				for (int i = 0; i < BOIL_RES; i++) {
					float x = (i + 0.5f) * TILE / BOIL_RES;
					int texel = i + j * BOIL_RES;
					int idOffset = 0;
					for (int s = 0; s < 2; s++) {
						int n = BOIL_CELLS[s];
						float grid = TILE / n;
						float reach = grid * BOIL_REACH;
						int gi = (int) Math.floor(x / grid);
						int gj = (int) Math.floor(z / grid);
						float best1 = Float.POSITIVE_INFINITY;
						float best2 = Float.POSITIVE_INFINITY;
						int id1 = -1;
						int id2 = -1;
						float dx1 = 0;
						float dz1 = 0;
						float dx2 = 0;
						float dz2 = 0;
						for (int dj = -1; dj <= 1; dj++) {
							for (int di = -1; di <= 1; di++) {
								int ci = gi + di;
								int cj = gj + dj;
								int wi = Math.floorMod(ci, n);
								int wj = Math.floorMod(cj, n);
								int id = wi + wj * n;
								// Centre of this neighbour, unwrapped next to the texel.
								float cx = centreX[s][id] + (ci - wi) * grid;
								float cz = centreZ[s][id] + (cj - wj) * grid;
								float dx = x - cx;
								float dz = z - cz;
								float d = (float) Math.sqrt(dx * dx + dz * dz);
								if (d < best1) {
									best2 = best1;
									id2 = id1;
									dx2 = dx1;
									dz2 = dz1;
									best1 = d;
									id1 = id;
									dx1 = dx;
									dz1 = dz;
								} else if (d < best2) {
									best2 = d;
									id2 = id;
									dx2 = dx;
									dz2 = dz;
								}
							}
						}
						store(texel, s * 2, id1 < 0 ? -1 : idOffset + id1, dx1, dz1, best1, reach);
						store(texel, s * 2 + 1, id2 < 0 ? -1 : idOffset + id2, dx2, dz2, best2, reach);
						idOffset += n * n;
					}
				}
			}
		}

		private static void store(int texel, int slot, int id, float dx, float dz, float distance, float reach) {
			int index = texel * BOIL_SLOTS + slot;
			float r = distance / reach;
			if (id < 0 || r >= 1f || distance < 1e-6f) {
				BOIL_IDS[index] = -1;
				return;
			}
			// Outward push, strongest a third of the way out: r (1 - r)^2, scaled to peak at 1.
			float profile = r * (1 - r) * (1 - r) * 6.75f;
			BOIL_IDS[index] = (short) id;
			BOIL_VECTORS[index * 2] = dx / distance * profile;
			BOIL_VECTORS[index * 2 + 1] = dz / distance * profile;
		}

		/** Eddy velocities at {@code time}, interpolated between the precomputed frames. */
		static void eddies(double time, float[] out) {
			double t = time % LOOP;
			if (t < 0) {
				t += LOOP;
			}
			double f = t / LOOP * EDDY_FRAMES;
			int f0 = (int) Math.floor(f) % EDDY_FRAMES;
			int f1 = (f0 + 1) % EDDY_FRAMES;
			float blend = (float) (f - Math.floor(f));
			int size = EDDY_RES * EDDY_RES * 2;
			int o0 = f0 * size;
			int o1 = f1 * size;
			for (int i = 0; i < size; i++) {
				out[i] = EDDY_FRAMES_DATA[o0 + i] * (1 - blend) + EDDY_FRAMES_DATA[o1 + i] * blend;
			}
		}

		/** How strongly every boil pushes at {@code time}: each swells and fades over its period. */
		static void boilEnvelopes(double time, float[] out) {
			for (int b = 0; b < BOIL_COUNT; b++) {
				double s = Math.sin(Math.PI * (time / BOIL_PERIOD[b] + BOIL_PHASE[b]));
				out[b] = (float) (s * s);
			}
		}
	}
}
