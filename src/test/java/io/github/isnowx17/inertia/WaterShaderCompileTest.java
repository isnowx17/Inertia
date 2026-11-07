package io.github.isnowx17.inertia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;
import org.lwjgl.util.spvc.Spvc;

/**
 * Compiles Inertia's water shaders the way Minecraft 26.3 does (GLSL to SPIR-V with shaderc, using
 * the vanilla include files), then cross-compiles the SPIR-V back to GLSL like the OpenGL backend.
 * Catches shader errors without needing a GPU.
 */
class WaterShaderCompileTest {
	private static final Path SHADERS = Path.of("src/client/resources/assets/inertia/shaders/core");

	@Test
	void waterShadersCompileForBothDepthConventions() throws IOException {
		for (boolean zeroToOne : new boolean[] {false, true}) {
			for (String file : new String[] {"water.vsh", "water.fsh", "water_depth.fsh", "water_particle.vsh"}) {
				int kind = file.endsWith(".vsh") ? Shaderc.shaderc_glsl_vertex_shader : Shaderc.shaderc_glsl_fragment_shader;
				ByteBuffer spirv = compile(file, kind, zeroToOne);
				crossCompileToGlsl(spirv, file);
				MemoryUtil.memFree(spirv);
			}
		}
	}

	/**
	 * The surface is drawn twice (depth pre-pass, then the water shader where the depth matches), so
	 * its position must stay invariant through the compilation to the OpenGL backend's GLSL.
	 */
	@Test
	void surfacePositionIsInvariant() throws IOException {
		ByteBuffer spirv = compile("water.vsh", Shaderc.shaderc_glsl_vertex_shader, false);
		try {
			String glsl = crossCompileToGlsl(spirv, "water.vsh");
			assertTrue(glsl.contains("invariant gl_Position"), glsl);
		} finally {
			MemoryUtil.memFree(spirv);
		}
	}

