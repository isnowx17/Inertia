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

	private PlayerFluidInteraction() {
	}

	/**
	 * Applies the water measured by the latest physics step. The physics thread counts the water
	 * particles in a thin shell around the player's bounding box (their kinematic body pushes the
	 * water out of the box itself, so a shell full of water means the player is submerged).
	 *
	 * @return fraction of the shell filled with water, 0..1
	 */
	static double apply(FluidSimulation sim, Player player) {
		if (sim.activeCount() == 0 || player.isSpectator() || player.getAbilities().flying) {
			return 0;
		}
		int inside = sim.playerWaterCount();
		if (inside == 0) {
			return 0;
		}

		AABB box = player.getBoundingBox();
		double margin = Math.max(FluidSimulation.PLAYER_SHELL, sim.params().spacing() * 3);
		double shellVolume = (box.getXsize() + 2 * margin) * (box.getYsize() + 2 * margin) * (box.getZsize() + 2 * margin)
			- box.getXsize() * box.getYsize() * box.getZsize();
		double submersion = Math.min(1.0, inside * sim.params().particleVolume() / shellVolume);
		if (submersion < MIN_SUBMERSION) {
			return submersion;
		}

		// Average water velocity, converted from blocks/second to blocks/tick.
		Vec3 water = new Vec3(sim.playerWaterVelocityX(), sim.playerWaterVelocityY(), sim.playerWaterVelocityZ()).scale(1.0 / 20.0);
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
