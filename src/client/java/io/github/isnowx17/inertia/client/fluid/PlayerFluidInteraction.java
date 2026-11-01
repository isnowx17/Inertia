package io.github.isnowx17.inertia.client.fluid;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Applies the water's effect on the local player: drag towards the water velocity (which both
 * slows the player down and carries them along a current), buoyancy and fall damage cushioning.
 *
 * <p>Player movement is client-authoritative in Minecraft, so changing the local player's motion
 * on the client is enough in singleplayer.
 */
final class PlayerFluidInteraction {
	/** How strongly the player's velocity is pulled towards the water velocity when fully submerged. */
	private static final double DRAG = 0.35;
	/** Upward acceleration per tick when fully submerged; vanilla gravity is 0.08 blocks/tick^2. */
	private static final double BUOYANCY = 0.07;
	/** Ignore a few stray droplets. */
	private static final double MIN_SUBMERSION = 0.02;
	/** Submersion above which a fall is cushioned like landing in water. */
	private static final double CUSHION_SUBMERSION = 0.2;
	/** Thickness in blocks of the sampling shell around the player's bounding box. */
	private static final double SHELL_THICKNESS = 0.35;

	private PlayerFluidInteraction() {
	}

	/** @return fraction of the player's bounding box filled with water, 0..1 */
	static double apply(FluidSimulation sim, Player player) {
		if (sim.activeCount() == 0 || player.isSpectator() || player.getAbilities().flying) {
			return 0;
		}

		// The player's kinematic body pushes the water out of their bounding box, so the water is
		// sampled in a thin shell around it: a shell full of water means the player is submerged.
		AABB box = player.getBoundingBox();
		double margin = Math.max(SHELL_THICKNESS, sim.params().spacing() * 3);
		double minX = box.minX - margin - sim.originX();
		double minY = box.minY - margin - sim.originY();
		double minZ = box.minZ - margin - sim.originZ();
		double maxX = box.maxX + margin - sim.originX();
		double maxY = box.maxY + margin - sim.originY();
		double maxZ = box.maxZ + margin - sim.originZ();

		float[] pos = sim.currentPositions();
		float[] vel = sim.velocities();
		int inside = 0;
		double sumX = 0;
		double sumY = 0;
		double sumZ = 0;
		for (int i = 0, n = sim.activeCount(); i < n; i++) {
			float x = pos[i * 3];
			float y = pos[i * 3 + 1];
			float z = pos[i * 3 + 2];
			if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
				inside++;
				sumX += vel[i * 3];
				sumY += vel[i * 3 + 1];
				sumZ += vel[i * 3 + 2];
			}
		}
		if (inside == 0) {
			return 0;
		}

		double shellVolume = (box.getXsize() + 2 * margin) * (box.getYsize() + 2 * margin) * (box.getZsize() + 2 * margin)
			- box.getXsize() * box.getYsize() * box.getZsize();
		double submersion = Math.min(1.0, inside * sim.params().particleVolume() / shellVolume);
		if (submersion < MIN_SUBMERSION) {
			return submersion;
		}

		// Average water velocity, converted from blocks/second to blocks/tick.
		Vec3 water = new Vec3(sumX / inside, sumY / inside, sumZ / inside).scale(1.0 / 20.0);
		Vec3 motion = player.getDeltaMovement();
		double k = DRAG * submersion;
		Vec3 newMotion = motion.add(water.subtract(motion).scale(k)).add(0, BUOYANCY * submersion, 0);
		player.setDeltaMovement(newMotion);

		if (submersion > CUSHION_SUBMERSION) {
			player.resetFallDistance();
		}
		return submersion;
	}
}
