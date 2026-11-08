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

	/** An oak room 18 x 18 flooded by two sources high above (as a player's test room). */
	private static final Scene ROOM = new Scene(
		new BlockPos[] {new BlockPos(9, -42, 9), new BlockPos(5, -42, 1)},
		FILL.phases(), FILL.stopSources(), FILL.moveToSurface(), FILL.moveInside(), FILL.finish());

	/**
	 * Pictures for the README: a cliff with two streams falling into a round pond, trees and a
	 * meadow, photographed from a few places without the HUD ({@code showcase-*.png}).
	 */
	private static final Scene SHOWCASE = new Scene(
		new BlockPos[] {new BlockPos(-2, -50, -9), new BlockPos(3, -50, -9), new BlockPos(-5, -61, 1), new BlockPos(5, -61, 1),
			new BlockPos(0, -61, 4), new BlockPos(0, -61, -3)},
		new Object[0][], 100000, 100000, 100000, 100000);

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
	private long lastShot = -1;
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
		scene = "light".equals(sceneName) ? LIGHT : "fill".equals(sceneName) ? FILL : "fill1".equals(sceneName) ? FILL_ONE
			: "room".equals(sceneName) ? ROOM : "showcase".equals(sceneName) ? SHOWCASE : HEAVY;
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
		return scene == LIGHT ? "light" : scene == FILL ? "fill" : scene == FILL_ONE ? "fill1" : scene == ROOM ? "room" : scene == SHOWCASE ? "showcase" : "heavy";
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
		if (worldTicks == 40 && Boolean.getBoolean("inertia.gpuprobe")) {
			try {
				io.github.isnowx17.inertia.client.gpu.GpuProbe.run();
			} catch (Throwable t) {
				LOGGER.error("GPUPROBE failed", t);
			}
			stage = Stage.DONE;
			client.stop();
			return;
		}
		if (scene == SHOWCASE) {
			showcase(client);
			return;
		}
		if (worldTicks == 20) {
			applyOptions(client);
			command(client, "time set 6000");
			command(client, "weather clear");
			command(client, "difficulty peaceful");
			command(client, "fill -8 -60 -8 8 -55 8 minecraft:stone");
			command(client, "fill -7 -60 -7 7 -55 7 minecraft:air");
			command(client, "fill -12 -51 -12 -11 -51 -11 minecraft:stone");
			if (scene == ROOM) {
				command(client, "fill -10 -61 -10 10 -50 10 minecraft:oak_planks");
				command(client, "fill -9 -60 -9 9 -50 9 minecraft:air");
				moveTo(client, -6.5, -57.5, -6.5, -45, -12);
			} else if ((scene == FILL || scene == FILL_ONE)) {
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
			if (client.level != null) {
				StringBuilder entities = new StringBuilder();
				for (net.minecraft.world.entity.Entity e : client.level.entitiesForRendering()) {
					if (e != client.player) {
						entities.append(String.format(java.util.Locale.ROOT, " %s@%.2f,%.2f,%.2f", e.getType().builtInRegistryHolder().key().identifier().getPath(),
							e.getX(), e.getY(), e.getZ()));
					}
				}
				if (!entities.isEmpty()) {
					LOGGER.info("ENTITIES{}", entities);
				}
			}
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
		if (scene == FILL_ONE && event(14, t, 1100)) {
			// Things dropped into the water: they should float or sink, and push the water aside.
			command(client, "summon minecraft:oak_boat -1.5 -47 -1.5");
			command(client, "summon minecraft:minecart 2.5 -47 -1.5");
			command(client, "summon minecraft:cow -1.5 -47 2.5");
			command(client, "summon minecraft:item 2.5 -47 2.5 {Item:{id:\"minecraft:oak_log\",count:1}}");
		}
		if ((scene == FILL_ONE || scene == ROOM) && Boolean.getBoolean("inertia.bench.streamshots")) {
			// Close-ups of the falling stream: the GPU's surface, then the CPU's in the same game.
			if (event(15, t, scene == ROOM ? 600 : 1690)) {
				if (scene == ROOM) {
					// Facing the corner the first stream falls down along the walls.
					moveTo(client, 4.5, -56.5, 4.5, -45, -25);
				} else {
					moveTo(client, 0.5, -50.5, -2.6, 0, 35);
				}
			}
			int from = scene == ROOM ? 610 : 1700;
			if (t >= from && t < from + 200 && t % 10 == 0 && lastShot != t) {
				lastShot = t;
				boolean gpu = t < from + 100;
				InertiaClient.fluids().config().gpuSurface = gpu;
				Screenshot.grab(client.gameDirectory, "bench-" + label + "-stream-" + t + (gpu ? "-gpu" : "-cpu") + ".png",
					client.gameRenderer.mainRenderTarget(), 1, m -> { });
			}
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

	// ------------------------------------------------------------------------------------------
	// Showcase
	// ------------------------------------------------------------------------------------------

	/** Pond centre and half widths. */
	private static final int POND_RX = 9;
	private int sourcesRemoved;
	/** Ticks each view is held for a recorded clip. */
	private static final int CLIP = 140;
	private static final int POND_RZ = 7;

	private void buildShowcase(Minecraft client) {
		command(client, "time set 5000");
		command(client, "weather clear");
		command(client, "difficulty peaceful");
		command(client, "gamerule advance_time false");
		// A meadow of grass and flowers.
		java.util.Random random = new java.util.Random(7);
		command(client, "fill -28 -60 -28 28 -60 28 minecraft:short_grass replace minecraft:air");
		String[] flowers = {"poppy", "dandelion", "oxeye_daisy", "cornflower", "azure_bluet", "allium", "red_tulip"};
		for (int i = 0; i < 160; i++) {
			int x = random.nextInt(57) - 28;
			int z = random.nextInt(57) - 28;
			command(client, "setblock " + x + " -60 " + z + " minecraft:" + flowers[random.nextInt(flowers.length)]);
		}
		// A low bank around the pond.
		for (int z = -POND_RZ - 2; z <= POND_RZ + 2; z++) {
			int w = halfWidth(POND_RX + 2, POND_RZ + 2, z);
			if (w >= 0) {
				command(client, "fill " + -w + " -60 " + z + " " + w + " -60 " + z + " minecraft:grass_block");
			}
		}
		// The pond: sand, some gravel and clay, three blocks deep.
		for (int z = -POND_RZ; z <= POND_RZ; z++) {
			int w = halfWidth(POND_RX, POND_RZ, z);
			command(client, "fill " + -w + " -63 " + z + " " + w + " -63 " + z + " minecraft:sand");
			command(client, "fill " + -w + " -62 " + z + " " + w + " -59 " + z + " minecraft:air");
		}
		command(client, "setblock 2 -62 -4 minecraft:mossy_cobblestone");
		// The cliff, in bands of stone, with two stream beds cut into its top.
		command(client, "fill -12 -60 -22 12 -51 -8 minecraft:stone");
		command(client, "fill -12 -56 -22 12 -55 -8 minecraft:andesite");
		command(client, "fill -12 -53 -22 12 -53 -8 minecraft:tuff");
		command(client, "fill -12 -50 -22 12 -50 -8 minecraft:grass_block");
		command(client, "fill -12 -49 -22 12 -49 -8 minecraft:short_grass replace minecraft:air");
		command(client, "fill -12 -60 -8 -10 -54 -8 minecraft:air");
		command(client, "fill 10 -60 -8 12 -52 -8 minecraft:air");
		command(client, "fill -3 -50 -18 -1 -49 -8 minecraft:air");
		command(client, "fill 2 -50 -18 4 -49 -8 minecraft:air");
		command(client, "fill -3 -51 -18 -1 -51 -8 minecraft:mossy_cobblestone");
		command(client, "fill 2 -51 -18 4 -51 -8 minecraft:mossy_cobblestone");
		// Trees around.
		String[][] trees = {{"fancy_oak", "-15 -60 7"}, {"birch", "14 -60 -2"}, {"oak", "-10 -60 14"}, {"birch", "-17 -60 -5"},
			{"oak", "-8 -49 -16"}, {"fancy_oak", "7 -49 -17"}, {"birch", "4 -49 -12"}, {"oak", "21 -60 -12"},
			{"spruce", "-21 -60 17"}, {"fancy_oak", "25 -60 14"}};
		for (String[] tree : trees) {
			command(client, "place feature minecraft:" + tree[0] + " " + tree[1]);
		}
		command(client, "kill @e[type=item]");
	}

	private static int halfWidth(int rx, int rz, int z) {
		double f = 1.0 - (double) z * z / ((rz + 0.6) * (rz + 0.6));
		return f < 0 ? -1 : (int) Math.round(rx * Math.sqrt(f));
	}

	/** Moves the camera to (x, y, z) (eye height) looking at the target. */
	private void look(Minecraft client, double x, double y, double z, double tx, double ty, double tz) {
		double dx = tx - x;
		double dy = ty - y;
		double dz = tz - z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		moveTo(client, x, y - 1.62, z, yaw, pitch);
	}

	private static void report(FluidManager fluids, String when) {
		FluidSimulation sim = fluids.simulation();
		LOGGER.info("TRAVEL {}: simulated {} particles and {} frozen in {} columns, asleep far away {} columns", when,
			sim != null ? sim.activeCount() : 0, sim != null ? sim.frozenParticles() : 0, sim != null ? sim.frozenColumns() : 0,
			fluids.dormantColumns());
	}

	private void shot(Minecraft client, String name) {
		Screenshot.grab(client.gameDirectory, "showcase-" + name + ".png", client.gameRenderer.mainRenderTarget(), 1, m -> { });
		LOGGER.info("SHOWCASE shot {}", name);
	}

	private void showcase(Minecraft client) {
		if (worldTicks == 20) {
			applyOptions(client);
			client.options.renderDistance().set(12);
			if (!client.gui.hud.isHidden()) {
				client.gui.hud.toggle();
			}
			InertiaClient.fluids().config().sourceFlow = Float.parseFloat(System.getProperty("inertia.showcase.flow", "15"));
			buildShowcase(client);
			if (Boolean.getBoolean("inertia.showcase.film")) {
				// Filmed in slow motion where the machine is slow, and played back at the game's speed.
				command(client, "tick rate " + Integer.getInteger("inertia.film.tps", 20));
			}
			look(client, 13.5, -53, 14.5, 0, -58.5, -3);
		}
		if (worldTicks % 100 == 0 && InertiaClient.fluids().simulation() != null) {
			FluidSimulation s = InertiaClient.fluids().simulation();
			var heard = s.acoustics();
			LOGGER.info("WATER t={} active={} frozen={} fps={} splashes={} top={} flows={} top={}", worldTicks, s.activeCount(),
				s.frozenParticles(), client.getFps(), heard != null ? heard.splashCount : -1,
				heard != null && heard.splashCount > 0 ? heard.splashes[3] : 0, heard != null ? heard.flowCount : -1,
				heard != null && heard.flowCount > 0 ? heard.flows[3] : 0);

		}
		FluidManager fluids = InertiaClient.fluids();
		BlockPos[] sources = scene.sources();
		if (sourcesPlaced < sources.length && worldTicks >= 200 + 20 * sourcesPlaced
			&& fluids.toggleSource(client.player, sources[sourcesPlaced], false)) {
			sourcesPlaced++;
			if (sourcesPlaced == sources.length) {
				startTick = worldTicks;
			}
		}
		if (startTick < 0) {
			return;
		}
		long t = worldTicks - startTick;
		int at = Integer.getInteger("inertia.showcase.at", 700);
		// The sources in the pond only fill it: off before the pictures (one per toggle cooldown).
		int fillEnd = Integer.getInteger("inertia.showcase.fill", 500);
		if (t >= fillEnd && sourcesRemoved < sources.length - 2
			&& fluids.toggleSource(client.player, sources[2 + sourcesRemoved], false)) {
			sourcesRemoved++;
			LOGGER.info("SHOWCASE pond source {} off at t={}", sourcesRemoved, t);
		}
		if (event(0, t, 300)) {
			command(client, "summon minecraft:oak_boat 4.5 -58 3.5 {Rotation:[60f,0f]}");
		}
		if (Boolean.getBoolean("inertia.showcase.film")) {
			// The film runs frame by frame (see filmFrame) once the pond is full.
			if (filmStart == 0L && t >= at) {
				hold = false;
				command(client, "gamemode spectator @a");
				client.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
				filmStart = System.nanoTime();
			}
			return;
		}
		if (Boolean.getBoolean("inertia.showcase.travel")) {
			// Far water: the player goes 400 blocks away (the water sleeps, then leaves the simulation),
			// the water is saved and read back while far, and the player comes back.
			if (event(1, t, at)) {
				look(client, 13.5, -53, 14.5, 0, -58.5, -3);
			}
			if (event(2, t, at + 40)) {
				report(fluids, "before");
				shot(client, "before-travel");
				moveTo(client, 400.5, -60, 0.5, 0, 0);
			}
			if (event(3, t, at + 160)) {
				report(fluids, "far");
				fluids.reloadWater(client);
			}
			if (event(4, t, at + 220)) {
				report(fluids, "far, read back");
				look(client, 13.5, -53, 14.5, 0, -58.5, -3);
			}
			if (event(5, t, at + 400)) {
				report(fluids, "back");
				shot(client, "after-travel");
			}
			if (event(6, t, at + 420)) {
				stage = Stage.DONE;
				client.stop();
			}
			return;
		}
		if (Boolean.getBoolean("inertia.showcase.reload")) {
			// Saving: the water before, saved and read back, and after.
			FluidSimulation sim = fluids.simulation();
			if (event(1, t, at)) {
				look(client, 13.5, -53, 14.5, 0, -58.5, -3);
			}
			if (event(2, t, at + 40) && sim != null) {
				LOGGER.info("SAVE before: {} particles, {} frozen in {} columns", sim.activeCount(), sim.frozenParticles(), sim.frozenColumns());
				shot(client, "before-reload");
				fluids.reloadWater(client);
			}
			if (event(3, t, at + 41)) {
				LOGGER.info("SAVE removed: simulation {}", fluids.simulation() == null ? "closed" : "still there");
			}
			if (event(4, t, at + 120) && sim != null) {
				FluidSimulation again = fluids.simulation();
				LOGGER.info("SAVE after: {}", again == null ? "nothing restored"
					: again.activeCount() + " particles, " + again.frozenParticles() + " frozen in " + again.frozenColumns() + " columns");
				shot(client, "after-reload");
			}
			if (event(5, t, at + 140)) {
				stage = Stage.DONE;
				client.stop();
			}
			return;
		}
		if (Boolean.getBoolean("inertia.showcase.gif")) {
			// Clips: each view held for CLIP ticks while the screen is recorded from outside.
			double[][] views = {
				{13.5, -53, 14.5, 0, -58.5, -3, 5000},
				{5.5, -57.5, 3.5, -1, -58, -7, 5000},
				{8, -59, 1.5, -8, -60.4, -0.5, 12300},
				{-4.5, -61.6, 3.5, 0.5, -60.4, -6, 5000}};
			for (int v = 0; v <= views.length; v++) {
				if (event(1 + v, t, at + v * CLIP)) {
					if (v == views.length) {
						stage = Stage.DONE;
						client.stop();
						return;
					}
					double[] w = views[v];
					command(client, "time set " + (int) w[6]);
					command(client, "gamemode " + (v == 3 ? "spectator" : "creative") + " @a");
					look(client, w[0], w[1], w[2], w[3], w[4], w[5]);
					LOGGER.info("SHOWCASE clip {} at {}", v, System.currentTimeMillis());
				}
			}
			return;
		}
		if (event(1, t, at)) {
			look(client, 13.5, -53, 14.5, 0, -58.5, -3);
		}
		if (event(2, t, at + 40)) {
			shot(client, "wide");
			look(client, 5.5, -57.5, 3.5, -1, -58, -7);
		}
		if (event(3, t, at + 80)) {
			shot(client, "falls");
			look(client, 1.5, -49, 9.5, 0, -61, -1);
		}
		if (event(4, t, at + 120)) {
			shot(client, "above");
			command(client, "time set 12300");
			look(client, 8, -59, 1.5, -8, -60.4, -0.5);
		}
		if (event(5, t, at + 160)) {
			shot(client, "sunset");
			command(client, "time set 5000");
			command(client, "gamemode spectator @a");
			look(client, -4.5, -61.6, 3.5, 0.5, -60.4, -6);
		}
		if (event(6, t, at + 200)) {
			shot(client, "underwater");
		}
		if (event(7, t, at + 220)) {
			stage = Stage.DONE;
			client.stop();
		}
	}

	// ------------------------------------------------------------------------------------------
	// Film: camera moves along each shot frame by frame, the frames go into a video per shot
	// ------------------------------------------------------------------------------------------

	/**
	 * Shots: eye from (x, y, z) to (x, y, z), looking from (x, y, z) to (x, y, z), seconds, time of day.
	 */
	private static final double[][] SHOTS = {
		{22, -47, 22, 15, -52, 15, 0, -58, -3, 0, -58, -4, 8, 11800},
		{8, -57, 5, -5, -57, 5, 2, -55, -7, -1, -55, -7, 7, 5000},
		{3.5, -58.6, -1, 2.2, -58.8, -3.2, 0.5, -60, -6.5, 0.5, -60.2, -6.5, 6, 5000},
		{4, -51, 7, -3, -51, 3, 0.5, -61, 0.5, -0.5, -61, -0.5, 6, 5000},
		{9, -59.1, 2.5, 6, -59.3, 0.5, -8, -60.4, -0.5, -8, -60.4, -1.5, 8, 12300},
		{-5, -61.7, 4, -1.5, -61.7, 0.5, 0.5, -60.4, -6, 1, -60.3, -6.5, 6, 5000},
		{0, -56, 9, 0, -42, 28, 0, -58, -2, 0, -57, -4, 8, 5000}};
	/** Seconds at the start of each shot before recording: the camera and the light settle. */
	private static final double SHOT_PREP = 0.7;
	private long filmStart;
	private int filmShot = -1;
	private FrameRecorder recorder;

	private static double filmSpeed() {
		return Math.min(1.0, Integer.getInteger("inertia.film.tps", 20) / 20.0);
	}

	private void filmFrame(Minecraft client) {
		if (recorder == null) {
			recorder = new FrameRecorder(System.getProperty("inertia.film.ffmpeg", "ffmpeg"), 60, filmSpeed());
		}
		// Film time: the game's time (slower than real time with a lower tick rate).
		double t = (System.nanoTime() - filmStart) / 1e9 * filmSpeed();
		int shot = 0;
		double start = 0;
		while (shot < SHOTS.length && t >= start + SHOT_PREP + SHOTS[shot][12]) {
			start += SHOT_PREP + SHOTS[shot][12];
			shot++;
		}
		if (shot != filmShot) {
			recorder.stop();
			filmShot = shot;
			if (shot >= SHOTS.length) {
				filmStart = 0L;
				stage = Stage.DONE;
				client.stop();
				return;
			}
			command(client, "time set " + (int) SHOTS[shot][13]);
		}
		double[] s = SHOTS[shot];
		double local = t - start;
		double u = Math.max(0, Math.min(1, (local - SHOT_PREP) / s[12]));
		double e = u * u * (3 - 2 * u);
		double x = s[0] + (s[3] - s[0]) * e;
		double y = s[1] + (s[4] - s[1]) * e;
		double z = s[2] + (s[5] - s[2]) * e;
		double lx = s[6] + (s[9] - s[6]) * e;
		double ly = s[7] + (s[10] - s[7]) * e;
		double lz = s[8] + (s[11] - s[8]) * e;
		double dx = lx - x;
		double dy = ly - y;
		double dz = lz - z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		if (client.player != null) {
			client.player.snapTo(x, y - 1.62, z, yaw, pitch);
			client.player.setYHeadRot(yaw);
			client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
			client.player.setOldPosAndRot();
		}
		if (local >= SHOT_PREP) {
			if (!recorder.recording()) {
				java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("inertia.film.dir", "film"));
				try {
					java.nio.file.Files.createDirectories(dir);
				} catch (java.io.IOException ignored) {
				}
				recorder.start(dir.resolve("shot-" + shot + ".mp4").toAbsolutePath());
			}
			recorder.capture(client);
		}
	}

	private void frame() {
		if (filmStart != 0L) {
			filmFrame(Minecraft.getInstance());
		}
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
