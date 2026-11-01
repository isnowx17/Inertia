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
import physx.common.PxVec3;
import physx.common.PxVec4;
import physx.particles.PxPBDMaterial;
import physx.particles.PxPBDParticleSystem;
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

	private final InertiaConfig config;
	private final FluidParameters params;
	private final int capacity;

	private final PxCudaContextManager cuda;
	private final PxScene scene;
	private final PxPBDMaterial material;
	private final PxPBDParticleSystem particleSystem;
	private final PxParticleBuffer buffer;
	private final int phase;

	// Pinned host staging memory, accessed from Java through direct buffers.
	private final PxVec4 hostPositions;
	private final PxVec4 hostVelocities;
	private final NativeObject hostPhases;
	private final FloatBuffer hostPos;
	private final FloatBuffer hostVel;

	private final long devicePositions;
	private final long deviceVelocities;
	private final long devicePhases;

	// Java-side copies (xyz per particle, relative to the origin).
	private float[] prevPos;
	private float[] currPos;
	private final float[] velocity;

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
		this.params = FluidParameters.forSpacing(config.particleSpacing);
		this.capacity = config.maxParticles;
		this.cuda = PhysXRuntime.cuda();

		this.scene = createScene();

		try (MemoryStack mem = MemoryStack.stackPush()) {
			// Water material, values from NVIDIA's PBF snippet.
			material = PhysXRuntime.physics().createPBDMaterial(0.05f, 0.05f, 0f, 0.001f, 0.5f, 0.005f, 0.01f, 0f, 0f);
			material.setViscosity(0.001f);
			material.setSurfaceTension(0.00704f);
			material.setCohesion(0.0704f);
			material.setVorticityConfinement(10f);

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

			hostPositions = PxCudaTopLevelFunctions.allocPinnedHostBufferPxVec4(cuda, capacity);
			hostVelocities = PxCudaTopLevelFunctions.allocPinnedHostBufferPxVec4(cuda, capacity);
			hostPhases = PxCudaTopLevelFunctions.allocPinnedHostBufferPxU32(cuda, capacity);
			hostPos = MemoryUtil.memFloatBuffer(hostPositions.getAddress(), capacity * 4);
			hostVel = MemoryUtil.memFloatBuffer(hostVelocities.getAddress(), capacity * 4);
			IntBuffer phases = MemoryUtil.memIntBuffer(hostPhases.getAddress(), capacity);

			float invMass = 1f / params.particleMass();
			for (int i = 0; i < capacity; i++) {
				hostPos.put(i * 4, 0f).put(i * 4 + 1, 0f).put(i * 4 + 2, 0f).put(i * 4 + 3, invMass);
				hostVel.put(i * 4, 0f).put(i * 4 + 1, 0f).put(i * 4 + 2, 0f).put(i * 4 + 3, 0f);
				phases.put(i, phase);
			}

			PxParticleBufferDesc desc = PxParticleBufferDesc.createAt(mem, MemoryStack::nmalloc);
			desc.setMaxParticles(capacity);
			desc.setNumActiveParticles(0);
			desc.setPositions(hostPositions);
			desc.setVelocities(hostVelocities);
			desc.setPhases(NativeArrayHelpers.voidToU32Ptr(hostPhases));
			buffer = PxCudaTopLevelFunctions.CreateAndPopulateParticleBuffer(desc, cuda);
			particleSystem.addParticleBuffer(buffer);
		}

		devicePositions = PxCudaTopLevelFunctions.pxVec4deviceptr(buffer.getPositionInvMasses());
		deviceVelocities = PxCudaTopLevelFunctions.pxVec4deviceptr(buffer.getVelocities());
		devicePhases = PxCudaTopLevelFunctions.pxU32deviceptr(buffer.getPhases());

		prevPos = new float[capacity * 3];
		currPos = new float[capacity * 3];
		velocity = new float[capacity * 3];

		terrain = new TerrainCollider(scene);
		playerBody = new PlayerBody(scene);
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
			return PhysXRuntime.physics().createScene(desc);
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
		// Re-centre the scene whenever it is empty. While water exists the origin stays put; all
		// water is within the despawn distance of the player, so floats remain precise.
		if (activeCount == 0 || !originSet) {
			rebase(BlockPos.containing(center));
		}

		int perAxis = params.particlesPerAxis();
		int wanted = params.particlesPerBlock();
		int count = Math.min(wanted, capacity - activeCount);
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

	private void rebase(BlockPos around) {
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

	/** Advances the simulation by one game tick. Must be called on the client thread. */
	public void tick(ClientLevel level, Player player) {
		tick++;
		if (activeCount == 0) {
			terrain.update(level, new LongOpenHashSet(), new LongOpenHashSet(), tick);
			playerBody.remove();
			return;
		}

		long started = System.nanoTime();

		updateTerrain(level);
		playerBody.update(player, originX, originY, originZ, config.playerInteraction);

		int substeps = config.substepsPerTick;
		float dt = TICK_SECONDS / substeps;
		for (int i = 0; i < substeps; i++) {
			scene.simulate(dt);
			scene.fetchResults(true);
		}
		scene.fetchResultsParticleSystem();

		readBack();
		removeLostParticles(level, player);

		lastStepMillis = (System.nanoTime() - started) / 1_000_000f;
	}

	private void updateTerrain(ClientLevel level) {
		// Sections within one block of a particle are needed right now; their neighbours soon.
		LongOpenHashSet occupiedBlocks = new LongOpenHashSet();
		for (int i = 0; i < activeCount; i++) {
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

	private void readBack() {
		cuda.acquireContext();
		try {
			int bytes = activeCount * VEC4_BYTES;
			cuda.getCudaContext().memcpyDtoH(hostPositions, devicePositions, bytes);
			cuda.getCudaContext().memcpyDtoH(hostVelocities, deviceVelocities, bytes);
		} finally {
			cuda.releaseContext();
		}

		float[] swap = prevPos;
		prevPos = currPos;
		currPos = swap;
		for (int i = 0; i < activeCount; i++) {
			currPos[i * 3] = hostPos.get(i * 4);
			currPos[i * 3 + 1] = hostPos.get(i * 4 + 1);
			currPos[i * 3 + 2] = hostPos.get(i * 4 + 2);
			velocity[i * 3] = hostVel.get(i * 4);
			velocity[i * 3 + 1] = hostVel.get(i * 4 + 1);
			velocity[i * 3 + 2] = hostVel.get(i * 4 + 2);
		}
	}

	/** Removes particles that fell out of the world, drifted too far away or became invalid. */
	private void removeLostParticles(ClientLevel level, Player player) {
		float minY = level.getMinY() - BELOW_WORLD_MARGIN - originY;
		float px = (float) (player.getX() - originX);
		float py = (float) (player.getY() - originY);
		float pz = (float) (player.getZ() - originZ);
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

	/** Removes all water but keeps the GPU resources for reuse. */
	public void clearParticles() {
		activeCount = 0;
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

	public FluidParameters params() {
		return params;
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

	public float lastStepMillis() {
		return lastStepMillis;
	}

	@Override
	public void close() {
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
	}
}
