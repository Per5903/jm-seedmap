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
		int highlight;
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
		int highlight = BiomeLayer.highlightVersion();
		if (entry.tile != tile || entry.mask != mask || entry.opacity != opacity || entry.highlight != highlight) {
			fill(entry.texture.getPixels(), tile, mask, opacity);
			entry.texture.upload();
			entry.tile = tile;
			entry.mask = mask;
			entry.opacity = opacity;
			entry.highlight = highlight;
		}
		entry.lastUsed = Util.getMillis();
		return entry.id;
	}

	private static void fill(NativeImage image, BiomeLayer.Tile tile, long mask, int opacity) {
		int size = tile.size();
		int[] colors = colors(tile, mask, opacity);
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				image.setPixel(x, z, colors[z * size + x]);
			}
		}
	}

	/**
	 * ARGB per cell. Normally: the biome color where {@code mask} allows it. With highlighted biomes (seedmap style):
	 * those stand out in full color everywhere, explored or not, and every other biome is a dark veil where the
	 * mask allows it, so the picked ones are easy to spot.
	 */
	public static int[] colors(BiomeLayer.Tile tile, long mask, int opacity) {
		int size = tile.size();
		int alpha = Math.clamp(opacity * 255 / 100, 0, 255) << 24;
		java.util.Set<net.minecraft.resources.Identifier> highlighted = BiomeLayer.highlighted();
		boolean[] lit = null;
		if (!highlighted.isEmpty()) {
			lit = new boolean[tile.palette().length];
			for (int p = 0; p < lit.length; p++) {
				lit[p] = highlighted.contains(tile.palette()[p]);
			}
		}
		int litAlpha = Math.max(alpha >>> 24, 0xD0) << 24;
		int dimAlpha = (Math.clamp(opacity * 255 / 100, 0, 255) * 3 / 4) << 24;
		int cellsPerBlock64 = Math.max(1, size / 8);
		int[] out = new int[size * size];
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int i = z * size + x;
				int bit = (z / cellsPerBlock64) * 8 + (x / cellsPerBlock64);
				boolean open = (mask >>> bit & 1L) != 0;
				int rgb = tile.colors()[i] & 0xFFFFFF;
				if (lit == null) {
					out[i] = open ? alpha | rgb : 0;
				} else if (lit[tile.cells()[i]]) {
					out[i] = litAlpha | bright(rgb);
				} else {
					out[i] = open ? dimAlpha | 0x101010 : 0;
				}
			}
		}
		return out;
	}

	/** The biome's hue, but bright enough to stand out even for dark biomes like the deep ocean. */
	private static int bright(int rgb) {
		float[] hsb = java.awt.Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
		return java.awt.Color.HSBtoRGB(hsb[0], hsb[1] < 0.15f ? hsb[1] : Math.max(hsb[1], 0.7f), Math.max(hsb[2], 0.95f)) & 0xFFFFFF;
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
