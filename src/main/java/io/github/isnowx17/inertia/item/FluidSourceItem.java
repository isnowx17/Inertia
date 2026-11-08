package io.github.isnowx17.inertia.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Places a flowing water source, like a water bucket places a water source block.
 *
 * <p>Right-clicking a block face places the source in the block in front of that face (or in the
 * clicked block itself when it can be replaced, like tall grass). The source keeps its block full
 * of water, which flows out of it: down when there is room below, otherwise outwards along the
 * ground. Placing a block into the source stops it, and so does right-clicking the same place
 * again; sneaking and right-clicking stops all sources. Like the PhysX water bucket, the sources
 * live in the client-side GPU simulation and are not saved.
 */
public class FluidSourceItem extends Item {
	@FunctionalInterface
	public interface ClientHandler {
		/**
		 * @param cell    block the source is placed in or removed from, or {@code null} with stopAll
		 * @param stopAll whether the player asked to stop every source
		 * @return whether something changed
		 */
		boolean toggle(Player player, BlockPos cell, boolean stopAll);
	}

	private volatile ClientHandler clientHandler;

	public FluidSourceItem(Properties properties) {
		super(properties);
	}

	public void setClientHandler(ClientHandler handler) {
		clientHandler = handler;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!level.isClientSide()) {
			return InteractionResult.SUCCESS_SERVER;
		}
		ClientHandler handler = clientHandler;
		if (handler == null) {
			return InteractionResult.FAIL;
		}
		if (player.isShiftKeyDown()) {
			return handler.toggle(player, null, true) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
		}

		BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return InteractionResult.PASS;
		}
		BlockPos clicked = hit.getBlockPos();
		BlockPos cell = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
		return handler.toggle(player, cell, false) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
	}
}
