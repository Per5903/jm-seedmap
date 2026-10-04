package ru.per.jmseedmap.core;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;

/**
 * Look of each structure on the map: a colored round badge with a vanilla item/block sprite, seedmap style.
 */
public final class StructureStyles {
	public static final int ICON_SIZE = 24;

	public record Style(String texture, int color, boolean defaultEnabled) {
	}

	/** Structures that belong together in the settings screen and the "nearest" search. */
	public record Group(String key, List<String> ids) {
		public String displayName() {
			return I18n.get("jm_seedmap.group." + key);
		}

		public String iconId() {
			return ids.get(0);
		}

		public boolean isEnabled() {
			return ids.stream().anyMatch(StructureStyles::isEnabled);
		}

		public void setEnabled(boolean enabled) {
			for (String id : ids) {
				StructureStyles.setEnabled(id, enabled);
			}
		}

		public boolean contains(String id) {
			return ids.contains(id);
		}
	}

	private static final Map<String, Style> STYLES = new HashMap<>();
	private static final List<Group> GROUPS = new ArrayList<>();
	private static final Map<String, Identifier> ICONS = new HashMap<>();
	private static final Map<String, Boolean> ENABLED_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
	private static volatile int filterVersion;

	static {
		Style village = new Style("item/bell", 0xE0B530, true);
		for (String v : new String[]{"village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga"}) {
			put(v, village);
		}
		put("pillager_outpost", new Style("item/crossbow_standby", 0x5A5A5A, true));
		put("mansion", new Style("item/totem_of_undying", 0x7A4A22, true));
		put("desert_pyramid", new Style("block/chiseled_sandstone", 0xD9C27A, true));
		put("jungle_pyramid", new Style("block/mossy_cobblestone", 0x3E8E3E, true));
		put("igloo", new Style("item/snowball", 0xBFD8F0, true));
		put("swamp_hut", new Style("item/cauldron", 0x4E6B2E, true));
		put("stronghold", new Style("item/ender_eye", 0x1F7A4D, true));
		put("monument", new Style("item/prismarine_shard", 0x2E9C95, true));
		put("ocean_ruin_cold", new Style("item/nautilus_shell", 0x2F6FA8, true));
		put("ocean_ruin_warm", new Style("item/nautilus_shell", 0x2F8FB8, true));
		put("shipwreck", new Style("item/oak_boat", 0x8D6E4A, true));
		put("shipwreck_beached", new Style("item/oak_boat", 0x9D7E5A, true));
		put("buried_treasure", new Style("item/heart_of_the_sea", 0xC9A227, false));
		put("mineshaft", new Style("item/minecart", 0x8A8A8A, false));
		put("mineshaft_mesa", new Style("item/minecart", 0xB5652B, false));
		Style portal = new Style("block/crying_obsidian", 0x6A2FB0, true);
		for (String p : new String[]{"ruined_portal", "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_swamp",
			"ruined_portal_mountain", "ruined_portal_ocean", "ruined_portal_nether"}) {
			put(p, portal);
		}
		put("ancient_city", new Style("item/echo_shard", 0x0E4A5A, true));
		put("trail_ruins", new Style("item/brush", 0xB5651D, true));
		put("trial_chambers", new Style("item/trial_key", 0xC07A2C, true));
		put("fortress", new Style("item/nether_brick", 0x7A1E1E, true));
		put("bastion_remnant", new Style("block/gilded_blackstone", 0x2B2B2B, true));
		put("nether_fossil", new Style("item/bone", 0xD8D2B8, false));
		put("end_city", new Style("item/shulker_shell", 0xA070A8, true));
		// Variant: End city that has the elytra ship (see FoundStructure#displayId).
		put("end_city_ship", new Style("item/elytra", 0xE0A030, true));

		group("village", "village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga");
		group("pillager_outpost", "pillager_outpost");
		group("mansion", "mansion");
		group("desert_pyramid", "desert_pyramid");
		group("jungle_pyramid", "jungle_pyramid");
		group("igloo", "igloo");
		group("swamp_hut", "swamp_hut");
		group("stronghold", "stronghold");
		group("monument", "monument");
		group("ocean_ruin", "ocean_ruin_cold", "ocean_ruin_warm");
		group("shipwreck", "shipwreck", "shipwreck_beached");
		group("ruined_portal", "ruined_portal", "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_swamp",
			"ruined_portal_mountain", "ruined_portal_ocean", "ruined_portal_nether");
		group("ancient_city", "ancient_city");
		group("trail_ruins", "trail_ruins");
		group("trial_chambers", "trial_chambers");
		group("buried_treasure", "buried_treasure");
		group("mineshaft", "mineshaft", "mineshaft_mesa");
		group("fortress", "fortress");
		group("bastion_remnant", "bastion_remnant");
		group("nether_fossil", "nether_fossil");
		group("end_city", "end_city");
		group("end_city_ship", "end_city_ship");
	}

