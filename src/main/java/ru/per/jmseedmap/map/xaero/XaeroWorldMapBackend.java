package ru.per.jmseedmap.map.xaero;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.core.BiomeLayer;
import ru.per.jmseedmap.core.Perf;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.SlimeChunks;
import ru.per.jmseedmap.core.StructureStyles;
import ru.per.jmseedmap.gen.FoundStructure;
import ru.per.jmseedmap.map.BiomeTextures;
import ru.per.jmseedmap.map.MapBackend;
import ru.per.jmseedmap.ui.SearchScreen;
import xaero.lib.client.graphics.XaeroBufferProvider;
import xaero.map.MapProcessor;
import xaero.map.WorldMap;
import xaero.map.WorldMapSession;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.region.MapRegion;

/**
 * Xaero's World Map: structure icons as a custom element layer with a right-click menu
 * (pin as waypoint, set as target, mark visited, hide type), slime chunks and a biome layer under them.
 * The layers follow whatever dimension the world map is currently showing.
 * <p>
 * Every hook that Xaero calls is guarded: if a future Xaero version changes something we rely on,
 * the layer switches itself off with one log line instead of breaking the map.
 */
public final class XaeroWorldMapBackend implements MapBackend {
	private static boolean broken;

	private @Nullable StructureRenderer renderer;

	@Override
	public String name() {
		return "Xaero's World Map";
	}

	@Override
	public void tick(SeedMap seedMap) {
		if (renderer == null && WorldMap.mapElementRenderHandler != null) {
			renderer = new StructureRenderer();
			WorldMap.mapElementRenderHandler.add(renderer);
			WorldMap.mapElementRenderHandler.add(new SlimeRenderer());
			WorldMap.mapElementRenderHandler.add(new BiomeRenderer());
		}
	}

	/** Logs the first failure and turns all layers of this backend off. */
	static void fail(String where, Throwable t) {
		if (!broken) {
			broken = true;
			SeedMapClient.LOGGER.error("Xaero's World Map layer failed in {}; disabling it (Xaero version not supported?)", where, t);
		}
	}

	private static double halfWidth(ElementRenderInfo info) {
		return Minecraft.getInstance().getWindow().getWidth() / 2.0 / info.scale;
	}

	private static double halfHeight(ElementRenderInfo info) {
		return Minecraft.getInstance().getWindow().getHeight() / 2.0 / info.scale;
	}

	// ---- structures ----

	static final class Context {
		List<FoundStructure> elements = List.of();
		double mapScale = 1.0;
		/** What {@link #elements} was computed for; recomputed only when this changes. */
		String key = "";
	}

	static final class StructureRenderer extends ElementRenderer<FoundStructure, Context, StructureRenderer> {
		StructureRenderer() {
			super(new Context(), new ListProvider<>(c -> c.elements), new Reader());
		}

		@Override
		public void preRender(ElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers, boolean shadow) {
			if (shadow) {
				return;
			}
			try {
				SeedMap seedMap = SeedMap.get();
				ResourceKey<Level> dim = info.mapDimension;
				double halfW = halfWidth(info);
				double halfH = halfHeight(info);
				double x = info.renderPos.x;
				double z = info.renderPos.z;
				seedMap.index.requestView("xaero_worldmap", dim, x - halfW, z - halfH, x + halfW, z + halfH);
				context.mapScale = info.scale;
				// The query walks every tile in view: only redo it when the view or the data changed.
				String key = dim.identifier() + "|" + Math.round(x / 16) + "," + Math.round(z / 16) + "|" + Math.round(halfW / 16) + ","
					+ Math.round(halfH / 16) + "|" + seedMap.index.completedTiles() + "|" + seedMap.revision() + "|" + StructureStyles.filterVersion();
				if (key.equals(context.key)) {
					return;
				}
				context.key = key;
				List<FoundStructure> list = new ArrayList<>();
				for (FoundStructure s : seedMap.index.query(dim, x - halfW - 32, z - halfH - 32, x + halfW + 32, z + halfH + 32)) {
					if (!seedMap.isHidden(s)) {
						list.add(s);
					}
				}
				int max = Perf.current().maxMarkers;
				if (list.size() > max) {
					// Zoomed far out: the markers nearest to the center are the useful ones.
					list.sort(Comparator.comparingDouble(s -> s.distanceSqr(x, z)));
					list = new ArrayList<>(list.subList(0, max));
				}
				context.elements = list;
			} catch (Throwable t) {
				fail("structures", t);
				context.elements = List.of();
			}
		}

