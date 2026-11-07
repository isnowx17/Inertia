package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import io.github.isnowx17.inertia.util.FrozenColumns;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.system.MemoryStack;
import physx.common.PxIDENTITYEnum;
import physx.common.PxQuat;
import physx.common.PxTransform;
import physx.common.PxVec3;
import physx.physics.PxRigidStatic;
import physx.physics.PxScene;
import physx.physics.PxShapeFlagEnum;
import physx.physics.PxShapeFlags;

/**
 * The frozen water ({@link FrozenColumns}) in the PhysX scene: one static box per run of equal
 * columns along x, from the floor to the frozen top, so the water above rests on it. Every 16x16
 * group of columns is one actor, rebuilt when one of its columns changed. The sides are moved in by
 * {@code inset}, so particles beside the box rest where they rested beside the water before.
 */
final class FrozenCollider implements AutoCloseable {
	private final PxScene scene;
	private final float spacing;
	private final float inset;
	private final Long2ObjectOpenHashMap<PxRigidStatic> actors = new Long2ObjectOpenHashMap<>();

	FrozenCollider(PxScene scene, float spacing, float inset) {
		this.scene = scene;
		this.spacing = spacing;
		this.inset = inset;
	}

	/** Rebuilds the groups whose columns changed. */
	void update(FrozenColumns frozen) {
		for (long group : frozen.takeDirtyGroups()) {
			remove(actors.remove(group));
			PxRigidStatic actor = build(frozen, (int) (group >> 32), (int) group);
			if (actor != null) {
				actors.put(group, actor);
			}
		}
	}

	private PxRigidStatic build(FrozenColumns frozen, int gx, int gz) {
		int x0 = gx << 4;
		int z0 = gz << 4;
		PxRigidStatic actor = null;
		try (MemoryStack mem = MemoryStack.stackPush()) {
			PxQuat identity = PxQuat.createAt(mem, MemoryStack::nmalloc, PxIDENTITYEnum.PxIdentity);
			PxShapeFlags flags = PxShapeFlags.createAt(mem, MemoryStack::nmalloc, (byte) 0);
			flags.raise(PxShapeFlagEnum.eSIMULATION_SHAPE);
			for (int z = z0; z < z0 + 16; z++) {
				int x = x0;
				while (x < x0 + 16) {
					FrozenColumns.Column c = frozen.get(x, z);
					if (c == null) {
						x++;
						continue;
					}
					int end = x + 1;
					while (end < x0 + 16) {
						FrozenColumns.Column next = frozen.get(end, z);
						if (next == null || next.floor != c.floor || next.layers != c.layers) {
							break;
						}
						end++;
					}
					if (actor == null) {
						PxVec3 zero = PxVec3.createAt(mem, MemoryStack::nmalloc, 0f, 0f, 0f);
						actor = PhysXRuntime.physics().createRigidStatic(PxTransform.createAt(mem, MemoryStack::nmalloc, zero, identity));
					}
					TerrainCollider.attachBox(mem, actor, flags, identity,
						x + inset, c.floor, z + inset, end - inset, c.top(spacing), z + 1 - inset);
					x = end;
				}
			}
		}
		if (actor != null) {
			scene.addActor(actor);
		}
		return actor;
	}

	private void remove(PxRigidStatic actor) {
		if (actor != null) {
			scene.removeActor(actor);
			actor.release();
		}
	}

	void clear() {
		for (PxRigidStatic actor : actors.values()) {
			remove(actor);
		}
		actors.clear();
	}

	@Override
	public void close() {
		clear();
	}
}
