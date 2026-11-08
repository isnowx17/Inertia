package io.github.isnowx17.inertia.realistic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A realistic thing from the Realiser: a block, a boat or a minecart that becomes a PhysX rigid body
 * when placed (simulated on the client's GPU). It looks and is named like what it was made from;
 * what that was is kept in its custom data ({@code source}: item id, {@code kind}: block, boat or
 * minecart). The client entrypoint installs the handler that places it.
 */
public class RealisticItem extends Item {
	public static final String BLOCK = "block";
	public static final String BOAT = "boat";
	public static final String MINECART = "minecart";

	@FunctionalInterface
	public interface ClientHandler {
		/** Places the realistic thing {@code stack} where the player looks; whether it was placed. */
		boolean place(Player player, InteractionHand hand, ItemStack stack, String kind, Identifier source);
	}

	private volatile ClientHandler clientHandler;

	public RealisticItem(Properties properties) {
		super(properties);
	}

	public void setClientHandler(ClientHandler handler) {
		clientHandler = handler;
	}

	/** The realistic version of {@code input} (one of it), or empty when it has none. */
	public static ItemStack of(Item realistic, ItemStack input) {
		if (input.isEmpty()) {
			return ItemStack.EMPTY;
		}
		Item item = input.getItem();
		String kind;
		if (item instanceof BoatItem) {
			kind = BOAT;
		} else if (item instanceof MinecartItem) {
			kind = MINECART;
		} else if (item instanceof BlockItem blockItem && !(blockItem.getBlock() instanceof EntityBlock)) {
			BlockState state = blockItem.getBlock().defaultBlockState();
			if (!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
				return ItemStack.EMPTY;
			}
			kind = BLOCK;
		} else {
			return ItemStack.EMPTY;
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		ItemStack out = new ItemStack(realistic);
		CompoundTag tag = new CompoundTag();
		tag.putString("source", id.toString());
		tag.putString("kind", kind);
		out.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		out.set(DataComponents.ITEM_MODEL, id);
		out.set(DataComponents.ITEM_NAME, Component.translatable("item.inertia.realistic.named", input.getHoverName()));
		return out;
	}

	/** What a realistic stack was made from, or null. */
	public static Identifier source(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		return data.copyTag().getString("source").map(Identifier::tryParse).orElse(null);
	}

	public static String kind(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? null : data.copyTag().getString("kind").orElse(null);
	}

	/** The realistic stack for a source item id and kind (when picking a body up again). */
	public static ItemStack forSource(Item realistic, Identifier source) {
		Item item = BuiltInRegistries.ITEM.getValue(source);
		return of(realistic, new ItemStack(item));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!level.isClientSide()) {
			return InteractionResult.SUCCESS_SERVER;
		}
		ClientHandler handler = clientHandler;
		Identifier source = source(stack);
		String kind = kind(stack);
		if (handler == null || source == null || kind == null) {
			return InteractionResult.FAIL;
		}
		return handler.place(player, hand, stack, kind, source) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
	}
}
