package ru.per.jmseedmap.core;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.gen.GenContext;
import ru.per.jmseedmap.gen.GenContextProvider;

/**
 * Slime chunks depend only on the seed (same formula as {@code Slime#checkSlimeSpawnRules}) and only exist
 * in the Overworld.
 */
public final class SlimeChunks {
	private SlimeChunks() {
	}

	public static boolean isSlimeChunk(long seed, int chunkX, int chunkZ) {
		return WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, seed, 987234911L).nextInt(10) == 0;
	}

	/** Seed to draw slime chunks with on a map of {@code dimension}, or null if they should not be drawn there. */
	public static @Nullable Long seedFor(SeedMap seedMap, ResourceKey<Level> dimension) {
		SeedMapConfig config = SeedMapConfig.get();
		if (!config.enabled || !config.showSlimeChunks) {
			return null;
		}
		if (GenContextProvider.resolveStem(dimension, Minecraft.getInstance().level) != LevelStem.OVERWORLD) {
			return null;
		}
		GenContext ctx = seedMap.index.context(dimension);
		return ctx == null ? null : ctx.seed();
	}

	/** Bit i*32+j set = chunk (tile*32+i, tile*32+j) is a slime chunk; used to draw a 512x512 tile at once. */
	public static long[] tileMask(long seed, int tileX, int tileZ) {
		long[] rows = new long[32];
		for (int x = 0; x < 32; x++) {
			long row = 0;
			for (int z = 0; z < 32; z++) {
				if (isSlimeChunk(seed, tileX * 32 + x, tileZ * 32 + z)) {
					row |= 1L << z;
				}
			}
			rows[x] = row;
		}
		return rows;
	}
}
