package ru.per.jmseedmap.map;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.core.BiomeLayer;

/**
 * GPU textures for biome tiles, reused between frames. A tile's texture is rewritten only when the tile,
 * its "unexplored" mask or the opacity changes. Render thread only.
 */
public final class BiomeTextures {
	/** All 64 blocks of 64x64 drawn. */
	public static final long FULL_MASK = -1L;
	private static final long UNUSED_MS = 10_000;

	private static final class Entry {
		final Identifier id;
		final DynamicTexture texture;
		BiomeLayer.Tile tile;
		long mask;
		int opacity;
		long lastUsed;

		Entry(Identifier id, DynamicTexture texture) {
			this.id = id;
			this.texture = texture;
		}
	}

	private final String prefix;
	private final Map<String, Entry> entries = new HashMap<>();
	private int created;
	private long lastCleanup;

	public BiomeTextures(String prefix) {
		this.prefix = prefix;
	}

	/**
	 * Texture for this tile: biome colors at {@code opacity} percent where the matching bit of {@code mask} is set
	 * (bit {@code z * 8 + x} for the 64x64 block square x, z of the tile), transparent elsewhere.
	 */
	public Identifier texture(BiomeLayer.Tile tile, long mask, int opacity) {
		String key = tile.tileX() + "," + tile.tileZ() + "@" + tile.size();
		Entry entry = entries.get(key);
		if (entry == null) {
			Identifier id = Identifier.fromNamespaceAndPath(SeedMapClient.MOD_ID, prefix + "/" + (created++));
			DynamicTexture texture = new DynamicTexture(id::toString, tile.size(), tile.size(), true);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			entry = new Entry(id, texture);
			entries.put(key, entry);
		}
		if (entry.tile != tile || entry.mask != mask || entry.opacity != opacity) {
			fill(entry.texture.getPixels(), tile, mask, opacity);
			entry.texture.upload();
			entry.tile = tile;
			entry.mask = mask;
			entry.opacity = opacity;
		}
		entry.lastUsed = Util.getMillis();
		return entry.id;
	}

	private static void fill(NativeImage image, BiomeLayer.Tile tile, long mask, int opacity) {
		int size = tile.size();
		int alpha = Math.clamp(opacity * 255 / 100, 0, 255) << 24;
		int cellsPerBlock64 = Math.max(1, size / 8);
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int bit = (z / cellsPerBlock64) * 8 + (x / cellsPerBlock64);
				int color = (mask >>> bit & 1L) != 0 ? alpha | (tile.colors()[z * size + x] & 0xFFFFFF) : 0;
				image.setPixel(x, z, color);
			}
		}
	}

	/** Releases textures that were not drawn for a while. */
	public void cleanup() {
		long now = Util.getMillis();
		if (now - lastCleanup < 2_000) {
			return;
		}
		lastCleanup = now;
		Iterator<Entry> it = entries.values().iterator();
		while (it.hasNext()) {
			Entry entry = it.next();
			if (now - entry.lastUsed > UNUSED_MS) {
				Minecraft.getInstance().getTextureManager().release(entry.id);
				it.remove();
			}
		}
	}

	public void clear() {
		for (Entry entry : entries.values()) {
			Minecraft.getInstance().getTextureManager().release(entry.id);
		}
		entries.clear();
	}
}
