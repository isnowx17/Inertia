// Inertia: the water surface, built on the GPU every frame (see GpuSurface.java).
//
// The same surface as FluidSurfaceMesher builds on the CPU: the particles (moved between the two
// latest physics steps) and the frozen water's boxes are spread over a sparse grid of 16^3 bricks
// with linear weights, the density is smoothed with the same kernel, and naive surface nets turn the
// iso-surface into one vertex per crossed cell and one quad per crossed edge. Bricks deep inside the
// water (the brick and its 26 neighbours full) get no storage and read as still water.
//
// Kernels run in this order every frame: insert, insertBoxes, assign, count, countBoxes, classify,
// clearStorage, splat, splatBoxes, normalize, blur x3, vertices, quads. Compiled to PTX with
// tools/compile-cuda.sh.

#define BRICK 16
#define BRICK_BITS 4
#define BRICK_MASK 15
#define BRICK_VOLUME 4096
#define EMPTY_KEY 0xFFFFFFFFFFFFFFFFull
#define MAX_THICKNESS_STEPS 24
#define OFFSET (1 << 20)

// Mirrors GpuSurface.FIELD_*: every pointer first, then the 32-bit values.
struct Field {
    unsigned long long* keys;   // hash table: brick keys
    int* slots;                 // hash table: brick index per key
    int* counters;              // 0 bricks, 1 storage, 2 vertices, 3 quads, 4 overflow
    unsigned long long* brickKeys;
    int* brickParticles;
    int* brickStorage;          // storage index, -1 deep, -2 no room
    int* storageBrick;          // brick of every storage
    float* raw;                 // per storage node: density
    float* vel;                 // per storage node: velocity (x, y, z)
    float* smooth;              // smoothed density
    float* tmp;
    int* cellVertex;            // per storage cell: vertex index or -1
    const float4* prev;         // particles one step before
    const float4* curr;         // particles of the newest step
    const float* boxes;         // frozen water: min x, y, z, max x, y, z
    unsigned char* vertices;    // output, GpuSurface vertex layout (48 bytes)
    unsigned int* indices;      // output, six per quad
    const unsigned long long* lightKeys; // light per block (packed like brick keys), from the CPU
    const int* lightValues;
    int* neighbors;             // per storage: the storage of the 27 bricks around it (-1 deep, -2 none)
    int hashMask;
    int maxBricks;
    int maxStorage;
    int count;                  // particles
    int boxCount;
    int maxVertices;
    int maxQuads;
    int lightMask;              // light table size - 1, or -1 without a table
    int blurRadius;
    float alpha;                // how far from prev to curr
    float invSpan;              // 1 / seconds between the two steps
    float cell;
    float invCell;
    float restDensity;
    float threshold;
    float fullCount;
    float perVolume;            // particles per cubic block of frozen water
    float foamTexels;           // foam map: texels per block, origin, size, base height (u < 0: no map)
    float foamOriginX;
    float foamOriginZ;
    float foamWidth;
    float foamHeight;
    float foamBase;
    float lightProbe;
    float foamAge;              // seconds since the foam map's surface; the foam moved with the water since
    float blurKernel[9];
};

__device__ __forceinline__ unsigned long long packKey(int bx, int by, int bz) {
    return ((unsigned long long) (bx + OFFSET) << 42) | ((unsigned long long) (by + OFFSET) << 21)
        | (unsigned long long) (bz + OFFSET);
}

__device__ __forceinline__ unsigned int hashKey(unsigned long long k) {
    k ^= k >> 33;
    k *= 0xff51afd7ed558ccdull;
    k ^= k >> 33;
    k *= 0xc4ceb9fe1a85ec53ull;
    k ^= k >> 33;
    return (unsigned int) k;
}

__device__ void insertKey(const Field* f, unsigned long long key) {
    unsigned int i = hashKey(key) & f->hashMask;
    for (int probe = 0; probe <= f->hashMask; probe++) {
        unsigned long long old = atomicCAS(&f->keys[i], EMPTY_KEY, key);
        if (old == EMPTY_KEY || old == key) {
            return;
        }
        i = (i + 1) & f->hashMask;
    }
    atomicOr(&f->counters[4], 1);
}

