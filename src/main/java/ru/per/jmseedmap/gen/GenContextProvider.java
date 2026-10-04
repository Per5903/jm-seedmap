package ru.per.jmseedmap.gen;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;

/**
 * Resolves the {@link GenContext} for a map dimension.
 * <ul>
 *   <li>Singleplayer: the integrated server's own generator, so the result is exact even with data packs.</li>
 *   <li>Multiplayer: vanilla data + the seed the player entered for this server.</li>
 * </ul>
 * Contexts are built asynchronously; {@link #get} returns null until one is ready.
 */
public final class GenContextProvider {
	private final Executor executor;
	private final Map<String, CompletableFuture<Optional<GenContext>>> contexts = new ConcurrentHashMap<>();
	private @Nullable CompletableFuture<VanillaWorldgen> vanilla;

	public GenContextProvider(Executor executor) {
		this.executor = executor;
	}

	public enum Status { READY, LOADING, NO_SEED, UNKNOWN_DIMENSION }

	public record Lookup(Status status, @Nullable GenContext context) {
	}

	public Lookup get(ResourceKey<Level> dimension) {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			String id = "sp:" + System.identityHashCode(server) + ":" + dimension.identifier();
			return await(id, () -> fromServer(id, server, dimension));
		}

		String serverKey = serverKey();
		Long seed = SeedMapConfig.get().seeds.get(serverKey);
		if (seed == null) {
			return new Lookup(Status.NO_SEED, null);
		}
		ResourceKey<LevelStem> stem = resolveStem(dimension, mc.level);
		if (stem == null) {
			return new Lookup(Status.UNKNOWN_DIMENSION, null);
		}
		String preset = preset(serverKey);
		String id = "mp:" + seed + ":" + preset + ":" + stem.identifier() + ":" + dimension.identifier();
		return await(id, () -> fromVanilla(id, seed, preset, stem, dimension));
	}

	private Lookup await(String id, java.util.function.Supplier<Optional<GenContext>> factory) {
		CompletableFuture<Optional<GenContext>> future = contexts.computeIfAbsent(id, k -> CompletableFuture.supplyAsync(() -> {
			try {
				return factory.get();
			} catch (Exception e) {
				SeedMapClient.LOGGER.error("Failed to prepare world generator for {}", id, e);
				return Optional.empty();
			}
		}, executor));
		if (!future.isDone()) {
			return new Lookup(Status.LOADING, null);
		}
		Optional<GenContext> ctx = future.join();
		return ctx.map(c -> new Lookup(Status.READY, c)).orElseGet(() -> new Lookup(Status.UNKNOWN_DIMENSION, null));
	}

	public static Optional<GenContext> fromServer(String id, IntegratedServer server, ResourceKey<Level> dimension) {
		ServerLevel level = server.getLevel(dimension);
		if (level == null) {
			return Optional.empty();
		}
		ServerChunkCache chunks = level.getChunkSource();
		ChunkGeneratorStructureState state = chunks.getGeneratorState();
		state.ensureStructuresGenerated();
		return Optional.of(new GenContext(
			id, level.getSeed(), dimension, level.registryAccess(), chunks.getGenerator(), chunks.randomState(), state,
			server.getStructureManager(), LevelHeightAccessor.create(level.getMinY(), level.getHeight())
		));
	}

	public Optional<GenContext> fromVanilla(String id, long seed, String preset, ResourceKey<LevelStem> stemKey, ResourceKey<Level> dimension) {
		VanillaWorldgen data = vanilla().join();
		LevelStem stem = data.dimensions(preset).get(stemKey).orElse(null);
		if (stem == null) {
			return Optional.empty();
		}
		ChunkGenerator generator = stem.generator();
		NoiseGeneratorSettings settings = generator instanceof NoiseBasedChunkGenerator noise
			? noise.generatorSettings().value()
			: NoiseGeneratorSettings.dummy();
		RandomState randomState = RandomState.create(settings, data.registries.lookupOrThrow(Registries.NOISE), seed);
		ChunkGeneratorStructureState state = generator.createState(data.registries.lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
		state.ensureStructuresGenerated();
		DimensionType type = stem.type().value();
		return Optional.of(new GenContext(
			id, seed, dimension, data.registries, generator, randomState, state, data.templates, LevelHeightAccessor.create(type.minY(), type.height())
		));
	}

	private synchronized CompletableFuture<VanillaWorldgen> vanilla() {
		if (vanilla == null || vanilla.isCompletedExceptionally()) {
			// Own thread: workers block on this future, so it must not queue behind them.
			vanilla = CompletableFuture.supplyAsync(() -> {
				try {
					return VanillaWorldgen.load(Minecraft.getInstance());
				} catch (Exception e) {
					throw new RuntimeException("Failed to load vanilla worldgen data", e);
				}
			}, task -> {
				Thread thread = new Thread(task, "SeedMap data loader");
				thread.setDaemon(true);
				thread.start();
			});
		}
		return vanilla;
	}

	/**
	 * Auto-detects which vanilla dimension a (possibly renamed) server dimension corresponds to.
	 */
	public static @Nullable ResourceKey<LevelStem> resolveStem(ResourceKey<Level> dimension, @Nullable ClientLevel clientLevel) {
		if (dimension.equals(Level.OVERWORLD)) return LevelStem.OVERWORLD;
		if (dimension.equals(Level.NETHER)) return LevelStem.NETHER;
		if (dimension.equals(Level.END)) return LevelStem.END;

		// Renamed dimension (e.g. "world_nether" on Bukkit-like servers): look at its dimension type.
		if (clientLevel != null && clientLevel.dimension().equals(dimension)) {
			Optional<ResourceKey<DimensionType>> type = clientLevel.dimensionTypeRegistration().unwrapKey();
			if (type.isPresent()) {
				ResourceKey<DimensionType> key = type.get();
				if (key.equals(BuiltinDimensionTypes.OVERWORLD) || key.equals(BuiltinDimensionTypes.OVERWORLD_CAVES)) return LevelStem.OVERWORLD;
				if (key.equals(BuiltinDimensionTypes.NETHER)) return LevelStem.NETHER;
				if (key.equals(BuiltinDimensionTypes.END)) return LevelStem.END;
			}
			DimensionType dt = clientLevel.dimensionType();
			if (dt.hasCeiling() && !dt.hasSkyLight()) return LevelStem.NETHER;
			if (dt.hasEnderDragonFight()) return LevelStem.END;
		}

		String path = dimension.identifier().getPath();
		if (path.contains("nether")) return LevelStem.NETHER;
		if (path.equals("end") || path.endsWith("_end") || path.contains("the_end")) return LevelStem.END;
		if (path.contains("overworld") || path.equals("world")) return LevelStem.OVERWORLD;
		return null;
	}

	public static String serverKey() {
		ServerData data = Minecraft.getInstance().getCurrentServer();
		if (data == null) {
			return "unknown";
		}
		return data.isRealm() ? "realms:" + data.name : data.ip.toLowerCase(java.util.Locale.ROOT);
	}

	public static String preset(String serverKey) {
		return SeedMapConfig.get().presets.getOrDefault(serverKey, "minecraft:normal");
	}

	/** Drops every cached context, e.g. after the seed changed or on disconnect. */
	public void invalidate() {
		contexts.clear();
	}

	public void shutdown() {
		invalidate();
		CompletableFuture<VanillaWorldgen> v = vanilla;
		vanilla = null;
		if (v != null) {
			v.thenAccept(VanillaWorldgen::close);
		}
	}
}
