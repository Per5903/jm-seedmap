package ru.per.jmseedmap.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.LevelStem;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.gen.GenContext;
import ru.per.jmseedmap.gen.GenContextProvider;
import ru.per.jmseedmap.gen.StructureFinder;

/**
 * Surface biomes from the seed, as 512x512 block tiles of colored cells (chunkbase style). Only the noise biome
 * source is asked, no terrain is generated, so a tile costs a few thousand climate samples.
 * <p>
 * In the Overworld the biome is sampled high above the terrain: there the "depth" climate parameter is negative,
 * which rules out cave biomes and leaves exactly the surface biome of each column.
 */
public final class BiomeLayer {
	private static final int MAX_TILES = 600;
	private static final int MAX_PENDING = 6;

	/**
	 * One computed tile.
	 *
	 * @param size    cells per edge
	 * @param colors  opaque ARGB per cell, row-major by z then x ({@code colors[z * size + x]})
	 * @param cells   palette index per cell
	 */
	public record Tile(int tileX, int tileZ, int cell, int size, int[] colors, short[] cells, Identifier[] palette) {
		/** Biome at a block inside this tile. */
		public Identifier biomeAt(int blockX, int blockZ) {
			int x = Math.clamp((blockX - tileX * StructureFinder.TILE_BLOCKS) / cell, 0, size - 1);
			int z = Math.clamp((blockZ - tileZ * StructureFinder.TILE_BLOCKS) / cell, 0, size - 1);
			return palette[cells[z * size + x]];
		}
	}

	private record Key(String contextId, int tileX, int tileZ, int cell) {
	}

