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
	private final java.util.List<Source> sources = new java.util.ArrayList<>();
	private long lastSourceToggle = Long.MIN_VALUE / 2;
	private static final int MAX_SOURCES = 16;
	/** Ticks between two source toggles, so holding right-click does not flip a source on and off. */
	private static final long SOURCE_TOGGLE_COOLDOWN = 15;
	/** Clicking within this many blocks of a source turns it off. */
	private static final double SOURCE_PICK_DISTANCE = 1.0;
	private java.util.function.Supplier<String> waterShaderStatus = () -> "off";

	public FluidManager(InertiaConfig config) {
		this.config = config;
	}

	public FluidSimulation simulation() {
		return simulation;
	}

	public InertiaConfig config() {
		return config;
	}

	public void setWaterShaderStatus(java.util.function.Supplier<String> status) {
		this.waterShaderStatus = status;
	}

	/** Water shader state for /inertia info. */
	public String waterShaderStatus() {
		return config.waterShader ? waterShaderStatus.get() : "off";
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
			for (Source source : sources) {
				if (simulation.spawnStream(source.position(), source.direction(), config.sourceRadius, config.sourceSpeed) == 0) {
					break; // particle limit reached
				}
			}
			simulation.tick(current, client.player);
			playerSubmersion = config.playerInteraction ? PlayerFluidInteraction.apply(simulation, client.player) : 0;
		} catch (Throwable t) {
			handleFailure(client.player, t);
		}
	}

	/** Handler for {@link io.github.isnowx17.inertia.item.FluidBucketItem}. */
	public boolean releaseWater(Player player, Vec3 center, Vec3 velocity) {
		if (!ensureSimulation(player)) {
			return false;
		}
		try {
			int added = simulation.spawnBlock(center, velocity);
			if (added == 0) {
				player.sendOverlayMessage(Component.translatable("message.inertia.limit_reached", simulation.limit()).withStyle(ChatFormatting.YELLOW));
				return false;
			}
			return true;
		} catch (Throwable t) {
			handleFailure(player, t);
			return false;
		}
	}

	/** Handler for {@link io.github.isnowx17.inertia.item.FluidSourceItem}. */
	public boolean toggleSource(Player player, Vec3 position, Vec3 direction, boolean stopAll) {
		long now = player.level().getGameTime();
		if (now - lastSourceToggle < SOURCE_TOGGLE_COOLDOWN) {
			return false; // holding right-click repeats the use; only react to separate clicks
		}
		lastSourceToggle = now;

		if (stopAll) {
			sources.clear();
			player.sendOverlayMessage(Component.translatable("message.inertia.sources_cleared"));
			return true;
		}
		for (int i = 0; i < sources.size(); i++) {
			if (sources.get(i).position().distanceToSqr(position) < SOURCE_PICK_DISTANCE * SOURCE_PICK_DISTANCE) {
				sources.remove(i);
				player.sendOverlayMessage(Component.translatable("message.inertia.source_removed", sources.size()));
				return true;
			}
		}
		if (sources.size() >= MAX_SOURCES) {
			player.sendOverlayMessage(Component.translatable("message.inertia.source_limit", MAX_SOURCES).withStyle(ChatFormatting.YELLOW));
			return false;
		}
		if (!ensureSimulation(player)) {
			return false;
		}
		sources.add(new Source(position, direction));
		player.sendOverlayMessage(Component.translatable("message.inertia.source_added", sources.size()));
		return true;
	}

	/** A continuously flowing water source. */
	private record Source(Vec3 position, Vec3 direction) {
	}

	/** Checks that the GPU simulation can run and creates it on first use. */
	private boolean ensureSimulation(Player player) {
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

		if (simulation == null) {
			try {
				simulation = new FluidSimulation(config);
				level = client.level;
				Inertia.LOGGER.info(
					"Fluid simulation started: {} particles (grows up to {}), {} per block",
					simulation.capacity(),
					simulation.limit(),
					simulation.params().particlesPerBlock()
				);
			} catch (Throwable t) {
				handleFailure(player, t);
				return false;
			}
		}
		return true;
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
		sources.clear();
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
		sources.clear();
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
