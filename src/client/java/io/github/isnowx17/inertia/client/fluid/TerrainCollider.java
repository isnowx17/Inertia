package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import io.github.isnowx17.inertia.util.BoxMerger;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.system.MemoryStack;
import physx.common.PxIDENTITYEnum;
import physx.common.PxQuat;
import physx.common.PxTransform;
import physx.common.PxVec3;
import physx.geometry.PxBoxGeometry;
import physx.physics.PxRigidStatic;
import physx.physics.PxScene;
import physx.physics.PxShape;
import physx.physics.PxShapeFlagEnum;
import physx.physics.PxShapeFlags;

/**
 * Mirrors the block collision shapes around the water into the PhysX scene.
 *
 * <p>Each 16x16x16 chunk section near a particle becomes one static actor. Full-cube blocks are
 * greedily merged into large boxes; partial blocks (slabs, stairs, fences, ...) contribute one box
 * per collision AABB. Sections are rebuilt when blocks change and dropped when no water has been
 * near them for a while.
 */
final class TerrainCollider implements AutoCloseable {
	private static final int SECTION_SIZE = 16;
	/** Sections rebuilt per tick at most, so a large splash does not cause a frame spike. */
	private static final int BUILD_BUDGET_PER_TICK = 24;
	/** Ticks a section stays in the scene after water left it. */
	private static final int KEEP_ALIVE_TICKS = 100;
	/** Safety cap on shapes per section (pathological builds made of thousands of partial blocks). */
	private static final int MAX_SHAPES_PER_SECTION = 4096;

	/** Marker for a section that was built but has no collision geometry. */
	private static final PxRigidStatic EMPTY = null;

	private final PxScene scene;
	private final Long2ObjectMap<PxRigidStatic> built = new Long2ObjectOpenHashMap<>();
	private final Long2LongMap lastNeeded = new Long2LongOpenHashMap();
	private final LongSet dirty = new LongOpenHashSet();

	private int originX;
	private int originY;
	private int originZ;

	TerrainCollider(PxScene scene) {
		this.scene = scene;
	}

	/** Scene origin in world block coordinates; all actors are placed relative to it. */
	void setOrigin(int x, int y, int z) {
		if (x != originX || y != originY || z != originZ) {
			clear();
			originX = x;
			originY = y;
			originZ = z;
		}
	}

	void markBlockDirty(BlockPos pos) {
		long key = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
		if (built.containsKey(key)) {
			dirty.add(key);
		}
	}

	void markSectionDirty(int sx, int sy, int sz) {
		long key = SectionPos.asLong(sx, sy, sz);
		if (built.containsKey(key)) {
			dirty.add(key);
		}
	}

	int sectionCount() {
		return built.size();
	}

	/**
	 * Makes sure every required section is present and up to date.
	 *
	 * @param required sections that currently need collision geometry
	 * @param urgent   sections that must be built this tick regardless of the budget (fresh water)
	 */
	void update(ClientLevel level, LongSet required, LongSet urgent, long tick) {
		LongArrayList toBuild = new LongArrayList();

		LongIterator it = required.iterator();
		while (it.hasNext()) {
			long key = it.nextLong();
			lastNeeded.put(key, tick);
			if (!built.containsKey(key) || dirty.contains(key)) {
				toBuild.add(key);
			}
		}

		int budget = BUILD_BUDGET_PER_TICK;
		for (int i = 0; i < toBuild.size(); i++) {
			long key = toBuild.getLong(i);
			if (budget <= 0 && !urgent.contains(key)) {
				continue;
			}
			if (build(level, key)) {
				budget--;
			}
		}

		// Drop sections the water has left.
		LongArrayList stale = new LongArrayList();
		for (Long2LongMap.Entry entry : lastNeeded.long2LongEntrySet()) {
			if (tick - entry.getLongValue() > KEEP_ALIVE_TICKS) {
				stale.add(entry.getLongKey());
			}
		}
		for (int i = 0; i < stale.size(); i++) {
			long key = stale.getLong(i);
			lastNeeded.remove(key);
			dirty.remove(key);
			removeActor(built.remove(key));
		}
	}

