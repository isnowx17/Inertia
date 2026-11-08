package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.client.config.InertiaConfig;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import io.github.isnowx17.inertia.util.FluidParameters;
import io.github.isnowx17.inertia.util.FrozenColumns;
import io.github.isnowx17.inertia.util.ParticleScan;
import io.github.isnowx17.inertia.util.SourceSlots;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import physx.NativeObject;
import physx.PxTopLevelFunctions;
import physx.common.PxCudaContextManager;
import physx.common.PxCudaTopLevelFunctions;
import physx.common.PxGpuDynamicsMemoryConfig;
import physx.common.PxVec3;
import physx.common.PxVec4;
import physx.particles.PxDiffuseParticleParams;
import physx.particles.PxPBDMaterial;
import physx.particles.PxPBDParticleSystem;
import physx.particles.PxParticleAndDiffuseBuffer;
import physx.particles.PxParticleAndDiffuseBufferDesc;
import physx.particles.PxParticleBuffer;
import physx.particles.PxParticleBufferDesc;
import physx.particles.PxParticleBufferFlagEnum;
import physx.particles.PxParticlePhaseFlagEnum;
import physx.particles.PxParticlePhaseFlags;
import physx.physics.PxBroadPhaseTypeEnum;
import physx.physics.PxPruningStructureTypeEnum;
import physx.physics.PxScene;
import physx.physics.PxSceneDesc;
import physx.physics.PxSceneFlagEnum;
import physx.physics.PxSolverTypeEnum;
import physx.support.NativeArrayHelpers;

/**
 * One GPU particle-based fluid simulation (PhysX PBD) for the current client level.
 *
 * <p>Coordinates inside PhysX are relative to an integer {@linkplain #originX() origin} near the
 * water so that single-precision floats stay accurate far away from the world centre.
 *
 * <p>Particle data lives in a fixed-capacity GPU buffer. Indices {@code [0, activeCount)} are alive;
 * removed particles are compacted by moving the last particle into the hole. After each tick the
 * positions and velocities are copied back to pinned host memory and from there, in one bulk copy
 * each, into Java arrays with four floats per particle (x, y, z and an unused fourth value).
 *
 * <p>The physics thread also does the per-particle work of the tick while it has the fresh data at
 * hand ({@link ParticleScan}, in parallel chunks together with the copy into the Java arrays): it
 * finds lost particles, measures the water around the player and collects the blocks that hold
 * water. The client thread only applies the results.
 *
 * <p>The client thread never waits for a running step: {@link #stepRunning()} tells the manager to
 * let the step finish and start the next one as soon as it is done. A step should take less than
 * a game tick, so the water flows in real time: when it takes longer, the simulation switches to a
 * cheaper solver ({@link FluidParameters#solverLevels}), and with the cheapest one it reports
 * {@link #atGpuLimit()}: new water then replaces calm water ({@link #recycleCalmWater}) instead of
 * adding to it. Should the water still fall behind, it runs slower than real time
 * ({@link #timeScale()}) while the game keeps its frame rate. Water that is still needs no steps at
 * all: it sleeps (see {@link #sleeping()}) until something happens to it.
 *
 * <p>Background jobs (the surface mesher) read the newest arrays without copying them through a
 * {@linkplain #lease() lease}; leased arrays are never handed back to the physics thread.
 */
public final class FluidSimulation implements AutoCloseable {
	private static final float GRAVITY = -9.81f;
	private static final float TICK_SECONDS = 1f / 20f;
	private static final int VEC4_BYTES = 16;
	private static final int U32_BYTES = 4;
	/** Particles more than this far below the world bottom are removed. */
	private static final int BELOW_WORLD_MARGIN = 16;
	/** Particles sampled per tick to find where collision geometry is needed. */
	private static final int TERRAIN_SAMPLES = 131_072;
	/** Particles per chunk of the parallel read-back and scan. */
	private static final int SCAN_CHUNK = 32_768;
	/** At most this many chunks per scan. */
	private static final int MAX_SCAN_CHUNKS = 128;
	/**
	 * Holes left by removed particles that are at most this many particles apart are uploaded
	 * together: copying a few unchanged particles is cheaper than another copy call.
	 */
	private static final int UPLOAD_MERGE_GAP = 2_048;
	/** With more separate runs than this, the whole changed range is uploaded at once. */
	private static final int MAX_UPLOAD_RUNS = 32;
	/** Slowest speed (blocks/second, downwards) water leaves a source block with. */
	private static final float SOURCE_MIN_SPEED = 1f;
	/** A source block this full holds back water that cannot get away; new water then comes gently. */
	private static final float SOURCE_POOLING_FILL = 0.6f;
	/** Fastest falling or flowing water, in blocks per second (water falling 20 blocks reaches about this). */
	private static final float MAX_WATER_SPEED = 20f;
	/** Water moves at most this many blocks per sub-step. */
	private static final float MAX_STEP_DISTANCE = 0.25f;
	/** A step should take at most this long (a game tick is 50 ms); longer and the solver gets cheaper. */
	private static final float STEP_BUDGET_MILLIS = 45f;
	/** The solver gets better again when the better one is expected to take at most this long. */
	private static final float STEP_RELAXED_MILLIS = 37f;
	/**
	 * Steps longer than this (on average) start frame pacing: the frames drawn faster than the screen
	 * shows them take GPU time from the physics (with no frame limit, often several times as much as
	 * the screen needs). The solver gets cheaper and calm water makes room only after pacing has had
	 * {@link #PACING_FIRST_STEPS} steps to help.
	 */
	private static final float PACING_MILLIS = 30f;
	private static final int PACING_FIRST_STEPS = 20;
	/**
	 * Above this with the cheapest solver, the GPU has reached what it can simulate in real time (with
	 * some room: water keeps moving, and the next step can only start on the next frame).
	 */
	private static final float GPU_LIMIT_MILLIS = 42f;
	/** Below this, there is room for more water again. */
	private static final float GPU_ROOM_MILLIS = 37f;
	/** Above this at the GPU's limit, new water replaces a little more calm water than it adds. */
	private static final float GPU_OVERLOADED_MILLIS = 45f;
	/** A single step counts as at most this long, so one hitch does not change the solver. */
	private static final float LONGEST_STEP_SAMPLE_MILLIS = 100f;
	/** Water faster than this (blocks/second) keeps the simulation awake... */
	private static final float MOVING_SPEED = 0.5f;
	/** ...unless it is less than this fraction of the water (single particles jitter). */
	private static final float MOVING_FRACTION = 0.001f;
	/** Steps the water has to stay still before the simulation goes to sleep. */
	private static final int SLEEP_AFTER_STEPS = 60;
	/** How close (blocks, around their bounding box) the player has to get to wake the water. */
	private static final double PLAYER_WAKE_DISTANCE = 0.75;
	/** Water slower than this (blocks/second) counts as calm and may be recycled at the GPU's limit. */
	private static final float CALM_SPEED = 1f;
	/** Recycled water is taken from this many places in the particle list, so it is never missed in one spot. */
	private static final int RECYCLE_GROUPS = 16;
	/** Particles looked at per particle taken, before a place is given up. */
	private static final int RECYCLE_SEARCH = 256;
	/** Water at least this many particle layers deep may make room for new water. */
	private static final int RECYCLE_MIN_LAYERS = 4;
	/** The longest foam lives, relative to the configured lifetime (SurfaceFoam). */
	private static final float FOAM_LONGEST_LIFE = 2.3f;
	/** Steps to wait after a change of solver before getting cheaper or better again. */
	private static final int CHEAPER_AFTER_STEPS = 10;
	private static final int BETTER_AFTER_STEPS = 60;
	/** Floats per particle in the position and velocity arrays. */
	public static final int STRIDE = 4;

	private final InertiaConfig config;
	/** Realistic blocks, boats and minecarts (in the water's scene, so the two act on each other). */
	private final RigidBodies rigids;
	/** Water or lava. */
	private final FluidKind kind;
	private final FluidParameters params;
	/** Current size of the particle buffer; grows by doubling up to {@link #limit}. */
	private int capacity;
	/** Hard ceiling for the particle count (GPU memory and frame time). */
	private final int limit;

	private final PxCudaContextManager cuda;
	private final PxScene scene;
	private final PxPBDMaterial material;
	private final PxPBDParticleSystem particleSystem;
	private PxParticleBuffer buffer;
	/** Same object as {@link #buffer} when foam is enabled, otherwise {@code null}. */
	private PxParticleAndDiffuseBuffer diffuseBuffer;
	private final int substeps;
	private final int phase;

	// Pinned host staging memory, accessed from Java through direct buffers.
	private PxVec4 hostPositions;
	private PxVec4 hostVelocities;
	private NativeObject hostPhases;
	private PxVec4 hostDiffuse;
	private FloatBuffer hostDiffuseView;
	private int maxFoam;

	// Foam/spray/bubble particles after the latest tick: xyz + remaining lifetime per particle.
	private float[] foam = new float[0];
	private int foamCount;
	private long foamHiddenUntilTick;
	private long readBackNanos;
	private FloatBuffer hostPos;
	private FloatBuffer hostVel;

	private long devicePositions;
	private long deviceVelocities;
	private long devicePhases;

	// Java-side copies (STRIDE floats per particle, relative to the origin).
	// Back buffers written by the physics thread and swapped in by awaitStep().
	private float[] nextPos = new float[0];
	private float[] nextVel = new float[0];
	private float[] nextFoam = new float[0];
	private int nextFoamCount;
	private long stepFinishedNanos;
	// The per-particle work done on the physics thread: its inputs (captured when a step is
	// launched), the chunks it runs in, the merged result being written and the published one.
	private final ParticleScan.Settings scanSettings = new ParticleScan.Settings();
	private ParticleScan.Part[] scanParts = new ParticleScan.Part[0];
	private ParticleScan.Part nextScan = new ParticleScan.Part();
	private ParticleScan.Part scan = new ParticleScan.Part();
	// Where the water makes noise (WaterSounds), measured on the physics thread after each step.
	private final io.github.isnowx17.inertia.util.WaterAcoustics acoustics = new io.github.isnowx17.inertia.util.WaterAcoustics();
	private volatile io.github.isnowx17.inertia.util.WaterAcoustics.Result latestAcoustics;
	private volatile boolean acousticsWanted;
	/** The tick the running step simulates. */
	private long stepTickNumber;
	/** Runs the read-back and scan chunks of a step in parallel. */
	private final ForkJoinPool scanPool = new ForkJoinPool(
		Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
		pool -> {
			ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
			thread.setName("Inertia physics scan " + thread.getPoolIndex());
			thread.setDaemon(true);
			return thread;
		},
		null, false);
	/** Simulated time per real time; below 1 only when the water cannot keep up with the game. */
	private float timeScale = 1f;
	private float lastSimulateMillis;
	/**
	 * The ways to simulate a tick, best first (see {@link FluidParameters#solverLevels}), the one in
	 * use, the smoothed duration of a step, and how many steps ago the solver last changed.
	 */
	private final FluidParameters.SolverLevel[] solverLevels;
	private int solverLevel;
	private float stepLoadMillis;
	private int stepsSinceSolverChange;
	private boolean gpuLimit;
	private final IntArrayList recycled = new IntArrayList();
	/** See {@link #wantsFramePacing}. */
	private volatile boolean framePacing;
	private int stepsPacing;
	private int recycleOffset;
	// Still water sleeps: no physics steps until something happens to it (see #sleep).
	private int stillSteps;
	private boolean sleeping;
	private int sleepTicks;
	private double sleepPlayerX;
	private double sleepPlayerY;
	private double sleepPlayerZ;
	private double sleepPlayerRange;
	// Sub-steps of the running step and their length.
	private int stepSubsteps;
	private float stepSubstepSeconds;

	// Flowing sources: the blocks to measure (set by the manager), the cells handed to the running
	// step (relative to the origin) and the measurements of the latest finished step.
	private long[] requestedSources = new long[0];
	private SourceCell[] stepSources = new SourceCell[0];
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<SourceCell> sourceCells = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

	/** Arrays background jobs are reading; they are not handed back to the physics thread. */
	private final Set<float[]> leased = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Changes whenever the particle data changes (a finished step, spawned water). */
	private long version;

