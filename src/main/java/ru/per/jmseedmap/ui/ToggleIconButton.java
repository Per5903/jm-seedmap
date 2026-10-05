package ru.per.jmseedmap.ui;

import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import ru.per.jmseedmap.core.StructureStyles;

/**
 * A structure type switch: badge on the left (grey while off), the name in the middle (scrolls if it is long) and
 * the state on the right as a green check or a red cross, so the state stays visible whatever the name length.
 */
public final class ToggleIconButton extends Button {
	private static final int INDICATOR = 14;

	private final Function<Boolean, Identifier> icon;
	private final BooleanSupplier state;

	/** @param icon badge for "on" (true) or "off" (false) */
	public ToggleIconButton(Function<Boolean, Identifier> icon, int width, Component name, BooleanSupplier state, OnPress onPress) {
		super(0, 0, width, DEFAULT_HEIGHT, name, onPress, DEFAULT_NARRATION);
		this.icon = icon;
		this.state = state;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		extractDefaultSprite(graphics);
		boolean on = state.getAsBoolean();
		int size = StructureStyles.ICON_SIZE;
		graphics.blit(RenderPipelines.GUI_TEXTURED, icon.apply(on), getX() + 3, getY() + 2, 0, 0, 16, 16, size, size, size, size);

		int boxRight = getX() + getWidth() - 3;
		int boxLeft = boxRight - INDICATOR;
		int boxTop = getY() + 3;
		graphics.fill(boxLeft, boxTop, boxRight, boxTop + INDICATOR, on ? 0xFF2E7D32 : 0xFF8E2424);
		String mark = on ? "✔" : "✖";
		var font = net.minecraft.client.Minecraft.getInstance().font;
		graphics.text(font, mark, boxLeft + (INDICATOR - font.width(mark)) / 2 + 1, boxTop + 3, 0xFFFFFFFF);

		var text = graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE);
		text.acceptScrollingWithDefaultCenter(getMessage(), getX() + 21, boxLeft - 3, getY(), getY() + getHeight());
	}
}
