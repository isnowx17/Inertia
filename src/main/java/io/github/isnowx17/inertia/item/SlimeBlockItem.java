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
 * Places a soft slime block (simulated on the client's GPU) in front of the clicked face; with sneak
 * the slime block looked at is picked up again. The client entrypoint installs the handler.
 */
public class SlimeBlockItem extends Item {
	@FunctionalInterface
	public interface ClientHandler {
		/**
		 * @param cell   block the slime goes into (null: none was clicked)
		 * @param remove sneak: pick up the slime looked at instead
		 */
		boolean use(Player player, BlockPos cell, boolean remove);
	}

	private volatile ClientHandler clientHandler;

	public SlimeBlockItem(Properties properties) {
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
		BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
		BlockPos cell = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().relative(hit.getDirection()) : null;
		return handler.use(player, cell, player.isShiftKeyDown()) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
	}
}
