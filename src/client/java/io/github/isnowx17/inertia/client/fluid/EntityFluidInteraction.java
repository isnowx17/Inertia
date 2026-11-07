package io.github.isnowx17.inertia.client.fluid;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the water does to the other entities in it ({@link EntityBodies}), like
 * {@link PlayerFluidInteraction} does to the player: it pulls their velocity towards its own (slows
 * them down and carries them along a current), lifts them by how deep they are in it and cushions
 * their fall. How much it lifts depends on what they are made of: boats float high, items and
 * experience float, animals and monsters float just so, minecarts, falling blocks and TNT sink slowly.
 *
 * <p>Entities other than the player move on the integrated server, so the change is made there, at
 * every server tick with the newest measurement ({@link #serverTick}), and on the client's copy, which
 * then predicts the same motion; an entity the player controls (a boat they row) moves on the client,
 * so it is changed there only. An entity touching the water is cushioned right away, before the water
 * around it was measured: a fall from high up would otherwise reach the bottom first.
 */
final class EntityFluidInteraction {
	/** Ignore a few stray droplets. */
	private static final double MIN_SUBMERSION = 0.02;
	/**
	 * At least this much of the vertical velocity is taken away per tick when fully submerged: the
	 * water's lift comes a tick or two late, and without this a floating entity would bob up and down
	 * further and further.
	 */
	private static final double VERTICAL_DAMPING = 0.45;
	/** Submersion above which a fall is cushioned like landing in water. */
	private static final double CUSHION_SUBMERSION = 0.2;

	/** Server ticks a measurement keeps being applied when no newer one came (the GPU was busy). */
	private static final int PUSH_TICKS = 3;

	/** The newest change per entity for the server, with the server ticks it has been applied. */
	private static final java.util.concurrent.ConcurrentHashMap<Integer, Pending> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
	private static volatile ResourceKey<Level> pendingDimension;

	private record Pending(Push push, int uses) {
	}

	private EntityFluidInteraction() {
	}

	/** How an entity floats: lift at full submersion relative to its gravity, and drag towards the water. */
	private record Buoyancy(double lift, double drag) {
	}

	private static Buoyancy floating(Entity entity) {
		if (entity instanceof AbstractBoat) {
			return new Buoyancy(2.2, 0.2);
		}
		if (entity instanceof AbstractMinecart) {
			return new Buoyancy(0.6, 0.3);
		}
		if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
			return new Buoyancy(1.6, 0.35);
		}
		if (entity instanceof FallingBlockEntity) {
			return new Buoyancy(0.5, 0.3);
		}
		if (entity instanceof PrimedTnt) {
			return new Buoyancy(0.8, 0.3);
		}
		return new Buoyancy(1.15, 0.35); // living
	}

	/**
	 * One entity's change. How deep the entity is in the water is worked out from where it is when the
	 * change is applied and the height of the water's surface over it ({@code surface}, world
	 * coordinates; NaN when unknown, then {@code submersion} as measured around it): the surface hardly
	 * moves from one tick to the next, the entity may, and a lift that followed a measurement made a
	 * tick or two before would make it bob up and down. Its velocity is pulled towards the water's
	 * ({@code wx, wy, wz}, blocks/tick) by {@code drag} ({@code verticalDrag} vertically) and it is
	 * lifted by {@code lift} times its gravity, all in proportion to how deep it is.
	 */
	private record Push(int id, double wx, double wy, double wz, double drag, double verticalDrag, double lift,
		double surface, double submersion, boolean touching) {
		void apply(Entity entity) {
			double depth = submersion;
			if (!Double.isNaN(surface)) {
				AABB box = entity.getBoundingBox();
				depth = Mth.clamp((surface - box.minY) / Math.max(box.getYsize(), 0.1), 0.0, 1.0);
			}
			if (touching || depth > CUSHION_SUBMERSION) {
				entity.resetFallDistance();
			}
			if (depth < MIN_SUBMERSION) {
				return;
			}
			double up = entity.isNoGravity() ? 0 : entity.getGravity() * lift * depth;
			Vec3 motion = entity.getDeltaMovement();
			entity.setDeltaMovement(
				motion.x + (wx - motion.x) * drag * depth,
				motion.y + (wy - motion.y) * verticalDrag * depth + up,
				motion.z + (wz - motion.z) * drag * depth);
		}
	}

	/** Applies the water measured by the latest physics step to the entities near it. */
	static void apply(FluidSimulation sim, Minecraft client) {
		if (client.level == null || client.getSingleplayerServer() == null) {
			return;
		}
		pendingDimension = client.level.dimension();
		EntityBodies.Step step = sim.measuredBodies();
		it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap measured = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
		measured.defaultReturnValue(-1);
		for (int b = 0; b < step.entities().size(); b++) {
			measured.put(step.entities().get(b).getId(), b);
		}
		double particleVolume = sim.params().particleVolume();
		for (Entity entity : sim.nearEntities()) {
			if (entity.isRemoved() || entity.isPassenger()) {
				continue;
			}
			AABB box = entity.getBoundingBox();
			int b = measured.get(entity.getId());
			int inside = b >= 0 ? sim.bodyWaterCount(b) : 0;
			double submersion = b >= 0 ? Math.min(1.0, inside * particleVolume / step.shellVolumes()[b]) : 0;
			double surface = sim.surfaceAround(box);
			boolean touching = sim.touchesWater(box.inflate(0.25));
			if (!touching && submersion < MIN_SUBMERSION && !(surface > box.minY)) {
				continue;
			}
			Buoyancy f = floating(entity);
			Push push = new Push(entity.getId(),
				inside > 0 ? sim.bodyWaterVelocity(b, 0) / 20.0 : 0,
				inside > 0 ? sim.bodyWaterVelocity(b, 1) / 20.0 : 0,
				inside > 0 ? sim.bodyWaterVelocity(b, 2) / 20.0 : 0,
				f.drag(), Math.max(f.drag(), VERTICAL_DAMPING), f.lift(), surface, submersion, touching);
			push.apply(entity);
			if (!entity.isLocalInstanceAuthoritative()) {
				PENDING.put(push.id(), new Pending(push, 0));
			}
		}
	}

	/** Server thread, at the start of every server tick: applies the newest change to every entity. */
	static void serverTick(MinecraftServer server) {
		ResourceKey<Level> dimension = pendingDimension;
		if (PENDING.isEmpty() || dimension == null) {
			return;
		}
		ServerLevel level = server.getLevel(dimension);
		for (java.util.Map.Entry<Integer, Pending> entry : PENDING.entrySet()) {
			Pending pending = entry.getValue();
			if (pending.uses() >= PUSH_TICKS || level == null) {
				PENDING.remove(entry.getKey(), pending);
				continue;
			}
			PENDING.replace(entry.getKey(), pending, new Pending(pending.push(), pending.uses() + 1));
			Entity entity = level.getEntity(entry.getKey());
			if (entity != null && !entity.isRemoved()) {
				pending.push().apply(entity);
				entity.needsSync = true;
			}
		}
	}

	/** Forgets everything waiting for the server (the water is gone or the world closed). */
	static void clear() {
		PENDING.clear();
	}
}
