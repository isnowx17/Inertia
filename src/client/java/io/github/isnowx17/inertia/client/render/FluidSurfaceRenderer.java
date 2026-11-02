package io.github.isnowx17.inertia.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.config.InertiaConfig;
import io.github.isnowx17.inertia.client.fluid.FluidManager;
import io.github.isnowx17.inertia.client.fluid.FluidSimulation;
import io.github.isnowx17.inertia.util.FluidSurfaceMesher;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the water as one continuous surface, plus foam streaks and spray.
 *
 * <p>A background job turns the particles into a mesh with {@link FluidSurfaceMesher} (in parallel
 * on a dedicated pool) as often as it can; the render thread always draws the newest finished mesh,
 * so meshing never blocks a frame. Every vertex carries the local flow velocity, and the surface is
 * moved along it for the time since the mesh was built, so the water flows smoothly between
 * rebuilds.
 *
 * <p>The surface is shaded with a Fresnel term (more reflective and opaque at grazing angles), the
 * biome water colour and block light. Foam, spray and bubbles come from PhysX's diffuse particles
 * and are drawn as soft white streaks stretched along the flow, so moving water leaves foam lines
 * behind. Particles too isolated to be part of the surface are drawn as small droplets.
 */
public final class FluidSurfaceRenderer {
	private static final Identifier SURFACE_TEXTURE = Inertia.id("textures/fluid/surface.png");
	private static final Identifier DROPLET_TEXTURE = Inertia.id("textures/fluid/spray.png");
	private static final Identifier FOAM_TEXTURE = Inertia.id("textures/fluid/foam.png");
	private static final int SKY_REFLECTION = 0xD6E8FF;
	private static final int FOAM_COLOR = 0xF4FAFF;
	/** Spray droplets are drawn smaller than full particles. */
	private static final float DROPLET_SCALE = 0.35f;
	/** Never move geometry further ahead than this, in seconds (e.g. after a hitch). */
	private static final float MAX_EXTRAPOLATION = 0.15f;
	/** Foam particles drawn at most per frame; beyond this every n-th particle is drawn. */
	private static final int MAX_FOAM_DRAWN = 120_000;
	private static final float TICK_SECONDS = 0.05f;
	/** How quickly light is absorbed in the simple (non-shader) rendering, per block of water. */
	private static final float ABSORPTION_PER_BLOCK = 1.1f;
	/** Opacity of very shallow water in the simple rendering. */
	private static final float SHALLOW_OPACITY = 0.12f;
	/**
	 * Radius in blocks of one foam splat at foamSize 1 (Realistic Water: whitewater size 0.012 times
	 * foam size 3). A splat adds 0.9 * exp(-2.5 r²) alpha over its disc.
	 */
	private static final float FOAM_SPLAT_RADIUS = 0.036f;
	/** Foam fades in between these flow speeds (blocks/second), so settled water clears up. */
	private static final float FOAM_MIN_FLOW = 0.2f;
	private static final float FOAM_FULL_FLOW = 1.0f;
	/** Isolated particles slower than this (blocks/second) are not drawn as droplets. */
	private static final float MIN_DROPLET_SPEED = 1.0f;
	/** Floats per collected foam particle: position, flow velocity, lifetime, type. */
	private static final int FOAM_STRIDE = 8;
	private static final float FOAM_SURFACE = 0f;
	private static final float FOAM_SPRAY = 1f;
	/** Spray is drawn where the field density is at least this fraction of the threshold (close to water). */
	private static final float SPRAY_MIN_DENSITY = 0.01f;
	/** Size of spray flecks relative to the foam size. */
	private static final float SPRAY_SCALE = 0.45f;
	/** Foam is drawn where the field density is within this band around the surface threshold. */
	private static final float FOAM_MIN_DENSITY = 0.15f;
	private static final float FOAM_MAX_DENSITY = 1.6f;
	/** Light is sampled this far above / along the normal, so geometry touching blocks is not black. */
	private static final float LIGHT_PROBE_OFFSET = 0.35f;
	/** Distance (blocks) foam is moved towards the camera when drawn over the shader water. */
	private static final float FOAM_DEPTH_PULL = 0.2f;
	/** Foam fades out during its last this many seconds of life. */
	private static final float FOAM_FADE = 0.8f;

