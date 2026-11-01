# Changelog

All notable changes to Inertia are documented here. Versioning rules are described in
[PLAN.md](PLAN.md#5-sürümleme).

## [0.1.1] — Beta

### Fixed
- Water is now drawn as one continuous, smooth water surface instead of separate balls. The
  surface is rebuilt from the particles on a background thread (surface nets over a particle
  density field), with Fresnel reflection, biome water colour, block light and foam on fast water.
  Isolated splashes are drawn as small spray droplets.
- Thin water no longer breaks up into scattered beads: lower cohesion and surface tension, more
  friction against blocks and a little viscosity make the water flow together and pool.

### Added
- Config: `renderMode` (`surface` or `particles`), `surfaceResolution`, `surfaceSmoothness`,
  `surfaceThreshold`, and the water material (`waterFriction`, `waterDamping`, `waterViscosity`,
  `waterSurfaceTension`, `waterCohesion`, `waterVorticity`).

## [0.1.0] — Beta

First release. Minecraft 26.3, Fabric. Requires an NVIDIA GPU (CUDA) on Windows x64 or Linux x64.
Singleplayer only.

### Added
- GPU water simulation powered by NVIDIA PhysX 5 (PBD particles via physx-jni 2.5.1).
- **PhysX Water Bucket** (Tools & Utilities tab): right-click releases one block of water where
  you are looking; hold right-click to keep pouring. The bucket never empties.
- Collision between the water and blocks, including partial blocks such as slabs and stairs.
  Collision geometry updates when blocks are placed or broken.
- Player interaction: water flows around the player, pushes and carries them, slows them down,
  makes them float and cushions falls.
- Water rendering with biome water colour, block lighting and lighter foam on fast-moving water.
- `config/inertia.json` with particle limit, particle spacing, despawn distance, simulation
  sub-steps, player interaction and render settings.
- `/inertia info` and `/inertia clear` client commands.
- When no CUDA-capable GPU is found the mod disables itself and shows a message instead of
  crashing the game.
