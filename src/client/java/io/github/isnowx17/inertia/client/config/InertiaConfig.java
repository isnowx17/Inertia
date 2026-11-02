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
import java.util.Locale;

/**
 * User settings, stored in {@code config/inertia.json}. Invalid values are clamped to their
 * allowed range and the file is rewritten so it always shows the values actually in use.
 */
public final class InertiaConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final String RENDER_SURFACE = "surface";
	public static final String RENDER_PARTICLES = "particles";
	public static final String QUALITY_CUSTOM = "custom";
	private static final int CURRENT_VERSION = 8;

	/** Version of this file's defaults; older files get changed defaults migrated on load. */
	public int configVersion = 0;

	/**
	 * Simulation and surface detail: {@code "low"}, {@code "medium"}, {@code "high"}, {@code "ultra"}
	 * or {@code "custom"}. Higher qualities use more, smaller particles (a bucket is always one block
	 * of water) and a finer surface; they need a stronger GPU and CPU. With {@code "custom"} the
	 * values below marked "custom" are used.
	 */
	public String quality = "high";

	/** Custom: distance between neighbouring particles in blocks (0.1 = 1000 particles per block). */
	public float particleSpacing = 0.1f;

	/** Custom: maximum number of water particles alive at once. */
	public int maxParticles = 131_072;

	/** Custom: size of the surface grid cells in blocks. Smaller is more detailed but slower. */
	public float surfaceCellSize = 0.15f;

	/** Custom: simulation steps per game tick (20 ticks per second). More is more stable but slower. */
	public int substepsPerTick = 3;

	/**
	 * Highest number of water particles. The particle buffer starts at the quality level's size and
	 * doubles whenever the water outgrows it, up to this limit. More water costs frame rate.
	 */
	public int particleLimit = 2_097_152;

	/** Radius in blocks of the stream coming out of a flowing water source. */
	public float sourceRadius = 0.3f;

	/** Speed in blocks per second of the water leaving a flowing water source. */
	public float sourceSpeed = 4.0f;

	/** Particles further than this many blocks from the player are removed. */
	public int despawnDistance = 96;

	/** Whether water pushes, slows down and carries the player. */
	public boolean playerInteraction = true;

	/**
	 * How the water is drawn: {@code "surface"} (one continuous water surface, default) or
	 * {@code "particles"} (every particle as a sphere; cheaper, useful for debugging).
	 */
	public String renderMode = RENDER_SURFACE;

	/** Surface smoothing radius relative to the surface cell size. Larger is smoother and rounder. */
	public float surfaceSmoothness = 2.0f;

	/**
	 * Surface iso-level as a fraction of the density of still water. Lower values make thin sheets
	 * visible but inflate the surface; higher values hug the particles more tightly.
	 */
	public float surfaceThreshold = 0.3f;

	/**
	 * Draw the water with Inertia's own shader (refraction, depth colour, ripples, sun glints).
	 * Turns itself off if the shader does not work on this computer.
	 */
	public boolean waterShader = true;

	/** Strength of the small surface ripples (shader). 0 makes the water perfectly smooth. */
	public float rippleStrength = 1.0f;

	/** How strongly the water bends what is behind it (shader). */
	public float refractionStrength = 1.0f;

	/** How quickly light is absorbed in the water (shader). Higher makes deep water darker and bluer. */
	public float waterAbsorption = 1.0f;

	/** Reflect the surroundings (blocks, entities) in the water (shader, screen-space reflections). */
	public boolean reflections = true;

	/** Strength of the moving light patterns on the ground under the water (shader). 0 turns them off. */
	public float caustics = 1.0f;

	/** Strength of the sun glint on the water (shader). */
	public float sunStrength = 1.0f;

	/** Size of rendered particles / spray droplets relative to the particle spacing. */
	public float renderScale = 1.6f;

	/** Opacity of the water when looking straight into it, 0..1. */
	public float renderOpacity = 0.6f;

	// Foam: forms where the water churns (fast flow, splashes; PhysX spawns spray and bubbles there
	// too), drifts with the surface flow and gathers into lines and rings before it fades.

	/** Whether foam and spray are simulated and drawn. */
	public boolean foam = true;

	/** How easily foam forms, 0..1. */
	public float foamAmount = 0.35f;

	/** How long foam lives in seconds (on average; single bits of foam live 0.8 to 2.3 times this). */
	public float foamLifetime = 3.0f;

	/** Width of the foam lines relative to the default. */
	public float foamSize = 1.0f;

	/** How quickly overlapping foam turns opaque white, relative to the default. */
	public float foamCoverage = 1.0f;

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

	/** The settings a quality level stands for. */
	public record Quality(float particleSpacing, int maxParticles, float surfaceCellSize, int substepsPerTick) {
	}

	private static final Quality LOW = new Quality(0.125f, 65_536, 0.1875f, 2);
	private static final Quality MEDIUM = new Quality(0.1f, 131_072, 0.15f, 3);
	private static final Quality HIGH = new Quality(1f / 12f, 262_144, 0.125f, 3);
	private static final Quality ULTRA = new Quality(0.0625f, 524_288, 0.1f, 4);

	/** The effective simulation and surface settings for the selected quality. */
	public Quality effectiveQuality() {
		return switch (quality) {
			case "low" -> LOW;
			case "ultra" -> ULTRA;
			case "medium" -> MEDIUM;
			case QUALITY_CUSTOM -> new Quality(particleSpacing, maxParticles, surfaceCellSize, substepsPerTick);
			default -> HIGH;
		};
	}

	public boolean renderSurface() {
		return !RENDER_PARTICLES.equals(renderMode);
	}

	/** PhysX diffuse-particle threshold for the configured foam amount; lower spawns more foam. */
	public float foamThreshold() {
		return 30f + 600f * (1f - foamAmount);
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

		config.migrate();
		config.clamp();
		config.save(file);
		return config;
	}

	/** Applies defaults that changed since the file was written. */
	private void migrate() {
		if (configVersion < 2) {
			// 0.2.0 test build: foam formed too easily on thin water.
			foamAmount = 0.4f;
		}
		if (configVersion < 3 && "medium".equals(quality)) {
			// 0.3.0: the default quality became "high"; files that still had the old default follow it.
			quality = "high";
		}
		if (configVersion < 4) {
			// 0.4.0: foam lies on the water as streaks; more, longer-lived foam forms the foam lines.
			if (foamAmount == 0.4f) {
				foamAmount = 0.5f;
			}
			if (foamLifetime == 2.5f) {
				foamLifetime = 4.0f;
			}
		}
		if (configVersion < 5) {
			// 0.4.2: foam is part of the surface now and needs fewer particles.
			if (foamAmount == 0.5f || foamAmount == 0.4f) {
				foamAmount = 0.35f;
			}
			if (foamLifetime == 4.0f || foamLifetime == 2.5f) {
				foamLifetime = 3.0f;
			}
		}
		if (configVersion < 6) {
			// 0.4.5: the foam was removed; the remaining spray is off by default.
			foam = false;
		}
		if (configVersion < 7) {
			// 0.5.0: foam is back, drawn like Realistic Water.
			foam = true;
		}
		if (configVersion < 8) {
			// 0.6.0: new foam made of tracers drifting with the flow; foamCoverage and foamSize changed meaning.
			foam = true;
			if (foamCoverage == 1.2f) {
				foamCoverage = 1.0f;
			}
		}
		configVersion = CURRENT_VERSION;
	}

	private void clamp() {
		quality = quality == null ? "high" : quality.toLowerCase(Locale.ROOT);
		if (!quality.equals("low") && !quality.equals("medium") && !quality.equals("high")
			&& !quality.equals("ultra") && !quality.equals(QUALITY_CUSTOM)) {
			quality = "high";
		}
		particleSpacing = clamp(orDefault(particleSpacing, 0.1f), 0.05f, 0.25f);
		maxParticles = clamp(maxParticles, 4_096, 2_097_152);
		particleLimit = clamp(particleLimit, 65_536, 8_388_608);
		sourceRadius = clamp(orDefault(sourceRadius, 0.3f), 0.1f, 1.0f);
		sourceSpeed = clamp(orDefault(sourceSpeed, 4f), 0.5f, 15f);
		surfaceCellSize = clamp(orDefault(surfaceCellSize, 0.15f), 0.05f, 0.5f);
		substepsPerTick = clamp(substepsPerTick, 1, 8);
		despawnDistance = clamp(despawnDistance, 16, 512);
		if (!RENDER_SURFACE.equals(renderMode) && !RENDER_PARTICLES.equals(renderMode)) {
			renderMode = RENDER_SURFACE;
		}
		surfaceSmoothness = clamp(orDefault(surfaceSmoothness, 2f), 1.5f, 4f);
		surfaceThreshold = clamp(orDefault(surfaceThreshold, 0.3f), 0.05f, 1f);
		renderScale = clamp(orDefault(renderScale, 1.6f), 0.5f, 4f);
		renderOpacity = clamp(orDefault(renderOpacity, 0.6f), 0.05f, 1f);
		rippleStrength = clamp(orDefault(rippleStrength, 1f), 0f, 3f);
		caustics = clamp(orDefault(caustics, 1f), 0f, 3f);
		sunStrength = clamp(orDefault(sunStrength, 1f), 0f, 3f);
		refractionStrength = clamp(orDefault(refractionStrength, 1f), 0f, 3f);
		waterAbsorption = clamp(orDefault(waterAbsorption, 1f), 0.1f, 5f);
		foamAmount = clamp(orDefault(foamAmount, 0.35f), 0f, 1f);
		foamLifetime = clamp(orDefault(foamLifetime, 3f), 0.2f, 10f);
		foamSize = clamp(orDefault(foamSize, 1f), 0.25f, 4f);
		foamCoverage = clamp(orDefault(foamCoverage, 1f), 0.1f, 5f);
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