	private final StructureIndex index;
	private final Map<Key, Tile> tiles = java.util.Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, Tile> eldest) {
			return size() > MAX_TILES;
		}
	});
	private final Set<Key> pending = ConcurrentHashMap.newKeySet();
	private final AtomicInteger version = new AtomicInteger();
	private volatile boolean errorLogged;
	/** Disk cache: compact encoded tiles of the contexts in use, read from and written to one file per context. */
	private final Map<Key, byte[]> stored = new ConcurrentHashMap<>();
	private final Set<String> storeLoaded = ConcurrentHashMap.newKeySet();
	private final Set<String> storeDirty = ConcurrentHashMap.newKeySet();
	private final java.nio.file.Path cacheDir = Minecraft.getInstance().gameDirectory.toPath().resolve("jm_seedmap").resolve("cache").resolve("biomes");
	private long lastSave = net.minecraft.util.Util.getMillis();
	private static final int MAX_STORED = 30_000;
	private static final int FORMAT = 1;

	BiomeLayer(StructureIndex index) {
		this.index = index;
	}

	/** Increases whenever a tile finishes or the layer is reset. */
	public int version() {
		return version.get();
	}

	/**
	 * The tile if it is ready; otherwise schedules it and returns a coarser or older version if one exists, else null.
	 * Render thread.
	 */
	public @Nullable Tile get(ResourceKey<Level> dimension, int tileX, int tileZ) {
		GenContext ctx = index.context(dimension);
		if (ctx == null) {
			return null;
		}
		int cell = Perf.current().biomeCell;
		Key key = new Key(ctx.id(), tileX, tileZ, cell);
		Tile tile = tiles.get(key);
		if (tile != null) {
			return tile;
		}
		loadStore(ctx.id());
		byte[] encoded = stored.get(key);
		if (encoded != null) {
			tile = decode(tileX, tileZ, cell, encoded);
			if (tile != null) {
				tiles.put(key, tile);
				return tile;
			}
		}
		if (pending.size() < MAX_PENDING && pending.add(key)) {
			int sampleY = sampleY(ctx, dimension);
			index.executeBackground(() -> {
				try {
					Tile computed = compute(ctx, tileX, tileZ, cell, sampleY);
					tiles.put(key, computed);
					if (ru.per.jmseedmap.SeedMapConfig.get().diskCache && stored.size() < MAX_STORED) {
						stored.put(key, encode(computed));
						storeDirty.add(ctx.id());
					}
					version.incrementAndGet();
				} catch (Throwable t) {
					if (!errorLogged) {
						errorLogged = true;
						SeedMapClient.LOGGER.error("Biome layer failed for tile {},{}", tileX, tileZ, t);
					}
				} finally {
					pending.remove(key);
				}
			});
		}
		// Meanwhile show the same tile at another resolution, if there is one.
		for (int other : new int[]{4, 8, 16}) {
			if (other != cell) {
				Tile fallback = tiles.get(new Key(ctx.id(), tileX, tileZ, other));
				if (fallback != null) {
					return fallback;
				}
			}
		}
		return null;
	}

	private static int sampleY(GenContext ctx, ResourceKey<Level> dimension) {
		ResourceKey<LevelStem> stem = GenContextProvider.resolveStem(dimension, Minecraft.getInstance().level);
		if (stem == LevelStem.OVERWORLD) {
			return ctx.heightAccessor().getMaxY();
		}
		// Nether and End biomes do not depend on height; modded dimensions: sea level is the best guess.
		return 64;
	}

	public static Tile compute(GenContext ctx, int tileX, int tileZ, int cell, int sampleY) {
		int size = StructureFinder.TILE_BLOCKS / cell;
		BiomeSource source = ctx.generator().getBiomeSource();
		Climate.Sampler sampler = ctx.randomState().sampler();
		int qy = QuartPos.fromBlock(sampleY);
		int[] colors = new int[size * size];
		short[] cells = new short[size * size];
		Map<Holder<Biome>, Short> paletteIndex = new HashMap<>();
		List<Identifier> palette = new ArrayList<>();
		int baseX = tileX * StructureFinder.TILE_BLOCKS + cell / 2;
		int baseZ = tileZ * StructureFinder.TILE_BLOCKS + cell / 2;
		Holder<Biome> last = null;
		short lastIndex = 0;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				Holder<Biome> biome = source.getNoiseBiome(QuartPos.fromBlock(baseX + x * cell), qy, QuartPos.fromBlock(baseZ + z * cell), sampler);
				if (biome != last) {
					Short idx = paletteIndex.get(biome);
					if (idx == null) {
						idx = (short) palette.size();
						paletteIndex.put(biome, idx);
						palette.add(biome.unwrapKey().map(ResourceKey::identifier).orElse(Identifier.withDefaultNamespace("plains")));
					}
					last = biome;
					lastIndex = idx;
				}
				cells[z * size + x] = lastIndex;
			}
		}
		int[] paletteColors = new int[palette.size()];
		for (int i = 0; i < paletteColors.length; i++) {
			paletteColors[i] = 0xFF000000 | color(palette.get(i));
		}
		for (int i = 0; i < cells.length; i++) {
			colors[i] = paletteColors[cells[i]];
		}
		return new Tile(tileX, tileZ, cell, size, colors, cells, palette.toArray(Identifier[]::new));
	}

	public void reset() {
		saveDirty();
		tiles.clear();
		stored.clear();
		storeLoaded.clear();
		version.incrementAndGet();
		errorLogged = false;
	}

	/** Every few seconds from the client tick: write new tiles now and then. */
	public void tick() {
		long now = net.minecraft.util.Util.getMillis();
		if (now - lastSave > 30_000) {
			lastSave = now;
			saveDirty();
		}
	}

	// ---- disk cache ----

	private void loadStore(String contextId) {
		if (!ru.per.jmseedmap.SeedMapConfig.get().diskCache || !storeLoaded.add(contextId)) {
			return;
		}
		index.executeIo(() -> {
			java.nio.file.Path file = cacheFile(contextId);
			if (!java.nio.file.Files.isRegularFile(file)) {
				return;
			}
			int read = 0;
			try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(file))))) {
				if (in.readInt() != FORMAT || !in.readUTF().equals(net.minecraft.SharedConstants.getCurrentVersion().name()) || !in.readUTF().equals(contextId)) {
					return;
				}
				int count = in.readInt();
				for (int i = 0; i < count; i++) {
					Key key = new Key(contextId, in.readInt(), in.readInt(), in.readInt());
					byte[] data = new byte[in.readInt()];
					in.readFully(data);
					if (stored.putIfAbsent(key, data) == null) {
						read++;
					}
				}
			} catch (Exception e) {
				SeedMapClient.LOGGER.warn("Ignoring broken biome cache {}", file, e);
			}
			if (read > 0) {
				version.incrementAndGet();
				SeedMapClient.LOGGER.info("Read {} biome tiles from the disk cache", read);
			}
		});
	}

	public void saveDirty() {
		if (storeDirty.isEmpty()) {
			return;
		}
		List<String> dirty = List.copyOf(storeDirty);
		storeDirty.clear();
		for (String contextId : dirty) {
			Map<Key, byte[]> snapshot = new HashMap<>();
			stored.forEach((k, v) -> {
				if (k.contextId().equals(contextId)) {
					snapshot.put(k, v);
				}
			});
			index.executeIo(() -> write(contextId, snapshot));
		}
	}

	private void write(String contextId, Map<Key, byte[]> tiles) {
		java.nio.file.Path file = cacheFile(contextId);
		try {
			java.nio.file.Files.createDirectories(file.getParent());
			java.nio.file.Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(tmp))))) {
				out.writeInt(FORMAT);
				out.writeUTF(net.minecraft.SharedConstants.getCurrentVersion().name());
				out.writeUTF(contextId);
				out.writeInt(tiles.size());
				for (Map.Entry<Key, byte[]> e : tiles.entrySet()) {
					out.writeInt(e.getKey().tileX());
					out.writeInt(e.getKey().tileZ());
					out.writeInt(e.getKey().cell());
					out.writeInt(e.getValue().length);
					out.write(e.getValue());
				}
			}
			java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			SeedMapClient.LOGGER.warn("Failed to write biome cache {}", file, e);
		}
	}

	private java.nio.file.Path cacheFile(String contextId) {
		try {
			byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(contextId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return cacheDir.resolve(java.util.HexFormat.of().formatHex(hash, 0, 12) + ".bin");
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Palette plus run-length encoded cells: biomes come in big patches, so a tile is a few hundred bytes. */
	static byte[] encode(Tile tile) {
		var bytes = new java.io.ByteArrayOutputStream();
		try (var out = new java.io.DataOutputStream(bytes)) {
			out.writeShort(tile.palette().length);
			for (Identifier id : tile.palette()) {
				out.writeUTF(id.toString());
			}
			short[] cells = tile.cells();
			int i = 0;
			while (i < cells.length) {
				int run = 1;
				while (i + run < cells.length && cells[i + run] == cells[i] && run < 65535) {
					run++;
				}
				out.writeShort(run);
				out.writeShort(cells[i]);
				i += run;
			}
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		return bytes.toByteArray();
	}

	static @Nullable Tile decode(int tileX, int tileZ, int cell, byte[] data) {
		int size = StructureFinder.TILE_BLOCKS / cell;
		try (var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data))) {
			Identifier[] palette = new Identifier[in.readUnsignedShort()];
			for (int p = 0; p < palette.length; p++) {
				palette[p] = Identifier.parse(in.readUTF());
			}
			short[] cells = new short[size * size];
			int i = 0;
			while (i < cells.length) {
				int run = in.readUnsignedShort();
				short value = in.readShort();
				java.util.Arrays.fill(cells, i, Math.min(cells.length, i + run), value);
				i += run;
			}
			int[] colors = new int[cells.length];
			int[] paletteColors = new int[palette.length];
			for (int p = 0; p < palette.length; p++) {
				paletteColors[p] = 0xFF000000 | color(palette[p]);
			}
			for (int c = 0; c < cells.length; c++) {
				colors[c] = paletteColors[cells[c]];
			}
			return new Tile(tileX, tileZ, cell, size, colors, cells, palette);
		} catch (Exception e) {
			return null;
		}
	}

	// ---- highlight ----

	private static volatile Set<Identifier> highlighted = Set.of();
	private static volatile int highlightVersion;

	/** Biomes picked in the search to stand out on the map (seedmap style); empty = normal coloring. */
	public static Set<Identifier> highlighted() {
		return highlighted;
	}

	public static int highlightVersion() {
		return highlightVersion;
	}

	public static void loadHighlight() {
		Set<Identifier> set = new java.util.HashSet<>();
		for (String id : ru.per.jmseedmap.SeedMapConfig.get().highlightedBiomes) {
			Identifier parsed = Identifier.tryParse(id);
			if (parsed != null) {
				set.add(parsed);
			}
		}
		highlighted = Set.copyOf(set);
		highlightVersion++;
	}

	public static void setHighlighted(Identifier biome, boolean on) {
		List<String> list = ru.per.jmseedmap.SeedMapConfig.get().highlightedBiomes;
		list.remove(biome.toString());
		if (on) {
			list.add(biome.toString());
		}
		ru.per.jmseedmap.SeedMapConfig.save();
		loadHighlight();
	}

	public static void clearHighlight() {
		ru.per.jmseedmap.SeedMapConfig.get().highlightedBiomes.clear();
		ru.per.jmseedmap.SeedMapConfig.save();
		loadHighlight();
	}

	// ---- colors ----

	private static final Map<String, Integer> COLORS = new HashMap<>();

	static {
		Object[] table = {
			"the_void", 0x000000, "plains", 0x8DB360, "sunflower_plains", 0xB5DB88, "snowy_plains", 0xF0F7F7, "ice_spikes", 0xB4DCDC,
			"desert", 0xFA9418, "swamp", 0x2F5A3A, "mangrove_swamp", 0x2CCC8E, "forest", 0x056621, "flower_forest", 0x2D8E49,
			"birch_forest", 0x307444, "dark_forest", 0x40511A, "pale_garden", 0xA6ADA4, "old_growth_birch_forest", 0x589C6C,
			"old_growth_pine_taiga", 0x596651, "old_growth_spruce_taiga", 0x818E79, "taiga", 0x0B6659, "snowy_taiga", 0x31554A,
			"savanna", 0xBDB25F, "savanna_plateau", 0xA79D64, "windswept_hills", 0x606060, "windswept_gravelly_hills", 0x888888,
			"windswept_forest", 0x5B7352, "windswept_savanna", 0xE5DA87, "jungle", 0x537B09, "sparse_jungle", 0x628B17,
			"bamboo_jungle", 0x768E14, "badlands", 0xD94515, "eroded_badlands", 0xFF6D3D, "wooded_badlands", 0xB09765,
			"meadow", 0x83BB6D, "cherry_grove", 0xFFB7D5, "grove", 0x47726E, "snowy_slopes", 0xC4C4C4, "frozen_peaks", 0xA0B4E0,
			"jagged_peaks", 0xDCDCDC, "stony_peaks", 0x7B8F74, "river", 0x0000FF, "frozen_river", 0xA0A0FF, "beach", 0xFADE55,
			"snowy_beach", 0xFAF0C0, "stony_shore", 0xA2A284, "warm_ocean", 0x0000AC, "lukewarm_ocean", 0x000090,
			"deep_lukewarm_ocean", 0x000040, "ocean", 0x000070, "deep_ocean", 0x000030, "cold_ocean", 0x202070,
			"deep_cold_ocean", 0x202038, "frozen_ocean", 0x7070D6, "deep_frozen_ocean", 0x404090, "mushroom_fields", 0xFF00FF,
			"dripstone_caves", 0x7B6B5B, "lush_caves", 0x2E8B57, "deep_dark", 0x0A1E28, "sulfur_caves", 0xC8C832,
			"nether_wastes", 0xBF3B3B, "warped_forest", 0x49907B, "crimson_forest", 0xDD0808, "soul_sand_valley", 0x5E3830,
			"basalt_deltas", 0x403636, "the_end", 0x8080FF, "end_highlands", 0xB5B536, "end_midlands", 0xC9C959,
			"small_end_islands", 0x4B4BAB, "end_barrens", 0x7070CC,
		};
		for (int i = 0; i < table.length; i += 2) {
			COLORS.put("minecraft:" + table[i], (Integer) table[i + 1]);
		}
	}

	/** RGB for a biome; modded and data pack biomes get a stable color derived from their id. */
	public static int color(Identifier biome) {
		Integer known = COLORS.get(biome.toString());
		if (known != null) {
			return known;
		}
		int hash = biome.toString().hashCode();
		return java.awt.Color.HSBtoRGB((hash & 0xFFFF) / 65535f, 0.45f, 0.7f) & 0xFFFFFF;
	}
}
