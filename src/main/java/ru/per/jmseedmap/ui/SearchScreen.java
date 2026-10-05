package ru.per.jmseedmap.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.core.FarmMode;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.StructureDetails;
import ru.per.jmseedmap.core.StructureStyles;
import ru.per.jmseedmap.gen.FoundStructure;

/**
 * Search for structure types and biomes of the current dimension (picking one sets a target waypoint on the
 * nearest one), and the list of all structures nearby.
 */
public final class SearchScreen extends OptionsSubScreen {
	public enum Tab { STRUCTURES, BIOMES, NEARBY }

	private static final List<Integer> RADII = List.of(500, 1000, 2000, 3000, 5000);
	private static final int MAX_ROWS = 80;

	private static String query = "";
	private static Tab tab = Tab.STRUCTURES;
	/** Nearby tab: group key, or "" for every type that is shown on the map. */
	private static String nearbyGroup = "";
	private static int nearbyRadius = 1000;
	/** Structures tab: 0 = set the nearest one as target, n > 0 = farm mode with n pins. */
	private static int farmCount = 0;

	private @Nullable EditBox searchBox;
	private @Nullable List<FoundStructure> nearby;
	private boolean nearbyLoading;
	private int detailsVersion = -1;

	public SearchScreen(@Nullable Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("jm_seedmap.search.title"));
	}

	public static SearchScreen nearby(@Nullable Screen parent) {
		tab = Tab.NEARBY;
		return new SearchScreen(parent);
	}

	@Override
	protected void init() {
		layout.removeChildren();
		super.init();
		if (searchBox != null) {
			setInitialFocus(searchBox);
			searchBox.moveCursorToEnd(false);
		}
		if (tab == Tab.NEARBY && nearby == null && !nearbyLoading) {
			loadNearby();
		}
	}

	@Override
	protected void addTitle() {
		layout.setHeaderHeight(58);
		var header = layout.addToHeader(net.minecraft.client.gui.layouts.LinearLayout.vertical().spacing(6));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new net.minecraft.client.gui.components.StringWidget(title, font));
		var tabs = header.addChild(net.minecraft.client.gui.layouts.LinearLayout.horizontal().spacing(4));
		for (Tab t : Tab.values()) {
			Button button = Button.builder(Component.translatable("jm_seedmap.search.tab_" + t.name().toLowerCase(Locale.ROOT)), b -> {
				tab = t;
				nearby = null;
				rebuildWidgets();
			}).width(100).build();
			button.active = tab != t;
			tabs.addChild(button);
		}
	}

	@Override
	protected void addOptions() {
		searchBox = null;
		if (tab == Tab.NEARBY) {
			addNearby();
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		boolean biomesTab = tab == Tab.BIOMES;
		searchBox = new EditBox(mc.font, 0, 0, 310, 20, Component.translatable("jm_seedmap.search.hint"));
		searchBox.setHint(Component.translatable(biomesTab ? "jm_seedmap.search.hint_biomes" : "jm_seedmap.search.hint"));
		searchBox.setValue(query);
		searchBox.setResponder(text -> {
			if (!text.equals(query)) {
				query = text;
				rebuildWidgets();
			}
		});
		list.addBig(searchBox);
		String q = query.trim().toLowerCase(Locale.ROOT);
		SeedMap seedMap = SeedMap.get();
		if (!biomesTab) {
			addFarmControls(seedMap);
		} else {
			addHighlightControls();
		}

		List<Button> structures = new ArrayList<>();
		for (StructureStyles.Group group : biomesTab ? List.<StructureStyles.Group>of() : groupsHere()) {
			String name = group.displayName();
			if (matches(q, name, group.key())) {
				structures.add(new IconButton(StructureStyles.icon(group.iconId()), 150, Component.literal(name), b -> {
					mc.gui.setScreen(null);
					if (farmCount > 0) {
						seedMap.farm.start(group, farmCount);
					} else {
						seedMap.findNearest(group::contains, name);
					}
				}));
			}
		}
		if (!structures.isEmpty()) {
			list.addHeader(Component.translatable("jm_seedmap.search.structures"));
			addPairs(structures);
		}

		List<Button> biomes = new ArrayList<>();
		if (mc.level != null && biomesTab) {
			List<Identifier> ids = new ArrayList<>(seedMap.index.possibleBiomes(mc.level.dimension()));
			ids.sort(Comparator.comparing(SearchScreen::biomeName));
			List<Identifier> shown = new ArrayList<>();
			for (Identifier id : ids) {
				if (matches(q, biomeName(id), id.toString())) {
					shown.add(id);
				}
			}
			if (ids.isEmpty()) {
				list.addHeader(Component.translatable("jm_seedmap.search.no_seed"));
			} else if (!shown.isEmpty()) {
				list.addHeader(Component.translatable("jm_seedmap.search.biomes"));
			}
			// One biome per row: find the nearest one / highlight it on the map.
			for (Identifier id : shown) {
				String name = biomeName(id);
				Button find = Button.builder(Component.literal("■ ").withColor(ru.per.jmseedmap.core.BiomeLayer.color(id))
					.append(Component.literal(name).withStyle(ChatFormatting.WHITE)), b -> {
					mc.gui.setScreen(null);
					seedMap.findNearestBiome(id, name);
				}).tooltip(Tooltip.create(Component.translatable("jm_seedmap.search.find_biome"))).build();
				boolean lit = ru.per.jmseedmap.core.BiomeLayer.highlighted().contains(id);
				Button highlight = Button.builder(Component.translatable(lit ? "jm_seedmap.search.highlight_on" : "jm_seedmap.search.highlight_off"), b -> {
					ru.per.jmseedmap.core.BiomeLayer.setHighlighted(id, !lit);
					if (!lit && !ru.per.jmseedmap.SeedMapConfig.get().showBiomes) {
						// Highlighting needs the biome layer.
						ru.per.jmseedmap.SeedMapConfig.get().showBiomes = true;
						ru.per.jmseedmap.SeedMapConfig.save();
					}
					double scroll = list.scrollAmount();
					rebuildWidgets();
					list.setScrollAmount(scroll);
				}).build();
				list.addSmall(find, highlight);
				biomes.add(find);
			}
		}
		if (structures.isEmpty() && biomes.isEmpty() && !q.isEmpty()) {
			list.addHeader(Component.translatable("jm_seedmap.search.nothing"));
		}
	}

	private void addFarmControls(SeedMap seedMap) {
		var farm = seedMap.farm;
		list.addSmall(
			CycleButton.<Integer>builder(v -> v == 0 ? Component.translatable("jm_seedmap.farm.mode_nearest")
					: Component.translatable("jm_seedmap.farm.mode_farm"), farmCount)
				.withValues(List.of(0, FarmMode.DEFAULT_PINS))
				.withTooltip(v -> Tooltip.create(Component.translatable("jm_seedmap.farm.mode.tip")))
				.create(Component.translatable("jm_seedmap.farm.mode"), (b, v) -> farmCount = v),
			farm.active()
				? Button.builder(Component.translatable("jm_seedmap.farm.stop", farm.group().displayName(), farm.visitedCount()), b -> {
					farm.stop(true);
					rebuildWidgets();
				}).build()
				: null);
	}

	private void addHighlightControls() {
		var config = ru.per.jmseedmap.SeedMapConfig.get();
		int lit = ru.per.jmseedmap.core.BiomeLayer.highlighted().size();
		Button clear = Button.builder(Component.translatable("jm_seedmap.search.highlight_clear", lit), b -> {
			ru.per.jmseedmap.core.BiomeLayer.clearHighlight();
			rebuildWidgets();
		}).build();
		clear.active = lit > 0;
		list.addSmall(
			CycleButton.onOffBuilder(config.showBiomes).create(Component.translatable("jm_seedmap.opt.biomes"), (b, v) -> {
				config.showBiomes = v;
				ru.per.jmseedmap.SeedMapConfig.save();
			}),
			clear);
	}

	// ---- nearby ----

	private void addNearby() {
		List<String> groups = new ArrayList<>();
		groups.add("");
		groupsHere().forEach(g -> groups.add(g.key()));
		if (!groups.contains(nearbyGroup)) {
			nearbyGroup = "";
		}
		list.addSmall(
			CycleButton.<String>builder(SearchScreen::groupLabel, nearbyGroup)
				.withValues(groups)
				.withTooltip(v -> Tooltip.create(Component.translatable("jm_seedmap.nearby.type.tip")))
				.create(Component.translatable("jm_seedmap.nearby.type"), (b, v) -> {
					nearbyGroup = v;
					loadNearby();
				}),
			CycleButton.<Integer>builder(v -> Component.literal(v + ""), nearbyRadius)
				.withValues(RADII)
				.create(Component.translatable("jm_seedmap.nearby.radius"), (b, v) -> {
					nearbyRadius = v;
					loadNearby();
				}));
		if (nearby == null) {
			list.addHeader(Component.translatable("jm_seedmap.nearby.loading").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (nearby.isEmpty()) {
			list.addHeader(Component.translatable("jm_seedmap.nearby.empty", nearbyRadius));
			return;
		}
		list.addHeader(Component.translatable(nearby.size() > MAX_ROWS ? "jm_seedmap.nearby.count_more" : "jm_seedmap.nearby.count",
			Math.min(nearby.size(), MAX_ROWS), nearby.size()));
		LocalPlayer player = Minecraft.getInstance().player;
		SeedMap seedMap = SeedMap.get();
		for (FoundStructure s : nearby.subList(0, Math.min(nearby.size(), MAX_ROWS))) {
			IconButton row = new IconButton(StructureStyles.icon(s.displayId(), seedMap.isDimmed(s)), 310, rowLabel(s, player),
				b -> Minecraft.getInstance().gui.setScreen(new StructureInfoScreen(this, s)));
			StructureDetails.Details details = seedMap.details.get(s);
			if (details != null && !details.lines().isEmpty()) {
				MutableComponent tip = Component.empty();
				for (int i = 0; i < details.lines().size(); i++) {
					tip.append(i == 0 ? Component.empty() : Component.literal("\n")).append(details.lines().get(i));
				}
				row.setTooltip(Tooltip.create(tip));
			}
			list.addBig(row);
		}
	}

	private static Component groupLabel(String key) {
		if (key.isEmpty()) {
			return Component.translatable("jm_seedmap.nearby.type_shown");
		}
		StructureStyles.Group group = StructureStyles.group(key);
		return Component.literal(group == null ? key : group.displayName());
	}

	private static Component rowLabel(FoundStructure s, @Nullable LocalPlayer player) {
		MutableComponent text = Component.literal(StructureStyles.displayName(s.displayId()));
		if (player != null) {
			double dx = s.pos().getX() + 0.5 - player.getX();
			double dz = s.pos().getZ() + 0.5 - player.getZ();
			text.append(Component.literal(" · ").withStyle(ChatFormatting.GRAY))
				.append(Component.translatable("jm_seedmap.pins.distance", Math.round(Math.sqrt(dx * dx + dz * dz)),
					Component.translatable("jm_seedmap.dir." + SeedMap.direction(dx, dz))).withStyle(ChatFormatting.GRAY));
		}
		if (SeedMap.get().isVisited(s)) {
			text.append(Component.literal(" ✔").withStyle(ChatFormatting.DARK_GREEN));
		}
		return text;
	}

	private void loadNearby() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		nearby = null;
		nearbyLoading = true;
		StructureStyles.Group group = nearbyGroup.isEmpty() ? null : StructureStyles.group(nearbyGroup);
		Predicate<String> filter = group == null ? StructureStyles::isEnabled : group::contains;
		SeedMap seedMap = SeedMap.get();
		seedMap.index.around(mc.level.dimension(), player.getX(), player.getZ(), nearbyRadius, filter)
			.whenComplete((result, error) -> mc.execute(() -> {
				nearbyLoading = false;
				List<FoundStructure> shown = new ArrayList<>();
				if (result != null) {
					for (FoundStructure s : result) {
						if (!seedMap.isHidden(s)) {
							shown.add(s);
						}
					}
				}
				nearby = shown;
				// Details for the tooltips, nearest first.
				shown.stream().limit(MAX_ROWS).forEach(seedMap.details::request);
				if (mc.gui.screen() == this) {
					rebuildWidgets();
				}
			}));
		rebuildWidgets();
	}

	@Override
	public void tick() {
		super.tick();
		// Refresh tooltips as details come in (at most once a second).
		int version = SeedMap.get().details.version();
		if (tab == Tab.NEARBY && nearby != null && version != detailsVersion && Minecraft.getInstance().level != null
			&& Minecraft.getInstance().level.getGameTime() % 20 == 0) {
			detailsVersion = version;
			double scroll = list.scrollAmount();
			rebuildWidgets();
			list.setScrollAmount(scroll);
		}
	}

	// ---- helpers ----

	/** Structure types this dimension can have (including data pack ones); all known types while the seed is unknown. */
	static List<StructureStyles.Group> groupsHere() {
		Minecraft mc = Minecraft.getInstance();
		java.util.Set<String> ids = mc.level == null ? java.util.Set.of() : SeedMap.get().index.possibleStructures(mc.level.dimension());
		return ids.isEmpty() ? StructureStyles.groups() : StructureStyles.groupsFor(ids);
	}

	private void addPairs(List<Button> buttons) {
		for (int i = 0; i < buttons.size(); i += 2) {
			list.addSmall(buttons.get(i), i + 1 < buttons.size() ? buttons.get(i + 1) : null);
		}
	}

	private static boolean matches(String query, String name, String id) {
		return query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query) || id.toLowerCase(Locale.ROOT).contains(query);
	}

	public static String biomeNameOf(Identifier id) {
		return biomeName(id);
	}

	static String biomeName(Identifier id) {
		String key = "biome." + id.getNamespace() + "." + id.getPath();
		return Language.getInstance().has(key) ? I18n.get(key) : id.toString();
	}
}