	/** @return whether the section was (re)built; false if its chunk is not loaded yet */
	private boolean build(ClientLevel level, long key) {
		int sx = SectionPos.x(key);
		int sy = SectionPos.y(key);
		int sz = SectionPos.z(key);

		if (sy < level.getMinSectionY() || sy > level.getMaxSectionY()) {
			replace(key, EMPTY);
			return false;
		}
		if (!level.hasChunk(sx, sz)) {
			return false; // retried next tick once the chunk arrives
		}

		LevelChunk chunk = level.getChunk(sx, sz);
		LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
		if (section.hasOnlyAir()) {
			replace(key, EMPTY);
			return true;
		}

		boolean[] solid = new boolean[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE];
		java.util.ArrayList<float[]> partialBoxes = new java.util.ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int baseX = sx << 4;
		int baseY = sy << 4;
		int baseZ = sz << 4;

		for (int y = 0; y < SECTION_SIZE; y++) {
			for (int z = 0; z < SECTION_SIZE; z++) {
				for (int x = 0; x < SECTION_SIZE; x++) {
					BlockState state = section.getBlockState(x, y, z);
					if (state.isAir()) {
						continue;
					}
					pos.set(baseX + x, baseY + y, baseZ + z);
					VoxelShape shape = state.getCollisionShape(level, pos);
					if (shape.isEmpty()) {
						continue;
					}
					if (shape == Shapes.block()) {
						solid[BoxMerger.index(SECTION_SIZE, x, y, z)] = true;
					} else {
						for (AABB box : shape.toAabbs()) {
							partialBoxes.add(new float[] {
								(float) (x + box.minX), (float) (y + box.minY), (float) (z + box.minZ),
								(float) (x + box.maxX), (float) (y + box.maxY), (float) (z + box.maxZ)
							});
						}
					}
				}
			}
		}

		List<int[]> merged = BoxMerger.merge(solid, SECTION_SIZE);
		if (merged.isEmpty() && partialBoxes.isEmpty()) {
			replace(key, EMPTY);
			return true;
		}

		PxRigidStatic actor;
		try (MemoryStack mem = MemoryStack.stackPush()) {
			PxQuat identity = PxQuat.createAt(mem, MemoryStack::nmalloc, PxIDENTITYEnum.PxIdentity);
			PxVec3 actorPos = PxVec3.createAt(mem, MemoryStack::nmalloc, baseX - originX, baseY - originY, baseZ - originZ);
			PxTransform actorPose = PxTransform.createAt(mem, MemoryStack::nmalloc, actorPos, identity);
			actor = PhysXRuntime.physics().createRigidStatic(actorPose);

			PxShapeFlags flags = PxShapeFlags.createAt(mem, MemoryStack::nmalloc, (byte) 0);
			flags.raise(PxShapeFlagEnum.eSIMULATION_SHAPE);

			int shapes = 0;
			for (int[] b : merged) {
				if (shapes++ >= MAX_SHAPES_PER_SECTION) {
					break;
				}
				attachBox(mem, actor, flags, identity, b[0], b[1], b[2], b[3], b[4], b[5]);
			}
			for (float[] b : partialBoxes) {
				if (shapes++ >= MAX_SHAPES_PER_SECTION) {
					break;
				}
				attachBox(mem, actor, flags, identity, b[0], b[1], b[2], b[3], b[4], b[5]);
			}
			if (shapes > MAX_SHAPES_PER_SECTION) {
				Inertia.LOGGER.debug("Section {} {} {} exceeds {} collision boxes; extra boxes skipped", sx, sy, sz, MAX_SHAPES_PER_SECTION);
			}
		}

		scene.addActor(actor);
		replace(key, actor);
		return true;
	}

	private static void attachBox(
		MemoryStack mem, PxRigidStatic actor, PxShapeFlags flags, PxQuat identity,
		float x0, float y0, float z0, float x1, float y1, float z1
	) {
		float hx = (x1 - x0) * 0.5f;
		float hy = (y1 - y0) * 0.5f;
		float hz = (z1 - z0) * 0.5f;
		if (hx <= 0f || hy <= 0f || hz <= 0f) {
			return;
		}

		PxBoxGeometry geometry = PxBoxGeometry.createAt(mem, MemoryStack::nmalloc, hx, hy, hz);
		PxShape shape = PhysXRuntime.physics().createShape(geometry, PhysXRuntime.staticMaterial(), true, flags);
		PxVec3 center = PxVec3.createAt(mem, MemoryStack::nmalloc, x0 + hx, y0 + hy, z0 + hz);
		shape.setLocalPose(PxTransform.createAt(mem, MemoryStack::nmalloc, center, identity));
		shape.setSimulationFilterData(PhysXRuntime.filterData());
		actor.attachShape(shape);
		shape.release(); // the actor keeps its own reference
	}

	private void replace(long key, PxRigidStatic actor) {
		PxRigidStatic previous = built.put(key, actor);
		dirty.remove(key);
		removeActor(previous);
	}

	private void removeActor(PxRigidStatic actor) {
		if (actor != null) {
			scene.removeActor(actor);
			actor.release();
		}
	}

	void clear() {
		for (PxRigidStatic actor : built.values()) {
			removeActor(actor);
		}
		built.clear();
		lastNeeded.clear();
		dirty.clear();
	}

	@Override
	public void close() {
		clear();
	}
}
