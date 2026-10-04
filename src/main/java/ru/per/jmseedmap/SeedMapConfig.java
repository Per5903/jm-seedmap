package ru.per.jmseedmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Persistent settings: manually entered seeds and world presets per multiplayer server,
 * display options and per-structure visibility toggles.
 */
public final class SeedMapConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("jm_seedmap");
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("jm_seedmap.json");

	public enum VisitedMode { SHOW, DIM, HIDE }

	/** The structure layer as a whole (toggle key). */
	public boolean enabled = true;
	public boolean showOnMinimap = true;
	public boolean showOnWorldMap = true;
	public boolean showLabels = true;
	/** Pinned structures (waypoints made from the map) are shown as waypoints. */
	public boolean showPins = true;
	public VisitedMode visitedMode = VisitedMode.DIM;
	/** Slime chunk overlay (Overworld only). */
	public boolean showSlimeChunks = false;
	/** Re-check the seed in the background now and then (otherwise only on demand). */
	public boolean seedCheckAuto = false;
	/** Horizontal distance from a structure's marker at which it counts as visited. */
	public int visitRadius = 48;
	/** Remove the navigation target once the player gets this close (0 = never). */
	public int arrivalRadius = 24;
	/** Server address -> seed. */
	public Map<String, Long> seeds = new LinkedHashMap<>();
	/** Server address -> world preset id (minecraft:normal, minecraft:large_biomes, minecraft:amplified). */
	public Map<String, String> presets = new LinkedHashMap<>();
	/** Structure id -> shown on map. Missing entries use the style default. */
	public Map<String, Boolean> structures = new LinkedHashMap<>();

	private static SeedMapConfig instance = new SeedMapConfig();

	public static SeedMapConfig get() {
		return instance;
	}

	public static void load() {
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				SeedMapConfig loaded = GSON.fromJson(reader, SeedMapConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (Exception e) {
				SeedMapClient.LOGGER.warn("Failed to read {}, using defaults", FILE, e);
			}
		}
		instance.fixNulls();
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			SeedMapClient.LOGGER.warn("Failed to write {}", FILE, e);
		}
	}

	private void fixNulls() {
		if (seeds == null) seeds = new LinkedHashMap<>();
		if (presets == null) presets = new LinkedHashMap<>();
		if (structures == null) structures = new LinkedHashMap<>();
		if (visitedMode == null) visitedMode = VisitedMode.DIM;
	}
}
