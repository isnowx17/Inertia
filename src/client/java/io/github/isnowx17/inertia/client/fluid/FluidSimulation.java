package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.client.config.InertiaConfig;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import io.github.isnowx17.inertia.util.FluidParameters;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ThreadLocalRandom;
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
 * positions and velocities are copied back to pinned host memory for rendering and gameplay.
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

	// Java-side copies (xyz per particle, relative to the origin).
	// Back buffers written by the physics thread and swapped in by awaitStep().
	private float[] nextPos = new float[0];
	private float[] nextVel = new float[0];
	private float[] nextFoam = new float[0];
	private int nextFoamCount;
	private long stepFinishedNanos;

	/** Runs the GPU step and read-back off the client thread. */
	private final java.util.concurrent.ExecutorService stepper = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Inertia physics");
		thread.setDaemon(true);
		return thread;
	});
	private java.util.concurrent.Future<?> pendingStep;
	private int stepParticleCount;
	private boolean stepHidesFoam;
	// Despawn parameters captured when a step is launched.
	private float despawnMinY;
	private float despawnPlayerX;
	private float despawnPlayerY;
	private float despawnPlayerZ;

	private float[] prevPos;
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
			particleSystem.enableCCD(false);
			particleSystem.setMaxVelocity(params.maxVelocity());
			particleSystem.setSimulationFilterData(PhysXRuntime.filterData());
			scene.addActor(particleSystem);

			PxParticlePhaseFlags phaseFlags = PxParticlePhaseFlags.createAt(mem, MemoryStack::nmalloc, 0);
			phaseFlags.raise(PxParticlePhaseFlagEnum.eParticlePhaseFluid);
			phaseFlags.raise(PxParticlePhaseFlagEnum.eParticlePhaseSelfCollide);
			phase = particleSystem.createPhase(material, phaseFlags);

		}

		prevPos = new float[0];
		currPos = new float[0];
		velocity = new float[0];
		allocateParticleBuffer(capacity);

		terrain = new TerrainCollider(scene);
		playerBody = new PlayerBody(scene);
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

		nextPos = new float[newCapacity * 3];
		nextVel = new float[newCapacity * 3];
		prevPos = java.util.Arrays.copyOf(prevPos, newCapacity * 3);
		currPos = java.util.Arrays.copyOf(currPos, newCapacity * 3);
		velocity = java.util.Arrays.copyOf(velocity, newCapacity * 3);
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
		awaitStep();
		// Re-centre the scene whenever it is empty. While water exists the origin stays put; all
		// water is within the despawn distance of the player, so floats remain precise.
		if (activeCount == 0 || !originSet) {
			rebase(BlockPos.containing(center));
		}

		int perAxis = params.particlesPerAxis();
		int wanted = params.particlesPerBlock();
		int count = Math.min(wanted, ensureRoom(wanted));
		if (count <= 0) {
			return 0;
		}

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

					currPos[i * 3] = prevPos[i * 3] = px;
					currPos[i * 3 + 1] = prevPos[i * 3 + 1] = py;
					currPos[i * 3 + 2] = prevPos[i * 3 + 2] = pz;
					velocity[i * 3] = vx;
					velocity[i * 3 + 1] = vy;
					velocity[i * 3 + 2] = vz;
					written++;
				}
			}
		}

		uploadRange(start, written, true);
		activeCount += written;
		buffer.setNbActiveParticles(activeCount);

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
	 * Emits one tick worth of a continuous stream: discs of particles of the given radius, moving
	 * along {@code direction} at {@code speed} blocks per second. Called every tick by a water source.
	 *
	 * @return number of particles added
	 */
	public int spawnStream(Vec3 center, Vec3 direction, float radius, float speed) {
		awaitStep();
		if (activeCount == 0 || !originSet) {
			rebase(BlockPos.containing(center));
		}
		float spacing = params.spacing();

		// Orthonormal basis around the stream direction.
		org.joml.Vector3f d = new org.joml.Vector3f((float) direction.x, (float) direction.y, (float) direction.z);
		if (d.lengthSquared() < 1e-6f) {
			d.set(0f, -1f, 0f);
		}
		d.normalize();
		org.joml.Vector3f helper = Math.abs(d.y) < 0.9f ? new org.joml.Vector3f(0f, 1f, 0f) : new org.joml.Vector3f(1f, 0f, 0f);
		org.joml.Vector3f u = new org.joml.Vector3f(d).cross(helper).normalize();
		org.joml.Vector3f v = new org.joml.Vector3f(d).cross(u).normalize();

		// Disc points on a grid with the particle spacing; enough layers per tick to fill the stream.
		int steps = Math.max(1, (int) Math.ceil(radius / spacing));
		it.unimi.dsi.fastutil.floats.FloatArrayList disc = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		for (int a = -steps; a <= steps; a++) {
			for (int b = -steps; b <= steps; b++) {
				float da = a * spacing;
				float db = b * spacing;
				if (da * da + db * db <= radius * radius) {
					disc.add(da);
					disc.add(db);
				}
			}
		}
		int points = disc.size() / 2;
		int layers = Math.max(1, Math.round(speed * TICK_SECONDS / spacing));
		int wanted = points * layers;
		int count = Math.min(wanted, ensureRoom(wanted));
		if (count <= 0) {
			return 0;
		}

		float invMass = 1f / params.particleMass();
		float jitter = spacing * 0.05f;
		float cx = (float) (center.x - originX);
		float cy = (float) (center.y - originY);
		float cz = (float) (center.z - originZ);
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int start = activeCount;
		int written = 0;
		outer:
		for (int k = 0; k < layers; k++) {
			for (int n = 0; n < points; n++) {
				if (written >= count) {
					break outer;
				}
				float a = disc.getFloat(n * 2);
				float b = disc.getFloat(n * 2 + 1);
				float along = k * spacing;
				float px = cx + u.x * a + v.x * b + d.x * along + (random.nextFloat() - 0.5f) * jitter;
				float py = cy + u.y * a + v.y * b + d.y * along + (random.nextFloat() - 0.5f) * jitter;
				float pz = cz + u.z * a + v.z * b + d.z * along + (random.nextFloat() - 0.5f) * jitter;
				int i = start + written;
				hostPos.put(i * 4, px).put(i * 4 + 1, py).put(i * 4 + 2, pz).put(i * 4 + 3, invMass);
				hostVel.put(i * 4, d.x * speed).put(i * 4 + 1, d.y * speed).put(i * 4 + 2, d.z * speed).put(i * 4 + 3, 0f);
				currPos[i * 3] = prevPos[i * 3] = px;
				currPos[i * 3 + 1] = prevPos[i * 3 + 1] = py;
				currPos[i * 3 + 2] = prevPos[i * 3 + 2] = pz;
				velocity[i * 3] = d.x * speed;
				velocity[i * 3 + 1] = d.y * speed;
				velocity[i * 3 + 2] = d.z * speed;
				written++;
			}
		}

		uploadRange(start, written, true);
		activeCount += written;
		buffer.setNbActiveParticles(activeCount);

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
		tick++;
		if (activeCount == 0) {
			foamCount = 0;
			terrain.update(level, new LongOpenHashSet(), new LongOpenHashSet(), tick);
			playerBody.remove();
			return;
		}

		updateTerrain(level);
		playerBody.update(player, originX, originY, originZ, config.playerInteraction);

		despawnMinY = level.getMinY() - BELOW_WORLD_MARGIN - originY;
		despawnPlayerX = (float) (player.getX() - originX);
		despawnPlayerY = (float) (player.getY() - originY);
		despawnPlayerZ = (float) (player.getZ() - originZ);
		stepParticleCount = activeCount;
		stepHidesFoam = tick < foamHiddenUntilTick;
		pendingStep = stepper.submit(this::step);
	}

	/** Physics thread: simulate the sub-steps and read the results back into the back buffers. */
	private void step() {
		long started = System.nanoTime();
		float dt = TICK_SECONDS / substeps;
		for (int i = 0; i < substeps; i++) {
			scene.simulate(dt);
			scene.fetchResults(true);
		}
		scene.fetchResultsParticleSystem();

		int count = stepParticleCount;
		cuda.acquireContext();
		try {
			int bytes = count * VEC4_BYTES;
			cuda.getCudaContext().memcpyDtoH(hostPositions, devicePositions, bytes);
			cuda.getCudaContext().memcpyDtoH(hostVelocities, deviceVelocities, bytes);
		} finally {
			cuda.releaseContext();
		}
		for (int i = 0; i < count; i++) {
			nextPos[i * 3] = hostPos.get(i * 4);
			nextPos[i * 3 + 1] = hostPos.get(i * 4 + 1);
			nextPos[i * 3 + 2] = hostPos.get(i * 4 + 2);
			nextVel[i * 3] = hostVel.get(i * 4);
			nextVel[i * 3 + 1] = hostVel.get(i * 4 + 1);
			nextVel[i * 3 + 2] = hostVel.get(i * 4 + 2);
		}
		readBackFoam();

		stepFinishedNanos = System.nanoTime();
		lastStepMillis = (stepFinishedNanos - started) / 1_000_000f;
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

		float[] oldPrev = prevPos;
		prevPos = currPos;
		currPos = nextPos;
		nextPos = oldPrev;
		float[] oldVelocity = velocity;
		velocity = nextVel;
		nextVel = oldVelocity;
		float[] oldFoam = foam;
		foam = nextFoam;
		nextFoam = oldFoam;
		foamCount = nextFoamCount;
		readBackNanos = stepFinishedNanos;

		removeLostParticles();
	}

	private void updateTerrain(ClientLevel level) {
		// Sections within one block of a particle are needed right now; their neighbours soon.
		// With very many particles, sample a subset: water is dense, and the one-block margin plus the
		// neighbouring sections cover the gaps. A rotating offset makes every particle count over time.
		LongOpenHashSet occupiedBlocks = new LongOpenHashSet();
		int stride = Math.max(1, activeCount / TERRAIN_SAMPLES);
		for (int i = (int) (tick % stride); i < activeCount; i += stride) {
			int bx = Mth.floor(currPos[i * 3] + originX);
			int by = Mth.floor(currPos[i * 3 + 1] + originY);
			int bz = Mth.floor(currPos[i * 3 + 2] + originZ);
			occupiedBlocks.add(BlockPos.asLong(bx, by, bz));
		}

		LongOpenHashSet urgent = new LongOpenHashSet(pendingUrgentSections);
		pendingUrgentSections.clear();
		LongOpenHashSet occupiedSections = new LongOpenHashSet();
		LongIterator it = occupiedBlocks.iterator();
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

	/** Removes particles that fell out of the world, drifted too far away or became invalid. */
	private void removeLostParticles() {
		float minY = despawnMinY;
		float px = despawnPlayerX;
		float py = despawnPlayerY;
		float pz = despawnPlayerZ;
		float maxDistSq = (float) config.despawnDistance * config.despawnDistance;

		int before = activeCount;
		int i = 0;
		while (i < activeCount) {
			float x = currPos[i * 3];
			float y = currPos[i * 3 + 1];
			float z = currPos[i * 3 + 2];
			float dx = x - px;
			float dy = y - py;
			float dz = z - pz;
			boolean lost = !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
				|| y < minY
				|| dx * dx + dy * dy + dz * dz > maxDistSq;
			if (lost) {
				moveParticle(activeCount - 1, i);
				activeCount--;
			} else {
				i++;
			}
		}

		if (activeCount != before) {
			uploadRange(0, activeCount, false);
			buffer.setNbActiveParticles(activeCount);
		}
	}

	private void moveParticle(int from, int to) {
		if (from == to) {
			return;
		}
		for (int c = 0; c < 3; c++) {
			currPos[to * 3 + c] = currPos[from * 3 + c];
			prevPos[to * 3 + c] = prevPos[from * 3 + c];
			velocity[to * 3 + c] = velocity[from * 3 + c];
		}
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
		buffer.setNbActiveParticles(0);
		terrain.clear();
		playerBody.remove();
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

	/** Positions after the previous tick, xyz per particle relative to the origin. */
	public float[] previousPositions() {
		return prevPos;
	}

	/** Positions after the latest tick, xyz per particle relative to the origin. */
	public float[] currentPositions() {
		return currPos;
	}

	/** Velocities after the latest tick in blocks per second, xyz per particle. */
	public float[] velocities() {
		return velocity;
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

	@Override
	public void close() {
		try {
			awaitStep();
		} catch (RuntimeException e) {
			io.github.isnowx17.inertia.Inertia.LOGGER.warn("Physics step failed while closing", e);
		}
		stepper.shutdown();
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
