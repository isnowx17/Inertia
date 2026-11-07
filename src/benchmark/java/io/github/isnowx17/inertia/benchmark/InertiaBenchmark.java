package io.github.isnowx17.inertia.benchmark;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.isnowx17.inertia.client.InertiaClient;
import io.github.isnowx17.inertia.client.fluid.FluidManager;
import io.github.isnowx17.inertia.client.fluid.FluidSimulation;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development benchmark (not part of the mod jar). Creates a flat world, builds a stone basin and
 * lets flowing water sources pour into it from ten blocks up, then records frame times: the world
 * without water, the waterfall seen from outside, the settling pool and the still pool seen from
 * outside, the camera standing in the pool, and the camera under the water surface (so back faces
 * are drawn too).
 *
 * <p>The phases are scheduled in simulation steps, so every run measures the same water, however
 * fast the simulation runs. Scenes: {@code heavy} (two sources, up to about 1.2 million particles)
 * and {@code light} (one source, about 250 000 particles).
 *
 * <p>Runs only with {@code -Dinertia.benchmark=<label>}
 * ({@code ./gradlew runBenchmark -PbenchLabel=x -PbenchScene=heavy}). Results go to
 * {@code run-benchmark/benchmark/<label>.json}, a screenshot per phase to
 * {@code run-benchmark/screenshots}. The game quits when done.
 */
