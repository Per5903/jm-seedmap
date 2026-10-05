package ru.per.jmseedmap.gen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;

/**
 * Resolves the {@link GenContext} for a map dimension.
 * <ul>
 *   <li>Singleplayer: the integrated server's own generator, so the result is exact even with data packs and mods.</li>
 *   <li>Multiplayer: vanilla data plus the server's data packs (if the player copied them into the server's folder)
 *       and the seed the player entered or found for this server.</li>
 * </ul>
 * Contexts are built asynchronously; {@link #get} returns null until one is ready. Context ids are stable across
 * game sessions (they name the disk cache), so they include everything that changes the generated world.
 */
public final class GenContextProvider {
	private final Executor executor;
	private final Map<String, CompletableFuture<Optional<GenContext>>> contexts = new ConcurrentHashMap<>();
	/** One loaded data set per set of extra data packs ("" = plain vanilla). */
	private final Map<String, CompletableFuture<VanillaWorldgen>> worldgen = new ConcurrentHashMap<>();
	/** Server key -> fingerprint of its data pack folder; recomputed after {@link #invalidate}. */
	private final Map<String, String> packKeys = new ConcurrentHashMap<>();
	private volatile @Nullable String lastError;
	private volatile @Nullable SpKey spKey;

	private record SpKey(IntegratedServer server, String key) {
	}

	public GenContextProvider(Executor executor) {
		this.executor = executor;
	}

	public enum Status { READY, LOADING, NO_SEED, UNKNOWN_DIMENSION, ERROR }

	public record Lookup(Status status, @Nullable GenContext context) {
	}

	public Lookup get(ResourceKey<Level> dimension) {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			SpKey cached = spKey;
			if (cached == null || cached.server() != server) {
				cached = new SpKey(server, singleplayerKey(server));
				spKey = cached;
			}
			String id = "sp:" + cached.key() + ":" + dimension.identifier();
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
		String packKey = packKeys.computeIfAbsent(serverKey, k -> packFingerprint(packDir(k)));
		String id = "mp:" + seed + ":" + preset + ":" + stem.identifier() + ":" + dimension.identifier() + (packKey.isEmpty() ? "" : ":dp" + packKey);
		Path packDir = packKey.isEmpty() ? null : packDir(serverKey);
		return await(id, () -> fromVanilla(id, seed, preset, stem, dimension, packDir, packKey));
	}

	/** Why the last context failed to build, for the settings screen; null if nothing failed. */
	public @Nullable String lastError() {
		return lastError;
	}

	private Lookup await(String id, java.util.function.Supplier<Optional<GenContext>> factory) {
		CompletableFuture<Optional<GenContext>> future = contexts.computeIfAbsent(id, k -> CompletableFuture.supplyAsync(() -> {
			try {
				return factory.get();
			} catch (Exception e) {
				SeedMapClient.LOGGER.error("Failed to prepare world generator for {}", id, e);
				Throwable cause = e;
				while (cause.getCause() != null && cause.getCause() != cause) {
					cause = cause.getCause();
				}
				lastError = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
				throw new java.util.concurrent.CompletionException(e);
			}
		}, executor));
		if (!future.isDone()) {
			return new Lookup(Status.LOADING, null);
		}
		if (future.isCompletedExceptionally()) {
			return new Lookup(Status.ERROR, null);
		}
		Optional<GenContext> ctx = future.join();
		return ctx.map(c -> new Lookup(Status.READY, c)).orElseGet(() -> new Lookup(Status.UNKNOWN_DIMENSION, null));
	}

	/** World folder, seed, enabled data packs and loaded mods: what makes a singleplayer world generate the way it does. */
	private static String singleplayerKey(IntegratedServer server) {
		String folder = String.valueOf(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName());
		// Sorted: the loader lists mods in no particular order, and the key must be the same every launch.
		java.util.TreeSet<String> parts = new java.util.TreeSet<>();
		server.getPackRepository().getSelectedIds().forEach(id -> parts.add("pack:" + id));
		FabricLoader.getInstance().getAllMods().forEach(mod ->
			parts.add(mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString()));
		String packs = String.join(";", parts);
		return folder + ":" + server.overworld().getSeed() + ":" + Integer.toHexString(packs.hashCode());
	}

	public static Optional<GenContext> fromServer(String id, IntegratedServer server, ResourceKey<Level> dimension) {
		ServerLevel level = server.getLevel(dimension);
		if (level == null) {
			return Optional.empty();
		}
		ServerChunkCache chunks = level.getChunkSource();
		ChunkGeneratorStructureState state = chunks.getGeneratorState();
		RingPositions.ensure(state, chunks.getGenerator().getBiomeSource(), chunks.randomState());
		return Optional.of(new GenContext(
			id, level.getSeed(), dimension, level.registryAccess(), chunks.getGenerator(), chunks.randomState(), state,
			server.getStructureManager(), LevelHeightAccessor.create(level.getMinY(), level.getHeight())
		));
	}

