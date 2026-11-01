# Inertia

**Inertia is an advanced, professional physics engine for Minecraft: Java Edition.**

Version 0.1 (beta) adds real-time, GPU-simulated water powered by **NVIDIA PhysX 5**. Pour it
from a bucket and watch it splash, flow down stairs, pool in holes and push you around.

> **Requirements:** an NVIDIA GPU with a current driver (CUDA), Windows x64 or Linux x64,
> Minecraft 26.3 with Fabric Loader ≥ 0.20.1 and Fabric API. **Singleplayer only.**
> Without a CUDA-capable GPU the mod loads but stays inactive and tells you why.

## Download

Get the jar from the [Releases page](https://github.com/isnowx17/Inertia/releases). Inertia ships one jar per operating system because the PhysX GPU library is large
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
| `quality` | `medium` | `low`, `medium`, `high`, `ultra` or `custom`; see below. |
| `despawnDistance` | 96 | Water further away from you than this is removed. |
| `playerInteraction` | true | Whether water pushes, slows and lifts the player. |
| `renderMode` | `surface` | `surface`: one continuous water surface. `particles`: every particle as a sphere. |
| `surfaceSmoothness` | 2.0 | Surface smoothing radius relative to the surface cell size. |
| `surfaceThreshold` | 0.3 | Surface level as a fraction of still-water density. |
| `renderScale` | 1.6 | Size of spray droplets and foam relative to the particle spacing. |
| `renderOpacity` | 0.6 | Opacity of the water when looking straight into it. |
| `foam` | true | Simulate and draw foam, spray and bubbles. |
| `foamAmount` | 0.6 | How easily foam forms, 0–1. |
| `foamLifetime` | 2.5 | Seconds foam lives; longer-lived foam forms longer streaks. |
| `foamSize` | 1.0 | Size of the foam streaks. |
| `waterFriction` | 0.3 | Friction against blocks; higher makes water pool instead of sliding away. |
| `waterDamping` | 0.05 | Velocity damping. |
| `waterViscosity` | 0.01 | Viscosity; very high values feel like honey. |
| `waterSurfaceTension` | 0.002 | Surface tension; high values make thin water bead up. |
| `waterCohesion` | 0.02 | Cohesion; high values make thin water bead up. |
| `waterVorticity` | 4 | Keeps swirls alive. |

Quality levels (a bucket is always one block of water; changes apply after restarting the game):

| `quality` | Particles per block | Max particles | Surface cell | Sub-steps |
|---|---|---|---|---|
| `low` | 512 | 65,536 | 0.19 blocks | 2 |
| `medium` | 1000 | 131,072 | 0.15 blocks | 3 |
| `high` | 1728 | 262,144 | 0.125 blocks | 3 |
| `ultra` | 4096 | 524,288 | 0.1 blocks | 4 |
| `custom` | from `particleSpacing` | `maxParticles` | `surfaceCellSize` | `substepsPerTick` |

On every quality level about 128 blocks of water can exist at the same time.

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

To publish a release, set `mod_version` in `gradle.properties`, add a `## [<version>]` entry to
`CHANGELOG.md` and push. If that version has not been released yet, GitHub Actions builds both
jars, creates the tag `v<version>` and publishes the release.

## How it works

- **PhysX PBD fluid** on the GPU via [physx-jni](https://github.com/fabmax/physx-jni) 2.5.1
  (PhysX 5.5.1), the last physx-jni release with CUDA support.
- **Block collision:** every 16×16×16 chunk section near the water becomes a static PhysX actor.
  Full blocks are greedily merged into large boxes, partial blocks use their collision boxes.
- **Player:** a kinematic box keeps the water out of the player; the force of the water on the
  player is computed from the particles around them.
- **Rendering:** a background job builds one continuous water surface from the particles
  (surface nets over a smoothed particle density field, in parallel on all spare cores) as often
  as it can; the render thread draws the newest surface, moved along the flow since it was built,
  shaded with Fresnel reflection, biome colour and block light. Isolated splashes are drawn as
  small droplets.
- **Foam:** PhysX diffuse particles (foam, spray, bubbles) spawn where the water splashes or
  churns and are drawn as soft streaks stretched along the flow.

See [PLAN.md](PLAN.md) for scope and versioning, and [CHANGELOG.md](CHANGELOG.md) for changes.

## Türkçe

Inertia, Minecraft: Java Edition için GPU üzerinde çalışan bir fizik motorudur. 0.1 sürümü
NVIDIA PhysX ile simüle edilen su ekler. NVIDIA ekran kartı (CUDA) gerekir, yalnızca tek
oyunculu çalışır. *Araçlar* sekmesindeki **PhysX Su Kovası** ile sağ tıklayarak su dökebilirsin.
Ayrıntılar için [PLAN.md](PLAN.md).

## License

Inertia is licensed under the [GNU AGPL-3.0](LICENSE). It bundles physx-jni (MIT) and NVIDIA
PhysX (BSD-3-Clause).
