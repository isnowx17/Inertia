// Inertia GPU probe: checks that Inertia's own CUDA kernels run next to PhysX and can write into
// OpenGL buffers. Compiled to PTX by tools/compile-cuda.sh.
extern "C" __global__ void lerp4(const float4* prev, const float4* curr, float4* out, int n, float a) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i < n) {
        float4 p = prev[i];
        float4 c = curr[i];
        out[i] = make_float4(p.x + (c.x - p.x) * a, p.y + (c.y - p.y) * a, p.z + (c.z - p.z) * a, 1.0f);
    }
}
