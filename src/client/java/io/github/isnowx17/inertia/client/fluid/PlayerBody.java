package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.lwjgl.system.MemoryStack;
import physx.common.PxIDENTITYEnum;
import physx.common.PxQuat;
import physx.common.PxTransform;
import physx.common.PxVec3;
import physx.geometry.PxBoxGeometry;
import physx.physics.PxRigidBodyFlagEnum;
import physx.physics.PxRigidDynamic;
import physx.physics.PxScene;
import physx.physics.PxShape;
import physx.physics.PxShapeFlagEnum;
import physx.physics.PxShapeFlags;

/**
 * Kinematic box that follows the local player, so the water flows around them instead of
 * passing through. The forces the water applies back to the player are computed on the CPU in
 * {@link PlayerFluidInteraction}.
 */
final class PlayerBody implements AutoCloseable {
	private final PxScene scene;
	private PxRigidDynamic actor;
	private float halfWidth;
	private float halfHeight;

	PlayerBody(PxScene scene) {
		this.scene = scene;
	}

	void update(Player player, int originX, int originY, int originZ, boolean enabled) {
		if (!enabled || player.isSpectator()) {
			remove();
			return;
		}

		AABB box = player.getBoundingBox();
		float hw = (float) Math.max(box.getXsize(), box.getZsize()) * 0.5f;
		float hh = (float) box.getYsize() * 0.5f;
		float cx = (float) ((box.minX + box.maxX) * 0.5 - originX);
		float cy = (float) ((box.minY + box.maxY) * 0.5 - originY);
		float cz = (float) ((box.minZ + box.maxZ) * 0.5 - originZ);

		try (MemoryStack mem = MemoryStack.stackPush()) {
			PxQuat identity = PxQuat.createAt(mem, MemoryStack::nmalloc, PxIDENTITYEnum.PxIdentity);
			PxTransform pose = PxTransform.createAt(mem, MemoryStack::nmalloc, PxVec3.createAt(mem, MemoryStack::nmalloc, cx, cy, cz), identity);

			if (actor == null || Math.abs(hw - halfWidth) > 1e-3f || Math.abs(hh - halfHeight) > 1e-3f) {
				remove();
				create(mem, pose, hw, hh);
			} else {
				actor.setKinematicTarget(pose);
			}
		}
	}

	private void create(MemoryStack mem, PxTransform pose, float hw, float hh) {
		halfWidth = hw;
		halfHeight = hh;

		actor = PhysXRuntime.physics().createRigidDynamic(pose);
		actor.setRigidBodyFlag(PxRigidBodyFlagEnum.eKINEMATIC, true);

		PxShapeFlags flags = PxShapeFlags.createAt(mem, MemoryStack::nmalloc, (byte) 0);
		flags.raise(PxShapeFlagEnum.eSIMULATION_SHAPE);
		PxBoxGeometry geometry = PxBoxGeometry.createAt(mem, MemoryStack::nmalloc, hw, hh, hw);
		PxShape shape = PhysXRuntime.physics().createShape(geometry, PhysXRuntime.staticMaterial(), true, flags);
		shape.setSimulationFilterData(PhysXRuntime.filterData());
		actor.attachShape(shape);
		shape.release();

		scene.addActor(actor);
	}

	void remove() {
		if (actor != null) {
			scene.removeActor(actor);
			actor.release();
			actor = null;
		}
	}

	@Override
	public void close() {
		remove();
	}
}
