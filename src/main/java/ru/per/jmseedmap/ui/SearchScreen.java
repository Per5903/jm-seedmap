package ru.per.jmseedmap.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.StructureStyles;

/**
 * Search for structure types and biomes of the current dimension; picking one sets a target waypoint on the
 * nearest one (structures: nearest not yet visited).
 */
public final class SearchScreen extends OptionsSubScreen {
	private static String query = "";
	/** false = structures tab, true = biomes tab. */
	private static boolean biomesTab;
	private @Nullable EditBox searchBox;

	public SearchScreen(@Nullable Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("jm_seedmap.search.title"));
	}

	@Override
	protected void init() {
		layout.removeChildren();
		super.init();
		if (searchBox != null) {
			setInitialFocus(searchBox);
			searchBox.moveCursorToEnd(false);
		}
	}

	@Override
	protected void addTitle() {
		layout.setHeaderHeight(58);
		var header = layout.addToHeader(net.minecraft.client.gui.layouts.LinearLayout.vertical().spacing(6));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new net.minecraft.client.gui.components.StringWidget(title, font));
		var tabs = header.addChild(net.minecraft.client.gui.layouts.LinearLayout.horizontal().spacing(4));
		for (boolean biomes : new boolean[]{false, true}) {
			Button button = Button.builder(Component.translatable(biomes ? "jm_seedmap.search.tab_biomes" : "jm_seedmap.search.tab_structures"), b -> {
				biomesTab = biomes;
				rebuildWidgets();
			}).width(120).build();
			button.active = biomesTab != biomes;
			tabs.addChild(button);
		}
	}

	@Override
	protected void addOptions() {
		Minecraft mc = Minecraft.getInstance();
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

		List<Button> structures = new ArrayList<>();
		for (StructureStyles.Group group : biomesTab ? List.<StructureStyles.Group>of() : StructureStyles.groups()) {
			String name = group.displayName();
			if (matches(q, name, group.key())) {
				structures.add(new IconButton(StructureStyles.icon(group.iconId()), 150, Component.literal(name), b -> {
					mc.gui.setScreen(null);
					SeedMap.get().findNearest(group::contains, name);
				}));
			}
		}
		if (!structures.isEmpty()) {
			list.addHeader(Component.translatable("jm_seedmap.search.structures"));
			addPairs(structures);
		}

		List<Button> biomes = new ArrayList<>();
		if (mc.level != null && biomesTab) {
			List<Identifier> ids = new ArrayList<>(SeedMap.get().index.possibleBiomes(mc.level.dimension()));
			ids.sort(Comparator.comparing(SearchScreen::biomeName));
			for (Identifier id : ids) {
				String name = biomeName(id);
				if (matches(q, name, id.toString())) {
					biomes.add(Button.builder(Component.literal(name), b -> {
						mc.gui.setScreen(null);
						SeedMap.get().findNearestBiome(id, name);
					}).build());
				}
			}
			if (ids.isEmpty()) {
				list.addHeader(Component.translatable("jm_seedmap.search.no_seed"));
			}
		}
		if (!biomes.isEmpty()) {
			list.addHeader(Component.translatable("jm_seedmap.search.biomes"));
			addPairs(biomes);
		}
		if (structures.isEmpty() && biomes.isEmpty() && !q.isEmpty()) {
			list.addHeader(Component.translatable("jm_seedmap.search.nothing"));
		}
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