	/** Runs the GPU step and read-back off the client thread. */
	private final java.util.concurrent.ExecutorService stepper = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Inertia physics");
		thread.setDaemon(true);
		return thread;
	});
	private java.util.concurrent.Future<?> pendingStep;
	private int stepParticleCount;
	private boolean stepHidesFoam;

	private float[] currPos;
	/**
	 * Positions at the start of the latest step, for the same particles as {@link #currPos} (kept in
	 * step with it when particles are added or removed), and when the step before that was read back.
	 */
	private float[] prevPos;
	private long prevReadBackNanos;
	/** When the running step was started, and the latest two finished ones. */
	private long stepLaunchNanos;
	private long finishedLaunchNanos;
	private long previousLaunchNanos;
	private float[] velocity;

	private int activeCount;
	private int originX;
	private int originY;
	private int originZ;
	private boolean originSet;
	private long tick;
	private float lastStepMillis;

	private final TerrainCollider terrain;
	private final PlayerBody playerBody;
	/** The other entities in the water (see {@link EntityBodies}): those near it now, of the running step and of the latest finished one. */
	private final EntityBodies entityBodies;
	private java.util.List<net.minecraft.world.entity.Entity> nearEntities = java.util.List.of();
	private EntityBodies.Step stepBodies = EntityBodies.Step.EMPTY;
	private EntityBodies.Step measuredBodies = EntityBodies.Step.EMPTY;
	/** Blocks per tick an entity in still water has to move to wake it up. */
	private static final double ENTITY_WAKE_SPEED = 0.06;
	/** Calm water deep under the surface, kept as a volume instead of particles; null when switched off. */
	private final FrozenColumns frozen;
	private final FrozenCollider frozenCollider;
	private final IntArrayList frozenRemoval = new IntArrayList();
	/** Columns measured one by one around the water at most (per axis); wider water freezes nothing. */
	private static final int MAX_GRID_SIZE = 160;
	/** Box (x and z, relative) of the water added since the last step was launched; min > max when none. */
	private float addedMinX = Float.POSITIVE_INFINITY;
	private float addedMaxX = Float.NEGATIVE_INFINITY;
	private float addedMinZ = Float.POSITIVE_INFINITY;
	private float addedMaxZ = Float.NEGATIVE_INFINITY;
	private final LongOpenHashSet pendingUrgentSections = new LongOpenHashSet();

	public FluidSimulation(InertiaConfig config, FluidKind kind) {
		this.config = config;
		this.kind = kind;
		InertiaConfig.Quality quality = config.effectiveQuality();
		this.params = FluidParameters.forSpacing(quality.particleSpacing());
		this.limit = Math.max(quality.maxParticles(), config.particleLimit);
		this.capacity = quality.maxParticles();
		this.substeps = quality.substepsPerTick();
		this.solverLevels = FluidParameters.solverLevels(substeps);
		this.cuda = PhysXRuntime.cuda();

		this.scene = createScene();

		try (MemoryStack mem = MemoryStack.stackPush()) {
			// Water: low cohesion and surface tension keep thin water as one sheet instead of beads;
			// friction and a little viscosity make it slow down and pool. Lava: high friction, damping
			// and viscosity make it a thick mass that creeps over the ground; no vorticity.
			boolean lava = kind == FluidKind.LAVA;
			material = PhysXRuntime.physics().createPBDMaterial(
				lava ? config.lavaFriction : config.waterFriction,             // friction
				lava ? config.lavaDamping : config.waterDamping,               // damping
				lava ? config.lavaAdhesion : 0f,                               // adhesion
				lava ? config.lavaViscosity : config.waterViscosity,           // viscosity
				lava ? 0f : config.waterVorticity,                             // vorticity confinement
				lava ? config.lavaSurfaceTension : config.waterSurfaceTension, // surface tension
				lava ? config.lavaCohesion : config.waterCohesion,             // cohesion
				0f,                                                            // lift
				0f                                                             // drag
			);

			particleSystem = PhysXRuntime.physics().createPBDParticleSystem(cuda, 96);
			particleSystem.setRestOffset(params.restOffset());
			particleSystem.setContactOffset(params.contactOffset());
			particleSystem.setParticleContactOffset(params.particleContactOffset());
			particleSystem.setSolidRestOffset(params.solidRestOffset());
			particleSystem.setFluidRestOffset(params.fluidRestOffset());
			// Falling water keeps accelerating up to a realistic speed; continuous collision detection
			// keeps such fast water from passing through blocks.
			particleSystem.enableCCD(true);
			particleSystem.setMaxVelocity(maxVelocity(params, substeps));
			particleSystem.setSimulationFilterData(PhysXRuntime.filterData());
			scene.addActor(particleSystem);

			PxParticlePhaseFlags phaseFlags = PxParticlePhaseFlags.createAt(mem, MemoryStack::nmalloc, 0);
			phaseFlags.raise(PxParticlePhaseFlagEnum.eParticlePhaseFluid);
			phaseFlags.raise(PxParticlePhaseFlagEnum.eParticlePhaseSelfCollide);
			phase = particleSystem.createPhase(material, phaseFlags);

		}

		currPos = new float[0];
		prevPos = new float[0];
		velocity = new float[0];
		allocateParticleBuffer(capacity);

		terrain = new TerrainCollider(scene);
		playerBody = new PlayerBody(scene);
		entityBodies = new EntityBodies(scene);
		rigids = new RigidBodies(scene);
		// Lava is not kept as a volume: its thick, slow mass would freeze in steps.
		if (config.stillWaterVolume && kind == FluidKind.WATER) {
			frozen = new FrozenColumns(params.spacing(), params.particlesPerAxis(), params.solidRestOffset());
			frozenCollider = new FrozenCollider(scene, params.spacing(), params.solidRestOffset() - 0.5f * params.spacing());
		} else {
			frozen = null;
			frozenCollider = null;
		}
	}

	private boolean frozenEmpty() {
		return frozen == null || frozen.isEmpty();
	}

	/** Notes water added at {@code x, z} (relative), so the next step measures the columns there. */
	private void noteAdded(float x0, float z0, float x1, float z1) {
		addedMinX = Math.min(addedMinX, x0);
		addedMaxX = Math.max(addedMaxX, x1);
		addedMinZ = Math.min(addedMinZ, z0);
		addedMaxZ = Math.max(addedMaxZ, z1);
	}

	/**
	 * Fastest the water may move: up to {@link #MAX_WATER_SPEED}, but never more than a quarter of a
	 * block per sub-step, so collisions with blocks stay reliable.
	 */
	static float maxVelocity(FluidParameters params, int substeps) {
		float perSubstep = MAX_STEP_DISTANCE * substeps / TICK_SECONDS;
		return Math.max(params.maxVelocity(), Math.min(MAX_WATER_SPEED, perSubstep));
	}

	/**
	 * (Re)creates the GPU particle buffer and its pinned host staging memory with room for
	 * {@code newCapacity} particles, keeping the {@code activeCount} live particles. Used once at
	 * start and whenever the water outgrows the buffer.
	 */
	private void allocateParticleBuffer(int newCapacity) {
		PxVec4 newPositions = PxCudaTopLevelFunctions.allocPinnedHostBufferPxVec4(cuda, newCapacity);
		PxVec4 newVelocities = PxCudaTopLevelFunctions.allocPinnedHostBufferPxVec4(cuda, newCapacity);
		NativeObject newPhases = PxCudaTopLevelFunctions.allocPinnedHostBufferPxU32(cuda, newCapacity);
		FloatBuffer newPos = MemoryUtil.memFloatBuffer(newPositions.getAddress(), newCapacity * 4);
		FloatBuffer newVel = MemoryUtil.memFloatBuffer(newVelocities.getAddress(), newCapacity * 4);
		IntBuffer phases = MemoryUtil.memIntBuffer(newPhases.getAddress(), newCapacity);

		float invMass = 1f / params.particleMass();
		for (int i = 0; i < newCapacity; i++) {
			if (i < activeCount) {
				for (int c = 0; c < 4; c++) {
					newPos.put(i * 4 + c, hostPos.get(i * 4 + c));
					newVel.put(i * 4 + c, hostVel.get(i * 4 + c));
				}
			} else {
				newPos.put(i * 4, 0f).put(i * 4 + 1, 0f).put(i * 4 + 2, 0f).put(i * 4 + 3, invMass);
				newVel.put(i * 4, 0f).put(i * 4 + 1, 0f).put(i * 4 + 2, 0f).put(i * 4 + 3, 0f);
			}
			phases.put(i, phase);
		}

		// Foam, spray and bubbles are water's; lava has none.
		int newMaxFoam = config.foam && kind == FluidKind.WATER ? Math.min(newCapacity / 2, 262_144) : 0;
		PxParticleBuffer newBuffer;
		PxParticleAndDiffuseBuffer newDiffuseBuffer = null;
		try (MemoryStack mem = MemoryStack.stackPush()) {
			if (newMaxFoam > 0) {
				// Foam, spray and bubbles: PhysX spawns these "diffuse" particles where the water has
				// high kinetic energy or traps air, and carries them with the flow.
				PxDiffuseParticleParams diffuse = PxDiffuseParticleParams.createAt(mem, MemoryStack::nmalloc);
				diffuse.setThreshold(config.foamThreshold());
				diffuse.setLifetime(config.foamLifetime);
				diffuse.setBubbleDrag(0.9f);
				diffuse.setBuoyancy(0.9f);
				diffuse.setAirDrag(0.0f);
				diffuse.setKineticEnergyWeight(0.01f);
				diffuse.setPressureWeight(1.0f);
				diffuse.setDivergenceWeight(10.0f);
				diffuse.setUseAccurateVelocity(false);

				PxParticleAndDiffuseBufferDesc desc = PxParticleAndDiffuseBufferDesc.createAt(mem, MemoryStack::nmalloc);
				desc.setMaxParticles(newCapacity);
				desc.setNumActiveParticles(activeCount);
				desc.setPositions(newPositions);
				desc.setVelocities(newVelocities);
				desc.setPhases(NativeArrayHelpers.voidToU32Ptr(newPhases));
				desc.setMaxDiffuseParticles(newMaxFoam);
				desc.setMaxActiveDiffuseParticles(newMaxFoam);
				desc.setDiffuseParams(diffuse);
				newDiffuseBuffer = PxCudaTopLevelFunctions.CreateAndPopulateParticleAndDiffuseBuffer(desc, cuda);
				newBuffer = newDiffuseBuffer;
			} else {
				PxParticleBufferDesc desc = PxParticleBufferDesc.createAt(mem, MemoryStack::nmalloc);
				desc.setMaxParticles(newCapacity);
				desc.setNumActiveParticles(activeCount);
				desc.setPositions(newPositions);
				desc.setVelocities(newVelocities);
				desc.setPhases(NativeArrayHelpers.voidToU32Ptr(newPhases));
				newBuffer = PxCudaTopLevelFunctions.CreateAndPopulateParticleBuffer(desc, cuda);
			}
		}

		// Swap in the new buffer and release the old one.
		if (buffer != null) {
			particleSystem.removeParticleBuffer(buffer);
			buffer.release();
			PxCudaTopLevelFunctions.freePinnedHostBufferPxVec4(cuda, hostPositions);
			PxCudaTopLevelFunctions.freePinnedHostBufferPxVec4(cuda, hostVelocities);
			PxCudaTopLevelFunctions.freePinnedHostBufferPxU32(cuda, NativeArrayHelpers.voidToU32Ptr(hostPhases));
		}
		if (hostDiffuse != null) {
			PxCudaTopLevelFunctions.freePinnedHostBufferPxVec4(cuda, hostDiffuse);
			hostDiffuse = null;
			hostDiffuseView = null;
		}

		buffer = newBuffer;
		diffuseBuffer = newDiffuseBuffer;
		particleSystem.addParticleBuffer(buffer);
		hostPositions = newPositions;
		hostVelocities = newVelocities;
		hostPhases = newPhases;
		hostPos = newPos;
		hostVel = newVel;
		maxFoam = newMaxFoam;
		if (newMaxFoam > 0) {
			hostDiffuse = PxCudaTopLevelFunctions.allocPinnedHostBufferPxVec4(cuda, newMaxFoam);
			hostDiffuseView = MemoryUtil.memFloatBuffer(hostDiffuse.getAddress(), newMaxFoam * 4);
		}
		foamCount = 0;

		devicePositions = PxCudaTopLevelFunctions.pxVec4deviceptr(buffer.getPositionInvMasses());
		deviceVelocities = PxCudaTopLevelFunctions.pxVec4deviceptr(buffer.getVelocities());
		devicePhases = PxCudaTopLevelFunctions.pxU32deviceptr(buffer.getPhases());

		nextPos = new float[newCapacity * STRIDE];
		nextVel = new float[newCapacity * STRIDE];
		currPos = java.util.Arrays.copyOf(currPos, newCapacity * STRIDE);
		prevPos = java.util.Arrays.copyOf(prevPos, newCapacity * STRIDE);
		velocity = java.util.Arrays.copyOf(velocity, newCapacity * STRIDE);
		capacity = newCapacity;
	}

	/** Makes room for {@code extra} more particles if the limit allows; returns the free space. */
	private int ensureRoom(int extra) {
		int free = capacity - activeCount;
		if (free >= extra || capacity >= limit) {
			return free;
		}
		int newCapacity = (int) Math.min((long) limit, Math.max((long) capacity * 2, (long) activeCount + extra));
		io.github.isnowx17.inertia.Inertia.LOGGER.info("Growing the water particle buffer: {} -> {}", capacity, newCapacity);
		allocateParticleBuffer(newCapacity);
		return capacity - activeCount;
	}

	private PxScene createScene() {
		try (MemoryStack mem = MemoryStack.stackPush()) {
			PxSceneDesc desc = PxSceneDesc.createAt(mem, MemoryStack::nmalloc, PhysXRuntime.physics().getTolerancesScale());
			desc.setGravity(PxVec3.createAt(mem, MemoryStack::nmalloc, 0f, GRAVITY, 0f));
			desc.setCpuDispatcher(PhysXRuntime.dispatcher());
			desc.setFilterShader(PxTopLevelFunctions.DefaultFilterShader());
			desc.setCudaContextManager(cuda);
			desc.setStaticStructure(PxPruningStructureTypeEnum.eDYNAMIC_AABB_TREE);
			desc.getFlags().raise(PxSceneFlagEnum.eENABLE_PCM);
			desc.getFlags().raise(PxSceneFlagEnum.eENABLE_GPU_DYNAMICS);
			desc.setBroadPhaseType(PxBroadPhaseTypeEnum.eGPU);
			desc.setSolverType(PxSolverTypeEnum.eTGS);

			// Room for particle-vs-block contacts on the GPU; if this overflows, water leaks through
			// the ground at high particle counts.
			PxGpuDynamicsMemoryConfig gpuMemory = new PxGpuDynamicsMemoryConfig();
			gpuMemory.setMaxParticleContacts((int) Math.min(Integer.MAX_VALUE / 2, Math.max((long) gpuMemory.getMaxParticleContacts(), (long) limit * 2)));
			desc.setGpuDynamicsConfig(gpuMemory);
			try {
				return PhysXRuntime.physics().createScene(desc);
			} finally {
				gpuMemory.destroy();
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Spawning
	// ------------------------------------------------------------------------------------------

	/**
	 * Adds one block worth of water centred on {@code center}.
	 *
	 * @return number of particles actually added (fewer than a full block when near the limit)
	 */
	public int spawnBlock(Vec3 center, Vec3 initialVelocity) {
		return spawnBlock(center, initialVelocity, Integer.MAX_VALUE);
	}

	/** {@link #spawnBlock(Vec3, Vec3)} with at most {@code maxParticles} particles. */
	public int spawnBlock(Vec3 center, Vec3 initialVelocity, int maxParticles) {
		awaitStep();
		// Re-centre the scene whenever it is empty. While water exists the origin stays put; all
		// water is within the despawn distance of the player, so floats remain precise.
		if ((activeCount == 0 && frozenEmpty() && rigids.isEmpty()) || !originSet) {
			rebase(BlockPos.containing(center));
		}

		int perAxis = params.particlesPerAxis();
		int wanted = Math.min(params.particlesPerBlock(), maxParticles);
		int count = Math.min(wanted, ensureRoom(wanted));
		if (count <= 0) {
			return 0;
		}
		wake();

		float spacing = params.spacing();
		float jitter = spacing * 0.05f;
		float baseX = (float) (center.x - originX) - 0.5f + spacing * 0.5f;
		float baseY = (float) (center.y - originY) - 0.5f + spacing * 0.5f;
		float baseZ = (float) (center.z - originZ) - 0.5f + spacing * 0.5f;
		float invMass = 1f / params.particleMass();
		float vx = (float) initialVelocity.x;
		float vy = (float) initialVelocity.y;
		float vz = (float) initialVelocity.z;
		ThreadLocalRandom random = ThreadLocalRandom.current();

		int start = activeCount;
		int written = 0;
		outer:
		for (int y = 0; y < perAxis; y++) {
			for (int z = 0; z < perAxis; z++) {
				for (int x = 0; x < perAxis; x++) {
					if (written >= count) {
						break outer;
					}
					int i = start + written;
					float px = baseX + x * spacing + (random.nextFloat() - 0.5f) * jitter;
					float py = baseY + y * spacing + (random.nextFloat() - 0.5f) * jitter;
					float pz = baseZ + z * spacing + (random.nextFloat() - 0.5f) * jitter;

					hostPos.put(i * 4, px).put(i * 4 + 1, py).put(i * 4 + 2, pz).put(i * 4 + 3, invMass);
					hostVel.put(i * 4, vx).put(i * 4 + 1, vy).put(i * 4 + 2, vz).put(i * 4 + 3, 0f);

					currPos[i * STRIDE] = px;
					currPos[i * STRIDE + 1] = py;
					currPos[i * STRIDE + 2] = pz;
					prevPos[i * STRIDE] = px;
					prevPos[i * STRIDE + 1] = py;
					prevPos[i * STRIDE + 2] = pz;
					velocity[i * STRIDE] = vx;
					velocity[i * STRIDE + 1] = vy;
					velocity[i * STRIDE + 2] = vz;
					written++;
				}
			}
		}

		uploadRange(start, written, true);
		activeCount += written;
		buffer.setNbActiveParticles(activeCount);
		version++;
		noteAdded(baseX, baseZ, baseX + 1f, baseZ + 1f);

		// Collision geometry around fresh water must exist before the next step.
		int sx = Mth.floor(center.x) >> 4;
		int sy = Mth.floor(center.y) >> 4;
		int sz = Mth.floor(center.z) >> 4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					pendingUrgentSections.add(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
				}
			}
		}
		return written;
	}

	/**
	 * The water as it is now, copied for saving with the world: the particles of the newest step and
	 * the still water kept as a volume. Client thread; does not wait for a running step.
	 *
	 * @param sources the flowing sources (world {@code BlockPos.asLong}), saved with it
	 */
	public io.github.isnowx17.inertia.util.WaterSaveFile.State capture(long[] sources) {
		int n = activeCount;
		float[] particles = new float[n * io.github.isnowx17.inertia.util.WaterSaveFile.PARTICLE_FLOATS];
		for (int i = 0; i < n; i++) {
			int o = i * 6;
			int p = i * STRIDE;
			particles[o] = currPos[p];
			particles[o + 1] = currPos[p + 1];
			particles[o + 2] = currPos[p + 2];
			particles[o + 3] = velocity[p];
			particles[o + 4] = velocity[p + 1];
			particles[o + 5] = velocity[p + 2];
		}
		int columnCount = frozen != null ? frozen.columnCount() : 0;
		int[] columns = new int[columnCount * io.github.isnowx17.inertia.util.WaterSaveFile.COLUMN_INTS];
		float[] walls = new float[columnCount * 4];
		if (frozen != null) {
			int k = 0;
			for (FrozenColumns.Column c : frozen.columns()) {
				columns[k * 5] = c.x;
				columns[k * 5 + 1] = c.z;
				columns[k * 5 + 2] = c.floor;
				columns[k * 5 + 3] = c.layers;
				columns[k * 5 + 4] = c.count;
				for (int side = 0; side < 4; side++) {
					walls[k * 4 + side] = c.wallTop(side);
				}
				k++;
			}
		}
		return new io.github.isnowx17.inertia.util.WaterSaveFile.State(originX, originY, originZ, params.spacing(), n, particles,
			columnCount, columns, walls, sources);
	}

	/**
	 * Puts saved water back into this simulation while it has no moving water. Water saved with another
	 * particle spacing keeps its volume: each saved particle becomes as many particles as it holds
	 * water. As much as fits within the particle limit.
	 *
	 * @return particles added (not counting the still water kept as a volume)
	 */
	public int restore(io.github.isnowx17.inertia.util.WaterSaveFile.State saved) {
		awaitStep();
		if (activeCount > 0) {
			return 0;
		}
		if (frozenEmpty() || !originSet) {
			rebase(new BlockPos(saved.originX(), saved.originY(), saved.originZ()));
		}
		// Saved relative to its own origin: moved to this one (the water stays where it was in the world).
		float shiftX = saved.originX() - originX;
		float shiftY = saved.originY() - originY;
		float shiftZ = saved.originZ() - originZ;
		float spacing = params.spacing();
		double ratio = Math.pow(saved.spacing() / spacing, 3);
		int wanted = (int) Math.min(Integer.MAX_VALUE / 2, Math.ceil(saved.particleCount() * ratio));
		int room = Math.min(wanted, ensureRoom(wanted));
		if (room < wanted) {
			io.github.isnowx17.inertia.Inertia.LOGGER.warn("Saved water does not fit the particle limit: {} of {} particles restored", room, wanted);
		}
		float invMass = 1f / params.particleMass();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		LongOpenHashSet sections = new LongOpenHashSet();
		float minX = Float.POSITIVE_INFINITY;
		float minZ = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float maxZ = Float.NEGATIVE_INFINITY;
		float[] p = saved.particles();
		int start = activeCount;
		int written = 0;
		for (int j = 0; j < saved.particleCount() && written < room; j++) {
			int o = j * io.github.isnowx17.inertia.util.WaterSaveFile.PARTICLE_FLOATS;
			int copies = (int) ratio + (random.nextDouble() < ratio - Math.floor(ratio) ? 1 : 0);
			for (int c = 0; c < copies && written < room; c++) {
				// Copies of one saved particle spread over the room it held.
				float spread = c == 0 ? 0f : saved.spacing() * 0.5f;
				float px = p[o] + shiftX + (random.nextFloat() - 0.5f) * spread;
				float py = p[o + 1] + shiftY + (random.nextFloat() - 0.5f) * spread;
				float pz = p[o + 2] + shiftZ + (random.nextFloat() - 0.5f) * spread;
				int i = start + written;
				hostPos.put(i * 4, px).put(i * 4 + 1, py).put(i * 4 + 2, pz).put(i * 4 + 3, invMass);
				hostVel.put(i * 4, p[o + 3]).put(i * 4 + 1, p[o + 4]).put(i * 4 + 2, p[o + 5]).put(i * 4 + 3, 0f);
				currPos[i * STRIDE] = px;
				currPos[i * STRIDE + 1] = py;
				currPos[i * STRIDE + 2] = pz;
				prevPos[i * STRIDE] = px;
				prevPos[i * STRIDE + 1] = py;
				prevPos[i * STRIDE + 2] = pz;
				velocity[i * STRIDE] = p[o + 3];
				velocity[i * STRIDE + 1] = p[o + 4];
				velocity[i * STRIDE + 2] = p[o + 5];
				minX = Math.min(minX, px);
				maxX = Math.max(maxX, px);
				minZ = Math.min(minZ, pz);
				maxZ = Math.max(maxZ, pz);
				sections.add(SectionPos.asLong(Mth.floor(px + originX) >> 4, Mth.floor(py + originY) >> 4, Mth.floor(pz + originZ) >> 4));
				written++;
			}
		}
		if (written > 0) {
			uploadRange(start, written, true);
			activeCount += written;
			buffer.setNbActiveParticles(activeCount);
			noteAdded(minX, minZ, maxX + 1f, maxZ + 1f);
		}

		// The still water kept as a volume: as it was, in layers of this spacing.
		int[] cols = saved.columns();
		for (int k = 0; k < saved.columnCount(); k++) {
			int x = cols[k * 5];
			int z = cols[k * 5 + 1];
			int floor = cols[k * 5 + 2];
			int layers = Math.max(1, Math.round(cols[k * 5 + 3] * saved.spacing() / spacing));
			int count = (int) Math.round(cols[k * 5 + 4] * ratio);
			if (frozen != null) {
				frozen.restore(x, z, floor, layers, count, saved.walls(), k * 4);
			} else {
				// Without still water kept as a volume it becomes particles again (as far as they fit).
				int free = ensureRoom(count);
				if (free > 0) {
					int from = activeCount;
					int added = spawnThawed(new FrozenColumns.Thaw(x, z, floor, 0, layers, Math.min(count, free), false));
					uploadRange(from, added, true);
					buffer.setNbActiveParticles(activeCount);
					written += added;
				}
			}
			for (int y = floor; y <= floor + layers * spacing + 1; y += 8) {
				sections.add(SectionPos.asLong((x + originX) >> 4, (y + originY) >> 4, (z + originZ) >> 4));
			}
		}
		// Collision geometry around all of it must exist before the next step.
		for (long section : sections.toLongArray()) {
			int sx = SectionPos.x(section);
			int sy = SectionPos.y(section);
			int sz = SectionPos.z(section);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						pendingUrgentSections.add(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
					}
				}
			}
		}
		version++;
		wake();
		return written;
	}

	/**
	 * Lets a steady flow of water out of the block of a flowing water source: every tick the same
	 * amount of water ({@code blocksPerSecond}) appears in the free places of the block, the top
	 * layer first, moving down at {@code blocksPerSecond} blocks per second. While the stream falls
	 * freely through the block, the water goes right on top of it instead (see
	 * {@link #continueStream}). When the
	 * water cannot get away (a pool below), the block fills up and the new water comes gently, so
	 * the water spreads out like from a water source block. A full block gets nothing. Called every
	 * tick for every source.
	 *
	 * <p>Which places are free comes from the latest physics step, which measures the blocks of the
	 * sources set with {@link #setSources}. A new source therefore starts one tick later, unless there
	 * is no water at all yet.
	 *
	 * @param maxParticles at most this many particles are added
	 * @return number of particles added
	 */
	public int fillSource(BlockPos cell, float blocksPerSecond, int maxParticles) {
		awaitStep();
		if ((activeCount == 0 && frozenEmpty() && rigids.isEmpty()) || !originSet) {
			rebase(cell);
		}
		int perAxis = params.particlesPerAxis();
		int slots = perAxis * perAxis * perAxis;
		SourceCell measured = sourceCells.get(cell.asLong());
		long[] taken;
		// A steady stream: the water leaves the block as fast as it is added, so it never piles up
		// into a block of water that would then fall as one lump.
		float flowSpeed = Math.max(SOURCE_MIN_SPEED, blocksPerSecond);
		float vx = 0f;
		float vy = -flowSpeed;
		float vz = 0f;
		if (measured != null && measured.slots.length >= SourceSlots.words(perAxis)) {
			taken = measured.slots;
			// A stream falling through fills much of the block too; it does not count as pooled water.
			if (measured.count >= slots * SOURCE_POOLING_FILL && measured.sumY / measured.count > -0.5f * flowSpeed) {
				// The water cannot get away (a pool below): add it gently, moving with the water there.
				vx = measured.sumX / measured.count;
				vy = Math.min(measured.sumY / measured.count, 0f);
				vz = measured.sumZ / measured.count;
			} else if (measured.count > 0) {
				return continueStream(cell, measured, flowSpeed, maxParticles);
			}
		} else if (activeCount == 0) {
			taken = new long[SourceSlots.words(perAxis)];
		} else {
			return 0; // not measured yet: never put particles into water that may already be there
		}

		int wanted = Math.min(sourceParticlesPerTick(blocksPerSecond), maxParticles);
		int budget = Math.min(wanted, ensureRoom(wanted));
		if (budget <= 0) {
			return 0;
		}
		wake();

		float spacing = 1f / perAxis;
		float jitter = spacing * 0.05f;
		float baseX = cell.getX() - originX + spacing * 0.5f;
		float baseY = cell.getY() - originY + spacing * 0.5f;
		float baseZ = cell.getZ() - originZ + spacing * 0.5f;
		float invMass = 1f / params.particleMass();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int start = activeCount;
		int written = 0;
		// Top layer first: each tick's water follows right behind the water of the tick before.
		for (int slot = slots - 1; slot >= 0 && written < budget; slot--) {
			if (SourceSlots.isTaken(taken, slot)) {
				continue;
			}
			int ix = SourceSlots.x(slot, perAxis);
			int iy = SourceSlots.y(slot, perAxis);
			int iz = SourceSlots.z(slot, perAxis);
			int i = start + written;
			float px = baseX + ix * spacing + (random.nextFloat() - 0.5f) * jitter;
			float py = baseY + iy * spacing + (random.nextFloat() - 0.5f) * jitter;
			float pz = baseZ + iz * spacing + (random.nextFloat() - 0.5f) * jitter;
			hostPos.put(i * 4, px).put(i * 4 + 1, py).put(i * 4 + 2, pz).put(i * 4 + 3, invMass);
			hostVel.put(i * 4, vx).put(i * 4 + 1, vy).put(i * 4 + 2, vz).put(i * 4 + 3, 0f);
			currPos[i * STRIDE] = px;
			currPos[i * STRIDE + 1] = py;
			currPos[i * STRIDE + 2] = pz;
			prevPos[i * STRIDE] = px;
			prevPos[i * STRIDE + 1] = py;
			prevPos[i * STRIDE + 2] = pz;
			velocity[i * STRIDE] = vx;
			velocity[i * STRIDE + 1] = vy;
			velocity[i * STRIDE + 2] = vz;
			SourceSlots.mark(taken, slot);
			written++;
		}
		if (written == 0) {
			return 0;
		}

		uploadRange(start, written, true);
		activeCount += written;
		buffer.setNbActiveParticles(activeCount);
		version++;

		markUrgent(cell);
		return written;
	}

	/** The surface around a source is rebuilt first. */
	private void markUrgent(BlockPos cell) {
		noteAdded(cell.getX() - originX, cell.getZ() - originZ, cell.getX() - originX + 1, cell.getZ() - originZ + 1);
		int sx = cell.getX() >> 4;
		int sy = cell.getY() >> 4;
		int sz = cell.getZ() >> 4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					pendingUrgentSections.add(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
				}
			}
		}
	}

	/**
	 * Lets the water out of a source whose stream is falling freely: the new water goes right on top
	 * of the stream, one particle spacing above its highest particle, up to the top of the block, and
	 * moves like a steady stream would there (a little slower than the stream's top, which left the
	 * block earlier). Placed on fixed places in the block instead, each tick's water lands a little too
	 * close to the water of the tick before or a little too far from it; the stream then breaks into
	 * one lump per tick as it falls.
	 */
	private int continueStream(BlockPos cell, SourceCell measured, float flowSpeed, int maxParticles) {
		int perAxis = params.particlesPerAxis();
		int perLayer = perAxis * perAxis;
		float spacing = 1f / perAxis;
		// Fill the room between the stream's top and the top of the block with whole layers one spacing
		// apart; what is left of the room is filled next tick. (A partly filled layer would be a weak
		// place where the stream tears.)
		float room = (1f - spacing * 0.1f) - measured.top;
		int layers = Math.min((int) Math.floor(room / spacing), 2 * Math.max(1, sourceParticlesPerTick(flowSpeed) / perLayer));
		int wanted = layers * perLayer;
		int budget = Math.min(Math.min(wanted, maxParticles), ensureRoom(Math.min(wanted, maxParticles)));
		if (budget <= 0 || layers <= 0) {
			return 0;
		}
		wake();
		// Less water than asked for (the GPU's limit): a thinner stream, the places nearest the middle
		// of every layer, so the stream stays one piece.
		int perLayerCount = Math.max(1, budget / layers);
		layers = Math.min(layers, budget / perLayerCount);
		int[] order = centerOrder(perAxis);

		float jitter = spacing * 0.05f;
		float baseX = cell.getX() - originX + spacing * 0.5f;
		float baseY = cell.getY() - originY + measured.top;
		float baseZ = cell.getZ() - originZ + spacing * 0.5f;
		// Moving like a steady stream that leaves the top of the block at the flow speed: the further
		// down in the block, the faster.
		float startY = 1f - spacing * 0.5f;
		float centerX = cell.getX() - originX + 0.5f;
		float centerZ = cell.getZ() - originZ + 0.5f;
		float invMass = 1f / params.particleMass();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int start = activeCount;
		int written = 0;
		for (int layer = 1; layer <= layers; layer++) {
			float h = layer * spacing;
			float fallen = Math.max(0f, startY - (measured.top + h));
			float vy = -(float) Math.sqrt(flowSpeed * flowSpeed - 2f * GRAVITY * fallen);
			// A falling stream gets faster and so thinner: water moves towards its middle at
			// distance * g / (2 * speed). The particles cannot pull each other in, so they get that
			// motion here; otherwise the stream stays as wide as the block, stretches and breaks up.
			float narrowing = 0.5f * -GRAVITY / (2f * -vy);
			for (int k = 0; k < perLayerCount; k++) {
				int slot = order[k];
				int i = start + written;
				float px = baseX + (slot % perAxis) * spacing + (random.nextFloat() - 0.5f) * jitter;
				float py = baseY + h + (random.nextFloat() - 0.5f) * jitter;
				float pz = baseZ + (slot / perAxis) * spacing + (random.nextFloat() - 0.5f) * jitter;
				float vx = (centerX - px) * narrowing;
				float vz = (centerZ - pz) * narrowing;
				hostPos.put(i * 4, px).put(i * 4 + 1, py).put(i * 4 + 2, pz).put(i * 4 + 3, invMass);
				hostVel.put(i * 4, vx).put(i * 4 + 1, vy).put(i * 4 + 2, vz).put(i * 4 + 3, 0f);
				currPos[i * STRIDE] = px;
				currPos[i * STRIDE + 1] = py;
				currPos[i * STRIDE + 2] = pz;
				prevPos[i * STRIDE] = px;
				prevPos[i * STRIDE + 1] = py;
				prevPos[i * STRIDE + 2] = pz;
				velocity[i * STRIDE] = vx;
				velocity[i * STRIDE + 1] = vy;
				velocity[i * STRIDE + 2] = vz;
				written++;
			}
		}
		// The new water is part of the block now: a second call in this tick must not fill it again.
		measured.top += layers * spacing;
		uploadRange(start, written, true);
		activeCount += written;
		buffer.setNbActiveParticles(activeCount);
		version++;
		markUrgent(cell);
		return written;
	}

	private int[] centerOrder = new int[0];

	/** The places of a layer of {@code perAxis} by {@code perAxis} particles, nearest the middle first. */
	private int[] centerOrder(int perAxis) {
		if (centerOrder.length != perAxis * perAxis) {
			Integer[] slots = new Integer[perAxis * perAxis];
			for (int k = 0; k < slots.length; k++) {
				slots[k] = k;
			}
			float middle = (perAxis - 1) * 0.5f;
			java.util.Arrays.sort(slots, java.util.Comparator.comparingDouble(k -> {
				float dx = k % perAxis - middle;
				float dz = k / perAxis - middle;
				return dx * dx + dz * dz + k * 1e-6;
			}));
			centerOrder = java.util.Arrays.stream(slots).mapToInt(Integer::intValue).toArray();
		}
		return centerOrder;
	}

	/** Particles a flowing water source adds per tick. */
	public int sourceParticlesPerTick(float blocksPerSecond) {
		return Math.max(1, Math.round(blocksPerSecond * params.particlesPerBlock() * TICK_SECONDS));
	}

	/**
	 * Makes room for new water at the GPU's limit by removing up to {@code wanted} particles of calm
	 * water, the same amount from places spread over all the water: a pool simply stops rising while
	 * a source keeps pouring. Each place loses only a few particles at a time, which the water closes
	 * right away. While the steps are still too long (the GPU got slower, or busier drawing) a little
	 * more is removed than asked for, until the amount of water fits what the GPU can do. Client
	 * thread, between steps.
	 *
	 * @return number of particles there is room for now, at most {@code wanted}
	 */
	public int recycleCalmWater(int wanted) {
		awaitStep();
		if (wanted <= 0 || activeCount == 0) {
			return 0;
		}
		// Deep calm water freezes instead (see FrozenColumns): the room it made is what the new water
		// gets, and none is taken away, unless the steps take too long all the same.
		if (frozen != null) {
			if (stepLoadMillis <= GPU_OVERLOADED_MILLIS) {
				return frozen.takeFreedRoom(wanted);
			}
			return recycleDeep(wanted);
		}
		return recycleDeep(wanted);
	}

	/** {@link #recycleCalmWater} by taking deep calm water away. */
	private int recycleDeep(int wanted) {
		recycled.clear();
		int asked = wanted;
		if (stepLoadMillis > GPU_OVERLOADED_MILLIS) {
			wanted += wanted / 4;
		}
		// Only where the water is deep: there the water closes over the gap and the level sinks a
		// little, where thin water spreading over the ground would visibly vanish. Deepest first.
		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap columns = scan.columns;
		int deepest = 0;
		for (it.unimi.dsi.fastutil.ints.IntIterator it = columns.values().iterator(); it.hasNext(); ) {
			deepest = Math.max(deepest, it.nextInt());
		}
		int perLayer = params.particlesPerAxis() * params.particlesPerAxis();
		int[] depths = {deepest / 2, deepest / 4};
		for (int pass = 0; pass < depths.length && recycled.size() < wanted; pass++) {
			int minDepth = Math.max(RECYCLE_MIN_LAYERS * perLayer, depths[pass]);
			if (deepest < minDepth) {
				break;
			}
			recycleFrom(columns, minDepth, wanted);
			// Ascending and each once (a later pass may find the same particles again).
			int[] list = recycled.elements();
			it.unimi.dsi.fastutil.ints.IntArrays.quickSort(list, 0, recycled.size());
			int unique = 0;
			for (int k = 0; k < recycled.size(); k++) {
				if (unique == 0 || list[k] != list[unique - 1]) {
					list[unique++] = list[k];
				}
			}
			recycled.size(unique);
		}
		int removed = recycled.size();
		if (removed > 0) {
			removeLostParticles(recycled);
			recycled.clear();
			version++;
		}
		return Math.min(removed, asked);
	}

	/** Adds calm particles in columns holding at least {@code minDepth} particles to {@link #recycled}. */
	private void recycleFrom(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap columns, int minDepth, int wanted) {
		float calm = CALM_SPEED * CALM_SPEED;
		int missing = wanted - recycled.size();
		int perGroup = missing / RECYCLE_GROUPS + 1;
		int spacing = Math.max(1, activeCount / RECYCLE_GROUPS);
		// Golden-ratio steps through the spacing: over time every particle's turn comes.
		recycleOffset = (int) ((recycleOffset + spacing * 0.618f + 1) % spacing);
		float[] v = velocity;
		float[] p = currPos;
		long lastColumn = Long.MIN_VALUE;
		boolean lastDeep = false;
		for (int g = 0; g < RECYCLE_GROUPS && recycled.size() < wanted; g++) {
			int start = recycleOffset + g * spacing;
			int end = Math.min(activeCount, Math.min(start + spacing, start + perGroup * RECYCLE_SEARCH));
			int found = 0;
			for (int i = start; i < end && found < perGroup && recycled.size() < wanted; i++) {
				int o = i * STRIDE;
				if (v[o] * v[o] + v[o + 1] * v[o + 1] + v[o + 2] * v[o + 2] >= calm) {
					continue;
				}
				long key = ParticleScan.column(p[o], p[o + 2]);
				if (key != lastColumn) {
					lastColumn = key;
					lastDeep = columns.get(key) >= minDepth;
				}
				if (lastDeep) {
					recycled.add(i);
					found++;
				}
			}
		}
	}

	/**
	 * The blocks of the flowing water sources (as {@link BlockPos#asLong}); the next physics step
	 * measures which places in them are taken. Client thread, before {@link #tick}.
	 */
	public void setSources(long[] cells) {
		requestedSources = cells.clone();
	}

	/** What a physics step found in the block of a flowing source. */
	private static final class SourceCell {
		final long key;
		final int x;
		final int y;
		final int z;
		/** One bit per particle place (bottom layer first) that holds a particle. */
		final long[] slots;
		int count;
		float sumX;
		float sumY;
		float sumZ;
		/** Height of the highest particle in the block (0 to 1), and its vertical velocity. */
		float top;
		float topVy;

		SourceCell(long key, int x, int y, int z, int slotCount) {
			this.key = key;
			this.x = x;
			this.y = y;
			this.z = z;
			this.slots = new long[(slotCount + 63) / 64];
		}
	}

	private void rebase(BlockPos around) {
		// Foam still alive on the GPU is expressed relative to the old origin; hide it until it expired.
		if (foamCount > 0) {
			foamHiddenUntilTick = tick + (long) Math.ceil(config.foamLifetime * 20f) + 1;
			foamCount = 0;
		}
		// Align the origin to a chunk section so section actors keep integer offsets.
		int x = around.getX() & ~15;
		int y = around.getY() & ~15;
		int z = around.getZ() & ~15;
		originX = x;
		originY = y;
		originZ = z;
		originSet = true;
		terrain.setOrigin(x, y, z);
	}

	/** Copies particles {@code [start, start + count)} from pinned host memory to the GPU. */
	private void uploadRange(int start, int count, boolean includePhases) {
		if (count <= 0) {
			return;
		}
		cuda.acquireContext();
		try {
			long offset4 = (long) start * VEC4_BYTES;
			int bytes4 = count * VEC4_BYTES;
			cuda.getCudaContext().memcpyHtoD(devicePositions + offset4, PxVec4.wrapPointer(hostPositions.getAddress() + offset4), bytes4);
			cuda.getCudaContext().memcpyHtoD(deviceVelocities + offset4, PxVec4.wrapPointer(hostVelocities.getAddress() + offset4), bytes4);
			if (includePhases) {
				long offset1 = (long) start * U32_BYTES;
				cuda.getCudaContext().memcpyHtoD(devicePhases + offset1, PxVec4.wrapPointer(hostPhases.getAddress() + offset1), count * U32_BYTES);
			}
		} finally {
			cuda.releaseContext();
		}
		buffer.raiseFlags(PxParticleBufferFlagEnum.eUPDATE_POSITION);
		buffer.raiseFlags(PxParticleBufferFlagEnum.eUPDATE_VELOCITY);
		if (includePhases) {
			buffer.raiseFlags(PxParticleBufferFlagEnum.eUPDATE_PHASE);
		}
	}

	// ------------------------------------------------------------------------------------------
	// Ticking
	// ------------------------------------------------------------------------------------------

	/**
	 * Advances the simulation by one game tick. Must be called on the client thread.
	 *
	 * <p>The GPU step of the previous tick is collected first; then collision geometry and the
	 * player body are updated and the next step is started on the physics thread. Rendering
	 * therefore never waits for the GPU: the results arrive one tick later, which the tick
	 * interpolation already hides.
	 */
	public void tick(ClientLevel level, Player player) {
		awaitStep();
		if (activeCount > 0 && sleep(level, player)) {
			// No steps, but the foam fades as the game runs (it gets no new data).
			if (config.foam && sleepTicks < Math.ceil(config.foamLifetime * FOAM_LONGEST_LIFE / TICK_SECONDS) + 1) {
				sleepTicks++;
				tick++;
				version++;
			}
			return;
		}
		tick++;
		net.minecraft.world.phys.AABB water = waterBox();
		nearEntities = config.entityInteraction && water != null ? EntityBodies.near(level, player, water) : java.util.List.of();
		if (frozen != null) {
			updateFrozen(level, player);
		}
		if (activeCount == 0 && rigids.isEmpty()) {
			foamCount = 0;
			terrain.update(level, new LongOpenHashSet(), new LongOpenHashSet(), tick);
			playerBody.remove();
			entityBodies.clear();
			stepBodies = EntityBodies.Step.EMPTY;
			measuredBodies = EntityBodies.Step.EMPTY;
			resetSolver();
			wake();
			return;
		}

		updateTerrain(level);
		playerBody.update(player, originX, originY, originZ, config.playerInteraction && !playerRiding && !playerNearBodies);
		stepBodies = entityBodies.update(nearEntities, originX, originY, originZ, Math.max(PLAYER_SHELL, params.spacing() * 3));

		ParticleScan.Settings s = scanSettings;
		s.minY = level.getMinY() - BELOW_WORLD_MARGIN - originY;
		s.playerX = (float) (player.getX() - originX);
		s.playerY = (float) (player.getY() - originY);
		s.playerZ = (float) (player.getZ() - originZ);
		s.maxDistSq = (float) config.despawnDistance * config.despawnDistance;
		s.movingSpeedSq = MOVING_SPEED * MOVING_SPEED;
		// The water around the player is measured in a thin shell around their bounding box: their
		// kinematic body keeps the water out of the box itself.
		s.measurePlayer = config.playerInteraction && !player.isSpectator();
		net.minecraft.world.phys.AABB box = player.getBoundingBox();
		double margin = Math.max(PLAYER_SHELL, params.spacing() * 3);
		s.shellMinX = (float) (box.minX - margin - originX);
		s.shellMinY = (float) (box.minY - margin - originY);
		s.shellMinZ = (float) (box.minZ - margin - originZ);
		s.shellMaxX = (float) (box.maxX + margin - originX);
		s.shellMaxY = (float) (box.maxY + margin - originY);
		s.shellMaxZ = (float) (box.maxZ + margin - originZ);
		s.sampleStride = Math.max(1, activeCount / TERRAIN_SAMPLES);
		s.sampleOffset = (int) (tick % s.sampleStride);
		s.originX = originX;
		s.originY = originY;
		s.originZ = originZ;
		int perAxis = params.particlesPerAxis();
		s.perAxis = perAxis;
		// Near the GPU's limit calm water may have to make room for new water: from the deepest places.
		s.measureColumns = config.keepRealTime && (gpuLimit || stepLoadMillis > GPU_ROOM_MILLIS);
		SourceCell[] cells = new SourceCell[requestedSources.length];
		int[] cellX = new int[cells.length];
		int[] cellY = new int[cells.length];
		int[] cellZ = new int[cells.length];
		for (int k = 0; k < cells.length; k++) {
			long key = requestedSources[k];
			cells[k] = new SourceCell(key, BlockPos.getX(key) - originX, BlockPos.getY(key) - originY, BlockPos.getZ(key) - originZ,
				perAxis * perAxis * perAxis);
			cellX[k] = cells[k].x;
			cellY[k] = cells[k].y;
			cellZ[k] = cells[k].z;
		}
		s.setSources(cellX, cellY, cellZ);
		s.calmSpeedSq = FrozenColumns.CALM_SPEED * FrozenColumns.CALM_SPEED;
		s.restlessSpeedSq = FrozenColumns.RESTLESS_SPEED * FrozenColumns.RESTLESS_SPEED;
		setGrid(s);
		s.setBodies(stepBodies.shells(), stepBodies.entities().size());
		stepSources = cells;
		stepParticleCount = activeCount;
		stepTickNumber = tick;
		stepHidesFoam = tick < foamHiddenUntilTick;
		stepSubsteps = solverLevels[solverLevel].substeps();
		stepSubstepSeconds = TICK_SECONDS / stepSubsteps;
		// The GPU surface (if used) takes the particles before the step starts changing them.
		// The particles of a step stand for the moment a tick after it was started: steps start with the
		// (even) game ticks, while their results come in whenever the GPU is done.
		long tickNanos = (long) (TICK_SECONDS * 1e9);
		io.github.isnowx17.inertia.client.gpu.GpuSurface.feed(kind.ordinal(), devicePositions, prevPos, activeCount,
			previousLaunchNanos != 0L ? previousLaunchNanos + tickNanos : 0L, finishedLaunchNanos + tickNanos,
			originX, originY, originZ, frozen != null ? frozen.boxes() : NO_BOXES);
		stepLaunchNanos = System.nanoTime();
		pendingStep = stepper.submit(this::step);
	}

	/**
	 * Whether the water sleeps this tick. Water that has been still for a while does not need the GPU:
	 * the steps stop until something happens to it, so it costs only its drawing (its ripples and
	 * caustics are drawn by the shader and keep moving). Water being added, a change of the collision
	 * geometry around it and the player coming close wake it up; so does the player moving so far away
	 * that water has to be removed.
	 */
	private boolean sleep(ClientLevel level, Player player) {
		if (!sleeping) {
			if (stillSteps < SLEEP_AFTER_STEPS || requestedSources.length > 0 || playerAtWater(player) || entitiesStir(level, player)
				|| rigids.awake()) {
				return false;
			}
			sleeping = true;
			sleepTicks = 0;
			// Spray would hang in the air; the bodies must not jump when the water wakes up. The entities
			// keep feeling the water as last measured: it is still.
			foamCount = 0;
			playerBody.remove();
			entityBodies.clear();
			sleepPlayerX = player.getX();
			sleepPlayerY = player.getY();
			sleepPlayerZ = player.getZ();
			sleepPlayerRange = config.despawnDistance - Math.sqrt(scan.farthestSq);
			return true;
		}
		double dx = player.getX() - sleepPlayerX;
		double dy = player.getY() - sleepPlayerY;
		double dz = player.getZ() - sleepPlayerZ;
		if (terrain.refreshDirty(level) || requestedSources.length > 0 || playerAtWater(player) || (frozen != null && frozen.hasChanges())
			|| entitiesStir(level, player) || rigids.awake()
			|| dx * dx + dy * dy + dz * dz > sleepPlayerRange * sleepPlayerRange) {
			wake();
			return false;
		}
		return true;
	}

	/** Whether an entity moves in or into the water (which then has to be simulated). */
	private boolean entitiesStir(ClientLevel level, Player player) {
		if (!config.entityInteraction) {
			return false;
		}
		net.minecraft.world.phys.AABB water = waterBox();
		if (water == null) {
			return false;
		}
		for (net.minecraft.world.entity.Entity entity : EntityBodies.near(level, player, water)) {
			double dx = entity.getX() - entity.xo;
			double dy = entity.getY() - entity.yo;
			double dz = entity.getZ() - entity.zo;
			if (dx * dx + dy * dy + dz * dz > ENTITY_WAKE_SPEED * ENTITY_WAKE_SPEED && touchesWater(entity.getBoundingBox().inflate(0.5))) {
				return true;
			}
		}
		return false;
	}

	/** The entities near the water at the latest tick. */
	java.util.List<net.minecraft.world.entity.Entity> nearEntities() {
		return nearEntities;
	}

	/** Whether a box (world coordinates) touches a block that held water in the latest step, or frozen water. */
	boolean touchesWater(net.minecraft.world.phys.AABB box) {
		if (frozen != null && frozen.touches((float) (box.minX - originX), (float) (box.minY - originY), (float) (box.minZ - originZ),
			(float) (box.maxX - originX), (float) (box.maxY - originY), (float) (box.maxZ - originZ))) {
			return true;
		}
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
			for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++) {
				for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
					if (scan.blocks.contains(BlockPos.asLong(x, y, z))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Box (world coordinates) around the water of the latest step and the frozen water; null without water. */
	private net.minecraft.world.phys.AABB waterBox() {
		float x0 = scan.minX;
		float y0 = scan.minY;
		float z0 = scan.minZ;
		float x1 = scan.maxX;
		float y1 = scan.maxY;
		float z1 = scan.maxZ;
		float[] f = frozen != null ? frozen.box() : null;
		if (f != null) {
			x0 = Math.min(x0, f[0]);
			y0 = Math.min(y0, f[1]);
			z0 = Math.min(z0, f[2]);
			x1 = Math.max(x1, f[3]);
			y1 = Math.max(y1, f[4]);
			z1 = Math.max(z1, f[5]);
		}
		if (!(x0 <= x1) || !(y0 <= y1) || !(z0 <= z1)) {
			return null;
		}
		return new net.minecraft.world.phys.AABB(x0 + originX, y0 + originY, z0 + originZ, x1 + originX, y1 + originY, z1 + originZ);
	}

	/** Whether the player is close to a block that held water in the latest step, and can touch it. */
	private boolean playerAtWater(Player player) {
		if (!config.playerInteraction || player.isSpectator()) {
			return false;
		}
		net.minecraft.world.phys.AABB box = player.getBoundingBox().inflate(PLAYER_WAKE_DISTANCE);
		if (frozen != null && frozen.touches((float) (box.minX - originX), (float) (box.minY - originY), (float) (box.minZ - originZ),
			(float) (box.maxX - originX), (float) (box.maxY - originY), (float) (box.maxZ - originZ))) {
			return true;
		}
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
			for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++) {
				for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
					if (scan.blocks.contains(BlockPos.asLong(x, y, z))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Something happened to the water: it has to be simulated again, and to be still for a while before it sleeps. */
	private void wake() {
		sleeping = false;
		stillSteps = 0;
	}

	/** Whether the water is still and the physics steps are paused. */
	public boolean sleeping() {
		return sleeping;
	}

	/**
	 * Whether the physics step of the latest tick is still running on the GPU. The manager then
	 * skips the water's part of the game tick instead of waiting, and starts the next step as soon
	 * as this one is done; everything that changes the simulation waits for the step otherwise.
	 */
	public boolean stepRunning() {
		java.util.concurrent.Future<?> step = pendingStep;
		return step != null && !step.isDone();
	}

	/**
	 * How fast the water moves compared to real time: 1 while a step is simulated every game tick,
	 * less when the GPU needs longer. The renderer moves the surface between steps at this speed.
	 */
	public void setTimeScale(float timeScale) {
		this.timeScale = Mth.clamp(timeScale, 0.01f, 1f);
	}

	public float timeScale() {
		return timeScale;
	}

	/** Thickness in blocks of the shell around the player's bounding box in which water is measured. */
	public static final double PLAYER_SHELL = 0.35;

	/** Physics thread: simulate the sub-steps and read the results back into the back buffers. */
	private void step() {
		long started = System.nanoTime();
		float dt = stepSubstepSeconds;
		for (int i = 0; i < stepSubsteps; i++) {
			scene.simulate(dt);
			scene.fetchResults(true);
		}
		scene.fetchResultsParticleSystem();
		long simulated = System.nanoTime();

		int count = stepParticleCount;
		cuda.acquireContext();
		try {
			int bytes = count * VEC4_BYTES;
			cuda.getCudaContext().memcpyDtoH(hostPositions, devicePositions, bytes);
			cuda.getCudaContext().memcpyDtoH(hostVelocities, deviceVelocities, bytes);
		} finally {
			cuda.releaseContext();
		}
		readBackAndScan(count);
		readBackFoam();
		if (acousticsWanted) {
			var result = acoustics.measure(nextPos, nextVel, count, STRIDE, params.particleVolume(),
				new io.github.isnowx17.inertia.util.WaterAcoustics.Result());
			result.originX = scanSettings.originX;
			result.originY = scanSettings.originY;
			result.originZ = scanSettings.originZ;
			result.tick = stepTickNumber;
			latestAcoustics = result;
		}

		stepFinishedNanos = System.nanoTime();
		lastSimulateMillis = (simulated - started) / 1_000_000f;
		lastStepMillis = (stepFinishedNanos - started) / 1_000_000f;
	}

	/**
	 * Physics thread: copies the read-back particles into the back buffers and scans them
	 * ({@link ParticleScan}), in parallel chunks: each chunk is scanned right after it was copied,
	 * while it is still in the cache.
	 */
	private void readBackAndScan(int count) {
		ParticleScan.Settings settings = scanSettings;
		int sources = settings.sourceCount();
		int perAxis = settings.perAxis;
		int chunks = Math.max(1, Math.min(MAX_SCAN_CHUNKS, (count + SCAN_CHUNK - 1) / SCAN_CHUNK));
		if (scanParts.length < chunks) {
			ParticleScan.Part[] grown = java.util.Arrays.copyOf(scanParts, chunks);
			for (int c = scanParts.length; c < chunks; c++) {
				grown[c] = new ParticleScan.Part();
			}
			scanParts = grown;
		}
		ParticleScan.Part[] parts = scanParts;
		float[] pos = nextPos;
		float[] vel = nextVel;
		FloatBuffer hostP = hostPos;
		FloatBuffer hostV = hostVel;
		java.util.function.IntConsumer chunk = c -> {
			int from = (int) ((long) count * c / chunks);
			int to = (int) ((long) count * (c + 1) / chunks);
			hostP.get(from * STRIDE, pos, from * STRIDE, (to - from) * STRIDE);
			hostV.get(from * STRIDE, vel, from * STRIDE, (to - from) * STRIDE);
			ParticleScan.Part part = parts[c];
			part.reset(sources, perAxis, settings);
			ParticleScan.scan(pos, vel, STRIDE, from, to, settings, part);
		};
		if (chunks == 1) {
			chunk.accept(0);
		} else {
			try {
				scanPool.submit(() -> IntStream.range(0, chunks).parallel().forEach(chunk)).get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while reading back the water", e);
			} catch (java.util.concurrent.ExecutionException e) {
				throw new IllegalStateException("Reading back the water failed", e.getCause());
			}
		}
		ParticleScan.Part total = nextScan;
		total.reset(sources, perAxis, settings);
		for (int c = 0; c < chunks; c++) {
			ParticleScan.merge(total, parts[c]);
		}
	}

	/**
	 * Waits for the running GPU step (if any), publishes its results and removes lost particles.
	 * Everything that touches the PhysX scene or the particle buffer calls this first.
	 */
	private void awaitStep() {
		java.util.concurrent.Future<?> step = pendingStep;
		if (step == null) {
			return;
		}
		pendingStep = null;
		try {
			step.get();
			rigids.capturePoses();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for the physics step", e);
		} catch (java.util.concurrent.ExecutionException e) {
			throw new IllegalStateException("Physics step failed", e.getCause());
		}

		// The positions at the start of the step become the previous ones: the renderer moves the water
		// from them to the new ones over the next tick (see Snapshot).
		float[] oldPrev = prevPos;
		prevPos = currPos;
		currPos = nextPos;
		nextPos = reusable(oldPrev.length == currPos.length ? oldPrev : new float[currPos.length]);
		float[] oldVelocity = velocity;
		velocity = nextVel;
		nextVel = reusable(oldVelocity);
		float[] oldFoam = foam;
		foam = nextFoam;
		nextFoam = reusable(oldFoam);
		foamCount = nextFoamCount;
		prevReadBackNanos = readBackNanos;
		previousLaunchNanos = finishedLaunchNanos;
		finishedLaunchNanos = stepLaunchNanos;
		readBackNanos = stepFinishedNanos;
		ParticleScan.Part finished = nextScan;
		nextScan = scan;
		scan = finished;
		sourceCells.clear();
		measuredBodies = stepBodies;
		SourceCell[] cells = stepSources;
		for (int k = 0; k < cells.length && k < finished.sourceSlots.length; k++) {
			SourceCell cell = cells[k];
			// A copy: fillSource marks the places it fills.
			System.arraycopy(finished.sourceSlots[k], 0, cell.slots, 0, Math.min(cell.slots.length, finished.sourceSlots[k].length));
			cell.count = finished.sourceCount[k];
			cell.sumX = finished.sourceSum[k * 3];
			cell.sumY = finished.sourceSum[k * 3 + 1];
			cell.sumZ = finished.sourceSum[k * 3 + 2];
			cell.top = finished.sourceTop[k];
			cell.topVy = finished.sourceTopVy[k];
			sourceCells.put(cell.key, cell);
		}
		version++;

		IntArrayList remove = finished.lost;
		parkFarWater(finished.lost);
		if (frozen != null && frozen.hasPlans()) {
			frozenRemoval.clear();
			frozen.commit(finished, currPos, STRIDE, frozenRemoval);
			if (!frozenRemoval.isEmpty()) {
				remove = mergeSorted(finished.lost, frozenRemoval);
				version++;
			}
		}
		removeLostParticles(remove);
		updateSolver();
		boolean still = finished.moving <= stepParticleCount * MOVING_FRACTION && finished.inside == 0 && finished.lost.isEmpty();
		stillSteps = still ? stillSteps + 1 : 0;
	}

	/** The level the frozen water was last updated in (for the blocks parked water settles on). */
	private ClientLevel parkLevel;

	/**
	 * Water that went further from the player than the simulated distance is not removed: it goes to
	 * sleep as still water where it is (see {@link FrozenColumns#park}), and wakes when the player comes
	 * back. Water that fell out of the world (or broke) is removed. Client thread.
	 */
	private void parkFarWater(IntArrayList lost) {
		if (frozen == null || parkLevel == null || lost.isEmpty()) {
			return;
		}
		float minY = scanSettings.minY;
		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap counts = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap lowest = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();
		lowest.defaultReturnValue(Float.POSITIVE_INFINITY);
		int[] indices = lost.elements();
		for (int k = 0, n = lost.size(); k < n; k++) {
			int o = indices[k] * STRIDE;
			float x = currPos[o];
			float y = currPos[o + 1];
			float z = currPos[o + 2];
			if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || y < minY) {
				continue;
			}
			long key = FrozenColumns.key(Mth.floor(x), Mth.floor(z));
			counts.addTo(key, 1);
			lowest.put(key, Math.min(lowest.get(key), y));
		}
		if (counts.isEmpty()) {
			return;
		}
		LevelBlocks blocks = new LevelBlocks(parkLevel, originX, originY, originZ);
		for (var e : counts.long2IntEntrySet()) {
			long key = e.getLongKey();
			frozen.park((int) (key >> 32), (int) key, e.getIntValue(), lowest.get(key), blocks);
		}
		version++;
	}

	/**
	 * Takes the still water further than {@code distance} blocks (horizontally) from the world position
	 * {@code x, z} out of the simulation, in world coordinates: {@link #putColumns}'s arguments.
	 *
	 * @return how many columns were taken
	 */
	public int takeFarColumns(double x, double z, double distance, it.unimi.dsi.fastutil.ints.IntArrayList ints,
		it.unimi.dsi.fastutil.floats.FloatArrayList walls) {
		if (frozen == null || frozen.isEmpty()) {
			return 0;
		}
		int from = ints.size();
		int wallsFrom = walls.size();
		int taken = frozen.extract((float) (x - originX), (float) (z - originZ), (float) distance, ints, walls);
		for (int i = from; i < ints.size(); i += io.github.isnowx17.inertia.util.WaterSaveFile.COLUMN_INTS) {
			ints.set(i, ints.getInt(i) + originX);
			ints.set(i + 1, ints.getInt(i + 1) + originZ);
			ints.set(i + 2, ints.getInt(i + 2) + originY);
		}
		for (int i = wallsFrom; i < walls.size(); i++) {
			walls.set(i, walls.getFloat(i) + originY);
		}
		if (taken > 0) {
			version++;
		}
		return taken;
	}

	/**
	 * Puts still water back (world coordinates: x, z, floor, layers, count per column, and four wall
	 * heights as world y): it comes back asleep and wakes when the player is near enough. Water saved
	 * with another particle spacing keeps its height.
	 *
	 * @return false without still water kept as a volume (stillWaterVolume off)
	 */
	public boolean putColumns(int[] ints, int offset, int count, float[] walls, int wallOffset, float savedSpacing, BlockPos around) {
		if (frozen == null) {
			return false;
		}
		awaitStep();
		if ((activeCount == 0 && frozenEmpty() && rigids.isEmpty()) || !originSet) {
			rebase(around);
		}
		float spacing = params.spacing();
		double ratio = Math.pow(savedSpacing / spacing, 3);
		float[] wall = new float[4];
		for (int k = 0; k < count; k++) {
			int o = offset + k * io.github.isnowx17.inertia.util.WaterSaveFile.COLUMN_INTS;
			int floor = ints[o + 2] - originY;
			for (int d = 0; d < 4; d++) {
				wall[d] = walls[wallOffset + k * 4 + d] - originY;
			}
			int layers = Math.max(1, Math.round(ints[o + 3] * savedSpacing / spacing));
			int particles = (int) Math.max(1, Math.round(ints[o + 4] * ratio));
			frozen.restore(ints[o] - originX, ints[o + 1] - originZ, floor, layers, particles, wall, 0);
		}
		version++;
		wake();
		return true;
	}

	/**
	 * Client thread, after every finished step: keeps the steps shorter than a game tick by choosing
	 * the best solver that fits, and notes when even the cheapest one does not.
	 */
	private void updateSolver() {
		float sample = Math.min(lastStepMillis, LONGEST_STEP_SAMPLE_MILLIS);
		stepLoadMillis = stepLoadMillis == 0f ? sample : stepLoadMillis + 0.2f * (sample - stepLoadMillis);
		stepsSinceSolverChange++;
		// Pacing stays on until the water is gone: switched off, the steps would get long again at once.
		if (!framePacing && stepLoadMillis > PACING_MILLIS) {
			framePacing = true;
			stepsPacing = 0;
		}
		stepsPacing++;
		boolean pacingTried = !framePacing || stepsPacing >= PACING_FIRST_STEPS;
		int last = solverLevels.length - 1;
		if (pacingTried && solverLevel < last && stepLoadMillis > STEP_BUDGET_MILLIS && stepsSinceSolverChange >= CHEAPER_AFTER_STEPS) {
			setSolverLevel(solverLevel + 1);
		} else if (solverLevel > 0 && stepsSinceSolverChange >= BETTER_AFTER_STEPS
			&& stepLoadMillis * solverLevels[solverLevel - 1].cost() / solverLevels[solverLevel].cost() < STEP_RELAXED_MILLIS) {
			setSolverLevel(solverLevel - 1);
		}
		if (pacingTried && solverLevel == last && stepsSinceSolverChange >= CHEAPER_AFTER_STEPS && stepLoadMillis > GPU_LIMIT_MILLIS) {
			gpuLimit = true;
		} else if (stepLoadMillis < GPU_ROOM_MILLIS || solverLevel != last) {
			gpuLimit = false;
		}
	}

	/** No water, no steps to measure: the next water starts with the best solver and all the room. */
	private void resetSolver() {
		if (solverLevel != 0) {
			setSolverLevel(0);
		}
		stepLoadMillis = 0f;
		gpuLimit = false;
		framePacing = false;
	}

	/**
	 * Whether the frame rate should be held to the screen's refresh rate, to leave the GPU to the
	 * physics: the steps were getting long and the water is awake. Any thread.
	 */
	public boolean wantsFramePacing() {
		return framePacing && !sleeping;
	}

	private void setSolverLevel(int level) {
		float before = solverLevels[solverLevel].cost();
		solverLevel = level;
		stepsSinceSolverChange = 0;
		FluidParameters.SolverLevel solver = solverLevels[level];
		particleSystem.setSolverIterationCounts(solver.iterations(), FluidParameters.VELOCITY_ITERATIONS);
		// What the next steps are expected to take, so the next decision does not act on old data.
		stepLoadMillis *= solver.cost() / before;
		io.github.isnowx17.inertia.Inertia.LOGGER.debug("Water solver: {} sub-steps, {} iterations", solver.substeps(), solver.iterations());
	}

	/** Whether the GPU has reached the most water it can simulate in real time: new water should replace old water. */
	public boolean atGpuLimit() {
		return gpuLimit;
	}

	/** Sub-steps per tick of the solver in use. */
	public int currentSubsteps() {
		return solverLevels[solverLevel].substeps();
	}

	/** Solver iterations per sub-step of the solver in use. */
	public int currentIterations() {
		return solverLevels[solverLevel].iterations();
	}

	/** An array the physics thread may write into: the given one unless a background job reads it. */
	private float[] reusable(float[] array) {
		synchronized (leased) {
			if (!leased.contains(array)) {
				return array;
			}
		}
		return new float[array.length];
	}

	/**
	 * The newest particle data for a background job, without copying: the arrays stay untouched
	 * until {@link #release} (the client thread only appends new particles beyond {@code count}).
	 * Client thread only.
	 */
	public Snapshot lease() {
		float[] frozenBoxes = frozen != null ? frozen.boxes() : new float[0];
		// The moments the particles stand for: a tick after their steps were started (see tick), the same
		// for the CPU's surface as for the GPU's.
		long tickNanos = (long) (TICK_SECONDS * 1e9);
		long current = finishedLaunchNanos != 0L ? finishedLaunchNanos + tickNanos : readBackNanos;
		long previous = previousLaunchNanos != 0L ? previousLaunchNanos + tickNanos : 0L;
		Snapshot snapshot = new Snapshot(currPos, velocity, activeCount, foam, foamCount, originX, originY, originZ,
			tick, current, version, timeScale, frozenBoxes, frozenBoxes.length / 6, prevPos,
			sleeping ? current : previous);
		synchronized (leased) {
			leased.add(currPos);
			leased.add(prevPos);
			leased.add(velocity);
			leased.add(foam);
		}
		return snapshot;
	}

	/** Ends a {@link #lease()}; may be called from any thread. */
	public void release(Snapshot snapshot) {
		synchronized (leased) {
			leased.remove(snapshot.positions());
			leased.remove(snapshot.previousPositions());
			leased.remove(snapshot.velocities());
			leased.remove(snapshot.diffuse());
		}
	}

	/**
	 * Particle data at one moment: positions and velocities with {@link #STRIDE} floats per particle,
	 * diffuse particles as x, y, z and remaining lifetime, all relative to the origin.
	 *
	 * @param readBackNanos {@link System#nanoTime()} the positions refer to
	 * @param version       changes whenever the data changes
	 * @param timeScale     simulated seconds per real second (1 while the GPU keeps up)
	 * @param frozenBoxes   the frozen water ({@link FrozenColumns#boxes}), never changed afterwards
	 * @param previousPositions the positions of the same particles one step before {@code positions}
	 * @param previousReadBackNanos {@link System#nanoTime()} those refer to; equal to
	 *                      {@code readBackNanos} when there is nothing to move between (still water)
	 */
	public record Snapshot(float[] positions, float[] velocities, int count, float[] diffuse, int diffuseCount,
		int originX, int originY, int originZ, long tick, long readBackNanos, long version, float timeScale,
		float[] frozenBoxes, int frozenBoxCount, float[] previousPositions, long previousReadBackNanos) {
	}

	/**
	 * Client thread, before a step: turns frozen water back into particles where it can move again,
	 * plans the freezing of calm water for the step and brings the boxes in the scene up to date.
	 */
	private void updateFrozen(ClientLevel level, Player player) {
		parkLevel = level;
		FrozenColumns.Context ctx = new FrozenColumns.Context();
		ctx.player = config.playerInteraction && !player.isSpectator();
		net.minecraft.world.phys.AABB box = player.getBoundingBox();
		ctx.playerMinX = (float) (box.minX - originX);
		ctx.playerMinY = (float) (box.minY - originY);
		ctx.playerMinZ = (float) (box.minZ - originZ);
		ctx.playerMaxX = (float) (box.maxX - originX);
		ctx.playerMaxY = (float) (box.maxY - originY);
		ctx.playerMaxZ = (float) (box.maxZ - originZ);
		ctx.playerX = (float) (player.getX() - originX);
		ctx.playerY = (float) (player.getY() - originY);
		ctx.playerZ = (float) (player.getZ() - originZ);
		ctx.maxDistSq = (float) config.despawnDistance * config.despawnDistance;
		int sources = requestedSources.length;
		ctx.sourceX = new int[sources];
		ctx.sourceY = new int[sources];
		ctx.sourceZ = new int[sources];
		for (int k = 0; k < sources; k++) {
			ctx.sourceX[k] = BlockPos.getX(requestedSources[k]) - originX;
			ctx.sourceY[k] = BlockPos.getY(requestedSources[k]) - originY;
			ctx.sourceZ[k] = BlockPos.getZ(requestedSources[k]) - originZ;
		}
		ctx.tight = gpuLimit;
		float[] bodies = new float[nearEntities.size() * 6];
		for (int k = 0; k < nearEntities.size(); k++) {
			net.minecraft.world.phys.AABB b = nearEntities.get(k).getBoundingBox();
			bodies[k * 6] = (float) (b.minX - originX);
			bodies[k * 6 + 1] = (float) (b.minY - originY);
			bodies[k * 6 + 2] = (float) (b.minZ - originZ);
			bodies[k * 6 + 3] = (float) (b.maxX - originX);
			bodies[k * 6 + 4] = (float) (b.maxY - originY);
			bodies[k * 6 + 5] = (float) (b.maxZ - originZ);
		}
		// The realistic bodies too, reaching down to the floor: still water under and around them stays
		// water (frozen water would hold up a body that should sink).
		float[] rigidBoxes = rigids.boxes(RIGID_THAW_DEPTH);
		if (rigidBoxes.length > 0) {
			float[] all = java.util.Arrays.copyOf(bodies, bodies.length + rigidBoxes.length);
			System.arraycopy(rigidBoxes, 0, all, bodies.length, rigidBoxes.length);
			bodies = all;
		}
		ctx.bodies = bodies;
		ctx.overloaded = stepLoadMillis > GPU_OVERLOADED_MILLIS;

		if (!frozen.isEmpty()) {
			java.util.List<FrozenColumns.Thaw> thaws = activeCount == 0 ? frozen.thawAll(ctx) : frozen.thaw(scan, ctx);
			if (!thaws.isEmpty()) {
				long needed = 0;
				for (FrozenColumns.Thaw t : thaws) {
					needed += t.drop() ? 0 : t.particles();
				}
				int room = ensureRoom((int) Math.min(Integer.MAX_VALUE, needed));
				int start = activeCount;
				for (FrozenColumns.Thaw t : thaws) {
					if (!t.drop()) {
						if (t.particles() > room) {
							continue; // waits until there is room
						}
						room -= spawnThawed(t);
					}
					frozen.applyThaw(t);
				}
				if (activeCount > start) {
					uploadRange(start, activeCount - start, true);
					buffer.setNbActiveParticles(activeCount);
				}
				version++;
				wake();
			}
		}
		if (activeCount > 0) {
			frozen.plan(scan, new LevelBlocks(level, originX, originY, originZ), ctx);
		}
		if (frozenCollider != null) {
			frozenCollider.update(frozen);
		}
	}

	/** Puts a thawed part of a frozen column back as particles at rest; returns how many. */
	private int spawnThawed(FrozenColumns.Thaw t) {
		int perAxis = params.particlesPerAxis();
		int perLayer = perAxis * perAxis;
		float spacing = params.spacing();
		int slots = (t.toLayer() - t.fromLayer()) * perLayer;
		int n = t.particles();
		if (n <= 0 || slots <= 0) {
			return 0;
		}
		float invMass = 1f / params.particleMass();
		float jitter = spacing * 0.05f;
		float bottom = t.floor() + params.solidRestOffset() + t.fromLayer() * spacing;
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int start = activeCount;
		for (int m = 0; m < n; m++) {
			// Spread evenly over the places; water that was packed closer than that goes in between.
			boolean extra = m >= slots;
			int slot = !extra ? (n <= slots ? (int) ((long) m * slots / n) : m) : (int) ((long) (m - slots) * slots / (n - slots));
			int layer = slot / perLayer;
			int rest = slot - layer * perLayer;
			int ix = rest % perAxis;
			int iz = rest / perAxis;
			float half = extra ? 0.5f : 0f;
			int i = start + m;
			float px = t.x() + Math.min(perAxis - 0.5f, ix + 0.5f + half) * spacing + (random.nextFloat() - 0.5f) * jitter;
			float py = bottom + (layer + half) * spacing + (random.nextFloat() - 0.5f) * jitter;
			float pz = t.z() + Math.min(perAxis - 0.5f, iz + 0.5f + half) * spacing + (random.nextFloat() - 0.5f) * jitter;
			hostPos.put(i * 4, px).put(i * 4 + 1, py).put(i * 4 + 2, pz).put(i * 4 + 3, invMass);
			hostVel.put(i * 4, 0f).put(i * 4 + 1, 0f).put(i * 4 + 2, 0f).put(i * 4 + 3, 0f);
			currPos[i * STRIDE] = px;
			currPos[i * STRIDE + 1] = py;
			currPos[i * STRIDE + 2] = pz;
			prevPos[i * STRIDE] = px;
			prevPos[i * STRIDE + 1] = py;
			prevPos[i * STRIDE + 2] = pz;
			velocity[i * STRIDE] = 0f;
			velocity[i * STRIDE + 1] = 0f;
			velocity[i * STRIDE + 2] = 0f;
		}
		activeCount += n;
		noteAdded(t.x(), t.z(), t.x() + 1, t.z() + 1);
		// Collision geometry around the thawed water must exist before the next step.
		int wx = t.x() + originX;
		int wz = t.z() + originZ;
		int y0 = t.floor() + originY - 1;
		int y1 = Mth.floor(bottom + (t.toLayer() - t.fromLayer()) * spacing) + originY + 1;
		for (int y = y0 >> 4; y <= y1 >> 4; y++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					pendingUrgentSections.add(SectionPos.asLong(((wx + dx) >> 4), y, ((wz + dz) >> 4)));
				}
			}
		}
		return n;
	}

	/**
	 * The columns the step measures one by one: around the water of the latest step, the frozen water
	 * and the water added since, with a margin; with the heights below which planned columns freeze.
	 */
	private void setGrid(ParticleScan.Settings s) {
		s.gridSizeX = 0;
		s.gridSizeZ = 0;
		s.freezeBelow = null;
		s.frozenTop = null;
		float minX = Math.min(scan.minX, addedMinX);
		float maxX = Math.max(scan.maxX, addedMaxX);
		float minZ = Math.min(scan.minZ, addedMinZ);
		float maxZ = Math.max(scan.maxZ, addedMaxZ);
		addedMinX = Float.POSITIVE_INFINITY;
		addedMaxX = Float.NEGATIVE_INFINITY;
		addedMinZ = Float.POSITIVE_INFINITY;
		addedMaxZ = Float.NEGATIVE_INFINITY;
		int[] frozenBounds = frozen != null ? frozen.bounds() : null;
		if (frozenBounds != null) {
			minX = Math.min(minX, frozenBounds[0]);
			maxX = Math.max(maxX, frozenBounds[1]);
			minZ = Math.min(minZ, frozenBounds[2]);
			maxZ = Math.max(maxZ, frozenBounds[3]);
		}
		// The columns are measured for the frozen water and for the water's surface under entities.
		boolean wanted = frozen != null || !nearEntities.isEmpty();
		if (wanted && minX <= maxX && minZ <= maxZ) {
			int x0 = Mth.floor(minX) - 2;
			int z0 = Mth.floor(minZ) - 2;
			int sizeX = Mth.floor(maxX) + 3 - x0;
			int sizeZ = Mth.floor(maxZ) + 3 - z0;
			if (sizeX <= MAX_GRID_SIZE && sizeZ <= MAX_GRID_SIZE) {
				s.gridX0 = x0;
				s.gridZ0 = z0;
				s.gridSizeX = sizeX;
				s.gridSizeZ = sizeZ;
			}
		}
		if (frozen != null) {
			frozen.prepareScan(s);
		}
	}


	/** Two ascending index lists as one, each index once. */
	private static IntArrayList mergeSorted(IntArrayList a, IntArrayList b) {
		IntArrayList out = new IntArrayList(a.size() + b.size());
		int i = 0;
		int j = 0;
		while (i < a.size() || j < b.size()) {
			int next;
			if (j >= b.size() || (i < a.size() && a.getInt(i) <= b.getInt(j))) {
				next = a.getInt(i++);
			} else {
				next = b.getInt(j++);
			}
			if (out.isEmpty() || out.getInt(out.size() - 1) != next) {
				out.add(next);
			}
		}
		return out;
	}

	/** The blocks of the level for {@link FrozenColumns}, relative to the origin. */
	private record LevelBlocks(ClientLevel level, int ox, int oy, int oz) implements FrozenColumns.Blocks {
		@Override
		public boolean solid(int x, int y, int z) {
			BlockPos pos = new BlockPos(x + ox, y + oy, z + oz);
			return level.getBlockState(pos).getCollisionShape(level, pos) == net.minecraft.world.phys.shapes.Shapes.block();
		}

		@Override
		public boolean open(int x, int y, int z) {
			BlockPos pos = new BlockPos(x + ox, y + oy, z + oz);
			return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
		}
	}

	private void updateTerrain(ClientLevel level) {
		// Sections within one block of a particle are needed right now; their neighbours soon. The
		// physics thread collected the blocks holding water from a sample of the particles (with very
		// many particles a subset: water is dense, and the one-block margin plus the neighbouring
		// sections cover the gaps; a rotating offset makes every particle count over time).

		LongOpenHashSet urgent = new LongOpenHashSet(pendingUrgentSections);
		pendingUrgentSections.clear();
		LongOpenHashSet occupiedSections = new LongOpenHashSet();
		LongIterator it = scan.blocks.iterator();
		while (it.hasNext()) {
			long block = it.nextLong();
			int bx = BlockPos.getX(block);
			int by = BlockPos.getY(block);
			int bz = BlockPos.getZ(block);
			occupiedSections.add(SectionPos.asLong(bx >> 4, by >> 4, bz >> 4));
			for (int dx = -1; dx <= 1; dx += 2) {
				for (int dy = -1; dy <= 1; dy += 2) {
					for (int dz = -1; dz <= 1; dz += 2) {
						urgent.add(SectionPos.asLong((bx + dx) >> 4, (by + dy) >> 4, (bz + dz) >> 4));
					}
				}
			}
		}

		LongOpenHashSet required = new LongOpenHashSet(urgent);
		LongIterator sections = occupiedSections.iterator();
		while (sections.hasNext()) {
			long s = sections.nextLong();
			int sx = SectionPos.x(s);
			int sy = SectionPos.y(s);
			int sz = SectionPos.z(s);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						required.add(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
					}
				}
			}
		}

		rigids.sections(originX, originY, originZ, required, urgent);
		terrain.update(level, required, urgent, tick);
	}

	/**
	 * Removes the particles the physics thread found lost (ascending indices), by moving the last
	 * particle into each hole (highest hole first, so a lost last particle is never moved). Only the
	 * filled holes changed: they are uploaded again, nearby ones together (see {@link ParticleScan#uploadRuns}).
	 */
	private void removeLostParticles(IntArrayList lostList) {
		int lostCount = lostList.size();
		if (lostCount == 0) {
			return;
		}
		int[] lost = lostList.elements();
		for (int k = lostCount - 1; k >= 0; k--) {
			int i = lost[k];
			if (i >= activeCount) {
				continue;
			}
			moveParticle(activeCount - 1, i);
			activeCount--;
		}
		// The filled holes are exactly the lost indices below the new particle count: every other
		// slot still holds what the GPU has, or is no longer in use.
		int[] runs = ParticleScan.uploadRuns(lost, lostCount, activeCount, UPLOAD_MERGE_GAP, MAX_UPLOAD_RUNS);
		for (int r = 0; r < runs.length; r += 2) {
			uploadRange(runs[r], runs[r + 1] - runs[r], false);
		}
		buffer.setNbActiveParticles(activeCount);
	}

	private void moveParticle(int from, int to) {
		if (from == to) {
			return;
		}
		System.arraycopy(prevPos, from * STRIDE, prevPos, to * STRIDE, STRIDE);
		System.arraycopy(currPos, from * STRIDE, currPos, to * STRIDE, STRIDE);
		System.arraycopy(velocity, from * STRIDE, velocity, to * STRIDE, STRIDE);
		for (int c = 0; c < 4; c++) {
			hostPos.put(to * 4 + c, hostPos.get(from * 4 + c));
			hostVel.put(to * 4 + c, hostVel.get(from * 4 + c));
		}
	}

	/** Physics thread: copies the foam, spray and bubble particles into the back buffer. */
	private void readBackFoam() {
		if (diffuseBuffer == null) {
			nextFoamCount = 0;
			return;
		}
		int count = Math.min(diffuseBuffer.getNbActiveDiffuseParticles(), maxFoam);
		if (count <= 0 || stepHidesFoam) {
			nextFoamCount = 0;
			return;
		}
		long device = PxCudaTopLevelFunctions.pxVec4deviceptr(diffuseBuffer.getDiffusePositionLifeTime());
		cuda.acquireContext();
		try {
			cuda.getCudaContext().memcpyDtoH(hostDiffuse, device, count * VEC4_BYTES);
		} finally {
			cuda.releaseContext();
		}
		if (nextFoam.length < count * 4) {
			nextFoam = new float[Math.max(count * 4, nextFoam.length * 2)];
		}
		hostDiffuseView.get(0, nextFoam, 0, count * 4);
		nextFoamCount = count;
	}

	/** Removes all water but keeps the GPU resources for reuse. */
	public void clearParticles() {
		awaitStep();
		activeCount = 0;
		foamCount = 0;
		version++;
		buffer.setNbActiveParticles(0);
		terrain.clear();
		entityBodies.clear();
		stepBodies = EntityBodies.Step.EMPTY;
		measuredBodies = EntityBodies.Step.EMPTY;
		if (frozen != null) {
			frozen.clear();
			frozenCollider.clear();
			frozen.takeDirtyGroups();
		}
		playerBody.remove();
		resetSolver();
		wake();
	}

	// ------------------------------------------------------------------------------------------
	// Block updates
	// ------------------------------------------------------------------------------------------

	public void onBlockChanged(BlockPos pos) {
		terrain.markBlockDirty(pos);
		if (frozen != null && originSet) {
			frozen.blockChanged(pos.getX() - originX, pos.getY() - originY, pos.getZ() - originZ);
		}
	}

	public void onSectionChanged(int sx, int sy, int sz) {
		terrain.markSectionDirty(sx, sy, sz);
		if (frozen != null && originSet && !frozen.isEmpty()) {
			// A whole section at once (a chunk arriving, for example): every frozen column in it thaws.
			int x0 = (sx << 4) - originX;
			int y0 = (sy << 4) - originY;
			int z0 = (sz << 4) - originZ;
			for (int x = x0; x < x0 + 16; x += 2) {
				for (int z = z0; z < z0 + 16; z += 2) {
					for (int y = y0; y < y0 + 16; y += 2) {
						frozen.blockChanged(x, y, z);
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Accessors for rendering and gameplay
	// ------------------------------------------------------------------------------------------

	public int activeCount() {
		return activeCount;
	}

	/** The entities the latest finished step measured the water around, in body order. */
	EntityBodies.Step measuredBodies() {
		return measuredBodies;
	}

	/** Particles in the shell around body {@code b} of {@link #measuredBodies()}. */
	public int bodyWaterCount(int b) {
		return b < ParticleScan.MAX_BODIES ? scan.bodyInside[b] : 0;
	}

	/** Average velocity (blocks/second) along {@code axis} (0 x, 1 y, 2 z) of the water around body {@code b}. */
	public double bodyWaterVelocity(int b, int axis) {
		int n = bodyWaterCount(b);
		return n == 0 ? 0 : scan.bodySum[b * 3 + axis] / n;
	}

	/**
	 * Height (world coordinates) of the water's surface around a box (world coordinates), from the
	 * latest step: the median over the columns right around it, not under it (water splashed onto an
	 * entity lies on it, and would rise with it). NaN where it is not known.
	 */
	public double surfaceAround(net.minecraft.world.phys.AABB box) {
		int x0 = Mth.floor(box.minX) - 1;
		int x1 = Mth.floor(box.maxX) + 1;
		int z0 = Mth.floor(box.minZ) - 1;
		int z1 = Mth.floor(box.maxZ) + 1;
		it.unimi.dsi.fastutil.floats.FloatArrayList tops = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				if (x > x0 && x < x1 && z > z0 && z < z1) {
					continue; // under the box
				}
				int c = scan.cell(x - originX, z - originZ);
				float top = Float.NEGATIVE_INFINITY;
				if (c >= 0 && scan.colCount[c] > 0) {
					top = ParticleScan.surface(scan, c) + 0.5f * params.spacing();
				}
				if (frozen != null) {
					FrozenColumns.Column column = frozen.get(x - originX, z - originZ);
					if (column != null) {
						top = Math.max(top, column.top(params.spacing()));
					}
				}
				if (top > Float.NEGATIVE_INFINITY) {
					tops.add(top + originY);
				}
			}
		}
		if (tops.isEmpty()) {
			return Double.NaN;
		}
		float[] sorted = tops.toFloatArray();
		java.util.Arrays.sort(sorted);
		return sorted[sorted.length / 2];
	}

	/** Particles the frozen water (still water kept as a volume) stands for. */
	public long frozenParticles() {
		return frozen != null ? frozen.frozenParticles() : 0L;
	}

	private static final float[] NO_BOXES = new float[0];

	/** Columns of frozen water. */
	public int frozenColumns() {
		return frozen != null ? frozen.columnCount() : 0;
	}

	public int capacity() {
		return capacity;
	}

	/** The most particles this simulation can grow to. */
	public int limit() {
		return limit;
	}

	public FluidParameters params() {
		return params;
	}

	/** Number of game ticks simulated so far. */
	/** While the player rides a realistic vehicle their box is out of the scene (it would push the vehicle). */
	private boolean playerRiding;

	public void setPlayerRiding(boolean riding) {
		playerRiding = riding;
	}

	/**
	 * Near realistic bodies the player's box is out of the scene: it is infinitely heavy and would shove
	 * them along at walking speed. The player pushes them with their own weight instead (RealisticController).
	 */
	private boolean playerNearBodies;

	public void setPlayerNearBodies(boolean near) {
		playerNearBodies = near;
	}

	/** While the player stands on a realistic body, their box's bottom is raised this far (blocks). */
	public void setPlayerStanding(boolean standing) {
		playerBody.setLift(standing ? 0.25f : 0f);
	}

	/** How far below a realistic body still water is kept unfrozen (blocks). */
	private static final float RIGID_THAW_DEPTH = 32f;

	/** The realistic blocks, boats and minecarts. Client thread, between steps ({@link #settle}). */
	public RigidBodies rigids() {
		return rigids;
	}

	/** Waits for a running step (so bodies can be changed), and sets the origin near {@code around} if nothing is here yet. */
	public void settle(BlockPos around) {
		awaitStep();
		if ((activeCount == 0 && frozenEmpty() && rigids.isEmpty()) || !originSet) {
			rebase(around);
		}
		wake();
	}

	/** Water or lava. */
	public FluidKind kind() {
		return kind;
	}

	/** Where the water made noise in the newest step, or {@code null}. */
	public io.github.isnowx17.inertia.util.WaterAcoustics.Result acoustics() {
		return latestAcoustics;
	}

	/** Whether the physics thread measures {@link #acoustics()}. */
	public void setAcousticsWanted(boolean wanted) {
		acousticsWanted = wanted;
		if (!wanted) {
			latestAcoustics = null;
		}
	}

	public long tickCount() {
		return tick;
	}

	public int originX() {
		return originX;
	}

	public int originY() {
		return originY;
	}

	public int originZ() {
		return originZ;
	}

	/** Positions after the latest tick relative to the origin, {@link #STRIDE} floats per particle. */
	public float[] currentPositions() {
		return currPos;
	}

	/** Velocities after the latest tick in blocks per second, {@link #STRIDE} floats per particle. */
	public float[] velocities() {
		return velocity;
	}

	/** Changes whenever the particle data changes. */
	public long version() {
		return version;
	}

	/** Particles in the shell around the player after the latest step (see {@link #PLAYER_SHELL}). */
	public int playerWaterCount() {
		return scan.inside;
	}

	/** Average velocity (blocks/second) of the water around the player after the latest step. */
	public double playerWaterVelocityX() {
		return scan.inside == 0 ? 0 : scan.sumX / scan.inside;
	}

	public double playerWaterVelocityY() {
		return scan.inside == 0 ? 0 : scan.sumY / scan.inside;
	}

	public double playerWaterVelocityZ() {
		return scan.inside == 0 ? 0 : scan.sumZ / scan.inside;
	}

	public int collisionSections() {
		return terrain.sectionCount();
	}

	/** Foam, spray and bubble particles: xyz relative to the origin plus remaining lifetime, per particle. */
	public float[] foamParticles() {
		return foam;
	}

	public int foamCount() {
		return foamCount;
	}

	/** {@link System#nanoTime()} of the latest read-back, i.e. the time the current positions refer to. */
	public long readBackNanos() {
		return readBackNanos;
	}

	public float lastStepMillis() {
		return lastStepMillis;
	}

	/** The part of {@link #lastStepMillis()} spent simulating on the GPU (the rest is reading back). */
	public float lastSimulateMillis() {
		return lastSimulateMillis;
	}

	@Override
	public void close() {
		try {
			awaitStep();
		} catch (RuntimeException e) {
			io.github.isnowx17.inertia.Inertia.LOGGER.warn("Physics step failed while closing", e);
		}
		stepper.shutdown();
		scanPool.shutdown();
		playerBody.close();
		entityBodies.close();
		rigids.close();
		terrain.close();
		if (frozenCollider != null) {
			frozenCollider.close();
		}
		particleSystem.removeParticleBuffer(buffer);
		buffer.release();
		scene.removeActor(particleSystem);
		particleSystem.release();
		material.release();
		scene.release();
		PxCudaTopLevelFunctions.freePinnedHostBufferPxVec4(cuda, hostPositions);
		PxCudaTopLevelFunctions.freePinnedHostBufferPxVec4(cuda, hostVelocities);
		PxCudaTopLevelFunctions.freePinnedHostBufferPxU32(cuda, NativeArrayHelpers.voidToU32Ptr(hostPhases));
		if (hostDiffuse != null) {
			PxCudaTopLevelFunctions.freePinnedHostBufferPxVec4(cuda, hostDiffuse);
		}
	}
}