/** Brick index of a key, or -1. */
__device__ int findBrick(const Field* f, unsigned long long key) {
    unsigned int i = hashKey(key) & f->hashMask;
    for (int probe = 0; probe <= f->hashMask; probe++) {
        unsigned long long k = f->keys[i];
        if (k == key) {
            return f->slots[i];
        }
        if (k == EMPTY_KEY) {
            return -1;
        }
        i = (i + 1) & f->hashMask;
    }
    return -1;
}

/** Storage of the brick holding node (x, y, z): >= 0, -1 deep, -2 none. */
__device__ int storageAt(const Field* f, int x, int y, int z) {
    int b = findBrick(f, packKey(x >> BRICK_BITS, y >> BRICK_BITS, z >> BRICK_BITS));
    if (b < 0 || b >= f->maxBricks) {
        return -2;
    }
    int s = f->brickStorage[b];
    return s >= 0 ? s : (s == -1 ? -1 : -2);
}

/** A storage brick's place, for looking up nodes around it without the hash table. */
struct Near {
    int s;
    int bx;
    int by;
    int bz;
};

__device__ __forceinline__ Near nearOf(const Field* f, int s) {
    unsigned long long key = f->brickKeys[f->storageBrick[s]];
    Near n;
    n.s = s;
    n.bx = (int) ((key >> 42) & 0x1FFFFF) - OFFSET;
    n.by = (int) ((key >> 21) & 0x1FFFFF) - OFFSET;
    n.bz = (int) (key & 0x1FFFFF) - OFFSET;
    return n;
}

/** storageAt for a node in or next to a storage brick (from its neighbour table), else by hash. */
__device__ __forceinline__ int storageNear(const Field* f, const Near& n, int x, int y, int z) {
    int dx = (x >> BRICK_BITS) - n.bx;
    int dy = (y >> BRICK_BITS) - n.by;
    int dz = (z >> BRICK_BITS) - n.bz;
    if (dx >= -1 && dx <= 1 && dy >= -1 && dy <= 1 && dz >= -1 && dz <= 1) {
        return f->neighbors[n.s * 27 + (dz + 1) * 9 + (dy + 1) * 3 + (dx + 1)];
    }
    return storageAt(f, x, y, z);
}

__device__ __forceinline__ int localIndex(int x, int y, int z) {
    return (((z & BRICK_MASK) * BRICK + (y & BRICK_MASK)) * BRICK) + (x & BRICK_MASK);
}

/** A density array at node (x, y, z): still water in deep bricks, nothing outside the bricks. */
__device__ float densityAt(const Field* f, const float* a, int x, int y, int z) {
    int s = storageAt(f, x, y, z);
    if (s == -1) {
        return f->restDensity;
    }
    if (s < 0) {
        return 0.0f;
    }
    return a[(size_t) s * BRICK_VOLUME + localIndex(x, y, z)];
}

/** densityAt for a node near a storage brick. */
__device__ __forceinline__ float densityNear(const Field* f, const float* a, const Near& n, int x, int y, int z) {
    int s = storageNear(f, n, x, y, z);
    if (s == -1) {
        return f->restDensity;
    }
    if (s < 0) {
        return 0.0f;
    }
    return a[(size_t) s * BRICK_VOLUME + localIndex(x, y, z)];
}

__device__ __forceinline__ float3 particleAt(const Field* f, int i, float3* velocity) {
    float4 p = f->prev[i];
    float4 c = f->curr[i];
    float3 d = make_float3(c.x - p.x, c.y - p.y, c.z - p.z);
    *velocity = make_float3(d.x * f->invSpan, d.y * f->invSpan, d.z * f->invSpan);
    return make_float3(p.x + d.x * f->alpha, p.y + d.y * f->alpha, p.z + d.z * f->alpha);
}

__device__ __forceinline__ bool finite3(float3 p) {
    return isfinite(p.x) && isfinite(p.y) && isfinite(p.z);
}

// ----------------------------------------------------------------------------------------------
// Bricks
// ----------------------------------------------------------------------------------------------

/** How far (nodes) from a particle its smoothed density and the cells around it reach. */
__device__ __forceinline__ int reach(const Field* f) {
    return f->blurRadius + 2;
}

