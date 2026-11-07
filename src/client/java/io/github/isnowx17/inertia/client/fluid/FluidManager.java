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
 * <p>The game never waits for the GPU: when the physics step of the previous tick is still running
 * at a game tick, the water's part of that tick is done on the first frame after the step finished
 * ({@link #onFrame}). Water poured with the bucket meanwhile is added then too. The simulation keeps
 * its steps shorter than a tick by choosing a cheaper solver when it has to; when even that is not
 * enough new water replaces calm water instead of adding to it ({@link InertiaConfig#keepRealTime}),
 * so the water keeps flowing in real time. Should it fall behind all the same, ticks are merged and
 * the renderer follows the slower water ({@link FluidSimulation#timeScale()}).
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
	/** A game tick came while the physics step was running; it is simulated once the step is done. */
	private boolean tickPending;
	/** Game time the player was last told that the GPU cannot simulate more water. */
	private long lastGpuLimitMessage = Long.MIN_VALUE / 2;
	/** Ticks between two of those messages. */
	private static final long GPU_LIMIT_MESSAGE_INTERVAL = 60;
	/** Water poured while a step was running: center and velocity, six values each. */
	private final it.unimi.dsi.fastutil.doubles.DoubleArrayList pendingSpawns = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
	/** Game ticks over which the time scale is measured. */
	private static final int RATE_WINDOW = 20;
	/** Per recent game tick: whether it got a physics step of its own (it was not merged into another). */
	private final boolean[] steppedTicks = new boolean[RATE_WINDOW];
	private int steppedTickCount = RATE_WINDOW;
	private int rateCursor;
	/**
	 * At the GPU's limit: room freed for new water and not used yet, and the average room freed per
	 * tick. The sources pour at that average, so a stream keeps an even thickness.
	 */
	private int roomBank;
	private float roomRate;
	/** Ticks between two source toggles, so holding right-click does not flip a source on and off. */
	private static final long SOURCE_TOGGLE_COOLDOWN = 15;
	private java.util.function.Supplier<String> waterShaderStatus = () -> "off";

	public FluidManager(InertiaConfig config) {
		this.config = config;
		java.util.Arrays.fill(steppedTicks, true);
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
			if (simulation.stepRunning()) {
				// The GPU is still busy with the previous step: simulate this tick as soon as it is
				// done (see onFrame) instead of making the game wait. A tick that comes while one is
				// already waiting is merged into it.
				recordTick(!tickPending);
				tickPending = true;
			} else {
				recordTick(true);
				simulateTick(client, current);
			}
			// The player feels the water every tick, from the newest measurement.
			playerSubmersion = config.playerInteraction ? PlayerFluidInteraction.apply(simulation, client.player) : 0;
		} catch (Throwable t) {
			handleFailure(client.player, t);
		}
	}

	/**
	 * Called every frame: simulates a game tick that came while the physics step was running, as
	 * soon as the step is done.
	 */
	public void onFrame(Minecraft client) {
		paceFrame(client);
		if (!tickPending) {
			return;
		}
		ClientLevel current = client.level;
		if (simulation == null || current == null || current != level || client.player == null) {
			tickPending = false;
			return;
		}
		if (client.isPaused() || simulation.stepRunning()) {
			return;
		}
		try {
			simulateTick(client, current);
		} catch (Throwable t) {
			handleFailure(client.player, t);
		}
	}

	private long paceNext;
	private long refreshCheckedAt;
	private float refreshRate;

	/**
	 * While the water needs the GPU ({@link FluidSimulation#wantsFramePacing}), holds the frame rate to
	 * the screen's refresh rate: frames the screen cannot show only take GPU time from the physics,
	 * which then has to make room by removing water. Does nothing with vsync or a lower frame limit.
	 */
	private void paceFrame(Minecraft client) {
		if (simulation == null || !simulation.wantsFramePacing() || client.options.enableVsync().get()) {
			paceNext = 0;
			return;
		}
		long now = System.nanoTime();
		if (now - refreshCheckedAt > 2_000_000_000L || refreshRate == 0f) {
			refreshCheckedAt = now;
			com.mojang.blaze3d.platform.Monitor monitor = client.getWindow().findBestMonitor();
			float rate = monitor != null ? monitor.currentMode().getRefreshRate() : 0f;
			refreshRate = rate >= 30f ? Math.min(rate, 360f) : 144f;
		}
		int limit = client.getFramerateLimitTracker().getFramerateLimit();
		if (limit < net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF && limit <= refreshRate) {
			paceNext = 0;
			return;
		}
		long frame = (long) (1_000_000_000L / refreshRate);
		if (paceNext != 0 && now < paceNext) {
			// Sleep most of the wait, then spin: sleeping alone overshoots by up to a millisecond or two.
			while (paceNext - now > 1_500_000L) {
				java.util.concurrent.locks.LockSupport.parkNanos(paceNext - now - 1_000_000L);
				now = System.nanoTime();
			}
			while (now < paceNext) {
				Thread.onSpinWait();
				now = System.nanoTime();
			}
		}
		paceNext = paceNext == 0 || now - paceNext > frame ? now + frame : paceNext + frame;
	}

	/** The water's part of a game tick: sources, poured water, then the next physics step. */
	private void simulateTick(Minecraft client, ClientLevel current) {
		tickPending = false;
		// A block placed into a source stops it, like filling in a water source block.
		sources.removeIf(source -> !isOpen(current, source.cell()));
		boolean full = gpuFull();
		// At the GPU's limit new water replaces calm water: everything below is limited to what was freed.
		int spawnWanted = pendingSpawns.size() / 6 * simulation.params().particlesPerBlock();
		int perSource = simulation.sourceParticlesPerTick(config.sourceFlow);
		int asked = perSource * sources.size() + spawnWanted;
		int freed = Integer.MAX_VALUE;
		if (full) {
			int got = simulation.recycleCalmWater(Math.max(0, asked - roomBank));
			roomBank = Math.min(roomBank + got, 2 * asked);
			roomRate += (got - roomRate) * 0.1f;
			freed = Math.min(roomBank, Math.max((int) Math.ceil(roomRate), spawnWanted > 0 ? roomBank : 0));
		} else {
			roomBank = 0;
			roomRate = asked;
		}
		int share = sources.isEmpty() ? 0 : (full ? freed * perSource / Math.max(1, perSource * sources.size() + spawnWanted) : Integer.MAX_VALUE);
		long[] cells = new long[sources.size()];
		// Water further away than the despawn distance would be removed again right away.
		double reach = Math.max(0, config.despawnDistance - 2);
		int used = 0;
		for (int i = 0; i < cells.length; i++) {
			Source source = sources.get(i);
			if (share > 0 && Vec3.atCenterOf(source.cell()).distanceToSqr(client.player.position()) < reach * reach) {
				used += simulation.fillSource(source.cell(), config.sourceFlow, share);
			}
			cells[i] = source.cell().asLong();
		}
		int spawnBudget = full ? Math.max(0, freed - used) : Integer.MAX_VALUE;
		int spawned = 0;
		for (int i = 0; i + 5 < pendingSpawns.size(); i += 6) {
			Vec3 center = new Vec3(pendingSpawns.getDouble(i), pendingSpawns.getDouble(i + 1), pendingSpawns.getDouble(i + 2));
			Vec3 velocity = new Vec3(pendingSpawns.getDouble(i + 3), pendingSpawns.getDouble(i + 4), pendingSpawns.getDouble(i + 5));
			int added = spawnBudget > 0 ? simulation.spawnBlock(center, velocity, spawnBudget) : 0;
			if (full) {
				spawnBudget -= added;
				spawned += added;
				if (added == 0) {
					tellGpuLimit(client.player);
				}
			} else if (added == 0) {
				client.player.sendOverlayMessage(Component.translatable("message.inertia.limit_reached", simulation.limit()).withStyle(ChatFormatting.YELLOW));
			}
		}
		if (full && freed == 0 && !sources.isEmpty()) {
			tellGpuLimit(client.player);
		}
		if (full) {
			roomBank = Math.max(0, roomBank - used - spawned);
		}
		pendingSpawns.clear();
		simulation.setSources(cells);
		simulation.setTimeScale((float) steppedTickCount / RATE_WINDOW);
		simulation.tick(current, client.player);
	}

	/** Notes whether the latest game tick got a physics step of its own. */
	private void recordTick(boolean stepped) {
		if (steppedTicks[rateCursor] != stepped) {
			steppedTickCount += stepped ? 1 : -1;
			steppedTicks[rateCursor] = stepped;
		}
		rateCursor = (rateCursor + 1) % RATE_WINDOW;
	}

	/** Whether more water would make it fall behind real time, so new water has to replace old water. */
	private boolean gpuFull() {
		return config.keepRealTime && simulation.atGpuLimit();
	}

	private void tellGpuLimit(Player player) {
		long now = player.level().getGameTime();
		if (now - lastGpuLimitMessage >= GPU_LIMIT_MESSAGE_INTERVAL || now < lastGpuLimitMessage) {
			lastGpuLimitMessage = now;
			player.sendOverlayMessage(Component.translatable("message.inertia.gpu_limit").withStyle(ChatFormatting.YELLOW));
		}
	}

	/** Handler for {@link io.github.isnowx17.inertia.item.FluidBucketItem}. */
	public boolean releaseWater(Player player, Vec3 center, Vec3 velocity) {
		if (!ensureSimulation(player)) {
			return false;
		}
		try {
			if (gpuFull() || simulation.stepRunning() || tickPending) {
				// Added with the next physics step instead of waiting for the GPU (see simulateTick).
				pendingSpawns.add(center.x);
				pendingSpawns.add(center.y);
				pendingSpawns.add(center.z);
				pendingSpawns.add(velocity.x);
				pendingSpawns.add(velocity.y);
				pendingSpawns.add(velocity.z);
				return true;
			}
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

	/**
	 * Handler for {@link io.github.isnowx17.inertia.item.FluidSourceItem}: places a flowing source in
	 * a block (like placing a water source block), or removes the source already there.
	 */
	public boolean toggleSource(Player player, BlockPos cell, boolean stopAll) {
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
		if (cell == null) {
			return false;
		}
		for (int i = 0; i < sources.size(); i++) {
			if (sources.get(i).cell().equals(cell)) {
				sources.remove(i);
				player.sendOverlayMessage(Component.translatable("message.inertia.source_removed", sources.size()));
				return true;
			}
		}
		if (!isOpen(player.level(), cell)) {
			player.sendOverlayMessage(Component.translatable("message.inertia.source_blocked").withStyle(ChatFormatting.YELLOW));
			return false;
		}
		if (sources.size() >= MAX_SOURCES) {
			player.sendOverlayMessage(Component.translatable("message.inertia.source_limit", MAX_SOURCES).withStyle(ChatFormatting.YELLOW));
			return false;
		}
		if (!ensureSimulation(player)) {
			return false;
		}
		sources.add(new Source(cell.immutable()));
		player.sendOverlayMessage(Component.translatable("message.inertia.source_added", sources.size()));
		return true;
	}

	/** Whether water can be in a block: nothing there to collide with. */
	private static boolean isOpen(net.minecraft.world.level.Level level, BlockPos cell) {
		return level.getBlockState(cell).getCollisionShape(level, cell).isEmpty();
	}

	/** A flowing water source: a block that is kept full of water, which flows out of it. */
	private record Source(BlockPos cell) {
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
		pendingSpawns.clear();
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
		pendingSpawns.clear();
		tickPending = false;
		java.util.Arrays.fill(steppedTicks, true);
		steppedTickCount = RATE_WINDOW;
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
