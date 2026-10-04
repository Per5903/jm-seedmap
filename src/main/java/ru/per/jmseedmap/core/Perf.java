package ru.per.jmseedmap.core;

import ru.per.jmseedmap.SeedMapConfig;

/**
 * Limits that keep the mod light on weak computers: how many threads compute in the background, how far around
 * the view, how many markers are drawn per frame and how fine the biome layer is.
 */
public enum Perf {
	LOW(1, 6, 8, 192, 400, 16, 4),
	NORMAL(Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 3), 10, 32, 320, 1200, 8, 7),
	HIGH(Math.clamp(Runtime.getRuntime().availableProcessors() - 2, 2, 6), 14, 64, 384, 3000, 4, 10);

	/** Background threads for structure and biome tiles. */
	public final int threads;
	/** Tiles further than this from a view center are not computed. */
	public final int tileRadius;
	public final int maxPending;
	/** Area kept computed around the player, blocks. */
	public final int playerRadius;
	/** Markers drawn per frame on a zoomed-out world map. */
	public final int maxMarkers;
	/** Blocks per pixel of the biome layer. */
	public final int biomeCell;
	/** Biome tiles drawn around the map center. */
	public final int biomeRadius;

	Perf(int threads, int tileRadius, int maxPending, int playerRadius, int maxMarkers, int biomeCell, int biomeRadius) {
		this.threads = threads;
		this.tileRadius = tileRadius;
		this.maxPending = maxPending;
		this.playerRadius = playerRadius;
		this.maxMarkers = maxMarkers;
		this.biomeCell = biomeCell;
		this.biomeRadius = biomeRadius;
	}

	public static Perf current() {
		return switch (SeedMapConfig.get().performance) {
			case LOW -> LOW;
			case NORMAL -> NORMAL;
			case HIGH -> HIGH;
			case AUTO -> auto();
		};
	}

	/** Few cores or a small heap (typical for weak laptops and 2-4 GB launcher defaults) mean LOW. */
	public static Perf auto() {
		int cores = Runtime.getRuntime().availableProcessors();
		long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
		if (cores <= 4 || heapMb < 3000) {
			return LOW;
		}
		return NORMAL;
	}
}