/** Bricks [lo, hi] along one axis that node n reaches into. */
__device__ __forceinline__ void reachRange(int n, int r, int* lo, int* hi) {
    *lo = (n - r) >> BRICK_BITS;
    *hi = (n + r) >> BRICK_BITS;
}

/**
 * The brick of every particle and the bricks next to it its smoothed density reaches into, so the
 * surface is never cut off at a brick's face (and closed where the density ends).
 */
extern "C" __global__ void insert(const Field* f) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= f->count) {
        return;
    }
    float3 v;
    float3 p = particleAt(f, i, &v);
    if (!finite3(p)) {
        return;
    }
    int r = reach(f);
    int x0, x1, y0, y1, z0, z1;
    reachRange((int) floorf(p.x * f->invCell), r, &x0, &x1);
    reachRange((int) floorf(p.y * f->invCell), r, &y0, &y1);
    reachRange((int) floorf(p.z * f->invCell), r, &z0, &z1);
    for (int bx = x0; bx <= x1; bx++) {
        for (int by = y0; by <= y1; by++) {
            for (int bz = z0; bz <= z1; bz++) {
                insertKey(f, packKey(bx, by, bz));
            }
        }
    }
}

/** Node range [n0, n1] of a box's extent [a, b] (blocks) that its density reaches. */
__device__ __forceinline__ void nodeRange(float a, float b, float invCell, int* n0, int* n1) {
    *n0 = (int) floorf(a * invCell - 1.0f) + 1;
    *n1 = (int) ceilf(b * invCell + 1.0f) - 1;
}

extern "C" __global__ void insertBoxes(const Field* f) {
    int b = blockIdx.x * blockDim.x + threadIdx.x;
    if (b >= f->boxCount) {
        return;
    }
    const float* box = f->boxes + b * 6;
    int x0, x1, y0, y1, z0, z1;
    nodeRange(box[0], box[3], f->invCell, &x0, &x1);
    nodeRange(box[1], box[4], f->invCell, &y0, &y1);
    nodeRange(box[2], box[5], f->invCell, &z0, &z1);
    int r = reach(f);
    for (int bx = (x0 - r) >> BRICK_BITS; bx <= (x1 + r) >> BRICK_BITS; bx++) {
        for (int by = (y0 - r) >> BRICK_BITS; by <= (y1 + r) >> BRICK_BITS; by++) {
            for (int bz = (z0 - r) >> BRICK_BITS; bz <= (z1 + r) >> BRICK_BITS; bz++) {
                insertKey(f, packKey(bx, by, bz));
            }
        }
    }
}

/** Numbers the bricks in the hash table. */
extern "C" __global__ void assign(const Field* f) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i > f->hashMask) {
        return;
    }
    unsigned long long key = f->keys[i];
    if (key == EMPTY_KEY) {
        return;
    }
    int b = atomicAdd(&f->counters[0], 1);
    f->slots[i] = b;
    if (b < f->maxBricks) {
        f->brickKeys[b] = key;
        f->brickParticles[b] = 0;
    } else {
        atomicOr(&f->counters[4], 2);
    }
}

/** Particles per brick (counting those reaching into it, like the CPU's bins). */
extern "C" __global__ void count(const Field* f) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= f->count) {
        return;
    }
    float3 v;
    float3 p = particleAt(f, i, &v);
    if (!finite3(p)) {
        return;
    }
    int ix = (int) floorf(p.x * f->invCell);
    int iy = (int) floorf(p.y * f->invCell);
    int iz = (int) floorf(p.z * f->invCell);
    int bx = ix >> BRICK_BITS;
    int by = iy >> BRICK_BITS;
    int bz = iz >> BRICK_BITS;
    int spill = ((ix & BRICK_MASK) == BRICK_MASK ? 1 : 0) | ((iy & BRICK_MASK) == BRICK_MASK ? 2 : 0)
        | ((iz & BRICK_MASK) == BRICK_MASK ? 4 : 0);
    for (int s = 0; s < 8; s++) {
        if ((s & ~spill) != 0) {
            continue;
        }
        int b = findBrick(f, packKey(bx + (s & 1), by + ((s >> 1) & 1), bz + ((s >> 2) & 1)));
        if (b >= 0 && b < f->maxBricks) {
            atomicAdd(&f->brickParticles[b], 1);
        }
    }
}

