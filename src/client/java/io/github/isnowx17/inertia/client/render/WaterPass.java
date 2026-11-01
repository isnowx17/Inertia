package io.github.isnowx17.inertia.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.isnowx17.inertia.Inertia;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws the water surface with Inertia's own shader in a render pass of its own, after the main
 * level pass.
 *
 * <p>Right before drawing, the colour and depth of the finished scene are copied into textures the
 * shader samples: that is what makes refraction and depth-dependent absorption possible (the water
 * cannot read the target it is drawing into). Drawing outside the main pass keeps the copy legal on
 * both the OpenGL and the Vulkan backend.
 *
 * <p>If the shader fails to compile on a machine, {@link #isAvailable()} turns false and the
 * renderer falls back to the vanilla entity pipeline.
 */
public final class WaterPass implements AutoCloseable {
	private static final BindGroupLayout SCENE_SAMPLERS = BindGroupLayout.builder()
		.withUniform("SceneColor", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("SceneDepth", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();

	public static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
		.withLocation(Inertia.id("pipeline/water"))
		.withVertexShader(Inertia.id("core/water"))
		.withFragmentShader(Inertia.id("core/water"))
		.withBindGroupLayout(SCENE_SAMPLERS)
		.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
		.withCull(false)
		.build();

	/**
	 * Foam and spray drawn on top of the water: the vanilla translucent entity shader, depth-tested
	 * but without depth writes. Drawing them after the water makes their soft edges blend with the
	 * water instead of the ground below it.
	 */
	public static final RenderPipeline OVERLAY_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
		.withLocation(Inertia.id("pipeline/water_overlay"))
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.withShaderDefine("PER_FACE_LIGHTING")
		.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
		.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
		.withCull(false)
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.build();

	private static final Identifier SURFACE_TEXTURE = Inertia.id("textures/fluid/surface.png");
	/** Vertex data offsets in the shared buffer are aligned to this many bytes. */
	private static final int ALIGNMENT = 256;

	/** Quads drawn on top of the water with {@link #OVERLAY_PIPELINE}. */
	public record Overlay(ByteBuffer vertices, int indexCount, Identifier texture) {
	}

	private enum Status { UNKNOWN, AVAILABLE, FAILED }

	private Status status = Status.UNKNOWN;
	private GpuTexture sceneColor;
	private GpuTextureView sceneColorView;
	private GpuTexture sceneDepth;
	private GpuTextureView sceneDepthView;
	private GpuBuffer vertexBuffer;

	/** Registers the pipeline so it is compiled with the other shaders on every resource reload. */
	public static void register() {
		RenderPipelines.registerOptional(PIPELINE);
		RenderPipelines.registerOptional(OVERLAY_PIPELINE);
	}

	/** Whether the water shader compiled on this machine. Checked once, on the render thread. */
	public boolean isAvailable() {
		if (status == Status.UNKNOWN) {
			CompiledRenderPipeline compiled = null;
			CompiledRenderPipeline overlay = null;
			try {
				compiled = RenderSystem.getCompiledPipelineNullable(PIPELINE);
				overlay = RenderSystem.getCompiledPipelineNullable(OVERLAY_PIPELINE);
			} catch (RuntimeException e) {
				Inertia.LOGGER.error("Inertia water shader could not be compiled", e);
			}
			status = compiled != null && overlay != null ? Status.AVAILABLE : Status.FAILED;
			if (status == Status.FAILED) {
				Inertia.LOGGER.warn("Inertia water shader unavailable; using the simple water rendering");
			} else {
				Inertia.LOGGER.info("Inertia water shader active");
			}
		}
		return status == Status.AVAILABLE;
	}

	/** "active", "unavailable" or "not checked yet", for /inertia info. */
	public String statusName() {
		return switch (status) {
			case AVAILABLE -> "active";
			case FAILED -> "unavailable";
			case UNKNOWN -> "not checked yet";
		};
	}

	/** Disables the shader for the rest of the session after a draw error. */
	public void markFailed() {
		status = Status.FAILED;
	}

	/**
	 * Copies the scene, draws the water quads with the water shader and then the overlays (foam,
	 * spray) on top. All vertex data uses the {@code ENTITY} format.
	 *
	 * @param water        water vertex data as produced by a {@code BufferBuilder}
	 * @param indexCount   number of water indices (6 per quad)
	 * @param overlays     quads drawn after the water, in order
	 * @param sunDirection world-space direction towards the sun
	 * @param daylight     0 at night, 1 at noon
	 * @param cameraWrap   camera world position wrapped to a small range (ripple coordinates)
	 * @param parameters   shader parameters, see {@code water.vsh}
	 */
	public void draw(
		ByteBuffer water, int indexCount, List<Overlay> overlays,
		Vector3f sunDirection, float daylight, Vector3f cameraWrap, Matrix4f parameters
	) {
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		GpuTexture color = main.getColorTexture();
		GpuTexture depth = main.getDepthTexture();
		if (color == null || depth == null || main.getColorTextureView() == null || main.getDepthTextureView() == null) {
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		ensureSceneTextures(color, depth);
		int width = color.getWidth(0);
		int height = color.getHeight(0);
		encoder.copyTextureToTexture(color, sceneColor, 0, 0, 0, 0, 0, width, height);
		encoder.copyTextureToTexture(depth, sceneDepth, 0, 0, 0, 0, 0, width, height);

		// Upload all vertex data into one buffer.
		int waterBytes = water.remaining();
		long[] overlayOffsets = new long[overlays.size()];
		long total = align(waterBytes);
		int maxIndices = indexCount;
		for (int i = 0; i < overlays.size(); i++) {
			overlayOffsets[i] = total;
			total += align(overlays.get(i).vertices().remaining());
			maxIndices = Math.max(maxIndices, overlays.get(i).indexCount());
		}
		ensureVertexCapacity(total);
		if (waterBytes > 0) {
			encoder.writeToBuffer(vertexBuffer.slice(0, waterBytes), water);
		}
		for (int i = 0; i < overlays.size(); i++) {
			ByteBuffer data = overlays.get(i).vertices();
			int bytes = data.remaining();
			if (bytes > 0) {
				encoder.writeToBuffer(vertexBuffer.slice(overlayOffsets[i], bytes), data);
			}
		}

		RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer indexBuffer = quadIndices.getBuffer(maxIndices);
		Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
		GpuBufferSlice waterTransforms = RenderSystem.getDynamicUniforms().writeTransform(
			modelView,
			new Vector4f(sunDirection.x, sunDirection.y, sunDirection.z, daylight),
			cameraWrap,
			parameters
		);
		GpuBufferSlice overlayTransforms = RenderSystem.getDynamicUniforms().writeTransform(modelView);

		// Resolve every texture before the pass opens: the first lookup of a texture uploads it, and
		// uploads are not allowed while a render pass is open.
		AbstractTexture surface = minecraft.getTextureManager().getTexture(SURFACE_TEXTURE);
		AbstractTexture[] overlayTextures = new AbstractTexture[overlays.size()];
		for (int i = 0; i < overlays.size(); i++) {
			overlayTextures[i] = minecraft.getTextureManager().getTexture(overlays.get(i).texture());
		}
		var samplers = RenderSystem.getSamplerCache();

		try (RenderPass pass = encoder.createRenderPass(
			() -> "Inertia water", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty()
		)) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setIndexBuffer(indexBuffer, quadIndices.type());

			if (indexCount > 0 && waterBytes > 0) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
				pass.setUniform("DynamicTransforms", waterTransforms);
				pass.setUniform("Sampler0", surface.getTextureView(), surface.getSampler());
				pass.setUniform("Sampler2", minecraft.gameRenderer.lightmap(), samplers.getClampToEdge(FilterMode.LINEAR));
				pass.setUniform("SceneColor", sceneColorView, samplers.getClampToEdge(FilterMode.LINEAR));
				pass.setUniform("SceneDepth", sceneDepthView, samplers.getClampToEdge(FilterMode.NEAREST));
				pass.setVertexBuffer(0, vertexBuffer.slice(0, waterBytes));
				pass.drawIndexed(indexCount, 1, 0, 0, 0);
			}

			if (!overlays.isEmpty()) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(OVERLAY_PIPELINE));
				pass.setUniform("DynamicTransforms", overlayTransforms);
				pass.setUniform("Sampler1", minecraft.gameRenderer.overlayTexture().getTextureView(), samplers.getClampToEdge(FilterMode.LINEAR));
				pass.setUniform("Sampler2", minecraft.gameRenderer.lightmap(), samplers.getClampToEdge(FilterMode.LINEAR));
				for (int i = 0; i < overlays.size(); i++) {
					Overlay overlay = overlays.get(i);
					if (overlay.indexCount() == 0 || overlay.vertices().remaining() == 0) {
						continue;
					}
					AbstractTexture texture = overlayTextures[i];
					pass.setUniform("Sampler0", texture.getTextureView(), texture.getSampler());
					pass.setVertexBuffer(0, vertexBuffer.slice(overlayOffsets[i], overlay.vertices().remaining()));
					pass.drawIndexed(overlay.indexCount(), 1, 0, 0, 0);
				}
			}
		}
	}

	private static long align(long bytes) {
		return (bytes + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT;
	}

	private void ensureSceneTextures(GpuTexture color, GpuTexture depth) {
		int width = color.getWidth(0);
		int height = color.getHeight(0);
		if (sceneColor != null && sceneColor.getWidth(0) == width && sceneColor.getHeight(0) == height
			&& sceneColor.getFormat() == color.getFormat() && sceneDepth.getFormat() == depth.getFormat()) {
			return;
		}
		closeSceneTextures();
		var device = RenderSystem.getDevice();
		int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
		sceneColor = device.createTexture("Inertia scene colour", usage, color.getFormat(), width, height, 1, 1);
		sceneColorView = device.createTextureView(sceneColor);
		sceneDepth = device.createTexture("Inertia scene depth", usage, depth.getFormat(), width, height, 1, 1);
		sceneDepthView = device.createTextureView(sceneDepth);
	}

	private void ensureVertexCapacity(long bytes) {
		if (vertexBuffer != null && vertexBuffer.size() >= bytes) {
			return;
		}
		if (vertexBuffer != null) {
			vertexBuffer.close();
		}
		long size = Math.max(1L << 20, Long.highestOneBit(Math.max(1L, bytes)) << 1);
		vertexBuffer = RenderSystem.getDevice().createBuffer(
			() -> "Inertia water vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size);
	}

	private void closeSceneTextures() {
		if (sceneColorView != null) {
			sceneColorView.close();
			sceneColor.close();
			sceneDepthView.close();
			sceneDepth.close();
		}
		sceneColor = null;
		sceneColorView = null;
		sceneDepth = null;
		sceneDepthView = null;
	}

	@Override
	public void close() {
		closeSceneTextures();
		if (vertexBuffer != null) {
			vertexBuffer.close();
			vertexBuffer = null;
		}
	}
}
