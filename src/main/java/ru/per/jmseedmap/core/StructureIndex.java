package ru.per.jmseedmap.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.gen.FoundStructure;
import ru.per.jmseedmap.gen.GenContext;
import ru.per.jmseedmap.gen.GenContextProvider;
import ru.per.jmseedmap.gen.StructureFinder;

/**
 * Map-independent structure cache. Map integrations report what area they show ({@link #requestView}),
 * the index computes the missing 512x512 tiles in the background, nearest to the view center first,
 * and {@link #query} returns what is already known. The dimension always comes from the view,
 * so a map showing the Nether gets Nether structures.
 */
public final class StructureIndex {
	/** Tiles further than this from a view center are not computed, which bounds work when zoomed far out. */
	private static final int MAX_TILE_RADIUS = 12;
	private static final int MAX_PENDING = 64;
	private static final int MAX_CACHED = 80_000;
	private static final long VIEW_TTL_MS = 1500;

	private record TileKey(String contextId, Identifier set, int tileX, int tileZ) {
	}

	private record View(ResourceKey<Level> dimension, double minX, double minZ, double maxX, double maxZ, long time) {
	}

	private record Candidate(GenContext context, Holder<StructureSet> set, TileKey key, double distance) {
	}

	private final ExecutorService workers;
	/** Separate from {@link #workers}: a search waits for contexts that are built on the workers. */
	private final ExecutorService search = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "SeedMap search");
		thread.setDaemon(true);
		return thread;
	});
	private final GenContextProvider contexts;
	private final Map<TileKey, List<FoundStructure>> results = new ConcurrentHashMap<>();
	private final Set<TileKey> pending = ConcurrentHashMap.newKeySet();
	private final Map<String, View> views = new ConcurrentHashMap<>();
	private final Map<ResourceKey<Level>, GenContextProvider.Status> lastStatus = new ConcurrentHashMap<>();
	private final AtomicInteger completed = new AtomicInteger();
	private volatile boolean errorLogged;

	public StructureIndex() {
		int threads = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
		AtomicInteger n = new AtomicInteger();
		this.workers = Executors.newFixedThreadPool(threads, task -> {
			Thread thread = new Thread(task, "SeedMap worker " + n.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY + 1);
			return thread;
		});
		this.contexts = new GenContextProvider(workers);
	}

	/**
	 * Tells the index that {@code source} (e.g. "xaero_worldmap") currently shows this area.
	 * Must be repeated while visible; stale views expire after a moment.
	 */
	public void requestView(String source, ResourceKey<Level> dimension, double minX, double minZ, double maxX, double maxZ) {
		views.put(source, new View(dimension, minX, minZ, maxX, maxZ, Util.getMillis()));
	}

	public GenContextProvider.@Nullable Status status(ResourceKey<Level> dimension) {
		GenContextProvider.Status status = lastStatus.get(dimension);
		return status != null ? status : contexts.get(dimension).status();
	}

	/** The ready generator context for a dimension, or null while loading / without a seed. */
	public @Nullable GenContext context(ResourceKey<Level> dimension) {
		return contexts.get(dimension).context();
	}

	public int pendingTiles() {
		return pending.size();
	}

	/** Increases whenever new tiles finish; cheap change detection for map integrations. */
	public int completedTiles() {
		return completed.get();
	}

	/** Schedules computation for all live views. Render thread. */
	public void tick() {
		long now = Util.getMillis();
		views.values().removeIf(view -> now - view.time() > VIEW_TTL_MS);
		List<Candidate> toCompute = new ArrayList<>();
		Set<TileKey> wanted = new java.util.HashSet<>();
		for (View view : views.values()) {
			collect(view, wanted, toCompute);
		}
		toCompute.sort(Comparator.comparingDouble(Candidate::distance));
		for (Candidate candidate : toCompute) {
			if (pending.size() >= MAX_PENDING) {
				break;
			}
			submit(candidate);
		}
		if (results.size() > MAX_CACHED) {
			results.keySet().removeIf(key -> !wanted.contains(key));
		}
	}

	private void collect(View view, Set<TileKey> wanted, List<Candidate> toCompute) {
		GenContextProvider.Lookup lookup = contexts.get(view.dimension());
		lastStatus.put(view.dimension(), lookup.status());
		GenContext ctx = lookup.context();
		if (ctx == null) {
			return;
		}
		int centerX = tile((view.minX() + view.maxX()) / 2);
		int centerZ = tile((view.minZ() + view.maxZ()) / 2);
		int minX = Math.max(tile(view.minX() - 64), centerX - MAX_TILE_RADIUS);
		int maxX = Math.min(tile(view.maxX() + 64), centerX + MAX_TILE_RADIUS);
		int minZ = Math.max(tile(view.minZ() - 64), centerZ - MAX_TILE_RADIUS);
		int maxZ = Math.min(tile(view.maxZ() + 64), centerZ + MAX_TILE_RADIUS);
		List<Holder<StructureSet>> sets = enabledSets(ctx, StructureStyles::isEnabled);
		for (int tx = minX; tx <= maxX; tx++) {
			for (int tz = minZ; tz <= maxZ; tz++) {
				double distance = Math.hypot(tx - centerX, tz - centerZ);
				for (Holder<StructureSet> set : sets) {
					TileKey key = new TileKey(ctx.id(), set.unwrapKey().get().identifier(), tx, tz);
					wanted.add(key);
					if (!results.containsKey(key) && !pending.contains(key)) {
						toCompute.add(new Candidate(ctx, set, key, distance));
					}
				}
			}
		}
	}

	private static int tile(double block) {
		return Math.floorDiv((int) Math.floor(block), StructureFinder.TILE_BLOCKS);
	}

	private static List<Holder<StructureSet>> enabledSets(GenContext ctx, Predicate<String> structureFilter) {
		List<Holder<StructureSet>> sets = new ArrayList<>();
		for (Holder<StructureSet> set : ctx.structureState().possibleStructureSets()) {
			if (set.unwrapKey().isEmpty()) {
				continue;
			}
			for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
				if (entry.structure().unwrapKey().map(k -> StructureStyles.anyVariant(k.identifier().toString(), structureFilter)).orElse(false)) {
					sets.add(set);
					break;
				}
			}
		}
		return sets;
	}

	private void submit(Candidate candidate) {
		TileKey key = candidate.key();
		if (!pending.add(key)) {
			return;
		}
		workers.execute(() -> {
			try {
				compute(candidate.context(), candidate.set(), key);
			} finally {
				pending.remove(key);
			}
		});
	}

	private List<FoundStructure> compute(GenContext ctx, Holder<StructureSet> set, TileKey key) {
		List<FoundStructure> found;
		try {
			found = StructureFinder.findInTile(ctx, set, key.tileX(), key.tileZ());
		} catch (Throwable t) {
			if (!errorLogged) {
				errorLogged = true;
				SeedMapClient.LOGGER.error("Structure search failed for {} in tile {},{}", key.set(), key.tileX(), key.tileZ(), t);
			}
			found = List.of();
		}
		results.put(key, found);
		completed.incrementAndGet();
		return found;
	}

	/**
	 * Already computed, enabled structures of {@code dimension} inside the box. Never blocks.
	 */
	public List<FoundStructure> query(ResourceKey<Level> dimension, double minX, double minZ, double maxX, double maxZ) {
		GenContext ctx = contexts.get(dimension).context();
		if (ctx == null) {
			return List.of();
		}
		List<FoundStructure> out = new ArrayList<>();
		List<Holder<StructureSet>> sets = enabledSets(ctx, StructureStyles::isEnabled);
		for (int tx = tile(minX); tx <= tile(maxX); tx++) {
			for (int tz = tile(minZ); tz <= tile(maxZ); tz++) {
				for (Holder<StructureSet> set : sets) {
					List<FoundStructure> tileResults = results.get(new TileKey(ctx.id(), set.unwrapKey().get().identifier(), tx, tz));
					if (tileResults == null) {
						continue;
					}
					for (FoundStructure s : tileResults) {
						if (s.pos().getX() >= minX && s.pos().getX() <= maxX && s.pos().getZ() >= minZ && s.pos().getZ() <= maxZ
							&& StructureStyles.isEnabled(s.displayId())) {
							out.add(s);
						}
					}
				}
			}
		}
		return out;
	}

	/**
	 * Searches outward from (x, z) ring by ring for the closest structure accepted by {@code filter},
	 * up to {@code maxTiles} tiles away. Runs on a worker; computed tiles are cached for the map too.
	 */
	public CompletableFuture<Optional<FoundStructure>> nearest(
		ResourceKey<Level> dimension, double x, double z, Predicate<String> filter, Predicate<FoundStructure> accept, int maxTiles
	) {
		return CompletableFuture.supplyAsync(() -> {
			GenContext ctx = awaitContext(dimension);
			if (ctx == null) {
				return Optional.empty();
			}
			List<Holder<StructureSet>> sets = enabledSets(ctx, filter);
			if (sets.isEmpty()) {
				return Optional.empty();
			}
			int cx = tile(x);
			int cz = tile(z);
			FoundStructure best = null;
			double bestDist = Double.MAX_VALUE;
			for (int r = 0; r <= maxTiles; r++) {
				for (int tx = cx - r; tx <= cx + r; tx++) {
					for (int tz = cz - r; tz <= cz + r; tz++) {
						if (Math.max(Math.abs(tx - cx), Math.abs(tz - cz)) != r) {
							continue;
						}
						for (Holder<StructureSet> set : sets) {
							TileKey key = new TileKey(ctx.id(), set.unwrapKey().get().identifier(), tx, tz);
							List<FoundStructure> found = results.get(key);
							if (found == null) {
								found = compute(ctx, set, key);
							}
							for (FoundStructure s : found) {
								double d = s.distanceSqr(x, z);
								if (d < bestDist && filter.test(s.displayId()) && accept.test(s)) {
									best = s;
									bestDist = d;
								}
							}
						}
					}
				}
				// Everything in later rings is at least r tiles away.
				double reach = (double) r * StructureFinder.TILE_BLOCKS;
				if (best != null && bestDist <= reach * reach) {
					break;
				}
			}
			return Optional.ofNullable(best);
		}, search);
	}

	/**
	 * Closest biome with this id around {@code origin}, the same search as {@code /locate biome}
	 * (6400 blocks, every 32 blocks horizontally, every 64 vertically).
	 */
	public CompletableFuture<Optional<net.minecraft.core.BlockPos>> nearestBiome(
		ResourceKey<Level> dimension, net.minecraft.core.BlockPos origin, Identifier biome, net.minecraft.world.level.LevelReader heights
	) {
		return CompletableFuture.supplyAsync(() -> {
			GenContext ctx = awaitContext(dimension);
			if (ctx == null) {
				return Optional.empty();
			}
			var result = ctx.generator().getBiomeSource().findClosestBiome3d(origin, 6400, 32, 64,
				holder -> holder.unwrapKey().map(k -> k.identifier().equals(biome)).orElse(false), ctx.randomState().sampler(), heights);
			return Optional.ofNullable(result).map(com.mojang.datafixers.util.Pair::getFirst);
		}, search);
	}

	/** Biomes the generator of {@code dimension} can produce, or empty while unknown. */
	public List<Identifier> possibleBiomes(ResourceKey<Level> dimension) {
		GenContext ctx = context(dimension);
		if (ctx == null) {
			return List.of();
		}
		List<Identifier> ids = new ArrayList<>();
		for (var holder : ctx.generator().getBiomeSource().possibleBiomes()) {
			holder.unwrapKey().ifPresent(k -> ids.add(k.identifier()));
		}
		return ids;
	}

	private @Nullable GenContext awaitContext(ResourceKey<Level> dimension) {
		for (int i = 0; i < 600; i++) {
			GenContextProvider.Lookup lookup = contexts.get(dimension);
			if (lookup.status() != GenContextProvider.Status.LOADING) {
				return lookup.context();
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		return null;
	}

	/** Forget all computed results, e.g. after the seed or preset changed. */
	public void reset() {
		results.clear();
		views.clear();
		lastStatus.clear();
		contexts.invalidate();
		completed.incrementAndGet();
		errorLogged = false;
	}

	public void shutdown() {
		reset();
		contexts.shutdown();
		workers.shutdownNow();
		search.shutdownNow();
	}
}