__device__ __forceinline__ float overlap(float a0, float a1, float b0, float b1) {
    return fmaxf(0.0f, fminf(a1, b1) - fmaxf(a0, b0));
}

/** The particles the frozen water stands for, per brick. */
extern "C" __global__ void countBoxes(const Field* f) {
    int b = blockIdx.x * blockDim.x + threadIdx.x;
    if (b >= f->boxCount) {
        return;
    }
    const float* box = f->boxes + b * 6;
    int x0, x1, y0, y1, z0, z1;
    nodeRange(box[0], box[3], f->invCell, &x0, &x1);
    nodeRange(box[1], box[4], f->invCell, &y0, &y1);
    nodeRange(box[2], box[5], f->invCell, &z0, &z1);
    float size = f->cell * BRICK;
    for (int bx = x0 >> BRICK_BITS; bx <= x1 >> BRICK_BITS; bx++) {
        float ox = overlap(box[0], box[3], bx * size, (bx + 1) * size);
        for (int by = y0 >> BRICK_BITS; by <= y1 >> BRICK_BITS; by++) {
            float oy = overlap(box[1], box[4], by * size, (by + 1) * size);
            for (int bz = z0 >> BRICK_BITS; bz <= z1 >> BRICK_BITS; bz++) {
                float oz = overlap(box[2], box[5], bz * size, (bz + 1) * size);
                int brick = findBrick(f, packKey(bx, by, bz));
                if (brick >= 0 && brick < f->maxBricks) {
                    atomicAdd(&f->brickParticles[brick], (int) rintf(ox * oy * oz * f->perVolume));
                }
            }
        }
    }
}

/** Deep bricks (the brick and all 26 neighbours full) get no storage; the others get theirs. */
extern "C" __global__ void classify(const Field* f) {
    int b = blockIdx.x * blockDim.x + threadIdx.x;
    int bricks = min(f->counters[0], f->maxBricks);
    if (b >= bricks) {
        return;
    }
    unsigned long long key = f->brickKeys[b];
    int bx = (int) ((key >> 42) & 0x1FFFFF) - OFFSET;
    int by = (int) ((key >> 21) & 0x1FFFFF) - OFFSET;
    int bz = (int) (key & 0x1FFFFF) - OFFSET;
    bool deep = true;
    for (int dz = -1; dz <= 1 && deep; dz++) {
        for (int dy = -1; dy <= 1 && deep; dy++) {
            for (int dx = -1; dx <= 1 && deep; dx++) {
                int n = findBrick(f, packKey(bx + dx, by + dy, bz + dz));
                if (n < 0 || n >= f->maxBricks || f->brickParticles[n] < f->fullCount) {
                    deep = false;
                }
            }
        }
    }
    if (deep) {
        f->brickStorage[b] = -1;
        return;
    }
    int s = atomicAdd(&f->counters[1], 1);
    if (s < f->maxStorage) {
        f->brickStorage[b] = s;
        f->storageBrick[s] = b;
    } else {
        f->brickStorage[b] = -2;
        atomicOr(&f->counters[4], 4);
    }
}

/** The neighbour table of every storage brick. */
extern "C" __global__ void neighbors(const Field* f) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    int storage = min(f->counters[1], f->maxStorage);
    if (i >= storage * 27) {
        return;
    }
    int s = i / 27;
    int k = i % 27;
    Near n = nearOf(f, s);
    int b = findBrick(f, packKey(n.bx + k % 3 - 1, n.by + (k / 3) % 3 - 1, n.bz + k / 9 - 1));
    int code = -2;
    if (b >= 0 && b < f->maxBricks) {
        int st = f->brickStorage[b];
        code = st >= 0 ? st : (st == -1 ? -1 : -2);
    }
    f->neighbors[i] = code;
}

/** Empties the storage in use (one thread per node). */
extern "C" __global__ void clearStorage(const Field* f) {
    long long i = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    long long used = (long long) min(f->counters[1], f->maxStorage) * BRICK_VOLUME;
    if (i >= used) {
        return;
    }
    f->raw[i] = 0.0f;
    f->vel[i * 3] = 0.0f;
    f->vel[i * 3 + 1] = 0.0f;
    f->vel[i * 3 + 2] = 0.0f;
    f->cellVertex[i] = -1;
}

