# Changelog

All notable changes to Inertia are documented here. Versioning rules are described in
[PLAN.md](PLAN.md#5-sürümleme).

## [Unreleased] — 0.3.0

### Added
- **Water shader.** The water surface is drawn with Inertia's own shader in a render pass after
  the main level pass:
  - **Refraction:** what is behind the water is seen through it, bent by the ripples.
  - **Depth-dependent colour and clarity:** light is absorbed by the distance it travels through
    the water (read per pixel from the depth buffer): shallow water is clear, deep water turns
    blue-green.
  - **Ripples:** animated, world-anchored small waves on every pixel.
  - **Reflections:** Fresnel sky reflection using the real sky colour, and a sharp sun glint.
  - Soft shorelines where the water gets very thin.
- The shader is compiled with Minecraft's shader system as an optional pipeline; if it does not
  work on a computer the mod falls back to the simple rendering automatically.
- The simple rendering also got depth-dependent colour and transparency (per-vertex water depth).
- Config: `waterShader`, `rippleStrength`, `refractionStrength`, `waterAbsorption`.
- `/inertia info` shows whether the water shader is active.
- The shape of the water reads better with the shader: refraction follows the tilt of the
  simulated waves (not only the small ripples), sun-facing sides of waves are lit brighter than the
  far sides, and the ripples are stronger.
- With the water shader, foam and spray are drawn after the water surface, so their soft edges
  blend with the water instead of the ground below it (foam no longer has green grass edges).
  Foam just below the surface is pulled slightly towards the camera so it stays visible.
- Shader compile test: the shaders are compiled with shaderc and cross-compiled with SPIRV-Cross
  in the unit tests, exactly like Minecraft does, so shader errors are caught without a GPU.

### Changed
- Default quality is now `high` (1728 particles per block). Config files that still had the old
  default `medium` are moved to `high`.

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
- Foam is white instead of grey: its quads were wound back-facing, and Minecraft's entity shader
  lights back faces with the inverted normal (ambient light only). Foam is also only drawn where
  it floats on the water surface (not spray in the air or bubbles deep inside) and takes the light
  from above.
- Lone particles resting on the ground are no longer drawn as blue balls; only moving spray is.
- Surface light is sampled just outside the water, so water touching blocks is not darkened.

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
