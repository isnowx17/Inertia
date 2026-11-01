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
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.isnowx17.inertia.Inertia;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
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

	private static final Identifier SURFACE_TEXTURE = Inertia.id("textures/fluid/surface.png");

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
	}

	/** Whether the water shader compiled on this machine. Checked once, on the render thread. */
	public boolean isAvailable() {
		if (status == Status.UNKNOWN) {
			CompiledRenderPipeline compiled = null;
			try {
				compiled = RenderSystem.getCompiledPipelineNullable(PIPELINE);
			} catch (RuntimeException e) {
				Inertia.LOGGER.error("Inertia water shader could not be compiled", e);
			}
			status = compiled != null ? Status.AVAILABLE : Status.FAILED;
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
	 * Copies the scene and draws the given quads (vertex format {@code ENTITY}).
	 *
	 * @param vertices    vertex data as produced by a {@code BufferBuilder} with the ENTITY format
	 * @param indexCount  number of indices (6 per quad)
	 * @param sunDirection world-space direction towards the sun
	 * @param daylight    0 at night, 1 at noon
	 * @param cameraWrap  camera world position wrapped to a small range (ripple coordinates)
	 * @param parameters  shader parameters, see {@code water.vsh}
	 */
	public void draw(
		ByteBuffer vertices, int indexCount, Vector3f sunDirection, float daylight, Vector3f cameraWrap, Matrix4f parameters
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

		int bytes = vertices.remaining();
		ensureVertexCapacity(bytes);
		encoder.writeToBuffer(vertexBuffer.slice(0, bytes), vertices);

		RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer indexBuffer = quadIndices.getBuffer(indexCount);
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
			RenderSystem.getModelViewMatrixCopy(),
			new Vector4f(sunDirection.x, sunDirection.y, sunDirection.z, daylight),
			cameraWrap,
			parameters
		);

		AbstractTexture surface = minecraft.getTextureManager().getTexture(SURFACE_TEXTURE);
		var samplers = RenderSystem.getSamplerCache();

		try (RenderPass pass = encoder.createRenderPass(
			() -> "Inertia water", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty()
		)) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			pass.setUniform("DynamicTransforms", transforms);
			pass.setUniform("Sampler0", surface.getTextureView(), surface.getSampler());
			pass.setUniform("Sampler2", minecraft.gameRenderer.lightmap(), samplers.getClampToEdge(FilterMode.LINEAR));
			pass.setUniform("SceneColor", sceneColorView, samplers.getClampToEdge(FilterMode.LINEAR));
			pass.setUniform("SceneDepth", sceneDepthView, samplers.getClampToEdge(FilterMode.NEAREST));
			pass.setVertexBuffer(0, vertexBuffer.slice(0, bytes));
			pass.setIndexBuffer(indexBuffer, quadIndices.type());
			pass.drawIndexed(indexCount, 1, 0, 0, 0);
		}
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

	private void ensureVertexCapacity(int bytes) {
		if (vertexBuffer != null && vertexBuffer.size() >= bytes) {
			return;
		}
		if (vertexBuffer != null) {
			vertexBuffer.close();
		}
		long size = Math.max(1L << 20, Long.highestOneBit(Math.max(1, bytes)) << 1);
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