// ----------------------------------------------------------------------------------------------
// Density
// ----------------------------------------------------------------------------------------------

__device__ __forceinline__ void deposit(const Field* f, int s, int x, int y, int z, float w, float3 v) {
    if (w <= 0.0f) {
        return;
    }
    if (s < 0) {
        return;
    }
    size_t n = (size_t) s * BRICK_VOLUME + localIndex(x, y, z);
    atomicAdd(&f->raw[n], w);
    atomicAdd(&f->vel[n * 3], w * v.x);
    atomicAdd(&f->vel[n * 3 + 1], w * v.y);
    atomicAdd(&f->vel[n * 3 + 2], w * v.z);
}

/** Every particle spread over the eight nodes around it with linear weights. */
extern "C" __global__ void splat(const Field* f) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= f->count) {
        return;
    }
    float3 v;
    float3 p = particleAt(f, i, &v);
    if (!finite3(p)) {
        return;
    }
    float gx = p.x * f->invCell;
    float gy = p.y * f->invCell;
    float gz = p.z * f->invCell;
    int x = (int) floorf(gx);
    int y = (int) floorf(gy);
    int z = (int) floorf(gz);
    float fx = gx - x;
    float fy = gy - y;
    float fz = gz - z;
    float wx[2] = {1.0f - fx, fx};
    float wy[2] = {1.0f - fy, fy};
    float wz[2] = {1.0f - fz, fz};
    int own = storageAt(f, x, y, z);
    Near n;
    if (own >= 0) {
        n = nearOf(f, own);
    }
    for (int c = 0; c < 8; c++) {
        int dx = c & 1;
        int dy = (c >> 1) & 1;
        int dz = (c >> 2) & 1;
        int s = own >= 0 ? storageNear(f, n, x + dx, y + dy, z + dz) : storageAt(f, x + dx, y + dy, z + dz);
        deposit(f, s, x + dx, y + dy, z + dz, wx[dx] * wy[dy] * wz[dz], v);
    }
}

/** Share of a node's tent (half-width one cell) that lies below u cells from the node. */
__device__ __forceinline__ float tentShare(float u) {
    if (u <= -1.0f) {
        return 0.0f;
    }
    if (u < 0.0f) {
        return 0.5f * (u + 1.0f) * (u + 1.0f);
    }
    if (u < 1.0f) {
        return 1.0f - 0.5f * (1.0f - u) * (1.0f - u);
    }
    return 1.0f;
}

/** The frozen water's density: evenly spread particles filling each box. */
extern "C" __global__ void splatBoxes(const Field* f) {
    int b = blockIdx.x;
    if (b >= f->boxCount) {
        return;
    }
    const float* box = f->boxes + b * 6;
    int x0, x1, y0, y1, z0, z1;
    nodeRange(box[0], box[3], f->invCell, &x0, &x1);
    nodeRange(box[1], box[4], f->invCell, &y0, &y1);
    nodeRange(box[2], box[5], f->invCell, &z0, &z1);
    int nx = x1 - x0 + 1;
    int ny = y1 - y0 + 1;
    int nz = z1 - z0 + 1;
    long long nodes = (long long) nx * ny * nz;
    float lo[3] = {box[0] * f->invCell, box[1] * f->invCell, box[2] * f->invCell};
    float hi[3] = {box[3] * f->invCell, box[4] * f->invCell, box[5] * f->invCell};
    for (long long k = threadIdx.x; k < nodes; k += blockDim.x) {
        int x = x0 + (int) (k % nx);
        int y = y0 + (int) ((k / nx) % ny);
        int z = z0 + (int) (k / ((long long) nx * ny));
        float sx = tentShare(hi[0] - x) - tentShare(lo[0] - x);
        float sy = tentShare(hi[1] - y) - tentShare(lo[1] - y);
        float sz = tentShare(hi[2] - z) - tentShare(lo[2] - z);
        float w = f->restDensity * sx * sy * sz;
        if (w <= 0.0f) {
            continue;
        }
        int s = storageAt(f, x, y, z);
        if (s >= 0) {
            atomicAdd(&f->raw[(size_t) s * BRICK_VOLUME + localIndex(x, y, z)], w);
        }
    }
}

