package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.client.config.InertiaConfig;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import io.github.isnowx17.inertia.util.FluidParameters;
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
	private static final int RECYCLE_SEARCH = 64;
	/** The longest foam lives, relative to the configured lifetime (SurfaceFoam). */
	private static final float FOAM_LONGEST_LIFE = 2.3f;
	/** Steps to wait after a change of solver before getting cheaper or better again. */
	private static final int CHEAPER_AFTER_STEPS = 10;
	private static final int BETTER_AFTER_STEPS = 60;
	/** Floats per particle in the position and velocity arrays. */
	public static final int STRIDE = 4;

	private final InertiaConfig config;
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
	private final LongOpenHashSet pendingUrgentSections = new LongOpenHashSet();

	public FluidSimulation(InertiaConfig config) {
		this.config = config;
		InertiaConfig.Quality quality = config.effectiveQuality();
		this.params = FluidParameters.forSpacing(quality.particleSpacing());
		this.limit = Math.max(quality.maxParticles(), config.particleLimit);
		this.capacity = quality.maxParticles();
		this.substeps = quality.substepsPerTick();
		this.solverLevels = FluidParameters.solverLevels(substeps);
		this.cuda = PhysXRuntime.cuda();

		this.scene = createScene();

		try (MemoryStack mem = MemoryStack.stackPush()) {
			// Water material. Low cohesion and surface tension keep thin water as one sheet instead of
			// beads; friction and a little viscosity make it slow down and pool.
			material = PhysXRuntime.physics().createPBDMaterial(
				config.waterFriction,      // friction
				config.waterDamping,       // damping
				0f,                        // adhesion
				config.waterViscosity,     // viscosity
				config.waterVorticity,     // vorticity confinement
				config.waterSurfaceTension,// surface tension
				config.waterCohesion,      // cohesion
				0f,                        // lift
				0f                         // drag
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
		velocity = new float[0];
		allocateParticleBuffer(capacity);

		terrain = new TerrainCollider(scene);
		playerBody = new PlayerBody(scene);
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

		int newMaxFoam = config.foam ? Math.min(newCapacity / 2, 262_144) : 0;
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
		if (activeCount == 0 || !originSet) {
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
	 * Lets a steady flow of water out of the block of a flowing water source: every tick the same
	 * amount of water ({@code blocksPerSecond}) appears in the free places of the block, the top
	 * layer first, moving down at {@code blocksPerSecond} blocks per second. Falling freely, each
	 * tick's layer joins the one before into one continuous column through the block. When the
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
		if (activeCount == 0 || !originSet) {
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
			if (measured.count >= slots * SOURCE_POOLING_FILL) {
				// The water cannot get away (a pool below): add it gently, moving with the water there.
				vx = measured.sumX / measured.count;
				vy = Math.min(measured.sumY / measured.count, 0f);
				vz = measured.sumZ / measured.count;
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
		return written;
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
		recycled.clear();
		float calm = CALM_SPEED * CALM_SPEED;
		int asked = wanted;
		if (stepLoadMillis > GPU_OVERLOADED_MILLIS) {
			wanted += wanted / 4;
		}
		int perGroup = wanted / RECYCLE_GROUPS + 1;
		int spacing = Math.max(1, activeCount / RECYCLE_GROUPS);
		// Golden-ratio steps through the spacing: over time every particle's turn comes.
		recycleOffset = (int) ((recycleOffset + spacing * 0.618f + 1) % spacing);
		float[] v = velocity;
		for (int g = 0; g < RECYCLE_GROUPS && recycled.size() < wanted; g++) {
			int start = recycleOffset + g * spacing;
			int end = Math.min(activeCount, Math.min(start + spacing, start + perGroup * RECYCLE_SEARCH));
			int found = 0;
			for (int i = start; i < end && found < perGroup && recycled.size() < wanted; i++) {
				int o = i * STRIDE;
				if (v[o] * v[o] + v[o + 1] * v[o + 1] + v[o + 2] * v[o + 2] < calm) {
					recycled.add(i);
					found++;
				}
			}
		}
		int removed = recycled.size();
		if (removed > 0) {
			removeLostParticles(recycled);
			recycled.clear();
			version++;
		}
		return Math.min(removed, asked);
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
		if (activeCount == 0) {
			foamCount = 0;
			terrain.update(level, new LongOpenHashSet(), new LongOpenHashSet(), tick);
			playerBody.remove();
			resetSolver();
			wake();
			return;
		}

		updateTerrain(level);
		playerBody.update(player, originX, originY, originZ, config.playerInteraction);

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
		stepSources = cells;
		stepParticleCount = activeCount;
		stepHidesFoam = tick < foamHiddenUntilTick;
		stepSubsteps = solverLevels[solverLevel].substeps();
		stepSubstepSeconds = TICK_SECONDS / stepSubsteps;
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
			if (stillSteps < SLEEP_AFTER_STEPS || requestedSources.length > 0 || playerAtWater(player)) {
				return false;
			}
			sleeping = true;
			sleepTicks = 0;
			// Spray would hang in the air; the player's body must not jump when the water wakes up.
			foamCount = 0;
			playerBody.remove();
			sleepPlayerX = player.getX();
			sleepPlayerY = player.getY();
			sleepPlayerZ = player.getZ();
			sleepPlayerRange = config.despawnDistance - Math.sqrt(scan.farthestSq);
			return true;
		}
		double dx = player.getX() - sleepPlayerX;
		double dy = player.getY() - sleepPlayerY;
		double dz = player.getZ() - sleepPlayerZ;
		if (terrain.refreshDirty(level) || requestedSources.length > 0 || playerAtWater(player)
			|| dx * dx + dy * dy + dz * dz > sleepPlayerRange * sleepPlayerRange) {
			wake();
			return false;
		}
		return true;
	}

	/** Whether the player is close to a block that held water in the latest step, and can touch it. */
	private boolean playerAtWater(Player player) {
		if (!config.playerInteraction || player.isSpectator()) {
			return false;
		}
		net.minecraft.world.phys.AABB box = player.getBoundingBox().inflate(PLAYER_WAKE_DISTANCE);
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
			part.reset(sources, perAxis);
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
		total.reset(sources, perAxis);
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
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for the physics step", e);
		} catch (java.util.concurrent.ExecutionException e) {
			throw new IllegalStateException("Physics step failed", e.getCause());
		}

		float[] oldPos = currPos;
		currPos = nextPos;
		nextPos = reusable(oldPos);
		float[] oldVelocity = velocity;
		velocity = nextVel;
		nextVel = reusable(oldVelocity);
		float[] oldFoam = foam;
		foam = nextFoam;
		nextFoam = reusable(oldFoam);
		foamCount = nextFoamCount;
		readBackNanos = stepFinishedNanos;
		ParticleScan.Part finished = nextScan;
		nextScan = scan;
		scan = finished;
		sourceCells.clear();
		SourceCell[] cells = stepSources;
		for (int k = 0; k < cells.length && k < finished.sourceSlots.length; k++) {
			SourceCell cell = cells[k];
			// A copy: fillSource marks the places it fills.
			System.arraycopy(finished.sourceSlots[k], 0, cell.slots, 0, Math.min(cell.slots.length, finished.sourceSlots[k].length));
			cell.count = finished.sourceCount[k];
			cell.sumX = finished.sourceSum[k * 3];
			cell.sumY = finished.sourceSum[k * 3 + 1];
			cell.sumZ = finished.sourceSum[k * 3 + 2];
			sourceCells.put(cell.key, cell);
		}
		version++;

		removeLostParticles(finished.lost);
		updateSolver();
		boolean still = finished.moving <= stepParticleCount * MOVING_FRACTION && finished.inside == 0 && finished.lost.isEmpty();
		stillSteps = still ? stillSteps + 1 : 0;
	}

	/**
	 * Client thread, after every finished step: keeps the steps shorter than a game tick by choosing
	 * the best solver that fits, and notes when even the cheapest one does not.
	 */
	private void updateSolver() {
		float sample = Math.min(lastStepMillis, LONGEST_STEP_SAMPLE_MILLIS);
		stepLoadMillis = stepLoadMillis == 0f ? sample : stepLoadMillis + 0.2f * (sample - stepLoadMillis);
		stepsSinceSolverChange++;
		int last = solverLevels.length - 1;
		if (solverLevel < last && stepLoadMillis > STEP_BUDGET_MILLIS && stepsSinceSolverChange >= CHEAPER_AFTER_STEPS) {
			setSolverLevel(solverLevel + 1);
		} else if (solverLevel > 0 && stepsSinceSolverChange >= BETTER_AFTER_STEPS
			&& stepLoadMillis * solverLevels[solverLevel - 1].cost() / solverLevels[solverLevel].cost() < STEP_RELAXED_MILLIS) {
			setSolverLevel(solverLevel - 1);
		}
		if (solverLevel == last && stepsSinceSolverChange >= CHEAPER_AFTER_STEPS && stepLoadMillis > GPU_LIMIT_MILLIS) {
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
		Snapshot snapshot = new Snapshot(currPos, velocity, activeCount, foam, foamCount, originX, originY, originZ,
			tick, readBackNanos, version, timeScale);
		synchronized (leased) {
			leased.add(currPos);
			leased.add(velocity);
			leased.add(foam);
		}
		return snapshot;
	}

	/** Ends a {@link #lease()}; may be called from any thread. */
	public void release(Snapshot snapshot) {
		synchronized (leased) {
			leased.remove(snapshot.positions());
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
	 */
	public record Snapshot(float[] positions, float[] velocities, int count, float[] diffuse, int diffuseCount,
		int originX, int originY, int originZ, long tick, long readBackNanos, long version, float timeScale) {
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
		playerBody.remove();
		resetSolver();
		wake();
	}

	// ------------------------------------------------------------------------------------------
	// Block updates
	// ------------------------------------------------------------------------------------------

	public void onBlockChanged(BlockPos pos) {
		terrain.markBlockDirty(pos);
	}

	public void onSectionChanged(int sx, int sy, int sz) {
		terrain.markSectionDirty(sx, sy, sz);
	}

	// ------------------------------------------------------------------------------------------
	// Accessors for rendering and gameplay
	// ------------------------------------------------------------------------------------------

	public int activeCount() {
		return activeCount;
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
		terrain.close();
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
