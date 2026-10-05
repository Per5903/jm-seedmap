package ru.per.jmseedmap.map.jm;

import java.awt.geom.Point2D;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import journeymap.api.v2.client.IClientAPI;
import com.mojang.blaze3d.platform.NativeImage;
import journeymap.api.v2.client.display.IOverlayListener;
import journeymap.api.v2.client.display.ImageOverlay;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.fullscreen.ModPopupMenu;
import journeymap.api.v2.client.model.MapImage;
import journeymap.api.v2.client.util.UIState;
import journeymap.api.v2.common.Context;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import journeymap.api.v2.common.waypoint.WaypointGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.core.BiomeLayer;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.SlimeChunks;
import ru.per.jmseedmap.core.StructureStyles;
import ru.per.jmseedmap.core.WorldData;
import ru.per.jmseedmap.gen.FoundStructure;
import ru.per.jmseedmap.map.MapBackend;

/**
 * JourneyMap: structures are {@link MarkerOverlay}s (not waypoints), pins are waypoints in their own
 * "SeedMap" group that the player can toggle in JourneyMap's waypoint manager.
 */
public final class JourneyMapBackend implements MapBackend {
	private static final String GROUP_NAME = "SeedMap";

	private final IClientAPI api;
	private final Map<String, MarkerOverlay> shown = new HashMap<>();
	/** Slime chunk overlays, one 32x32 image per 512x512 tile. */
	private final Map<String, ImageOverlay> slimeShown = new HashMap<>();
	/** Biome layer overlays and the tile each one shows. */
	private final Map<String, ImageOverlay> biomeShown = new HashMap<>();
	private final Map<String, BiomeLayer.Tile> biomeTiles = new HashMap<>();
	private int biomeOpacity = -1;
	private int biomeHighlight = -1;
	private int ticks;
	private int shownRevision = -1;
	private int shownPins = -1;
	private boolean shownPinsVisible;
	private @Nullable WaypointGroup group;

	public JourneyMapBackend(IClientAPI api) {
		this.api = api;
	}

	@Override
	public String name() {
		return "JourneyMap";
	}

