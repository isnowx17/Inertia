#version 330
#extension GL_ARB_separate_shader_objects : require

// Inertia water surface: fragment stage.
//
// The scene behind the water (colour and depth, copied right before the water is drawn) is
// sampled through a ripple-distorted offset (refraction), absorbed according to the distance the
// light travels through the water (depth-dependent colour and transparency), and mixed with a sky
// reflection by the Fresnel term. A sharp sun highlight and foam are added on top.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:globals.glsl>

uniform sampler2D Sampler0;
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec4 lightMapColor;
layout(location = 4) in vec3 relativePosition;
layout(location = 5) in vec3 surfaceNormal;
layout(location = 6) in vec2 surfaceParams;
layout(location = 7) in float diffuseLight;

layout(location = 0) out vec4 fragColor;

// Distance along the view axis for a depth-buffer value.
float viewDepth(float depth) {
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    float ndcZ = depth;
#else
    float ndcZ = depth * 2.0 - 1.0;
#endif
    // For any perspective projection: clip.z = A * z + B, clip.w = -z.
    float a = ProjMat[2][2];
    float b = ProjMat[3][2];
    return b / (ndcZ + a);
}

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// Value noise with analytic derivatives: returns (value, d/dx, d/dy).
vec3 noised(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    vec2 du = 6.0 * f * (1.0 - f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    float value = a + (b - a) * u.x + (c - a) * u.y + (a - b - c + d) * u.x * u.y;
    vec2 derivative = du * (vec2(b - a, c - a) + (a - b - c + d) * u.yx);
    return vec3(value, derivative);
}

// Slope of the ripple height field at a world position.
vec2 rippleSlope(vec2 p, float t) {
    vec2 slope = vec2(0.0);
    // A few travelling waves in different directions...
    const vec3 waves[4] = vec3[4](
        vec3(0.83, 0.55, 1.9),
        vec3(-0.49, 0.87, 2.7),
        vec3(0.21, -0.98, 3.9),
        vec3(-0.92, -0.39, 5.3)
    );
    for (int i = 0; i < 4; i++) {
        vec2 dir = waves[i].xy;
        float k = waves[i].z;
        float phase = dot(dir, p) * k + t * sqrt(9.81 * k) * 0.6;
        slope += dir * cos(phase) * 0.11;
    }
    // ...plus drifting noise so the pattern never visibly repeats.
    vec3 n1 = noised(p * 2.3 + vec2(t * 0.31, t * 0.17));
    vec3 n2 = noised(p * 5.1 - vec2(t * 0.23, -t * 0.29));
    slope += n1.yz * 0.16 + n2.yz * 0.07;
    return slope;
}

void main() {
    float time = TextureMat[0][0];
    float rippleStrength = TextureMat[0][1];
    float refractionStrength = TextureMat[0][2];
    float absorption = TextureMat[0][3];
    vec3 deepColor = TextureMat[1].rgb;
    vec3 sunDirection = normalize(ColorModulator.xyz);
    float daylight = ColorModulator.w;
    float foam = surfaceParams.x;

    vec3 normal = normalize(surfaceNormal);
    vec3 toCamera = normalize(-relativePosition);
    if (!gl_FrontFacing) {
        normal = -normal;
    }

    // Ripples, mostly on surfaces that face up.
    vec3 worldPosition = relativePosition + ModelOffset;
    float flatness = smoothstep(0.2, 0.9, abs(normal.y));
    float fade = 1.0 / (1.0 + sphericalVertexDistance * 0.04);
    vec2 slope = rippleSlope(worldPosition.xz, time) * rippleStrength * flatness * fade;
    vec3 rippled = normalize(normal + vec3(-slope.x, 0.0, -slope.y) * sign(normal.y + 1e-4));

    // Screen position of this fragment and the water's own depth.
    vec2 screenUv = gl_FragCoord.xy / ScreenSize;
    float waterDepth = viewDepth(gl_FragCoord.z);

    // Refraction: bend the lookup by the tilt of the surface (the simulated waves of the mesh plus the
    // ripples), but never sample things in front of the water.
    // The horizontal part of the normal is zero on still, flat water, so only waves bend the image.
    vec2 offset = rippled.xz * refractionStrength * 6.0 / max(waterDepth, 1.0);
    vec2 refractedUv = clamp(screenUv + offset, vec2(0.001), vec2(0.999));
    float sceneDepth = viewDepth(texture(SceneDepth, refractedUv).r);
    if (sceneDepth < waterDepth) {
        refractedUv = screenUv;
        sceneDepth = viewDepth(texture(SceneDepth, screenUv).r);
    }
    vec3 behind = texture(SceneColor, refractedUv).rgb;

    // Light travelling through the water is absorbed, red first: shallow water is clear, deep water blue.
    float distanceInWater = max(sceneDepth - waterDepth, 0.0);
    distanceInWater = max(distanceInWater, surfaceParams.y * 0.25);
    vec3 extinction = vec3(0.46, 0.11, 0.07) * absorption;
    vec3 transmittance = exp(-extinction * distanceInWater);
    vec3 waterTint = vertexColor.rgb;
    vec3 scattered = deepColor * 1.6 * diffuseLight * lightMapColor.rgb;
    vec3 body = behind * transmittance * mix(vec3(1.0), waterTint * 1.4, 0.2) + scattered * (1.0 - transmittance);

    // Reflection of the sky (the fog colour is the colour of the horizon) and the sun.
    float cosTheta = clamp(dot(rippled, toCamera), 0.0, 1.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - cosTheta, 5.0);
    vec3 reflected = reflect(-toCamera, rippled);
    // Horizon: fog colour; higher up: the actual sky colour (TextureMat column 2, when provided).
    vec3 zenith = TextureMat[2].w > 0.5 ? TextureMat[2].rgb : FogColor.rgb * vec3(0.75, 0.85, 1.1);
    vec3 skyColor = mix(FogColor.rgb, zenith, smoothstep(0.0, 0.6, reflected.y));
    skyColor *= lightMapColor.rgb;
    float sun = pow(max(dot(reflected, sunDirection), 0.0), 380.0) * 6.0 + pow(max(dot(reflected, sunDirection), 0.0), 40.0) * 0.25;
    vec3 sunColor = vec3(1.0, 0.95, 0.85) * sun * daylight * lightMapColor.g;

    // Sun-facing sides of the waves are brighter, the far sides darker: this is what makes the shape of
    // the water readable when looking down into it.
    float facing = dot(rippled, sunDirection) * 0.5 + 0.5;
    float waveLight = mix(0.78, 1.18, facing * daylight + (1.0 - daylight) * 0.5);
    body *= waveLight;

    vec3 color = mix(body, skyColor, max(fresnel, 0.06)) + sunColor;

    // Foam: churned water turns white, broken up by noise.
    float foamNoise = noised(worldPosition.xz * 6.0 + vec2(time * 0.5)).x;
    float foamAmount = clamp(foam * (0.6 + 0.8 * foamNoise), 0.0, 1.0);
    color = mix(color, vec3(0.92, 0.96, 1.0) * diffuseLight * lightMapColor.rgb, foamAmount * 0.7);

    // Soft shoreline: very thin water fades into the ground instead of ending in a hard edge.
    float edge = smoothstep(0.0, TextureMat[1].w, distanceInWater + fresnel * 0.2 + foamAmount);
    color *= texture(Sampler0, vec2(0.5)).rgb;

    vec4 result = vec4(color, edge * vertexColor.a);
    fragColor = apply_fog(result, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