	/**
	 * Spray and droplets are drawn with Inertia's vertex shader and vanilla's entity fragment shader
	 * (PER_FACE_LIGHTING, ALPHA_CUTOUT): every input the fragment shader reads must come out of the
	 * vertex shader at the same location with the same type.
	 */
	@Test
	void particleShaderFeedsTheVanillaEntityShader() throws IOException {
		ByteBuffer vertex = compile("water_particle.vsh", Shaderc.shaderc_glsl_vertex_shader, false);
		String entity;
		try (InputStream in = WaterShaderCompileTest.class.getClassLoader().getResourceAsStream("assets/minecraft/shaders/core/entity.fsh")) {
			assertNotNull(in, "vanilla entity.fsh on the classpath");
			entity = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		ByteBuffer fragment = compileSource(entity, "entity.fsh", Shaderc.shaderc_glsl_fragment_shader, false,
			java.util.Map.of("PER_FACE_LIGHTING", "", "ALPHA_CUTOUT", "0.1"));
		try {
			java.util.Map<Integer, String> outputs = stageVariables(vertex, false);
			java.util.Map<Integer, String> inputs = stageVariables(fragment, true);
			assertTrue(!inputs.isEmpty(), "entity.fsh inputs");
			for (var input : inputs.entrySet()) {
				assertEquals(input.getValue(), outputs.get(input.getKey()), "fragment input at location " + input.getKey());
			}
		} finally {
			MemoryUtil.memFree(vertex);
			MemoryUtil.memFree(fragment);
		}
	}

	/** Location to "type" of every stage input or output of a SPIR-V module, read with SPIRV-Cross. */
	private static java.util.Map<Integer, String> stageVariables(ByteBuffer spirv, boolean inputs) {
		java.util.Map<Integer, String> variables = new java.util.TreeMap<>();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer pointer = stack.mallocPointer(1);
			assertEquals(Spvc.SPVC_SUCCESS, Spvc.spvc_context_create(pointer));
			long context = pointer.get(0);
			try {
				PointerBuffer ir = stack.mallocPointer(1);
				assertEquals(Spvc.SPVC_SUCCESS, Spvc.nspvc_context_parse_spirv(context, MemoryUtil.memAddress(spirv), spirv.remaining() / 4,
					MemoryUtil.memAddress(ir)));
				PointerBuffer compilerPointer = stack.mallocPointer(1);
				assertEquals(Spvc.SPVC_SUCCESS, Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir.get(0),
					Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, compilerPointer));
				long compiler = compilerPointer.get(0);
				PointerBuffer resources = stack.mallocPointer(1);
				assertEquals(Spvc.SPVC_SUCCESS, Spvc.spvc_compiler_create_shader_resources(compiler, resources));
				PointerBuffer list = stack.mallocPointer(1);
				PointerBuffer size = stack.mallocPointer(1);
				int type = inputs ? Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT : Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT;
				assertEquals(Spvc.SPVC_SUCCESS, Spvc.spvc_resources_get_resource_list_for_type(resources.get(0), type, list, size));
				org.lwjgl.util.spvc.SpvcReflectedResource.Buffer resourceList =
					org.lwjgl.util.spvc.SpvcReflectedResource.create(list.get(0), (int) size.get(0));
				for (org.lwjgl.util.spvc.SpvcReflectedResource resource : resourceList) {
					int location = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), org.lwjgl.util.spvc.Spv.SpvDecorationLocation);
					long handle = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
					String description = Spvc.spvc_type_get_basetype(handle) + "x" + Spvc.spvc_type_get_vector_size(handle)
						+ "x" + Spvc.spvc_type_get_columns(handle);
					variables.put(location, description);
				}
			} finally {
				Spvc.spvc_context_destroy(context);
			}
		}
		return variables;
	}

	/** The vertex shader reads the surface vertices in exactly the layout the renderer writes. */
	@Test
	void vertexInputsMatchTheWaterVertexLayout() throws IOException {
		String source = Files.readString(SHADERS.resolve("water.vsh"));
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("layout\\(location\\s*=\\s*(\\d+)\\)\\s*in\\s+(\\w+)\\s+(\\w+)\\s*;")
			.matcher(source);
		List<String> inputs = new ArrayList<>();
		while (matcher.find()) {
			inputs.add(matcher.group(1) + " " + matcher.group(2) + " " + matcher.group(3));
		}
		List<String> expected = new ArrayList<>();
		var attributes = io.github.isnowx17.inertia.util.WaterVertexLayout.ATTRIBUTES;
		int offset = 0;
		for (int i = 0; i < attributes.size(); i++) {
			var attribute = attributes.get(i);
			expected.add(i + " " + attribute.glslType() + " " + attribute.name());
			offset += attribute.bytes();
		}
		assertEquals(expected, inputs);
		assertEquals(io.github.isnowx17.inertia.util.WaterVertexLayout.STRIDE, offset);
	}

	private static ByteBuffer compile(String file, int kind, boolean zeroToOne) throws IOException {
		return compileSource(Files.readString(SHADERS.resolve(file)), file, kind, zeroToOne, java.util.Map.of());
	}

	private static ByteBuffer compileSource(String source, String file, int kind, boolean zeroToOne, java.util.Map<String, String> defines) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		List<ByteBuffer> allocations = new ArrayList<>();
		try (
			ShadercIncludeResolve resolve = ShadercIncludeResolve.create((userData, requested, type, requesting, depth) ->
				include(MemoryUtil.memASCII(requested), allocations));
			ShadercIncludeResultRelease release = ShadercIncludeResultRelease.create((userData, result) -> { })
		) {
			// Same options as com.mojang.renderpearl.frontend.shaders.GlslCompiler.
			Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
			Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
			Shaderc.shaderc_compile_options_set_preserve_bindings(options, false);
			Shaderc.shaderc_compile_options_set_optimization_level(options, 0);
			if (zeroToOne) {
				Shaderc.shaderc_compile_options_add_macro_definition(options, "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
			}
			defines.forEach((name, value) -> Shaderc.shaderc_compile_options_add_macro_definition(options, name, value));
			Shaderc.shaderc_compile_options_set_include_callbacks(options, resolve, release, 0L);

			long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, file, "main", options);
			try {
				int status = Shaderc.shaderc_result_get_compilation_status(result);
				String errors = Shaderc.shaderc_result_get_error_message(result);
				assertEquals(Shaderc.shaderc_compilation_status_success, status, file + " failed to compile:\n" + errors);
				ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
				assertNotNull(spirv);
				ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
				MemoryUtil.memCopy(spirv, copy);
				return copy;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
			allocations.forEach(MemoryUtil::memFree);
		}
	}

	/** Resolves {@code #include <minecraft:x.glsl>} from the vanilla client jar on the classpath. */
	private static long include(String requested, List<ByteBuffer> allocations) {
		String path = requested.replace("minecraft:", "assets/minecraft/shaders/include/");
		String content;
		try (InputStream in = WaterShaderCompileTest.class.getClassLoader().getResourceAsStream(path)) {
			content = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			content = null;
		}
		String name = content == null ? "" : requested;
		String body = content == null ? "include not found: " + requested : content;

		ByteBuffer nameBuffer = MemoryUtil.memUTF8(name, false);
		ByteBuffer bodyBuffer = MemoryUtil.memUTF8(body, false);
		ShadercIncludeResult result = ShadercIncludeResult.calloc();
		result.source_name(nameBuffer);
		result.content(bodyBuffer);
		allocations.add(nameBuffer);
		allocations.add(bodyBuffer);
		allocations.add(MemoryUtil.memByteBuffer(result.address(), ShadercIncludeResult.SIZEOF));
		return result.address();
	}

	/** SPIR-V to GLSL 3.30 with SPIRV-Cross, like Minecraft's OpenGL backend. */
	private static String crossCompileToGlsl(ByteBuffer spirv, String name) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer pointer = stack.mallocPointer(1);
			assertEquals(Spvc.SPVC_SUCCESS, Spvc.spvc_context_create(pointer));
			long context = pointer.get(0);
			try {
				PointerBuffer ir = stack.mallocPointer(1);
				int words = spirv.remaining() / 4;
				assertEquals(Spvc.SPVC_SUCCESS, Spvc.nspvc_context_parse_spirv(context, MemoryUtil.memAddress(spirv), words, MemoryUtil.memAddress(ir)),
					name + ": " + Spvc.spvc_context_get_last_error_string(context));
				PointerBuffer compilerPointer = stack.mallocPointer(1);
				assertEquals(Spvc.SPVC_SUCCESS, Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_GLSL, ir.get(0),
					Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, compilerPointer));
				long compiler = compilerPointer.get(0);
				PointerBuffer optionsPointer = stack.mallocPointer(1);
				assertEquals(Spvc.SPVC_SUCCESS, Spvc.spvc_compiler_create_compiler_options(compiler, optionsPointer));
				long options = optionsPointer.get(0);
				Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_GLSL_VERSION, 330);
				Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_GLSL_ES, false);
				Spvc.spvc_compiler_install_compiler_options(compiler, options);
				PointerBuffer source = stack.mallocPointer(1);
				int status = Spvc.spvc_compiler_compile(compiler, source);
				assertEquals(Spvc.SPVC_SUCCESS, status, name + ": " + Spvc.spvc_context_get_last_error_string(context));
				String glsl = MemoryUtil.memUTF8(source.get(0));
				assertTrue(glsl.contains("void main()"), name);
				return glsl;
			} finally {
				Spvc.spvc_context_destroy(context);
			}
		}
	}
}
