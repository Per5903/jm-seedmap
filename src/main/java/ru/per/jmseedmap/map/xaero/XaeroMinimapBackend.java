package ru.per.jmseedmap.map.xaero;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.StructureStyles;
import ru.per.jmseedmap.core.WorldData;
import ru.per.jmseedmap.gen.FoundStructure;
import ru.per.jmseedmap.map.MapBackend;
import xaero.common.HudMod;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;
import xaero.hud.minimap.element.render.MinimapElementRenderer;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;
import xaero.hud.minimap.world.container.MinimapWorldContainer;
import xaero.hud.minimap.world.container.MinimapWorldRootContainer;
import xaero.lib.client.graphics.XaeroBufferProvider;

/**
 * Xaero's Minimap: structure icons are a custom element layer drawn over the minimap (not waypoints).
 * Pins become "third-party" waypoints under their own origin, the same mechanism Waystones uses:
 * they never enter the player's waypoint sets or files.
 */
public final class XaeroMinimapBackend implements MapBackend {
	public static final Identifier PINS_ORIGIN = Identifier.fromNamespaceAndPath(SeedMapClient.MOD_ID, "pins");
	private static final double VIEW_RADIUS = 320;

	private @Nullable StructureRenderer renderer;
	private @Nullable MinimapWorldRootContainer syncedRoot;
	private int syncedPins = -1;
	private boolean syncedVisible;

	@Override
	public String name() {
		return "Xaero's Minimap";
	}

	@Override
	public void tick(SeedMap seedMap) {
		if (renderer == null && HudMod.INSTANCE != null && HudMod.INSTANCE.getMinimap() != null) {
			renderer = new StructureRenderer();
			HudMod.INSTANCE.getMinimap().getOverMapRendererHandler().add(renderer);
		}
		syncPins(seedMap);
	}

	private void syncPins(SeedMap seedMap) {
		MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
		if (session == null) {
			return;
		}
		MinimapWorldRootContainer root = session.getWorldManager().getAutoRootContainer();
		boolean visible = SeedMapConfig.get().showPins;
		if (root == null || (root == syncedRoot && syncedPins == seedMap.pinsRevision() && syncedVisible == visible)) {
			return;
		}
		syncedRoot = root;
		syncedPins = seedMap.pinsRevision();
		syncedVisible = visible;
		for (MinimapWorldContainer container : root.getSubContainers()) {
			container.getThirdPartyWaypointManager().clearOrigin(PINS_ORIGIN);
		}
		for (WorldData.Pin pin : seedMap.pins()) {
			String dirName = session.getDimensionHelper().getDimensionDirectoryName(pin.dimensionKey());
			MinimapWorldContainer container = root.addSubContainer(root.getPath().resolve(dirName));
			ThirdPartyWaypoints waypoints = container.getThirdPartyWaypointManager().get(PINS_ORIGIN);
			waypoints.setEnabledStateGetter(() -> SeedMapConfig.get().showPins);
			String initials = pin.name().replace("→ ", "");
			initials = initials.isEmpty() ? "S" : initials.substring(0, 1).toUpperCase();
			Waypoint waypoint = new Waypoint(pin.x(), pin.y(), pin.z(), pin.name(), initials, closestColor(pin.color()), WaypointPurpose.NORMAL, false, false);
			waypoints.add(pin.target() ? "target" : pin.key(), waypoint);
		}
	}

	static WaypointColor closestColor(int rgb) {
		WaypointColor best = WaypointColor.WHITE;
		long bestDist = Long.MAX_VALUE;
		for (WaypointColor color : WaypointColor.values()) {
			int c = color.getHex();
			long dr = ((c >> 16) & 0xFF) - ((rgb >> 16) & 0xFF);
			long dg = ((c >> 8) & 0xFF) - ((rgb >> 8) & 0xFF);
			long db = (c & 0xFF) - (rgb & 0xFF);
			long dist = dr * dr + dg * dg + db * db;
			if (dist < bestDist) {
				bestDist = dist;
				best = color;
			}
		}
		return best;
	}

	static final class Context {
		List<FoundStructure> elements = List.of();
	}

	/** Draws structure icons on the minimap; anything outside the minimap circle/square is skipped. */
	static final class StructureRenderer extends MinimapElementRenderer<FoundStructure, Context> {
		StructureRenderer() {
			super(new Reader(), new Provider(), new Context());
		}

		@Override
		public void preRender(MinimapElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers) {
			SeedMap seedMap = SeedMap.get();
			ResourceKey<Level> dim = info.mapDimension;
			double x = info.renderPos.x;
			double z = info.renderPos.z;
			seedMap.index.requestView("xaero_minimap", dim, x - VIEW_RADIUS, z - VIEW_RADIUS, x + VIEW_RADIUS, z + VIEW_RADIUS);
			List<FoundStructure> list = new ArrayList<>();
			for (FoundStructure s : seedMap.index.query(dim, x - VIEW_RADIUS, z - VIEW_RADIUS, x + VIEW_RADIUS, z + VIEW_RADIUS)) {
				if (!seedMap.isHidden(s)) {
					list.add(s);
				}
			}
			context.elements = list;
		}