public final class InertiaBenchmark implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("InertiaBenchmark");
	private static final String WORLD = "inertia-benchmark";
	private static final Identifier LATE = Identifier.fromNamespaceAndPath("inertia_benchmark", "late");
	private static final Identifier EARLY = Identifier.fromNamespaceAndPath("inertia_benchmark", "early");

	private record Scene(BlockPos[] sources, Object[][] phases, int stopSources, int moveToSurface, int moveInside, int finish) {
	}

	private static final Scene HEAVY = new Scene(
		new BlockPos[] {new BlockPos(0, -50, 0), new BlockPos(3, -50, -3)},
		new Object[][] {{"waterfall", 600, 1000}, {"pool", 1300, 1600}, {"still", 1700, 1900}, {"surface", 2000, 2300}, {"inside", 2360, 2600}},
		1000, 1900, 2300, 2600);
	private static final Scene LIGHT = new Scene(
		new BlockPos[] {new BlockPos(0, -50, 0)},
		new Object[][] {{"waterfall", 200, 400}, {"pool", 600, 800}, {"still", 1000, 1200}, {"surface", 1300, 1500}, {"inside", 1560, 1700}},
		400, 1200, 1500, 1700);

	/** A closed box 7 x 7 blocks wide and 16 deep, filled to the top by four sources: lots of still water. */
	private static final Scene FILL = new Scene(
		new BlockPos[] {new BlockPos(-2, -45, -2), new BlockPos(2, -45, -2), new BlockPos(-2, -45, 2), new BlockPos(2, -45, 2)},
		new Object[][] {{"filling", 400, 800}, {"deep", 1200, 1500}, {"full", 1800, 2000}, {"surface", 2050, 2250}, {"inside", 2300, 2500}},
		2000, 2000, 2250, 2500);

	/** {@link #FILL} with a single source, as a player would fill a box. */
	private static final Scene FILL_ONE = new Scene(
		new BlockPos[] {new BlockPos(0, -45, 0)},
		FILL.phases(), FILL.stopSources(), FILL.moveToSurface(), FILL.moveInside(), FILL.finish());

	private enum Stage { WAIT_TITLE, CREATING, IN_WORLD, DONE }

	private String label;
	private Scene scene;
	private Stage stage = Stage.WAIT_TITLE;
	private int worldTicks;
	private int sourcesPlaced;
	private long startTick = -1;
	private final boolean[] eventDone = new boolean[20];
	private Phase phase;
	private final List<Phase> phases = new ArrayList<>();
	private int stopWorldTick = -1;
	private boolean hold;
	private double holdX;
	private double holdY;
	private double holdZ;
	private float holdYaw;
	private float holdPitch;
	private long tickStart;
	private long lastFrame;
	private long lastCpu;
	private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
	// Time spent in Inertia's own rendering code this frame (between our early and late listeners).
	private long inertiaStart;
	private long inertiaCpuNanos;

	@Override
	public void onInitializeClient() {
		label = System.getProperty("inertia.benchmark");
		if (label == null || label.isBlank()) {
			return;
		}
		String sceneName = System.getProperty("inertia.benchmark.scene", "heavy");
		scene = "light".equals(sceneName) ? LIGHT : "fill".equals(sceneName) ? FILL : "fill1".equals(sceneName) ? FILL_ONE : HEAVY;
		LOGGER.info("Inertia benchmark '{}' armed ({} scene)", label, sceneName());
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (stage == Stage.WAIT_TITLE && !(screen instanceof CreateWorldScreen) && client.level == null
				&& (screen instanceof TitleScreen || screen.getClass().getSimpleName().contains("Onboarding"))) {
				stage = Stage.CREATING;
				client.execute(() -> createWorld(client));
			}
		});
		ClientTickEvents.START_CLIENT_TICK.register(client -> tickStart = System.nanoTime());
		ClientTickEvents.END_CLIENT_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
		ClientTickEvents.END_CLIENT_TICK.register(LATE, this::tick);
		// Around Inertia's own listeners: their CPU time.
		LevelRenderEvents.START_MAIN.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
		LevelRenderEvents.START_MAIN.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
		LevelRenderEvents.START_MAIN.register(EARLY, context -> inertiaStart = System.nanoTime());
		LevelRenderEvents.START_MAIN.register(LATE, context -> inertiaCpuNanos += System.nanoTime() - inertiaStart);
		LevelRenderEvents.COLLECT_SUBMITS.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
		LevelRenderEvents.COLLECT_SUBMITS.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
		LevelRenderEvents.COLLECT_SUBMITS.register(EARLY, context -> inertiaStart = System.nanoTime());
		LevelRenderEvents.COLLECT_SUBMITS.register(LATE, context -> inertiaCpuNanos += System.nanoTime() - inertiaStart);
		LevelRenderEvents.END_MAIN.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
		LevelRenderEvents.END_MAIN.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
		LevelRenderEvents.END_MAIN.register(EARLY, context -> inertiaStart = System.nanoTime());
		LevelRenderEvents.END_MAIN.register(LATE, context -> {
			inertiaCpuNanos += System.nanoTime() - inertiaStart;
			frame();
		});
	}

	private String sceneName() {
		return scene == LIGHT ? "light" : scene == FILL ? "fill" : scene == FILL_ONE ? "fill1" : "heavy";
	}

	private void createWorld(Minecraft client) {
		try {
			Path save = client.getLevelSource().getBaseDir().resolve(WORLD);
			if (Files.exists(save)) {
				try (Stream<Path> files = Files.walk(save)) {
					for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
						Files.delete(p);
					}
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Could not delete the old benchmark world", e);
		}
		applyOptions(client);
		CreateWorldScreen.openFresh(client, () -> client.gui.setScreen(new TitleScreen()));
		if (!(client.gui.screen() instanceof CreateWorldScreen screen)) {
			throw new IllegalStateException("CreateWorldScreen did not open");
		}
		WorldCreationUiState ui = screen.getUiState();
		ui.setName(WORLD);
		Holder<WorldPreset> flat = ui.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT);
		ui.setWorldType(new WorldCreationUiState.WorldTypeEntry(flat));
		ui.setSeed("1");
		ui.setGenerateStructures(false);
		ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
		ui.setAllowCommands(true);
		ui.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
		ui.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
		ui.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
		press(screen, "selectWorld.create");
	}

	private static void press(Screen screen, String key) {
		String text = Component.translatable(key).getString();
		for (GuiEventListener child : screen.children()) {
			if (child instanceof Button button && text.equals(button.getMessage().getString())) {
				button.onPress(new MouseButtonInfo(1, 0));
				return;
			}
		}
		throw new IllegalStateException("No button '" + text + "' on " + screen.getClass().getName());
	}

	private static void applyOptions(Minecraft client) {
		var options = client.options;
		options.framerateLimit().set(260);
		options.enableVsync().set(false);
		options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		options.renderDistance().set(8);
		options.simulationDistance().set(5);
		options.pauseOnLostFocus = false;
		options.onboardAccessibility = false;
		options.tutorialStep = TutorialSteps.NONE;
		options.save();
	}

	private void command(Minecraft client, String command) {
		MinecraftServer server = client.getSingleplayerServer();
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
	}

	/** Teleports the player and keeps them there, whatever keyboard and mouse do during the run. */
	private void moveTo(Minecraft client, double x, double y, double z, float yaw, float pitch) {
		command(client, String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
		holdX = x;
		holdY = y;
		holdZ = z;
		holdYaw = yaw;
		holdPitch = pitch;
		hold = true;
	}

	private void holdStill(Minecraft client) {
		if (client.mouseHandler.isMouseGrabbed()) {
			client.mouseHandler.releaseMouse();
		}
		if (client.gui.screen() != null) {
			client.gui.setScreen(null);
		}
		if (hold && client.player != null) {
			client.player.setPos(holdX, holdY, holdZ);
			client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
			client.player.setYRot(holdYaw);
			client.player.setYHeadRot(holdYaw);
			client.player.setXRot(holdPitch);
		}
	}

	/** Runs an event once, when the simulation reached {@code atTick} steps after the sources started. */
	private boolean event(int id, long t, int atTick) {
		if (!eventDone[id] && t >= atTick) {
			eventDone[id] = true;
			return true;
		}
		return false;
	}

	private void tick(Minecraft client) {
		if (stage == Stage.CREATING && client.level != null && client.player != null) {
			stage = Stage.IN_WORLD;
			worldTicks = 0;
		}
		if (stage != Stage.IN_WORLD) {
			return;
		}
		worldTicks++;
		long tickNanos = System.nanoTime() - tickStart;
		holdStill(client);
		if (worldTicks == 20) {
			applyOptions(client);
			command(client, "time set 6000");
			command(client, "weather clear");
			command(client, "difficulty peaceful");
			command(client, "fill -8 -60 -8 8 -55 8 minecraft:stone");
			command(client, "fill -7 -60 -7 7 -55 7 minecraft:air");
			command(client, "fill -12 -51 -12 -11 -51 -11 minecraft:stone");
			if ((scene == FILL || scene == FILL_ONE)) {
				command(client, "fill -4 -61 -4 4 -44 4 minecraft:stone");
				command(client, "fill -3 -60 -3 3 -44 3 minecraft:air");
				// The water is watched through a glass wall.
				command(client, "fill -3 -61 -4 3 -44 -4 minecraft:glass");
				moveTo(client, 0.5, -52, -11.5, 0, 33);
			} else {
				moveTo(client, -11.0, -50, -11.0, -45, 32);
			}
		}
		if (worldTicks % 100 == 0 && InertiaClient.fluids().simulation() != null) {
			FluidSimulation s = InertiaClient.fluids().simulation();
			LOGGER.info("WATER t={} active={} frozen={} columns={} stepMs={} limit={} asleep={} fps={}", worldTicks, s.activeCount(),
				s.frozenParticles(), s.frozenColumns(), String.format(java.util.Locale.ROOT, "%.1f", s.lastStepMillis()), s.atGpuLimit(),
				s.sleeping(), client.getFps());
		}
		// The world without any water: the ceiling for everything below.
		if (worldTicks == 200) {
			phase = new Phase("empty", 0);
			phases.add(phase);
			lastFrame = 0;
		}
		if (worldTicks == 300) {
			phase = null;
		}
		FluidManager fluids = InertiaClient.fluids();
		BlockPos[] sources = scene.sources();
		if (sourcesPlaced < sources.length && worldTicks >= 300 + 30 * sourcesPlaced
			&& fluids.toggleSource(client.player, sources[sourcesPlaced], false)) {
			sourcesPlaced++;
			if (sourcesPlaced == sources.length) {
				startTick = fluids.simulation().tickCount();
				LOGGER.info("Benchmark sources running");
			}
		}
		FluidSimulation sim = fluids.simulation();
		if (startTick < 0 || sim == null) {
			return;
		}
		// While the sources pour the schedule follows the simulation, so every run measures the same
		// water; afterwards it follows the game (still water may sleep).
		long t = stopWorldTick >= 0 ? scene.stopSources() + worldTicks - stopWorldTick : sim.tickCount() - startTick;

		Object[][] list = scene.phases();
		for (int p = 0; p < list.length; p++) {
			if (event(2 * p, t, (int) list[p][1])) {
				phase = new Phase((String) list[p][0], sim.tickCount());
				phases.add(phase);
				lastFrame = 0;
			}
			if (event(2 * p + 1, t, (int) list[p][2]) && phase != null) {
				Screenshot.grab(client.gameDirectory, "bench-" + label + "-" + list[p][0] + ".png", client.gameRenderer.mainRenderTarget(), 1, m -> { });
				phase.simTicks = sim.tickCount() - phase.firstSimTick;
				phase = null;
				writeResults(client);
			}
		}
		if (phase != null) {
			phase.ticks++;
			phase.tickNanos += tickNanos;
			phase.maxTickNanos = Math.max(phase.maxTickNanos, tickNanos);
			phase.particles += sim.activeCount();
			phase.frozen += sim.frozenParticles();
			phase.stepMillis += sim.lastStepMillis();
			phase.simulateMillis += sim.lastSimulateMillis();
			phase.substeps += sim.currentSubsteps();
			phase.iterations += sim.currentIterations();
			phase.limited += sim.atGpuLimit() ? 1 : 0;
			phase.asleep += sim.sleeping() ? 1 : 0;
		}
		if (event(10, t, scene.stopSources())) {
			fluids.toggleSource(client.player, null, true);
			stopWorldTick = worldTicks;
		}
		if (event(11, t, scene.moveToSurface())) {
			if ((scene == FILL || scene == FILL_ONE)) {
				moveTo(client, 0.5, -48, 2.5, 180, 55);
			} else {
				moveTo(client, 3.5, -60, 3.5, -45, 25);
			}
		}
		if (event(13, t, scene.moveInside())) {
			// Spectators can stay low: the camera right under the water surface, looking along it.
			command(client, "gamemode spectator @a");
			if ((scene == FILL || scene == FILL_ONE)) {
				moveTo(client, 0.5, -52, 0.5, -45, 10);
			} else {
				moveTo(client, 3.5, -61.45, 3.5, -45, -8);
			}
		}
		if (event(12, t, scene.finish())) {
			stage = Stage.DONE;
			writeResults(client);
			client.stop();
		}
	}

	private void frame() {
		long now = System.nanoTime();
		long cpu = threads.getCurrentThreadCpuTime();
		if (phase != null && lastFrame != 0) {
			phase.add(now - lastFrame, cpu - lastCpu);
			phase.inertiaCpuNanos += inertiaCpuNanos;
		}
		inertiaCpuNanos = 0;
		lastFrame = now;
		lastCpu = cpu;
	}

	private void writeResults(Minecraft client) {
		JsonObject root = new JsonObject();
		root.addProperty("label", label);
		root.addProperty("scene", sceneName());
		root.addProperty("keepRealTime", InertiaClient.fluids().config().keepRealTime);
		root.addProperty("width", client.getWindow().getWidth());
		root.addProperty("height", client.getWindow().getHeight());
		JsonArray list = new JsonArray();
		for (Phase p : phases) {
			list.add(p.toJson());
			LOGGER.info("Benchmark {} / {}: {}", label, p.name, p.toJson());
		}
		root.add("phases", list);
		try {
			Path dir = client.gameDirectory.toPath().resolve("benchmark");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(label + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(root));
		} catch (IOException e) {
			LOGGER.error("Could not write the benchmark results", e);
		}
	}

	private static final class Phase {
		final String name;
		final long firstSimTick;
		long simTicks;
		long[] frames = new long[4096];
		long[] cpu = new long[4096];
		int count;
		int ticks;
		long tickNanos;
		long maxTickNanos;
		double particles;
		double frozen;
		double stepMillis;
		double simulateMillis;
		double substeps;
		double iterations;
		int limited;
		int asleep;
		long inertiaCpuNanos;

		Phase(String name, long firstSimTick) {
			this.name = name;
			this.firstSimTick = firstSimTick;
		}

		void add(long frameNanos, long cpuNanos) {
			if (count == frames.length) {
				frames = Arrays.copyOf(frames, count * 2);
				cpu = Arrays.copyOf(cpu, count * 2);
			}
			frames[count] = frameNanos;
			cpu[count] = cpuNanos;
			count++;
		}

		JsonObject toJson() {
			JsonObject o = new JsonObject();
			long[] sorted = Arrays.copyOf(frames, count);
			Arrays.sort(sorted);
			long total = 0;
			long cpuTotal = 0;
			for (int i = 0; i < count; i++) {
				total += frames[i];
				cpuTotal += cpu[i];
			}
			double seconds = total / 1e9;
			o.addProperty("phase", name);
			o.addProperty("frames", count);
			o.addProperty("seconds", round(seconds));
			o.addProperty("fps", round(count / Math.max(seconds, 1e-9)));
			o.addProperty("frameMsAvg", round(total / 1e6 / Math.max(1, count)));
			o.addProperty("frameMsP50", round(percentile(sorted, 0.50) / 1e6));
			o.addProperty("frameMsP95", round(percentile(sorted, 0.95) / 1e6));
			o.addProperty("frameMsP99", round(percentile(sorted, 0.99) / 1e6));
			o.addProperty("frameMsMax", round((count == 0 ? 0 : sorted[count - 1]) / 1e6));
			// "1 % low": the frame rate of the slowest 1 % of the frames.
			int slow = Math.max(1, count / 100);
			long slowTotal = 0;
			for (int i = count - slow; i < count; i++) {
				slowTotal += sorted[Math.max(0, i)];
			}
			o.addProperty("onePercentLowFps", round(slow / Math.max(slowTotal / 1e9, 1e-9)));
			o.addProperty("renderThreadCpuMsPerFrame", round(cpuTotal / 1e6 / Math.max(1, count)));
			// Inertia's rendering code on the render thread, per frame.
			o.addProperty("inertiaRenderCpuMsPerFrame", round(inertiaCpuNanos / 1e6 / Math.max(1, count)));
			o.addProperty("gameTicks", ticks);
			o.addProperty("simulationSteps", simTicks);
			// Simulated seconds per real second: 1 when the GPU keeps up with the game.
			o.addProperty("simulationSpeed", round(simTicks * 0.05 / Math.max(seconds, 1e-9)));
			o.addProperty("tickMsAvg", round(tickNanos / 1e6 / Math.max(1, ticks)));
			o.addProperty("tickMsMax", round(maxTickNanos / 1e6));
			o.addProperty("particlesAvg", Math.round(particles / Math.max(1, ticks)));
			o.addProperty("frozenParticlesAvg", Math.round(frozen / Math.max(1, ticks)));
			o.addProperty("physicsStepMsAvg", round(stepMillis / Math.max(1, ticks)));
			o.addProperty("physicsGpuMsAvg", round(simulateMillis / Math.max(1, ticks)));
			o.addProperty("substepsAvg", round(substeps / Math.max(1, ticks)));
			o.addProperty("iterationsAvg", round(iterations / Math.max(1, ticks)));
			o.addProperty("gpuLimitFraction", round((double) limited / Math.max(1, ticks)));
			o.addProperty("asleepFraction", round((double) asleep / Math.max(1, ticks)));
			return o;
		}

		private static long percentile(long[] sorted, double q) {
			if (sorted.length == 0) {
				return 0;
			}
			return sorted[Math.min(sorted.length - 1, (int) Math.floor(q * (sorted.length - 1)))];
		}

		private static double round(double v) {
			return Double.isFinite(v) ? Math.round(v * 100.0) / 100.0 : -1;
		}
	}
}
