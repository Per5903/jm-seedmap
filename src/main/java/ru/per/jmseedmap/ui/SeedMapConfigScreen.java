package ru.per.jmseedmap.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.core.SeedCheck;
import ru.per.jmseedmap.core.SeedFinder;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.StructureStyles;
import ru.per.jmseedmap.gen.GenContextProvider;

/**
 * Settings, split into tabs: what the map shows, which structures, waypoints/visited, and the seed.
 */
public final class SeedMapConfigScreen extends OptionsSubScreen {
	private enum Tab { MAP, STRUCTURES, WAYPOINTS, SEED }

	private static final List<Integer> RADII = List.of(0, 16, 24, 32, 48, 64, 96, 128);
	private static final List<String> PRESETS = List.of("minecraft:normal", "minecraft:large_biomes", "minecraft:amplified");
	private static final List<Integer> OPACITIES = List.of(20, 30, 40, 50, 60, 70, 80, 90, 100);
	private static Tab tab = Tab.MAP;

	private @Nullable EditBox seedBox;
	private @Nullable Button checkLine;
	private @Nullable Button finderLine;
	private @Nullable Button packsLine;
	private int ticks;

	public SeedMapConfigScreen(@Nullable Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("jm_seedmap.screen.title"));
	}

	@Override
	protected void init() {
		// Rebuilt on every tab switch; the layout itself is reused.
		layout.removeChildren();
		super.init();
	}

	@Override
	protected void addTitle() {
		layout.setHeaderHeight(58);
		LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(6));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(title, font));
		LinearLayout tabs = header.addChild(LinearLayout.horizontal().spacing(4));
		for (Tab t : Tab.values()) {
			Button button = Button.builder(Component.translatable("jm_seedmap.tab." + t.name().toLowerCase(Locale.ROOT)), b -> {
				tab = t;
				rebuildWidgets();
			}).width(76).build();
			// The current tab is shown pressed (inactive).
			button.active = t != tab;
			tabs.addChild(button);
		}
	}

	@Override
	protected void addOptions() {
		checkLine = null;
		finderLine = null;
		packsLine = null;
		seedBox = null;
		switch (tab) {
			case MAP -> addMapTab();
			case STRUCTURES -> addStructuresTab();
			case WAYPOINTS -> addWaypointsTab();
			case SEED -> addSeedTab();
		}
	}

	private void addMapTab() {
		SeedMapConfig c = SeedMapConfig.get();
		list.addHeader(Component.translatable("jm_seedmap.screen.display"));
		list.addSmall(
			toggle("jm_seedmap.opt.enabled", c.enabled, v -> c.enabled = v),
			toggle("jm_seedmap.opt.labels", c.showLabels, v -> c.showLabels = v));
		list.addSmall(
			toggle("jm_seedmap.opt.minimap", c.showOnMinimap, v -> c.showOnMinimap = v),
			toggle("jm_seedmap.opt.worldmap", c.showOnWorldMap, v -> c.showOnWorldMap = v));
		list.addSmall(toggle("jm_seedmap.opt.slime", c.showSlimeChunks, v -> c.showSlimeChunks = v),
			toggle("jm_seedmap.opt.biomes", c.showBiomes, v -> c.showBiomes = v));
		list.addSmall(
			withTip(toggle("jm_seedmap.opt.biomes_unexplored", c.biomesOnlyUnexplored, v -> c.biomesOnlyUnexplored = v), "jm_seedmap.opt.biomes_unexplored.tip"),
			CycleButton.<Integer>builder(v -> Component.literal(v + "%"), c.biomeOpacity)
				.withValues(OPACITIES.contains(c.biomeOpacity) ? OPACITIES
					: java.util.stream.Stream.concat(OPACITIES.stream(), java.util.stream.Stream.of(c.biomeOpacity)).sorted().toList())
				.create(Component.translatable("jm_seedmap.opt.biome_opacity"), (b, v) -> c.biomeOpacity = v));
		list.addHeader(Component.translatable("jm_seedmap.screen.hud"));
		list.addSmall(
			withTip(toggle("jm_seedmap.opt.hud", c.hudEnabled, v -> c.hudEnabled = v), "jm_seedmap.opt.hud.tip"),
			CycleButton.<SeedMapConfig.HudCorner>builder(v -> Component.translatable("jm_seedmap.hud.corner." + v.name().toLowerCase(Locale.ROOT)), c.hudCorner)
				.withValues(SeedMapConfig.HudCorner.values())
				.create(Component.translatable("jm_seedmap.opt.hud_corner"), (b, v) -> c.hudCorner = v));
		list.addSmall(
			CycleButton.<Integer>builder(v -> Component.literal(v + ""), c.hudCount)
				.withValues(withValue(List.of(3, 5, 7, 10), c.hudCount))
				.create(Component.translatable("jm_seedmap.opt.hud_count"), (b, v) -> c.hudCount = v),
			CycleButton.<Integer>builder(v -> Component.literal(v + ""), c.hudRadius)
				.withValues(withValue(List.of(300, 500, 1000, 2000, 3000), c.hudRadius))
				.create(Component.translatable("jm_seedmap.opt.hud_radius"), (b, v) -> c.hudRadius = v));
		list.addHeader(Component.translatable("jm_seedmap.screen.visited"));
		list.addSmall(
			CycleButton.<SeedMapConfig.VisitedMode>builder(
					m -> Component.translatable("jm_seedmap.visited." + m.name().toLowerCase(Locale.ROOT)), c.visitedMode)
				.withValues(SeedMapConfig.VisitedMode.values())
				.withTooltip(v -> Tooltip.create(Component.translatable("jm_seedmap.opt.visited.tip")))
				.create(Component.translatable("jm_seedmap.opt.visited"), (b, v) -> c.visitedMode = v),
			radius("jm_seedmap.opt.visit_radius", c.visitRadius, v -> c.visitRadius = v));
		list.addSmall(
			withTip(CycleButton.<Integer>builder(v -> v == 0 ? Component.translatable("jm_seedmap.opt.off") : Component.literal(v + ""), c.visitHeight)
				.withValues(withValue(List.of(0, 16, 24, 32, 48, 64), c.visitHeight))
				.create(Component.translatable("jm_seedmap.opt.visit_height"), (b, v) -> c.visitHeight = v), "jm_seedmap.opt.visit_height.tip"),
			null);

		list.addHeader(Component.translatable("jm_seedmap.screen.performance"));
		list.addSmall(
			CycleButton.<SeedMapConfig.Performance>builder(SeedMapConfigScreen::perfLabel, c.performance)
				.withValues(SeedMapConfig.Performance.values())
				.withTooltip(v -> Tooltip.create(Component.translatable("jm_seedmap.opt.perf.tip")))
				.create(Component.translatable("jm_seedmap.opt.perf"), (b, v) -> c.performance = v),
			withTip(toggle("jm_seedmap.opt.cache", c.diskCache, v -> c.diskCache = v), "jm_seedmap.opt.cache.tip"));
		long cacheKb = SeedMap.get().index.diskCacheBytes() / 1024;
		list.addSmall(Button.builder(Component.translatable("jm_seedmap.screen.cache_clear", cacheKb), b -> {
			SeedMap.get().index.clearDiskCache();
			b.setMessage(Component.translatable("jm_seedmap.screen.cache_clear", 0));
			b.active = false;
		}).build(), null);
	}

	private static List<Integer> withValue(List<Integer> values, int value) {
		return values.contains(value) ? values : java.util.stream.Stream.concat(values.stream(), java.util.stream.Stream.of(value)).sorted().toList();
	}

	private static Component perfLabel(SeedMapConfig.Performance p) {
		Component name = Component.translatable("jm_seedmap.perf." + p.name().toLowerCase(Locale.ROOT));
		if (p == SeedMapConfig.Performance.AUTO) {
			return Component.empty().append(name).append(" (")
				.append(Component.translatable("jm_seedmap.perf." + ru.per.jmseedmap.core.Perf.auto().name().toLowerCase(Locale.ROOT))).append(")");
		}
		return name;
	}

	private static <T extends net.minecraft.client.gui.components.AbstractWidget> T withTip(T widget, String key) {
		widget.setTooltip(Tooltip.create(Component.translatable(key)));
		return widget;
	}

	private void addStructuresTab() {
		list.addSmall(
			Button.builder(Component.translatable("jm_seedmap.screen.show_all"), b -> setAll(true)).build(),
			Button.builder(Component.translatable("jm_seedmap.screen.hide_all"), b -> setAll(false)).build());
		List<Button> toggles = new ArrayList<>();
		// All known types, plus data pack / mod structures of the current dimension.
		List<StructureStyles.Group> groups = new ArrayList<>(StructureStyles.groups());
		for (StructureStyles.Group g : SearchScreen.groupsHere()) {
			if (groups.stream().noneMatch(k -> k.key().equals(g.key()))) {
				groups.add(g);
			}
		}
		for (StructureStyles.Group group : groups) {
			ToggleIconButton toggle = new ToggleIconButton(on -> StructureStyles.icon(group.iconId(), !on), 150,
				Component.literal(group.displayName()), group::isEnabled, b -> group.setEnabled(!group.isEnabled()));
			toggle.setTooltip(Tooltip.create(Component.literal(group.displayName())));
			toggles.add(toggle);
		}
		for (int i = 0; i < toggles.size(); i += 2) {
			list.addSmall(toggles.get(i), i + 1 < toggles.size() ? toggles.get(i + 1) : null);
		}
	}

	private void setAll(boolean enabled) {
		for (StructureStyles.Group group : StructureStyles.groups()) {
			group.setEnabled(enabled);
		}
		for (StructureStyles.Group group : SearchScreen.groupsHere()) {
			group.setEnabled(enabled);
		}
		rebuildWidgets();
	}

	private static Component groupLabel(StructureStyles.Group group) {
		boolean on = group.isEnabled();
		return Component.literal(group.displayName() + ": ")
			.append(on ? CommonComponents.OPTION_ON.copy().withStyle(ChatFormatting.GREEN) : CommonComponents.OPTION_OFF.copy().withStyle(ChatFormatting.RED));
	}

	private void addWaypointsTab() {
		SeedMapConfig c = SeedMapConfig.get();
		Minecraft mc = Minecraft.getInstance();
		list.addSmall(
			toggle("jm_seedmap.opt.pins", c.showPins, v -> c.showPins = v),
			radius("jm_seedmap.opt.arrival_radius", c.arrivalRadius, v -> c.arrivalRadius = v));
		list.addSmall(
			Button.builder(Component.translatable("jm_seedmap.screen.pins", SeedMap.get().pins().size()), b -> mc.gui.setScreen(new PinsScreen(this))).build(),
			Button.builder(Component.translatable("jm_seedmap.screen.nearby"), b -> mc.gui.setScreen(SearchScreen.nearby(this))).build());
		list.addSmall(
			Button.builder(Component.translatable("jm_seedmap.screen.search"), b -> mc.gui.setScreen(new SearchScreen(this))).build(),
			oneShot("jm_seedmap.screen.clear_target", () -> SeedMap.get().clearTarget()));
		list.addSmall(
			oneShot("jm_seedmap.screen.clear_pins", () -> SeedMap.get().clearPins()),
			oneShot("jm_seedmap.screen.clear_visited", () -> SeedMap.get().clearVisited()));
	}

	private void addSeedTab() {
		SeedMapConfig c = SeedMapConfig.get();
		Minecraft mc = Minecraft.getInstance();
		boolean singleplayer = mc.getSingleplayerServer() != null;
		Component versionWarning = ru.per.jmseedmap.compat.VersionCheck.warning();
		if (versionWarning != null) {
			list.addBig(infoLine(Component.literal("⚠ ").append(versionWarning).withStyle(ChatFormatting.GOLD), versionWarning));
		}
		if (singleplayer) {
			list.addHeader(Component.translatable("jm_seedmap.screen.seed_sp"));
		} else if (mc.level != null) {
			String key = GenContextProvider.serverKey();
			String address = ru.per.jmseedmap.core.SeedProfiles.address();
			list.addHeader(Component.translatable("jm_seedmap.screen.seed", address));
			list.addSmall(
				CycleButton.<String>builder(Component::literal, ru.per.jmseedmap.core.SeedProfiles.active(address))
					.withValues(ru.per.jmseedmap.core.SeedProfiles.names(address))
					.withTooltip(v -> Tooltip.create(Component.translatable("jm_seedmap.opt.profile.tip")))
					.create(Component.translatable("jm_seedmap.opt.profile"), (b, v) -> {
						ru.per.jmseedmap.core.SeedProfiles.use(v);
						rebuildWidgets();
					}),
				Button.builder(Component.translatable("jm_seedmap.screen.profile_new"), b -> {
					ru.per.jmseedmap.core.SeedProfiles.create(null);
					rebuildWidgets();
				}).build());
			seedBox = new EditBox(mc.font, 0, 0, 150, 20, Component.translatable("jm_seedmap.screen.seed_hint"));
			seedBox.setMaxLength(64);
			Long seed = c.seeds.get(key);
			seedBox.setValue(seed == null ? "" : Long.toString(seed));
			seedBox.setHint(Component.translatable("jm_seedmap.screen.seed_hint"));
			list.addSmall(seedBox, Button.builder(Component.translatable("jm_seedmap.screen.seed_save"), b -> saveSeed(key)).build());
			list.addSmall(
				CycleButton.<String>builder(p -> Component.translatable("jm_seedmap.preset." + p.substring(p.indexOf(':') + 1)), GenContextProvider.preset(key))
					.withValues(PRESETS)
					.create(Component.translatable("jm_seedmap.opt.preset"), (b, v) -> {
						c.presets.put(key, v);
						SeedMap.get().resetStructures();
					}),
				withTip(toggle("jm_seedmap.opt.seedcracker", c.seedCrackerAuto, v -> c.seedCrackerAuto = v), "jm_seedmap.opt.seedcracker.tip"));

			list.addHeader(Component.translatable("jm_seedmap.screen.datapacks"));
			list.addSmall(
				Button.builder(Component.translatable("jm_seedmap.screen.datapacks_open"), b -> {
					java.nio.file.Path dir = GenContextProvider.packDir(key);
					try {
						java.nio.file.Files.createDirectories(dir);
					} catch (java.io.IOException ignored) {
					}
					net.minecraft.util.Util.getPlatform().openPath(dir);
				}).tooltip(Tooltip.create(Component.translatable("jm_seedmap.datapacks.hint"))).build(),
				Button.builder(Component.translatable("jm_seedmap.screen.datapacks_reload"), b -> {
					SeedMap.get().resetStructures();
					updateLines();
				}).build());
			packsLine = infoLine(packsStatus(), null);
			list.addBig(packsLine);
		}

		list.addHeader(Component.translatable("jm_seedmap.screen.check"));
		list.addSmall(
			Button.builder(Component.translatable("jm_seedmap.screen.check_now"), b -> {
				SeedMap.get().seedCheck.run(mc, SeedMap.get().index, false);
				updateLines();
			}).build(),
			toggle("jm_seedmap.opt.seed_check_auto", c.seedCheckAuto, v -> c.seedCheckAuto = v));
		checkLine = infoLine(SeedMap.get().seedCheck.describe());
		list.addBig(checkLine);

		if (!singleplayer && mc.level != null) {
			list.addHeader(Component.translatable("jm_seedmap.screen.find"));
			boolean hasHash = SeedCheck.serverHashedSeed(mc.level).isPresent();
			Button start = Button.builder(Component.translatable("jm_seedmap.screen.find_start"), b -> {
				SeedMap.get().startSeedSearch(null);
				updateLines();
			}).tooltip(Tooltip.create(Component.translatable("jm_seedmap.screen.find_tip"))).build();
			start.active = hasHash;
			Button stop = Button.builder(Component.translatable("jm_seedmap.screen.find_stop"), b -> {
				SeedMap.get().seedFinder.cancel();
				updateLines();
			}).build();
			list.addSmall(start, stop);
			finderLine = infoLine(finderStatus(hasHash));
			list.addBig(finderLine);
		}
		updateLines();
	}

	private static Button infoLine(Component text) {
		return infoLine(text, null);
	}

	/** A disabled full-width button used as a status line; the tooltip shows long texts in full. */
	private static Button infoLine(Component text, @Nullable Component tooltip) {
		Button line = Button.builder(text, b -> { }).width(310).build();
		line.active = false;
		if (tooltip != null) {
			line.setTooltip(Tooltip.create(tooltip));
		}
		return line;
	}

	private static Component packsStatus() {
		var contexts = SeedMap.get().index.contexts();
		String error = contexts.lastError();
		if (error != null) {
			return Component.translatable("jm_seedmap.datapacks.error", error).withStyle(ChatFormatting.RED);
		}
		List<String> loaded = contexts.loadedPacks();
		String fingerprint = GenContextProvider.packFingerprint(GenContextProvider.packDir(GenContextProvider.serverKey()));
		if (loaded.isEmpty()) {
			return Component.translatable(fingerprint.isEmpty() ? "jm_seedmap.datapacks.none" : "jm_seedmap.datapacks.pending");
		}
		return Component.translatable("jm_seedmap.datapacks.loaded", String.join(", ", loaded));
	}

	private static Component finderStatus(boolean hasHash) {
		SeedFinder finder = SeedMap.get().seedFinder;
		if (!hasHash) {
			return Component.translatable("jm_seedmap.find.no_hash_short");
		}
		if (finder.isRunning()) {
			return Component.translatable("jm_seedmap.find.progress", finder.progress(), finder.elapsedMillis() / 1000);
		}
		if (finder.found() != null) {
			return Component.translatable("jm_seedmap.find.done", finder.found());
		}
		return Component.translatable(finder.finished() ? "jm_seedmap.find.idle_none" : "jm_seedmap.find.idle");
	}

	private void updateLines() {
		Minecraft mc = Minecraft.getInstance();
		if (checkLine != null) {
			checkLine.setMessage(SeedMap.get().seedCheck.describe());
		}
		if (finderLine != null) {
			finderLine.setMessage(finderStatus(SeedCheck.serverHashedSeed(mc.level).isPresent()));
		}
		if (packsLine != null && mc.level != null) {
			Component status = packsStatus();
			packsLine.setMessage(status);
			packsLine.setTooltip(Tooltip.create(status));
		}
		if (seedBox != null && mc.level != null) {
			Long seed = SeedMapConfig.get().seeds.get(GenContextProvider.serverKey());
			if (seed != null && !seedBox.isFocused() && !seedBox.getValue().equals(Long.toString(seed))) {
				seedBox.setValue(Long.toString(seed));
			}
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (++ticks % 10 == 0) {
			updateLines();
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
		SeedMap.get().scheduleSeedCheck();
	}

	private static Button oneShot(String key, Runnable action) {
		return Button.builder(Component.translatable(key), b -> {
			action.run();
			b.active = false;
		}).build();
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

	@Override
	public void removed() {
		SeedMapConfig.save();
		SeedMap.get().invalidateVisuals();
		super.removed();
	}
}