		@Override
		public void postRender(MinimapElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers) {
			context.elements = List.of();
		}

		@Override
		public boolean renderElement(
			FoundStructure element, boolean highlighted, boolean outOfBounds, double optionalDepth, float optionalScale,
			double partialX, double partialY, MinimapElementRenderInfo info, MinimapElementGraphics graphics, XaeroBufferProvider buffers
		) {
			if (outOfBounds) {
				return false;
			}
			var pose = graphics.pose();
			pose.pushPose();
			pose.translate(partialX, partialY, 0.0);
			float scale = optionalScale * 0.5f;
			pose.scale(scale, scale, 1.0f);
			Identifier icon = StructureStyles.icon(element.displayId(), SeedMap.get().isDimmed(element));
			int size = StructureStyles.ICON_SIZE;
			// This blit swaps v1/v2 (the minimap is drawn upside down into its framebuffer).
			graphics.blit(icon, -size / 2, -size / 2, 0, 0, size, size, size, size, size, RenderPipelines.GUI_TEXTURED);
			pose.popPose();
			return true;
		}

		@Override
		public boolean shouldRender(MinimapElementRenderLocation location) {
			SeedMapConfig config = SeedMapConfig.get();
			return location == MinimapElementRenderLocation.OVER_MINIMAP && config.enabled && config.showOnMinimap;
		}

		@Override
		public int getOrder() {
			// Below waypoints and players.
			return -100;
		}
	}

	static final class Provider extends MinimapElementRenderProvider<FoundStructure, Context> {
		private @Nullable Iterator<FoundStructure> iterator;

		@Override
		public void begin(MinimapElementRenderLocation location, Context context) {
			iterator = context.elements.iterator();
		}

		@Override
		public boolean hasNext(MinimapElementRenderLocation location, Context context) {
			return iterator != null && iterator.hasNext();
		}

		@Override
		public FoundStructure getNext(MinimapElementRenderLocation location, Context context) {
			return iterator.next();
		}

		@Override
		public void end(MinimapElementRenderLocation location, Context context) {
			iterator = null;
		}
	}

	static final class Reader extends MinimapElementReader<FoundStructure, Context> {
		@Override
		public boolean isHidden(FoundStructure element, Context context) {
			return false;
		}

		@Override
		public double getRenderX(FoundStructure element, Context context, float partialTicks) {
			return element.pos().getX() + 0.5;
		}

		@Override
		public double getRenderY(FoundStructure element, Context context, float partialTicks) {
			return element.pos().getY();
		}

		@Override
		public double getRenderZ(FoundStructure element, Context context, float partialTicks) {
			return element.pos().getZ() + 0.5;
		}

		@Override
		public double getCoordinateScale(FoundStructure element, Context context, MinimapElementRenderInfo renderInfo) {
			// Coordinates are already in the map dimension's own space.
			return renderInfo.backgroundCoordinateScale;
		}

		@Override
		public int getInteractionBoxLeft(FoundStructure element, Context context, float partialTicks) {
			return -6;
		}

		@Override
		public int getInteractionBoxRight(FoundStructure element, Context context, float partialTicks) {
			return 6;
		}

		@Override
		public int getInteractionBoxTop(FoundStructure element, Context context, float partialTicks) {
			return -6;
		}

		@Override
		public int getInteractionBoxBottom(FoundStructure element, Context context, float partialTicks) {
			return 6;
		}

		@Override
		public int getRenderBoxLeft(FoundStructure element, Context context, float partialTicks) {
			return -8;
		}

		@Override
		public int getRenderBoxRight(FoundStructure element, Context context, float partialTicks) {
			return 8;
		}

		@Override
		public int getRenderBoxTop(FoundStructure element, Context context, float partialTicks) {
			return -8;
		}

		@Override
		public int getRenderBoxBottom(FoundStructure element, Context context, float partialTicks) {
			return 8;
		}

		@Override
		public int getLeftSideLength(FoundStructure element, net.minecraft.client.Minecraft mc) {
			return 9 + mc.font.width(getMenuName(element));
		}

		@Override
		public String getMenuName(FoundStructure element) {
			return StructureStyles.displayName(element.displayId());
		}

		@Override
		public String getFilterName(FoundStructure element) {
			return getMenuName(element);
		}

		@Override
		public int getMenuTextFillLeftPadding(FoundStructure element) {
			return 0;
		}

		@Override
		public int getRightClickTitleBackgroundColor(FoundStructure element) {
			return 0xFF000000 | StructureStyles.style(element.displayId()).color();
		}

		@Override
		public boolean shouldScaleBoxWithOptionalScale() {
			return true;
		}
	}
}
