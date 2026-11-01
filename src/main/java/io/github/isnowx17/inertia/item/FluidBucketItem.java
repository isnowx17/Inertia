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
import net.minecraft.world.phys.Vec3;

/**
 * Releases one block worth of GPU-simulated water where the player is looking.
 *
 * <p>The simulation itself is client-only (it runs on the local NVIDIA GPU), so the item
 * forwards the request to a handler that the client entrypoint installs. On the logical
 * server the item does nothing. The bucket is never emptied: picking fluid back up is not
 * part of v0.1.0.
 */
public class FluidBucketItem extends Item {
	/** How far in front of the eyes the water appears when the player is not looking at a block. */
	private static final double AIR_PLACE_DISTANCE = 2.5;

	@FunctionalInterface
	public interface ClientHandler {
		/**
		 * @param center      world position of the centre of the 1x1x1 water volume
		 * @param velocity    initial velocity of the water in blocks per second
		 * @return whether water was released
		 */
		boolean release(Player player, Vec3 center, Vec3 velocity);
	}

	private static volatile ClientHandler clientHandler;

	public FluidBucketItem(Properties properties) {
		super(properties);
	}

	public static void setClientHandler(ClientHandler handler) {
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

		Vec3 center;
		BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
		if (hit.getType() == HitResult.Type.BLOCK) {
			BlockPos target = hit.getBlockPos().relative(hit.getDirection());
			center = Vec3.atCenterOf(target);
		} else {
			center = player.getEyePosition().add(player.getLookAngle().scale(AIR_PLACE_DISTANCE));
		}

		// A gentle push in the looking direction makes pouring feel natural.
		Vec3 velocity = player.getLookAngle().scale(1.5);
		return handler.release(player, center, velocity) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
	}
}
