package ru.per.jmseedmap.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import ru.per.jmseedmap.core.StructureStyles;

/** A button with a structure badge on the left. */
public final class IconButton extends Button {
	private final Identifier icon;

	public IconButton(Identifier icon, int width, Component message, OnPress onPress) {
		super(0, 0, width, DEFAULT_HEIGHT, message, onPress, DEFAULT_NARRATION);
		this.icon = icon;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		extractDefaultSprite(graphics);
		int size = StructureStyles.ICON_SIZE;
		graphics.blit(RenderPipelines.GUI_TEXTURED, icon, getX() + 3, getY() + 2, 0, 0, 16, 16, size, size, size, size);
		extractScrollingStringOverContents(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE), getMessage(), 21);
	}
}