	@Override
	public void tick(SeedMap seedMap) {
		if (++ticks % 4 != 0) {
			return;
		}
		syncPins(seedMap);
		syncSlime(seedMap);
		syncBiomes(seedMap);
		SeedMapConfig config = SeedMapConfig.get();
		if (!config.enabled) {
			hideAll();
			return;
		}
		if (shownRevision != seedMap.revision()) {
			hideAll();
			shownRevision = seedMap.revision();
		}

		Map<String, FoundStructure> wanted = new HashMap<>();
		collect(seedMap, Context.UI.Fullscreen, wanted);
		if (config.showOnMinimap) {
			collect(seedMap, Context.UI.Minimap, wanted);
		}

		Iterator<Map.Entry<String, MarkerOverlay>> it = shown.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, MarkerOverlay> entry = it.next();
			if (!wanted.containsKey(entry.getKey())) {
				api.remove(entry.getValue());
				it.remove();
			}
		}
		for (Map.Entry<String, FoundStructure> entry : wanted.entrySet()) {
			if (!shown.containsKey(entry.getKey())) {
				MarkerOverlay overlay = createOverlay(seedMap, entry.getValue());
				if (overlay != null) {
					shown.put(entry.getKey(), overlay);
				}
			}
		}
	}

	private void collect(SeedMap seedMap, Context.UI ui, Map<String, FoundStructure> wanted) {
		UIState state = api.getUIState(ui);
		if (state == null || !state.active || state.blockBounds == null || state.dimension == null) {
			return;
		}
		AABB b = state.blockBounds;
		seedMap.index.requestView("jm_" + ui.name(), state.dimension, b.minX, b.minZ, b.maxX, b.maxZ);
		for (FoundStructure s : seedMap.index.query(state.dimension, b.minX - 64, b.minZ - 64, b.maxX + 64, b.maxZ + 64)) {
			if (!seedMap.isHidden(s)) {
				wanted.put(s.key(), s);
			}
		}
	}

	private @Nullable MarkerOverlay createOverlay(SeedMap seedMap, FoundStructure structure) {
		SeedMapConfig config = SeedMapConfig.get();
		String id = structure.displayId();
		String name = StructureStyles.displayName(id);
		BlockPos pos = structure.pos();
		boolean dimmed = seedMap.isDimmed(structure);
		MapImage icon = new MapImage(StructureStyles.icon(id, dimmed), StructureStyles.ICON_SIZE, StructureStyles.ICON_SIZE)
			.setDisplayWidth(18)
			.setDisplayHeight(18)
			.centerAnchors();
		MarkerOverlay overlay = new MarkerOverlay(SeedMapClient.MOD_ID, pos, icon);
		overlay.setDimension(structure.dimension())
			.setTitle(name + "\n" + pos.getX() + ", " + pos.getZ() + (seedMap.isVisited(structure) ? "\n" + I18n.get("jm_seedmap.label.visited") : ""))
			.setLabel(config.showLabels ? name : "")
			.setOverlayGroupName("SeedMap")
			.setDisplayOrder(1000)
			.setOverlayListener(new MarkerListener(structure));
		if (!config.showOnMinimap) {
			overlay.setActiveUIs(Context.UI.Fullscreen, Context.UI.Webmap);
		}
		overlay.getTextProperties()
			.setMinZoom(1024)
			.setOffsetY(14)
			.setBackgroundOpacity(0.5f)
			.setOpacity(dimmed ? 0.5f : 1f);
		try {
			api.show(overlay);
			return overlay;
		} catch (Exception e) {
			SeedMapClient.LOGGER.warn("JourneyMap refused overlay {}", overlay, e);
			return null;
		}
	}

	private static final class MarkerListener implements IOverlayListener {
		private final FoundStructure structure;

		MarkerListener(FoundStructure structure) {
			this.structure = structure;
		}

		@Override
		public void onOverlayMenuPopup(UIState mapState, Point2D.Double mousePosition, BlockPos blockPosition, ModPopupMenu menu) {
			SeedMap seedMap = SeedMap.get();
			String name = StructureStyles.displayName(structure.displayId());
			menu.addMenuItem(I18n.get("jm_seedmap.menu.details"), clicked -> {
				Minecraft mc = Minecraft.getInstance();
				mc.gui.setScreen(new ru.per.jmseedmap.ui.StructureInfoScreen(mc.gui.screen(), structure));
			});
			seedMap.details.request(structure);
			boolean pinned = seedMap.isPinned(structure);
			menu.addMenuItem(I18n.get(pinned ? "jm_seedmap.menu.unpin" : "jm_seedmap.menu.pin", name),
				clicked -> seedMap.setPinned(structure, !pinned));
			menu.addMenuItem(I18n.get("jm_seedmap.menu.target"), clicked -> seedMap.setTarget(structure));
			boolean visited = seedMap.isVisited(structure);
			menu.addMenuItem(I18n.get(visited ? "jm_seedmap.menu.unvisit" : "jm_seedmap.menu.visit"),
				clicked -> seedMap.setVisited(structure, !visited));
			menu.addMenuItem(I18n.get("jm_seedmap.menu.hide_type", StructureStyles.groupOf(structure.displayId()).displayName()), clicked -> {
				StructureStyles.groupOf(structure.displayId()).setEnabled(false);
				SeedMapConfig.save();
				seedMap.invalidateVisuals();
			});
			menu.addMenuItem(I18n.get("jm_seedmap.menu.copy"), clicked -> Minecraft.getInstance().keyboardHandler.setClipboard(
				structure.pos().getX() + " " + structure.pos().getY() + " " + structure.pos().getZ()));
		}
	}

	private void syncPins(SeedMap seedMap) {
		boolean visible = SeedMapConfig.get().showPins;
		if (shownPins == seedMap.pinsRevision() && shownPinsVisible == visible) {
			return;
		}
		shownPins = seedMap.pinsRevision();
		shownPinsVisible = visible;
		api.removeAllWaypoints(SeedMapClient.MOD_ID);
		if (!visible || seedMap.pins().isEmpty()) {
			return;
		}
		if (group == null) {
			group = WaypointFactory.createWaypointGroup(SeedMapClient.MOD_ID, GROUP_NAME);
			group.setPersistent(false);
			api.addWaypointGroup(group);
		}
		for (WorldData.Pin pin : seedMap.pins()) {
			// Not persisted by JourneyMap: the pin list in config/jm_seedmap is the source of truth.
			Waypoint waypoint = WaypointFactory.createWaypoint(SeedMapClient.MOD_ID, pin.pos(), pin.name(), pin.dimensionKey(), false);
			waypoint.setColor(pin.color());
			group.addWaypoint(waypoint);
			api.addWaypoint(SeedMapClient.MOD_ID, waypoint);
		}
	}

	private void syncSlime(SeedMap seedMap) {
		Map<String, SlimeTile> wanted = new HashMap<>();
		// Fullscreen only: JourneyMap does not clip image overlays to the round minimap.
		for (Context.UI ui : new Context.UI[]{Context.UI.Fullscreen}) {
			UIState state = api.getUIState(ui);
			if (state == null || !state.active || state.blockBounds == null || state.dimension == null) {
				continue;
			}
			Long seed = SlimeChunks.seedFor(seedMap, state.dimension);
			if (seed == null) {
				continue;
			}
			AABB b = state.blockBounds;
			int cx = Math.floorDiv((int) ((b.minX + b.maxX) / 2), 512);
			int cz = Math.floorDiv((int) ((b.minZ + b.maxZ) / 2), 512);
			int minX = Math.max(Math.floorDiv((int) b.minX, 512), cx - 6);
			int maxX = Math.min(Math.floorDiv((int) b.maxX, 512), cx + 6);
			int minZ = Math.max(Math.floorDiv((int) b.minZ, 512), cz - 6);
			int maxZ = Math.min(Math.floorDiv((int) b.maxZ, 512), cz + 6);
			for (int tx = minX; tx <= maxX; tx++) {
				for (int tz = minZ; tz <= maxZ; tz++) {
					wanted.put(state.dimension.identifier() + "|" + seed + "|" + tx + "," + tz, new SlimeTile(state.dimension, seed, tx, tz));
				}
			}
		}
		Iterator<Map.Entry<String, ImageOverlay>> it = slimeShown.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, ImageOverlay> entry = it.next();
			if (!wanted.containsKey(entry.getKey())) {
				api.remove(entry.getValue());
				it.remove();
			}
		}
		for (Map.Entry<String, SlimeTile> entry : wanted.entrySet()) {
			if (!slimeShown.containsKey(entry.getKey())) {
				showSlimeTile(entry.getKey(), entry.getValue());
			}
		}
	}

	private record SlimeTile(ResourceKey<Level> dimension, long seed, int tileX, int tileZ) {
	}

	private void showSlimeTile(String key, SlimeTile tile) {
		int tx = tile.tileX();
		int tz = tile.tileZ();
		long[] mask = SlimeChunks.tileMask(tile.seed(), tx, tz);
		NativeImage image = new NativeImage(32, 32, true);
		for (int x = 0; x < 32; x++) {
			for (int z = 0; z < 32; z++) {
				if ((mask[x] >>> z & 1L) != 0) {
					image.setPixel(x, z, 0x6630E040);
				}
			}
		}
		MapImage mapImage = new MapImage(image).setBlur(false);
		ImageOverlay overlay = new ImageOverlay(SeedMapClient.MOD_ID, new BlockPos(tx * 512, 64, tz * 512),
			new BlockPos(tx * 512 + 512, 64, tz * 512 + 512), mapImage);
		overlay.setDimension(tile.dimension()).setOverlayGroupName("SeedMap slime").setDisplayOrder(-100)
			.setActiveUIs(Context.UI.Fullscreen, Context.UI.Webmap);
		try {
			api.show(overlay);
			slimeShown.put(key, overlay);
		} catch (Exception e) {
			SeedMapClient.LOGGER.warn("JourneyMap refused slime overlay", e);
			image.close();
		}
	}

	/**
	 * Biome colors on the fullscreen map. JourneyMap has no API for "explored or not", so the layer is drawn
	 * translucent over everything; use the opacity setting to keep the real map readable.
	 */
	private void syncBiomes(SeedMap seedMap) {
		SeedMapConfig config = SeedMapConfig.get();
		Map<String, BiomeLayer.Tile> wanted = new HashMap<>();
		UIState state = api.getUIState(Context.UI.Fullscreen);
		if (config.enabled && config.showBiomes && state != null && state.active && state.blockBounds != null && state.dimension != null) {
			AABB b = state.blockBounds;
			var perf = ru.per.jmseedmap.core.Perf.current();
			// Zoomed out: coarser cells over a wider area (see the Xaero backend).
			int minCell = BiomeLayer.cellForZoom((b.maxX - b.minX) / Math.max(1, Minecraft.getInstance().getWindow().getWidth()));
			int radius = BiomeLayer.radiusFor(perf, minCell);
			int cx = Math.floorDiv((int) ((b.minX + b.maxX) / 2), 512);
			int cz = Math.floorDiv((int) ((b.minZ + b.maxZ) / 2), 512);
			int minX = Math.max(Math.floorDiv((int) b.minX, 512), cx - radius);
			int maxX = Math.min(Math.floorDiv((int) b.maxX, 512), cx + radius);
			int minZ = Math.max(Math.floorDiv((int) b.minZ, 512), cz - radius);
			int maxZ = Math.min(Math.floorDiv((int) b.maxZ, 512), cz + radius);
			for (int tx = minX; tx <= maxX; tx++) {
				for (int tz = minZ; tz <= maxZ; tz++) {
					BiomeLayer.Tile tile = seedMap.biomes.get(state.dimension, tx, tz, minCell);
					if (tile != null) {
						wanted.put(state.dimension.identifier() + "|" + tx + "," + tz, tile);
					}
				}
			}
		}
		boolean opacityChanged = biomeOpacity != config.biomeOpacity || biomeHighlight != BiomeLayer.highlightVersion();
		biomeOpacity = config.biomeOpacity;
		biomeHighlight = BiomeLayer.highlightVersion();
		Iterator<Map.Entry<String, ImageOverlay>> it = biomeShown.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, ImageOverlay> entry = it.next();
			BiomeLayer.Tile tile = wanted.get(entry.getKey());
			if (tile == null || tile != biomeTiles.get(entry.getKey()) || opacityChanged) {
				api.remove(entry.getValue());
				biomeTiles.remove(entry.getKey());
				it.remove();
			}
		}
		ResourceKey<Level> dim = state == null ? null : state.dimension;
		for (Map.Entry<String, BiomeLayer.Tile> entry : wanted.entrySet()) {
			if (!biomeShown.containsKey(entry.getKey()) && dim != null) {
				showBiomeTile(entry.getKey(), dim, entry.getValue(), config.biomeOpacity);
			}
		}
	}

	private void showBiomeTile(String key, ResourceKey<Level> dimension, BiomeLayer.Tile tile, int opacity) {
		int size = tile.size();
		int[] colors = ru.per.jmseedmap.map.BiomeTextures.colors(tile, ru.per.jmseedmap.map.BiomeTextures.FULL_MASK, opacity);
		NativeImage image = new NativeImage(size, size, false);
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				image.setPixel(x, z, colors[z * size + x]);
			}
		}
		int tx = tile.tileX() * 512;
		int tz = tile.tileZ() * 512;
		ImageOverlay overlay = new ImageOverlay(SeedMapClient.MOD_ID, new BlockPos(tx, 64, tz), new BlockPos(tx + 512, 64, tz + 512),
			new MapImage(image).setBlur(false));
		overlay.setDimension(dimension).setOverlayGroupName("SeedMap biomes").setDisplayOrder(-200).setActiveUIs(Context.UI.Fullscreen);
		try {
			api.show(overlay);
			biomeShown.put(key, overlay);
			biomeTiles.put(key, tile);
		} catch (Exception e) {
			SeedMapClient.LOGGER.warn("JourneyMap refused biome overlay", e);
			image.close();
		}
	}

	private void hideAll() {
		if (!shown.isEmpty()) {
			shown.values().forEach(api::remove);
			shown.clear();
		}
	}
}
