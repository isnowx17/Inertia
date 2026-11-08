# Changelog

All notable changes to Inertia are documented here. Versioning rules are described in
[PLAN.md](PLAN.md#5-sürümleme).

## [2.4.0] — Slime Update

### Added
- **Soft slime blocks.** The PhysX Slime Block (next to the lava buckets) places a slime block that
  is a soft body: a cube of 384 tetrahedra simulated with finite elements on the GPU (the stable
  Neo-Hookean material, solved with XPBD in Inertia's own CUDA kernels, 40 substeps a tick). It
  falls, wobbles and squashes, and springs back into shape. Sneak + use picks it up again.
- **It reacts to entities.** The player, animals, items and vehicles push it around; the weight of
  whoever stands on it presses it down a little, they rest on the squashed surface and bounce on it
  when they land fast, like on a vanilla slime block (not while sneaking). Walking on it is sticky.
- **Slime blocks stick to each other** and rub against blocks and each other.
- Drawn with the vanilla slime block texture (inner core and translucent shell) following the
  squashed shape. Saved with the world (`slime-<dimension>.dat`); `/inertia clear` removes them.
- Settings: `slimeStiffness`, `slimeStickiness`, `slimeEntityWeight`, `slimeFriction`,
  `slimeDamping`, `slimeSubsteps`.

## [2.3.0] — Lava Update

### Added
- **Lava.** The PhysX Lava Bucket and the PhysX Flowing Lava Bucket (next to the vanilla lava
  bucket) pour lava simulated by PhysX like the water, in a particle system of its own so the two do
  not mix. Lava is thick and slow: high viscosity and cohesion, friction with the ground and a little
  adhesion make it spread in tongues and run down walls; it falls at normal speed.
- **Lava's look:** opaque and glowing with its own light, brighter and yellower where it is thick and
  seen from the side, with slow fine wrinkles and an orange sun sheen. Its surface is built on the
  GPU every frame like the water's (each liquid has its own GPU surface).
- **A player in the lava burns.** Lava pops and bubbles, and its buckets make the lava bucket
  sounds. Lava is saved with the world like the water (`lava-<dimension>.dat`).
- Settings: `lavaViscosity`, `lavaDamping`, `lavaFriction`, `lavaCohesion`, `lavaAdhesion`,
  `lavaSurfaceTension`.

### Changed
- Frame pacing is shared by all liquids.

## [2.2.0]

### Added
- **The water is saved with the world** (`saveWater`, on by default): the moving water (every
  particle with its velocity), the still water kept as a volume and the flowing sources, per
  dimension in `<world>/inertia/water-<dimension>.dat`. Saved when leaving the world or the
  dimension, when the game saves the world, and every `autosaveSeconds` (60) when the water changed;
  written in the background, compressed, to a temporary file that then replaces the old one. Back in
  the world, the moving water returns once the chunks it lies in are loaded, so it does not fall
  through the ground. Water saved with another particle spacing keeps its volume. An unreadable save
  is moved aside instead of being written over.
- **Water far away stays.** Water further than `despawnDistance` from the player is no longer
  removed: it goes to sleep as still water where it is (on the ground below it), and the moving
  water nearby rests against it instead of flowing away. Beyond twice that distance it leaves the
  simulation and is kept per chunk (nothing is simulated or drawn there, however far you travel).
  When the player comes back it returns once its chunk is loaded and wakes 8 blocks inside the
  distance. All of it is saved. `/inertia clear` removes this water too.

## [2.1.0]

### Added
- **Water sounds** (`waterSounds`, on by default), all Minecraft's own. Falling water splashes where
  it lands, on the water below it or on the ground right under it (never while it is still in the
  air): a few drops drip, more water splashes louder and deeper. Water stirred a little sloshes
  softly, a fast current gurgles like flowing water. The player splashes when getting into the
  water (the diving sound only after a real fall) and makes water sounds wading and swimming. The
  buckets sound when water is poured, a source is turned on and when it is turned off. The water is
  measured for this on the physics thread after each step: water falling faster than 1.8 blocks a
  second per 2 x 2 block column, and water moving faster than 0.8 blocks a second per 2-block
  cell; each place sounds again only after a short pause.

## [2.0.1]

### Changed
- The game shows Inertia's items, messages and command output in English whatever language it is
  set to (the Turkish translation was removed).

## [2.0.0]

The big optimisation update: the water surface is built on the graphics card, every frame.

### Added
- **The water surface is built on the GPU** (`gpuSurface`, on by default; with the water shader on
  NVIDIA). Inertia's own CUDA kernels, running in PhysX's CUDA context, take the particles straight
  from the GPU and build the surface there: they spread the particles over a sparse grid of 16³
  bricks, smooth it, and extract the surface with surface nets right into the OpenGL buffers the
  game draws, so the surface never goes through the CPU. One surface takes about 1 ms on the GPU and
  0.5 ms on the CPU with 140,000 vertices (an RTX 4090 Laptop), and it is rebuilt up to the frame
  rate (70 to 90 times a second there) instead of once per physics step. Where CUDA or the OpenGL
  link is not available the CPU builds the surface as before. Settings files from before 2.0.0 turn
  it on once.
- **The water moves smoothly between physics steps.** It is shown one tick behind and moved evenly
  from the previous physics step to the latest one (on the GPU, and between CPU surfaces as well),
  instead of being pushed along its flow and jumping back when the next step came in. Frozen still
  water, foam and spray follow the same timeline.

### Fixed
- Ripples, caustics and the light in the water moved in small steps, the longer the computer had been
  on the more: the shader's clock lost its precision as a float second count of the uptime.
- Spray (PhysX's white particles) jumped back at every physics step: it moved with the water's
  velocity where it was. Each spray particle is now found again in the next step and moves the way it
  really went, falling along a curve; when not all can be drawn, the same ones are drawn every step.
- The water no longer disappears for a frame (showing the sky or the ground): a surface whose foam
  belonged to another foam map hid all foam, and a surface that did not fit its buffers was drawn
  with holes. Each surface is drawn with the foam map it was built for, and a surface that does not
  fit is built again with more room before it is shown (the room grows before it runs out).
- Foam turned blurry and sharp again in turn when it spread over a wide area: the whole foam map lost
  half its resolution. The map now holds 64 blocks at full resolution and, beyond that, covers the
  part around the camera, fading out where it is cut.
- Foam close up no longer looks grainy at high screen resolutions: its bubbles are only drawn where
  they are a few pixels wide.

## [1.4.1]

### Fixed
- Splashes no longer flash up as spikes that vanish a moment later. Between two surfaces every
  vertex is moved along its flow; where a splash left the water one vertex could be much faster than
  those around it and the surface stretched into a spike until the next surface. The flow of the
  surface is now evened out with the neighbours' first, and no vertex may move more than 1.5 blocks a
  second faster or slower than the ones around it (a waterfall flows as before).
- An animal falling from high up into shallow water no longer dies: the water below it now counts as
  soon as it would reach it within the next tick.
- Water that leaves the surface as it gets thin (a splash breaking up) no longer turns into small
  white specks: it is drawn as drops of water as big as the water each particle stands for, in the
  water's colour.

### Added
- `droplets` (on by default): off, water that leaves the surface in single drops is not drawn.

## [1.4.0]

### Added
- **The water and the other entities act on each other** (`entityInteraction`, on by default):
  minecarts, boats, animals and monsters, dropped items, experience, falling blocks and TNT. Each
  gets a box in the physics like the player, so the water flows around it and makes waves where it
  moves. The water carries them along a current, slows them down and lifts them by how deep they
  are in it: boats float high, items and experience float, animals and monsters float just so,
  minecarts, falling blocks and TNT sink slowly. A fall into the water is cushioned. The lift
  follows the height of the water around an entity (not the water splashed onto it), worked out
  where the entity is at that moment, so a boat lies still instead of bobbing. The changes are made
  on the integrated server at every tick; a boat the player rows is moved on the client. Moving
  entities wake still water, and frozen water thaws below them. Measured natively at `ultra` with a
  boat, a minecart, a cow and an item in a filling 7 x 7 box: 240 FPS, real time.
- A player in a boat or a minecart is moved by the water through the vehicle.

## [1.3.1]

### Added
- `foamQuality`: the foam's detail can be set apart from the quality level (`auto`, the default,
  follows it as before; `low`, `medium`, `high`, `ultra`), for example ultra foam with a `custom`
  quality.

## [1.3.0]

Still water kept as a volume: a box, a pool or a room can be filled with far more water.

### Added
- **Still water as a volume** (`stillWaterVolume`, on by default). Calm water deep under the surface
  is taken out of the physics and kept as a height per column of blocks, with a box in its place
  that the water above rests on; the surface mesh fills that box with water of the same density, so
  it looks the same, through glass too. Only the top few particle layers stay particles, so a deep
  pool costs about as much as a shallow one. The water turns back into particles where it could move
  again: when the water on it gets thin or drains away, when the water beside it is lower, when a
  stream plunges down to it, when a block next to it changes, near flowing sources and around the
  player. At the GPU's limit slowly moving deep water freezes too, and the sources pour as much new
  water as froze, so the water keeps growing instead of new water replacing old. Measured at
  `ultra`, filling a closed 7 x 7 box:

  | | 1.2.1 | 1.3.0 |
  |---|---|---|
  | One source, native Windows, 240 Hz | not measured | 240 FPS, real time, 1.3 million particles of water and rising |
  | Four sources, WSL2 | 350,000-410,000 particles, 0.89-0.97 x real time | box full: 4.6 million particles, 0.91-0.94 x real time |

### Changed
- At the GPU's limit no water is deleted any more while freezing can make room; deep water is taken
  away only when the steps take longer than a game tick all the same.

## [1.2.1]

### Performance
- **Frame pacing while the water needs the GPU.** With no frame limit the game drew 1,100-1,500
  frames a second on an RTX 4090 Laptop, more than the screen shows, and those frames took the GPU
  time the physics needed: the GPU's limit came at about 90,000 particles. When the physics steps
  get long, Inertia now holds the frame rate to the screen's refresh rate (240 FPS there) until the
  water is gone or sleeps; the limit then came at about 456,000 particles. Nothing happens with vsync
  on or a frame limit at or below the refresh rate. The solver gets cheaper and calm water makes
  room only after that.

### Fixed
- A falling stream from a flowing source no longer breaks into a row of lumps, one per tick. The
  water of each tick now goes right on top of the stream, moving like a steady stream would, and the
  stream gets thinner as it falls faster, like real water, so it stays one piece even over a long
  drop.
- At the GPU's limit water no longer vanishes where it spreads thin over the ground. New water now
  replaces calm water only where the water is deep (deepest first), so a room still floods evenly and
  only deep water sinks a little. When there is not enough deep water, the sources pour a thinner
  stream instead, at an even rate.

## [1.2.0]

A big performance update: the game never waits for the GPU, still water costs next to nothing, and
the water keeps flowing in real time.

Measured at `ultra`, 1920×1080, no frame limit, RTX 4090 Laptop under WSL2 (a world without water:
about 150 FPS):

| Scene | 1.1.2 | 1.2.0 |
|---|---|---|
| Waterfall, 180,000 particles | 69 FPS (1 % low 14) | 96 FPS (1 % low 26) |
| Still pool, 245,000 particles | 96 FPS | 151 FPS |
| Standing in a pool, 245,000 particles | 101 FPS | 112 FPS |
| Two sources left running | 1 FPS, water at 50 % speed, 1 million particles | 80 FPS, water at 97 % speed, 400,000 particles |
| Still pool after that | 0.7 FPS, 1.2 million particles | 160 FPS, 400,000 particles |

### Performance
- **The game never waits for the GPU.** A physics step that takes longer than a game tick used to
  stop the game until it was done, and the game then ran several ticks per frame, each waiting for
  the GPU (about 1 FPS). Now the game goes on, and the tick is simulated on the first frame after the
  step finished. The longest game tick went from 20-250 ms to mostly under 10 ms.
- **Still water sleeps.** Water that has been still for 3 seconds needs no physics steps and no new
  surfaces, which frees the GPU and the CPU: a still pool costs almost nothing but its drawing. New
  water, a change of the collision geometry around it and the player coming close wake it up.
- **Water keeps flowing in real time.** When a step gets close to a tick's length, the simulation
  uses fewer sub-steps (three instead of four at `ultra`). When that is not enough, new water replaces
  calm water (see `keepRealTime` below).
- **Spray and droplets stay on the GPU:** one instance per particle, uploaded once per simulation
  step and turned into camera-facing quads by a vertex shader, instead of being rebuilt and
  uploaded every frame. Inertia's own rendering code now takes 0.1-1.2 ms per frame on the render
  thread; with a lot of water it took 11-24 ms.
- Light is looked up once per block instead of per vertex; water outside the view is not drawn;
  with the camera in the water a depth pre-pass makes every pixel get shaded once; the reflection
  rays need no matrix product per step.
- Physics thread: the copy of the particles and the work on them run in parallel chunks. Removing
  particles uploads only the changed ones (it used to upload nearly all of them). Block changes
  that leave the collision geometry as it is (grass spreading, crops growing) no longer rebuild it.
- The surface worker threads run below normal priority.

### Changed
- New setting `keepRealTime` (default on): when the GPU cannot simulate more water in real time, new
  water replaces calm water. A source keeps pouring and a pool stops rising, so the water does not
  fall into slow motion. How much water that is depends on the GPU and on how much it has to draw.
  When off, the water can grow up to `particleLimit` as before, and runs in slow motion once the GPU
  cannot keep up (the game keeps its frame rate).
- With the cheaper solver the water level of a pool is about 5 % lower.
- Foam fades on sleeping water; surface details that only came from the particles' jitter stand still.
- `/inertia info` shows the solver in use, whether the GPU's limit is reached and whether the water
  sleeps.

## [1.1.2]

### Fixed
- Falling water no longer stops speeding up after about a block and a half. Its top speed was
  capped by a value tied to the particle size (5.2 blocks/second at `ultra`); it is now 20
  blocks/second, limited to a quarter of a block per sub-step (15 at `high`/`medium`, 10 at `low`).
  Continuous collision detection is on, so fast water does not pass through blocks.

## [1.1.1]

### Fixed
- Water from a flowing water source no longer falls in separate lumps. The source now lets out a
  steady flow: every tick the same amount of water appears at the top of its block, moving down at
  `sourceFlow` blocks per second, so it forms one continuous column. Only when the water cannot get
  away (a pool below) does the block fill up and the water spread out gently.

## [1.1.0]

### Changed
- The **PhysX Flowing Water Bucket** now works like a water bucket placing a water source block.
  The source goes into the block in front of the clicked face (or into a replaceable block like
  tall grass) and keeps that whole block full of water: water flows out of it, down when there is
  room below, otherwise out along the ground. It no longer shoots a jet out of the clicked face.
- Placing a block into a source stops it, like filling in a water source block. Clicking the same
  place again also stops it; sneak + click stops all sources. A source cannot be placed into a
  solid block.
- New setting `sourceFlow` (blocks of water per second, default 3) limits how fast a source
  refills; `sourceRadius` and `sourceSpeed` are gone.
- Sources further away than the despawn distance pause instead of making water that would be
  removed right away.

## [1.0.0]

A big performance update: the same water, much less CPU time, and almost no work per frame.

### Performance
- **Surface building 3 to 7 times faster** (measured, 3 worker threads, `ultra`): 262,144 particles
  72 → about 20 ms, 2,097,152 particles 639 → about 90 ms. Every particle is now spread over the
  eight surrounding grid nodes (instead of about 33) and the density is smoothed with a small
  separable blur. Grid bricks deep inside a body of water are not splatted at all, bricks the
  surface cannot cross are skipped, and grid memory and particle bins are reused between updates.
- **No per-frame CPU work for the water surface:** the surface stays on the GPU. Each new surface
  is uploaded once, with every vertex shared by its quads (about a quarter of the data), and the
  vertex shader moves it along the flow every frame. Before, every vertex was recomputed, rebuilt
  and uploaded every frame.
- Light for the surface is looked up once per surface and cached, instead of every frame.
- **Half the shading work** when the camera is outside the water: the inner sides of the surface
  (a pool's floor seen through its top) are no longer shaded.
- The surface is rebuilt once per simulation step (20 per second) from the newest data, without
  copying the particle arrays (they are lent to the surface job).
- Physics thread: positions and velocities are copied back with one bulk copy each; lost
  particles, the water around the player and the blocks needing collision geometry are found on
  the physics thread instead of the game thread. After removing lost particles only the changed
  part of the particle buffer is uploaded again (it used to be all of it).
- Finding the isolated spray droplets reuses the surface's particle bins and skips dense bricks.
- The fine bubble detail of the foam is only computed up close.

## [0.6.1] — Beta

### Changed
- The default quality is now `ultra`: 4096 particles per block of water, 0.1-block surface cells,
  4 sub-steps per tick, up to 250,000 foam tracers and a foam map with 32 texels per block.
  Config files that still have the previous default `high` switch to `ultra`. It needs a strong
  NVIDIA GPU; set `quality` to `high` (or lower) for more FPS.

## [0.6.0] — Beta

### Changed
- Completely new foam. Foam is now made of many small foam tracers that float on the water
  surface: they are spawned where the water churns (fast flow, splashes, and where PhysX spawns
  fresh diffuse particles at the surface), drift with the surface flow, are stirred by small
  eddies and pushed aside by boils (upwellings), so the foam stretches into swirling lines, rings
  and patches and fades after a few seconds. Still water stays clear.
- The foam is drawn from a fine top-down foam map (about 4 cm per texel, with mip levels) that
  moves with the surface between rebuilds; thin foam is translucent, dense foam opaque white, and
  up close it breaks up into fine bubbles. It only shows on the surface it floats on, not on a
  stream passing above it.
- The foam lace from 0.5.0 is no longer used.
- `foamSize` is now the width of the foam lines and `foamCoverage` (default 1.0) how quickly
  overlapping foam turns white; old values of `foamCoverage` are reset.

### Performance
- The foam runs on the surface worker with the meshing (about 3 ms per rebuild for 40,000
  tracers, 12 ms for 150,000, in parallel on the spare cores).

## [0.5.0] — Beta

### Added
- New foam. Foam particles on the surface add up to a foam amount that turns into coverage
  (`1 - exp(-amount × coverage)`, at most 90 %). The coverage is broken up by a foam lace, a fine
  web of bubble walls (5 cm bubbles) that thickens where the foam is dense and fades to an even
  tone when the bubbles are smaller than a pixel.
- `foamCoverage` setting (default 1.2). `foamSize` now scales the foam splats.
- Foam is on again by default (also in existing config files).

## [0.4.5] — Beta

### Removed
- The foam is gone: no foam pattern in the water shader, no white tint on fast water and no
  foam streaks on the surface.
- The remaining spray flecks (`foam` setting) are off by default; existing config files are
  switched off too. Turn `foam` back on in `config/inertia.json` to get the spray back.

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
