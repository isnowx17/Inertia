package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.util.WaterSaveFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Where the water of the current dimension is saved ({@code <world>/inertia/water-<dimension>.dat}),
 * reading it when the player enters the dimension and writing it in the background. Until saved water
 * was put back, nothing is written over it. Client thread.
 */
final class WaterStorage {
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Inertia water save");
		thread.setDaemon(true);
		return thread;
	});

	private Path file;
	private CompletableFuture<WaterSaveFile.State> loading;
	/** Whether the water may be written: the save was read (everything in it is kept in memory since). */
	private boolean mayWrite;
	private Future<?> writing;
	private long lastSaveNanos;
	private long savedKey = Long.MIN_VALUE;

	/** The player entered {@code level}: reads its saved water in the background. */
	void enter(Minecraft client, ClientLevel level, boolean enabled, FluidKind kind) {
		file = null;
		loading = null;
		mayWrite = false;
		savedKey = Long.MIN_VALUE;
		lastSaveNanos = System.nanoTime();
		var server = client.getSingleplayerServer();
		if (level == null || server == null) {
			return;
		}
		String dimension = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
		file = server.getWorldPath(LevelResource.ROOT).resolve("inertia").resolve(kind.id + "-" + dimension + ".dat");
		if (!enabled) {
			mayWrite = true;
			return;
		}
		Path path = file;
		loading = CompletableFuture.supplyAsync(() -> {
			try {
				return WaterSaveFile.read(path);
			} catch (Exception e) {
				// Kept aside instead of written over, so it is not lost.
				Path aside = path.resolveSibling(path.getFileName() + ".broken-" + System.currentTimeMillis());
				Inertia.LOGGER.error("Could not read the saved water {}; moved to {}", path, aside, e);
				try {
					Files.move(path, aside);
				} catch (Exception ignored) {
					// stays where it is; nothing is written over it then
				}
				return null;
			}
		}, IO);
	}

	/**
	 * The saved water, once, as soon as it is read; {@code null} before and after, and without a save.
	 * From then on the water may be written (an unreadable save that could not be moved aside excepted).
	 */
	WaterSaveFile.State takeLoaded() {
		if (loading == null || !loading.isDone()) {
			return null;
		}
		WaterSaveFile.State state = loading.getNow(null);
		loading = null;
		mayWrite = state != null || !Files.exists(file);
		lastSaveNanos = System.nanoTime();
		return state != null && !state.isEmpty() ? state : null;
	}

	/** Nothing is written any more until the dimension is entered again (the last save is kept). */
	void stopWriting() {
		mayWrite = false;
	}

	/** The file of the realistic bodies next to the water's, or null outside a world. */
	Path bodiesFile() {
		return file == null ? null : file.resolveSibling(file.getFileName().toString().replaceFirst("^[a-z]+-", "bodies-"));
	}

	boolean mayWrite() {
		return mayWrite && file != null;
	}

	boolean due(long intervalNanos) {
		return System.nanoTime() - lastSaveNanos >= intervalNanos;
	}

	/**
	 * Writes the water in the background ({@code wait}: and waits for it, at most a few seconds);
	 * an empty water removes the save. Nothing is written when {@code key} did not change.
	 */
	void write(WaterSaveFile.State state, long key, boolean wait) {
		lastSaveNanos = System.nanoTime();
		if (!mayWrite() || key == savedKey) {
			return;
		}
		savedKey = key;
		Path path = file;
		writing = IO.submit(() -> {
			long started = System.nanoTime();
			try {
				if (state.isEmpty()) {
					Files.deleteIfExists(path);
				} else {
					WaterSaveFile.write(path, state);
					Inertia.LOGGER.info("Water saved: {} particles, {} still columns, {} sources in {} ms", state.particleCount(),
						state.columnCount(), state.sources().length, (System.nanoTime() - started) / 1_000_000);
				}
			} catch (Exception e) {
				Inertia.LOGGER.error("Could not save the water to {}", path, e);
			}
		});
		if (wait) {
			try {
				writing.get(15, TimeUnit.SECONDS);
			} catch (Exception e) {
				Inertia.LOGGER.warn("Saving the water took too long", e);
			}
		}
	}
}
