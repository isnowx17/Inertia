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
    float denominator = ndcZ + a;
    float distance = abs(denominator) < 1e-6 ? 1e6 : b / denominator;
    return distance > 0.0 ? distance : 1e6;
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
        slope += dir * cos(phase) * (0.06 / k) * k;
    }
    // ...plus drifting noise so the pattern never visibly repeats.
    vec3 n1 = noised(p * 2.3 + vec2(t * 0.31, t * 0.17));
    vec3 n2 = noised(p * 5.1 - vec2(t * 0.23, -t * 0.29));
    slope += n1.yz * 0.08 + n2.yz * 0.035;
    return slope;
}

// ---------------------------------------------------------------------------------------------
// Caustics: bright, moving light lines on the ground under the water. Two layers of warped value
// noise folded into thin ridges.
// ---------------------------------------------------------------------------------------------
float causticLayer(vec2 p, float t) {
    vec3 warp = noised(p + vec2(t * 0.15, -t * 0.11));
    vec2 q = p + warp.yz * 0.55;
    float r = noised(q * 1.7 - vec2(t * 0.21, t * 0.13)).x;
    return pow(1.0 - abs(r * 2.0 - 1.0), 7.0);
}

float caustics(vec2 p, float t) {
    return causticLayer(p * 1.25, t) * 0.65 + causticLayer(p * 2.3 + vec2(3.7, 1.9), t * 1.31) * 0.45;
}

// ---------------------------------------------------------------------------------------------
// Screen-space reflections: march the reflected ray through the copied depth buffer and, where it
// hits something, reflect the colour of the scene there (walls, terrain, entities).
// ---------------------------------------------------------------------------------------------
vec2 projectToUv(vec3 viewPosition, bool flipY) {
    vec4 clip = ProjMat * vec4(viewPosition, 1.0);
    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
    return flipY ? vec2(uv.x, 1.0 - uv.y) : uv;
}

bool insideScreen(vec2 uv) {
    return uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0;
}

