package io.github.isnowx17.inertia.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.config.InertiaConfig;
import io.github.isnowx17.inertia.client.fluid.FluidManager;
import io.github.isnowx17.inertia.client.fluid.FluidSimulation;
import io.github.isnowx17.inertia.util.FluidSurfaceMesher;
import io.github.isnowx17.inertia.util.SurfaceFoam;
import io.github.isnowx17.inertia.util.WaterVertexLayout;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the water as one continuous surface, with foam on it, plus spray and droplets.
 *
 * <p>Whenever the simulation has new data, a background job turns the particles into a surface
 * mesh with {@link FluidSurfaceMesher} (in parallel on a dedicated pool), advances the
 * {@link SurfaceFoam foam} on it and packs everything into GPU-ready data: one vertex per surface
 * point ({@link WaterVertexLayout}) and two triangles per quad, and one instance per spray particle
 * or droplet ({@link WaterPass#PARTICLE_FORMAT}). The job reads the simulation's arrays directly
 * through a {@linkplain FluidSimulation#lease() lease}, without copying them. It also notes which
 * block every vertex takes its light from, each block once, and the box the water stays in.
 *
 * <p>The render thread looks up the light of those blocks once per new surface and uploads it
 * once; every frame the shaders then only move the vertices and particles along their flow velocity
 * for the time since the simulation data was taken, so the water flows smoothly between updates at
 * no cost to the CPU. Every vertex also carries its position in the foam map, so the foam moves
 * with the surface. Water entirely outside the view is not drawn at all.
 *
 * <p>Without the shader the surface is drawn with the vanilla entity pipeline instead (vertices
 * moved on the CPU, shaded with a Fresnel term, a depth tint and the foam). Spray comes from
 * PhysX's diffuse particles and is drawn as small soft flecks; particles too isolated to be part
 * of the surface are drawn as small droplets.
 */
public final class FluidSurfaceRenderer {
	private static final Identifier SURFACE_TEXTURE = Inertia.id("textures/fluid/surface.png");
	private static final Identifier DROPLET_TEXTURE = Inertia.id("textures/fluid/spray.png");
	private static final Identifier FOAM_TEXTURE = Inertia.id("textures/fluid/foam.png");
	private static final int SKY_REFLECTION = 0xD6E8FF;
	private static final int FOAM_COLOR = 0xF4FAFF;
	/** Spray droplets are drawn smaller than full particles. */
	private static final float DROPLET_SCALE = 0.35f;
	/** Never move geometry further ahead than this, in simulated seconds (e.g. after a hitch). */
	private static final float MAX_EXTRAPOLATION = 0.25f;
	/** Spray particles drawn at most per frame; beyond this every n-th particle is drawn. */
	private static final int MAX_FOAM_DRAWN = 120_000;
	private static final float TICK_SECONDS = 0.05f;
	/** How quickly light is absorbed in the simple (non-shader) rendering, per block of water. */
	private static final float ABSORPTION_PER_BLOCK = 1.1f;
	/** Opacity of very shallow water in the simple rendering. */
	private static final float SHALLOW_OPACITY = 0.12f;
	/** Foam (simple rendering) shows on surfaces whose normal points up at least this much. */
	private static final float FOAM_MIN_UP = 0.3f;
	/** Isolated particles slower than this (blocks/second) are not drawn as droplets. */
	private static final float MIN_DROPLET_SPEED = 1.0f;
	/** Floats per collected spray particle: position, flow velocity, lifetime. */
	private static final int SPRAY_STRIDE = 7;
	/** Spray is drawn where the field density is at least this fraction of the threshold (close to water). */
	private static final float SPRAY_MIN_DENSITY = 0.01f;
	/** Diffuse particles in denser water than this fraction of the threshold are foam or bubbles. */
	private static final float SPRAY_MAX_DENSITY = 0.15f;
	/** Size of spray flecks relative to the foam size. */
	private static final float SPRAY_SCALE = 0.45f;
	/** Light is sampled this far above / along the normal, so geometry touching blocks is not black. */
	private static final float LIGHT_PROBE_OFFSET = 0.35f;
	/** Spray fades out during its last this many seconds of life (also in water_particle.vsh). */
	private static final float SPRAY_FADE = 0.8f;
	/** Cached block light is looked up again after this many game ticks. */
	private static final long LIGHT_CACHE_TICKS = 40;
	private static final int LIGHT_CACHE_MAX = 1 << 18;
	/** Spray instances start at a multiple of this many bytes in the particle data. */
	private static final int PARTICLE_ALIGNMENT = 256;
	/** Extra room (blocks) around the water's box for the size of spray, droplets and ripples. */
	private static final float BOUNDS_MARGIN = 1f;

	private final FluidManager manager;
	private final WaterPass waterPass;
	/** Surface and foam work; below normal priority so it never holds up the game's own threads. */
	private final ForkJoinPool meshPool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 2), pool -> {
		ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
		thread.setName("Inertia surface worker " + thread.getPoolIndex());
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	}, null, false);
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Inertia surface mesher");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	private final AtomicBoolean busy = new AtomicBoolean();
	/** Simulation data version the newest job was started for: one surface per change. */
	private FluidSimulation meshedSimulation;
	private long meshedVersion = Long.MIN_VALUE;
	private long serials;

	// Double-buffered job output: the render thread reads `latest`, the worker writes the other one.
	private final Result[] results = {new Result(), new Result()};
	private volatile Result latest;

	private FluidSurfaceMesher mesher;
	private float mesherCell;
	private float mesherSpacing;
	private float mesherRadius;
	private float mesherThreshold;
	/** Foam on the surface; only used by the meshing job. */
	private SurfaceFoam foam;
	private FluidSimulation foamSimulation;
	private int foamOriginX;
	private int foamOriginY;
	private int foamOriginZ;
	private long foamTick;

	private RenderType surfaceType;
	private RenderType dropletType;
	private RenderType foamType;
	private final Long2IntOpenHashMap lightCache = new Long2IntOpenHashMap();
	private ClientLevel lightCacheLevel;
	private long lightCacheTick;
	private final float[] velocityScratch = new float[3];
	/** Extrapolation of the last frame, in simulated seconds; kept while the game is paused. */
	private float lastDt;
	// Scratch arrays of the simple rendering, reused between frames.
	private float[] simplePositions = new float[0];
	private int[] simpleColors = new int[0];

	private static final class Result {
		final FluidSurfaceMesher.Mesh mesh = new FluidSurfaceMesher.Mesh();
		/** xyz, vx, vy, vz per droplet. */
		float[] droplets = new float[0];
		int dropletCount;
		/** xyz, vx, vy, vz, lifetime per spray particle. */
		float[] spray = new float[0];
		int sprayCount;
		/** Foam on the surface at the time of the data. */
		final SurfaceFoam.Map foamMap = new SurfaceFoam.Map();
		FluidSimulation simulation;
		float spacing;
		int originX;
		int originY;
		int originZ;
		/** {@link System#nanoTime()} the positions refer to. */
		long dataNanos;
		/** Simulated seconds per real second when the data was taken. */
		float timeScale = 1f;
		/** Identifies this surface; the GPU copy is replaced when it changes. */
		long serial;
		/** Surface vertices in {@link WaterVertexLayout}, then two triangles per quad. */
		ByteBuffer vertexBytes = ByteBuffer.allocateDirect(0);
		int vertexCount;
		ByteBuffer indexBytes = ByteBuffer.allocateDirect(0);
		int indexCount;
		/** Per vertex: which of the light probes it takes its light from. */
		int[] probeIndex = new int[0];
		/** The blocks (as {@link BlockPos#asLong}) the vertices take their light from, each once. */
		long[] probes = new long[0];
		int probeCount;
		final Long2IntOpenHashMap probeIds = new Long2IntOpenHashMap();
		/** Per vertex: the light (filled in on the render thread). */
		int[] lights = new int[0];
		int[] probeLights = new int[0];
		boolean lightsReady;
		/** Droplets, then (from {@link #sprayOffset} bytes) spray, as {@link WaterPass#PARTICLE_FORMAT} instances. */
		ByteBuffer particleBytes = ByteBuffer.allocateDirect(0);
		int sprayOffset;
		boolean particleLightsReady;
		/** Box (relative to the origin) holding the surface, droplets and spray, and their fastest speed. */
		float minX;
		float minY;
		float minZ;
		float maxX;
		float maxY;
		float maxZ;
		float maxSpeed;
		/** Whether the camera was in or right next to the water when the job started. */
		boolean cameraNearWater;
	}

	public FluidSurfaceRenderer(FluidManager manager, WaterPass waterPass) {
		this.manager = manager;
		this.waterPass = waterPass;
		for (Result result : results) {
			result.probeIds.defaultReturnValue(-1);
		}
	}

	public void collectSubmits(LevelRenderContext context) {
		FluidSimulation sim = manager.simulation();
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (sim == null || level == null) {
			latest = null;
			return;
		}

		CameraRenderState camera = context.levelState().cameraRenderState;
		startMeshing(sim, camera.pos);

		Result result = latest;
		if (result == null || result.simulation != sim) {
			return;
		}

		// Everything is moved along its velocity for the time since the simulation data was taken,
		// at the speed the simulation runs at. While the game is paused or the water sleeps that time
		// stands still (the data is shifted along with the clock).
		long now = System.nanoTime();
		float scale = result.timeScale;
		if (client.isPaused() || sim.sleeping()) {
			result.dataNanos = now - (long) (lastDt * 1.0e9 / scale);
		}
		float dt = Mth.clamp((now - result.dataNanos) / 1.0e9f * scale, 0f, MAX_EXTRAPOLATION);
		lastDt = dt;

		if (!visible(camera.cullFrustum, result)) {
			return; // nothing of the water can be on screen
		}

		if (surfaceType == null) {
			surfaceType = RenderTypes.entityTranslucent(SURFACE_TEXTURE, false);
			dropletType = RenderTypes.entityTranslucent(DROPLET_TEXTURE, false);
			foamType = RenderTypes.entityTranslucent(FOAM_TEXTURE, false);
		}
		refreshLightCache(level);

		Vec3 cam = camera.pos;
		int waterColor = BiomeColors.getAverageWaterColor(level, BlockPos.containing(cam)) & 0xFFFFFF;
		InertiaConfig config = manager.config();

		boolean shader = config.waterShader && waterPass.isAvailable();
		if (result.indexCount > 0) {
			fillLights(level, result);
			if (shader) {
				pendingSurface = result;
				pendingFoamMap = config.foam && !result.foamMap.empty ? result.foamMap : null;
				pendingWaterColor = waterColor;
				pendingDt = dt;
			} else {
				submitSimpleSurface(context, result, cam, dt, waterColor, config.renderOpacity);
			}
		}

		if (shader) {
			// Spray and droplets stay on the GPU: only this frame's parameters change.
			if (result.dropletCount > 0 || result.sprayCount > 0) {
				fillParticleLights(level, result, dt);
				float radius = result.spacing * config.renderScale * 0.5f * DROPLET_SCALE;
				float spraySize = result.spacing * config.renderScale * 0.45f;
				pendingParticles = new WaterPass.Particles(
					result.particleBytes, result.dropletCount, result.sprayOffset, result.sprayCount, result.serial,
					dropletParameters(camera.orientation, radius, waterColor, dt), sprayParameters(spraySize * SPRAY_SCALE, dt));
				pendingParticleOrigin = result;
			}
			pendingCamera = cam;
		} else {
			if (result.dropletCount > 0) {
				float radius = result.spacing * config.renderScale * 0.5f * DROPLET_SCALE;
				submitQuads(context, dropletType, prepareDroplets(level, result, cam, dt, camera.orientation, radius, waterColor));
			}
			if (result.sprayCount > 0) {
				float size = result.spacing * config.renderScale * 0.45f;
				submitQuads(context, foamType, prepareSpray(level, result, cam, dt, size));
			}
		}
	}

	/** Whether any part of the water (moved along its flow for up to the longest extrapolation) can be in view. */
	private static boolean visible(Frustum frustum, Result result) {
		if (frustum == null || !(result.minX <= result.maxX)) {
			return true;
		}
		float margin = result.maxSpeed * MAX_EXTRAPOLATION + BOUNDS_MARGIN;
		return frustum.isVisible(new AABB(
			result.originX + result.minX - margin, result.originY + result.minY - margin, result.originZ + result.minZ - margin,
			result.originX + result.maxX + margin, result.originY + result.maxY + margin, result.originZ + result.maxZ + margin));
	}

	/** Droplet parameters for water_particle.vsh: camera-facing square, colour, time since the data. */
	private static Matrix4f dropletParameters(Quaternionf orientation, float radius, int waterColor, float dt) {
		Quaternionf q = new Quaternionf(orientation);
		Vector3f right = q.transform(new Vector3f(radius, 0, 0));
		Vector3f up = q.transform(new Vector3f(0, radius, 0));
		Vector3f normal = q.transform(new Vector3f(0, 0, 1));
		int color = lerpRgb(waterColor, FOAM_COLOR, 0.8f);
		return new Matrix4f()
			.m00(right.x).m01(right.y).m02(right.z).m03(dt)
			.m10(up.x).m11(up.y).m12(up.z).m13(0f)
			.m20(normal.x).m21(normal.y).m22(normal.z).m23(0f)
			.m30(((color >> 16) & 0xFF) / 255f).m31(((color >> 8) & 0xFF) / 255f).m32((color & 0xFF) / 255f).m33(150 / 255f);
	}

	/** Spray parameters for water_particle.vsh: fleck size, colour, time since the data. */
	private static Matrix4f sprayParameters(float halfSize, float dt) {
		return new Matrix4f()
			.m00(0f).m01(0f).m02(0f).m03(dt)
			.m10(0f).m11(0f).m12(0f).m13(1f)
			.m20(0f).m21(0f).m22(0f).m23(halfSize)
			.m30(((FOAM_COLOR >> 16) & 0xFF) / 255f).m31(((FOAM_COLOR >> 8) & 0xFF) / 255f).m32((FOAM_COLOR & 0xFF) / 255f).m33(1f);
	}

	// ------------------------------------------------------------------------------------------
	// Background meshing
	// ------------------------------------------------------------------------------------------

	/** Starts a surface job for new simulation data, unless one is still running. */
	private void startMeshing(FluidSimulation sim, Vec3 cameraPosition) {
		if (sim == meshedSimulation && sim.version() == meshedVersion) {
			return;
		}
		if (!busy.compareAndSet(false, true)) {
			return;
		}

		InertiaConfig config = manager.config();
		float spacing = sim.params().spacing();
		ensureMesher(spacing, config);
		FluidSimulation.Snapshot snapshot = sim.lease();
		meshedSimulation = sim;
		meshedVersion = snapshot.version();

		// Foam advances with the simulation ticks (it stands still while the game is paused).
		boolean resetFoam = sim != foamSimulation || snapshot.originX() != foamOriginX || snapshot.originY() != foamOriginY
			|| snapshot.originZ() != foamOriginZ;
		if (resetFoam) {
			foamSimulation = sim;
			foamOriginX = snapshot.originX();
			foamOriginY = snapshot.originY();
			foamOriginZ = snapshot.originZ();
			foamTick = snapshot.tick();
		}
		float foamDt = Math.max(0L, snapshot.tick() - foamTick) * TICK_SECONDS;
		foamTick = snapshot.tick();
		SurfaceFoam foamJob = config.foam && config.renderSurface() ? ensureFoam(config) : null;
		float foamLifetime = config.foamLifetime;
		int diffuseCount = config.foam ? snapshot.diffuseCount() : 0;
		float foamCell = mesherCell;

		Result target = latest == results[0] ? results[1] : results[0];
		target.simulation = sim;
		target.spacing = spacing;
		target.originX = snapshot.originX();
		target.originY = snapshot.originY();
		target.originZ = snapshot.originZ();
		target.dataNanos = snapshot.readBackNanos() != 0L ? snapshot.readBackNanos() : System.nanoTime();
		target.timeScale = snapshot.timeScale();
		target.serial = ++serials;
		target.lightsReady = false;
		target.particleLightsReady = false;
		FluidSurfaceMesher job = mesher;
		float camX = (float) (cameraPosition.x - snapshot.originX());
		float camY = (float) (cameraPosition.y - snapshot.originY());
		float camZ = (float) (cameraPosition.z - snapshot.originZ());

		try {
			worker.execute(() -> {
				try {
					job.mesh(snapshot.positions(), snapshot.velocities(), snapshot.count(), FluidSimulation.STRIDE, target.mesh);
					collectDroplets(job, snapshot, target);
					collectSpray(job, snapshot.diffuse(), diffuseCount, target);
					if (foamJob != null) {
						if (resetFoam) {
							foamJob.clear();
						}
						FluidSurfaceMesher.Mesh mesh = target.mesh;
						foamJob.setSurfaceFromMesh(mesh.vertices, mesh.vertexCount, FluidSurfaceMesher.VERTEX_STRIDE, foamCell);
						foamJob.step(foamDt, snapshot.diffuse(), diffuseCount, foamLifetime);
						foamJob.buildMap(target.foamMap);
						fillDirect(target.foamMap);
					} else {
						target.foamMap.empty = true;
						target.foamMap.version++;
					}
					beginBounds(target);
					packSurface(target);
					packParticles(target);
					target.cameraNearWater = nearWater(job, camX, camY, camZ);
					latest = target;
				} catch (Throwable t) {
					Inertia.LOGGER.error("Water surface meshing failed", t);
				} finally {
					sim.release(snapshot);
					busy.set(false);
				}
			});
		} catch (RuntimeException e) {
			sim.release(snapshot);
			busy.set(false);
			throw e;
		}
	}

	/** The foam with settings for the current configuration (only called while no job runs). */
	private SurfaceFoam ensureFoam(InertiaConfig config) {
		SurfaceFoam.Settings settings = foamSettings(config);
		if (foam == null) {
			foam = new SurfaceFoam(settings, System.nanoTime(), meshPool);
		} else if (!foam.settings().equals(settings)) {
			foam.setSettings(settings);
		}
		return foam;
	}

	private static SurfaceFoam.Settings foamSettings(InertiaConfig config) {
		int tracers;
		int texelsPerBlock;
		switch (config.quality) {
			case "low" -> {
				tracers = 40_000;
				texelsPerBlock = 16;
			}
			case "medium" -> {
				tracers = 80_000;
				texelsPerBlock = 20;
			}
			case "ultra" -> {
				tracers = 250_000;
				texelsPerBlock = 32;
			}
			default -> {
				tracers = 150_000;
				texelsPerBlock = 24;
			}
		}
		// foamAmount 0.35 (the default) spawns the foam the look was tuned with.
		float emission = config.foamAmount / 0.35f;
		return new SurfaceFoam.Settings(tracers, texelsPerBlock, emission, config.foamLifetime, config.foamSize, config.foamCoverage);
	}

	private void ensureMesher(float spacing, InertiaConfig config) {
		float cell = config.effectiveQuality().surfaceCellSize();
		// The smoothing must span a few particles for the surface to be continuous.
		float radius = Math.max(cell * config.surfaceSmoothness, spacing * 2.5f);
		float threshold = config.surfaceThreshold;
		if (mesher != null && cell == mesherCell && radius == mesherRadius && threshold == mesherThreshold
			&& spacing == mesherSpacing) {
			return;
		}
		mesher = new FluidSurfaceMesher(cell, spacing, radius, threshold, meshPool);
		mesherSpacing = spacing;
		mesherCell = cell;
		mesherRadius = radius;
		mesherThreshold = threshold;
	}

	/** Particles in regions too sparse to form a surface become spray droplets. */
	private static void collectDroplets(FluidSurfaceMesher mesher, FluidSimulation.Snapshot snapshot, Result target) {
		float[] positions = snapshot.positions();
		float[] velocities = snapshot.velocities();
		int stride = FluidSimulation.STRIDE;
		int[] sparse = mesher.sparseParticles(positions, snapshot.count(), stride);
		if (target.droplets.length < sparse.length * 6) {
			target.droplets = new float[sparse.length * 6];
		}
		int n = 0;
		for (int i : sparse) {
			float vx = velocities[i * stride];
			float vy = velocities[i * stride + 1];
			float vz = velocities[i * stride + 2];
			// A lone particle lying still on the ground is a leftover of a drained puddle, not spray.
			if (vx * vx + vy * vy + vz * vz < MIN_DROPLET_SPEED * MIN_DROPLET_SPEED) {
				continue;
			}
			System.arraycopy(positions, i * stride, target.droplets, n * 6, 3);
			System.arraycopy(velocities, i * stride, target.droplets, n * 6 + 3, 3);
			n++;
		}
		target.dropletCount = n;
	}

	/** Keeps the diffuse particles just outside the water (spray), with the local flow velocity. */
	private void collectSpray(FluidSurfaceMesher mesher, float[] diffuse, int count, Result target) {
		int stride = Math.max(1, (count + MAX_FOAM_DRAWN - 1) / MAX_FOAM_DRAWN);
		int drawn = (count + stride - 1) / stride;
		if (target.spray.length < drawn * SPRAY_STRIDE) {
			target.spray = new float[drawn * SPRAY_STRIDE];
		}
		float[] v = velocityScratch;
		float minDensity = mesher.threshold() * SPRAY_MIN_DENSITY;
		float maxDensity = mesher.threshold() * SPRAY_MAX_DENSITY;
		int n = 0;
		for (int i = 0; i < count; i += stride) {
			float x = diffuse[i * 4];
			float y = diffuse[i * 4 + 1];
			float z = diffuse[i * 4 + 2];
			float life = diffuse[i * 4 + 3];
			if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || life <= 0f) {
				continue;
			}
			// Just outside the water: spray. Foam on the surface and bubbles inside are not drawn here.
			float density = mesher.densityAt(x, y, z);
			if (density < minDensity || density >= maxDensity) {
				continue;
			}
			mesher.velocityAt(x, y, z, v);
			int o = n * SPRAY_STRIDE;
			target.spray[o] = x;
			target.spray[o + 1] = y;
			target.spray[o + 2] = z;
			target.spray[o + 3] = v[0];
			target.spray[o + 4] = v[1];
			target.spray[o + 5] = v[2];
			target.spray[o + 6] = life;
			n++;
		}
		target.sprayCount = n;
	}

	/** Worker: copies the foam map into its direct buffer, so the render thread uploads it as it is. */
	private static void fillDirect(SurfaceFoam.Map map) {
		if (map.empty) {
			return;
		}
		int size = 0;
		for (int level = 0; level < map.levels; level++) {
			size = Math.max(size, map.offsets[level] + 2 * (map.width >> level) * (map.height >> level));
		}
		if (map.direct == null || map.direct.capacity() < size) {
			map.direct = ByteBuffer.allocateDirect(size + size / 4);
		}
		map.direct.clear();
		map.direct.put(map.data, 0, size);
		map.direct.clear();
	}

	private static void beginBounds(Result target) {
		target.minX = Float.POSITIVE_INFINITY;
		target.minY = Float.POSITIVE_INFINITY;
		target.minZ = Float.POSITIVE_INFINITY;
		target.maxX = Float.NEGATIVE_INFINITY;
		target.maxY = Float.NEGATIVE_INFINITY;
		target.maxZ = Float.NEGATIVE_INFINITY;
		target.maxSpeed = 0f;
	}

	/** Worker: widens the water's box by a point moving at the given velocity. */
	private static void include(Result target, float x, float y, float z, float vx, float vy, float vz) {
		if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
			return;
		}
		target.minX = Math.min(target.minX, x);
		target.minY = Math.min(target.minY, y);
		target.minZ = Math.min(target.minZ, z);
		target.maxX = Math.max(target.maxX, x);
		target.maxY = Math.max(target.maxY, y);
		target.maxZ = Math.max(target.maxZ, z);
		float speed = vx * vx + vy * vy + vz * vz;
		if (speed > target.maxSpeed * target.maxSpeed) {
			target.maxSpeed = Float.isFinite(speed) ? (float) Math.sqrt(speed) : Float.POSITIVE_INFINITY;
		}
	}

	/**
	 * Worker: packs the surface into GPU-ready data. Vertices keep their position as built; the
	 * shader moves them along their velocity. The light is filled in later on the render thread
	 * (only it may read the world); the worker notes which block each vertex takes its light from,
	 * numbering every block once, so the render thread looks up each block only once.
	 */
	private static void packSurface(Result target) {
		FluidSurfaceMesher.Mesh mesh = target.mesh;
		int quads = mesh.quadCount;
		int n = quads > 0 ? mesh.vertexCount : 0;
		target.vertexCount = n;
		target.indexCount = quads * WaterVertexLayout.INDICES_PER_QUAD;
		target.probeCount = 0;
		if (n == 0) {
			return;
		}
		int vertexBytes = n * WaterVertexLayout.STRIDE;
		if (target.vertexBytes.capacity() < vertexBytes) {
			target.vertexBytes = ByteBuffer.allocateDirect(vertexBytes + vertexBytes / 2).order(ByteOrder.nativeOrder());
		}
		int indexBytes = target.indexCount * Integer.BYTES;
		if (target.indexBytes.capacity() < indexBytes) {
			target.indexBytes = ByteBuffer.allocateDirect(indexBytes + indexBytes / 2).order(ByteOrder.nativeOrder());
		}
		if (target.probeIndex.length < n) {
			target.probeIndex = new int[n + n / 2];
			target.lights = new int[n + n / 2];
		}

		SurfaceFoam.Map map = target.foamMap.empty ? null : target.foamMap;
		float[] v = mesh.vertices;
		ByteBuffer b = target.vertexBytes;
		b.clear();
		float probe = LIGHT_PROBE_OFFSET;
		Long2IntOpenHashMap probeIds = target.probeIds;
		probeIds.clear();
		long lastKey = Long.MIN_VALUE;
		int lastId = -1;
		for (int i = 0; i < n; i++) {
			int o = i * FluidSurfaceMesher.VERTEX_STRIDE;
			int base = i * WaterVertexLayout.STRIDE;
			float x = v[o];
			float y = v[o + 1];
			float z = v[o + 2];
			float nx = v[o + 3];
			float ny = v[o + 4];
			float nz = v[o + 5];
			b.putFloat(base + WaterVertexLayout.POSITION, x);
			b.putFloat(base + WaterVertexLayout.POSITION + 4, y);
			b.putFloat(base + WaterVertexLayout.POSITION + 8, z);
			b.putFloat(base + WaterVertexLayout.VELOCITY, v[o + 6]);
			b.putFloat(base + WaterVertexLayout.VELOCITY + 4, v[o + 7]);
			b.putFloat(base + WaterVertexLayout.VELOCITY + 8, v[o + 8]);
			b.putFloat(base + WaterVertexLayout.FOAM_UV, map != null ? map.u(x) : -1f);
			b.putFloat(base + WaterVertexLayout.FOAM_UV + 4, map != null ? map.v(z) : -1f);
			b.putFloat(base + WaterVertexLayout.INFO, v[o + 9]);
			b.putFloat(base + WaterVertexLayout.INFO + 4, map != null ? map.heightCode(y) : 0f);
			b.putInt(base + WaterVertexLayout.LIGHT, 0);
			b.put(base + WaterVertexLayout.NORMAL, normalByte(nx));
			b.put(base + WaterVertexLayout.NORMAL + 1, normalByte(ny));
			b.put(base + WaterVertexLayout.NORMAL + 2, normalByte(nz));
			b.put(base + WaterVertexLayout.NORMAL + 3, (byte) 0);
			long key = BlockPos.asLong(
				Mth.floor(x + nx * probe) + target.originX,
				Mth.floor(y + Math.max(ny, 0f) * probe + 0.05f) + target.originY,
				Mth.floor(z + nz * probe) + target.originZ);
			if (key != lastKey) {
				lastKey = key;
				lastId = probeIds.get(key);
				if (lastId < 0) {
					lastId = target.probeCount++;
					probeIds.put(key, lastId);
					if (target.probes.length <= lastId) {
						target.probes = java.util.Arrays.copyOf(target.probes, Math.max(256, target.probes.length * 2));
					}
					target.probes[lastId] = key;
				}
			}
			target.probeIndex[i] = lastId;
			include(target, x, y, z, v[o + 6], v[o + 7], v[o + 8]);
		}
		b.position(0).limit(vertexBytes);

		// Two triangles per quad, in the same order as vanilla's quad index buffer (0 1 2, 2 3 0).
		ByteBuffer ib = target.indexBytes;
		ib.clear();
		int[] q = mesh.quads;
		for (int k = 0; k < quads; k++) {
			int a = q[k * 4];
			int bb = q[k * 4 + 1];
			int c = q[k * 4 + 2];
			int d = q[k * 4 + 3];
			int base = k * WaterVertexLayout.INDICES_PER_QUAD * Integer.BYTES;
			ib.putInt(base, a);
			ib.putInt(base + 4, bb);
			ib.putInt(base + 8, c);
			ib.putInt(base + 12, c);
			ib.putInt(base + 16, d);
			ib.putInt(base + 20, a);
		}
		ib.position(0).limit(indexBytes);
	}

	/**
	 * Worker: packs the droplets and then the spray as {@link WaterPass#PARTICLE_FORMAT} instances.
	 * Their light is filled in on the render thread.
	 */
	private static void packParticles(Result target) {
		int stride = WaterPass.PARTICLE_STRIDE;
		int dropletBytes = target.dropletCount * stride;
		target.sprayOffset = (dropletBytes + PARTICLE_ALIGNMENT - 1) / PARTICLE_ALIGNMENT * PARTICLE_ALIGNMENT;
		int bytes = target.sprayOffset + target.sprayCount * stride;
		if (target.particleBytes.capacity() < bytes) {
			target.particleBytes = ByteBuffer.allocateDirect(bytes + bytes / 2).order(ByteOrder.nativeOrder());
		}
		ByteBuffer b = target.particleBytes;
		b.clear();
		float[] d = target.droplets;
		for (int i = 0; i < target.dropletCount; i++) {
			int o = i * stride;
			int s = i * 6;
			putParticle(b, o, d[s], d[s + 1], d[s + 2], d[s + 3], d[s + 4], d[s + 5], 0f);
			include(target, d[s], d[s + 1], d[s + 2], d[s + 3], d[s + 4], d[s + 5]);
		}
		float[] f = target.spray;
		for (int i = 0; i < target.sprayCount; i++) {
			int o = target.sprayOffset + i * stride;
			int s = i * SPRAY_STRIDE;
			putParticle(b, o, f[s], f[s + 1], f[s + 2], f[s + 3], f[s + 4], f[s + 5], f[s + 6]);
			include(target, f[s], f[s + 1], f[s + 2], f[s + 3], f[s + 4], f[s + 5]);
		}
		b.position(0).limit(bytes);
	}

	private static void putParticle(ByteBuffer b, int o, float x, float y, float z, float vx, float vy, float vz, float life) {
		b.putFloat(o + WaterPass.PARTICLE_POSITION, x);
		b.putFloat(o + WaterPass.PARTICLE_POSITION + 4, y);
		b.putFloat(o + WaterPass.PARTICLE_POSITION + 8, z);
		b.putFloat(o + WaterPass.PARTICLE_VELOCITY, vx);
		b.putFloat(o + WaterPass.PARTICLE_VELOCITY + 4, vy);
		b.putFloat(o + WaterPass.PARTICLE_VELOCITY + 8, vz);
		b.putFloat(o + WaterPass.PARTICLE_LIFE, life);
		b.putInt(o + WaterPass.PARTICLE_LIGHT, 0);
	}

	/** Whether there is water at or within about half a block of a point (relative to the origin). */
	private static boolean nearWater(FluidSurfaceMesher mesher, float x, float y, float z) {
		float level = mesher.threshold() * 0.05f;
		float r = 0.6f;
		return mesher.densityAt(x, y, z) > level
			|| mesher.densityAt(x + r, y, z) > level || mesher.densityAt(x - r, y, z) > level
			|| mesher.densityAt(x, y + r, z) > level || mesher.densityAt(x, y - r, z) > level
			|| mesher.densityAt(x, y, z + r) > level || mesher.densityAt(x, y, z - r) > level;
	}

	private static byte normalByte(float value) {
		return (byte) Math.round(Mth.clamp(value, -1f, 1f) * 127f);
	}

	/** Render thread: looks up the light of a new surface, each block once (cached per block). */
	private void fillLights(ClientLevel level, Result result) {
		if (result.lightsReady) {
			return;
		}
		BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
		int probes = result.probeCount;
		if (result.probeLights.length < probes) {
			result.probeLights = new int[probes + probes / 2];
		}
		int[] probeLights = result.probeLights;
		long[] keys = result.probes;
		for (int k = 0; k < probes; k++) {
			long key = keys[k];
			probeLights[k] = light(level, block, BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
		}
		ByteBuffer b = result.vertexBytes;
		int[] probeIndex = result.probeIndex;
		int[] lights = result.lights;
		boolean littleEndian = b.order() == ByteOrder.LITTLE_ENDIAN;
		for (int i = 0; i < result.vertexCount; i++) {
			int light = probeLights[probeIndex[i]];
			lights[i] = light;
			int base = i * WaterVertexLayout.STRIDE + WaterVertexLayout.LIGHT;
			if (littleEndian) {
				// Block light in the low, sky light in the high half: the two shorts in one write.
				b.putInt(base, light);
			} else {
				b.putShort(base, (short) (light & 0xFFFF));
				b.putShort(base + 2, (short) ((light >>> 16) & 0xFFFF));
			}
		}
		result.lightsReady = true;
	}

	/** Render thread: looks up the light of new spray and droplets, where they are this frame. */
	private void fillParticleLights(ClientLevel level, Result result, float dt) {
		if (result.particleLightsReady) {
			return;
		}
		BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
		ByteBuffer b = result.particleBytes;
		int stride = WaterPass.PARTICLE_STRIDE;
		float[] d = result.droplets;
		for (int i = 0; i < result.dropletCount; i++) {
			int s = i * 6;
			b.putInt(i * stride + WaterPass.PARTICLE_LIGHT, particleLight(level, block, result,
				d[s] + d[s + 3] * dt, d[s + 1] + d[s + 4] * dt, d[s + 2] + d[s + 5] * dt));
		}
		float[] f = result.spray;
		for (int i = 0; i < result.sprayCount; i++) {
			int s = i * SPRAY_STRIDE;
			b.putInt(result.sprayOffset + i * stride + WaterPass.PARTICLE_LIGHT, particleLight(level, block, result,
				f[s] + f[s + 3] * dt, f[s + 1] + f[s + 4] * dt, f[s + 2] + f[s + 5] * dt));
		}
		result.particleLightsReady = true;
	}

	private int particleLight(ClientLevel level, BlockPos.MutableBlockPos block, Result result, float lx, float ly, float lz) {
		int light = light(level, block,
			Mth.floor(lx) + result.originX, Mth.floor(ly + LIGHT_PROBE_OFFSET) + result.originY, Mth.floor(lz) + result.originZ);
		// UV2 holds two shorts, block light first, like vanilla's vertex data.
		return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? light : ((light & 0xFFFF) << 16) | (light >>> 16);
	}

	/** Block light rarely changes: keep looked-up values for a while instead of every frame. */
	private void refreshLightCache(ClientLevel level) {
		long now = level.getGameTime();
		if (level != lightCacheLevel || now - lightCacheTick > LIGHT_CACHE_TICKS || now < lightCacheTick
			|| lightCache.size() > LIGHT_CACHE_MAX) {
			lightCache.clear();
			lightCacheLevel = level;
			lightCacheTick = now;
		}
	}

	// ------------------------------------------------------------------------------------------
	// Simple rendering (no shader): the vanilla entity pipeline, everything on the CPU
	// ------------------------------------------------------------------------------------------

	/**
	 * Submits the surface with camera-relative positions (moved along the flow for {@code dt}) and
	 * colours that already contain depth-dependent tint and transparency (shallow water clear, deep
	 * water blue), a Fresnel sky reflection and the foam.
	 */
	private void submitSimpleSurface(LevelRenderContext context, Result result, Vec3 cam, float dt, int waterColor, float opacity) {
		FluidSurfaceMesher.Mesh mesh = result.mesh;
		int vertexCount = mesh.vertexCount;
		float[] v = mesh.vertices;
		float offX = (float) (result.originX - cam.x);
		float offY = (float) (result.originY - cam.y);
		float offZ = (float) (result.originZ - cam.z);

		if (simplePositions.length < vertexCount * 3) {
			simplePositions = new float[vertexCount * 3 + vertexCount * 3 / 2];
			simpleColors = new int[vertexCount + vertexCount / 2];
		}
		float[] pos = simplePositions;
		int[] colors = simpleColors;
		SurfaceFoam.Map foamMap = manager.config().foam && !result.foamMap.empty ? result.foamMap : null;
		float deepOpacity = Mth.clamp(opacity + 0.3f, 0.3f, 0.95f);
		int shallowColor = lerpRgb(waterColor, 0xFFFFFF, 0.3f);
		int deepColor = darken(waterColor, 0.45f);

		for (int i = 0; i < vertexCount; i++) {
			int o = i * FluidSurfaceMesher.VERTEX_STRIDE;
			float x = v[o] + v[o + 6] * dt + offX;
			float y = v[o + 1] + v[o + 7] * dt + offY;
			float z = v[o + 2] + v[o + 8] * dt + offZ;
			pos[i * 3] = x;
			pos[i * 3 + 1] = y;
			pos[i * 3 + 2] = z;

			// Light passing through deeper water is absorbed more: clear when shallow, blue when deep.
			float depthFactor = 1f - (float) Math.exp(-v[o + 9] * ABSORPTION_PER_BLOCK);
			int body = lerpRgb(shallowColor, deepColor, depthFactor);
			float bodyAlpha = Mth.lerp(depthFactor, SHALLOW_OPACITY, deepOpacity);

			// Fresnel: grazing angles reflect the sky and become opaque.
			float len = Mth.sqrt(x * x + y * y + z * z);
			float cos = len > 1e-4f ? Math.abs((x * v[o + 3] + y * v[o + 4] + z * v[o + 5]) / len) : 1f;
			float fresnel = 0.04f + 0.96f * pow5(1f - cos);

			int rgb = lerpRgb(body, SKY_REFLECTION, fresnel * 0.7f);
			float foamCover = 0f;
			if (foamMap != null && v[o + 4] > FOAM_MIN_UP
				&& Math.abs(foamMap.heightCode(v[o + 1]) - foamMap.heightCodeAt(v[o], v[o + 2])) < 10) {
				foamCover = foamOpacity(foamMap.amountAt(v[o], v[o + 2]));
			}
			rgb = lerpRgb(rgb, FOAM_COLOR, foamCover);
			float alpha = bodyAlpha + (1f - bodyAlpha) * Math.max(fresnel, foamCover);
			colors[i] = (Mth.clamp(Math.round(alpha * 255f), 0, 255) << 24) | rgb;
		}

		int[] lights = result.lights;
		int[] quads = mesh.quads;
		int quadCount = mesh.quadCount;
		context.submitNodeCollector().submitCustomGeometry(new PoseStack(), surfaceType, (pose, buffer) -> {
			for (int q = 0; q < quadCount; q++) {
				for (int k = 0; k < 4; k++) {
					int i = quads[q * 4 + k];
					int o = i * FluidSurfaceMesher.VERTEX_STRIDE;
					buffer.addVertex(pose, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])
						.setColor(colors[i])
						.setUv(0.5f, 0.5f)
						.setOverlay(OverlayTexture.NO_OVERLAY)
						.setLight(lights[i])
						.setNormal(pose, v[o + 3], v[o + 4], v[o + 5]);
				}
			}
		});
	}

	/** Opacity of foam for a foam map amount; the water shader uses the same curve. */
	private static float foamOpacity(float amount) {
		return (float) Math.pow(Mth.clamp(amount * 1.15f, 0f, 1f), 1.6) * 0.97f;
	}

	// ------------------------------------------------------------------------------------------
	// Shader path: the surface is drawn by WaterPass after the main pass
	// ------------------------------------------------------------------------------------------

	/** Surface waiting to be drawn by the water shader at the end of the main pass. */
	private Result pendingSurface;
	private SurfaceFoam.Map pendingFoamMap;
	private WaterPass.Particles pendingParticles;
	/** The result the pending particles belong to (for their origin). */
	private Result pendingParticleOrigin;
	private int pendingWaterColor;
	private float pendingDt;
	private Vec3 pendingCamera;

	/** Called at {@code END_MAIN}: draws the surface with its foam, the spray and the droplets collected this frame. */
	public void drawShaderSurface(LevelRenderContext context) {
		Result surface = pendingSurface;
		SurfaceFoam.Map foamMap = pendingFoamMap;
		WaterPass.Particles particles = pendingParticles;
		Result particleOrigin = pendingParticleOrigin;
		Vec3 cam = pendingCamera;
		float dt = pendingDt;
		pendingSurface = null;
		pendingFoamMap = null;
		pendingParticles = null;
		pendingParticleOrigin = null;
		boolean hasWater = surface != null && surface.indexCount > 0 && cam != null;
		boolean hasParticles = particles != null && cam != null && particleOrigin != null;
		if (!hasWater && !hasParticles) {
			return;
		}

		try {
			InertiaConfig config = manager.config();
			var sky = context.levelState().skyRenderState;
			float sunAngle = sky.sunAngle;
			Vector3f sunDirection = new Vector3f(-Mth.sin(sunAngle), Mth.cos(sunAngle), 0f);
			float daylight = Mth.clamp(sunDirection.y * 4f + 0.2f, 0f, 1f) * Mth.clamp(sky.rainBrightness, 0f, 1f);

			WaterPass.Surface gpuSurface = null;
			// The vertices are relative to the water's origin; the shaders add this to get camera-relative positions.
			Result origin = hasWater ? surface : particleOrigin;
			Vector3f originOffset = new Vector3f(
				(float) (origin.originX - cam.x), (float) (origin.originY - cam.y), (float) (origin.originZ - cam.z));
			if (hasWater) {
				gpuSurface = new WaterPass.Surface(surface.vertexBytes, surface.vertexCount, surface.indexBytes, surface.indexCount,
					surface.serial, !surface.cameraNearWater);
			}

			int deep = darken(pendingWaterColor, 0.35f);
			org.joml.Vector3fc skyColor = sky.skyColor;
			Matrix4f parameters = new Matrix4f()
				.m00((System.nanoTime() / 1.0e9f) % 3600f)
				.m01(config.rippleStrength)
				.m02(0.06f * config.refractionStrength)
				.m03(config.waterAbsorption)
				.m10(((deep >> 16) & 0xFF) / 255f)
				.m11(((deep >> 8) & 0xFF) / 255f)
				.m12((deep & 0xFF) / 255f)
				.m13(dt)
				.m20(skyColor != null ? skyColor.x() : 0f)
				.m21(skyColor != null ? skyColor.y() : 0f)
				.m22(skyColor != null ? skyColor.z() : 0f)
				.m23(skyColor != null ? 1f : 0f)
				.m30(config.reflections ? 1f : 0f)
				.m31(config.caustics)
				.m32(config.sunStrength)
				.m33(foamMap != null ? 1f : 0f);

			waterPass.draw(gpuSurface, hasParticles ? particles : null, foamMap, sunDirection, daylight, originOffset, parameters);
		} catch (Throwable t) {
			Inertia.LOGGER.error("Water shader failed; switching to the simple water rendering", t);
			waterPass.markFailed();
		}
	}

	// ------------------------------------------------------------------------------------------
	// Spray and foam quads (simple rendering)
	// ------------------------------------------------------------------------------------------

	/** Camera-relative quads: four corners (12 floats), colour, light and normal per quad. */
	private record QuadBatch(float[] corners, int[] colors, int[] lights, float[] normals, int count) {
	}

	/** Submits quads to the vanilla translucent entity pipeline (simple rendering). */
	private static void submitQuads(LevelRenderContext context, RenderType type, QuadBatch batch) {
		if (batch == null || batch.count() == 0) {
			return;
		}
		context.submitNodeCollector().submitCustomGeometry(new PoseStack(), type, (pose, buffer) -> {
			float[] p = batch.corners();
			for (int i = 0; i < batch.count(); i++) {
				int c = i * 12;
				int color = batch.colors()[i];
				int light = batch.lights()[i];
				float nx = batch.normals()[i * 3];
				float ny = batch.normals()[i * 3 + 1];
				float nz = batch.normals()[i * 3 + 2];
				corner(buffer, pose, p[c], p[c + 1], p[c + 2], color, 0f, 1f, light, nx, ny, nz);
				corner(buffer, pose, p[c + 3], p[c + 4], p[c + 5], color, 1f, 1f, light, nx, ny, nz);
				corner(buffer, pose, p[c + 6], p[c + 7], p[c + 8], color, 1f, 0f, light, nx, ny, nz);
				corner(buffer, pose, p[c + 9], p[c + 10], p[c + 11], color, 0f, 0f, light, nx, ny, nz);
			}
		});
	}

	private QuadBatch prepareDroplets(
		ClientLevel level, Result result, Vec3 cam, float dt, Quaternionf orientation, float radius, int waterColor
	) {
		int count = result.dropletCount;
		float[] d = result.droplets;
		float offX = (float) (result.originX - cam.x);
		float offY = (float) (result.originY - cam.y);
		float offZ = (float) (result.originZ - cam.z);

		Quaternionf q = new Quaternionf(orientation);
		Vector3f right = q.transform(new Vector3f(radius, 0, 0));
		Vector3f up = q.transform(new Vector3f(0, radius, 0));
		Vector3f normal = q.transform(new Vector3f(0, 0, 1));
		int alpha = 150;
		int color = (alpha << 24) | lerpRgb(waterColor, FOAM_COLOR, 0.8f);

		float[] corners = new float[count * 12];
		int[] colors = new int[count];
		int[] lights = new int[count];
		float[] normals = new float[count * 3];
		BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
		for (int i = 0; i < count; i++) {
			float lx = d[i * 6] + d[i * 6 + 3] * dt;
			float ly = d[i * 6 + 1] + d[i * 6 + 4] * dt;
			float lz = d[i * 6 + 2] + d[i * 6 + 5] * dt;
			float x = lx + offX;
			float y = ly + offY;
			float z = lz + offZ;
			int c = i * 12;
			corners[c] = x - right.x - up.x;
			corners[c + 1] = y - right.y - up.y;
			corners[c + 2] = z - right.z - up.z;
			corners[c + 3] = x + right.x - up.x;
			corners[c + 4] = y + right.y - up.y;
			corners[c + 5] = z + right.z - up.z;
			corners[c + 6] = x + right.x + up.x;
			corners[c + 7] = y + right.y + up.y;
			corners[c + 8] = z + right.z + up.z;
			corners[c + 9] = x - right.x + up.x;
			corners[c + 10] = y - right.y + up.y;
			corners[c + 11] = z - right.z + up.z;
			colors[i] = color;
			normals[i * 3] = normal.x;
			normals[i * 3 + 1] = normal.y;
			normals[i * 3 + 2] = normal.z;
			lights[i] = light(level, block,
				Mth.floor(lx) + result.originX, Mth.floor(ly + LIGHT_PROBE_OFFSET) + result.originY, Mth.floor(lz) + result.originZ);
		}
		return new QuadBatch(corners, colors, lights, normals, count);
	}

	/** Spray just above the water: small camera-facing flecks that fade out at the end of their life. */
	private QuadBatch prepareSpray(ClientLevel level, Result result, Vec3 cam, float dt, float size) {
		int count = result.sprayCount;
		float[] f = result.spray;
		float offX = (float) (result.originX - cam.x);
		float offY = (float) (result.originY - cam.y);
		float offZ = (float) (result.originZ - cam.z);

		float[] corners = new float[count * 12];
		int[] colors = new int[count];
		int[] lights = new int[count];
		float[] normals = new float[count * 3];
		BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
		float half = size * SPRAY_SCALE;
		int drawn = 0;

		for (int i = 0; i < count; i++) {
			int o = i * SPRAY_STRIDE;
			float life = f[o + 6] - dt;
			if (life <= 0f) {
				continue;
			}
			float lx = f[o] + f[o + 3] * dt;
			float ly = f[o + 1] + f[o + 4] * dt;
			float lz = f[o + 2] + f[o + 5] * dt;
			float px = lx + offX;
			float py = ly + offY;
			float pz = lz + offZ;

			float dist = Mth.sqrt(px * px + py * py + pz * pz);
			if (dist < 1e-3f) {
				continue;
			}
			float ex = px / dist;
			float ey = py / dist;
			float ez = pz / dist;
			// Small round fleck facing the camera; (a x e) winds it front-facing.
			float ax = ez;
			float az = -ex;
			float alen = Mth.sqrt(ax * ax + az * az);
			if (alen < 1e-3f) {
				ax = 1f;
				az = 0f;
				alen = 1f;
			}
			ax = ax / alen * half;
			az = az / alen * half;
			// b = a x e with a = (ax, 0, az).
			float bx = -az * ey;
			float by = az * ex - ax * ez;
			float bz = ax * ey;
			float blen = Mth.sqrt(bx * bx + by * by + bz * bz);
			bx = bx / blen * half;
			by = by / blen * half;
			bz = bz / blen * half;

			int c = drawn * 12;
			corners[c] = px - ax - bx;
			corners[c + 1] = py - by;
			corners[c + 2] = pz - az - bz;
			corners[c + 3] = px + ax - bx;
			corners[c + 4] = py - by;
			corners[c + 5] = pz + az - bz;
			corners[c + 6] = px + ax + bx;
			corners[c + 7] = py + by;
			corners[c + 8] = pz + az + bz;
			corners[c + 9] = px - ax + bx;
			corners[c + 10] = py + by;
			corners[c + 11] = pz - az + bz;
			normals[drawn * 3] = -ex;
			normals[drawn * 3 + 1] = -ey;
			normals[drawn * 3 + 2] = -ez;

			float fade = Mth.clamp(life / SPRAY_FADE, 0f, 1f);
			int alpha = Mth.clamp(Math.round(fade * 230f), 0, 255);
			colors[drawn] = (alpha << 24) | FOAM_COLOR;
			lights[drawn] = light(level, block,
				Mth.floor(lx) + result.originX, Mth.floor(ly + LIGHT_PROBE_OFFSET) + result.originY, Mth.floor(lz) + result.originZ);
			drawn++;
		}

		return new QuadBatch(corners, colors, lights, normals, drawn);
	}

	private static void corner(
		VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, int color, float u, float v, int light,
		float nx, float ny, float nz
	) {
		buffer.addVertex(pose, x, y, z)
			.setColor(color)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light)
			.setNormal(pose, nx, ny, nz);
	}

	private int light(ClientLevel level, BlockPos.MutableBlockPos block, int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		int light = lightCache.getOrDefault(key, -1);
		if (light == -1) {
			light = LightCoordsUtil.getLightCoords(level, block.set(x, y, z));
			lightCache.put(key, light);
		}
		return light;
	}

	private static float pow5(float x) {
		float x2 = x * x;
		return x2 * x2 * x;
	}

	private static int darken(int rgb, float factor) {
		int r = Math.round(((rgb >> 16) & 0xFF) * factor);
		int g = Math.round(((rgb >> 8) & 0xFF) * factor);
		int b = Math.round((rgb & 0xFF) * factor);
		return (r << 16) | (g << 8) | b;
	}

	private static int lerpRgb(int a, int b, float t) {
		int r = Math.round(Mth.lerp(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF));
		int g = Math.round(Mth.lerp(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF));
		int bl = Math.round(Mth.lerp(t, a & 0xFF, b & 0xFF));
		return (r << 16) | (g << 8) | bl;
	}
}
