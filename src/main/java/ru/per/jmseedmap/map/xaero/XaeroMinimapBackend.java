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
import ru.per.jmseedmap.core.SlimeChunks;
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

	private static boolean broken;

	@Override
	public void tick(SeedMap seedMap) {
		if (renderer == null && HudMod.INSTANCE != null && HudMod.INSTANCE.getMinimap() != null) {
			renderer = new StructureRenderer();
			var handler = HudMod.INSTANCE.getMinimap().getOverMapRendererHandler();
			handler.add(renderer);
			if (SlimeRenderer.View.init(handler)) {
				handler.add(new SlimeRenderer());
			}
		}
		syncPins(seedMap);
	}

	/** Logs the first failure and turns the minimap layers off. */
	static void fail(String where, Throwable t) {
		if (!broken) {
			broken = true;
			SeedMapClient.LOGGER.error("Xaero's Minimap layer failed in {}; disabling it (Xaero version not supported?)", where, t);
		}
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
		String key = "";
	}

	/** Draws structure icons on the minimap; anything outside the minimap circle/square is skipped. */
	static final class StructureRenderer extends MinimapElementRenderer<FoundStructure, Context> {
		StructureRenderer() {
			super(new Reader(), new Provider(), new Context());
		}

		@Override
		public void preRender(MinimapElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers) {
			try {
				SeedMap seedMap = SeedMap.get();
				ResourceKey<Level> dim = info.mapDimension;
				double x = info.renderPos.x;
				double z = info.renderPos.z;
				seedMap.index.requestView("xaero_minimap", dim, x - VIEW_RADIUS, z - VIEW_RADIUS, x + VIEW_RADIUS, z + VIEW_RADIUS);
				// Recomputed when the player crosses into another 16-block cell or the data changes.
				String key = dim.identifier() + "|" + Math.floorDiv((int) x, 16) + "," + Math.floorDiv((int) z, 16) + "|"
					+ seedMap.index.completedTiles() + "|" + seedMap.revision() + "|" + StructureStyles.filterVersion();
				if (key.equals(context.key)) {
					return;
				}
				context.key = key;
				List<FoundStructure> list = new ArrayList<>();
				for (FoundStructure s : seedMap.index.query(dim, x - VIEW_RADIUS, z - VIEW_RADIUS, x + VIEW_RADIUS, z + VIEW_RADIUS)) {
					if (!seedMap.isHidden(s)) {
						list.add(s);
					}
				}
				context.elements = list;
			} catch (Throwable t) {
				fail("structures", t);
				context.elements = List.of();
			}
		}

		@Override
		public void postRender(MinimapElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers) {
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
			try {
				pose.translate(partialX, partialY, 0.0);
				float scale = optionalScale * 0.5f;
				pose.scale(scale, scale, 1.0f);
				Identifier icon = StructureStyles.icon(element.displayId(), SeedMap.get().isDimmed(element));
				int size = StructureStyles.ICON_SIZE;
				// This blit swaps v1/v2 (the minimap is drawn upside down into its framebuffer).
				graphics.blit(icon, -size / 2, -size / 2, 0, 0, size, size, size, size, size, RenderPipelines.GUI_TEXTURED);
			} catch (Throwable t) {
				fail("structure icon", t);
			} finally {
				pose.popPose();
			}
			return true;
		}

		@Override
		public boolean shouldRender(MinimapElementRenderLocation location) {
			SeedMapConfig config = SeedMapConfig.get();
			return !broken && location == MinimapElementRenderLocation.OVER_MINIMAP && config.enabled && config.showOnMinimap;
		}

		@Override
		public int getOrder() {
			// Below waypoints and players.
			return -100;
		}
	}

	/** The whole slime layer is one element at the minimap center that draws every slime chunk itself. */
	enum SlimeLayer { INSTANCE }

	static final class SlimeContext {
		List<SlimeLayer> elements = List.of();
		double centerX;
		double centerZ;
		/** Slime chunks around the player as (x, z) pairs, and what they were computed for. */
		int[] chunks = new int[0];
		String key = "";
		final java.util.Map<String, long[]> masks = new java.util.HashMap<>();
	}

	/**
	 * Slime chunks on the minimap. The minimap may rotate with the player, so each chunk is drawn as a square in
	 * world space under the same rotation and zoom Xaero uses to place elements (read from its element handler).
	 */
	static final class SlimeRenderer extends MinimapElementRenderer<SlimeLayer, SlimeContext> {
		private static boolean debugLogged;
		SlimeRenderer() {
			super(new SlimeReader(), new SlimeProvider(), new SlimeContext());
		}

		/** Xaero's current minimap transform: rotation (sin/cos), pixels per block and visible half size. */
		static final class View {
			private static @Nullable Object handler;
			private static java.lang.reflect.@Nullable Field ps;
			private static java.lang.reflect.@Nullable Field pc;
			private static java.lang.reflect.@Nullable Field zoom;
			private static java.lang.reflect.@Nullable Field halfW;
			private static java.lang.reflect.@Nullable Field halfH;
			private static java.lang.reflect.@Nullable Field circle;

			static boolean init(Object overMapHandler) {
				try {
					Class<?> type = overMapHandler.getClass();
					ps = field(type, "ps");
					pc = field(type, "pc");
					zoom = field(type, "zoom");
					halfW = field(type, "halfViewW");
					halfH = field(type, "halfViewH");
					circle = field(type, "circle");
					handler = overMapHandler;
					return true;
				} catch (ReflectiveOperationException | RuntimeException e) {
					SeedMapClient.LOGGER.warn("Slime chunks on Xaero's Minimap are unavailable in this Xaero version", e);
					return false;
				}
			}

			/** Chunks from the center to the minimap edge (from the last frame), plus a margin. */
			static int radiusChunks() {
				try {
					double zoom = View.zoom.getDouble(handler);
					int half = Math.max(View.halfW.getInt(handler), View.halfH.getInt(handler));
					return zoom <= 0 ? 12 : Math.clamp((int) Math.ceil(half * 1.5 / zoom / 16) + 1, 4, 40);
				} catch (ReflectiveOperationException | RuntimeException e) {
					return 12;
				}
			}

			private static java.lang.reflect.Field field(Class<?> type, String name) throws NoSuchFieldException {
				java.lang.reflect.Field f = type.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			}
		}

		@Override
		public void preRender(MinimapElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers) {
			context.elements = List.of();
			try {
				Long seed = SlimeChunks.seedFor(SeedMap.get(), info.mapDimension);
				if (seed == null || View.handler == null) {
					return;
				}
				context.centerX = info.renderPos.x;
				context.centerZ = info.renderPos.z;
				int radius = View.radiusChunks();
				int pcx = Math.floorDiv((int) Math.floor(info.renderPos.x), 16);
				int pcz = Math.floorDiv((int) Math.floor(info.renderPos.z), 16);
				String key = seed + "|" + pcx + "," + pcz + "|" + radius;
				if (!key.equals(context.key)) {
					context.key = key;
					if (context.masks.size() > 64) {
						context.masks.clear();
					}
					int[] out = new int[2 * (2 * radius + 1) * (2 * radius + 1)];
					int n = 0;
					for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
						for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
							int tx = Math.floorDiv(cx, 32);
							int tz = Math.floorDiv(cz, 32);
							long[] mask = context.masks.computeIfAbsent(tx + "," + tz, k -> SlimeChunks.tileMask(seed, tx, tz));
							if ((mask[Math.floorMod(cx, 32)] >>> Math.floorMod(cz, 32) & 1L) != 0) {
								out[n++] = cx;
								out[n++] = cz;
							}
						}
					}
					context.chunks = java.util.Arrays.copyOf(out, n);
				}
				context.elements = List.of(SlimeLayer.INSTANCE);
			} catch (Throwable t) {
				fail("slime chunks", t);
			}
		}

		@Override
		public void postRender(MinimapElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers) {
		}

		@Override
		public boolean renderElement(
			SlimeLayer element, boolean highlighted, boolean outOfBounds, double optionalDepth, float optionalScale,
			double partialX, double partialY, MinimapElementRenderInfo info, MinimapElementGraphics graphics, XaeroBufferProvider buffers
		) {
			var pose = graphics.pose();
			pose.pushPose();
			try {
				Object h = View.handler;
				double sin = View.ps.getDouble(h);
				double cos = View.pc.getDouble(h);
				double zoom = View.zoom.getDouble(h);
				int halfW = View.halfW.getInt(h);
				int halfH = View.halfH.getInt(h);
				boolean circle = View.circle.getBoolean(h);
				if (!debugLogged && Boolean.getBoolean("jm_seedmap.selftest")) {
					debugLogged = true;
					var m = pose.last().pose();
					SeedMapClient.LOGGER.info("SELFTEST minimap view ps={} pc={} zoom={} half={}x{} circle={} partial={},{} pose m00={} m11={} m30={} m31={} chunks={}",
						sin, cos, zoom, halfW, halfH, circle, partialX, partialY, m.m00(), m.m11(), m.m30(), m.m31(), context.chunks.length / 2);
				}
				// Xaero places a point at offset (dx, dz) at ((ps*dx - pc*dz) * zoom, (pc*dx + ps*dz) * zoom): a rotation.
				pose.translate(partialX, partialY, 0.0);
				pose.mulPose(new org.joml.Quaternionf().rotationZ((float) Math.atan2(cos, sin)));
				pose.scale((float) zoom, (float) zoom, 1.0f);
				double baseX = Math.floor(context.centerX);
				double baseZ = Math.floor(context.centerZ);
				pose.translate(baseX - context.centerX, baseZ - context.centerZ, 0.0);
				double limit = Math.max(0, Math.min(halfW, halfH) - 4);
				int[] chunks = context.chunks;
				for (int i = 0; i < chunks.length; i += 2) {
					double dx = chunks[i] * 16 + 8 - context.centerX;
					double dz = chunks[i + 1] * 16 + 8 - context.centerZ;
					double sx = (sin * dx - cos * dz) * zoom;
					double sy = (cos * dx + sin * dz) * zoom;
					boolean visible = circle ? sx * sx + sy * sy <= limit * limit : Math.abs(sx) <= halfW - 4 && Math.abs(sy) <= halfH - 4;
					if (visible) {
						int x0 = (int) (chunks[i] * 16 - baseX);
						int z0 = (int) (chunks[i + 1] * 16 - baseZ);
						graphics.fill(x0 + 1, z0 + 1, x0 + 15, z0 + 15, 0x6630E040);
					}
				}
			} catch (Throwable t) {
				fail("slime chunk", t);
			} finally {
				pose.popPose();
			}
			return true;
		}

		@Override
		public boolean shouldRender(MinimapElementRenderLocation location) {
			SeedMapConfig config = SeedMapConfig.get();
			return !broken && location == MinimapElementRenderLocation.OVER_MINIMAP && config.enabled && config.showOnMinimap && config.showSlimeChunks;
		}

		@Override
		public int getOrder() {
			return -200;
		}
	}

	static final class SlimeProvider extends MinimapElementRenderProvider<SlimeLayer, SlimeContext> {
		private @Nullable Iterator<SlimeLayer> iterator;

		@Override
		public void begin(MinimapElementRenderLocation location, SlimeContext context) {
			iterator = context.elements.iterator();
		}

		@Override
		public boolean hasNext(MinimapElementRenderLocation location, SlimeContext context) {
			return iterator != null && iterator.hasNext();
		}

		@Override
		public SlimeLayer getNext(MinimapElementRenderLocation location, SlimeContext context) {
			return iterator.next();
		}

		@Override
		public void end(MinimapElementRenderLocation location, SlimeContext context) {
			iterator = null;
		}
	}

	static final class SlimeReader extends MinimapElementReader<SlimeLayer, SlimeContext> {
		@Override
		public boolean isHidden(SlimeLayer element, SlimeContext context) {
			return false;
		}

		@Override
		public double getRenderX(SlimeLayer element, SlimeContext context, float partialTicks) {
			return context.centerX;
		}

		@Override
		public double getRenderY(SlimeLayer element, SlimeContext context, float partialTicks) {
			return 64;
		}

		@Override
		public double getRenderZ(SlimeLayer element, SlimeContext context, float partialTicks) {
			return context.centerZ;
		}

		@Override
		public double getCoordinateScale(SlimeLayer element, SlimeContext context, MinimapElementRenderInfo renderInfo) {
			return renderInfo.backgroundCoordinateScale;
		}

		@Override
		public int getInteractionBoxLeft(SlimeLayer element, SlimeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getInteractionBoxRight(SlimeLayer element, SlimeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getInteractionBoxTop(SlimeLayer element, SlimeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getInteractionBoxBottom(SlimeLayer element, SlimeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getRenderBoxLeft(SlimeLayer element, SlimeContext context, float partialTicks) {
			return -1;
		}

		@Override
		public int getRenderBoxRight(SlimeLayer element, SlimeContext context, float partialTicks) {
			return 1;
		}

		@Override
		public int getRenderBoxTop(SlimeLayer element, SlimeContext context, float partialTicks) {
			return -1;
		}

		@Override
		public int getRenderBoxBottom(SlimeLayer element, SlimeContext context, float partialTicks) {
			return 1;
		}

		@Override
		public int getLeftSideLength(SlimeLayer element, net.minecraft.client.Minecraft mc) {
			return 0;
		}

		@Override
		public String getMenuName(SlimeLayer element) {
			return "";
		}

		@Override
		public String getFilterName(SlimeLayer element) {
			return "";
		}

		@Override
		public int getMenuTextFillLeftPadding(SlimeLayer element) {
			return 0;
		}

		@Override
		public int getRightClickTitleBackgroundColor(SlimeLayer element) {
			return 0;
		}

		@Override
		public boolean shouldScaleBoxWithOptionalScale() {
			return false;
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
