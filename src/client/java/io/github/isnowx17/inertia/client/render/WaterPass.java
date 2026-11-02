package io.github.isnowx17.inertia.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
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
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.util.SurfaceFoam;
import io.github.isnowx17.inertia.util.WaterVertexLayout;
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
 * both the OpenGL and the Vulkan backend. The foam map is uploaded at the same point, with all its
 * mip levels.
 *
 * <p>The water surface itself stays on the GPU: its vertices ({@link WaterVertexLayout}) and its
 * triangle indices are uploaded only when the surface changes, and the vertex shader moves them
 * along their flow velocity every frame.
 *
 * <p>If the shader fails to compile on a machine, {@link #isAvailable()} turns false and the
 * renderer falls back to the vanilla entity pipeline.
 */
public final class WaterPass implements AutoCloseable {
	private static final BindGroupLayout SCENE_SAMPLERS = BindGroupLayout.builder()
		.withUniform("SceneColor", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("SceneDepth", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("FoamMap", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();

	/** Vertex format of the water surface; matches {@link WaterVertexLayout} and the vertex shader. */
	public static final VertexFormat SURFACE_FORMAT = VertexFormat.builder(0)
		.addAttribute("Position", GpuFormat.RGB32_FLOAT)
		.addAttribute("Velocity", GpuFormat.RGB32_FLOAT)
		.addAttribute("UV0", GpuFormat.RG32_FLOAT)
		.addAttribute("Info", GpuFormat.RG32_FLOAT)
		.addAttribute("UV2", GpuFormat.RG16_SINT)
		.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
		.build();

	public static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
		.withLocation(Inertia.id("pipeline/water"))
		.withVertexShader(Inertia.id("core/water"))
		.withFragmentShader(Inertia.id("core/water"))
		.withBindGroupLayout(SCENE_SAMPLERS)
		.withVertexBinding(0, SURFACE_FORMAT)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
		.withCull(false)
		.build();

	/**
	 * The same, without the faces turned away from the camera. Seen from outside the water those are
	 * the inner sides of the surface (a pool's floor seen through its top), always hidden behind a
	 * front face, so skipping them halves the shading work.
	 */
	public static final RenderPipeline PIPELINE_CULLED = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
		.withLocation(Inertia.id("pipeline/water_culled"))
		.withVertexShader(Inertia.id("core/water"))
		.withFragmentShader(Inertia.id("core/water"))
		.withBindGroupLayout(SCENE_SAMPLERS)
		.withVertexBinding(0, SURFACE_FORMAT)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
		.withCull(true)
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

	/**
	 * A water surface: vertices in {@link WaterVertexLayout}, 32-bit triangle indices. Uploaded only
	 * when {@code serial} differs from the surface already on the GPU.
	 *
	 * @param cullBackFaces whether the camera is outside the water, so faces turned away are hidden
	 */
	public record Surface(ByteBuffer vertices, int vertexCount, ByteBuffer indices, int indexCount, long serial, boolean cullBackFaces) {
	}

	private enum Status { UNKNOWN, AVAILABLE, FAILED }

	private Status status = Status.UNKNOWN;
	private GpuTexture sceneColor;
	private GpuTextureView sceneColorView;
	private GpuTexture sceneDepth;
	private GpuTextureView sceneDepthView;
	private GpuBuffer vertexBuffer;
	private GpuBuffer surfaceVertices;
	private GpuBuffer surfaceIndices;
	private long uploadedSurface = Long.MIN_VALUE;
	private GpuTexture foamTexture;
	private GpuTextureView foamView;
	private SurfaceFoam.Map uploadedFoam;
	private long uploadedFoamVersion;
	private ByteBuffer foamUpload;
	private GpuTexture emptyFoamTexture;
	private GpuTextureView emptyFoamView;

	/** Registers the pipeline so it is compiled with the other shaders on every resource reload. */
	public static void register() {
		RenderPipelines.registerOptional(PIPELINE);
		RenderPipelines.registerOptional(PIPELINE_CULLED);
		RenderPipelines.registerOptional(OVERLAY_PIPELINE);
	}

	/** Whether the water shader compiled on this machine. Checked once, on the render thread. */
	public boolean isAvailable() {
		if (status == Status.UNKNOWN) {
			CompiledRenderPipeline compiled = null;
			CompiledRenderPipeline culled = null;
			CompiledRenderPipeline overlay = null;
			try {
				compiled = RenderSystem.getCompiledPipelineNullable(PIPELINE);
				culled = RenderSystem.getCompiledPipelineNullable(PIPELINE_CULLED);
				overlay = RenderSystem.getCompiledPipelineNullable(OVERLAY_PIPELINE);
			} catch (RuntimeException e) {
				Inertia.LOGGER.error("Inertia water shader could not be compiled", e);
			}
			status = compiled != null && culled != null && overlay != null && layoutMatches() ? Status.AVAILABLE : Status.FAILED;
			if (status == Status.FAILED) {
				Inertia.LOGGER.warn("Inertia water shader unavailable; using the simple water rendering");
			} else {
				Inertia.LOGGER.info("Inertia water shader active");
			}
		}
		return status == Status.AVAILABLE;
	}

	/** Whether {@link #SURFACE_FORMAT} has exactly the offsets the renderer writes. */
	private static boolean layoutMatches() {
		var elements = SURFACE_FORMAT.getElements();
		var attributes = WaterVertexLayout.ATTRIBUTES;
		int[] offsets = {
			WaterVertexLayout.POSITION, WaterVertexLayout.VELOCITY, WaterVertexLayout.FOAM_UV,
			WaterVertexLayout.INFO, WaterVertexLayout.LIGHT, WaterVertexLayout.NORMAL
		};
		boolean ok = SURFACE_FORMAT.getVertexSize() == WaterVertexLayout.STRIDE && elements.size() == attributes.size();
		for (int i = 0; ok && i < elements.size(); i++) {
			VertexFormatElement element = elements.get(i);
			ok = element.name().equals(attributes.get(i).name()) && element.offset() == offsets[i];
		}
		if (!ok) {
			Inertia.LOGGER.error("Inertia water vertex format does not match its layout: {}", SURFACE_FORMAT);
		}
		return ok;
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
	 * Copies the scene, draws the water surface with the water shader and then the overlays
	 * (droplets, spray) on top, which use the {@code ENTITY} format.
	 *
	 * @param surface      the water surface, or {@code null} for none
	 * @param overlays     quads drawn after the water, in order
	 * @param foam         foam map for this frame, or {@code null} without foam
	 * @param sunDirection world-space direction towards the sun
	 * @param daylight     0 at night, 1 at noon
	 * @param originOffset the surface's origin relative to the camera
	 * @param parameters   shader parameters, see {@code water.vsh}
	 */
	public void draw(
		Surface surface, List<Overlay> overlays, SurfaceFoam.Map foam,
		Vector3f sunDirection, float daylight, Vector3f originOffset, Matrix4f parameters
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
		GpuTextureView foamMap = foamView(encoder, foam);

		// The surface is uploaded only when it changed.
		boolean drawSurface = surface != null && surface.indexCount() > 0 && surface.vertexCount() > 0;
		if (drawSurface && surface.serial() != uploadedSurface) {
			uploadSurface(encoder, surface);
		}

		// Upload the overlay vertex data into one buffer.
		long[] overlayOffsets = new long[overlays.size()];
		long total = 0;
		int maxIndices = 0;
		for (int i = 0; i < overlays.size(); i++) {
			overlayOffsets[i] = total;
			total += align(overlays.get(i).vertices().remaining());
			maxIndices = Math.max(maxIndices, overlays.get(i).indexCount());
		}
		ensureVertexCapacity(Math.max(total, ALIGNMENT));
		for (int i = 0; i < overlays.size(); i++) {
			ByteBuffer data = overlays.get(i).vertices();
			int bytes = data.remaining();
			if (bytes > 0) {
				encoder.writeToBuffer(vertexBuffer.slice(overlayOffsets[i], bytes), data);
			}
		}

		RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer quadIndexBuffer = quadIndices.getBuffer(Math.max(maxIndices, 6));
		Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
		GpuBufferSlice waterTransforms = RenderSystem.getDynamicUniforms().writeTransform(
			modelView,
			new Vector4f(sunDirection.x, sunDirection.y, sunDirection.z, daylight),
			originOffset,
			parameters
		);
		GpuBufferSlice overlayTransforms = RenderSystem.getDynamicUniforms().writeTransform(modelView);

		// Resolve every texture before the pass opens: the first lookup of a texture uploads it, and
		// uploads are not allowed while a render pass is open.
		AbstractTexture surfaceTexture = minecraft.getTextureManager().getTexture(SURFACE_TEXTURE);
		AbstractTexture[] overlayTextures = new AbstractTexture[overlays.size()];
		for (int i = 0; i < overlays.size(); i++) {
			overlayTextures[i] = minecraft.getTextureManager().getTexture(overlays.get(i).texture());
		}
		var samplers = RenderSystem.getSamplerCache();

		try (RenderPass pass = encoder.createRenderPass(
			() -> "Inertia water", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty()
		)) {
			RenderSystem.bindDefaultUniforms(pass);

			if (drawSurface) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(surface.cullBackFaces() ? PIPELINE_CULLED : PIPELINE));
				pass.setUniform("DynamicTransforms", waterTransforms);
				pass.setUniform("Sampler0", surfaceTexture.getTextureView(), surfaceTexture.getSampler());
				pass.setUniform("Sampler2", minecraft.gameRenderer.lightmap(), samplers.getClampToEdge(FilterMode.LINEAR));
				pass.setUniform("SceneColor", sceneColorView, samplers.getClampToEdge(FilterMode.LINEAR));
				pass.setUniform("SceneDepth", sceneDepthView, samplers.getClampToEdge(FilterMode.NEAREST));
				pass.setUniform("FoamMap", foamMap, samplers.getClampToEdge(FilterMode.LINEAR, true));
				pass.setVertexBuffer(0, surfaceVertices.slice(0, (long) surface.vertexCount() * WaterVertexLayout.STRIDE));
				pass.setIndexBuffer(surfaceIndices, IndexType.INT);
				pass.drawIndexed(surface.indexCount(), 1, 0, 0, 0);
			}

			if (!overlays.isEmpty()) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(OVERLAY_PIPELINE));
				pass.setIndexBuffer(quadIndexBuffer, quadIndices.type());
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

	/** Copies a new surface into the GPU buffers (grown by doubling when too small). */
	private void uploadSurface(CommandEncoder encoder, Surface surface) {
		var device = RenderSystem.getDevice();
		long vertexBytes = (long) surface.vertexCount() * WaterVertexLayout.STRIDE;
		long indexBytes = (long) surface.indexCount() * Integer.BYTES;
		if (surfaceVertices == null || surfaceVertices.size() < vertexBytes) {
			if (surfaceVertices != null) {
				surfaceVertices.close();
			}
			surfaceVertices = device.createBuffer(() -> "Inertia water surface vertices",
				GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, Math.max(1L << 20, Long.highestOneBit(vertexBytes) << 1));
		}
		if (surfaceIndices == null || surfaceIndices.size() < indexBytes) {
			if (surfaceIndices != null) {
				surfaceIndices.close();
			}
			surfaceIndices = device.createBuffer(() -> "Inertia water surface indices",
				GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, Math.max(1L << 20, Long.highestOneBit(indexBytes) << 1));
		}
		encoder.writeToBuffer(surfaceVertices.slice(0, vertexBytes), surface.vertices().duplicate().position(0).limit((int) vertexBytes));
		encoder.writeToBuffer(surfaceIndices.slice(0, indexBytes), surface.indices().duplicate().position(0).limit((int) indexBytes));
		uploadedSurface = surface.serial();
	}

	/** Uploads a new foam map (all mip levels) if it changed; returns the view to bind. */
	private GpuTextureView foamView(CommandEncoder encoder, SurfaceFoam.Map map) {
		var device = RenderSystem.getDevice();
		if (map == null || map.empty) {
			if (emptyFoamTexture == null) {
				int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
				emptyFoamTexture = device.createTexture("Inertia empty foam", usage, GpuFormat.RG8_UNORM, 4, 4, 1, 1);
				emptyFoamView = device.createTextureView(emptyFoamTexture);
				ByteBuffer zeros = ByteBuffer.allocateDirect(4 * 4 * 2);
				encoder.writeToTexture(emptyFoamTexture, zeros, 0, 0, 0, 0, 4, 4);
			}
			return emptyFoamView;
		}
		if (foamTexture == null || foamTexture.getWidth(0) != map.width || foamTexture.getHeight(0) != map.height
			|| foamTexture.getMipLevels() != map.levels) {
			closeFoamTexture();
			int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
			foamTexture = device.createTexture("Inertia foam", usage, GpuFormat.RG8_UNORM, map.width, map.height, 1, map.levels);
			foamView = device.createTextureView(foamTexture, 0, map.levels);
			uploadedFoam = null;
		}
		if (uploadedFoam != map || uploadedFoamVersion != map.version) {
			for (int level = 0; level < map.levels; level++) {
				int w = map.width >> level;
				int h = map.height >> level;
				int bytes = w * h * 2;
				if (foamUpload == null || foamUpload.capacity() < bytes) {
					foamUpload = ByteBuffer.allocateDirect(Math.max(bytes, 1 << 16));
				}
				foamUpload.clear();
				foamUpload.put(map.data, map.offsets[level], bytes);
				foamUpload.flip();
				encoder.writeToTexture(foamTexture, foamUpload, level, 0, 0, 0, w, h);
			}
			uploadedFoam = map;
			uploadedFoamVersion = map.version;
		}
		return foamView;
	}

	private void closeFoamTexture() {
		if (foamView != null) {
			foamView.close();
			foamTexture.close();
		}
		foamView = null;
		foamTexture = null;
		uploadedFoam = null;
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
		closeFoamTexture();
		if (surfaceVertices != null) {
			surfaceVertices.close();
			surfaceVertices = null;
		}
		if (surfaceIndices != null) {
			surfaceIndices.close();
			surfaceIndices = null;
		}
		uploadedSurface = Long.MIN_VALUE;
		if (emptyFoamView != null) {
			emptyFoamView.close();
			emptyFoamTexture.close();
			emptyFoamView = null;
			emptyFoamTexture = null;
		}
		if (vertexBuffer != null) {
			vertexBuffer.close();
			vertexBuffer = null;
		}
	}
}