/** Weighted velocity sums become velocities. */
extern "C" __global__ void normalize(const Field* f) {
    long long i = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    long long used = (long long) min(f->counters[1], f->maxStorage) * BRICK_VOLUME;
    if (i >= used) {
        return;
    }
    float d = f->raw[i];
    float inv = d > 1e-6f ? 1.0f / d : 0.0f;
    f->vel[i * 3] *= inv;
    f->vel[i * 3 + 1] *= inv;
    f->vel[i * 3 + 2] *= inv;
}

/** Node coordinates of node {@code local} of storage {@code s}. */
__device__ __forceinline__ void storageNode(const Field* f, int s, int local, int* x, int* y, int* z) {
    unsigned long long key = f->brickKeys[f->storageBrick[s]];
    *x = (((int) ((key >> 42) & 0x1FFFFF) - OFFSET) << BRICK_BITS) + (local & BRICK_MASK);
    *y = (((int) ((key >> 21) & 0x1FFFFF) - OFFSET) << BRICK_BITS) + ((local >> BRICK_BITS) & BRICK_MASK);
    *z = (((int) (key & 0x1FFFFF) - OFFSET) << BRICK_BITS) + (local >> (2 * BRICK_BITS));
}

/** One axis of the separable smoothing, from src to dst (axis 0 x, 1 y, 2 z). */
extern "C" __global__ void blur(const Field* f, const float* src, float* dst, int axis) {
    long long i = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    int storage = min(f->counters[1], f->maxStorage);
    if (i >= (long long) storage * BRICK_VOLUME) {
        return;
    }
    int s = (int) (i / BRICK_VOLUME);
    int x, y, z;
    storageNode(f, s, (int) (i % BRICK_VOLUME), &x, &y, &z);
    Near n = nearOf(f, s);
    int r = f->blurRadius;
    float sum = 0.0f;
    for (int t = -r; t <= r; t++) {
        int ax = x + (axis == 0 ? t : 0);
        int ay = y + (axis == 1 ? t : 0);
        int az = z + (axis == 2 ? t : 0);
        sum += f->blurKernel[t + r] * densityNear(f, src, n, ax, ay, az);
    }
    dst[i] = sum;
}

// ----------------------------------------------------------------------------------------------
// Surface nets
// ----------------------------------------------------------------------------------------------

