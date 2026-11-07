package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import io.github.isnowx17.inertia.util.ParticleScan;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
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
 * The other entities in the water: minecarts, boats, animals and monsters, dropped items, falling
 * blocks, TNT. Each gets a kinematic box like the player's ({@link PlayerBody}), so the water flows
 * around it and makes waves where it moves, and a thin shell around it in which the physics step
 * measures the water, so the water can carry it, make it float and soften its fall
 * ({@link EntityFluidInteraction}).
 *
 * <p>Only entities in or right next to the water count, the nearest to the player first, at most
 * {@link ParticleScan#MAX_BODIES}. Client thread only.
 */
final class EntityBodies implements AutoCloseable {
	/** Blocks around the water in which entities are looked for. */
	private static final double REACH = 1.5;

	/** The entities of one physics step and their shells (relative to the origin), in body order. */
	record Step(List<Entity> entities, float[] shells, double[] shellVolumes) {
		static final Step EMPTY = new Step(List.of(), new float[0], new double[0]);
	}

	private final PxScene scene;
	private final Int2ObjectOpenHashMap<Body> bodies = new Int2ObjectOpenHashMap<>();

	EntityBodies(PxScene scene) {
		this.scene = scene;
	}

	/** Whether the water acts on an entity at all. */
	static boolean interacts(Entity entity, Player localPlayer) {
		if (entity == localPlayer || entity instanceof Player || entity.isSpectator() || entity.noPhysics
			|| !entity.isAlive() || entity.isPassenger()) {
			return false;
		}
		return entity instanceof LivingEntity || entity instanceof VehicleEntity || entity instanceof ItemEntity
			|| entity instanceof FallingBlockEntity || entity instanceof PrimedTnt || entity instanceof ExperienceOrb;
	}

	/**
	 * The entities near the water ({@code waterBox}, world coordinates), nearest to the player first.
	 */
	static List<Entity> near(ClientLevel level, Player player, AABB waterBox) {
		List<Entity> found = level.getEntities(player, waterBox.inflate(REACH), entity -> interacts(entity, player));
		if (found.size() > ParticleScan.MAX_BODIES) {
			found = new ArrayList<>(found);
			found.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)));
			found = found.subList(0, ParticleScan.MAX_BODIES);
		}
		return found;
	}

	/**
	 * Moves the boxes to the entities for the next step, adds boxes for new ones and removes the
	 * boxes of the entities that left.
	 *
	 * @param shellMargin thickness of the measuring shell around each box
	 */
	Step update(List<Entity> entities, int originX, int originY, int originZ, double shellMargin) {
		IntOpenHashSet seen = new IntOpenHashSet();
		float[] shells = new float[entities.size() * 6];
		double[] volumes = new double[entities.size()];
		try (MemoryStack mem = MemoryStack.stackPush()) {
			PxQuat identity = PxQuat.createAt(mem, MemoryStack::nmalloc, PxIDENTITYEnum.PxIdentity);
			for (int k = 0; k < entities.size(); k++) {
				Entity entity = entities.get(k);
				AABB box = entity.getBoundingBox();
				seen.add(entity.getId());
				float hx = (float) box.getXsize() * 0.5f;
				float hy = (float) box.getYsize() * 0.5f;
				float hz = (float) box.getZsize() * 0.5f;
				PxTransform pose = PxTransform.createAt(mem, MemoryStack::nmalloc, PxVec3.createAt(mem, MemoryStack::nmalloc,
					(float) ((box.minX + box.maxX) * 0.5 - originX),
					(float) ((box.minY + box.maxY) * 0.5 - originY),
					(float) ((box.minZ + box.maxZ) * 0.5 - originZ)), identity);
				Body body = bodies.get(entity.getId());
				if (body == null || !body.fits(hx, hy, hz)) {
					if (body != null) {
						body.release(scene);
					}
					bodies.put(entity.getId(), Body.create(scene, mem, pose, hx, hy, hz));
				} else {
					body.actor.setKinematicTarget(pose);
				}
				shells[k * 6] = (float) (box.minX - shellMargin - originX);
				shells[k * 6 + 1] = (float) (box.minY - shellMargin - originY);
				shells[k * 6 + 2] = (float) (box.minZ - shellMargin - originZ);
				shells[k * 6 + 3] = (float) (box.maxX + shellMargin - originX);
				shells[k * 6 + 4] = (float) (box.maxY + shellMargin - originY);
				shells[k * 6 + 5] = (float) (box.maxZ + shellMargin - originZ);
				volumes[k] = (box.getXsize() + 2 * shellMargin) * (box.getYsize() + 2 * shellMargin) * (box.getZsize() + 2 * shellMargin)
					- box.getXsize() * box.getYsize() * box.getZsize();
			}
		}
		bodies.int2ObjectEntrySet().removeIf(entry -> {
			if (seen.contains(entry.getIntKey())) {
				return false;
			}
			entry.getValue().release(scene);
			return true;
		});
		return new Step(List.copyOf(entities), shells, volumes);
	}

	/** Removes every box (no water, or the water sleeps). */
	void clear() {
		for (Body body : bodies.values()) {
			body.release(scene);
		}
		bodies.clear();
	}

	@Override
	public void close() {
		clear();
	}

	/** A kinematic box in the scene. */
	private record Body(PxRigidDynamic actor, float hx, float hy, float hz) {
		static Body create(PxScene scene, MemoryStack mem, PxTransform pose, float hx, float hy, float hz) {
			PxRigidDynamic actor = PhysXRuntime.physics().createRigidDynamic(pose);
			actor.setRigidBodyFlag(PxRigidBodyFlagEnum.eKINEMATIC, true);
			PxShapeFlags flags = PxShapeFlags.createAt(mem, MemoryStack::nmalloc, (byte) 0);
			flags.raise(PxShapeFlagEnum.eSIMULATION_SHAPE);
			PxBoxGeometry geometry = PxBoxGeometry.createAt(mem, MemoryStack::nmalloc,
				Math.max(hx, 0.01f), Math.max(hy, 0.01f), Math.max(hz, 0.01f));
			PxShape shape = PhysXRuntime.physics().createShape(geometry, PhysXRuntime.staticMaterial(), true, flags);
			shape.setSimulationFilterData(PhysXRuntime.filterData());
			actor.attachShape(shape);
			shape.release();
			scene.addActor(actor);
			return new Body(actor, hx, hy, hz);
		}

		boolean fits(float x, float y, float z) {
			return Math.abs(x - hx) < 1e-3f && Math.abs(y - hy) < 1e-3f && Math.abs(z - hz) < 1e-3f;
		}

		void release(PxScene scene) {
			scene.removeActor(actor);
			actor.release();
		}
	}
}
