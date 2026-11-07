#version 330
#extension GL_ARB_separate_shader_objects : require

// Inertia water surface: depth pre-pass, for when all faces are drawn (the camera in the water). Only
// the depth of the nearest water surface is written (colour writes are masked off), so the expensive
// water shader afterwards runs once per pixel instead of once for every layer of water behind it.
// See WaterPass.

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(0.0);
}