// Returns the reflected scene colour (rgb) and how much it can be trusted (a).
vec4 traceReflection(vec3 origin, vec3 direction, bool flipY) {
    const float MAX_DISTANCE = 48.0;
    float stepLength = 0.2;
    float travelled = 0.0;
    vec3 previous = origin;
    for (int i = 0; i < 40; i++) {
        vec3 current = previous + direction * stepLength;
        travelled += stepLength;
        if (current.z > -0.05 || travelled > MAX_DISTANCE) {
            break; // behind the camera or too far
        }
        vec2 uv = projectToUv(current, flipY);
        if (!insideScreen(uv)) {
            break;
        }
        float behind = -current.z - viewDepth(texture(SceneDepth, uv).r);
        if (behind > 0.0) {
            if (behind > stepLength * 3.0 + 0.5) {
                break; // passed behind a foreground object: no reliable hit
            }
            // Refine between the last point in front and the first point behind the surface.
            vec3 front = previous;
            vec3 back = current;
            for (int j = 0; j < 5; j++) {
                vec3 middle = (front + back) * 0.5;
                vec2 middleUv = projectToUv(middle, flipY);
                if (-middle.z - viewDepth(texture(SceneDepth, middleUv).r) > 0.0) {
                    back = middle;
                } else {
                    front = middle;
                }
            }
            vec2 hitUv = projectToUv(back, flipY);
            vec2 edge = smoothstep(vec2(0.0), vec2(0.1), hitUv) * smoothstep(vec2(0.0), vec2(0.1), 1.0 - hitUv);
            float confidence = edge.x * edge.y * (1.0 - travelled / MAX_DISTANCE);
            return vec4(texture(SceneColor, hitUv).rgb, confidence);
        }
        previous = current;
        stepLength *= 1.15;
    }
    return vec4(0.0);
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

    // Refraction: offset the lookup by the ripple slope, but never sample things in front of the water.
    vec2 offset = slope * refractionStrength / max(waterDepth, 1.0) * 4.0;
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
    // Red is absorbed first, then green; blue travels furthest: shallow water is clear, deeper water
    // turns turquoise and then deep blue.
    vec3 extinction = vec3(0.42, 0.085, 0.065) * absorption;
    vec3 transmittance = exp(-extinction * distanceInWater);
    vec3 waterTint = vertexColor.rgb;
    vec3 turquoise = vec3(0.05, 0.32, 0.38);
    vec3 scattered = mix(deepColor * 1.6, turquoise, 0.45) * diffuseLight * lightMapColor.rgb;

    // Caustics on the ground below the water. The point under the water lies on the same view ray,
    // deeper by the ratio of the depths.
    float causticStrength = TextureMat[3][1];
    if (causticStrength > 0.0 && distanceInWater > 0.02) {
        vec3 groundPosition = relativePosition * (sceneDepth / max(waterDepth, 1e-3)) + ModelOffset;
        float pattern = caustics(groundPosition.xz, time);
        float reach = smoothstep(0.03, 0.4, distanceInWater) * exp(-distanceInWater * 0.25);
        behind *= 1.0 + pattern * causticStrength * 1.6 * reach * daylight * lightMapColor.g;
    }
    vec3 body = behind * transmittance * mix(vec3(1.0), waterTint * 1.4, 0.2) + scattered * (1.0 - transmittance);

    // Reflection of the sky (the fog colour is the colour of the horizon) and the sun.
    float cosTheta = clamp(dot(rippled, toCamera), 0.0, 1.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - cosTheta, 5.0);
    vec3 reflected = reflect(-toCamera, rippled);
    // Horizon: fog colour; higher up: the actual sky colour (TextureMat column 2, when provided).
    vec3 zenith = TextureMat[2].w > 0.5 ? TextureMat[2].rgb : FogColor.rgb * vec3(0.75, 0.85, 1.1);
    vec3 skyColor = mix(FogColor.rgb, zenith, smoothstep(0.0, 0.6, reflected.y));
    skyColor *= lightMapColor.rgb;

    // Reflect the surroundings where the reflected ray hits something on screen.
    vec3 reflectionColor = skyColor;
    if (TextureMat[3][0] > 0.5 && fresnel > 0.03) {
        vec3 viewPosition = (ModelViewMat * vec4(relativePosition, 1.0)).xyz;
        vec3 viewNormal = normalize(mat3(ModelViewMat) * rippled);
        vec3 viewReflection = reflect(normalize(viewPosition), viewNormal);
        // Screen-space y may point either way depending on the graphics backend: compare the
        // projection of this very fragment with its real screen position.
        vec2 selfUv = projectToUv(viewPosition, false);
        bool flipY = abs(selfUv.y - screenUv.y) > abs((1.0 - selfUv.y) - screenUv.y);
        vec4 hit = traceReflection(viewPosition, viewReflection, flipY);
        reflectionColor = mix(skyColor, hit.rgb, hit.a);
    }
    float sunDot = max(dot(reflected, sunDirection), 0.0);
    float sun = pow(sunDot, 500.0) * 6.0 + pow(sunDot, 60.0) * 0.3;
    vec3 sunColor = vec3(1.0, 0.95, 0.85) * sun * daylight * TextureMat[3][2] * lightMapColor.g;

    vec3 color = mix(body, reflectionColor, fresnel) + sunColor;

    // Foam: a lacy web of fine filaments at two scales, broken up into bubbles, gathering in
    // patches. The amount of foam decides how much of the web shows; it never becomes a solid sheet.
    vec2 foamUv = worldPosition.xz;
    float web1 = causticLayer(foamUv * 2.2 + vec2(time * 0.05, -time * 0.04), time * 0.35);
    float web2 = causticLayer(foamUv * 5.0 + vec2(-time * 0.08, time * 0.07), time * 0.5);
    float bubbles = noised(foamUv * 16.0 + vec2(time * 0.3, -time * 0.2)).x;
    float patches = noised(foamUv * 0.7 + vec2(time * 0.03, 0.0)).x;
    float pattern = pow(max(web1, web2 * 0.7), 0.6) * (0.7 + 0.3 * bubbles);
    float churn = clamp(foam, 0.0, 1.0);
    float cover = clamp(churn * (0.5 + patches), 0.0, 1.0);
    float foamThreshold = 0.78 - cover * 0.5;
    float foamAmount = smoothstep(foamThreshold, foamThreshold + 0.3, pattern) * smoothstep(0.03, 0.3, churn);
    color = mix(color, vec3(0.95, 0.97, 1.0) * diffuseLight * lightMapColor.rgb, foamAmount * 0.7);

    // Soft shoreline: very thin water fades into the ground instead of ending in a hard edge.
    float edge = smoothstep(0.0, TextureMat[1].w, distanceInWater + fresnel * 0.2 + foamAmount);
    color *= texture(Sampler0, vec2(0.5)).rgb;

    vec4 result = vec4(color, edge * vertexColor.a);
    fragColor = apply_fog(result, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
