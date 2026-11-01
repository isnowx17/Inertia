package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.config.InertiaConfig;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Owns the fluid simulation of the current level and connects it to the game: ticking, level
 * changes, block updates, the bucket and user feedback.
 *
 * <p>All methods must be called on the client thread.
 */
public final class FluidManager {
	private final InertiaConfig config;
	private FluidSimulation simulation;
	private ClientLevel level;
	private boolean failed;
	private boolean warnedThisSession;
	private double playerSubmersion;

	public FluidManager(InertiaConfig config) {
		this.config = config;
	}

	public FluidSimulation simulation() {
		return simulation;
	}

	public InertiaConfig config() {
		return config;
	}

	public double playerSubmersion() {
		return playerSubmersion;
	}

	/** Called when the player joins a world. */
	public void onJoin(Minecraft client) {
		warnedThisSession = false;
		failed = false;
		if (!client.hasSingleplayerServer()) {
			return; // Inertia is singleplayer only
		}
		PhysXRuntime.State state = PhysXRuntime.state();
		if (state == PhysXRuntime.State.UNAVAILABLE || state == PhysXRuntime.State.FAILED) {
			warnUnavailable(client.player);
		}
	}

	/** Called when the player leaves a world. */
	public void onDisconnect() {
		closeSimulation();
	}

	public void tick(Minecraft client) {
		ClientLevel current = client.level;
		if (current != level) {
			// Left the world or changed dimension: water does not travel with the player.
			closeSimulation();
			level = current;
		}
		if (simulation == null || current == null || client.player == null || client.isPaused()) {
			return;
		}

		try {
			simulation.tick(current, client.player);
			playerSubmersion = config.playerInteraction ? PlayerFluidInteraction.apply(simulation, client.player) : 0;
		} catch (Throwable t) {
			handleFailure(client.player, t);
		}
	}

	/** Handler for {@link io.github.isnowx17.inertia.item.FluidBucketItem}. */
	public boolean releaseWater(Player player, Vec3 center, Vec3 velocity) {
		Minecraft client = Minecraft.getInstance();
		if (!client.hasSingleplayerServer()) {
			player.sendOverlayMessage(Component.translatable("message.inertia.singleplayer_only").withStyle(ChatFormatting.RED));
			return false;
		}

		switch (PhysXRuntime.state()) {
			case READY -> {
			}
			case NOT_STARTED, LOADING -> {
				player.sendOverlayMessage(Component.translatable("message.inertia.loading").withStyle(ChatFormatting.YELLOW));
				return false;
			}
			default -> {
				warnUnavailable(player);
				player.sendOverlayMessage(Component.translatable("message.inertia.unavailable").withStyle(ChatFormatting.RED));
				return false;
			}
		}

		if (failed) {
			player.sendOverlayMessage(Component.translatable("message.inertia.failed").withStyle(ChatFormatting.RED));
			return false;
		}

		try {
			if (simulation == null) {
				simulation = new FluidSimulation(config);
				level = client.level;
				Inertia.LOGGER.info(
					"Fluid simulation started: up to {} particles, {} per block",
					simulation.capacity(),
					simulation.params().particlesPerBlock()
				);
			}

			int added = simulation.spawnBlock(center, velocity);
			if (added == 0) {
				player.sendOverlayMessage(Component.translatable("message.inertia.limit_reached", simulation.capacity()).withStyle(ChatFormatting.YELLOW));
				return false;
			}
			return true;
		} catch (Throwable t) {
			handleFailure(player, t);
			return false;
		}
	}

	public void onBlockChanged(BlockPos pos) {
		if (simulation != null) {
			simulation.onBlockChanged(pos);
		}
	}

	public void onSectionChanged(int sx, int sy, int sz) {
		if (simulation != null) {
			simulation.onSectionChanged(sx, sy, sz);
		}
	}

	/** Removes all water. */
	public int clear() {
		if (simulation == null) {
			return 0;
		}
		int removed = simulation.activeCount();
		simulation.clearParticles();
		return removed;
	}

	private void warnUnavailable(Player player) {
		if (warnedThisSession || player == null) {
			return;
		}
		warnedThisSession = true;
		player.sendSystemMessage(
			Component.translatable("message.inertia.unavailable_detail", PhysXRuntime.statusDetail()).withStyle(ChatFormatting.RED)
		);
	}

	private void handleFailure(Player player, Throwable t) {
		Inertia.LOGGER.error("Fluid simulation failed and was stopped", t);
		failed = true;
		closeSimulation();
		if (player != null) {
			player.sendSystemMessage(Component.translatable("message.inertia.failed").withStyle(ChatFormatting.RED));
		}
	}

	private void closeSimulation() {
		playerSubmersion = 0;
		if (simulation != null) {
			try {
				simulation.close();
			} catch (Throwable t) {
				Inertia.LOGGER.warn("Error while releasing the fluid simulation", t);
			}
			simulation = null;
		}
	}
}
