# Inertia

**Inertia is an advanced, professional physics engine for Minecraft: Java Edition.**

Version 0.1 (beta) adds real-time, GPU-simulated water powered by **NVIDIA PhysX 5**. Pour it
from a bucket and watch it splash, flow down stairs, pool in holes and push you around.

> **Requirements:** an NVIDIA GPU with a current driver (CUDA), Windows x64 or Linux x64,
> Minecraft 26.3 with Fabric Loader ≥ 0.20.1 and Fabric API. **Singleplayer only.**
> Without a CUDA-capable GPU the mod loads but stays inactive and tells you why.

## Download

Inertia ships one jar per operating system because the PhysX GPU library is large
(about 250 MB unpacked per platform):

| Your system | File |
|---|---|
| Windows x64 | `inertia-<version>+26.3-windows.jar` |
| Linux x64 | `inertia-<version>+26.3-linux.jar` |

Put the jar into your `mods` folder together with Fabric API. On first launch the PhysX native
libraries are unpacked to `.minecraft/inertia/natives/` (this takes a few seconds, in the
background).

## Usage

- Take the **PhysX Water Bucket** from the *Tools & Utilities* creative tab.
- **Right-click** releases one block of water where you are looking. Hold right-click to keep
  pouring. The bucket never runs empty.
- `/inertia info` shows the GPU and simulation status, `/inertia clear` removes all water.

Water simulated by Inertia is separate from vanilla water: oceans, lakes and the normal water
bucket are unchanged. The water is not saved: it disappears when you leave the world.

## Settings

`config/inertia.json` (created on first launch):

| Setting | Default | Meaning |
|---|---|---|
| `maxParticles` | 65536 | Water particles alive at once (4096 – 1048576). |
| `particleSpacing` | 0.125 | Distance between particles in blocks. 0.125 = 512 particles per block. |
| `despawnDistance` | 96 | Water further away from you than this is removed. |
| `substepsPerTick` | 3 | Simulation steps per game tick; higher is more stable but slower. |
| `playerInteraction` | true | Whether water pushes, slows and lifts the player. |
| `renderScale` | 1.6 | Size of rendered particles relative to the spacing. |
| `renderOpacity` | 0.6 | Opacity of the rendered water. |

With the defaults about 128 blocks of water can exist at the same time.

## Building

Requires JDK 25.

```bash
./gradlew build -Pplatform=windows
```

```bash
./gradlew build -Pplatform=linux
```

The jars are written to `build/libs/`. Without `-Pplatform` the current operating system is
used. The CUDA build of physx-jni is downloaded from its GitHub release and its SHA-256 checksum
is verified during the build.

## How it works

- **PhysX PBD fluid** on the GPU via [physx-jni](https://github.com/fabmax/physx-jni) 2.5.1
  (PhysX 5.5.1), the last physx-jni release with CUDA support.
- **Block collision:** every 16×16×16 chunk section near the water becomes a static PhysX actor.
  Full blocks are greedily merged into large boxes, partial blocks use their collision boxes.
- **Player:** a kinematic box keeps the water out of the player; the force of the water on the
  player is computed from the particles around them.
- **Rendering:** particles are drawn as shaded, camera-facing sprites, interpolated between
  simulation ticks.

See [PLAN.md](PLAN.md) for scope and versioning, and [CHANGELOG.md](CHANGELOG.md) for changes.

## Türkçe

Inertia, Minecraft: Java Edition için GPU üzerinde çalışan bir fizik motorudur. 0.1 sürümü
NVIDIA PhysX ile simüle edilen su ekler. NVIDIA ekran kartı (CUDA) gerekir, yalnızca tek
oyunculu çalışır. *Araçlar* sekmesindeki **PhysX Su Kovası** ile sağ tıklayarak su dökebilirsin.
Ayrıntılar için [PLAN.md](PLAN.md).

## License

Inertia is licensed under the [GNU AGPL-3.0](LICENSE). It bundles physx-jni (MIT) and NVIDIA
PhysX (BSD-3-Clause).
