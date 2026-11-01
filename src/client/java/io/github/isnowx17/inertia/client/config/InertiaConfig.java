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

	/**
	 * How the water is drawn: {@code "surface"} (one continuous water surface, default) or
	 * {@code "particles"} (every particle as a sphere; cheaper, useful for debugging).
	 */
	public String renderMode = RENDER_SURFACE;

	/** Surface grid cell size relative to the particle spacing. Smaller is more detailed but slower. */
	public float surfaceResolution = 1.5f;

	/** Surface smoothing radius relative to the particle spacing. Larger is smoother and rounder. */
	public float surfaceSmoothness = 3.0f;

	/**
	 * Surface iso-level as a fraction of the density of still water. Lower values make thin sheets
	 * visible but inflate the surface; higher values hug the particles more tightly.
	 */
	public float surfaceThreshold = 0.3f;

	/** Size of rendered particles / spray droplets relative to the particle spacing. */
	public float renderScale = 1.6f;

	/** Opacity of the water when looking straight into it, 0..1. */
	public float renderOpacity = 0.6f;

	// Water material (PhysX PBD). Change carefully: extreme values make the simulation unstable.

	/** Friction against blocks. Higher values make water slow down and pool instead of sliding away. */
	public float waterFriction = 0.3f;

	/** Velocity damping. Higher values calm the water down faster. */
	public float waterDamping = 0.05f;

	/** Viscosity. Higher values keep the flow together; very high values feel like honey. */
	public float waterViscosity = 0.01f;

	/** Surface tension. High values make thin water break up into beads. */
	public float waterSurfaceTension = 0.002f;

	/** Cohesion between particles. High values make thin water break up into beads. */
	public float waterCohesion = 0.02f;

	/** Vorticity confinement: keeps swirls alive. */
	public float waterVorticity = 4f;

	public static final String RENDER_SURFACE = "surface";
	public static final String RENDER_PARTICLES = "particles";

	public boolean renderSurface() {
		return !RENDER_PARTICLES.equals(renderMode);
	}

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
		particleSpacing = clamp(orDefault(particleSpacing, 0.125f), 0.08f, 0.25f);
		despawnDistance = clamp(despawnDistance, 16, 512);
		substepsPerTick = clamp(substepsPerTick, 1, 8);
		renderScale = clamp(orDefault(renderScale, 1.6f), 0.5f, 4f);
		renderOpacity = clamp(orDefault(renderOpacity, 0.6f), 0.05f, 1f);
		if (!RENDER_SURFACE.equals(renderMode) && !RENDER_PARTICLES.equals(renderMode)) {
			renderMode = RENDER_SURFACE;
		}
		surfaceResolution = clamp(orDefault(surfaceResolution, 1.5f), 1f, 3f);
		surfaceSmoothness = clamp(orDefault(surfaceSmoothness, 3f), 1.5f, 5f);
		surfaceThreshold = clamp(orDefault(surfaceThreshold, 0.3f), 0.05f, 1f);
		waterFriction = clamp(orDefault(waterFriction, 0.3f), 0f, 1f);
		waterDamping = clamp(orDefault(waterDamping, 0.05f), 0f, 1f);
		waterViscosity = clamp(orDefault(waterViscosity, 0.01f), 0f, 1f);
		waterSurfaceTension = clamp(orDefault(waterSurfaceTension, 0.002f), 0f, 0.1f);
		waterCohesion = clamp(orDefault(waterCohesion, 0.02f), 0f, 1f);
		waterVorticity = clamp(orDefault(waterVorticity, 4f), 0f, 20f);
	}

	private static float orDefault(float value, float fallback) {
		return Float.isNaN(value) ? fallback : value;
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
