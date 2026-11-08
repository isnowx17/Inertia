package io.github.isnowx17.inertia.client.realistic;

import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.realistic.RealiserMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/** The Realiser's screen: an input slot, an arrow and the realistic result. */
public class RealiserScreen extends AbstractContainerScreen<RealiserMenu> {
	private static final Identifier BACKGROUND = Inertia.id("textures/gui/container/realiser.png");

	public RealiserScreen(RealiserMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos, 0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
	}
}
