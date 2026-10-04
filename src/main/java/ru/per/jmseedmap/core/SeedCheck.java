package ru.per.jmseedmap.core;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.gen.GenContext;

/**
 * Tells whether the seed in use is the server's real seed, on demand.
 * <ol>
 *   <li>The server sends a SHA-256 hash of its seed (used for biome blending). If it is there, comparing it
 *       with the hash of our seed is an exact yes/no.</li>
 *   <li>Real biomes of loaded chunks are compared with the biomes the seed predicts. This catches a wrong
 *       world type or version and still works when the server hides or fakes the hash.</li>
 * </ol>
 * Nothing is sent anywhere.
 */
public final class SeedCheck {
	public enum Verdict { UNKNOWN, OK, SUSPECT, WRONG }

	private static final int SAMPLES = 256;
	private static final @Nullable Field ZOOM_SEED = zoomSeedField();

	private final Random random = new Random();
	private Verdict verdict = Verdict.UNKNOWN;
	private int percent = -1;
	private int samples;
	private @Nullable Boolean hashMatch;
	private String contextId = "";

	private static @Nullable Field zoomSeedField() {
		try {
			Field field = BiomeManager.class.getDeclaredField("biomeZoomSeed");
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** The hashed seed the server sent, if any (some servers send 0 to hide it). */
	public static OptionalLong serverHashedSeed(@Nullable ClientLevel level) {
		if (level == null || ZOOM_SEED == null) {
			return OptionalLong.empty();
		}
		try {
			long hashed = ZOOM_SEED.getLong(level.getBiomeManager());
			return hashed == 0 ? OptionalLong.empty() : OptionalLong.of(hashed);
		} catch (IllegalAccessException e) {
			return OptionalLong.empty();
		}
	}

	public Verdict verdict() {
		return verdict;
	}

	/**
	 * Runs the check now on the render thread and reports in chat.
	 *
	 * @param quietIfUnchanged only report when the verdict differs from the previous one (automatic mode)
	 */
	public void run(Minecraft mc, StructureIndex index, boolean quietIfUnchanged) {
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) {
			return;
		}
		GenContext ctx = index.context(level.dimension());
		if (ctx == null) {
			if (!quietIfUnchanged) {
				var status = index.status(level.dimension());
				String key = status == null ? "jm_seedmap.check.no_context"
					: switch (status) {
						case LOADING -> "jm_seedmap.check.loading";
						case ERROR -> "jm_seedmap.check.error";
						case UNKNOWN_DIMENSION -> "jm_seedmap.status.unknown_dimension";
						default -> "jm_seedmap.check.no_context";
					};
				player.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.YELLOW));
			}
			return;
		}
		Verdict previous = ctx.id().equals(contextId) ? verdict : Verdict.UNKNOWN;
		contextId = ctx.id();

		OptionalLong hashed = serverHashedSeed(level);
		hashMatch = hashed.isPresent() ? BiomeManager.obfuscateSeed(ctx.seed()) == hashed.getAsLong() : null;
		sampleBiomes(mc, level, ctx);

		if (Boolean.TRUE.equals(hashMatch)) {
			verdict = Verdict.OK;
		} else if (percent < 0) {
			verdict = Boolean.FALSE.equals(hashMatch) ? Verdict.WRONG : Verdict.UNKNOWN;
		} else if (percent >= 85) {
			// Biomes agree even if the hash does not: the server hides or fakes the hash.
			verdict = Verdict.OK;
		} else {
			verdict = percent >= 40 && hashMatch == null ? Verdict.SUSPECT : Verdict.WRONG;
		}
		if (quietIfUnchanged && verdict == previous) {
			return;
		}
		ChatFormatting color = switch (verdict) {
			case OK -> ChatFormatting.GREEN;
			case SUSPECT, UNKNOWN -> ChatFormatting.YELLOW;
			case WRONG -> ChatFormatting.RED;
		};
		player.sendSystemMessage(describe().copy().withStyle(color));
	}

	private void sampleBiomes(Minecraft mc, ClientLevel level, GenContext ctx) {
		int radius = Math.max(2, mc.options.getEffectiveRenderDistance() - 1);
		int pcx = mc.player.chunkPosition().x();
		int pcz = mc.player.chunkPosition().z();
		int minQuartY = QuartPos.fromBlock(level.getMinY());
		int quartHeight = QuartPos.fromBlock(level.getHeight());
		int matches = 0;
		int total = 0;
		for (int attempt = 0; attempt < SAMPLES * 2 && total < SAMPLES; attempt++) {
			int cx = pcx + random.nextInt(radius * 2 + 1) - radius;
			int cz = pcz + random.nextInt(radius * 2 + 1) - radius;
			LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
			if (chunk == null || chunk.isEmpty()) {
				continue;
			}
			int qx = QuartPos.fromSection(cx) + random.nextInt(4);
			int qz = QuartPos.fromSection(cz) + random.nextInt(4);
			int qy = minQuartY + random.nextInt(quartHeight);
			Optional<ResourceKey<Biome>> actual = chunk.getNoiseBiome(qx, qy, qz).unwrapKey();
			Optional<ResourceKey<Biome>> predicted = ctx.generator().getBiomeSource()
				.getNoiseBiome(qx, qy, qz, ctx.randomState().sampler()).unwrapKey();
			if (actual.isEmpty() || predicted.isEmpty()) {
				continue;
			}
			total++;
			if (actual.get().identifier().equals(predicted.get().identifier())) {
				matches++;
			}
		}
		samples = total;
		percent = total < 32 ? -1 : Math.round(matches * 100f / total);
	}

	public void reset() {
		verdict = Verdict.UNKNOWN;
		percent = -1;
		samples = 0;
		hashMatch = null;
		contextId = "";
	}

	public Component describe() {
		String biomes = percent < 0 ? "?" : percent + "%";
		if (Boolean.TRUE.equals(hashMatch)) {
			return Component.translatable("jm_seedmap.check.hash_ok", biomes);
		}
		if (Boolean.FALSE.equals(hashMatch)) {
			return verdict == Verdict.OK
				? Component.translatable("jm_seedmap.check.hash_hidden", biomes)
				: Component.translatable("jm_seedmap.check.hash_wrong", biomes);
		}
		return switch (verdict) {
			case UNKNOWN -> Component.translatable("jm_seedmap.check.not_run");
			case OK -> Component.translatable("jm_seedmap.check.ok", biomes, samples);
			case SUSPECT -> Component.translatable("jm_seedmap.check.suspect", biomes, samples);
			case WRONG -> Component.translatable("jm_seedmap.check.wrong", biomes, samples);
		};
	}
}
