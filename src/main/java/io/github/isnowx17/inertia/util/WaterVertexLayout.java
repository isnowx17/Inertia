package io.github.isnowx17.inertia.util;

import java.util.List;

/**
 * Vertex layout of the water surface as it is kept on the GPU: one vertex per surface point,
 * shared by the quads around it, so a surface update uploads every vertex once.
 *
 * <p>The vertex shader ({@code shaders/core/water.vsh}) declares its inputs in exactly this order
 * (a test checks it); the renderer builds the matching vertex format from it.
 */
public final class WaterVertexLayout {
	/** One vertex attribute: name in the shader, GLSL type and size in bytes. */
	public record Attribute(String name, String glslType, int bytes) {
	}

	public static final List<Attribute> ATTRIBUTES = List.of(
		new Attribute("Position", "vec3", 12),  // float x, y, z relative to the water's origin
		new Attribute("Velocity", "vec3", 12),  // float flow velocity, blocks per second
		new Attribute("UV0", "vec2", 8),        // float position in the foam map
		new Attribute("Info", "vec2", 8),       // float water thickness, surface height code in the foam map
		new Attribute("UV2", "ivec2", 4),       // short block light, sky light (packed like vanilla)
		new Attribute("Normal", "vec3", 4)      // signed normalized byte x, y, z, padding
	);

	public static final int POSITION = 0;
	public static final int VELOCITY = 12;
	public static final int FOAM_UV = 24;
	public static final int INFO = 32;
	public static final int LIGHT = 40;
	public static final int NORMAL = 44;
	public static final int STRIDE = 48;

	/** Indices per surface quad: two triangles. */
	public static final int INDICES_PER_QUAD = 6;

	private WaterVertexLayout() {
	}
}
