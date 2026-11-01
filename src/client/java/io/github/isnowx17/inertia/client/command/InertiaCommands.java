package io.github.isnowx17.inertia.client.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import io.github.isnowx17.inertia.client.fluid.FluidManager;
import io.github.isnowx17.inertia.client.fluid.FluidSimulation;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Client commands for testing:
 * {@code /inertia info} shows the GPU and simulation state, {@code /inertia clear} removes all water.
 */
public final class InertiaCommands {
	private InertiaCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, FluidManager manager) {
		dispatcher.register(literal("inertia")
			.then(literal("info").executes(ctx -> {
				FabricClientCommandSource source = ctx.getSource();
				source.sendFeedback(Component.translatable("command.inertia.info.quality", manager.config().quality, manager.waterShaderStatus()));
				source.sendFeedback(Component.translatable(
					"command.inertia.info.physx",
					PhysXRuntime.state().name().toLowerCase(Locale.ROOT),
					PhysXRuntime.statusDetail()
				));
				FluidSimulation sim = manager.simulation();
				if (sim == null) {
					source.sendFeedback(Component.translatable("command.inertia.info.idle"));
				} else {
					source.sendFeedback(Component.translatable(
						"command.inertia.info.simulation",
						sim.activeCount(),
						sim.limit(),
						sim.foamCount(),
						sim.collisionSections(),
						String.format(Locale.ROOT, "%.2f", sim.lastStepMillis())
					));
				}
				return 1;
			}))
			.then(literal("clear").executes(ctx -> {
				int removed = manager.clear();
				ctx.getSource().sendFeedback(Component.translatable("command.inertia.clear", removed));
				return removed;
			})));
	}
}
