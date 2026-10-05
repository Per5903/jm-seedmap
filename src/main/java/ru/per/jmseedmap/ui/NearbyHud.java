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
	/** Farm mode: a title line above the rows ("Farm: villages · visited 3"), otherwise empty. */
	private Component header = Component.empty();
	/** Shown instead of the list when it is empty, so it is clear the HUD is on (and why there is nothing). */
	private Component emptyReason = Component.empty();

	/** Every few ticks: which structures to list. */
	public void update(SeedMap seedMap, Minecraft mc) {
		SeedMapConfig config = SeedMapConfig.get();
		LocalPlayer player = mc.player;
		header = Component.empty();
		if (seedMap.farm.active() && player != null && mc.level != null) {
			updateFarm(seedMap, player);
			return;
		}
		if (!config.hudEnabled || player == null || mc.level == null) {
			entries = List.of();
			emptyReason = Component.empty();
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
		if (out.isEmpty()) {
			var status = seedMap.index.status(dim);
			String key = !config.enabled ? "jm_seedmap.hud.empty_off"
				: status == ru.per.jmseedmap.gen.GenContextProvider.Status.NO_SEED ? "jm_seedmap.hud.empty_no_seed"
				: status == ru.per.jmseedmap.gen.GenContextProvider.Status.LOADING || seedMap.index.pendingTiles() > 0 ? "jm_seedmap.hud.empty_loading"
				: "jm_seedmap.hud.empty";
			emptyReason = Component.translatable(key, config.hudRadius);
		}
	}

	/** Farm mode: the route's target and next pins, nearest first, under a title with the progress. */
	private void updateFarm(SeedMap seedMap, LocalPlayer player) {
		var farm = seedMap.farm;
		header = Component.translatable("jm_seedmap.hud.farm", farm.group() == null ? "" : farm.group().displayName(), farm.visitedCount());
		List<Entry> out = new ArrayList<>();
		WorldData.Pin target = seedMap.world() == null ? null : seedMap.world().target();
		if (target != null && target.dimensionKey().equals(player.level().dimension())) {
			out.add(new Entry(StructureStyles.icon(target.structure()), target.name(), target.x() + 0.5, target.z() + 0.5, false, true));
		}
		List<WorldData.Pin> pins = new ArrayList<>(farm.pins());
		pins.sort(Comparator.comparingDouble(p -> {
			double dx = p.x() + 0.5 - player.getX();
			double dz = p.z() + 0.5 - player.getZ();
			return dx * dx + dz * dz;
		}));
		for (WorldData.Pin pin : pins) {
			if (target != null && pin.key().equals(target.key())) {
				continue;
			}
			out.add(new Entry(StructureStyles.icon(pin.structure()), pin.name(), pin.x() + 0.5, pin.z() + 0.5, false, false));
		}
		entries = out;
		emptyReason = Component.translatable(farm.isSearching() ? "jm_seedmap.hud.farm_searching" : "jm_seedmap.hud.empty_loading");
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		List<Entry> list = entries;
		LocalPlayer player = mc.player;
		// Only the real F3 screen hides the list: debug lines pinned to the screen (26.x) must not.
		boolean farm = !header.getString().isEmpty();
		if ((!SeedMapConfig.get().hudEnabled && !farm) || player == null || mc.debugEntries.isOverlayVisible()) {
			return;
		}
		Font font = mc.font;
		if (list.isEmpty()) {
			Component text = farm ? header.copy().append(" · ").append(emptyReason) : Component.literal("SeedMap: ").append(emptyReason);
			int w = font.width(text) + 6;
			int x = SeedMapConfig.get().hudCorner.name().endsWith("LEFT") ? 4 : graphics.guiWidth() - w - 4;
			int y = SeedMapConfig.get().hudCorner.name().startsWith("TOP") ? 4 : graphics.guiHeight() / 2 - 6;
			graphics.fill(x, y, x + w, y + 12, 0x80000000);
			graphics.text(font, text, x + 3, y + 2, 0xFFAAAAAA);
			return;
		}
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
		int titleHeight = farm ? 12 : 0;
		int width = Math.max(2 + ICON + 3 + nameWidth + 6 + distWidth + 3 + 9 + 2, farm ? font.width(header) + 8 : 0);
		int height = list.size() * ROW + 2 + titleHeight;
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
		if (farm) {
			graphics.fill(x, y, x + width, y + titleHeight, 0xA0205060);
			graphics.text(font, header, x + 4, y + 2, 0xFF7FE7FF);
		}
		int size = StructureStyles.ICON_SIZE;
		for (int i = 0; i < list.size(); i++) {
			Entry e = list.get(i);
			int rowY = y + titleHeight + 1 + i * ROW;
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
