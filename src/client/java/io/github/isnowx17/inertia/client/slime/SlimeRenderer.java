package io.github.isnowx17.inertia.client.slime;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.isnowx17.inertia.util.SlimeMesh;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the slime blocks with the vanilla slime block texture on their squashed shape: an inner
 * core (the vanilla model's 10/16 cube) and the translucent outer shell, both following the body.
 */
public final class SlimeRenderer {
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/block/slime_block.png");
	private static final float CORE = 10f / 16f;

	private final SlimeWorld world;
	private RenderType type;
	private float[] positions = new float[0];
	private float[] normals = new float[0];

	public SlimeRenderer(SlimeWorld world) {
		this.world = world;
	}

	public void collectSubmits(LevelRenderContext context) {
		int bodies = world.bodyCount();
		ClientLevel level = Minecraft.getInstance().level;
		if (bodies == 0 || level == null) {
			return;
		}
		SlimeMesh mesh = SlimeWorld.MESH;
		int perBody = mesh.particleCount();
		if (positions.length < bodies * perBody * 3) {
			positions = new float[bodies * perBody * 3];
			normals = new float[bodies * perBody * 3];
		}
		Vec3 cam = context.levelState().cameraRenderState.pos;
		int drawn = world.interpolated(cam, positions);
		if (drawn == 0) {
			return;
		}
		if (type == null) {
			type = RenderTypes.entityTranslucent(TEXTURE, false);
		}
		float[] p = positions;
		float[] nrm = normals;
		java.util.Arrays.fill(nrm, 0, drawn * perBody * 3, 0f);
		int[] quads = mesh.quads;
		// Vertex normals from the quads around each surface particle.
		for (int b = 0; b < drawn; b++) {
			int base = b * perBody;
			for (int q = 0; q < mesh.quadCount(); q++) {
				int a = base + quads[q * 4], c = base + quads[q * 4 + 1], e = base + quads[q * 4 + 2], f = base + quads[q * 4 + 3];
				float ux = p[e * 3] - p[a * 3], uy = p[e * 3 + 1] - p[a * 3 + 1], uz = p[e * 3 + 2] - p[a * 3 + 2];
				float vx = p[f * 3] - p[c * 3], vy = p[f * 3 + 1] - p[c * 3 + 1], vz = p[f * 3 + 2] - p[c * 3 + 2];
				float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
				for (int k = 0; k < 4; k++) {
					int i = base + quads[q * 4 + k];
					nrm[i * 3] += nx;
					nrm[i * 3 + 1] += ny;
					nrm[i * 3 + 2] += nz;
				}
			}
		}
		int[] light = new int[drawn];
		float[] centre = new float[drawn * 3];
		for (int b = 0; b < drawn; b++) {
			light[b] = LightCoordsUtil.getLightCoords(level, world.bodyCentre(b));
			for (int i = b * perBody; i < (b + 1) * perBody; i++) {
				centre[b * 3] += p[i * 3] / perBody;
				centre[b * 3 + 1] += p[i * 3 + 1] / perBody;
				centre[b * 3 + 2] += p[i * 3 + 2] / perBody;
			}
		}
		int bodyCount = drawn;
		context.submitNodeCollector().submitCustomGeometry(new PoseStack(), type, (pose, buffer) -> {
			for (int pass = 0; pass < 2; pass++) {
				// The core first, then the shell around it.
				float scale = pass == 0 ? CORE : 1f;
				float uvLow = pass == 0 ? 3f / 16f : 0f;
				float uvSpan = pass == 0 ? CORE : 1f;
				for (int b = 0; b < bodyCount; b++) {
					int base = b * perBody;
					float cx = centre[b * 3], cy = centre[b * 3 + 1], cz = centre[b * 3 + 2];
					for (int q = 0; q < mesh.quadCount(); q++) {
						for (int k = 0; k < 4; k++) {
							int i = base + quads[q * 4 + k];
							float x = cx + (p[i * 3] - cx) * scale;
							float y = cy + (p[i * 3 + 1] - cy) * scale;
							float z = cz + (p[i * 3 + 2] - cz) * scale;
							float nx = nrm[i * 3], ny = nrm[i * 3 + 1], nz = nrm[i * 3 + 2];
							float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
							if (len > 1e-6f) {
								nx /= len;
								ny /= len;
								nz /= len;
							} else {
								ny = 1f;
							}
							buffer.addVertex(pose, x, y, z)
								.setColor(0xFFFFFFFF)
								.setUv(uvLow + mesh.quadUv[q * 8 + k * 2] * uvSpan, uvLow + mesh.quadUv[q * 8 + k * 2 + 1] * uvSpan)
								.setOverlay(OverlayTexture.NO_OVERLAY)
								.setLight(light[b])
								.setNormal(pose, nx, ny, nz);
						}
					}
				}
			}
		});
	}
}
