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
			ByteBuffer vertex = compile("water.vsh", Shaderc.shaderc_glsl_vertex_shader, zeroToOne);
			ByteBuffer fragment = compile("water.fsh", Shaderc.shaderc_glsl_fragment_shader, zeroToOne);
			crossCompileToGlsl(vertex, "water.vsh");
			crossCompileToGlsl(fragment, "water.fsh");
			MemoryUtil.memFree(vertex);
			MemoryUtil.memFree(fragment);
		}
	}

	private static ByteBuffer compile(String file, int kind, boolean zeroToOne) throws IOException {
		String source = Files.readString(SHADERS.resolve(file));
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
	private static void crossCompileToGlsl(ByteBuffer spirv, String name) {
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
			} finally {
				Spvc.spvc_context_destroy(context);
			}
		}
	}
}
