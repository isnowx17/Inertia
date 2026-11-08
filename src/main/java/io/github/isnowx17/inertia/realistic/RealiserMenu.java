package io.github.isnowx17.inertia.realistic;

import io.github.isnowx17.inertia.item.InertiaItems;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The Realiser: a block, boat or minecart goes in, its realistic version comes out (one for one). */
public class RealiserMenu extends AbstractContainerMenu {
	private static final int INPUT = 0;
	private static final int RESULT = 1;
	private static final int INVENTORY_START = 2;
	private static final int INVENTORY_END = 38;

	private final ContainerLevelAccess access;
	private final Container input = new SimpleContainer(1) {
		@Override
		public void setChanged() {
			super.setChanged();
			RealiserMenu.this.slotsChanged(this);
		}
	};
	private final ResultContainer result = new ResultContainer();

	/** Client side. */
	public RealiserMenu(int containerId, Inventory inventory) {
		this(containerId, inventory, ContainerLevelAccess.NULL);
	}

	public RealiserMenu(int containerId, Inventory inventory, ContainerLevelAccess access) {
		super(InertiaItems.REALISER_MENU, containerId);
		this.access = access;
		addSlot(new Slot(input, 0, 44, 35) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return !RealisticItem.of(InertiaItems.REALISTIC, stack).isEmpty();
			}
		});
		addSlot(new Slot(result, 0, 116, 35) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}

			@Override
			public void onTake(Player player, ItemStack carried) {
				input.removeItem(0, 1);
				super.onTake(player, carried);
			}
		});
		addStandardInventorySlots(inventory, 8, 84);
	}

	@Override
	public void slotsChanged(Container container) {
		super.slotsChanged(container);
		if (container == input) {
			result.setItem(0, RealisticItem.of(InertiaItems.REALISTIC, input.getItem(0)));
		}
	}

	@Override
	public boolean stillValid(Player player) {
		return stillValid(access, player, InertiaItems.REALISER_BLOCK);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		result.removeItemNoUpdate(0);
		access.execute((level, pos) -> clearContainer(player, input));
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		Slot slot = slots.get(slotIndex);
		if (slot == null || !slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack clicked = stack.copy();
		if (slotIndex == RESULT) {
			// One at a time: every realistic thing takes one from the input.
			if (!moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, true)) {
				return ItemStack.EMPTY;
			}
			slot.onTake(player, clicked);
			return ItemStack.EMPTY;
		}
		if (slotIndex == INPUT) {
			if (!moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, false)) {
				return ItemStack.EMPTY;
			}
		} else if (!RealisticItem.of(InertiaItems.REALISTIC, stack).isEmpty()) {
			if (!moveItemStackTo(stack, INPUT, INPUT + 1, false)) {
				return ItemStack.EMPTY;
			}
		} else {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		}
		slot.setChanged();
		return clicked;
	}
}
