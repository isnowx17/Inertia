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

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, java.util.List<FluidManager> managers) {
		FluidManager first = managers.get(0);
		dispatcher.register(literal("inertia")
			.then(literal("info").executes(ctx -> {
				FabricClientCommandSource source = ctx.getSource();
				source.sendFeedback(Component.translatable("command.inertia.info.quality", first.config().quality, first.waterShaderStatus()));
				source.sendFeedback(Component.translatable(
					"command.inertia.info.physx",
					PhysXRuntime.state().name().toLowerCase(Locale.ROOT),
					PhysXRuntime.statusDetail()
				));
				for (FluidManager manager : managers) {
					FluidSimulation sim = manager.simulation();
					String name = manager.kind().id;
					if (sim == null) {
						source.sendFeedback(Component.literal(name + ": ").append(Component.translatable("command.inertia.info.idle")));
						continue;
					}
					source.sendFeedback(Component.literal(name + ": ").append(Component.translatable(
						"command.inertia.info.simulation",
						sim.activeCount(),
						sim.limit(),
						sim.foamCount(),
						sim.collisionSections(),
						String.format(Locale.ROOT, "%.2f", sim.lastStepMillis())
					)));
					source.sendFeedback(Component.literal(name + ": ").append(Component.translatable(
						"command.inertia.info.solver",
						sim.currentSubsteps(),
						sim.currentIterations(),
						sim.atGpuLimit() ? "\u2714" : "\u2718",
						sim.sleeping() ? "\u2714" : "\u2718"
					)));
					source.sendFeedback(Component.literal(name + ": ")
						.append(Component.translatable("command.inertia.info.frozen", sim.frozenColumns(), sim.frozenParticles())));
				}
				return 1;
			}))
			.then(literal("clear").executes(ctx -> {
				int removed = 0;
				for (FluidManager manager : managers) {
					removed += manager.clear();
				}
				if (io.github.isnowx17.inertia.client.InertiaClient.slime() != null) {
					io.github.isnowx17.inertia.client.InertiaClient.slime().clear();
				}
				ctx.getSource().sendFeedback(Component.translatable("command.inertia.clear", removed));
				return removed;
			})));
	}
}
