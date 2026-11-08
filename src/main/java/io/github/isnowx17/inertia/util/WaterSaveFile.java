package io.github.isnowx17.inertia.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The water of one dimension as it is saved with the world: the moving particles (position and
 * velocity, relative to the origin), the still water kept as a volume ({@link FrozenColumns}, in the
 * same local block coordinates) and the flowing sources (world block positions). Written compressed,
 * to a temporary file that then replaces the old one, so a save cut short leaves the last one intact.
 */
public final class WaterSaveFile {
	private static final int MAGIC = 0x494E5754; // "INWT"
	private static final int FORMAT = 1;
	/** Floats per particle: x, y, z, vx, vy, vz. */
	public static final int PARTICLE_FLOATS = 6;
	/** Ints per column: x, z, floor, layers, count. */
	public static final int COLUMN_INTS = 5;

	/**
	 * @param spacing   distance between neighbouring particles the water was simulated with
	 * @param particles {@link #PARTICLE_FLOATS} floats per particle, local
	 * @param columns   {@link #COLUMN_INTS} ints per frozen column, local
	 * @param walls     four floats per frozen column: how high the neighbours are solid (see FrozenColumns)
	 * @param sources   flowing sources, as {@code BlockPos.asLong}, world
	 */
	public record State(int originX, int originY, int originZ, float spacing, int particleCount, float[] particles,
		int columnCount, int[] columns, float[] walls, long[] sources) {

		public boolean isEmpty() {
			return particleCount == 0 && columnCount == 0 && sources.length == 0;
		}
	}

	private WaterSaveFile() {
	}

	public static void write(Path file, State state) throws IOException {
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fastGzip(Files.newOutputStream(temp)), 1 << 16))) {
			write(out, state);
		}
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** The saved water, or {@code null} without a save. */
	public static State read(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file), 1 << 16), 1 << 16))) {
			return read(in);
		}
	}

	static void write(DataOutputStream out, State s) throws IOException {
		out.writeInt(MAGIC);
		out.writeInt(FORMAT);
		out.writeInt(s.originX());
		out.writeInt(s.originY());
		out.writeInt(s.originZ());
		out.writeFloat(s.spacing());
		out.writeInt(s.particleCount());
		float[] p = s.particles();
		for (int i = 0, n = s.particleCount() * PARTICLE_FLOATS; i < n; i++) {
			out.writeFloat(p[i]);
		}
		out.writeInt(s.columnCount());
		for (int i = 0, n = s.columnCount() * COLUMN_INTS; i < n; i++) {
			out.writeInt(s.columns()[i]);
		}
		for (int i = 0, n = s.columnCount() * 4; i < n; i++) {
			out.writeFloat(s.walls()[i]);
		}
		out.writeInt(s.sources().length);
		for (long source : s.sources()) {
			out.writeLong(source);
		}
	}

	static State read(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC) {
			throw new IOException("Not an Inertia water save");
		}
		int format = in.readInt();
		if (format != FORMAT) {
			throw new IOException("Unknown water save format " + format);
		}
		int ox = in.readInt();
		int oy = in.readInt();
		int oz = in.readInt();
		float spacing = in.readFloat();
		int count = checkedCount(in.readInt(), 1 << 26);
		float[] particles = new float[count * PARTICLE_FLOATS];
		for (int i = 0; i < particles.length; i++) {
			particles[i] = in.readFloat();
		}
		int columnCount = checkedCount(in.readInt(), 1 << 24);
		int[] columns = new int[columnCount * COLUMN_INTS];
		for (int i = 0; i < columns.length; i++) {
			columns[i] = in.readInt();
		}
		float[] walls = new float[columnCount * 4];
		for (int i = 0; i < walls.length; i++) {
			walls[i] = in.readFloat();
		}
		long[] sources = new long[checkedCount(in.readInt(), 1 << 16)];
		for (int i = 0; i < sources.length; i++) {
			sources[i] = in.readLong();
		}
		return new State(ox, oy, oz, spacing, count, particles, columnCount, columns, walls, sources);
	}

	private static int checkedCount(int n, int max) throws IOException {
		if (n < 0 || n > max) {
			throw new IOException("Damaged water save (count " + n + ")");
		}
		return n;
	}

	private static OutputStream fastGzip(OutputStream out) throws IOException {
		return new GZIPOutputStream(out, 1 << 16) {
			{
				def.setLevel(Deflater.BEST_SPEED);
			}
		};
	}

	/** For tests: an in-memory round trip. */
	static State roundTrip(State state) throws IOException {
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(fastGzip(bytes))) {
			write(out, state);
		}
		InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()));
		return read(new DataInputStream(in));
	}
}
