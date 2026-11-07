package io.github.isnowx17.inertia.client;

import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.command.InertiaCommands;
import io.github.isnowx17.inertia.client.config.InertiaConfig;
import io.github.isnowx17.inertia.client.fluid.FluidManager;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import io.github.isnowx17.inertia.client.render.FluidParticleRenderer;
import io.github.isnowx17.inertia.client.render.FluidSurfaceRenderer;
import io.github.isnowx17.inertia.client.render.WaterPass;
import io.github.isnowx17.inertia.item.FluidBucketItem;
import io.github.isnowx17.inertia.item.FluidSourceItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class InertiaClient implements ClientModInitializer {
	private static FluidManager fluids;

	/** The fluid manager of this client. Available after client initialisation. */
	public static FluidManager fluids() {
		return fluids;
	}

	@Override
	public void onInitializeClient() {
		FabricLoader loader = FabricLoader.getInstance();
		InertiaConfig config = InertiaConfig.load(loader.getConfigDir().resolve(Inertia.MOD_ID + ".json"));

		PhysXRuntime.startAsync(loader.getGameDir().resolve(Inertia.MOD_ID).resolve("natives"));

		fluids = new FluidManager(config);
		WaterPass.register();
		WaterPass waterPass = new WaterPass();
		fluids.setWaterShaderStatus(waterPass::statusName);
		FluidSurfaceRenderer surfaceRenderer = new FluidSurfaceRenderer(fluids, waterPass);
		FluidParticleRenderer particleRenderer = new FluidParticleRenderer(fluids);

		FluidBucketItem.setClientHandler(fluids::releaseWater);
		FluidSourceItem.setClientHandler(fluids::toggleSource);
		ClientTickEvents.END_CLIENT_TICK.register(fluids::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> fluids.onJoin(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> fluids.onDisconnect());
		// A game tick that came while the GPU was busy is simulated on the first frame after.
		LevelRenderEvents.START_MAIN.register(context -> fluids.onFrame(Minecraft.getInstance()));
		LevelRenderEvents.END_MAIN.register(context -> {
			if (config.renderSurface()) {
				surfaceRenderer.drawShaderSurface(context);
			}
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			if (config.renderSurface()) {
				surfaceRenderer.collectSubmits(context);
			} else {
				particleRenderer.collectSubmits(context);
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> InertiaCommands.register(dispatcher, fluids));
	}
}
