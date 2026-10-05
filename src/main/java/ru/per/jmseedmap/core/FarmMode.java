package ru.per.jmseedmap.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.gen.FoundStructure;

/**
 * Farming route: pins on the {@code count} nearest unvisited structures of one type, the nearest one is the target.
 * Reaching a structure marks it visited, and the pins move on to the next nearest ones of the same type, so the
 * player can go from village to village (or portal to portal) without searching again.
 */
public final class FarmMode {
	/** How far the search goes, in 512-block tiles. */
	private static final int MAX_TILES = 40;

	private final SeedMap seedMap;
	private StructureStyles.@Nullable Group group;
	private @Nullable ResourceKey<Level> dimension;
	private int count = 1;
	/** Keys of the pins this mode placed, so they can be moved or removed without touching the player's own pins. */
	private final Set<String> pinned = new HashSet<>();
	private boolean searching;
	private int visitedCount;

	FarmMode(SeedMap seedMap) {
		this.seedMap = seedMap;
	}

	public boolean active() {
		return group != null;
	}

	public StructureStyles.@Nullable Group group() {
		return group;
	}

	public int count() {
		return count;
	}

	public int visitedCount() {
		return visitedCount;
	}

	public void start(StructureStyles.Group group, int count) {
		stop(false);
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		this.group = group;
		this.count = Math.clamp(count, 1, 10);
		this.dimension = mc.level.dimension();
		this.visitedCount = 0;
		message(Component.translatable("jm_seedmap.farm.started", group.displayName(), this.count).withStyle(ChatFormatting.AQUA));
		refresh();
	}

	public void stop(boolean announce) {
		if (group == null) {
			return;
		}
		WorldData world = seedMap.world();
		if (world != null) {
			for (String key : pinned) {
				world.removePin(key);
			}
			WorldData.Pin target = world.target();
			if (target != null && pinned.contains(target.key())) {
				world.clearTarget();
			}
		}
		pinned.clear();
		seedMap.pinsChanged();
		if (announce) {
			message(Component.translatable("jm_seedmap.farm.stopped", group.displayName(), visitedCount));
		}
		group = null;
		dimension = null;
	}

	/** Every few ticks: a farmed structure was reached (or marked visited) -> move on. */
	void tick(Minecraft mc) {
		if (group == null || searching || mc.level == null || !mc.level.dimension().equals(dimension)) {
			return;
		}
		WorldData world = seedMap.world();
		if (world == null) {
			return;
		}
		boolean moved = world.target() == null;
		for (String key : pinned) {
			if (world.isVisited(key)) {
				moved = true;
				visitedCount++;
			}
		}
		if (moved) {
			refresh();
		}
	}

	private void refresh() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		StructureStyles.Group farmed = group;
		if (player == null || farmed == null || dimension == null) {
			return;
		}
		searching = true;
		seedMap.index.nearestN(dimension, player.getX(), player.getZ(), farmed::contains, s -> !seedMap.isVisited(s), count, MAX_TILES)
			.whenComplete((found, error) -> mc.execute(() -> {
				searching = false;
				if (group != farmed) {
					return;
				}
				apply(found == null ? List.of() : found);
			}));
	}

	private void apply(List<FoundStructure> found) {
		WorldData world = seedMap.world();
		LocalPlayer player = Minecraft.getInstance().player;
		if (world == null || player == null || group == null) {
			return;
		}
		for (String key : pinned) {
			world.removePin(key);
		}
		pinned.clear();
		if (found.isEmpty()) {
			message(Component.translatable("jm_seedmap.farm.none", group.displayName(), MAX_TILES * 512).withStyle(ChatFormatting.YELLOW));
			stop(true);
			return;
		}
		for (FoundStructure s : found) {
			seedMap.setPinned(s, true);
			pinned.add(s.key());
		}
		FoundStructure next = found.get(0);
		seedMap.setTarget(next);
		double dx = next.pos().getX() + 0.5 - player.getX();
		double dz = next.pos().getZ() + 0.5 - player.getZ();
		player.sendOverlayMessage(Component.translatable("jm_seedmap.farm.next", StructureStyles.displayName(next.displayId()),
			Math.round(Math.sqrt(dx * dx + dz * dz)), Component.translatable("jm_seedmap.dir." + SeedMap.direction(dx, dz)))
			.withStyle(ChatFormatting.AQUA));
	}

	private static void message(Component text) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.sendSystemMessage(Component.literal("[SeedMap] ").append(text));
		}
	}
}
