package ru.per.jmseedmap.ui;

import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.StructureStyles;
import ru.per.jmseedmap.gen.GenContextProvider;

/**
 * All SeedMap settings in one place: the structure layer, how visited structures and pins look,
 * the seed for the current server and per-type visibility.
 */
public final class SeedMapConfigScreen extends OptionsSubScreen {
	private static final List<Integer> RADII = List.of(0, 16, 24, 32, 48, 64, 96, 128);

	private @Nullable EditBox seedBox;

	public SeedMapConfigScreen(@Nullable Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("jm_seedmap.screen.title"));
	}

	@Override
	protected void addOptions() {
		SeedMapConfig c = SeedMapConfig.get();
		Minecraft mc = Minecraft.getInstance();

		list.addHeader(Component.translatable("jm_seedmap.screen.display"));
		list.addSmall(
			toggle("jm_seedmap.opt.enabled", c.enabled, v -> c.enabled = v),
			toggle("jm_seedmap.opt.labels", c.showLabels, v -> c.showLabels = v));
		list.addSmall(
			toggle("jm_seedmap.opt.minimap", c.showOnMinimap, v -> c.showOnMinimap = v),
			toggle("jm_seedmap.opt.worldmap", c.showOnWorldMap, v -> c.showOnWorldMap = v));
		list.addSmall(
			toggle("jm_seedmap.opt.slime", c.showSlimeChunks, v -> c.showSlimeChunks = v),
			toggle("jm_seedmap.opt.seed_check", c.seedCheck, v -> c.seedCheck = v));

		list.addHeader(Component.translatable("jm_seedmap.screen.waypoints"));
		list.addSmall(
			CycleButton.<SeedMapConfig.VisitedMode>builder(
					m -> Component.translatable("jm_seedmap.visited." + m.name().toLowerCase(Locale.ROOT)), c.visitedMode)
				.withValues(SeedMapConfig.VisitedMode.values())
				.withTooltip(v -> net.minecraft.client.gui.components.Tooltip.create(Component.translatable("jm_seedmap.opt.visited.tip")))
				.create(Component.translatable("jm_seedmap.opt.visited"), (b, v) -> c.visitedMode = v),
			radius("jm_seedmap.opt.visit_radius", c.visitRadius, v -> c.visitRadius = v));
		list.addSmall(
			toggle("jm_seedmap.opt.pins", c.showPins, v -> c.showPins = v),
			radius("jm_seedmap.opt.arrival_radius", c.arrivalRadius, v -> c.arrivalRadius = v));
		list.addSmall(
			Button.builder(Component.translatable("jm_seedmap.screen.nearest"), b -> mc.gui.setScreen(new NearestScreen(this))).build(),
			Button.builder(Component.translatable("jm_seedmap.screen.clear_pins"), b -> {
				SeedMap.get().clearPins();
				b.active = false;
			}).build());
		list.addSmall(
			Button.builder(Component.translatable("jm_seedmap.screen.clear_visited"), b -> {
				SeedMap.get().clearVisited();
				b.active = false;
			}).build(),
			Button.builder(Component.translatable("jm_seedmap.screen.clear_target"), b -> {
				SeedMap.get().clearTarget();
				b.active = false;
			}).build());

		if (mc.getSingleplayerServer() == null && mc.level != null) {
			String key = GenContextProvider.serverKey();
			list.addHeader(Component.translatable("jm_seedmap.screen.seed", key));
			seedBox = new EditBox(mc.font, 0, 0, 150, 20, Component.translatable("jm_seedmap.screen.seed_hint"));
			seedBox.setMaxLength(64);
			Long seed = c.seeds.get(key);
			seedBox.setValue(seed == null ? "" : Long.toString(seed));
			seedBox.setHint(Component.translatable("jm_seedmap.screen.seed_hint"));
			list.addSmall(seedBox, Button.builder(Component.translatable("jm_seedmap.screen.seed_save"), b -> saveSeed(key)).build());
		}
		if (mc.level != null) {
			// Seed check result: a non-interactive full-width line.
			Button check = Button.builder(SeedMap.get().seedCheck.describe(), b -> { }).width(310).build();
			check.active = false;
			list.addBig(check);
		}

		list.addHeader(Component.translatable("jm_seedmap.screen.structures"));
		List<StructureStyles.Group> groups = StructureStyles.groups();
		for (int i = 0; i < groups.size(); i += 2) {
			StructureStyles.Group first = groups.get(i);
			StructureStyles.Group second = i + 1 < groups.size() ? groups.get(i + 1) : null;
			list.addSmall(groupToggle(first), second == null ? null : groupToggle(second));
		}
	}

	private void saveSeed(String key) {
		if (seedBox == null) {
			return;
		}
		OptionalLong seed = WorldOptions.parseSeed(seedBox.getValue());
		SeedMapConfig c = SeedMapConfig.get();
		if (seed.isPresent()) {
			c.seeds.put(key, seed.getAsLong());
			seedBox.setValue(Long.toString(seed.getAsLong()));
		} else {
			c.seeds.remove(key);
		}
		SeedMapConfig.save();
		SeedMap.get().resetStructures();
	}

	private static CycleButton<Boolean> toggle(String key, boolean value, java.util.function.Consumer<Boolean> setter) {
		return CycleButton.onOffBuilder(value).create(Component.translatable(key), (b, v) -> setter.accept(v));
	}

	private static CycleButton<Integer> radius(String key, int value, java.util.function.Consumer<Integer> setter) {
		List<Integer> values = RADII.contains(value) ? RADII : java.util.stream.Stream.concat(RADII.stream(), java.util.stream.Stream.of(value)).sorted().toList();
		return CycleButton.<Integer>builder(v -> v == 0 ? Component.translatable("jm_seedmap.opt.off") : Component.literal(v + ""), value)
			.withValues(values)
			.create(Component.translatable(key), (b, v) -> setter.accept(v));
	}

	private static CycleButton<Boolean> groupToggle(StructureStyles.Group group) {
		return CycleButton.onOffBuilder(group.isEnabled())
			.create(Component.literal(group.displayName()), (b, v) -> group.setEnabled(v));
	}

	@Override
	public void removed() {
		SeedMapConfig.save();
		SeedMap.get().invalidateVisuals();
		super.removed();
	}
}