__constant__ int CORNER[8][3] = {
    {0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {1, 1, 0}, {0, 0, 1}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1}
};
__constant__ int EDGE[12][2] = {
    {0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
};

/** Smoothed density at a node. */
__device__ __forceinline__ float field(const Field* f, int x, int y, int z) {
    return densityAt(f, f->smooth, x, y, z);
}

/** How deep the water is behind a surface point, along -normal (as FluidSurfaceMesher.thickness). */
__device__ float thickness(const Field* f, float x, float y, float z, float nx, float ny, float nz) {
    float step = f->cell * 2.0f;
    float px = x - nx * f->cell * 0.5f;
    float py = y - ny * f->cell * 0.5f;
    float pz = z - nz * f->cell * 0.5f;
    int steps = 0;
    while (steps < MAX_THICKNESS_STEPS) {
        int gx = (int) rintf(px * f->invCell);
        int gy = (int) rintf(py * f->invCell);
        int gz = (int) rintf(pz * f->invCell);
        if (field(f, gx, gy, gz) <= f->threshold) {
            break;
        }
        px -= nx * step;
        py -= ny * step;
        pz -= nz * step;
        steps++;
    }
    return steps * step;
}

__device__ __forceinline__ signed char normalByte(float v) {
    return (signed char) rintf(fminf(1.0f, fmaxf(-1.0f, v)) * 127.0f);
}

/** Light of the block a vertex takes it from (as FluidSurfaceRenderer.packSurface), from the CPU's table. */
__device__ int lightAt(const Field* f, float x, float y, float z, float nx, float ny, float nz) {
    if (f->lightMask < 0) {
        return 0x00F00000; // full sky light, no block light
    }
    int bx = (int) floorf(x + nx * f->lightProbe);
    int by = (int) floorf(y + fmaxf(ny, 0.0f) * f->lightProbe + 0.05f);
    int bz = (int) floorf(z + nz * f->lightProbe);
    unsigned long long key = packKey(bx, by, bz);
    unsigned int i = hashKey(key) & f->lightMask;
    for (int probe = 0; probe <= f->lightMask; probe++) {
        unsigned long long k = f->lightKeys[i];
        if (k == key) {
            return f->lightValues[i];
        }
        if (k == EMPTY_KEY) {
            break;
        }
        i = (i + 1) & f->lightMask;
    }
    // A block the CPU's surface had no vertex at: the one above (the surface moved a little).
    key = packKey(bx, by + 1, bz);
    i = hashKey(key) & f->lightMask;
    for (int probe = 0; probe <= f->lightMask; probe++) {
        unsigned long long k = f->lightKeys[i];
        if (k == key) {
            return f->lightValues[i];
        }
        if (k == EMPTY_KEY) {
            break;
        }
        i = (i + 1) & f->lightMask;
    }
    return 0x00F00000;
}

/** One vertex per cell the surface crosses (at the average of its edge crossings). */
extern "C" __global__ void vertices(const Field* f) {
    long long i = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    int storage = min(f->counters[1], f->maxStorage);
    if (i >= (long long) storage * BRICK_VOLUME) {
        return;
    }
    int s = (int) (i / BRICK_VOLUME);
    int x, y, z;
    storageNode(f, s, (int) (i % BRICK_VOLUME), &x, &y, &z);
    Near near = nearOf(f, s);
    float corner[8];
    int mask = 0;
    for (int c = 0; c < 8; c++) {
        corner[c] = densityNear(f, f->smooth, near, x + CORNER[c][0], y + CORNER[c][1], z + CORNER[c][2]);
        if (corner[c] > f->threshold) {
            mask |= 1 << c;
        }
    }
    if (mask == 0 || mask == 0xFF) {
        return;
    }
    float sx = 0.0f, sy = 0.0f, sz = 0.0f;
    int crossings = 0;
    for (int e = 0; e < 12; e++) {
        float a = corner[EDGE[e][0]];
        float b = corner[EDGE[e][1]];
        if ((a > f->threshold) == (b > f->threshold)) {
            continue;
        }
        float t = (f->threshold - a) / (b - a);
        const int* ca = CORNER[EDGE[e][0]];
        const int* cb = CORNER[EDGE[e][1]];
        sx += ca[0] + t * (cb[0] - ca[0]);
        sy += ca[1] + t * (cb[1] - ca[1]);
        sz += ca[2] + t * (cb[2] - ca[2]);
        crossings++;
    }
    sx /= crossings;
    sy /= crossings;
    sz /= crossings;
    float gx = (corner[1] - corner[0]) + (corner[3] - corner[2]) + (corner[5] - corner[4]) + (corner[7] - corner[6]);
    float gy = (corner[2] - corner[0]) + (corner[3] - corner[1]) + (corner[6] - corner[4]) + (corner[7] - corner[5]);
    float gz = (corner[4] - corner[0]) + (corner[5] - corner[1]) + (corner[6] - corner[2]) + (corner[7] - corner[3]);
    float len = sqrtf(gx * gx + gy * gy + gz * gz);
    float nx = 0.0f, ny = 1.0f, nz = 0.0f;
    if (len > 1e-6f) {
        nx = -gx / len;
        ny = -gy / len;
        nz = -gz / len;
    }
    // Flow velocity at the corners, weighted by the particles there.
    float wsum = 0.0f, vx = 0.0f, vy = 0.0f, vz = 0.0f;
    for (int c = 0; c < 8; c++) {
        int cx = x + CORNER[c][0], cy = y + CORNER[c][1], cz = z + CORNER[c][2];
        int cs = storageNear(f, near, cx, cy, cz);
        if (cs < 0) {
            continue;
        }
        size_t n = (size_t) cs * BRICK_VOLUME + localIndex(cx, cy, cz);
        float w = f->raw[n];
        wsum += w;
        vx += w * f->vel[n * 3];
        vy += w * f->vel[n * 3 + 1];
        vz += w * f->vel[n * 3 + 2];
    }
    if (wsum > 1e-6f) {
        vx /= wsum;
        vy /= wsum;
        vz /= wsum;
    }
    int index = atomicAdd(&f->counters[2], 1);
    if (index >= f->maxVertices) {
        atomicOr(&f->counters[4], 8);
        return;
    }
    f->cellVertex[i] = index;
    float px = (x + sx) * f->cell;
    float py = (y + sy) * f->cell;
    float pz = (z + sz) * f->cell;
    float* out = (float*) (f->vertices + (size_t) index * 48);
    out[0] = px;
    out[1] = py;
    out[2] = pz;
    out[3] = vx;
    out[4] = vy;
    out[5] = vz;
    if (f->foamTexels > 0.0f) {
        // Where this water was when the foam map was made: the foam flows with it in between.
        float fx = px - vx * f->foamAge;
        float fy = py - vy * f->foamAge;
        float fz = pz - vz * f->foamAge;
        out[6] = (fx * f->foamTexels - f->foamOriginX) / f->foamWidth;
        out[7] = (fz * f->foamTexels - f->foamOriginZ) / f->foamHeight;
        out[9] = fminf(255.0f, fmaxf(0.0f, rintf((fy - f->foamBase) * 16.0f)));
    } else {
        out[6] = -1.0f;
        out[7] = -1.0f;
        out[9] = 0.0f;
    }
    out[8] = thickness(f, px, py, pz, nx, ny, nz);
    ((int*) out)[10] = lightAt(f, px, py, pz, nx, ny, nz);
    char4 normal = make_char4(normalByte(nx), normalByte(ny), normalByte(nz), 0);
    *((char4*) (f->vertices + (size_t) index * 48 + 44)) = normal;
}

/** Vertex of the cell at node (x, y, z), or -1. */
__device__ int vertexAt(const Field* f, const Near& n, int x, int y, int z) {
    int s = storageNear(f, n, x, y, z);
    if (s < 0) {
        return -1;
    }
    return f->cellVertex[(size_t) s * BRICK_VOLUME + localIndex(x, y, z)];
}

__device__ void quad(const Field* f, bool flip, int v0, int v1, int v2, int v3) {
    if (v0 < 0 || v1 < 0 || v2 < 0 || v3 < 0) {
        return;
    }
    int q = atomicAdd(&f->counters[3], 1);
    if (q >= f->maxQuads) {
        atomicOr(&f->counters[4], 16);
        return;
    }
    if (flip) {
        int t = v1;
        v1 = v3;
        v3 = t;
    }
    unsigned int* out = f->indices + (size_t) q * 6;
    out[0] = v0;
    out[1] = v1;
    out[2] = v2;
    out[3] = v2;
    out[4] = v3;
    out[5] = v0;
}

/** One quad per grid edge the surface crosses, joining the vertices of the four cells around it. */
extern "C" __global__ void quads(const Field* f) {
    long long i = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    int storage = min(f->counters[1], f->maxStorage);
    if (i >= (long long) storage * BRICK_VOLUME) {
        return;
    }
    if (f->cellVertex[i] < 0) {
        return;
    }
    int s = (int) (i / BRICK_VOLUME);
    int x, y, z;
    storageNode(f, s, (int) (i % BRICK_VOLUME), &x, &y, &z);
    Near n = nearOf(f, s);
    const float* d = f->smooth;
    bool inside = densityNear(f, d, n, x, y, z) > f->threshold;
    bool flip = !inside;
    int v0 = f->cellVertex[i];
    if (inside != (densityNear(f, d, n, x + 1, y, z) > f->threshold)) {
        quad(f, flip, v0, vertexAt(f, n, x, y - 1, z), vertexAt(f, n, x, y - 1, z - 1), vertexAt(f, n, x, y, z - 1));
    }
    if (inside != (densityNear(f, d, n, x, y + 1, z) > f->threshold)) {
        quad(f, flip, v0, vertexAt(f, n, x, y, z - 1), vertexAt(f, n, x - 1, y, z - 1), vertexAt(f, n, x - 1, y, z));
    }
    if (inside != (densityNear(f, d, n, x, y, z + 1) > f->threshold)) {
        quad(f, flip, v0, vertexAt(f, n, x - 1, y, z), vertexAt(f, n, x - 1, y - 1, z), vertexAt(f, n, x, y - 1, z));
    }
}
