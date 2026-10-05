package ru.per.jmseedmap.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.StructureStyles;
import ru.per.jmseedmap.core.WorldData;
import ru.per.jmseedmap.gen.FoundStructure;

/**
 * The nearest structures on screen without opening a map: type badge, name, distance and an arrow that turns with
 * the player. Only types that are shown on the map are listed; the navigation target is always on top.
 */
public final class NearbyHud implements HudElement {
	private static final int ICON = 12;
	private static final int ROW = 13;

	private record Entry(Identifier icon, String name, double x, double z, boolean dimmed, boolean target) {
	}

	private List<Entry> entries = List.of();

	/** Every few ticks: which structures to list. */
	public void update(SeedMap seedMap, Minecraft mc) {
		SeedMapConfig config = SeedMapConfig.get();
		LocalPlayer player = mc.player;
		if (!config.hudEnabled || player == null || mc.level == null) {
			entries = List.of();
			return;
		}
		ResourceKey<Level> dim = mc.level.dimension();
		double r = config.hudRadius;
		double px = player.getX();
		double pz = player.getZ();
		List<Entry> out = new ArrayList<>();
		WorldData.Pin target = seedMap.world() == null ? null : seedMap.world().target();
		if (target != null && target.dimensionKey().equals(dim)) {
			String id = target.structure().startsWith("biome:") ? "minecraft:biome" : target.structure();
			out.add(new Entry(StructureStyles.icon(id), target.name(), target.x() + 0.5, target.z() + 0.5, false, true));
		}
		if (config.enabled) {
			seedMap.index.requestView("hud", dim, px - r, pz - r, px + r, pz + r);
			List<FoundStructure> found = new ArrayList<>();
			for (FoundStructure s : seedMap.index.query(dim, px - r, pz - r, px + r, pz + r)) {
				if (!seedMap.isHidden(s) && s.distanceSqr(px, pz) <= r * r) {
					found.add(s);
				}
			}
			found.sort(Comparator.comparingDouble(s -> s.distanceSqr(px, pz)));
			for (FoundStructure s : found.subList(0, Math.min(found.size(), config.hudCount))) {
				boolean dimmed = seedMap.isDimmed(s);
				out.add(new Entry(StructureStyles.icon(s.displayId(), dimmed), StructureStyles.displayName(s.displayId()),
					s.pos().getX() + 0.5, s.pos().getZ() + 0.5, dimmed, false));
			}
		}
		entries = out;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		List<Entry> list = entries;
		LocalPlayer player = mc.player;
		if (list.isEmpty() || player == null || mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		Font font = mc.font;
		float partial = delta.getGameTimeDeltaPartialTick(true);
		double px = net.minecraft.util.Mth.lerp(partial, player.xo, player.getX());
		double pz = net.minecraft.util.Mth.lerp(partial, player.zo, player.getZ());
		float yaw = player.getViewYRot(partial);

		List<String> names = new ArrayList<>();
		List<String> distances = new ArrayList<>();
		int nameWidth = 0;
		int distWidth = 0;
		for (Entry e : list) {
			String name = font.plainSubstrByWidth(e.name(), 110);
			if (name.length() < e.name().length()) {
				name = font.plainSubstrByWidth(e.name(), 104) + "…";
			}
			double dx = e.x() - px;
			double dz = e.z() - pz;
			String dist = Component.translatable("jm_seedmap.hud.distance", Math.round(Math.sqrt(dx * dx + dz * dz))).getString();
			names.add(name);
			distances.add(dist);
			nameWidth = Math.max(nameWidth, font.width(name));
			distWidth = Math.max(distWidth, font.width(dist));
		}
		int width = 2 + ICON + 3 + nameWidth + 6 + distWidth + 3 + 9 + 2;
		int height = list.size() * ROW + 2;
		int sw = graphics.guiWidth();
		int sh = graphics.guiHeight();
		SeedMapConfig.HudCorner corner = SeedMapConfig.get().hudCorner;
		int x = switch (corner) {
			case TOP_LEFT, MIDDLE_LEFT -> 4;
			case TOP_RIGHT, MIDDLE_RIGHT -> sw - width - 4;
		};
		int y = switch (corner) {
			case TOP_LEFT, TOP_RIGHT -> 4;
			case MIDDLE_LEFT, MIDDLE_RIGHT -> (sh - height) / 2;
		};
		graphics.fill(x, y, x + width, y + height, 0x80000000);
		int size = StructureStyles.ICON_SIZE;
		for (int i = 0; i < list.size(); i++) {
			Entry e = list.get(i);
			int rowY = y + 1 + i * ROW;
			graphics.blit(RenderPipelines.GUI_TEXTURED, e.icon(), x + 2, rowY, 0, 0, ICON, ICON, size, size, size, size);
			int color = e.target() ? 0xFF55FFFF : e.dimmed() ? 0xFFAAAAAA : 0xFFFFFFFF;
			int textY = rowY + 2;
			graphics.text(font, names.get(i), x + 2 + ICON + 3, textY, color);
			String dist = distances.get(i);
			int arrowX = x + width - 2 - 9;
			graphics.text(font, dist, arrowX - 3 - font.width(dist), textY, 0xFFDDDDDD);
			// Bearing of the structure relative to where the player looks: 0 = straight ahead, clockwise.
			double bearing = Math.toDegrees(Math.atan2(e.x() - px, -(e.z() - pz)));
			double relative = Math.toRadians(bearing - (yaw + 180.0));
			graphics.pose().pushMatrix();
			graphics.pose().translate(arrowX + 4.5f, textY + 4f);
			graphics.pose().rotate((float) relative);
			graphics.text(font, "↑", -font.width("↑") / 2, -4, e.target() ? 0xFF55FFFF : 0xFFFFDD55);
			graphics.pose().popMatrix();
		}
	}
}
