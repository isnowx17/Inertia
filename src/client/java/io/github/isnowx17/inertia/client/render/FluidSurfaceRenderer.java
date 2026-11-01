package io.github.isnowx17.inertia.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.config.InertiaConfig;
import io.github.isnowx17.inertia.client.fluid.FluidManager;
import io.github.isnowx17.inertia.client.fluid.FluidSimulation;
import io.github.isnowx17.inertia.util.FluidSurfaceMesher;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the water as one continuous surface.
 *
 * <p>A background thread turns the particles into a mesh with {@link FluidSurfaceMesher} as often
 * as it can; the render thread always draws the newest finished mesh, so meshing never blocks a
 * frame. The surface is shaded with a Fresnel term (more reflective and opaque at grazing angles),
 * the biome water colour, block light and white foam where the water moves fast. Particles that are
 * too isolated to be part of the surface are drawn as small spray droplets.
 */
public final class FluidSurfaceRenderer {
	private static final Identifier SURFACE_TEXTURE = Inertia.id("textures/fluid/surface.png");
	private static final Identifier DROPLET_TEXTURE = Inertia.id("textures/fluid/particle.png");
	private static final int SKY_REFLECTION = 0xD6E8FF;
	private static final int FOAM_COLOR = 0xF2F8FF;
	/** Speed (blocks/second) at which the surface is drawn fully foamy. */
	private static final float FOAM_SPEED = 7f;
	/** Spray droplets are drawn smaller than full particles. */
	private static final float DROPLET_SCALE = 0.55f;

	private final FluidManager manager;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Inertia surface mesher");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	private final AtomicBoolean busy = new AtomicBoolean();

	// Job input, only written while no job is running.
	private float[] inputPositions = new float[0];
	private float[] inputSpeeds = new float[0];

	// Double-buffered job output: the render thread reads `latest`, the worker writes the other one.
	private final Result[] results = {new Result(), new Result()};
	private volatile Result latest;

	private FluidSurfaceMesher mesher;
	private float mesherSpacing;
	private float mesherResolution;
	private float mesherSmoothness;
	private float mesherThreshold;

	private RenderType surfaceType;
	private RenderType dropletType;
	private final Long2IntOpenHashMap lightCache = new Long2IntOpenHashMap();

	private static final class Result {
		final FluidSurfaceMesher.Mesh mesh = new FluidSurfaceMesher.Mesh();
		float[] droplets = new float[0];
		int dropletCount;
		FluidSimulation simulation;
		int originX;
		int originY;
		int originZ;
	}

	public FluidSurfaceRenderer(FluidManager manager) {
		this.manager = manager;
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
		}

		Vec3 cam = camera.pos;
		lightCache.clear();
		int waterColor = BiomeColors.getAverageWaterColor(level, BlockPos.containing(cam)) & 0xFFFFFF;
		InertiaConfig config = manager.config();

