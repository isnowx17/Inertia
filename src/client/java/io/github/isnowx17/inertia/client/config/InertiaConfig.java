package io.github.isnowx17.inertia.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.isnowx17.inertia.Inertia;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User settings, stored in {@code config/inertia.json}. Invalid values are clamped to their
 * allowed range and the file is rewritten so it always shows the values actually in use.
 */
public final class InertiaConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Maximum number of water particles alive at once. Higher values need more GPU memory and time. */
	public int maxParticles = 65_536;

	/**
	 * Distance between neighbouring particles in blocks. 0.125 means 8x8x8 = 512 particles per block.
	 * Smaller values look smoother but fill the particle budget faster.
	 */
	public float particleSpacing = 0.125f;

	/** Particles further than this many blocks from the player are removed. */
	public int despawnDistance = 96;

	/** Simulation steps per game tick (20 ticks per second). More steps are more stable but slower. */
	public int substepsPerTick = 3;

	/** Whether water pushes, slows down and carries the player. */
	public boolean playerInteraction = true;

	/** Size of the rendered particles relative to the particle spacing. */
	public float renderScale = 1.6f;

	/** Opacity of the rendered water, 0..1. */
	public float renderOpacity = 0.6f;

	public static InertiaConfig load(Path file) {
		InertiaConfig config = new InertiaConfig();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				InertiaConfig read = GSON.fromJson(reader, InertiaConfig.class);
				if (read != null) {
					config = read;
				}
			} catch (IOException | JsonParseException e) {
				Inertia.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}

		config.clamp();
		config.save(file);
		return config;
	}

	private void clamp() {
		maxParticles = clamp(maxParticles, 4_096, 1_048_576);
		particleSpacing = clamp(Float.isNaN(particleSpacing) ? 0.125f : particleSpacing, 0.08f, 0.25f);
		despawnDistance = clamp(despawnDistance, 16, 512);
		substepsPerTick = clamp(substepsPerTick, 1, 8);
		renderScale = clamp(Float.isNaN(renderScale) ? 1.6f : renderScale, 0.5f, 4f);
		renderOpacity = clamp(Float.isNaN(renderOpacity) ? 0.6f : renderOpacity, 0.05f, 1f);
	}

	private void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			Inertia.LOGGER.warn("Could not write {}", file, e);
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}