	private final FluidManager manager;
	private final ForkJoinPool meshPool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Inertia surface mesher");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	private final AtomicBoolean busy = new AtomicBoolean();
	private long lastMeshStart;
	/** At most 40 surface rebuilds per second. */
	private static final long MIN_MESH_INTERVAL_NANOS = 25_000_000L;

	// Job input, only written while no job is running.
	private float[] inputPositions = new float[0];
	private float[] inputVelocities = new float[0];
	private float[] inputFoam = new float[0];

	// Double-buffered job output: the render thread reads `latest`, the worker writes the other one.
	private final Result[] results = {new Result(), new Result()};
	private volatile Result latest;

	private FluidSurfaceMesher mesher;
	private float mesherCell;
	private float mesherRadius;
	private float mesherThreshold;

	private RenderType surfaceType;
	private RenderType dropletType;
	private RenderType foamType;
	private final Long2IntOpenHashMap lightCache = new Long2IntOpenHashMap();
	private final float[] velocityScratch = new float[3];

	private static final class Result {
		final FluidSurfaceMesher.Mesh mesh = new FluidSurfaceMesher.Mesh();
		/** xyz, vx, vy, vz per droplet. */
		float[] droplets = new float[0];
		int dropletCount;
		/** xyz, vx, vy, vz, lifetime per foam particle. */
		float[] foam = new float[0];
		int foamCount;
		FluidSimulation simulation;
		float spacing;
		int originX;
		int originY;
		int originZ;
		/** Time the mesh and droplets refer to. */
		long meshNanos;
		/** Time the foam positions refer to. */
		long foamNanos;
	}

	private final WaterPass waterPass;
	private final ByteBufferBuilder shaderVertexBytes = new ByteBufferBuilder(1 << 20);

	public FluidSurfaceRenderer(FluidManager manager, WaterPass waterPass) {
		this.manager = manager;
		this.waterPass = waterPass;
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
		float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		startMeshing(sim, partialTick);

		Result result = latest;
		if (result == null || result.simulation != sim) {
			return;
		}

		if (surfaceType == null) {
			surfaceType = RenderTypes.entityTranslucent(SURFACE_TEXTURE, false);
			dropletType = RenderTypes.entityTranslucent(DROPLET_TEXTURE, false);
			foamType = RenderTypes.entityTranslucent(FOAM_TEXTURE, false);
		}

		Vec3 cam = camera.pos;
		lightCache.clear();
		int waterColor = BiomeColors.getAverageWaterColor(level, BlockPos.containing(cam)) & 0xFFFFFF;
		InertiaConfig config = manager.config();
		long now = System.nanoTime();
		boolean paused = client.isPaused();
		float meshDt = paused ? 0f : extrapolation(now, result.meshNanos);
		// The surface is interpolated between the last two ticks (one tick behind the newest data);
		// foam positions are from the newest tick, so shift them back by that tick to stay in sync.
		float foamDt = paused ? -TICK_SECONDS : Mth.clamp((now - result.foamNanos) / 1.0e9f - TICK_SECONDS, -TICK_SECONDS, MAX_EXTRAPOLATION);

		boolean shader = config.waterShader && waterPass.isAvailable();
		if (!result.mesh.isEmpty()) {
			if (shader) {
				pendingShaderSurface = prepareSurface(level, result, cam, meshDt, waterColor, config.renderOpacity, true);
				pendingWaterColor = waterColor;
				pendingCamera = cam;
			} else {
				submitSurface(context, level, result, cam, meshDt, waterColor, config.renderOpacity);
			}
		}
		QuadBatch droplets = null;
		if (result.dropletCount > 0) {
			float radius = result.spacing * config.renderScale * 0.5f * DROPLET_SCALE;
			droplets = prepareDroplets(level, result, cam, meshDt, camera.orientation, radius, waterColor, config.renderOpacity);
		}
		QuadBatch foam = null;
		if (result.foamCount > 0) {
			float size = result.spacing * config.renderScale * 0.45f * config.foamSize;
			// With the shader the water surface is in the depth buffer when foam is drawn; pull the foam
			// slightly towards the camera so foam floating just below the surface stays visible.
			// Surface foam is drawn by the water itself; only spray is drawn as flecks.
			foam = prepareFoam(level, result, cam, foamDt, size, shader ? FOAM_DEPTH_PULL : 0f, false);
		}

		if (shader) {
			// Drawn after the water by WaterPass, so their soft edges blend with the water.
			pendingDroplets = droplets;
			pendingFoam = foam;
			pendingCamera = cam;
		} else {
			submitQuads(context, dropletType, droplets);
			submitQuads(context, foamType, foam);
		}
	}