		@Override
		public void postRender(ElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers, boolean shadow) {
		}

		@Override
		public void renderElementShadow(
			FoundStructure element, boolean hovered, float optionalScale, double partialX, double partialY,
			ElementRenderInfo info, MapElementGraphics graphics, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers
		) {
		}

		@Override
		public boolean renderElement(
			FoundStructure element, boolean hovered, double optionalDepth, float optionalScale, double partialX, double partialY,
			ElementRenderInfo info, MapElementGraphics graphics, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers
		) {
			SeedMap seedMap = SeedMap.get();
			var pose = graphics.pose();
			pose.pushPose();
			try {
				pose.translate(partialX, partialY, 0.0);
				pose.scale(optionalScale, optionalScale, 1.0f);
				float iconScale = (hovered ? 22f : 18f) / StructureStyles.ICON_SIZE;
				pose.pushPose();
				pose.scale(iconScale, iconScale, 1.0f);
				int size = StructureStyles.ICON_SIZE;
				graphics.blit(StructureStyles.icon(element.displayId(), seedMap.isDimmed(element)), -size / 2, -size / 2, 0, 0, size, size, size,
					RenderPipelines.GUI_TEXTURED);
				pose.popPose();
				SeedMapConfig config = SeedMapConfig.get();
				if (hovered || (config.showLabels && context.mapScale >= 2.0)) {
					Font font = Minecraft.getInstance().font;
					String name = StructureStyles.displayName(element.displayId());
					int width = font.width(name);
					graphics.fill(-width / 2 - 2, 11, width / 2 + 2, 21, 0x88000000);
					graphics.drawString(font, name, -width / 2, 12, seedMap.isDimmed(element) ? 0xFFAAAAAA : 0xFFFFFFFF);
				}
			} catch (Throwable t) {
				fail("structure icon", t);
			} finally {
				pose.popPose();
			}
			return false;
		}

		@Override
		public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
			SeedMapConfig config = SeedMapConfig.get();
			return !broken && location == ElementRenderLocation.WORLD_MAP && config.enabled && config.showOnWorldMap;
		}

		@Override
		public boolean shouldBeDimScaled() {
			// Positions are already in the shown dimension's coordinates.
			return false;
		}