	/** Display ids of the variants a structure can produce, e.g. end_city -> end_city_ship. */
	public static List<String> variants(String structureId) {
		return structureId.equals("minecraft:end_city") ? List.of("minecraft:end_city_ship") : List.of();
	}

	/** Whether a structure or any of its variants passes the filter: decides if its set needs computing at all. */
	public static boolean anyVariant(String structureId, java.util.function.Predicate<String> filter) {
		return filter.test(structureId) || variants(structureId).stream().anyMatch(filter);
	}

	private static void group(String key, String... vanillaPaths) {
		List<String> ids = new ArrayList<>();
		for (String path : vanillaPaths) {
			ids.add("minecraft:" + path);
		}
		GROUPS.add(new Group(key, List.copyOf(ids)));
	}

	public static List<Group> groups() {
		return GROUPS;
	}

	public static Group group(String key) {
		for (Group group : GROUPS) {
			if (group.key().equals(key)) {
				return group;
			}
		}
		return null;
	}

	public static Group groupOf(String structureId) {
		for (Group group : GROUPS) {
			if (group.contains(structureId)) {
				return group;
			}
		}
		return new Group(structureId, List.of(structureId));
	}

	private static void put(String vanillaPath, Style style) {
		STYLES.put("minecraft:" + vanillaPath, style);
	}

	private StructureStyles() {
	}

	public static Style style(String structureId) {
		Style style = STYLES.get(structureId);
		if (style != null) {
			return style;
		}
		// Modded structure: compass on a color derived from its id.
		int hash = structureId.hashCode();
		int color = java.awt.Color.HSBtoRGB((hash & 0xFFFF) / 65535f, 0.55f, 0.75f) & 0xFFFFFF;
		return new Style("item/compass_00", color, true);
	}

	public static java.util.Set<String> knownIds() {
		return new java.util.TreeSet<>(STYLES.keySet());
	}

	/** Called for every marker on every frame, so the answer is cached until a toggle changes. */
	public static boolean isEnabled(String structureId) {
		Boolean cached = ENABLED_CACHE.get(structureId);
		if (cached != null) {
			return cached;
		}
		Boolean value = SeedMapConfig.get().structures.get(structureId);
		boolean enabled = value != null ? value : style(structureId).defaultEnabled();
		ENABLED_CACHE.put(structureId, enabled);
		return enabled;
	}

	public static void setEnabled(String structureId, boolean enabled) {
		SeedMapConfig.get().structures.put(structureId, enabled);
		filtersChanged();
	}

	/** Increases whenever any structure toggle changes. */
	public static int filterVersion() {
		return filterVersion;
	}

	public static void filtersChanged() {
		ENABLED_CACHE.clear();
		filterVersion++;
	}

	public static String displayName(String structureId) {
		String key = "jm_seedmap.structure." + structureId.replace(':', '.');
		if (net.minecraft.locale.Language.getInstance().has(key)) {
			return I18n.get(key);
		}
		String path = structureId.substring(structureId.indexOf(':') + 1).replace('_', ' ');
		return path.isEmpty() ? structureId : Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}

	/** Texture id of the badge; built and registered on first use. Render thread only. */
	public static Identifier icon(String structureId) {
		return icon(structureId, false);
	}

