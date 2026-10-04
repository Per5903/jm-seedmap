package ru.per.jmseedmap.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;

/**
 * Per-world state that must survive restarts: visited structures and pinned waypoints.
 * Stored in {@code config/jm_seedmap/worlds/<world>.json}, separate from the map mods' own waypoint files,
 * so pins can be shown, hidden or wiped without touching the player's waypoints.
 */
public final class WorldData {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * A structure turned into a waypoint.
	 *
	 * @param target the single temporary "go here" waypoint; removed on arrival
	 */
	public record Pin(String key, String dimension, int x, int y, int z, String structure, String name, int color, boolean target) {
		public ResourceKey<Level> dimensionKey() {
			return ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
		}

		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	private Set<String> visited = new LinkedHashSet<>();
	private List<Pin> pins = new ArrayList<>();

	private transient Path file;
	private transient boolean dirty;

	public static WorldData load(String worldKey) {
		Path file = SeedMapConfig.DIR.resolve("worlds").resolve(worldKey.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
		WorldData data = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				data = GSON.fromJson(reader, WorldData.class);
			} catch (Exception e) {
				SeedMapClient.LOGGER.warn("Failed to read {}", file, e);
			}
		}
		if (data == null) {
			data = new WorldData();
		}
		if (data.visited == null) data.visited = new LinkedHashSet<>();
		if (data.pins == null) data.pins = new ArrayList<>();
		data.file = file;
		return data;
	}

	public void saveIfDirty() {
		if (!dirty || file == null) {
			return;
		}
		dirty = false;
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (Exception e) {
			SeedMapClient.LOGGER.warn("Failed to write {}", file, e);
		}
	}

	public boolean isVisited(String structureKey) {
		return visited.contains(structureKey);
	}

	public boolean setVisited(String structureKey, boolean value) {
		boolean changed = value ? visited.add(structureKey) : visited.remove(structureKey);
		dirty |= changed;
		return changed;
	}

	public int clearVisited() {
		int n = visited.size();
		visited.clear();
		dirty |= n > 0;
		return n;
	}

	public List<Pin> pins() {
		return pins;
	}

	public @Nullable Pin pin(String structureKey) {
		for (Pin pin : pins) {
			if (pin.key().equals(structureKey) && !pin.target()) {
				return pin;
			}
		}
		return null;
	}

	public @Nullable Pin target() {
		for (Pin pin : pins) {
			if (pin.target()) {
				return pin;
			}
		}
		return null;
	}

	public void addPin(Pin pin) {
		pins.removeIf(p -> p.target() == pin.target() && (pin.target() || p.key().equals(pin.key())));
		pins.add(pin);
		dirty = true;
	}

	public boolean removePin(String structureKey) {
		boolean changed = pins.removeIf(p -> !p.target() && p.key().equals(structureKey));
		dirty |= changed;
		return changed;
	}

	/** Replaces one pin (exact match) with another, keeping its place in the list. */
	public boolean replacePin(Pin old, Pin replacement) {
		int i = pins.indexOf(old);
		if (i < 0) {
			return false;
		}
		pins.set(i, replacement);
		dirty = true;
		return true;
	}

	/** Removes exactly this pin (a normal pin or the target). */
	public boolean removeExact(Pin pin) {
		boolean changed = pins.remove(pin);
		dirty |= changed;
		return changed;
	}

	public boolean clearTarget() {
		boolean changed = pins.removeIf(Pin::target);
		dirty |= changed;
		return changed;
	}

	public int clearPins() {
		int n = pins.size();
		pins.clear();
		dirty |= n > 0;
		return n;
	}
}
