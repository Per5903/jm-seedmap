package ru.per.jmseedmap.map.jm;

import java.awt.geom.Point2D;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.IOverlayListener;
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
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.core.SeedMap;
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
		String id = structure.id();
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
			String name = StructureStyles.displayName(structure.id());
			boolean pinned = seedMap.isPinned(structure);
			menu.addMenuItem(I18n.get(pinned ? "jm_seedmap.menu.unpin" : "jm_seedmap.menu.pin", name),
				clicked -> seedMap.setPinned(structure, !pinned));
			menu.addMenuItem(I18n.get("jm_seedmap.menu.target"), clicked -> seedMap.setTarget(structure));
			boolean visited = seedMap.isVisited(structure);
			menu.addMenuItem(I18n.get(visited ? "jm_seedmap.menu.unvisit" : "jm_seedmap.menu.visit"),
				clicked -> seedMap.setVisited(structure, !visited));
			menu.addMenuItem(I18n.get("jm_seedmap.menu.hide_type", StructureStyles.groupOf(structure.id()).displayName()), clicked -> {
				StructureStyles.groupOf(structure.id()).setEnabled(false);
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

	private void hideAll() {
		if (!shown.isEmpty()) {
			shown.values().forEach(api::remove);
			shown.clear();
		}
	}
}
