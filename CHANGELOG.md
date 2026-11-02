# Changelog

All notable changes to Inertia are documented here. Versioning rules are described in
[PLAN.md](PLAN.md#5-sürümleme).

## [0.4.4] — Beta

### Fixed
- Still water no longer shows foam: foam is tied to the local flow speed and only appears where
  the water moves (from 0.4 blocks/second, fully at 1.6); when the water settles it disappears.
- The sun glint is less blown out (no wide white halo).

## [0.4.3] — Beta

### Fixed
- Foam no longer looks like thick white paint strokes: the shader draws it as a lacy web of fine
  filaments at two scales, broken up into bubbles and gathering in patches, with soft,
  semi-transparent edges. (The pattern was tuned with an offline replica of the shader.)

## [0.4.2] — Beta

### Fixed
- Still water no longer turns completely white: only foam floating right at the surface counts
  (bubbles below the surface are ignored), the foam layer saturates much more slowly, and the
  shader always draws foam as thin marbled lines instead of a solid white sheet.
- Default `foamAmount` 0.35 and `foamLifetime` 3 s (old defaults in config files are updated).

## [0.4.1] — Beta

### Fixed
- Foam no longer looks like white needles. With the water shader, foam is now part of the water
  surface: the foam particles PhysX produces are spread over the surface mesh (a foam channel in
  the surface grid) and the shader draws them as a marbled white foam layer that drifts with the
  current. In the simple rendering, foam streaks are shorter and wider and the surface itself is
  tinted white where foam floats.
- Spray and droplets are drawn as small soft white flecks instead of blue balls.

## [0.4.0] — Beta

### Changed
- **Performance:** the GPU simulation step and the read-back of the results run on their own
  physics thread. The game no longer waits for the GPU every tick; each tick collects the previous
  step's results and starts the next one, which the tick interpolation hides. `/inertia info` now
  shows the GPU step time.
- The water surface is rebuilt at most 40 times per second (it follows the flow in between), so
  meshing no longer competes with chunk building for CPU time.
- **Foam:** foam on the surface is drawn as thin streaks lying flat on the water and stretched
  along the current, with a new wispy foam texture, so moving water forms white foam lines.
  Spray just above the water is drawn as small white flecks. Default `foamAmount` 0.5 and
  `foamLifetime` 4 s (old defaults in config files are updated).
- **Shader foam:** fast-moving water gets a marbled web of white foam lines on its surface.

## [0.3.0] — Beta

First release since 0.1.1; it also contains the 0.2.0 changes listed below, which were not
released separately.

### Added
- **PhysX Flowing Water Bucket:** right-click a block face to start a continuous stream out of it
  (sideways from a wall, a fountain from the ground, falling from a ceiling); right-click near a
  source to stop it, sneak + right-click to stop all. Up to 16 sources. Same sprite as the water
  bucket. Config: `sourceRadius`, `sourceSpeed`.
- **No more small particle limit:** the GPU particle buffer starts at the quality level's size and
  doubles automatically whenever the water outgrows it, up to `particleLimit` (default 2,097,152,
  about 1200 blocks of water on `high`). More water costs frame rate.
- **Reflections (shader):** screen-space reflections show blocks, terrain and entities around the
  water on its surface; where the reflected ray leaves the screen the sky is reflected instead.
- **Caustics (shader):** moving light patterns on the ground under the water, strongest in shallow
  water and in daylight.
- Config: `reflections`, `caustics`, `sunStrength`, `particleLimit`.
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
- The water shader no longer switches itself off on the first frame: textures used by the water
  pass are loaded before the pass opens (loading a texture inside an open render pass is not
  allowed and made the shader fall back to the simple rendering).
- With the water shader, foam and spray are drawn after the water surface, so their soft edges
  blend with the water instead of the ground below it (foam no longer has green grass edges).
  Foam just below the surface is pulled slightly towards the camera so it stays visible.
- Shader compile test: the shaders are compiled with shaderc and cross-compiled with SPIRV-Cross
  in the unit tests, exactly like Minecraft does, so shader errors are caught without a GPU.

### Changed
- Water colour: turquoise scattering in medium depths, a stronger and wider sun glint.
- With many particles, collision bookkeeping samples a subset of particles per tick.
- Default quality is now `high` (1728 particles per block). Config files that still had the old
  default `medium` are moved to `high`.

## [0.2.0] — included in 0.3.0

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
