package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaterSaveFileTest {
	private static WaterSaveFile.State sample() {
		float[] particles = {1.5f, 2.25f, -3f, 0.1f, -4f, 0f, 7f, 8f, 9f, 0f, 0f, 1f};
		int[] columns = {3, -2, 1, 12, 768};
		float[] walls = {0f, 2.5f, 1f, 0f};
		return new WaterSaveFile.State(-32, 48, 16, 0.125f, 2, particles, 1, columns, walls, new long[] {123456789L, -42L});
	}

	@Test
	void roundTripKeepsEverything() throws IOException {
		WaterSaveFile.State in = sample();
		WaterSaveFile.State out = WaterSaveFile.roundTrip(in);
		assertEquals(in.originX(), out.originX());
		assertEquals(in.originY(), out.originY());
		assertEquals(in.originZ(), out.originZ());
		assertEquals(in.spacing(), out.spacing());
		assertEquals(2, out.particleCount());
		assertArrayEquals(in.particles(), out.particles());
		assertEquals(1, out.columnCount());
		assertArrayEquals(in.columns(), out.columns());
		assertArrayEquals(in.walls(), out.walls());
		assertArrayEquals(in.sources(), out.sources());
	}

	@Test
	void writesThroughATemporaryFile(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("inertia").resolve("water-minecraft_overworld.dat");
		assertNull(WaterSaveFile.read(file));
		WaterSaveFile.write(file, sample());
		assertEquals(2, WaterSaveFile.read(file).particleCount());
		assertEquals(false, Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
	}

	@Test
	void rejectsOtherFiles(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("water.dat");
		try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) {
			out.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
		}
		assertThrows(IOException.class, () -> WaterSaveFile.read(file));
	}
}
