# Changelog

All notable changes to Inertia are documented here. Versioning rules are described in
[PLAN.md](PLAN.md#5-sürümleme).

## [Unreleased] — 0.2.0

### Added
- **Foam streaks.** PhysX now simulates foam, spray and bubbles (diffuse particles) wherever the
  water splashes, speeds up or traps air. They are carried by the flow and drawn as soft white
  streaks stretched along it, leaving foam lines behind moving water before they fade out.
- **Quality levels** (`quality`: `low`, `medium`, `high`, `ultra`, `custom`). Higher levels use
  more and smaller particles (up to 4096 per block and 524,288 in total on `ultra`) and a finer
  surface. A bucket is always one block of water.
- Config: `foam`, `foamAmount`, `foamLifetime`, `foamSize`; `surfaceCellSize` for `custom`.
- `/inertia info` shows the quality level and the number of foam particles.

### Changed
- Finer surface detail: the surface grid now has an absolute cell size per quality level (down
  to 0.1 blocks) instead of being tied to the particle spacing.
- The surface is meshed in parallel on all spare CPU cores.
- The surface moves along the local flow between rebuilds, so the water flows smoothly at any
  frame rate even when meshing takes longer than a frame.
- More GPU room for particle contacts so large amounts of water do not leak through blocks.
- Default quality is `medium` (1000 particles per block, previously 512).

### Removed
- `surfaceResolution` (replaced by the quality levels and `surfaceCellSize`).

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
