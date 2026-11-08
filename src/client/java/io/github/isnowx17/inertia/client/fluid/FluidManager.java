package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.config.InertiaConfig;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import java.util.List;
import io.github.isnowx17.inertia.util.FrozenColumns;
import io.github.isnowx17.inertia.util.WaterSaveFile;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
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
	private volatile double playerSubmersion;
	private final WaterSounds sounds;
	private final WaterStorage storage = new WaterStorage();
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

	public FluidManager(InertiaConfig config, FluidKind kind) {
		this.config = config;
		this.kind = kind;
		this.sounds = new WaterSounds(kind);
		java.util.Arrays.fill(steppedTicks, true);
		ALL.add(this);
	}

	/** Every liquid's manager: the frame pacing is for all of them, once per frame. */
	private static final java.util.List<FluidManager> ALL = new java.util.concurrent.CopyOnWriteArrayList<>();

	private final FluidKind kind;

	/** Water or lava. */
	public FluidKind kind() {
		return kind;
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
		if (kind == FluidKind.WATER && (state == PhysXRuntime.State.UNAVAILABLE || state == PhysXRuntime.State.FAILED)) {
			warnUnavailable(client.player);
		}
	}

	/** Called when the player leaves a world. */
	public void onDisconnect() {
		saveWater(true);
		closeSimulation();
		forgetWaiting();
		level = null;
		storage.enter(Minecraft.getInstance(), null, false, kind);
	}

	public void tick(Minecraft client) {
		ClientLevel current = client.level;
		if (current != level) {
			// Left the world or changed dimension: water does not travel with the player. It is saved
			// with the dimension it was in, and the new dimension's water is read.
			saveWater(true);
			closeSimulation();
			forgetWaiting();
			level = current;
			storage.enter(client, current, config.saveWater, kind);
		}
		if (current == null || client.player == null || client.isPaused()) {
			return;
		}
		restoreWater(client, current);
		if (simulation == null) {
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
			// Water carries and lifts the other entities (lava only burns them, on the server).
			if (config.entityInteraction && kind == FluidKind.WATER) {
				EntityFluidInteraction.apply(simulation, client);
			}
			simulation.setAcousticsWanted(config.waterSounds);
			if (config.waterSounds) {
				sounds.tick(client, simulation, playerSubmersion);
			}
			if (config.saveWater && storage.due(config.autosaveSeconds * 1_000_000_000L)) {
				saveWater(false);
			}
		} catch (Throwable t) {
			handleFailure(client.player, t);
		}
	}

	/**
	 * Called every frame: simulates a game tick that came while the physics step was running, as
	 * soon as the step is done.
	 */
	public void onFrame(Minecraft client) {
		if (kind == FluidKind.WATER) {
			paceFrame(client); // once per frame, for all liquids
		}
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
		boolean wanted = false;
		for (FluidManager manager : ALL) {
			wanted |= manager.simulation != null && manager.simulation.wantsFramePacing();
		}
		if (!wanted || client.options.enableVsync().get()) {
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
				pouredSound(player, center, true);
				return true;
			}
			int added = simulation.spawnBlock(center, velocity);
			if (added == 0) {
				player.sendOverlayMessage(Component.translatable("message.inertia.limit_reached", simulation.limit()).withStyle(ChatFormatting.YELLOW));
				return false;
			}
			pouredSound(player, center, true);
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
			if (!sources.isEmpty()) {
				pouredSound(player, player.position(), false);
			}
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
				pouredSound(player, Vec3.atCenterOf(cell), false);
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
		pouredSound(player, Vec3.atCenterOf(cell), true);
		player.sendOverlayMessage(Component.translatable("message.inertia.source_added", sources.size()));
		return true;
	}

	/** A bucket's sound where water was poured ({@code empty}) or a source was turned off. */
	private void pouredSound(Player player, Vec3 at, boolean empty) {
		if (config.waterSounds) {
			WaterSounds.bucket(player.level(), at, empty, kind);
		}
	}

	/**
	 * Saves the water of the current dimension with the world ({@code wait}: before returning, when
	 * leaving it): the simulated water, the water asleep far away and saved water not put back yet.
	 * Also when the game saves the world, and every {@code autosaveSeconds}.
	 */
	public void saveWater(boolean wait) {
		if (!config.saveWater || !storage.mayWrite()) {
			return;
		}
		saveBodies();
		try {
			long[] cells = new long[sources.size()];
			long key = (simulation != null ? simulation.version() : -1) * 31 + dormant.version();
			key = key * 31 + (pendingWater != null ? pendingWater.particleCount() : 0);
			for (int i = 0; i < cells.length; i++) {
				cells[i] = sources.get(i).cell().asLong();
				key = key * 31 + cells[i];
			}
			WaterSaveFile.State state;
			if (simulation != null) {
				state = simulation.capture(cells);
			} else {
				WaterSaveFile.State p = pendingWater;
				state = new WaterSaveFile.State(p != null ? p.originX() : 0, p != null ? p.originY() : 0, p != null ? p.originZ() : 0,
					p != null ? p.spacing() : config.effectiveQuality().particleSpacing(), 0, new float[0], 0, new int[0], new float[0], cells);
			}
			storage.write(withWaiting(state), key, wait);
		} catch (Throwable t) {
			Inertia.LOGGER.error("Could not save the water", t);
		}
	}

	/** {@code state} with the water asleep far away and the saved water not put back yet added. */
	private WaterSaveFile.State withWaiting(WaterSaveFile.State state) {
		WaterSaveFile.State p = pendingWater;
		if (dormant.isEmpty() && p == null) {
			return state;
		}
		int ox = state.originX();
		int oy = state.originY();
		int oz = state.originZ();
		float[] particles = state.particles();
		int particleCount = state.particleCount();
		if (p != null && p.particleCount() > 0) {
			// Moved to this origin (the water stays where it was in the world).
			int n = p.particleCount() * WaterSaveFile.PARTICLE_FLOATS;
			particles = java.util.Arrays.copyOf(state.particles(), particleCount * WaterSaveFile.PARTICLE_FLOATS + n);
			float dx = p.originX() - ox;
			float dy = p.originY() - oy;
			float dz = p.originZ() - oz;
			for (int i = 0; i < n; i += WaterSaveFile.PARTICLE_FLOATS) {
				int o = particleCount * WaterSaveFile.PARTICLE_FLOATS + i;
				particles[o] = p.particles()[i] + dx;
				particles[o + 1] = p.particles()[i + 1] + dy;
				particles[o + 2] = p.particles()[i + 2] + dz;
				particles[o + 3] = p.particles()[i + 3];
				particles[o + 4] = p.particles()[i + 4];
				particles[o + 5] = p.particles()[i + 5];
			}
			particleCount += p.particleCount();
		}
		IntArrayList ints = new IntArrayList(state.columns());
		FloatArrayList walls = new FloatArrayList(state.walls());
		int columns = state.columnCount() + dormant.appendTo(ints, walls, ox, oy, oz, state.spacing());
		return new WaterSaveFile.State(ox, oy, oz, state.spacing(), particleCount, particles, columns, ints.toIntArray(),
			walls.toFloatArray(), state.sources());
	}

	/**
	 * The simulation the realistic bodies live in, created if needed (the water's: they act on each
	 * other), with no step running and its origin set near {@code around}; null where it cannot run.
	 */
	public FluidSimulation simulationForBodies(Player player, BlockPos around) {
		if (!ensureSimulation(player)) {
			return null;
		}
		simulation.settle(around);
		return simulation;
	}

	/** Still water asleep far away, outside the simulation (columns). */
	public int dormantColumns() {
		return dormant.columnCount();
	}

	/** Saves the water, removes it and reads it back from the save (shows that saving works). */
	public void reloadWater(Minecraft client) {
		saveWater(true);
		closeSimulation();
		forgetWaiting();
		storage.enter(client, level, config.saveWater, kind);
	}

	// ------------------------------------------------------------------------------------------
	// Realistic bodies, saved with the water
	// ------------------------------------------------------------------------------------------

	private List<BodySaveFile.Saved> pendingBodies;
	private boolean bodiesRead;

	private void saveBodies() {
		if (kind != FluidKind.WATER || !bodiesRead) {
			return;
		}
		java.nio.file.Path file = storage.bodiesFile();
		if (file == null) {
			return;
		}
		List<BodySaveFile.Saved> list = new java.util.ArrayList<>();
		if (pendingBodies != null) {
			list.addAll(pendingBodies);
		}
		if (simulation != null) {
			for (RigidBodies.Body b : simulation.rigids().bodies()) {
				float[] p = b.current();
				list.add(new BodySaveFile.Saved(b.kind, b.source, p[0] + simulation.originX(), p[1] + simulation.originY(),
					p[2] + simulation.originZ(), p[3], p[4], p[5], p[6], List.copyOf(b.parts())));
			}
		}
		try {
			BodySaveFile.write(file, list);
		} catch (Exception e) {
			Inertia.LOGGER.error("Could not save the realistic bodies", e);
		}
	}

	/** Reads the saved bodies once, and puts them back once the player is near and their chunks are loaded. */
	private void restoreBodies(Minecraft client, ClientLevel current) {
		if (kind != FluidKind.WATER) {
			return;
		}
		if (!bodiesRead) {
			java.nio.file.Path file = storage.bodiesFile();
			if (file == null) {
				return;
			}
			bodiesRead = true;
			try {
				pendingBodies = BodySaveFile.read(file);
			} catch (Exception e) {
				Inertia.LOGGER.error("Could not read the saved realistic bodies", e);
				pendingBodies = null;
			}
		}
		if (pendingBodies == null || pendingBodies.isEmpty() || PhysXRuntime.state() != PhysXRuntime.State.READY || failed) {
			return;
		}
		Vec3 at = client.player.position();
		var it = pendingBodies.iterator();
		while (it.hasNext()) {
			BodySaveFile.Saved b = it.next();
			BlockPos pos = BlockPos.containing(b.x(), b.y(), b.z());
			if (at.distanceToSqr(b.x(), b.y(), b.z()) > config.despawnDistance * (double) config.despawnDistance || !current.isLoaded(pos)) {
				continue;
			}
			FluidSimulation sim = simulationForBodies(client.player, pos);
			if (sim == null) {
				return;
			}
			sim.rigids().restore(b.kind(), b.source(), b.parts(),
				new Vec3(b.x() - sim.originX(), b.y() - sim.originY(), b.z() - sim.originZ()),
				new org.joml.Quaternionf(b.qx(), b.qy(), b.qz(), b.qw()));
			it.remove();
		}
	}

	/** Forgets the water that is not simulated (it was saved with its dimension). */
	private void forgetWaiting() {
		pendingBodies = null;
		bodiesRead = false;
		dormant.clear();
		pendingWater = null;
		pendingBox = null;
		farTicks = 0;
	}

	/**
	 * Every tick in a world: takes the saved water once it is read (the moving water waits, the still
	 * water goes to sleep), puts the moving water back once the player is near and its chunks are
	 * loaded, wakes sleeping water the player comes near and puts water far away to sleep.
	 */
	private void restoreWater(Minecraft client, ClientLevel current) {
		WaterSaveFile.State loaded = storage.takeLoaded();
		if (loaded != null) {
			takeSaved(current, loaded);
		}
		restoreBodies(client, current);
		if (!client.hasSingleplayerServer() || PhysXRuntime.state() != PhysXRuntime.State.READY || failed) {
			return;
		}
		Vec3 at = client.player.position();
		double reach = Math.max(8, config.despawnDistance - FrozenColumns.WAKE_MARGIN);
		if (pendingWater != null && near(pendingBox, at, reach) && loaded(current, pendingBox, Mth.floor(at.y))) {
			WaterSaveFile.State p = pendingWater;
			if (simulation != null && simulation.activeCount() > 0) {
				// New water came first where the saved water was: the new water stays.
				Inertia.LOGGER.warn("Saved water not restored: there is new water already");
			} else if (ensureSimulation(client.player)) {
				int particles = simulation.restore(p);
				Inertia.LOGGER.info("Saved water restored: {} particles", particles);
			} else {
				return;
			}
			pendingWater = null;
			pendingBox = null;
		}
		if (++farTicks < FAR_CHECK_TICKS) {
			return;
		}
		farTicks = 0;
		double far = farDistance();
		if (simulation != null) {
			IntArrayList ints = new IntArrayList();
			FloatArrayList walls = new FloatArrayList();
			int taken = simulation.takeFarColumns(at.x, at.z, far, ints, walls);
			if (taken > 0) {
				dormant.add(ints.elements(), 0, taken, walls.elements(), 0, simulation.params().spacing());
			}
		}
		if (!dormant.isEmpty() && (simulation != null || ensureSimulation(client.player))) {
			double[] keepOut = pendingBox == null ? null
				: new double[] {pendingBox[0] - 16, pendingBox[2] - 16, pendingBox[3] + 16, pendingBox[5] + 16};
			dormant.wake(simulation, current, at.x, at.z, far - 16, keepOut);
		}
	}

	/** Water further away than this (horizontally) sleeps outside the simulation and is not drawn. */
	private double farDistance() {
		return Math.max(config.despawnDistance * 2.0, config.despawnDistance + 64.0);
	}

	private static final int FAR_CHECK_TICKS = 10;
	private int farTicks;
	/** Water asleep far away (see {@link DormantWater}). */
	private final DormantWater dormant = new DormantWater();
	/** Saved moving water waiting for the player to come near, and its world box (min x, y, z, max x, y, z). */
	private WaterSaveFile.State pendingWater;
	private double[] pendingBox;

	/** The saved water of the dimension just entered: its still water sleeps, its moving water waits. */
	private void takeSaved(ClientLevel current, WaterSaveFile.State saved) {
		int[] cols = saved.columns();
		int n = saved.columnCount();
		int[] world = new int[n * WaterSaveFile.COLUMN_INTS];
		float[] walls = new float[n * 4];
		for (int k = 0; k < n; k++) {
			int o = k * WaterSaveFile.COLUMN_INTS;
			world[o] = cols[o] + saved.originX();
			world[o + 1] = cols[o + 1] + saved.originZ();
			world[o + 2] = cols[o + 2] + saved.originY();
			world[o + 3] = cols[o + 3];
			world[o + 4] = cols[o + 4];
			for (int d = 0; d < 4; d++) {
				walls[k * 4 + d] = saved.walls()[k * 4 + d] + saved.originY();
			}
		}
		dormant.add(world, 0, n, walls, 0, saved.spacing());
		if (saved.particleCount() > 0) {
			pendingWater = new WaterSaveFile.State(saved.originX(), saved.originY(), saved.originZ(), saved.spacing(),
				saved.particleCount(), saved.particles(), 0, new int[0], new float[0], new long[0]);
			pendingBox = particleBox(pendingWater);
		}
		for (long cell : saved.sources()) {
			BlockPos pos = BlockPos.of(cell);
			if (sources.size() < MAX_SOURCES) {
				sources.add(new Source(pos));
			}
		}
		Inertia.LOGGER.info("Saved water read: {} particles, {} still columns, {} sources", saved.particleCount(), n,
			saved.sources().length);
	}

	private static double[] particleBox(WaterSaveFile.State s) {
		double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
			Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
		float[] p = s.particles();
		for (int i = 0; i < s.particleCount(); i++) {
			int o = i * WaterSaveFile.PARTICLE_FLOATS;
			double x = p[o] + s.originX();
			double y = p[o + 1] + s.originY();
			double z = p[o + 2] + s.originZ();
			b[0] = Math.min(b[0], x);
			b[1] = Math.min(b[1], y);
			b[2] = Math.min(b[2], z);
			b[3] = Math.max(b[3], x);
			b[4] = Math.max(b[4], y);
			b[5] = Math.max(b[5], z);
		}
		return b;
	}

	/** Whether {@code at} is within {@code reach} of the box. */
	private static boolean near(double[] box, Vec3 at, double reach) {
		double dx = Math.max(0, Math.max(box[0] - at.x, at.x - box[3]));
		double dy = Math.max(0, Math.max(box[1] - at.y, at.y - box[4]));
		double dz = Math.max(0, Math.max(box[2] - at.z, at.z - box[5]));
		return dx * dx + dy * dy + dz * dz <= reach * reach;
	}

	/** Whether all chunks under the box are loaded. */
	private static boolean loaded(ClientLevel level, double[] box, int y) {
		for (int cx = Mth.floor(box[0]) >> 4; cx <= Mth.floor(box[3]) >> 4; cx++) {
			for (int cz = Mth.floor(box[2]) >> 4; cz <= Mth.floor(box[5]) >> 4; cz++) {
				if (!level.isLoaded(new BlockPos(cx << 4, y, cz << 4))) {
					return false;
				}
			}
		}
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
				simulation = new FluidSimulation(config, kind);
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

	/** Server thread: applies what the water does to the server's entities (see {@link EntityFluidInteraction}). */
	public void serverTick(net.minecraft.server.MinecraftServer server) {
		if (kind == FluidKind.LAVA) {
			// A player in the lava burns, as in vanilla lava.
			if (playerSubmersion > LAVA_BURNS_FROM) {
				for (var player : server.getPlayerList().getPlayers()) {
					if (!player.isSpectator() && !player.isCreative()) {
						player.lavaHurt();
					}
				}
			}
			return;
		}
		if (config.entityInteraction) {
			EntityFluidInteraction.serverTick(server);
		}
	}

	/** Submersion in lava from which the player burns. */
	private static final double LAVA_BURNS_FROM = 0.05;

	/** Removes all water. */
	public int clear() {
		sources.clear();
		pendingSpawns.clear();
		// All of it: the water asleep far away and saved water not put back yet too, and the bodies.
		dormant.clear();
		if (simulation != null) {
			simulation.settle(BlockPos.ZERO);
			simulation.rigids().clear();
		}
		pendingWater = null;
		pendingBox = null;
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
		// The last save is kept: the water that was lost with the simulation is not written over it.
		storage.stopWriting();
		closeSimulation();
		if (player != null) {
			player.sendSystemMessage(Component.translatable("message.inertia.failed").withStyle(ChatFormatting.RED));
		}
	}

	private void closeSimulation() {
		playerSubmersion = 0;
		sounds.reset();
		if (kind == FluidKind.WATER) {
			EntityFluidInteraction.clear();
		}
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