	/**
	 * @param packDir     extra data packs, or null for vanilla
	 * @param fingerprint {@link #packFingerprint} of {@code packDir}: a changed folder is loaded again
	 */
	public Optional<GenContext> fromVanilla(
		String id, long seed, String preset, ResourceKey<LevelStem> stemKey, ResourceKey<Level> dimension, @Nullable Path packDir, String fingerprint
	) {
		VanillaWorldgen data = worldgen(packDir, fingerprint).join();
		LevelStem stem = data.stem(preset, stemKey).orElse(null);
		if (stem == null) {
			return Optional.empty();
		}
		ChunkGenerator generator = stem.generator();
		NoiseGeneratorSettings settings = generator instanceof NoiseBasedChunkGenerator noise
			? noise.generatorSettings().value()
			: NoiseGeneratorSettings.dummy();
		RandomState randomState = RandomState.create(settings, data.registries.lookupOrThrow(Registries.NOISE), seed);
		ChunkGeneratorStructureState state = generator.createState(data.registries.lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
		RingPositions.ensure(state, generator.getBiomeSource(), randomState);
		DimensionType type = stem.type().value();
		return Optional.of(new GenContext(
			id, seed, dimension, data.registries, generator, randomState, state, data.templates, LevelHeightAccessor.create(type.minY(), type.height())
		));
	}

	private CompletableFuture<VanillaWorldgen> worldgen(@Nullable Path packDir, String fingerprint) {
		String path = packDir == null ? "" : packDir.toAbsolutePath().normalize().toString();
		String key = path + "#" + fingerprint;
		if (!path.isEmpty()) {
			// The folder changed since that set was loaded: let it go once no worker can still be using it.
			for (Map.Entry<String, CompletableFuture<VanillaWorldgen>> entry : Map.copyOf(worldgen).entrySet()) {
				if (entry.getKey().startsWith(path + "#") && !entry.getKey().equals(key) && worldgen.remove(entry.getKey(), entry.getValue())) {
					CompletableFuture.delayedExecutor(30, java.util.concurrent.TimeUnit.SECONDS)
						.execute(() -> entry.getValue().thenAccept(VanillaWorldgen::close));
				}
			}
		}
		return worldgen.compute(key, (k, existing) -> {
			if (existing != null && !existing.isCompletedExceptionally()) {
				return existing;
			}
			// Own thread: workers block on this future, so it must not queue behind them.
			return CompletableFuture.supplyAsync(() -> {
				try {
					return VanillaWorldgen.load(Minecraft.getInstance(), packDir);
				} catch (Exception e) {
					throw new RuntimeException("Failed to load worldgen data" + (packDir == null ? "" : " with data packs from " + packDir), e);
				}
			}, task -> {
				Thread thread = new Thread(task, "SeedMap data loader");
				thread.setDaemon(true);
				thread.start();
			});
		});
	}

	/** Names of the extra data packs used for the current server, or empty while not loaded / none. */
	public List<String> loadedPacks() {
		String serverKey = serverKey();
		String fingerprint = packKeys.get(serverKey);
		if (fingerprint == null || fingerprint.isEmpty()) {
			return List.of();
		}
		CompletableFuture<VanillaWorldgen> future = worldgen.get(packDir(serverKey).toAbsolutePath().normalize() + "#" + fingerprint);
		return future != null && future.isDone() && !future.isCompletedExceptionally() ? future.join().extraPacks : List.of();
	}

	/** Where the player puts data packs copied from {@code serverKey} (zips or unpacked folders). */
	public static Path packDir(String serverKey) {
		return SeedMapConfig.DIR.resolve("datapacks").resolve(serverKey.replaceAll("[^A-Za-z0-9._-]", "_"));
	}

	/** Names, sizes and dates of the pack folder's entries; "" if there are none. */
	public static String packFingerprint(Path dir) {
		if (!Files.isDirectory(dir)) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		try (Stream<Path> entries = Files.list(dir)) {
			for (Path entry : entries.sorted().toList()) {
				String name = entry.getFileName().toString();
				if (name.startsWith(".")) {
					continue;
				}
				sb.append(name).append(':').append(fingerprint(entry)).append(';');
			}
		} catch (IOException e) {
			SeedMapClient.LOGGER.warn("Cannot read data pack folder {}", dir, e);
		}
		return sb.isEmpty() ? "" : Integer.toHexString(sb.toString().hashCode());
	}

	private static String fingerprint(Path entry) throws IOException {
		if (!Files.isDirectory(entry)) {
			return Files.size(entry) + "@" + Files.getLastModifiedTime(entry).toMillis();
		}
		long newest = 0;
		long count = 0;
		try (Stream<Path> files = Files.walk(entry)) {
			for (Path file : files.toList()) {
				newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
				count++;
			}
		}
		return count + "@" + newest;
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

	/** Key the current server's seed, world type and data packs are stored under: its address plus the seed profile. */
	public static String serverKey() {
		return ru.per.jmseedmap.core.SeedProfiles.currentKey();
	}

	public static String preset(String serverKey) {
		return SeedMapConfig.get().presets.getOrDefault(serverKey, "minecraft:normal");
	}

	/** Drops every cached context, e.g. after the seed changed or on disconnect. Data pack folders are re-read. */
	public void invalidate() {
		contexts.clear();
		packKeys.clear();
		lastError = null;
	}

	public void shutdown() {
		contexts.clear();
		for (CompletableFuture<VanillaWorldgen> future : worldgen.values()) {
			future.thenAccept(VanillaWorldgen::close);
		}
		worldgen.clear();
	}
}
