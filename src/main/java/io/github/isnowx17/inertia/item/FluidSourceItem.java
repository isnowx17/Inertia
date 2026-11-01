package io.github.isnowx17.inertia.item;

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
 * Places a continuously flowing water source where the player is looking.
 *
 * <p>Right-clicking a block face starts a stream that shoots out of that face (from a wall it pours
 * sideways, from the ground it rises like a fountain, from a ceiling it falls). Right-clicking near
 * an existing source stops it; sneaking and right-clicking stops all sources. Like the PhysX water
 * bucket, the sources live in the client-side GPU simulation and are not saved.
 */
public class FluidSourceItem extends Item {
	private static final double AIR_PLACE_DISTANCE = 2.5;
	/** Sources start this far in front of the clicked face. */
	private static final double FACE_OFFSET = 0.3;

	@FunctionalInterface
	public interface ClientHandler {
		/**
		 * @param position  world position of the source
		 * @param direction direction the water flows out (unit vector)
		 * @param stopAll   whether the player asked to stop every source
		 * @return whether something changed
		 */
		boolean toggle(Player player, Vec3 position, Vec3 direction, boolean stopAll);
	}

	private static volatile ClientHandler clientHandler;

	public FluidSourceItem(Properties properties) {
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

		Vec3 position;
		Vec3 direction;
		BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
		if (hit.getType() == HitResult.Type.BLOCK) {
			Vec3 normal = hit.getDirection().getUnitVec3();
			position = Vec3.atCenterOf(hit.getBlockPos()).add(normal.scale(0.5 + FACE_OFFSET));
			direction = normal;
		} else {
			position = player.getEyePosition().add(player.getLookAngle().scale(AIR_PLACE_DISTANCE));
			direction = player.getLookAngle().normalize();
		}

		boolean changed = handler.toggle(player, position, direction, player.isShiftKeyDown());
		return changed ? InteractionResult.SUCCESS : InteractionResult.FAIL;
	}
}
