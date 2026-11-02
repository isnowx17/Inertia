#version 330
#extension GL_ARB_separate_shader_objects : require

// Inertia water surface: vertex stage.
//
// Positions are camera-relative. Per-frame parameters travel in the DynamicTransforms block that
// Inertia writes itself (see WaterPass):
//   ColorModulator = sun direction (xyz) and daylight (w)
//   ModelOffset    = camera world position, wrapped, for world-anchored ripples
//   TextureMat     = column 0: time, ripple strength, refraction strength, absorption
//                    column 1: deep water colour (rgb), shoreline softness (w)
//                    column 2: sky colour (rgb), 1 if the sky colour is valid (w)
//                    column 3: reflections on (x), caustics strength (y), sun strength (z),
//                              foam on (w)
// Per-vertex: Color = biome water colour (rgb) and the vertex height in the foam map's height code
// (a), UV0 = position in the foam map, UV1.x = water thickness in 1/1024 blocks (used where the
// depth copy cannot measure it).

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec3 relativePosition;
layout(location = 5) out vec3 surfaceNormal;
layout(location = 6) out vec2 foamUv;
layout(location = 7) out float diffuseLight;
layout(location = 8) out vec2 surfaceInfo;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

    vertexColor = vec4(Color.rgb, 1.0);
    lightMapColor = sample_lightmap(Sampler2, UV2);
    relativePosition = Position;
    surfaceNormal = Normal;
    foamUv = UV0;
    // x: fallback water thickness in blocks; y: height of this vertex in the foam map's height code.
    surfaceInfo = vec2(float(UV1.x) / 1024.0, Color.a * 255.0);

    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
    diffuseLight = min(1.0, (max(light.x, 0.0) + max(light.y, 0.0)) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
}
