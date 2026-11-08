// Inertia soft bodies: finite elements (linear tetrahedra) solved with XPBD on the GPU.
//
// Each body is a tetrahedral mesh. Every substep the particles are predicted under gravity and the
// pressure of the entities resting on them, then every tetrahedron applies the two constraints of
// the stable Neo-Hookean material (Macklin and Muller 2021): a deviatoric one (C = |F|, compliance
// 1 / (mu V)) and a hydrostatic one (C = det F - (1 + mu / lambda), compliance 1 / (lambda V)),
// Gauss-Seidel inside the tetrahedron and Jacobi between tetrahedra (the corrections are averaged per
// particle). Particles of different bodies keep apart, and all particles are pushed out of solid
// blocks (a voxel grid around the bodies) and of the entities' boxes, with friction.
//
// Positions are relative to an origin; units are blocks, seconds, and mass per block of slime.

struct Sim {
    float4* x;          // position (xyz), inverse mass (w)
    float4* v;          // velocity
    float4* prev;       // position at the start of the substep
    float* dx;          // accumulated corrections, 3 per particle
    int* cnt;           // how many corrections a particle got
    const int* body;    // body of each particle
    const int4* tets;   // particle indices per tetrahedron
    const float* invDm; // inverse rest shape matrix per tetrahedron (row-major 3x3)
    const float* vol;   // rest volume per tetrahedron
    const unsigned char* solid; // voxel grid around the bodies: 1 where a block is solid
    const float* boxes; // entity boxes: min xyz, max xyz
    const float* press; // pressing feet: min x, min z, max x, max z, low y, high y, acceleration, -
    int* head;          // spatial hash: first particle per cell
    int* next;          // spatial hash: next particle in the same cell
    int n;              // particles
    int m;              // tetrahedra
    int gridX, gridY, gridZ; // voxel grid origin (block coordinates, relative)
    int sizeX, sizeY, sizeZ; // voxel grid size
    int boxCount;
    int pressCount;
    int hashMask;
    float h;            // substep length, seconds
    float gravity;      // blocks per second squared (negative: down)
    float mu;           // shear modulus
    float lambda;       // first Lame parameter
    float damping;      // per second
    float friction;     // with blocks and boxes
    float radius;       // contact radius between bodies
    float hashCell;     // spatial hash cell size
    float stick;        // stickiness: how far (blocks) touching surfaces still pull together
};

__device__ __forceinline__ float3 f3(float4 a) { return make_float3(a.x, a.y, a.z); }
__device__ __forceinline__ float3 operator+(float3 a, float3 b) { return make_float3(a.x + b.x, a.y + b.y, a.z + b.z); }
__device__ __forceinline__ float3 operator-(float3 a, float3 b) { return make_float3(a.x - b.x, a.y - b.y, a.z - b.z); }
__device__ __forceinline__ float3 operator*(float3 a, float s) { return make_float3(a.x * s, a.y * s, a.z * s); }
__device__ __forceinline__ float dot3(float3 a, float3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
__device__ __forceinline__ float3 cross3(float3 a, float3 b) {
    return make_float3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
}

__device__ __forceinline__ void addDelta(const Sim* s, int i, float3 d) {
    atomicAdd(&s->dx[i * 3], d.x);
    atomicAdd(&s->dx[i * 3 + 1], d.y);
    atomicAdd(&s->dx[i * 3 + 2], d.z);
    atomicAdd(&s->cnt[i], 1);
}

/** Start of a substep: gravity, pressure from the entities above, damping; positions predicted. */
extern "C" __global__ void predict(const Sim* s) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= s->n) return;
    float4 x = s->x[i];
    float4 v = s->v[i];
    s->prev[i] = x;
    s->dx[i * 3] = 0.0f;
    s->dx[i * 3 + 1] = 0.0f;
    s->dx[i * 3 + 2] = 0.0f;
    s->cnt[i] = 0;
    if (x.w <= 0.0f) return;
    float vy = v.y + s->gravity * s->h;
    for (int k = 0; k < s->pressCount; k++) {
        const float* p = s->press + k * 8;
        if (x.x >= p[0] && x.x <= p[2] && x.z >= p[1] && x.z <= p[3] && x.y >= p[4] && x.y <= p[5]) {
            vy -= p[6] * s->h;
        }
    }
    float keep = __expf(-s->damping * s->h);
    v = make_float4(v.x * keep, vy * keep, v.z * keep, 0.0f);
    s->v[i] = v;
    s->x[i] = make_float4(x.x + v.x * s->h, x.y + v.y * s->h, x.z + v.z * s->h, x.w);
}