		@Override
		public int getOrder() {
			// Below waypoints (0) and tracked players (200).
			return -100;
		}
	}

	// ---- slime chunks ----

	/** One 512x512 block tile of the slime chunk layer. */
	record SlimeTile(int tileX, int tileZ, long[] mask) {
	}

	static final class SlimeContext {
		List<SlimeTile> tiles = List.of();
		double mapScale = 1.0;
		final java.util.Map<String, long[]> masks = new java.util.HashMap<>();
	}

	/** Translucent green squares on slime chunks, drawn under the structure icons. */
	static final class SlimeRenderer extends ElementRenderer<SlimeTile, SlimeContext, SlimeRenderer> {
		SlimeRenderer() {
			super(new SlimeContext(), new ListProvider<>(c -> c.tiles), new SlimeReader());
		}

		@Override
		public void preRender(ElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers, boolean shadow) {
			context.tiles = List.of();
			try {
				Long seed = SlimeChunks.seedFor(SeedMap.get(), info.mapDimension);
				if (shadow || seed == null || info.scale < 0.15) {
					return;
				}
				double halfW = halfWidth(info);
				double halfH = halfHeight(info);
				int minX = Math.floorDiv((int) (info.renderPos.x - halfW), 512);
				int maxX = Math.floorDiv((int) (info.renderPos.x + halfW), 512);
				int minZ = Math.floorDiv((int) (info.renderPos.z - halfH), 512);
				int maxZ = Math.floorDiv((int) (info.renderPos.z + halfH), 512);
				if (context.masks.size() > 2048) {
					context.masks.clear();
				}
				List<SlimeTile> tiles = new ArrayList<>();
				for (int tx = minX; tx <= maxX; tx++) {
					for (int tz = minZ; tz <= maxZ; tz++) {
						int x = tx;
						int z = tz;
						long[] mask = context.masks.computeIfAbsent(seed + "|" + tx + "," + tz, k -> SlimeChunks.tileMask(seed, x, z));
						tiles.add(new SlimeTile(tx, tz, mask));
					}
				}
				context.tiles = tiles;
				context.mapScale = info.scale;
			} catch (Throwable t) {
				fail("slime chunks", t);
			}
		}

		@Override
		public void postRender(ElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers, boolean shadow) {
		}

		@Override
		public void renderElementShadow(
			SlimeTile element, boolean hovered, float optionalScale, double partialX, double partialY,
			ElementRenderInfo info, MapElementGraphics graphics, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers
		) {
		}

		@Override
		public boolean renderElement(
			SlimeTile element, boolean hovered, double optionalDepth, float optionalScale, double partialX, double partialY,
			ElementRenderInfo info, MapElementGraphics graphics, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers
		) {
			var pose = graphics.pose();
			pose.pushPose();
			try {
				pose.translate(partialX, partialY, 0.0);
				// From here on one unit is one block.
				pose.scale((float) info.scale, (float) info.scale, 1.0f);
				for (int x = 0; x < 32; x++) {
					long row = element.mask()[x];
					if (row == 0) {
						continue;
					}
					for (int z = 0; z < 32; z++) {
						if ((row >>> z & 1L) != 0) {
							graphics.fill(x * 16, z * 16, x * 16 + 16, z * 16 + 16, 0x5530E040);
						}
					}
				}
			} catch (Throwable t) {
				fail("slime chunk", t);
			} finally {
				pose.popPose();
			}
			return false;
		}

		@Override
		public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
			return !broken && location == ElementRenderLocation.WORLD_MAP && SeedMapConfig.get().showSlimeChunks && SeedMapConfig.get().enabled;
		}

		@Override
		public boolean shouldBeDimScaled() {
			return false;
		}

		@Override
		public int getOrder() {
			return -200;
		}
	}

	// ---- biomes ----

	/** A biome tile texture at the tile's north-west corner, or (with {@code label}) the biome name under the cursor. */
	record BiomeElement(double x, double z, @Nullable Identifier texture, int size, @Nullable String label) {
	}

	static final class BiomeContext {
		List<BiomeElement> elements = List.of();
		double mapScale = 1.0;
		final BiomeTextures textures = new BiomeTextures("xaero_biomes");
		final java.util.Map<Long, long[]> masks = new java.util.HashMap<>();
		long masksTime;
		@Nullable String hoverLabel;
	}

	/**
	 * Biome colors from the seed. By default only where the world map has nothing yet: Xaero keeps its map in
	 * 512x512 block regions split into 64x64 block "tile chunks"; a tile chunk the player has not explored is
	 * missing from its region.
	 */
	static final class BiomeRenderer extends ElementRenderer<BiomeElement, BiomeContext, BiomeRenderer> {
		BiomeRenderer() {
			super(new BiomeContext(), new ListProvider<>(c -> c.elements), new BiomeReader());
		}

		@Override
		public void preRender(ElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers, boolean shadow) {
			context.elements = List.of();
			context.hoverLabel = null;
			if (shadow) {
				return;
			}
			try {
				SeedMapConfig config = SeedMapConfig.get();
				SeedMap seedMap = SeedMap.get();
				ResourceKey<Level> dim = info.mapDimension;
				Perf perf = Perf.current();
				double halfW = halfWidth(info);
				double halfH = halfHeight(info);
				// Zoomed out: coarser cells (cheap) over a wider area, so what was computed stays and the view fills in.
				int minCell = BiomeLayer.cellForZoom(1.0 / info.scale);
				int radius = BiomeLayer.radiusFor(perf, minCell);
				int cx = Math.floorDiv((int) info.renderPos.x, 512);
				int cz = Math.floorDiv((int) info.renderPos.z, 512);
				int minX = Math.max(Math.floorDiv((int) (info.renderPos.x - halfW), 512), cx - radius);
				int maxX = Math.min(Math.floorDiv((int) (info.renderPos.x + halfW), 512), cx + radius);
				int minZ = Math.max(Math.floorDiv((int) (info.renderPos.z - halfH), 512), cz - radius);
				int maxZ = Math.min(Math.floorDiv((int) (info.renderPos.z + halfH), 512), cz + radius);
				long now = net.minecraft.util.Util.getMillis();
				if (now - context.masksTime > 500) {
					context.masks.clear();
					context.masksTime = now;
				}
				MapProcessor processor = config.biomesOnlyUnexplored ? processor() : null;
				List<BiomeElement> list = new ArrayList<>();
				for (int[] t : centerFirst(minX, maxX, minZ, maxZ, cx, cz)) {
					int tx = t[0];
					int tz = t[1];
					{
						BiomeLayer.Tile tile = seedMap.biomes.get(dim, tx, tz, minCell);
						if (tile == null) {
							continue;
						}
						long mask = BiomeTextures.FULL_MASK;
						if (config.biomesOnlyUnexplored) {
							// (Highlighted biomes are drawn everywhere; the mask only limits the rest.)
							int x = tx;
							int z = tz;
							mask = context.masks.computeIfAbsent(((long) tx << 32) ^ (tz & 0xFFFFFFFFL), k -> new long[]{unexploredMask(processor, x, z)})[0];
						}
						if (mask != 0 || !BiomeLayer.highlighted().isEmpty()) {
							list.add(new BiomeElement(tx * 512.0, tz * 512.0, context.textures.texture(tile, mask, config.biomeOpacity), tile.size(), null));
						}
						if (tile.tileX() == Math.floorDiv((int) Math.floor(info.mouseX), 512) && tile.tileZ() == Math.floorDiv((int) Math.floor(info.mouseZ), 512)) {
							context.hoverLabel = SearchScreen.biomeNameOf(tile.biomeAt((int) Math.floor(info.mouseX), (int) Math.floor(info.mouseZ)));
						}
					}
				}
				if (context.hoverLabel != null) {
					list.add(new BiomeElement(info.mouseX, info.mouseZ, null, 0, context.hoverLabel));
				}
				context.textures.cleanup();
				context.elements = list;
				context.mapScale = info.scale;
			} catch (Throwable t) {
				fail("biomes", t);
				context.elements = List.of();
			}
		}

		/** Tile coordinates of the box, nearest to (cx, cz) first: those get computed first. */
		static List<int[]> centerFirst(int minX, int maxX, int minZ, int maxZ, int cx, int cz) {
			List<int[]> out = new ArrayList<>();
			for (int tx = minX; tx <= maxX; tx++) {
				for (int tz = minZ; tz <= maxZ; tz++) {
					out.add(new int[]{tx, tz});
				}
			}
			out.sort(Comparator.comparingInt(t -> Math.max(Math.abs(t[0] - cx), Math.abs(t[1] - cz))));
			return out;
		}

		private static @Nullable MapProcessor processor() {
			WorldMapSession session = WorldMapSession.getCurrentSession();
			return session == null ? null : session.getMapProcessor();
		}

		/** Bit z*8+x set = the 64x64 block square (x, z) of this region is not on the map yet. */
		private static long unexploredMask(@Nullable MapProcessor processor, int regionX, int regionZ) {
			if (processor == null) {
				return BiomeTextures.FULL_MASK;
			}
			int layer = processor.getCurrentCaveLayer();
			// In memory first: a region explored this session may not be saved to disk yet.
			MapRegion region = processor.getLeafMapRegion(layer, regionX, regionZ, false);
			if (region == null) {
				return processor.regionExists(layer, regionX, regionZ) ? 0L : BiomeTextures.FULL_MASK;
			}
			if (!region.isLoaded()) {
				// Explored, but not loaded into memory yet: draw nothing rather than cover the real map.
				return 0L;
			}
			long mask = 0L;
			for (int z = 0; z < 8; z++) {
				for (int x = 0; x < 8; x++) {
					if (region.getChunk(x, z) == null) {
						mask |= 1L << (z * 8 + x);
					}
				}
			}
			return mask;
		}

		@Override
		public void postRender(ElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers, boolean shadow) {
		}

		@Override
		public void renderElementShadow(
			BiomeElement element, boolean hovered, float optionalScale, double partialX, double partialY,
			ElementRenderInfo info, MapElementGraphics graphics, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers
		) {
		}

		@Override
		public boolean renderElement(
			BiomeElement element, boolean hovered, double optionalDepth, float optionalScale, double partialX, double partialY,
			ElementRenderInfo info, MapElementGraphics graphics, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers
		) {
			var pose = graphics.pose();
			pose.pushPose();
			try {
				pose.translate(partialX, partialY, 0.0);
				if (element.label() != null) {
					// Biome name next to the cursor, above the tile layers.
					Font font = Minecraft.getInstance().font;
					int width = font.width(element.label());
					graphics.fill(10, -16, 14 + width, -5, 0xAA000000);
					graphics.drawString(font, element.label(), 12, -15, 0xFFFFFFFF);
				} else if (element.texture() != null) {
					int size = element.size();
					// One texture pixel per biome cell, stretched over the 512 blocks of the tile.
					float scale = (float) info.scale * (512f / size);
					pose.scale(scale, scale, 1.0f);
					graphics.blit(element.texture(), 0, 0, 0, 0, size, size, size, RenderPipelines.GUI_TEXTURED);
				}
			} catch (Throwable t) {
				fail("biome tile", t);
			} finally {
				pose.popPose();
			}
			return false;
		}

		@Override
		public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
			SeedMapConfig config = SeedMapConfig.get();
			return !broken && location == ElementRenderLocation.WORLD_MAP && config.showBiomes && config.enabled;
		}

		@Override
		public boolean shouldBeDimScaled() {
			return false;
		}

		@Override
		public int getOrder() {
			return -300;
		}
	}

	static final class BiomeReader extends ElementReader<BiomeElement, BiomeContext, BiomeRenderer> {
		@Override
		public boolean isHidden(BiomeElement element, BiomeContext context) {
			return false;
		}

		@Override
		public double getRenderX(BiomeElement element, BiomeContext context, float partialTicks) {
			return element.x();
		}

		@Override
		public double getRenderZ(BiomeElement element, BiomeContext context, float partialTicks) {
			return element.z();
		}

		private static int tilePixels(BiomeContext context) {
			return (int) Math.ceil(512 * context.mapScale) + 1;
		}

		@Override
		public int getInteractionBoxLeft(BiomeElement element, BiomeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getInteractionBoxRight(BiomeElement element, BiomeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getInteractionBoxTop(BiomeElement element, BiomeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getInteractionBoxBottom(BiomeElement element, BiomeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getRenderBoxLeft(BiomeElement element, BiomeContext context, float partialTicks) {
			return element.label() != null ? 0 : -1;
		}

		@Override
		public int getRenderBoxRight(BiomeElement element, BiomeContext context, float partialTicks) {
			return element.label() != null ? 200 : tilePixels(context);
		}

		@Override
		public int getRenderBoxTop(BiomeElement element, BiomeContext context, float partialTicks) {
			return element.label() != null ? -20 : -1;
		}

		@Override
		public int getRenderBoxBottom(BiomeElement element, BiomeContext context, float partialTicks) {
			return element.label() != null ? 0 : tilePixels(context);
		}

		@Override
		public int getLeftSideLength(BiomeElement element, Minecraft mc) {
			return 0;
		}

		@Override
		public String getMenuName(BiomeElement element) {
			return "";
		}

		@Override
		public String getFilterName(BiomeElement element) {
			return "";
		}

		@Override
		public int getMenuTextFillLeftPadding(BiomeElement element) {
			return 0;
		}

		@Override
		public int getRightClickTitleBackgroundColor(BiomeElement element) {
			return 0;
		}

		@Override
		public boolean shouldScaleBoxWithOptionalScale() {
			return false;
		}
	}

	/** Reader for elements that cover a whole 512x512 tile anchored at its north-west corner. */
	static final class SlimeReader extends ElementReader<SlimeTile, SlimeContext, SlimeRenderer> {
		@Override
		public boolean isHidden(SlimeTile element, SlimeContext context) {
			return false;
		}

		@Override
		public double getRenderX(SlimeTile element, SlimeContext context, float partialTicks) {
			return element.tileX() * 512.0;
		}

		@Override
		public double getRenderZ(SlimeTile element, SlimeContext context, float partialTicks) {
			return element.tileZ() * 512.0;
		}

		private static int tilePixels(SlimeContext context) {
			return (int) Math.ceil(512 * context.mapScale) + 1;
		}

		@Override
		public int getInteractionBoxLeft(SlimeTile element, SlimeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getInteractionBoxRight(SlimeTile element, SlimeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getInteractionBoxTop(SlimeTile element, SlimeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getInteractionBoxBottom(SlimeTile element, SlimeContext context, float partialTicks) {
			return 0;
		}

		@Override
		public int getRenderBoxLeft(SlimeTile element, SlimeContext context, float partialTicks) {
			return -1;
		}

		@Override
		public int getRenderBoxRight(SlimeTile element, SlimeContext context, float partialTicks) {
			return tilePixels(context);
		}

		@Override
		public int getRenderBoxTop(SlimeTile element, SlimeContext context, float partialTicks) {
			return -1;
		}

		@Override
		public int getRenderBoxBottom(SlimeTile element, SlimeContext context, float partialTicks) {
			return tilePixels(context);
		}

		@Override
		public int getLeftSideLength(SlimeTile element, Minecraft mc) {
			return 0;
		}

		@Override
		public String getMenuName(SlimeTile element) {
			return "";
		}

		@Override
		public String getFilterName(SlimeTile element) {
			return "";
		}

		@Override
		public int getMenuTextFillLeftPadding(SlimeTile element) {
			return 0;
		}

		@Override
		public int getRightClickTitleBackgroundColor(SlimeTile element) {
			return 0;
		}

		@Override
		public boolean shouldScaleBoxWithOptionalScale() {
			return false;
		}
	}

	/** Iterates whatever list the context currently holds. */
	static final class ListProvider<E, C> extends ElementRenderProvider<E, C> {
		private final java.util.function.Function<C, List<E>> list;
		private @Nullable Iterator<E> iterator;

		ListProvider(java.util.function.Function<C, List<E>> list) {
			this.list = list;
		}

		@Override
		public void begin(ElementRenderLocation location, C context) {
			iterator = list.apply(context).iterator();
		}

		@Override
		public boolean hasNext(ElementRenderLocation location, C context) {
			return iterator != null && iterator.hasNext();
		}

		@Override
		public E getNext(ElementRenderLocation location, C context) {
			return iterator.next();
		}

		@Override
		public void end(ElementRenderLocation location, C context) {
			iterator = null;
		}
	}

	static final class Reader extends ElementReader<FoundStructure, Context, StructureRenderer> {
		@Override
		public boolean isHidden(FoundStructure element, Context context) {
			return false;
		}

		@Override
		public double getRenderX(FoundStructure element, Context context, float partialTicks) {
			return element.pos().getX() + 0.5;
		}

		@Override
		public double getRenderZ(FoundStructure element, Context context, float partialTicks) {
			return element.pos().getZ() + 0.5;
		}

		@Override
		public int getInteractionBoxLeft(FoundStructure element, Context context, float partialTicks) {
			return -9;
		}

		@Override
		public int getInteractionBoxRight(FoundStructure element, Context context, float partialTicks) {
			return 9;
		}

		@Override
		public int getInteractionBoxTop(FoundStructure element, Context context, float partialTicks) {
			return -9;
		}

		@Override
		public int getInteractionBoxBottom(FoundStructure element, Context context, float partialTicks) {
			return 9;
		}

		@Override
		public int getRenderBoxLeft(FoundStructure element, Context context, float partialTicks) {
			return -60;
		}

		@Override
		public int getRenderBoxRight(FoundStructure element, Context context, float partialTicks) {
			return 60;
		}

		@Override
		public int getRenderBoxTop(FoundStructure element, Context context, float partialTicks) {
			return -12;
		}

		@Override
		public int getRenderBoxBottom(FoundStructure element, Context context, float partialTicks) {
			return 24;
		}

		@Override
		public int getLeftSideLength(FoundStructure element, Minecraft mc) {
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

		@Override
		public boolean isInteractable(ElementRenderLocation location, FoundStructure element) {
			return true;
		}

		@Override
		public boolean isRightClickValid(FoundStructure element) {
			return true;
		}

		@Override
		public ArrayList<RightClickOption> getRightClickOptions(FoundStructure element, IRightClickableElement target) {
			SeedMap seedMap = SeedMap.get();
			ArrayList<RightClickOption> options = new ArrayList<>();
			options.add(new RightClickOption(getMenuName(element), options.size(), target) {
				@Override
				public void onAction(Screen screen) {
				}
			});
			// What is inside, if already known (otherwise it starts computing for the details screen).
			var details = seedMap.details.get(element);
			if (details != null) {
				for (var line : details.lines().subList(0, Math.min(details.lines().size(), 4))) {
					options.add(new RightClickOption(line.getString(), Style.EMPTY.withColor(ChatFormatting.AQUA), options.size(), target) {
						@Override
						public void onAction(Screen screen) {
						}
					});
				}
			}
			options.add(new RightClickOption(I18n.get("jm_seedmap.menu.details"), options.size(), target) {
				@Override
				public void onAction(Screen screen) {
					Minecraft.getInstance().gui.setScreen(new ru.per.jmseedmap.ui.StructureInfoScreen(screen, element));
				}
			});
			String coords = String.format("X: %d, Y: %d, Z: %d", element.pos().getX(), element.pos().getY(), element.pos().getZ());
			options.add(new RightClickOption(coords, Style.EMPTY.withColor(ChatFormatting.GRAY), options.size(), target) {
				@Override
				public void onAction(Screen screen) {
					Minecraft.getInstance().keyboardHandler.setClipboard(
						element.pos().getX() + " " + element.pos().getY() + " " + element.pos().getZ());
				}
			});
			boolean pinned = seedMap.isPinned(element);
			options.add(new RightClickOption(I18n.get(pinned ? "jm_seedmap.menu.unpin" : "jm_seedmap.menu.pin", getMenuName(element)),
				options.size(), target) {
				@Override
				public void onAction(Screen screen) {
					seedMap.setPinned(element, !pinned);
				}
			});
			options.add(new RightClickOption(I18n.get("jm_seedmap.menu.target"), options.size(), target) {
				@Override
				public void onAction(Screen screen) {
					seedMap.setTarget(element);
				}
			});
			boolean visited = seedMap.isVisited(element);
			options.add(new RightClickOption(I18n.get(visited ? "jm_seedmap.menu.unvisit" : "jm_seedmap.menu.visit"), options.size(), target) {
				@Override
				public void onAction(Screen screen) {
					seedMap.setVisited(element, !visited);
				}
			});
			StructureStyles.Group group = StructureStyles.groupOf(element.displayId());
			options.add(new RightClickOption(I18n.get("jm_seedmap.menu.hide_type", group.displayName()), options.size(), target) {
				@Override
				public void onAction(Screen screen) {
					group.setEnabled(false);
					SeedMapConfig.save();
					seedMap.invalidateVisuals();
				}
			});
			return options;
		}
	}
}