		if (!result.mesh.isEmpty()) {
			submitSurface(context, level, result, cam, waterColor, config.renderOpacity);
		}
		if (result.dropletCount > 0) {
			float radius = sim.params().spacing() * config.renderScale * 0.5f * DROPLET_SCALE;
			submitDroplets(context, level, result, cam, camera.orientation, radius, waterColor, config.renderOpacity);
		}
	}

	// ------------------------------------------------------------------------------------------
	// Background meshing
	// ------------------------------------------------------------------------------------------

	private void startMeshing(FluidSimulation sim, float partialTick) {
		if (!busy.compareAndSet(false, true)) {
			return;
		}

		InertiaConfig config = manager.config();
		ensureMesher(sim.params().spacing(), config);

		int count = sim.activeCount();
		if (inputPositions.length < count * 3) {
			inputPositions = new float[count * 3];
			inputSpeeds = new float[count];
		}
		float[] prev = sim.previousPositions();
		float[] curr = sim.currentPositions();
		float[] vel = sim.velocities();
		for (int i = 0; i < count * 3; i++) {
			inputPositions[i] = Mth.lerp(partialTick, prev[i], curr[i]);
		}
		for (int i = 0; i < count; i++) {
			float vx = vel[i * 3];
			float vy = vel[i * 3 + 1];
			float vz = vel[i * 3 + 2];
			inputSpeeds[i] = Mth.sqrt(vx * vx + vy * vy + vz * vz);
		}

		Result target = latest == results[0] ? results[1] : results[0];
		target.simulation = sim;
		target.originX = sim.originX();
		target.originY = sim.originY();
		target.originZ = sim.originZ();
		FluidSurfaceMesher job = mesher;
		float[] positions = inputPositions;
		float[] speeds = inputSpeeds;

		try {
			worker.execute(() -> {
				try {
					job.mesh(positions, speeds, count, target.mesh);
					collectDroplets(job, positions, count, target);
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
		if (mesher != null && spacing == mesherSpacing && config.surfaceResolution == mesherResolution
			&& config.surfaceSmoothness == mesherSmoothness && config.surfaceThreshold == mesherThreshold) {
			return;
		}
		float cell = spacing * config.surfaceResolution;
		float radius = spacing * config.surfaceSmoothness;
		float threshold = config.surfaceThreshold * FluidSurfaceMesher.restDensity(spacing, radius);
		mesher = new FluidSurfaceMesher(cell, radius, threshold);
		mesherSpacing = spacing;
		mesherResolution = config.surfaceResolution;
		mesherSmoothness = config.surfaceSmoothness;
		mesherThreshold = config.surfaceThreshold;
	}

	/** Particles in regions too sparse to form a surface become spray droplets. */
	private static void collectDroplets(FluidSurfaceMesher mesher, float[] positions, int count, Result target) {
		float threshold = mesher.threshold();
		int droplets = 0;
		for (int i = 0; i < count; i++) {
			float x = positions[i * 3];
			float y = positions[i * 3 + 1];
			float z = positions[i * 3 + 2];
			if (mesher.densityAt(x, y, z) < threshold) {
				if (target.droplets.length < (droplets + 1) * 3) {
					target.droplets = Arrays.copyOf(target.droplets, Math.max(64, target.droplets.length * 2));
				}
				target.droplets[droplets * 3] = x;
				target.droplets[droplets * 3 + 1] = y;
				target.droplets[droplets * 3 + 2] = z;
				droplets++;
			}
		}
		target.dropletCount = droplets;
	}

	// ------------------------------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------------------------------

	private void submitSurface(LevelRenderContext context, ClientLevel level, Result result, Vec3 cam, int waterColor, float opacity) {
		FluidSurfaceMesher.Mesh mesh = result.mesh;
		int vertexCount = mesh.vertexCount;
		float[] v = mesh.vertices;
		float offX = (float) (result.originX - cam.x);
		float offY = (float) (result.originY - cam.y);
		float offZ = (float) (result.originZ - cam.z);

		// Per-vertex camera-relative position, colour and light, computed once per frame.
		float[] pos = new float[vertexCount * 3];
		int[] colors = new int[vertexCount];
		int[] lights = new int[vertexCount];
		BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
		int baseAlpha = Math.round(Mth.clamp(opacity, 0.15f, 1f) * 255f);

		for (int i = 0; i < vertexCount; i++) {
			int o = i * FluidSurfaceMesher.VERTEX_STRIDE;
			float x = v[o] + offX;
			float y = v[o + 1] + offY;
			float z = v[o + 2] + offZ;
			pos[i * 3] = x;
			pos[i * 3 + 1] = y;
			pos[i * 3 + 2] = z;

			// Fresnel: looking straight down shows the water colour, grazing angles reflect the sky.
			float len = Mth.sqrt(x * x + y * y + z * z);
			float cos = len > 1e-4f ? Math.abs((x * v[o + 3] + y * v[o + 4] + z * v[o + 5]) / len) : 1f;
			float fresnel = 0.04f + 0.96f * pow5(1f - cos);
			float foam = Mth.clamp(v[o + 6] / FOAM_SPEED, 0f, 1f);

			int rgb = lerpRgb(darken(waterColor, 0.75f), SKY_REFLECTION, fresnel * 0.7f);
			rgb = lerpRgb(rgb, FOAM_COLOR, foam * 0.8f);
			int alpha = Mth.clamp(Math.round(baseAlpha + (255 - baseAlpha) * Math.max(fresnel, foam * 0.6f)), 0, 255);
			colors[i] = (alpha << 24) | rgb;

			int bx = Mth.floor(v[o] + 1e-3f) + result.originX;
			int by = Mth.floor(v[o + 1] + 1e-3f) + result.originY;
			int bz = Mth.floor(v[o + 2] + 1e-3f) + result.originZ;
			lights[i] = light(level, block, bx, by, bz);
		}

		int quadCount = mesh.quadCount;
		int[] quads = mesh.quads;
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

	private void submitDroplets(
		LevelRenderContext context, ClientLevel level, Result result, Vec3 cam, Quaternionf orientation,
		float radius, int waterColor, float opacity
	) {
		int count = result.dropletCount;
		float[] d = result.droplets;
		float offX = (float) (result.originX - cam.x);
		float offY = (float) (result.originY - cam.y);
		float offZ = (float) (result.originZ - cam.z);
		float[] pos = new float[count * 3];
		int[] lights = new int[count];
		BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
		for (int i = 0; i < count; i++) {
			pos[i * 3] = d[i * 3] + offX;
			pos[i * 3 + 1] = d[i * 3 + 1] + offY;
			pos[i * 3 + 2] = d[i * 3 + 2] + offZ;
			lights[i] = light(level, block,
				Mth.floor(d[i * 3]) + result.originX, Mth.floor(d[i * 3 + 1]) + result.originY, Mth.floor(d[i * 3 + 2]) + result.originZ);
		}

		Quaternionf q = new Quaternionf(orientation);
		Vector3f right = q.transform(new Vector3f(radius, 0, 0));
		Vector3f up = q.transform(new Vector3f(0, radius, 0));
		Vector3f normal = q.transform(new Vector3f(0, 0, 1));
		int alpha = Mth.clamp(Math.round(opacity * 255f), 40, 255);
		int color = (alpha << 24) | lerpRgb(waterColor, FOAM_COLOR, 0.35f);

		context.submitNodeCollector().submitCustomGeometry(new PoseStack(), dropletType, (pose, buffer) -> {
			for (int i = 0; i < count; i++) {
				float x = pos[i * 3];
				float y = pos[i * 3 + 1];
				float z = pos[i * 3 + 2];
				int light = lights[i];
				corner(buffer, pose, x - right.x - up.x, y - right.y - up.y, z - right.z - up.z, color, 0f, 1f, light, normal);
				corner(buffer, pose, x + right.x - up.x, y + right.y - up.y, z + right.z - up.z, color, 1f, 1f, light, normal);
				corner(buffer, pose, x + right.x + up.x, y + right.y + up.y, z + right.z + up.z, color, 1f, 0f, light, normal);
				corner(buffer, pose, x - right.x + up.x, y - right.y + up.y, z - right.z + up.z, color, 0f, 0f, light, normal);
			}
		});
	}

	private static void corner(
		VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, int color, float u, float v, int light, Vector3f normal
	) {
		buffer.addVertex(pose, x, y, z)
			.setColor(color)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light)
			.setNormal(pose, normal.x, normal.y, normal.z);
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