	/** @param dimmed faded grey variant for structures the player has already visited */
	public static Identifier icon(String structureId, boolean dimmed) {
		return ICONS.computeIfAbsent((dimmed ? "dim:" : "") + structureId, k -> createIcon(structureId, dimmed));
	}

	/** Forget built textures (resource reload changes the sprites). */
	public static void clearIcons() {
		Minecraft mc = Minecraft.getInstance();
		for (Identifier id : ICONS.values()) {
			mc.getTextureManager().release(id);
		}
		ICONS.clear();
	}

	private static Identifier createIcon(String structureId, boolean dimmed) {
		Style style = style(structureId);
		String safe = structureId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
		Identifier id = Identifier.fromNamespaceAndPath(SeedMapClient.MOD_ID, (dimmed ? "icon_dim/" : "icon/") + safe);
		NativeImage image = new NativeImage(ICON_SIZE, ICON_SIZE, true);
		drawBadge(image, style.color());
		NativeImage sprite = loadSprite(style.texture());
		if (sprite != null) {
			try {
				drawSprite(image, sprite, 4, 4, 16);
			} finally {
				sprite.close();
			}
		}
		if (dimmed) {
			fade(image);
		}
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
		return id;
	}

	private static void drawBadge(NativeImage image, int color) {
		float c = (ICON_SIZE - 1) / 2f;
		float outer = ICON_SIZE / 2f - 0.5f;
		int border = darken(color, 0.45f);
		for (int y = 0; y < ICON_SIZE; y++) {
			for (int x = 0; x < ICON_SIZE; x++) {
				float d = (float) Math.hypot(x - c, y - c);
				if (d <= outer - 1.5f) {
					image.setPixel(x, y, 0xF0000000 | color);
				} else if (d <= outer) {
					image.setPixel(x, y, 0xFF000000 | border);
				} else if (d <= outer + 0.7f) {
					image.setPixel(x, y, 0x80000000 | border);
				}
			}
		}
	}

	private static void drawSprite(NativeImage target, NativeImage sprite, int ox, int oy, int size) {
		// Item textures may be animated strips or high-res packs: sample the first square frame.
		int frame = Math.min(sprite.getWidth(), sprite.getHeight());
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int src = sprite.getPixel(x * frame / size, y * frame / size);
				int alpha = src >>> 24;
				if (alpha == 0) {
					continue;
				}
				int dst = target.getPixel(ox + x, oy + y);
				target.setPixel(ox + x, oy + y, blend(dst, src, alpha / 255f));
			}
		}
	}

	private static NativeImage loadSprite(String texture) {
		Identifier location = Identifier.withDefaultNamespace("textures/" + texture + ".png");
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open()) {
			return NativeImage.read(in);
		} catch (Exception e) {
			SeedMapClient.LOGGER.debug("Cannot read sprite {}", location, e);
			return null;
		}
	}

	/** Greyscale at ~45% opacity: clearly "done" but still readable. */
	private static void fade(NativeImage image) {
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int argb = image.getPixel(x, y);
				int alpha = argb >>> 24;
				if (alpha == 0) {
					continue;
				}
				int grey = (((argb >> 16) & 0xFF) * 30 + ((argb >> 8) & 0xFF) * 59 + (argb & 0xFF) * 11) / 100;
				image.setPixel(x, y, (alpha * 45 / 100) << 24 | grey << 16 | grey << 8 | grey);
			}
		}
	}

	private static int blend(int dst, int src, float a) {
		int r = Math.round(((src >> 16) & 0xFF) * a + ((dst >> 16) & 0xFF) * (1 - a));
		int g = Math.round(((src >> 8) & 0xFF) * a + ((dst >> 8) & 0xFF) * (1 - a));
		int b = Math.round((src & 0xFF) * a + (dst & 0xFF) * (1 - a));
		int outA = Math.max(dst >>> 24, src >>> 24);
		return outA << 24 | r << 16 | g << 8 | b;
	}

	private static int darken(int color, float factor) {
		int r = (int) (((color >> 16) & 0xFF) * factor);
		int g = (int) (((color >> 8) & 0xFF) * factor);
		int b = (int) ((color & 0xFF) * factor);
		return r << 16 | g << 8 | b;
	}
}
