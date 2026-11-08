package io.github.isnowx17.inertia.client.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.isnowx17.inertia.client.InertiaClient;
import io.github.isnowx17.inertia.client.fluid.RigidBodies;
import io.github.isnowx17.inertia.client.fluid.FluidManager;
import io.github.isnowx17.inertia.client.fluid.FluidSimulation;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Client commands: {@code /inertia info} shows the GPU and simulation state; {@code ball}, {@code lava},
 * {@code slime}, {@code block}, {@code boat} and {@code minecart} put things in front of the player;
 * {@code clear} removes everything or one kind.
 */
public final class InertiaCommands {
	private InertiaCommands() {
	}

	/** The largest ball of water or lava (blocks across), cube of slime and realistic cube. */
	private static final int MAX_BALL = 16;
	private static final int MAX_SLIME = 4;
	private static final int MAX_BLOCK = 8;

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext buildContext,
		java.util.List<FluidManager> managers) {
		FluidManager first = managers.get(0);
		dispatcher.register(literal("inertia")
			.then(literal("ball")
				.executes(ctx -> ball(ctx, InertiaClient.fluids(), 3, "command.inertia.ball"))
				.then(argument("size", IntegerArgumentType.integer(1, MAX_BALL))
					.executes(ctx -> ball(ctx, InertiaClient.fluids(), IntegerArgumentType.getInteger(ctx, "size"), "command.inertia.ball"))))
			.then(literal("lava")
				.executes(ctx -> ball(ctx, InertiaClient.lava(), 3, "command.inertia.lava"))
				.then(argument("size", IntegerArgumentType.integer(1, MAX_BALL))
					.executes(ctx -> ball(ctx, InertiaClient.lava(), IntegerArgumentType.getInteger(ctx, "size"), "command.inertia.lava"))))
			.then(literal("slime")
				.executes(ctx -> slime(ctx, 1))
				.then(argument("size", IntegerArgumentType.integer(1, MAX_SLIME))
					.executes(ctx -> slime(ctx, IntegerArgumentType.getInteger(ctx, "size")))))
			.then(literal("block")
				.then(argument("block", BlockStateArgument.block(buildContext))
					.executes(ctx -> block(ctx, 1))
					.then(argument("size", IntegerArgumentType.integer(1, MAX_BLOCK))
						.executes(ctx -> block(ctx, IntegerArgumentType.getInteger(ctx, "size"))))))
			.then(literal("boat").executes(ctx -> vehicle(ctx, RigidBodies.Kind.BOAT, "minecraft:oak_boat")))
			.then(literal("minecart").executes(ctx -> vehicle(ctx, RigidBodies.Kind.MINECART, "minecraft:minecart")))
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
				if (InertiaClient.slime() != null) {
					InertiaClient.slime().clear();
				}
				ctx.getSource().sendFeedback(Component.translatable("command.inertia.clear", removed));
				return removed;
			})
				.then(literal("water").executes(ctx -> cleared(ctx, InertiaClient.fluids().clearLiquid(), "command.inertia.clear")))
				.then(literal("lava").executes(ctx -> cleared(ctx, InertiaClient.lava().clearLiquid(), "command.inertia.clear.lava")))
				.then(literal("slime").executes(ctx -> cleared(ctx, InertiaClient.slime() != null ? InertiaClient.slime().clear() : 0,
					"command.inertia.clear.slime")))
				.then(literal("bodies").executes(ctx -> cleared(ctx, InertiaClient.fluids().clearBodies(), "command.inertia.clear.bodies")))));
	}

	private static int cleared(CommandContext<FabricClientCommandSource> ctx, int removed, String message) {
		ctx.getSource().sendFeedback(Component.translatable(message, removed));
		return removed;
	}

	/** Where something {@code size} blocks across is put: in front of the player's eyes, clear of them. */
	private static Vec3 inFront(Player player, int size) {
		return player.getEyePosition().add(player.getLookAngle().scale(size * 0.5 + 2.5));
	}

	/** A round ball of water or lava {@code size} blocks across in front of the player, falling from there. */
	private static int ball(CommandContext<FabricClientCommandSource> ctx, FluidManager manager, int size, String message) {
		Player player = ctx.getSource().getPlayer();
		if (manager == null || !manager.releaseBall(player, inFront(player, size), size * 0.5)) {
			return 0;
		}
		ctx.getSource().sendFeedback(Component.translatable(message, size));
		return size;
	}

	/** A cube of slime blocks {@code size} blocks across in front of the player, in the open cells. */
	private static int slime(CommandContext<FabricClientCommandSource> ctx, int size) {
		var slime = InertiaClient.slime();
		Player player = ctx.getSource().getPlayer();
		if (slime == null) {
			return 0;
		}
		BlockPos base = BlockPos.containing(inFront(player, size)).offset(-size / 2, -size / 2, -size / 2);
		int placed = 0;
		for (int x = 0; x < size; x++) {
			for (int y = 0; y < size; y++) {
				for (int z = 0; z < size; z++) {
					BlockPos pos = base.offset(x, y, z);
					if (player.level().getBlockState(pos).canBeReplaced() && slime.place(pos)) {
						placed++;
					}
				}
			}
		}
		ctx.getSource().sendFeedback(Component.translatable("command.inertia.slime", placed));
		return placed;
	}

	/** A realistic cube of {@code size} blocks across in front of the player: one body. */
	private static int block(CommandContext<FabricClientCommandSource> ctx, int size) {
		BlockState state = ctx.getArgument("block", BlockInput.class).getState();
		Player player = ctx.getSource().getPlayer();
		BlockPos at = BlockPos.containing(inFront(player, size));
		FluidSimulation sim = InertiaClient.fluids().simulationForBodies(player, at);
		if (sim == null) {
			return 0;
		}
		List<RigidBodies.Part> parts = new ArrayList<>();
		for (int x = 0; x < size; x++) {
			for (int y = 0; y < size; y++) {
				for (int z = 0; z < size; z++) {
					parts.add(new RigidBodies.Part(x - size / 2, y - size / 2, z - size / 2, state));
				}
			}
		}
		Vec3 centre = Vec3.atCenterOf(at).subtract(sim.originX(), sim.originY(), sim.originZ());
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		sim.rigids().restore(RigidBodies.Kind.BLOCKS, id, parts, centre, new Quaternionf());
		ctx.getSource().sendFeedback(Component.translatable("command.inertia.block", state.getBlock().getName(), parts.size()));
		return parts.size();
	}

	/** A realistic boat or minecart on the ground in front of the player, facing the way they look. */
	private static int vehicle(CommandContext<FabricClientCommandSource> ctx, RigidBodies.Kind kind, String source) {
		Player player = ctx.getSource().getPlayer();
		Vec3 look = player.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0, look.z);
		flat = flat.lengthSqr() > 1e-6 ? flat.normalize() : new Vec3(0, 0, 1);
		Vec3 at = player.position().add(flat.scale(2.5)).add(0, 0.6, 0);
		FluidSimulation sim = InertiaClient.fluids().simulationForBodies(player, BlockPos.containing(at));
		if (sim == null) {
			return 0;
		}
		sim.rigids().addVehicle(kind, source, at.subtract(sim.originX(), sim.originY(), sim.originZ()), player.getYRot());
		var type = BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.tryParse(source));
		ctx.getSource().sendFeedback(Component.translatable("command.inertia.vehicle", type.getDescription()));
		return 1;
	}
}
