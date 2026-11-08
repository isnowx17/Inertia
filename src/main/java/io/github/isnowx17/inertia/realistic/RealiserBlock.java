package io.github.isnowx17.inertia.realistic;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The Realiser: right-click opens it; a block, boat or minecart put in comes out realistic. */
public class RealiserBlock extends Block {
	private static final Component TITLE = Component.translatable("container.inertia.realiser");

	public RealiserBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (!level.isClientSide()) {
			player.openMenu(state.getMenuProvider(level, pos));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
		return new SimpleMenuProvider((containerId, inventory, player) -> new RealiserMenu(containerId, inventory,
			ContainerLevelAccess.create(level, pos)), TITLE);
	}
}