/** One tetrahedron: the deviatoric, then the hydrostatic constraint; corrections accumulated. */
extern "C" __global__ void tets(const Sim* s) {
    int t = blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= s->m) return;
    int4 id = s->tets[t];
    int ids[4] = {id.x, id.y, id.z, id.w};
    float3 p[4];
    float w[4];
    for (int k = 0; k < 4; k++) {
        float4 x = s->x[ids[k]];
        p[k] = f3(x);
        w[k] = x.w;
    }
    float3 p0[4] = {p[0], p[1], p[2], p[3]};
    const float* B = s->invDm + t * 9; // B = Dm^-1, row-major
    float V = s->vol[t];
    float h2 = s->h * s->h;
    for (int pass = 0; pass < 2; pass++) {
        // F = Ds B, columns of Ds: p1 - p0, p2 - p0, p3 - p0.
        float3 d1 = p[1] - p[0], d2 = p[2] - p[0], d3 = p[3] - p[0];
        float3 c0 = d1 * B[0] + d2 * B[3] + d3 * B[6];
        float3 c1 = d1 * B[1] + d2 * B[4] + d3 * B[7];
        float3 c2 = d1 * B[2] + d2 * B[5] + d3 * B[8];
        float C;
        float3 P0, P1, P2; // dC/dF, columns
        float alpha;
        if (pass == 0) {
            float r = sqrtf(dot3(c0, c0) + dot3(c1, c1) + dot3(c2, c2));
            if (r < 1e-6f) continue;
            C = r;
            P0 = c0 * (1.0f / r);
            P1 = c1 * (1.0f / r);
            P2 = c2 * (1.0f / r);
            alpha = 1.0f / (s->mu * V);
        } else {
            C = dot3(c0, cross3(c1, c2)) - (1.0f + s->mu / s->lambda);
            P0 = cross3(c1, c2);
            P1 = cross3(c2, c0);
            P2 = cross3(c0, c1);
            alpha = 1.0f / (s->lambda * V);
        }
        // Gradients for p1..p3: (dC/dF) B^T, columns; p0 gets minus their sum.
        float3 g1 = P0 * B[0] + P1 * B[1] + P2 * B[2];
        float3 g2 = P0 * B[3] + P1 * B[4] + P2 * B[5];
        float3 g3 = P0 * B[6] + P1 * B[7] + P2 * B[8];
        float3 g0 = make_float3(0.0f, 0.0f, 0.0f) - g1 - g2 - g3;
        float denom = w[0] * dot3(g0, g0) + w[1] * dot3(g1, g1) + w[2] * dot3(g2, g2) + w[3] * dot3(g3, g3) + alpha / h2;
        if (denom < 1e-12f) continue;
        float dl = -C / denom;
        p[0] = p[0] + g0 * (w[0] * dl);
        p[1] = p[1] + g1 * (w[1] * dl);
        p[2] = p[2] + g2 * (w[2] * dl);
        p[3] = p[3] + g3 * (w[3] * dl);
    }
    for (int k = 0; k < 4; k++) {
        if (w[k] > 0.0f) addDelta(s, ids[k], p[k] - p0[k]);
    }
}

__device__ __forceinline__ int hashCellOf(const Sim* s, float3 p, int dx, int dy, int dz) {
    int cx = (int) floorf(p.x / s->hashCell) + dx;
    int cy = (int) floorf(p.y / s->hashCell) + dy;
    int cz = (int) floorf(p.z / s->hashCell) + dz;
    unsigned int k = (unsigned int) (cx * 92837111) ^ (unsigned int) (cy * 689287499) ^ (unsigned int) (cz * 283923481);
    return (int) (k & (unsigned int) s->hashMask);
}

extern "C" __global__ void hashClear(const Sim* s) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i <= s->hashMask) s->head[i] = -1;
}

extern "C" __global__ void hashInsert(const Sim* s) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= s->n) return;
    int c = hashCellOf(s, f3(s->x[i]), 0, 0, 0);
    s->next[i] = atomicExch(&s->head[c], i);
}

/** Particles of different bodies keep apart (each moves half of the overlap). */
extern "C" __global__ void contacts(const Sim* s) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= s->n) return;
    float4 xi = s->x[i];
    if (xi.w <= 0.0f) return;
    float3 pi = f3(xi);
    int bi = s->body[i];
    float reach = 2.0f * s->radius + s->stick;
    float d2min = reach * reach;
    for (int dz = -1; dz <= 1; dz++)
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int c = hashCellOf(s, pi, dx, dy, dz);
                for (int j = s->head[c]; j >= 0; j = s->next[j]) {
                    int bj = s->body[j];
                    if (bj == bi) continue;
                    float3 pj = f3(s->x[j]);
                    float3 d = pi - pj;
                    float d2 = dot3(d, d);
                    if (d2 >= d2min) continue;
                    float dist = sqrtf(d2);
                    float3 n;
                    if (dist < 1e-5f) {
                        // On the same spot (bodies placed face to face): the later body goes on top.
                        n = make_float3(0.0f, bi > bj ? 1.0f : -1.0f, 0.0f);
                        dist = 0.0f;
                    } else {
                        n = d * (1.0f / dist);
                    }
                    // Overlapping: apart; just apart: a little back together (sticky slime).
                    float push = dist < 2.0f * s->radius ? (2.0f * s->radius - dist) * 0.5f : -(dist - 2.0f * s->radius) * 0.25f;
                    // Friction: the sliding of the two particles against each other this substep is taken back.
                    float3 rel = (pi - f3(s->prev[i])) - (pj - f3(s->prev[j]));
                    float3 slide = rel - n * dot3(rel, n);
                    float len = sqrtf(dot3(slide, slide));
                    float stop = len > 1e-9f ? fminf(1.0f, s->friction * fabsf(push) / len) * 0.5f : 0.0f;
                    addDelta(s, i, n * push - slide * stop);
                }
            }
}