	private static float extrapolation(long now, long then) {
		return Mth.clamp((now - then) / 1.0e9f, 0f, MAX_EXTRAPOLATION);
	}

	// ------------------------------------------------------------------------------------------
	// Background meshing
	// ------------------------------------------------------------------------------------------

	private void startMeshing(FluidSimulation sim, float partialTick) {
		// The surface follows the flow between rebuilds, so rebuilding faster than this only takes CPU
		// time away from the rest of the game.
		long now = System.nanoTime();
		if (now - lastMeshStart < MIN_MESH_INTERVAL_NANOS) {
			return;
		}
		if (!busy.compareAndSet(false, true)) {
			return;
		}
		lastMeshStart = now;

		InertiaConfig config = manager.config();
		float spacing = sim.params().spacing();
		ensureMesher(spacing, config);

		int count = sim.activeCount();
		if (inputPositions.length < count * 3) {
			inputPositions = new float[count * 3];
			inputVelocities = new float[count * 3];
		}
		float[] prev = sim.previousPositions();
		float[] curr = sim.currentPositions();
		for (int i = 0; i < count * 3; i++) {
			inputPositions[i] = Mth.lerp(partialTick, prev[i], curr[i]);
		}
		System.arraycopy(sim.velocities(), 0, inputVelocities, 0, count * 3);

		int foamCount = config.foam ? sim.foamCount() : 0;
		if (inputFoam.length < foamCount * 4) {
			inputFoam = new float[foamCount * 4];
		}
		System.arraycopy(sim.foamParticles(), 0, inputFoam, 0, foamCount * 4);

		Result target = latest == results[0] ? results[1] : results[0];
		target.simulation = sim;
		target.spacing = spacing;
		target.originX = sim.originX();
		target.originY = sim.originY();
		target.originZ = sim.originZ();
		target.meshNanos = System.nanoTime();
		target.foamNanos = sim.readBackNanos();
		FluidSurfaceMesher job = mesher;
		float[] positions = inputPositions;
		float[] velocities = inputVelocities;
		float[] foamInput = inputFoam;

		try {
			worker.execute(() -> {
				try {
					job.mesh(positions, velocities, count, foamInput, foamCount, target.mesh);
					collectDroplets(job, positions, velocities, count, target);
					collectFoam(job, foamInput, foamCount, target);
					latest = target;
				} catch (Throwable t) {
					Inertia.LOGGER.error("Water surface meshing failed", t);
				} finally {
					busy.set(false);
				}
			});
		} catch (RuntimeException e) {
			busy.set(false);
			throw e;
		}
	}

	private void ensureMesher(float spacing, InertiaConfig config) {
		float cell = config.effectiveQuality().surfaceCellSize();
		// The kernel must span a few particles for the surface to be continuous.
		float radius = Math.max(cell * config.surfaceSmoothness, spacing * 2.5f);
		float threshold = config.surfaceThreshold * FluidSurfaceMesher.restDensity(spacing, radius);
		if (mesher != null && cell == mesherCell && radius == mesherRadius && threshold == mesherThreshold) {
			return;
		}
		mesher = new FluidSurfaceMesher(cell, radius, threshold, meshPool);
		mesherCell = cell;
		mesherRadius = radius;
		mesherThreshold = threshold;
	}

