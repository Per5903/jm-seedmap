package ru.per.jmseedmap.ui;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.StructureStyles;

/**
 * "Where is the nearest ...?" Picking a type closes the screen and sets a temporary target waypoint
 * on the closest structure of that type the player has not visited yet.
 */
public final class NearestScreen extends OptionsSubScreen {
	public NearestScreen(@Nullable Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("jm_seedmap.screen.nearest"));
	}

	@Override
	protected void addOptions() {
		List<StructureStyles.Group> groups = StructureStyles.groups();
		for (int i = 0; i < groups.size(); i += 2) {
			list.addSmall(button(groups.get(i)), i + 1 < groups.size() ? button(groups.get(i + 1)) : null);
		}
	}

	private Button button(StructureStyles.Group group) {
		return Button.builder(Component.literal(group.displayName()), b -> {
			Minecraft.getInstance().gui.setScreen(null);
			SeedMap.get().findNearest(group::contains, group.displayName());
		}).build();
	}
}