__device__ __forceinline__ bool solidAt(const Sim* s, int bx, int by, int bz) {
    int x = bx - s->gridX, y = by - s->gridY, z = bz - s->gridZ;
    if (x < 0 || y < 0 || z < 0 || x >= s->sizeX || y >= s->sizeY || z >= s->sizeZ) return false;
    return s->solid[(y * s->sizeZ + z) * s->sizeX + x] != 0;
}

/** Friction: the part of this substep's motion along the surface is (partly) taken back. */
__device__ __forceinline__ float3 rub(const Sim* s, float3 p, float3 start, float3 n, float depth) {
    float3 moved = p - start;
    float3 along = moved - n * dot3(moved, n);
    float len = sqrtf(dot3(along, along));
    if (len < 1e-9f) return p;
    float stop = fminf(1.0f, s->friction * depth / len);
    return p - along * stop;
}

/** Applies the averaged corrections, then keeps the particles out of blocks and entity boxes. */
extern "C" __global__ void apply(const Sim* s) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= s->n) return;
    float4 x = s->x[i];
    if (x.w <= 0.0f) return;
    float3 p = f3(x);
    int c = s->cnt[i];
    if (c > 0) {
        float inv = 1.0f / (float) c;
        p = p + make_float3(s->dx[i * 3], s->dx[i * 3 + 1], s->dx[i * 3 + 2]) * inv;
    }
    float3 start = f3(s->prev[i]);
    // Blocks: out through the nearest face that is not covered by another solid block.
    for (int round = 0; round < 2; round++) {
        int bx = (int) floorf(p.x), by = (int) floorf(p.y), bz = (int) floorf(p.z);
        if (!solidAt(s, bx, by, bz)) break;
        float fx = p.x - bx, fy = p.y - by, fz = p.z - bz;
        float best = 1e9f;
        float3 n = make_float3(0.0f, 1.0f, 0.0f);
        float depth = 0.0f;
        if (!solidAt(s, bx, by + 1, bz) && 1.0f - fy < best) { best = 1.0f - fy; n = make_float3(0, 1, 0); }
        if (!solidAt(s, bx, by - 1, bz) && fy < best) { best = fy; n = make_float3(0, -1, 0); }
        if (!solidAt(s, bx + 1, by, bz) && 1.0f - fx < best) { best = 1.0f - fx; n = make_float3(1, 0, 0); }
        if (!solidAt(s, bx - 1, by, bz) && fx < best) { best = fx; n = make_float3(-1, 0, 0); }
        if (!solidAt(s, bx, by, bz + 1) && 1.0f - fz < best) { best = 1.0f - fz; n = make_float3(0, 0, 1); }
        if (!solidAt(s, bx, by, bz - 1) && fz < best) { best = fz; n = make_float3(0, 0, -1); }
        if (best > 1e8f) break; // buried: nowhere to go
        depth = best + 1e-4f;
        p = p + n * depth;
        p = rub(s, p, start, n, depth);
    }
    // Entity boxes: out through the nearest face.
    for (int k = 0; k < s->boxCount; k++) {
        const float* b = s->boxes + k * 6;
        if (p.x <= b[0] || p.y <= b[1] || p.z <= b[2] || p.x >= b[3] || p.y >= b[4] || p.z >= b[5]) continue;
        float d[6] = {p.x - b[0], p.y - b[1], p.z - b[2], b[3] - p.x, b[4] - p.y, b[5] - p.z};
        int best = 0;
        for (int a = 1; a < 6; a++) if (d[a] < d[best]) best = a;
        float3 n = make_float3(best == 0 ? -1.0f : best == 3 ? 1.0f : 0.0f, best == 1 ? -1.0f : best == 4 ? 1.0f : 0.0f,
            best == 2 ? -1.0f : best == 5 ? 1.0f : 0.0f);
        p = p + n * (d[best] + 1e-4f);
        p = rub(s, p, start, n, d[best]);
    }
    s->x[i] = make_float4(p.x, p.y, p.z, x.w);
}

/** End of a substep: the velocity is the motion over it. */
extern "C" __global__ void velocities(const Sim* s) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= s->n) return;
    float4 x = s->x[i];
    if (x.w <= 0.0f) return;
    float4 a = s->prev[i];
    float inv = 1.0f / s->h;
    s->v[i] = make_float4((x.x - a.x) * inv, (x.y - a.y) * inv, (x.z - a.z) * inv, 0.0f);
}
