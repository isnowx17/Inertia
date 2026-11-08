package io.github.isnowx17.inertia.client;

import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.command.InertiaCommands;
import io.github.isnowx17.inertia.client.config.InertiaConfig;
import io.github.isnowx17.inertia.client.fluid.FluidKind;
import io.github.isnowx17.inertia.client.fluid.FluidManager;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import io.github.isnowx17.inertia.client.render.FluidParticleRenderer;
import io.github.isnowx17.inertia.client.render.FluidSurfaceRenderer;
import io.github.isnowx17.inertia.client.render.WaterPass;
import io.github.isnowx17.inertia.item.FluidBucketItem;
import io.github.isnowx17.inertia.item.FluidSourceItem;
import io.github.isnowx17.inertia.item.InertiaItems;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class InertiaClient implements ClientModInitializer {
	private static FluidManager fluids;
	private static FluidManager lava;

	/** The water's manager. Available after client initialisation. */
	public static FluidManager fluids() {
		return fluids;
	}

	/** The lava's manager. Available after client initialisation. */
	public static FluidManager lava() {
		return lava;
	}

	/** Every liquid's manager: water, lava. */
	public static java.util.List<FluidManager> allFluids() {
		return java.util.List.of(fluids, lava);
	}

	@Override
	public void onInitializeClient() {
		FabricLoader loader = FabricLoader.getInstance();
		InertiaConfig config = InertiaConfig.load(loader.getConfigDir().resolve(Inertia.MOD_ID + ".json"));

		PhysXRuntime.startAsync(loader.getGameDir().resolve(Inertia.MOD_ID).resolve("natives"));

		fluids = new FluidManager(config, FluidKind.WATER);
		lava = new FluidManager(config, FluidKind.LAVA);
		WaterPass.register();
		WaterPass waterPass = new WaterPass();
		WaterPass lavaPass = new WaterPass(true);
		fluids.setWaterShaderStatus(waterPass::statusName);
		lava.setWaterShaderStatus(lavaPass::statusName);
		FluidSurfaceRenderer surfaceRenderer = new FluidSurfaceRenderer(fluids, waterPass);
		FluidSurfaceRenderer lavaRenderer = new FluidSurfaceRenderer(lava, lavaPass);
		FluidParticleRenderer particleRenderer = new FluidParticleRenderer(fluids);
		FluidParticleRenderer lavaParticleRenderer = new FluidParticleRenderer(lava);

		((FluidBucketItem) InertiaItems.FLUID_BUCKET).setClientHandler(fluids::releaseWater);
		((FluidSourceItem) InertiaItems.FLUID_SOURCE).setClientHandler(fluids::toggleSource);
		((FluidBucketItem) InertiaItems.LAVA_BUCKET).setClientHandler(lava::releaseWater);
		((FluidSourceItem) InertiaItems.LAVA_SOURCE).setClientHandler(lava::toggleSource);
		for (FluidManager manager : allFluids()) {
			ClientTickEvents.END_CLIENT_TICK.register(manager::tick);
			// The liquids act on the entities of the integrated server at its own ticks.
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(manager::serverTick);
			ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> manager.onJoin(client));
			ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onDisconnect());
			// The game saving the world (autosave, /save-all, pausing to quit) saves the liquids too.
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.AFTER_SAVE.register(
				(server, flush, force) -> Minecraft.getInstance().execute(() -> manager.saveWater(false)));
			// A game tick that came while the GPU was busy is simulated on the first frame after.
			LevelRenderEvents.START_MAIN.register(context -> manager.onFrame(Minecraft.getInstance()));
		}
		LevelRenderEvents.END_MAIN.register(context -> {
			if (config.renderSurface()) {
				// Lava first: it is opaque, and the water shader then sees it behind the water.
				lavaRenderer.drawShaderSurface(context);
				surfaceRenderer.drawShaderSurface(context);
			}
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			if (config.renderSurface()) {
				lavaRenderer.collectSubmits(context);
				surfaceRenderer.collectSubmits(context);
			} else {
				lavaParticleRenderer.collectSubmits(context);
				particleRenderer.collectSubmits(context);
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> InertiaCommands.register(dispatcher, allFluids()));
	}
}
