package ru.per.jmseedmap.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.StructureStyles;
import ru.per.jmseedmap.core.WorldData;

/**
 * All SeedMap pins of this world, nearest first: pick one to rename it, make it the target, copy its
 * coordinates or delete it.
 */
public final class PinsScreen extends OptionsSubScreen {
	/** Selected pin, as {@code key + "|" + target}; survives rebuilding the list. */
	private static @Nullable String selected;
	private @Nullable EditBox nameBox;

	public PinsScreen(@Nullable Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("jm_seedmap.pins.title"));
	}

	@Override
	protected void init() {
		layout.removeChildren();
		super.init();
	}

	@Override
	protected void addOptions() {
		SeedMap seedMap = SeedMap.get();
		SeedMapConfig config = SeedMapConfig.get();
		nameBox = null;
		list.addSmall(
			net.minecraft.client.gui.components.CycleButton.onOffBuilder(config.showPins)
				.create(Component.translatable("jm_seedmap.opt.pins"), (b, v) -> {
					config.showPins = v;
					SeedMapConfig.save();
				}),
			Button.builder(Component.translatable("jm_seedmap.screen.clear_pins"), b -> {
				seedMap.clearPins();
				rebuildWidgets();
			}).build());

		List<WorldData.Pin> pins = new ArrayList<>(seedMap.pins());
		if (pins.isEmpty()) {
			list.addHeader(Component.translatable("jm_seedmap.pins.empty"));
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		pins.sort(Comparator.comparing((WorldData.Pin p) -> !p.target()).thenComparingDouble(p -> distance(p, player)));
		list.addHeader(Component.translatable("jm_seedmap.pins.count", pins.size()));
		for (WorldData.Pin pin : pins) {
			String id = id(pin);
			boolean isSelected = id.equals(selected);
			IconButton row = new IconButton(StructureStyles.icon(pin.structure().startsWith("biome:") ? "minecraft:biome" : pin.structure()), 310,
				label(pin, player, isSelected), b -> {
					selected = isSelected ? null : id;
					rebuildWidgets();
				});
			list.addBig(row);
			if (isSelected) {
				addActions(seedMap, pin);
			}
		}
	}

	private void addActions(SeedMap seedMap, WorldData.Pin pin) {
		Minecraft mc = Minecraft.getInstance();
		nameBox = new EditBox(mc.font, 0, 0, 150, 20, Component.translatable("jm_seedmap.pins.name"));
		nameBox.setMaxLength(64);
		nameBox.setValue(pin.name());
		list.addSmall(nameBox, Button.builder(Component.translatable("jm_seedmap.pins.rename"), b -> {
			String name = nameBox.getValue().trim();
			if (!name.isEmpty()) {
				seedMap.renamePin(pin, name);
				selected = null;
				rebuildWidgets();
			}
		}).build());
		Button target = Button.builder(Component.translatable(pin.target() ? "jm_seedmap.pins.is_target" : "jm_seedmap.menu.target"), b -> {
			seedMap.targetPin(pin);
			rebuildWidgets();
		}).build();
		target.active = !pin.target();
		list.addSmall(target, Button.builder(Component.translatable("jm_seedmap.pins.delete").withStyle(ChatFormatting.RED), b -> {
			seedMap.removePin(pin);
			selected = null;
			rebuildWidgets();
		}).build());
		list.addSmall(Button.builder(Component.translatable("jm_seedmap.menu.copy"),
			b -> mc.keyboardHandler.setClipboard(pin.x() + " " + pin.y() + " " + pin.z())).build(), null);
	}

	private static String id(WorldData.Pin pin) {
		return pin.key() + "|" + pin.target();
	}

	private static double distance(WorldData.Pin pin, @Nullable LocalPlayer player) {
		if (player == null || !player.level().dimension().equals(pin.dimensionKey())) {
			return Double.MAX_VALUE;
		}
		double dx = pin.x() + 0.5 - player.getX();
		double dz = pin.z() + 0.5 - player.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static Component label(WorldData.Pin pin, @Nullable LocalPlayer player, boolean selected) {
		MutableComponent text = Component.literal(pin.name());
		if (pin.target()) {
			text.withStyle(ChatFormatting.AQUA);
		}
		double d = distance(pin, player);
		if (d < Double.MAX_VALUE && player != null) {
			double dx = pin.x() + 0.5 - player.getX();
			double dz = pin.z() + 0.5 - player.getZ();
			text.append(Component.literal(" · ").withStyle(ChatFormatting.GRAY))
				.append(Component.translatable("jm_seedmap.pins.distance", Math.round(d),
					Component.translatable("jm_seedmap.dir." + direction(dx, dz))).withStyle(ChatFormatting.GRAY));
		} else {
			text.append(Component.literal(" · " + pin.dimension().replace("minecraft:", "")).withStyle(ChatFormatting.DARK_GRAY));
		}
		return selected ? Component.literal("▶ ").append(text) : text;
	}

	private static String direction(double dx, double dz) {
		double angle = Math.toDegrees(Math.atan2(dx, -dz));
		String[] names = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
		return names[(int) Math.floorMod(Math.round(angle / 45.0), 8L)].toLowerCase(Locale.ROOT);
	}

	@Override
	public void removed() {
		SeedMapConfig.save();
		super.removed();
	}
}
