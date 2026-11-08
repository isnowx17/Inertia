#version 330
#extension GL_ARB_separate_shader_objects : require

// Inertia lava surface: vertex stage (the water's, with the flow velocity passed on for the streaks).
//
// The surface stays on the GPU between updates: every vertex holds its position when the
// simulation data was taken (relative to the water's origin) and its flow velocity, and is moved
// along the velocity here for the time since then. Per-frame parameters travel in the
// DynamicTransforms block that Inertia writes itself (see WaterPass):
//   ColorModulator = sun direction (xyz) and daylight (w)
//   ModelOffset    = the water's origin relative to the camera
//   TextureMat     = column 0: time, ripple strength, refraction strength, absorption
//                    column 1: deep water colour (rgb), seconds since the data was taken (w)
//                    column 2: sky colour (rgb), 1 if the sky colour is valid (w)
//                    column 3: reflections on (x), caustics strength (y), sun strength (z),
//                              foam on (w)
// The vertex inputs follow io.github.isnowx17.inertia.util.WaterVertexLayout, in this order.

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Velocity;
layout(location = 2) in vec2 UV0;
layout(location = 3) in vec2 Info;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec3 localPosition;
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec3 relativePosition;
layout(location = 5) out vec3 surfaceNormal;
layout(location = 6) out vec2 foamUv;
layout(location = 7) out float diffuseLight;
layout(location = 8) out vec2 surfaceInfo;
layout(location = 9) out vec3 flowVelocity;

// From inside the water the surface is drawn twice: depth only, then shaded where its depth matches
// (see WaterPass), so both pipelines must compute exactly the same positions.
invariant gl_Position;

void main() {
    // Move along the flow for the time since the data was taken.
    vec3 local = Position + Velocity * TextureMat[1].w;
    vec3 relative = local + ModelOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(relative, 1.0);

    sphericalVertexDistance = fog_spherical_distance(relative);
    cylindricalVertexDistance = fog_cylindrical_distance(relative);

    lightMapColor = sample_lightmap(Sampler2, UV2);
    localPosition = local;
    relativePosition = relative;
    surfaceNormal = Normal;
    foamUv = UV0;
    // x: water thickness in blocks (where the depth copy cannot measure it); y: height of this
    // vertex in the foam map's height code.
    surfaceInfo = Info;
    flowVelocity = Velocity;

    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
    diffuseLight = min(1.0, (max(light.x, 0.0) + max(light.y, 0.0)) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
}
