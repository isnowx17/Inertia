package io.github.isnowx17.inertia.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.fluid.FluidManager;
import io.github.isnowx17.inertia.client.fluid.FluidSimulation;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
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
 * Draws every water particle as a camera-facing, sphere-shaded sprite ({@code renderMode =
 * "particles"}). The default is {@link FluidSurfaceRenderer}.
 *
 * <p>Positions are interpolated between the last two simulation ticks so the water moves smoothly
 * at any frame rate. Particles take the biome water colour and the light level of their block, and
 * fast-moving particles turn lighter to suggest foam and spray.
 */
public final class FluidParticleRenderer {
	private static final Identifier TEXTURE = Inertia.id("textures/fluid/particle.png");
	/** Speed (blocks/second) at which particles are drawn fully foam-coloured. */
	private static final float FOAM_SPEED = 8f;
	private static final int FOAM_COLOR = 0xE8F4FF;

	private final FluidManager manager;
	/** Created on first use, once the render system is up. */
	private RenderType renderType;
	private final Long2IntOpenHashMap lightCache = new Long2IntOpenHashMap();
	private float[] vertexData = new float[0];
	private int[] colorData = new int[0];
	private int[] lightData = new int[0];

	public FluidParticleRenderer(FluidManager manager) {
		this.manager = manager;
	}

	public void collectSubmits(LevelRenderContext context) {
		FluidSimulation sim = manager.simulation();
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (sim == null || level == null || sim.activeCount() == 0) {
			return;
		}

		CameraRenderState camera = context.levelState().cameraRenderState;
		float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		prepare(sim, level, camera.pos, partialTick);

		final int count = sim.activeCount();
		final float radius = sim.params().spacing() * manager.config().renderScale * 0.5f;
		final Quaternionf orientation = new Quaternionf(camera.orientation);
		final Vector3f right = orientation.transform(new Vector3f(radius, 0, 0));
		final Vector3f up = orientation.transform(new Vector3f(0, radius, 0));
		final Vector3f normal = orientation.transform(new Vector3f(0, 0, 1));
		final float[] vertices = vertexData;
		final int[] colors = colorData;
		final int[] lights = lightData;

		if (renderType == null) {
			renderType = RenderTypes.entityTranslucent(TEXTURE, false);
		}

		context.submitNodeCollector().submitCustomGeometry(new PoseStack(), renderType, (pose, buffer) -> {
			for (int i = 0; i < count; i++) {
				float x = vertices[i * 3];
				float y = vertices[i * 3 + 1];
				float z = vertices[i * 3 + 2];
				int color = colors[i];
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

	/** Computes camera-relative positions (moved along their velocity into the tick), colours and light. */
	private void prepare(FluidSimulation sim, ClientLevel level, Vec3 cameraPos, float partialTick) {
		int count = sim.activeCount();
		if (vertexData.length < count * 3) {
			vertexData = new float[count * 3];
			colorData = new int[count];
			lightData = new int[count];
		}

		float[] curr = sim.currentPositions();
		float[] vel = sim.velocities();
		int stride = FluidSimulation.STRIDE;
		// The positions are from the end of the latest step; move them on by the time since the tick.
		float dt = partialTick / 20f;
		double offX = sim.originX() - cameraPos.x;
		double offY = sim.originY() - cameraPos.y;
		double offZ = sim.originZ() - cameraPos.z;

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int waterColor = BiomeColors.getAverageWaterColor(level, BlockPos.containing(cameraPos)) & 0xFFFFFF;
		int alpha = Mth.clamp(Math.round(manager.config().renderOpacity * 255f), 0, 255);
		lightCache.clear();

		for (int i = 0; i < count; i++) {
			float vx = vel[i * stride];
			float vy = vel[i * stride + 1];
			float vz = vel[i * stride + 2];
			float px = curr[i * stride] + vx * dt;
			float py = curr[i * stride + 1] + vy * dt;
			float pz = curr[i * stride + 2] + vz * dt;
			vertexData[i * 3] = (float) (px + offX);
			vertexData[i * 3 + 1] = (float) (py + offY);
			vertexData[i * 3 + 2] = (float) (pz + offZ);

			int bx = Mth.floor(px) + sim.originX();
			int by = Mth.floor(py) + sim.originY();
			int bz = Mth.floor(pz) + sim.originZ();
			long key = BlockPos.asLong(bx, by, bz);
			int light;
			if (lightCache.containsKey(key)) {
				light = lightCache.get(key);
			} else {
				light = LightCoordsUtil.getLightCoords(level, pos.set(bx, by, bz));
				lightCache.put(key, light);
			}
			lightData[i] = light;

			float foam = Mth.clamp(Mth.sqrt(vx * vx + vy * vy + vz * vz) / FOAM_SPEED, 0f, 1f);
			colorData[i] = (alpha << 24) | lerpRgb(waterColor, FOAM_COLOR, foam * 0.7f);
		}
	}

	private static int lerpRgb(int a, int b, float t) {
		int r = Math.round(Mth.lerp(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF));
		int g = Math.round(Mth.lerp(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF));
		int bl = Math.round(Mth.lerp(t, a & 0xFF, b & 0xFF));
		return (r << 16) | (g << 8) | bl;
	}
}
