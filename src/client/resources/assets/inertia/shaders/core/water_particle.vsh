#version 330
#extension GL_ARB_separate_shader_objects : require

// Inertia spray and droplets over the shader water: one instance per particle, expanded here into
// a small quad facing the camera. The particle data is uploaded once per simulation step; every
// frame only these parameters change, so drawing them costs the CPU nothing.
//
// The fragment stage is vanilla's entity shader (PER_FACE_LIGHTING, ALPHA_CUTOUT 0.1), and the
// outputs below are computed exactly like vanilla's entity.vsh computes them for the quads that
// FluidSurfaceRenderer used to build on the CPU every frame (corners, colour, normal, light).
//
// Per-draw parameters in DynamicTransforms (ColorModulator stays white for entity.fsh):
//   ModelOffset = the water's origin relative to the camera
// The instance's Life is the size of a droplet (0 to 1) and the remaining lifetime of spray.
//   TextureMat  = column 0: billboard right vector (droplets), seconds since the data was taken (w)
//                 column 1: billboard up vector (droplets), 0 for droplets / 1 for spray (w)
//                 column 2: quad normal (droplets), half size of a spray fleck (w)
//                 column 3: colour (rgb, and alpha for droplets), as vertex colour bytes / 255
// The instance inputs follow WaterPass.PARTICLE_FORMAT.

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Velocity;
layout(location = 2) in float Life;
layout(location = 3) in ivec2 UV2;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
layout(location = 4) out vec4 lightMapColor;
layout(location = 5) out vec4 overlayColor;
layout(location = 6) out vec2 texCoord0;

// FluidSurfaceRenderer.SPRAY_DEPTH_PULL: spray is moved this far towards the camera so spray right
// at the water surface stays visible.
const float SPRAY_DEPTH_PULL = 0.2;
// FluidSurfaceRenderer.SPRAY_FADE: spray fades out during its last this many seconds of life.
const float SPRAY_FADE = 0.8;
// The physics' gravity (FluidSimulation.GRAVITY), blocks per second squared.
const float SPRAY_GRAVITY = -9.81;
// OverlayTexture.NO_OVERLAY (u 0, v 10).
const ivec2 NO_OVERLAY = ivec2(0, 10);

// Normals reach the entity shader as signed bytes: BufferBuilder stores (byte) (int) (n * 127).
vec3 quantizeNormal(vec3 n) {
    return trunc(clamp(n, -1.0, 1.0) * 127.0) / 127.0;
}

void main() {
    // Quad corners in the order the CPU built them: (-, -), (+, -), (+, +), (-, +), with the
    // texture coordinates (0, 1), (1, 1), (1, 0), (0, 0).
    int corner = gl_VertexIndex & 3;
    float sx = (corner == 1 || corner == 2) ? 1.0 : -1.0;
    float sy = corner >= 2 ? 1.0 : -1.0;
    texCoord0 = vec2(sx > 0.0 ? 1.0 : 0.0, sy > 0.0 ? 0.0 : 1.0);

    float dt = TextureMat[0].w;
    vec3 position = (Position + Velocity * dt) + ModelOffset;
    if (TextureMat[1].w >= 0.5) {
        // Spray flies through the air: it falls along a curve between two physics steps, not a line.
        position.y += 0.5 * SPRAY_GRAVITY * dt * dt;
    }
    vec3 normal;
    vec4 color;
    if (TextureMat[1].w < 0.5) {
        // Droplet: a camera-facing square of the given radius, times its size.
        position = position + (TextureMat[0].xyz * sx + TextureMat[1].xyz * sy) * Life;
        normal = TextureMat[2].xyz;
        color = TextureMat[3];
    } else {
        // Spray fleck: a small round fleck facing the camera that fades out at the end of its life.
        float life = Life - dt;
        float dist = length(position);
        if (life <= 0.0 || dist < 1e-3) {
            // Gone (or right at the camera): no quad, like on the CPU.
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            sphericalVertexDistance = 0.0;
            cylindricalVertexDistance = 0.0;
            vertexPerFaceColorBack = vec4(0.0);
            vertexPerFaceColorFront = vec4(0.0);
            lightMapColor = vec4(0.0);
            overlayColor = vec4(0.0);
            return;
        }
        vec3 e = position / dist;
        // Pull towards the camera so spray right at the shader water's surface stays visible.
        position -= e * min(SPRAY_DEPTH_PULL, dist * 0.5);

        float halfSize = TextureMat[2].w;
        // a = (e.z, 0, -e.x), normalised; b = a x e.
        float ax = e.z;
        float az = -e.x;
        float alen = sqrt(ax * ax + az * az);
        if (alen < 1e-3) {
            ax = 1.0;
            az = 0.0;
            alen = 1.0;
        }
        ax = ax / alen * halfSize;
        az = az / alen * halfSize;
        vec3 b = vec3(-az * e.y, az * e.x - ax * e.z, ax * e.y);
        b = b / length(b) * halfSize;
        position = vec3(position.x + ax * sx + b.x * sy, position.y + b.y * sy, position.z + az * sx + b.z * sy);
        normal = -e;

        float fade = clamp(life / SPRAY_FADE, 0.0, 1.0);
        color = vec4(TextureMat[3].rgb, clamp(floor(fade * 230.0 + 0.5), 0.0, 255.0) / 255.0);
    }

    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);

    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, quantizeNormal(normal));
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, color);
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, NO_OVERLAY, 0);
}
