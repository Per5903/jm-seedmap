package ru.per.jmseedmap.map.xaero;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.SlimeChunks;
import ru.per.jmseedmap.core.StructureStyles;
import ru.per.jmseedmap.gen.FoundStructure;
import ru.per.jmseedmap.map.MapBackend;
import xaero.lib.client.graphics.XaeroBufferProvider;
import xaero.map.WorldMap;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/**
 * Xaero's World Map: structure icons as a custom element layer with a right-click menu
 * (pin as waypoint, set as target, mark visited, hide type). The layer follows whatever dimension
 * the world map is currently showing.
 */
public final class XaeroWorldMapBackend implements MapBackend {
	private @Nullable StructureRenderer renderer;
	private @Nullable SlimeRenderer slimeRenderer;

	@Override
	public String name() {
		return "Xaero's World Map";
	}

	@Override
	public void tick(SeedMap seedMap) {
		if (renderer == null && WorldMap.mapElementRenderHandler != null) {
			renderer = new StructureRenderer();
			WorldMap.mapElementRenderHandler.add(renderer);
			slimeRenderer = new SlimeRenderer();
			WorldMap.mapElementRenderHandler.add(slimeRenderer);
		}
	}

	static final class Context {
		List<FoundStructure> elements = List.of();
		double mapScale = 1.0;
	}

	static final class StructureRenderer extends ElementRenderer<FoundStructure, Context, StructureRenderer> {
		StructureRenderer() {
			super(new Context(), new Provider(), new Reader());
		}

		@Override
		public void preRender(ElementRenderInfo info, XaeroBufferProvider buffers, MultiTextureRenderTypeRendererProvider renderers, boolean shadow) {
			if (shadow) {
				return;
			}
			SeedMap seedMap = SeedMap.get();
			ResourceKey<Level> dim = info.mapDimension;
			Minecraft mc = Minecraft.getInstance();
			// info.scale is screen pixels per block; take the whole framebuffer to be safe.
			double halfW = mc.getWindow().getWidth() / 2.0 / info.scale;
			double halfH = mc.getWindow().getHeight() / 2.0 / info.scale;
			double x = info.renderPos.x;
			double z = info.renderPos.z;
			seedMap.index.requestView("xaero_worldmap", dim, x - halfW, z - halfH, x + halfW, z + halfH);
			List<FoundStructure> list = new ArrayList<>();
			for (FoundStructure s : seedMap.index.query(dim, x - halfW - 32, z - halfH - 32, x + halfW + 32, z + halfH + 32)) {
				if (!seedMap.isHidden(s)) {
					list.add(s);
				}
			}
			context.elements = list;
			context.mapScale = info.scale;
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
			pose.popPose();
			return false;
		}

		@Override
		public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
			SeedMapConfig config = SeedMapConfig.get();
			return location == ElementRenderLocation.WORLD_MAP && config.enabled && config.showOnWorldMap;
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
			Long seed = SlimeChunks.seedFor(SeedMap.get(), info.mapDimension);
			if (shadow || seed == null || info.scale < 0.15) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			double halfW = mc.getWindow().getWidth() / 2.0 / info.scale;
			double halfH = mc.getWindow().getHeight() / 2.0 / info.scale;
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
			pose.popPose();
			return false;
		}

		@Override
		public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
			return location == ElementRenderLocation.WORLD_MAP && SeedMapConfig.get().showSlimeChunks && SeedMapConfig.get().enabled;
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

	static final class Provider extends ElementRenderProvider<FoundStructure, Context> {
		private @Nullable Iterator<FoundStructure> iterator;

		@Override
		public void begin(ElementRenderLocation location, Context context) {
			iterator = context.elements.iterator();
		}

		@Override
		public boolean hasNext(ElementRenderLocation location, Context context) {
			return iterator != null && iterator.hasNext();
		}

		@Override
		public FoundStructure getNext(ElementRenderLocation location, Context context) {
			return iterator.next();
		}

		@Override
		public void end(ElementRenderLocation location, Context context) {
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