	/** Particles in regions too sparse to form a surface become spray droplets. */
	private static void collectDroplets(FluidSurfaceMesher mesher, float[] positions, float[] velocities, int count, Result target) {
		int[] sparse = mesher.sparseParticles(positions, count);
		if (target.droplets.length < sparse.length * 6) {
			target.droplets = new float[sparse.length * 6];
		}
		int n = 0;
		for (int i : sparse) {
			float vx = velocities[i * 3];
			float vy = velocities[i * 3 + 1];
			float vz = velocities[i * 3 + 2];
			// A lone particle lying still on the ground is a leftover of a drained puddle, not spray.
			if (vx * vx + vy * vy + vz * vz < MIN_DROPLET_SPEED * MIN_DROPLET_SPEED) {
				continue;
			}
			System.arraycopy(positions, i * 3, target.droplets, n * 6, 3);
			System.arraycopy(velocities, i * 3, target.droplets, n * 6 + 3, 3);
			n++;
		}
		target.dropletCount = n;
	}

	/** Attaches the local flow velocity to every foam particle; it moves and stretches the foam. */
	private void collectFoam(FluidSurfaceMesher mesher, float[] foam, int count, Result target) {
		int stride = Math.max(1, (count + MAX_FOAM_DRAWN - 1) / MAX_FOAM_DRAWN);
		int drawn = (count + stride - 1) / stride;
		if (target.foam.length < drawn * FOAM_STRIDE) {
			target.foam = new float[drawn * FOAM_STRIDE];
		}
		float[] v = velocityScratch;
		float sprayDensity = mesher.threshold() * SPRAY_MIN_DENSITY;
		float minDensity = mesher.threshold() * FOAM_MIN_DENSITY;
		float maxDensity = mesher.threshold() * FOAM_MAX_DENSITY;
		int n = 0;
		for (int i = 0; i < count; i += stride) {
			float x = foam[i * 4];
			float y = foam[i * 4 + 1];
			float z = foam[i * 4 + 2];
			float life = foam[i * 4 + 3];
			if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || life <= 0f) {
				continue;
			}
			// In the surface band of the water: foam lying on the surface. Just outside the water:
			// spray. Deep inside (bubbles) or far from any water: not drawn.
			float density = mesher.densityAt(x, y, z);
			float type;
			if (density >= minDensity && density <= maxDensity) {
				type = FOAM_SURFACE;
			} else if (density >= sprayDensity && density < minDensity) {
				type = FOAM_SPRAY;
			} else {
				continue;
			}
			mesher.velocityAt(x, y, z, v);
			int o = n * FOAM_STRIDE;
			target.foam[o] = x;
			target.foam[o + 1] = y;
			target.foam[o + 2] = z;
			target.foam[o + 3] = v[0];
			target.foam[o + 4] = v[1];
			target.foam[o + 5] = v[2];
			target.foam[o + 6] = life;
			target.foam[o + 7] = type;
			n++;
		}
		target.foamCount = n;
	}

	// ------------------------------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------------------------------

	/** Per-vertex data of the surface for one frame. */
	private record SurfaceFrame(float[] positions, int[] colors, int[] lights, float[] foam, float[] thickness, float[] normals,
		int[] quads, int quadCount) {
	}

	/**
	 * Computes camera-relative positions (moved along the flow for {@code dt}), colours and light
	 * for every surface vertex.
	 *
	 * <p>With {@code forShader} the colour is the plain biome water colour and the shader does all
	 * shading. Otherwise the colour already contains depth-dependent tint and transparency (shallow
	 * water clear, deep water blue) and a Fresnel sky reflection.
	 */
	private SurfaceFrame prepareSurface(ClientLevel level, Result result, Vec3 cam, float dt, int waterColor, float opacity, boolean forShader) {
		FluidSurfaceMesher.Mesh mesh = result.mesh;
		int vertexCount = mesh.vertexCount;
		float[] v = mesh.vertices;
		float offX = (float) (result.originX - cam.x);
		float offY = (float) (result.originY - cam.y);
		float offZ = (float) (result.originZ - cam.z);

		float[] pos = new float[vertexCount * 3];
		float[] normals = new float[vertexCount * 3];
		int[] colors = new int[vertexCount];
		int[] lights = new int[vertexCount];
		float[] foam = new float[vertexCount];
		InertiaConfig config = manager.config();
		// Foam particles per square block -> accumulated foam alpha: each splat contributes the
		// integral of 0.9 * exp(-2.5 d²/R²) over its disc, 0.9 * π R² / 2.5.
		float splatRadius = FOAM_SPLAT_RADIUS * config.foamSize;
		float foamAlphaPerParticle = config.foam ? 0.9f * (float) Math.PI * splatRadius * splatRadius / 2.5f : 0f;
		float[] thickness = new float[vertexCount];
		BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
		float deepOpacity = Mth.clamp(opacity + 0.3f, 0.3f, 0.95f);
		int shallowColor = lerpRgb(waterColor, 0xFFFFFF, 0.3f);
		int deepColor = darken(waterColor, 0.45f);

		for (int i = 0; i < vertexCount; i++) {
			int o = i * FluidSurfaceMesher.VERTEX_STRIDE;
			float vx = v[o + 6];
			float vy = v[o + 7];
			float vz = v[o + 8];
			// Move the vertex along the flow for the time since the mesh was built.
			float lx = v[o] + vx * dt;
			float ly = v[o + 1] + vy * dt;
			float lz = v[o + 2] + vz * dt;
			float x = lx + offX;
			float y = ly + offY;
			float z = lz + offZ;
			pos[i * 3] = x;
			pos[i * 3 + 1] = y;
			pos[i * 3 + 2] = z;
			normals[i * 3] = v[o + 3];
			normals[i * 3 + 1] = v[o + 4];
			normals[i * 3 + 2] = v[o + 5];

			float speed = Mth.sqrt(vx * vx + vy * vy + vz * vz);
			foam[i] = v[o + 10] * foamAlphaPerParticle * smoothstep(FOAM_MIN_FLOW, FOAM_FULL_FLOW, speed);
			thickness[i] = v[o + 9];

			if (forShader) {
				colors[i] = 0xFF000000 | waterColor;
			} else {
				// Light passing through deeper water is absorbed more: clear when shallow, blue when deep.
				float depthFactor = 1f - (float) Math.exp(-v[o + 9] * ABSORPTION_PER_BLOCK);
				int body = lerpRgb(shallowColor, deepColor, depthFactor);
				float bodyAlpha = Mth.lerp(depthFactor, SHALLOW_OPACITY, deepOpacity);

				// Fresnel: grazing angles reflect the sky and become opaque.
				float len = Mth.sqrt(x * x + y * y + z * z);
				float cos = len > 1e-4f ? Math.abs((x * v[o + 3] + y * v[o + 4] + z * v[o + 5]) / len) : 1f;
				float fresnel = 0.04f + 0.96f * pow5(1f - cos);

				int rgb = lerpRgb(body, SKY_REFLECTION, fresnel * 0.7f);
				float foamCover = Math.min(1f - (float) Math.exp(-foam[i] * config.foamCoverage), 0.9f);
				rgb = lerpRgb(rgb, FOAM_COLOR, foamCover);
				float alpha = bodyAlpha + (1f - bodyAlpha) * Math.max(fresnel, foamCover);
				colors[i] = (Mth.clamp(Math.round(alpha * 255f), 0, 255) << 24) | rgb;
			}

			float probe = LIGHT_PROBE_OFFSET;
			lights[i] = light(level, block,
				Mth.floor(lx + v[o + 3] * probe) + result.originX,
				Mth.floor(ly + Math.max(v[o + 4], 0f) * probe + 0.05f) + result.originY,
				Mth.floor(lz + v[o + 5] * probe) + result.originZ);
		}
		return new SurfaceFrame(pos, colors, lights, foam, thickness, normals, mesh.quads, mesh.quadCount);
	}

	private void submitSurface(
		LevelRenderContext context, ClientLevel level, Result result, Vec3 cam, float dt, int waterColor, float opacity
	) {
		SurfaceFrame frame = prepareSurface(level, result, cam, dt, waterColor, opacity, false);
		context.submitNodeCollector().submitCustomGeometry(new PoseStack(), surfaceType, (pose, buffer) -> {
			for (int q = 0; q < frame.quadCount(); q++) {
				for (int k = 0; k < 4; k++) {
					int i = frame.quads()[q * 4 + k];
					buffer.addVertex(pose, frame.positions()[i * 3], frame.positions()[i * 3 + 1], frame.positions()[i * 3 + 2])
						.setColor(frame.colors()[i])
						.setUv(0.5f, 0.5f)
						.setOverlay(OverlayTexture.NO_OVERLAY)
						.setLight(frame.lights()[i])
						.setNormal(pose, frame.normals()[i * 3], frame.normals()[i * 3 + 1], frame.normals()[i * 3 + 2]);
				}
			}
		});
	}

	// ------------------------------------------------------------------------------------------
	// Shader path: the surface is drawn by WaterPass after the main pass
	// ------------------------------------------------------------------------------------------

	/** Surface waiting to be drawn by the water shader at the end of the main pass. */
	private SurfaceFrame pendingShaderSurface;
	private QuadBatch pendingDroplets;
	private QuadBatch pendingFoam;
	private int pendingWaterColor;
	private Vec3 pendingCamera;
	private final ByteBufferBuilder dropletVertexBytes = new ByteBufferBuilder(1 << 16);
	private final ByteBufferBuilder foamVertexBytes = new ByteBufferBuilder(1 << 18);

	/** Called at {@code END_MAIN}: draws the surface, spray and foam collected this frame. */
	public void drawShaderSurface(LevelRenderContext context) {
		SurfaceFrame frame = pendingShaderSurface;
		QuadBatch droplets = pendingDroplets;
		QuadBatch foam = pendingFoam;
		pendingShaderSurface = null;
		pendingDroplets = null;
		pendingFoam = null;
		boolean hasWater = frame != null && frame.quadCount() > 0;
		boolean hasDroplets = droplets != null && droplets.count() > 0;
		boolean hasFoam = foam != null && foam.count() > 0;
		if (!hasWater && !hasDroplets && !hasFoam) {
			return;
		}

		MeshData water = null;
		MeshData dropletMesh = null;
		MeshData foamMesh = null;
		try {
			if (hasWater) {
				BufferBuilder builder = new BufferBuilder(shaderVertexBytes, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
				for (int q = 0; q < frame.quadCount(); q++) {
					for (int k = 0; k < 4; k++) {
						int i = frame.quads()[q * 4 + k];
						builder.addVertex(frame.positions()[i * 3], frame.positions()[i * 3 + 1], frame.positions()[i * 3 + 2])
							.setColor(frame.colors()[i])
							.setUv(frame.foam()[i], frame.thickness()[i])
							.setOverlay(OverlayTexture.NO_OVERLAY)
							.setLight(frame.lights()[i])
							.setNormal(frame.normals()[i * 3], frame.normals()[i * 3 + 1], frame.normals()[i * 3 + 2]);
					}
				}
				water = builder.build();
			}
			if (hasDroplets) {
				dropletMesh = buildQuads(dropletVertexBytes, droplets);
			}
			if (hasFoam) {
				foamMesh = buildQuads(foamVertexBytes, foam);
			}

			java.util.List<WaterPass.Overlay> overlays = new java.util.ArrayList<>(2);
			if (dropletMesh != null) {
				overlays.add(new WaterPass.Overlay(dropletMesh.vertexBuffer(), dropletMesh.drawState().indexCount(), DROPLET_TEXTURE));
			}
			if (foamMesh != null) {
				// In the shader path this batch only holds spray (surface foam is part of the water).
				overlays.add(new WaterPass.Overlay(foamMesh.vertexBuffer(), foamMesh.drawState().indexCount(), DROPLET_TEXTURE));
			}

			InertiaConfig config = manager.config();
			var sky = context.levelState().skyRenderState;
			float sunAngle = sky.sunAngle;
			Vector3f sunDirection = new Vector3f(-Mth.sin(sunAngle), Mth.cos(sunAngle), 0f);
			float daylight = Mth.clamp(sunDirection.y * 4f + 0.2f, 0f, 1f) * Mth.clamp(sky.rainBrightness, 0f, 1f);
			Vec3 cam = pendingCamera;
			Vector3f cameraWrap = new Vector3f((float) (cam.x % 4096.0), (float) (cam.y % 4096.0), (float) (cam.z % 4096.0));

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
				.m13(0.08f)
				.m20(skyColor != null ? skyColor.x() : 0f)
				.m21(skyColor != null ? skyColor.y() : 0f)
				.m22(skyColor != null ? skyColor.z() : 0f)
				.m23(skyColor != null ? 1f : 0f)
				.m30(config.reflections ? 1f : 0f)
				.m31(config.caustics)
				.m32(config.sunStrength)
				.m33(config.foam ? config.foamCoverage : 0f);

			ByteBuffer waterVertices = water != null ? water.vertexBuffer() : ByteBuffer.allocateDirect(0);
			int waterIndices = water != null ? water.drawState().indexCount() : 0;
			waterPass.draw(waterVertices, waterIndices, overlays, sunDirection, daylight, cameraWrap, parameters);
		} catch (Throwable t) {
			Inertia.LOGGER.error("Water shader failed; switching to the simple water rendering", t);
			waterPass.markFailed();
		} finally {
			if (water != null) {
				water.close();
			}
			if (dropletMesh != null) {
				dropletMesh.close();
			}
			if (foamMesh != null) {
				foamMesh.close();
			}
		}
	}

	private static MeshData buildQuads(ByteBufferBuilder bytes, QuadBatch batch) {
		BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
		for (int i = 0; i < batch.count(); i++) {
			int c = i * 12;
			int color = batch.colors()[i];
			int light = batch.lights()[i];
			float nx = batch.normals()[i * 3];
			float ny = batch.normals()[i * 3 + 1];
			float nz = batch.normals()[i * 3 + 2];
			float[] p = batch.corners();
			quadCorner(builder, p[c], p[c + 1], p[c + 2], color, 0f, 1f, light, nx, ny, nz);
			quadCorner(builder, p[c + 3], p[c + 4], p[c + 5], color, 1f, 1f, light, nx, ny, nz);
			quadCorner(builder, p[c + 6], p[c + 7], p[c + 8], color, 1f, 0f, light, nx, ny, nz);
			quadCorner(builder, p[c + 9], p[c + 10], p[c + 11], color, 0f, 0f, light, nx, ny, nz);
		}
		return builder.build();
	}

	private static void quadCorner(
		BufferBuilder builder, float x, float y, float z, int color, float u, float v, int light, float nx, float ny, float nz
	) {
		builder.addVertex(x, y, z)
			.setColor(color)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light)
			.setNormal(nx, ny, nz);
	}

	// ------------------------------------------------------------------------------------------
	// Spray and foam quads
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
		ClientLevel level, Result result, Vec3 cam, float dt, Quaternionf orientation, float radius, int waterColor, float opacity
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

	/**
	 * Foam on the surface is drawn as thin streaks lying flat on the water, stretched along the
	 * horizontal flow: carried by the current they line up into the white foam lines of moving
	 * water. Spray just above the water is drawn as small camera-facing flecks.
	 */
	private QuadBatch prepareFoam(ClientLevel level, Result result, Vec3 cam, float dt, float size, float pull, boolean includeSurface) {
		int count = result.foamCount;
		float[] f = result.foam;
		float offX = (float) (result.originX - cam.x);
		float offY = (float) (result.originY - cam.y);
		float offZ = (float) (result.originZ - cam.z);

		float[] corners = new float[count * 12];
		int[] colors = new int[count];
		int[] lights = new int[count];
		float[] normals = new float[count * 3];
		BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
		int drawn = 0;

		for (int i = 0; i < count; i++) {
			int o = i * FOAM_STRIDE;
			float vx = f[o + 3];
			float vy = f[o + 4];
			float vz = f[o + 5];
			float life = f[o + 6] - dt;
			boolean spray = f[o + 7] == FOAM_SPRAY;
			if (life <= 0f || (!spray && !includeSurface)) {
				continue;
			}
			float lx = f[o] + vx * dt;
			float ly = f[o + 1] + vy * dt;
			float lz = f[o + 2] + vz * dt;
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
			// Pull towards the camera so foam floating just below the shader water stays visible.
			float shift = Math.min(pull, dist * 0.5f);
			px -= ex * shift;
			py -= ey * shift;
			pz -= ez * shift;

			float ax;
			float ay;
			float az;
			float bx;
			float by;
			float bz;
			float nx;
			float ny;
			float nz;
			if (spray) {
				// Small round fleck facing the camera; (a x e) winds it front-facing.
				float half = size * SPRAY_SCALE;
				ax = ez;
				ay = 0f;
				az = -ex;
				float alen = Mth.sqrt(ax * ax + az * az);
				if (alen < 1e-3f) {
					ax = 1f;
					alen = 1f;
				}
				ax = ax / alen * half;
				az = az / alen * half;
				bx = ay * ez - az * ey;
				by = az * ex - ax * ez;
				bz = ax * ey - ay * ex;
				float blen = Mth.sqrt(bx * bx + by * by + bz * bz);
				bx = bx / blen * half;
				by = by / blen * half;
				bz = bz / blen * half;
				nx = -ex;
				ny = -ey;
				nz = -ez;
			} else {
				// Flat streak on the water, along the horizontal flow. Still water gets a slowly varying
				// direction from its position so the foam does not all line up.
				float hx = vx;
				float hz = vz;
				float speed = Mth.sqrt(hx * hx + hz * hz);
				if (speed < 0.05f) {
					float angle = (Mth.sin(lx * 0.73f + lz * 0.41f) + Mth.cos(lz * 0.67f - lx * 0.29f)) * Mth.PI;
					hx = Mth.cos(angle);
					hz = Mth.sin(angle);
					speed = 1f;
				}
				float halfLength = size * (1.0f + Math.min(speed * 0.2f, 1.0f));
				float halfWidth = size * 0.6f;
				ax = hx / speed * halfLength;
				ay = 0f;
				az = hz / speed * halfLength;
				// b = up x a: with this corner order the quad faces up (front-facing from above).
				bx = az / halfLength * halfWidth;
				by = 0f;
				bz = -ax / halfLength * halfWidth;
				nx = 0f;
				ny = 1f;
				nz = 0f;
			}

			int c = drawn * 12;
			corners[c] = px - ax - bx;
			corners[c + 1] = py - ay - by;
			corners[c + 2] = pz - az - bz;
			corners[c + 3] = px + ax - bx;
			corners[c + 4] = py + ay - by;
			corners[c + 5] = pz + az - bz;
			corners[c + 6] = px + ax + bx;
			corners[c + 7] = py + ay + by;
			corners[c + 8] = pz + az + bz;
			corners[c + 9] = px - ax + bx;
			corners[c + 10] = py - ay + by;
			corners[c + 11] = pz - az + bz;
			normals[drawn * 3] = nx;
			normals[drawn * 3 + 1] = ny;
			normals[drawn * 3 + 2] = nz;

			float fade = Mth.clamp(life / FOAM_FADE, 0f, 1f);
			int alpha = Mth.clamp(Math.round(fade * (spray ? 230f : 215f)), 0, 255);
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

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
		return t * t * (3f - 2f * t);
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
