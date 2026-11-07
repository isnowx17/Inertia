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
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
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
 * level pass, and the spray and droplets on top of it.
 *
 * <p>Right before drawing, the colour and depth of the finished scene are copied into textures the
 * shader samples: that is what makes refraction and depth-dependent absorption possible (the water
 * cannot read the target it is drawing into). Drawing outside the main pass keeps the copy legal on
 * both the OpenGL and the Vulkan backend. The foam map is uploaded at the same point, with all its
 * mip levels.
 *
 * <p>The water surface itself stays on the GPU: its vertices ({@link WaterVertexLayout}) and its
 * triangle indices are uploaded only when the surface changes, and the vertex shader moves them
 * along their flow velocity every frame. Seen from outside the water, the faces turned away from the
 * camera are skipped. Seen from inside or right next to the water all faces are needed, and then
 * the surface is drawn twice: first only its depth, then the water shader where the depth matches,
 * so the expensive shading still runs only once per pixel.
 *
 * <p>Spray and droplets stay on the GPU too: one {@link #PARTICLE_FORMAT} instance per particle,
 * uploaded once per simulation step and turned into camera-facing quads by
 * {@code water_particle.vsh}, shaded by vanilla's entity shader.
 *
 * <p>If a shader fails to compile on a machine, {@link #isAvailable()} turns false and the renderer
 * falls back to the vanilla entity pipeline.
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

	/**
	 * One instance per spray particle or droplet (water_particle.vsh): position when the simulation
	 * data was taken and flow velocity (relative to the water's origin), remaining life (spray) and
	 * block and sky light packed like vanilla.
	 */
	public static final VertexFormat PARTICLE_FORMAT = VertexFormat.builder(1)
		.addAttribute("Position", GpuFormat.RGB32_FLOAT)
		.addAttribute("Velocity", GpuFormat.RGB32_FLOAT)
		.addAttribute("Life", GpuFormat.R32_FLOAT)
		.addAttribute("UV2", GpuFormat.RG16_SINT)
		.build();
	public static final int PARTICLE_POSITION = 0;
	public static final int PARTICLE_VELOCITY = 12;
	public static final int PARTICLE_LIFE = 24;
	public static final int PARTICLE_LIGHT = 28;
	public static final int PARTICLE_STRIDE = 32;

	/** The water shader where {@link #DEPTH_PIPELINE} left the nearest water's depth (no depth writes of its own). */
	public static final RenderPipeline PIPELINE = surfacePipeline("pipeline/water", false, new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false));

	/**
	 * The same, without the faces turned away from the camera. Seen from outside the water those are
	 * the inner sides of the surface (a pool's floor seen through its top), always hidden behind a
	 * front face, so skipping them halves the shading work. Needs no depth pre-pass.
	 */
	public static final RenderPipeline PIPELINE_CULLED = surfacePipeline("pipeline/water_culled", true, DepthStencilState.DEFAULT);

	/** Depth pre-pass of the surface: the nearest water's depth, no colour. */
	public static final RenderPipeline DEPTH_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
		.withLocation(Inertia.id("pipeline/water_depth"))
		.withVertexShader(Inertia.id("core/water"))
		.withFragmentShader(Inertia.id("core/water_depth"))
		.withVertexBinding(0, SURFACE_FORMAT)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
		.withCull(false)
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.build();

	/**
	 * Spray and droplets drawn on top of the water: quads built from {@link #PARTICLE_FORMAT}
	 * instances and shaded by the vanilla translucent entity shader, depth-tested but without depth
	 * writes. Drawing them after the water makes their soft edges blend with the water instead of
	 * the ground below it.
	 */
	public static final RenderPipeline PARTICLE_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
		.withLocation(Inertia.id("pipeline/water_particles"))
		.withVertexShader(Inertia.id("core/water_particle"))
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.withShaderDefine("PER_FACE_LIGHTING")
		.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
		.withVertexBinding(0, PARTICLE_FORMAT)
		.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
		.withCull(false)
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.build();

	private static RenderPipeline surfacePipeline(String location, boolean cull, DepthStencilState depth) {
		return RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
			.withLocation(Inertia.id(location))
			.withVertexShader(Inertia.id("core/water"))
			.withFragmentShader(Inertia.id("core/water"))
			.withBindGroupLayout(SCENE_SAMPLERS)
			.withVertexBinding(0, SURFACE_FORMAT)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withCull(cull)
			.withDepthStencilState(depth)
			.build();
	}

	private static final Identifier SURFACE_TEXTURE = Inertia.id("textures/fluid/surface.png");
	private static final Identifier PARTICLE_TEXTURE = Inertia.id("textures/fluid/spray.png");

	/**
	 * A water surface: vertices in {@link WaterVertexLayout}, 32-bit triangle indices. Uploaded only
	 * when {@code serial} differs from the surface already on the GPU.
	 *
	 * @param cullBackFaces whether the camera is outside the water, so faces turned away are hidden
	 */
	public record Surface(ByteBuffer vertices, int vertexCount, ByteBuffer indices, int indexCount, long serial, boolean cullBackFaces,
		GpuBuffer builtVertices, GpuBuffer builtIndices) {
		/** A surface already in GPU buffers (built there, see GpuSurface): nothing to upload. */
		public static Surface built(GpuBuffer vertices, int vertexCount, GpuBuffer indices, int indexCount, boolean cullBackFaces) {
			return new Surface(null, vertexCount, null, indexCount, -1L, cullBackFaces, vertices, indices);
		}

		public Surface(ByteBuffer vertices, int vertexCount, ByteBuffer indices, int indexCount, long serial, boolean cullBackFaces) {
			this(vertices, vertexCount, indices, indexCount, serial, cullBackFaces, null, null);
		}
	}

	/**
	 * Spray and droplets: {@code dropletCount} {@link #PARTICLE_FORMAT} instances from the start of
	 * {@code data}, {@code sprayCount} more from {@code sprayOffset} (bytes). Uploaded only when
	 * {@code serial} differs from the particles already on the GPU.
	 *
	 * @param dropletParameters TextureMat for the droplets, see {@code water_particle.vsh}
	 * @param sprayParameters   TextureMat for the spray
	 */
	public record Particles(
		ByteBuffer data, int dropletCount, int sprayOffset, int sprayCount, long serial,
		Matrix4f dropletParameters, Matrix4f sprayParameters
	) {
		boolean isEmpty() {
			return dropletCount <= 0 && sprayCount <= 0;
		}
	}

	private enum Status { UNKNOWN, AVAILABLE, FAILED }

	private Status status = Status.UNKNOWN;
	private GpuTexture sceneColor;
	private GpuTextureView sceneColorView;
	private GpuTexture sceneDepth;
	private GpuTextureView sceneDepthView;
	private GpuBuffer surfaceVertices;
	private GpuBuffer surfaceIndices;
	private long uploadedSurface = Long.MIN_VALUE;
	private GpuBuffer particleBuffer;
	private long uploadedParticles = Long.MIN_VALUE;
	/** The two newest foam maps: a surface built a moment ago still needs the one of its moment. */
	private final FoamSlot[] foamSlots = {new FoamSlot(), new FoamSlot()};
	private int newestFoamSlot;
	private ByteBuffer foamUpload;
	private GpuTexture emptyFoamTexture;
	private GpuTextureView emptyFoamView;

	/** Registers the pipelines so they are compiled with the other shaders on every resource reload. */
	public static void register() {
		RenderPipelines.registerOptional(PIPELINE);
		RenderPipelines.registerOptional(PIPELINE_CULLED);
		RenderPipelines.registerOptional(DEPTH_PIPELINE);
		RenderPipelines.registerOptional(PARTICLE_PIPELINE);
	}

	/** Whether the water shaders compiled on this machine. Checked once, on the render thread. */
	public boolean isAvailable() {
		if (status == Status.UNKNOWN) {
			boolean compiled = false;
			try {
				compiled = RenderSystem.getCompiledPipelineNullable(PIPELINE) != null
					&& RenderSystem.getCompiledPipelineNullable(PIPELINE_CULLED) != null
					&& RenderSystem.getCompiledPipelineNullable(DEPTH_PIPELINE) != null
					&& RenderSystem.getCompiledPipelineNullable(PARTICLE_PIPELINE) != null;
			} catch (RuntimeException e) {
				Inertia.LOGGER.error("Inertia water shader could not be compiled", e);
			}
			status = compiled && layoutMatches() ? Status.AVAILABLE : Status.FAILED;
			if (status == Status.FAILED) {
				Inertia.LOGGER.warn("Inertia water shader unavailable; using the simple water rendering");
			} else {
				Inertia.LOGGER.info("Inertia water shader active");
			}
		}
		return status == Status.AVAILABLE;
	}

	/** Whether {@link #SURFACE_FORMAT} and {@link #PARTICLE_FORMAT} have exactly the offsets the renderer writes. */
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
		var particleElements = PARTICLE_FORMAT.getElements();
		int[] particleOffsets = {PARTICLE_POSITION, PARTICLE_VELOCITY, PARTICLE_LIFE, PARTICLE_LIGHT};
		ok &= PARTICLE_FORMAT.getVertexSize() == PARTICLE_STRIDE && particleElements.size() == particleOffsets.length;
		for (int i = 0; ok && i < particleElements.size(); i++) {
			ok = particleElements.get(i).offset() == particleOffsets[i];
		}
		if (!ok) {
			Inertia.LOGGER.error("Inertia water vertex formats do not match their layout: {} / {}", SURFACE_FORMAT, PARTICLE_FORMAT);
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
	 * Copies the scene, draws the water surface with the water shader and then the spray and droplets
	 * on top.
	 *
	 * @param surface      the water surface, or {@code null} for none
	 * @param particles    spray and droplets, or {@code null} for none
	 * @param foam         foam map for this frame, or {@code null} without foam
	 * @param sunDirection world-space direction towards the sun
	 * @param daylight     0 at night, 1 at noon
	 * @param originOffset the water's origin relative to the camera
	 * @param parameters   shader parameters, see {@code water.vsh}
	 */
	public void draw(
		Surface surface, Particles particles, SurfaceFoam.Map foam, FoamKey surfaceFoam,
		Vector3f sunDirection, float daylight, Vector3f originOffset, Matrix4f parameters
	) {
		boolean drawSurface = surface != null && surface.indexCount() > 0 && surface.vertexCount() > 0;
		boolean drawParticles = particles != null && !particles.isEmpty();
		if (!drawSurface && !drawParticles) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		GpuTexture color = main.getColorTexture();
		GpuTexture depth = main.getDepthTexture();
		if (color == null || depth == null || main.getColorTextureView() == null || main.getDepthTextureView() == null) {
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		GpuTextureView foamMap = null;
		if (drawSurface) {
			// Only the water shader reads the scene; spray and droplets alone do not need the copies.
			ensureSceneTextures(color, depth);
			int width = color.getWidth(0);
			int height = color.getHeight(0);
			encoder.copyTextureToTexture(color, sceneColor, 0, 0, 0, 0, 0, width, height);
			encoder.copyTextureToTexture(depth, sceneDepth, 0, 0, 0, 0, 0, width, height);
			foamMap = foamView(encoder, foam, surfaceFoam);
			// The surface is uploaded only when it changed (and not at all when it was built on the GPU).
			if (surface.builtVertices() == null && surface.serial() != uploadedSurface) {
				uploadSurface(encoder, surface);
			}
		}
		if (drawParticles && particles.serial() != uploadedParticles) {
			uploadParticles(encoder, particles);
		}

		RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer quadIndexBuffer = quadIndices.getBuffer(6);
		Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
		var uniforms = RenderSystem.getDynamicUniforms();
		GpuBufferSlice waterTransforms = drawSurface
			? uniforms.writeTransform(modelView, new Vector4f(sunDirection.x, sunDirection.y, sunDirection.z, daylight), originOffset, parameters)
			: null;
		Vector4f white = new Vector4f(1f, 1f, 1f, 1f);
		GpuBufferSlice dropletTransforms = drawParticles && particles.dropletCount() > 0
			? uniforms.writeTransform(modelView, white, originOffset, particles.dropletParameters())
			: null;
		GpuBufferSlice sprayTransforms = drawParticles && particles.sprayCount() > 0
			? uniforms.writeTransform(modelView, white, originOffset, particles.sprayParameters())
			: null;

		// Resolve every texture before the pass opens: the first lookup of a texture uploads it, and
		// uploads are not allowed while a render pass is open.
		AbstractTexture surfaceTexture = minecraft.getTextureManager().getTexture(SURFACE_TEXTURE);
		AbstractTexture particleTexture = minecraft.getTextureManager().getTexture(PARTICLE_TEXTURE);
		var samplers = RenderSystem.getSamplerCache();

		try (RenderPass pass = encoder.createRenderPass(
			() -> "Inertia water", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty()
		)) {
			RenderSystem.bindDefaultUniforms(pass);

			if (drawSurface) {
				GpuBuffer vertexBuffer = surface.builtVertices() != null ? surface.builtVertices() : surfaceVertices;
				GpuBuffer indexBuffer = surface.builtIndices() != null ? surface.builtIndices() : surfaceIndices;
				GpuBufferSlice vertices = vertexBuffer.slice(0, (long) surface.vertexCount() * WaterVertexLayout.STRIDE);
				boolean cull = surface.cullBackFaces();

				// With all faces drawn: first the depth of the nearest water surface.
				if (!cull) {
					pass.setPipeline(RenderSystem.getCompiledPipeline(DEPTH_PIPELINE));
					pass.setUniform("DynamicTransforms", waterTransforms);
					pass.setUniform("Sampler0", surfaceTexture.getTextureView(), surfaceTexture.getSampler());
					pass.setUniform("Sampler2", minecraft.gameRenderer.lightmap(), samplers.getClampToEdge(FilterMode.LINEAR));
					pass.setVertexBuffer(0, vertices);
					pass.setIndexBuffer(indexBuffer, IndexType.INT);
					pass.drawIndexed(surface.indexCount(), 1, 0, 0, 0);
				}

				// The water shader (with the pre-pass only where that depth is).
				pass.setPipeline(RenderSystem.getCompiledPipeline(cull ? PIPELINE_CULLED : PIPELINE));
				pass.setUniform("DynamicTransforms", waterTransforms);
				pass.setUniform("Sampler0", surfaceTexture.getTextureView(), surfaceTexture.getSampler());
				pass.setUniform("Sampler2", minecraft.gameRenderer.lightmap(), samplers.getClampToEdge(FilterMode.LINEAR));
				pass.setUniform("SceneColor", sceneColorView, samplers.getClampToEdge(FilterMode.LINEAR));
				pass.setUniform("SceneDepth", sceneDepthView, samplers.getClampToEdge(FilterMode.NEAREST));
				pass.setUniform("FoamMap", foamMap, samplers.getClampToEdge(FilterMode.LINEAR, true));
				pass.setVertexBuffer(0, vertices);
				pass.setIndexBuffer(indexBuffer, IndexType.INT);
				pass.drawIndexed(surface.indexCount(), 1, 0, 0, 0);
			}

			if (drawParticles) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(PARTICLE_PIPELINE));
				pass.setIndexBuffer(quadIndexBuffer, quadIndices.type());
				pass.setUniform("Sampler0", particleTexture.getTextureView(), particleTexture.getSampler());
				pass.setUniform("Sampler1", minecraft.gameRenderer.overlayTexture().getTextureView(), samplers.getClampToEdge(FilterMode.LINEAR));
				pass.setUniform("Sampler2", minecraft.gameRenderer.lightmap(), samplers.getClampToEdge(FilterMode.LINEAR));
				// Droplets first, then spray, as one camera-facing quad (six indices) per instance.
				if (dropletTransforms != null) {
					pass.setUniform("DynamicTransforms", dropletTransforms);
					pass.setVertexBuffer(0, particleBuffer.slice(0, (long) particles.dropletCount() * PARTICLE_STRIDE));
					pass.drawIndexed(6, particles.dropletCount(), 0, 0, 0);
				}
				if (sprayTransforms != null) {
					pass.setUniform("DynamicTransforms", sprayTransforms);
					pass.setVertexBuffer(0, particleBuffer.slice(particles.sprayOffset(), (long) particles.sprayCount() * PARTICLE_STRIDE));
					pass.drawIndexed(6, particles.sprayCount(), 0, 0, 0);
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

	/** Copies new spray and droplet instances into the GPU buffer (grown by doubling when too small). */
	private void uploadParticles(CommandEncoder encoder, Particles particles) {
		long bytes = particles.sprayCount() > 0
			? particles.sprayOffset() + (long) particles.sprayCount() * PARTICLE_STRIDE
			: (long) particles.dropletCount() * PARTICLE_STRIDE;
		if (particleBuffer == null || particleBuffer.size() < bytes) {
			if (particleBuffer != null) {
				particleBuffer.close();
			}
			particleBuffer = RenderSystem.getDevice().createBuffer(() -> "Inertia spray and droplets",
				GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, Math.max(1L << 18, Long.highestOneBit(bytes) << 1));
		}
		encoder.writeToBuffer(particleBuffer.slice(0, bytes), particles.data().duplicate().position(0).limit((int) bytes));
		uploadedParticles = particles.serial();
	}

	/** A foam map as it was at one moment: the map object and its version then. */
	public record FoamKey(SurfaceFoam.Map map, long version) {
	}

	private static final class FoamSlot {
		GpuTexture texture;
		GpuTextureView view;
		SurfaceFoam.Map map;
		long version;

		boolean holds(SurfaceFoam.Map m, long v) {
			return view != null && map == m && version == v;
		}

		void close() {
			if (view != null) {
				view.close();
				texture.close();
			}
			view = null;
			texture = null;
			map = null;
		}
	}

	/**
	 * Uploads a new foam map (all mip levels) if it changed; returns the view to bind: the one of
	 * {@code wanted} (the moment the surface was built for) while it is still kept, otherwise the newest.
	 */
	private GpuTextureView foamView(CommandEncoder encoder, SurfaceFoam.Map map, FoamKey wanted) {
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
		int current = foamSlots[0].holds(map, map.version) ? 0 : foamSlots[1].holds(map, map.version) ? 1 : -1;
		if (current < 0) {
			// Over the older map, unless that is the one the surface still needs.
			int older = 1 - newestFoamSlot;
			boolean olderWanted = wanted != null && foamSlots[older].holds(wanted.map(), wanted.version());
			current = olderWanted ? newestFoamSlot : older;
			upload(encoder, foamSlots[current], map);
			newestFoamSlot = current;
		}
		if (wanted != null) {
			for (FoamSlot slot : foamSlots) {
				if (slot.holds(wanted.map(), wanted.version())) {
					return slot.view;
				}
			}
		}
		return foamSlots[current].view;
	}

	private void upload(CommandEncoder encoder, FoamSlot slot, SurfaceFoam.Map map) {
		GpuTexture texture = slot.texture;
		if (texture == null || texture.getWidth(0) != map.width || texture.getHeight(0) != map.height
			|| texture.getMipLevels() != map.levels) {
			slot.close();
			int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
			var device = RenderSystem.getDevice();
			slot.texture = device.createTexture("Inertia foam", usage, GpuFormat.RG8_UNORM, map.width, map.height, 1, map.levels);
			slot.view = device.createTextureView(slot.texture, 0, map.levels);
		}
		for (int level = 0; level < map.levels; level++) {
			int w = map.width >> level;
			int h = map.height >> level;
			int bytes = w * h * 2;
			ByteBuffer data;
			if (map.direct != null && map.direct.capacity() >= map.offsets[level] + bytes) {
				// Filled by the surface job: no copy on the render thread.
				data = map.direct.duplicate().limit(map.offsets[level] + bytes).position(map.offsets[level]);
			} else {
				if (foamUpload == null || foamUpload.capacity() < bytes) {
					foamUpload = ByteBuffer.allocateDirect(Math.max(bytes, 1 << 16));
				}
				foamUpload.clear();
				foamUpload.put(map.data, map.offsets[level], bytes);
				foamUpload.flip();
				data = foamUpload;
			}
			encoder.writeToTexture(slot.texture, data, level, 0, 0, 0, w, h);
		}
		slot.map = map;
		slot.version = map.version;
	}

	private void closeFoamTexture() {
		for (FoamSlot slot : foamSlots) {
			slot.close();
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
		if (particleBuffer != null) {
			particleBuffer.close();
			particleBuffer = null;
		}
		uploadedParticles = Long.MIN_VALUE;
		if (emptyFoamView != null) {
			emptyFoamView.close();
			emptyFoamTexture.close();
			emptyFoamView = null;
			emptyFoamTexture = null;
		}
	}
}
