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

	private static io.github.isnowx17.inertia.client.slime.SlimeWorld slime;

	private static io.github.isnowx17.inertia.client.realistic.RealisticController realistic;

	/** The player and the realistic bodies. */
	public static io.github.isnowx17.inertia.client.realistic.RealisticController realistic() {
		return realistic;
	}

	/** The soft slime blocks. */
	public static io.github.isnowx17.inertia.client.slime.SlimeWorld slime() {
		return slime;
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
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> InertiaCommands.register(dispatcher, buildContext, allFluids()));

		// Realistic blocks, boats and minecarts (PhysX rigid bodies in the water's scene).
		realistic = new io.github.isnowx17.inertia.client.realistic.RealisticController(fluids);
		var rigidRenderer = new io.github.isnowx17.inertia.client.realistic.RigidRenderer(fluids);
		((io.github.isnowx17.inertia.realistic.RealisticItem) InertiaItems.REALISTIC).setClientHandler(realistic::place);
		net.minecraft.client.gui.screens.MenuScreens.register(InertiaItems.REALISER_MENU,
			io.github.isnowx17.inertia.client.realistic.RealiserScreen::new);
		ClientTickEvents.END_CLIENT_TICK.register(realistic::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(rigidRenderer::collectSubmits);

		// Soft slime blocks (finite elements on the GPU).
		slime = new io.github.isnowx17.inertia.client.slime.SlimeWorld(config);
		var slimeRenderer = new io.github.isnowx17.inertia.client.slime.SlimeRenderer(slime);
		((io.github.isnowx17.inertia.item.SlimeBlockItem) InertiaItems.SLIME_BLOCK).setClientHandler((player, cell, remove) -> {
			if (!Minecraft.getInstance().hasSingleplayerServer()) {
				return false;
			}
			if (remove) {
				boolean removed = slime.removeLookedAt(player.getEyePosition(), player.getLookAngle(), 6.0);
				if (removed) {
					player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.inertia.slime_removed", slime.bodyCount()));
				}
				return removed;
			}
			if (cell == null || !player.level().getBlockState(cell).getCollisionShape(player.level(), cell).isEmpty()) {
				return false;
			}
			boolean placed = slime.place(cell);
			if (placed) {
				player.level().playLocalSound(cell, net.minecraft.sounds.SoundEvents.SLIME_BLOCK_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f, false);
			}
			return placed;
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			slime.restoreSaved(client);
			slime.tick(client);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(slime::supportEntities);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> slime.save());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.AFTER_SAVE.register(
			(server, flush, force) -> Minecraft.getInstance().execute(slime::save));
		LevelRenderEvents.COLLECT_SUBMITS.register(slimeRenderer::collectSubmits);
	}
}
