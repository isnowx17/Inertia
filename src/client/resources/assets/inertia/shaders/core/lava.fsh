#version 330
#extension GL_ARB_separate_shader_objects : require

// Inertia lava surface: fragment stage. Opaque and glowing (it gives its own light). The glow grows
// with how thick the lava is behind the surface, so thick ridges burn yellow-orange and thin films a
// darker red; grazing views glow more, fine slow wrinkles bend the surface a little and the sun leaves
// an orange sheen. The shape of the lava draws its streaks.
//   ColorModulator = sun direction (xyz) and daylight (w)
//   TextureMat     = column 0: time (x); column 1: seconds since the data was taken (w)

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:globals.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec3 localPosition;
layout(location = 3) in vec4 lightMapColor;
layout(location = 4) in vec3 relativePosition;
layout(location = 5) in vec3 surfaceNormal;
layout(location = 6) in vec2 foamUv;
layout(location = 7) in float diffuseLight;
layout(location = 8) in vec2 surfaceInfo;
layout(location = 9) in vec3 flowVelocity;

layout(location = 0) out vec4 fragColor;

const float PI = 3.14159265;
// Glow colour (scattered light per block of lava) and how quickly it saturates with thickness.
const vec3 GLOW = vec3(1.2, 0.35, 0.05);
const float GLOW_DENSITY = 2.5 * 0.45;
const float RIM = 0.85;
const float DETAIL = 0.25;
const float ROUGHNESS = 0.35;
const float SPECULAR = 2.5;
// Least depth of lava counted behind the surface (blocks).
const float MIN_THICKNESS = 0.35;

// Slope of fine wrinkles: a few slow waves in different directions, each fading out once it is
// smaller than a few pixels.
vec2 wrinkles(vec2 p, float t, float footprint) {
    const vec3 waves[5] = vec3[5](vec3(0.8, 0.6, 9.0), vec3(-0.5, 0.86, 13.0), vec3(0.95, -0.3, 17.0),
        vec3(-0.2, -0.98, 23.0), vec3(0.6, 0.8, 31.0));
    const float weights[5] = float[5](0.36, 0.26, 0.18, 0.12, 0.08);
    vec2 g = vec2(0.0);
    vec2 q = p;
    for (int i = 0; i < 5; i++) {
        vec2 dir = normalize(waves[i].xy);
        float k = waves[i].z;
        float fade = smoothstep(1.5, 4.0, (6.2831853 / k) / max(footprint, 1e-5));
        if (fade <= 0.0) {
            break;
        }
        float phase = dot(q, dir) * k + t * (0.8 + 0.3 * float(i)) * 0.35;
        g += dir * (weights[i] * fade * cos(phase));
        q += dir.yx * (weights[i] / k * sin(phase) * 0.6);
    }
    return g;
}

void main() {
    float time = TextureMat[0][0];
    vec3 sun = normalize(ColorModulator.xyz);
    float daylight = ColorModulator.w;
    vec3 toCamera = normalize(-relativePosition);
    vec3 normal = normalize(surfaceNormal);
    if (dot(normal, toCamera) < 0.0) {
        normal = -normal;
    }
    // How much lava the view looks through: the depth behind the surface, more when seen from the
    // side. Never nothing: a vertex whose depth was missed would otherwise show as a black dot.
    float facing = max(abs(dot(normal, toCamera)), 0.35);
    float thickness = max(surfaceInfo.x, MIN_THICKNESS) / facing;
    vec3 p = mod(localPosition, 512.0);
    float footprint = max(length(fwidth(p)), 1e-4);
    vec2 slope = wrinkles(p.xz, time, footprint);
    vec3 g = vec3(slope.x, 0.0, slope.y) * DETAIL;
    normal = normalize(normal - (g - normal * dot(g, normal)));

    // Glow: thick lava burns brighter (and yellower, once the red is saturated); grazing views more.
    vec3 glow = GLOW * (1.4 + 0.6 * daylight);
    vec3 core = glow * (1.0 - exp(-GLOW_DENSITY * thickness));
    float fresnel = pow(1.0 - clamp(dot(normal, toCamera), 0.0, 1.0), 2.0);
    vec3 color = core + glow * fresnel * RIM;

    // The sun on the wet surface (GGX), tinted orange.
    float nl = dot(normal, sun);
    if (nl > 0.0 && daylight > 0.0) {
        vec3 h = normalize(sun + toCamera);
        float nh = max(dot(normal, h), 0.0);
        float nv = max(dot(normal, toCamera), 1e-3);
        float rough = clamp(ROUGHNESS + footprint * 0.25, 0.02, 0.7);
        float a2 = rough * rough * rough * rough;
        float den = nh * nh * (a2 - 1.0) + 1.0;
        float d = a2 / max(PI * den * den, 1e-9);
        float f = 0.02 + 0.98 * pow(1.0 - max(dot(toCamera, h), 0.0), 5.0);
        float k = rough * rough * 0.5;
        float vis = 0.5 / max(nl * (nv * (1.0 - k) + k) + nv * (nl * (1.0 - k) + k), 1e-5);
        color += vec3(1.0, 0.65, 0.25) * min(d * vis * f * nl * SPECULAR, 8.0) * daylight;
    }

    color *= texture(Sampler0, vec2(0.5)).rgb;
    fragColor = apply_fog(vec4(color, 1.0), sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
