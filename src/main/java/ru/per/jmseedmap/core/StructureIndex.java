package ru.per.jmseedmap.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.gen.FoundStructure;
import ru.per.jmseedmap.gen.GenContext;
import ru.per.jmseedmap.gen.GenContextProvider;
import ru.per.jmseedmap.gen.StructureFinder;

/**
 * Map-independent structure cache. Map integrations report what area they show ({@link #requestView}),
 * the index computes the missing 512x512 tiles in the background, nearest to the view center first,
 * and {@link #query} returns what is already known. The dimension always comes from the view,
 * so a map showing the Nether gets Nether structures.
 * <p>
 * Finished tiles are also kept on disk ({@link TileCache}) and read back the next time the same world is opened.
 */
public final class StructureIndex {
	private static final int MAX_CACHED = 80_000;
	private static final long VIEW_TTL_MS = 1500;
	private static final long SAVE_INTERVAL_MS = 30_000;

	record TileKey(String contextId, Identifier set, int tileX, int tileZ) {
	}

	private record View(ResourceKey<Level> dimension, double minX, double minZ, double maxX, double maxZ, long time) {
	}

	private record Candidate(GenContext context, Holder<StructureSet> set, TileKey key, double distance) {
	}

	private record EnabledSets(int filterVersion, List<Holder<StructureSet>> sets) {
	}

	private final ThreadPoolExecutor workers;
	/** Separate from {@link #workers}: a search waits for contexts that are built on the workers. */
	private final ExecutorService search = singleThread("SeedMap search");
	/** Disk cache reads and writes. */
	private final ExecutorService io = singleThread("SeedMap cache");
	private final GenContextProvider contexts;
	private final TileCache cache;
	private final Map<TileKey, List<FoundStructure>> results = new ConcurrentHashMap<>();
	private final Set<TileKey> pending = ConcurrentHashMap.newKeySet();
	private final Set<TileKey> failed = ConcurrentHashMap.newKeySet();
	private final Map<String, View> views = new ConcurrentHashMap<>();
	private final Map<ResourceKey<Level>, GenContextProvider.Status> lastStatus = new ConcurrentHashMap<>();
	private final Map<String, EnabledSets> enabledSets = new ConcurrentHashMap<>();
	/** Contexts whose disk cache was read (or is being read) in this session. */
	private final Set<String> cacheLoaded = ConcurrentHashMap.newKeySet();
	/** Contexts with tiles computed since the last save, and the dimension they belong to. */
	private final Map<String, ResourceKey<Level>> cacheDirty = new ConcurrentHashMap<>();
	private final AtomicInteger completed = new AtomicInteger();
	private volatile boolean errorLogged;
	private long lastSave = Util.getMillis();
	private int appliedThreads;

	public StructureIndex() {
		AtomicInteger n = new AtomicInteger();
		int threads = Perf.current().threads;
		this.workers = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
			Thread thread = new Thread(task, "SeedMap worker " + n.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY + 1);
			return thread;
		});
		this.workers.allowCoreThreadTimeOut(true);
		this.appliedThreads = threads;
		this.contexts = new GenContextProvider(workers);
		this.cache = new TileCache(Minecraft.getInstance().gameDirectory.toPath().resolve("jm_seedmap").resolve("cache"));
	}

	private static ExecutorService singleThread(String name) {
		return Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, name);
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY + 1);
			return thread;
		});
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

	public GenContextProvider contexts() {
		return contexts;
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

	/** Runs low-priority background work (biome tiles) on the same threads as the structure search. */
	void executeBackground(Runnable task) {
		workers.execute(task);
	}

	int workerQueueSize() {
		return workers.getQueue().size();
	}

	/** Schedules computation for all live views. Render thread. */
	public void tick() {
		Perf perf = Perf.current();
		applyThreads(perf.threads);
		long now = Util.getMillis();
		views.values().removeIf(view -> now - view.time() > VIEW_TTL_MS);
		List<Candidate> toCompute = new ArrayList<>();
		Set<TileKey> wanted = new HashSet<>();
		for (View view : views.values()) {
			collect(view, perf, wanted, toCompute);
		}
		toCompute.sort(Comparator.comparingDouble(Candidate::distance));
		for (Candidate candidate : toCompute) {
			if (pending.size() >= perf.maxPending) {
				break;
			}
			submit(candidate);
		}
		if (results.size() > MAX_CACHED) {
			saveDirty();
			results.keySet().removeIf(key -> !wanted.contains(key));
		}
		if (now - lastSave > SAVE_INTERVAL_MS) {
			lastSave = now;
			saveDirty();
		}
	}

	private void applyThreads(int threads) {
		if (threads == appliedThreads) {
			return;
		}
		// Growing: raise the maximum first; shrinking: lower the core size first.
		if (threads > appliedThreads) {
			workers.setMaximumPoolSize(threads);
			workers.setCorePoolSize(threads);
		} else {
			workers.setCorePoolSize(threads);
			workers.setMaximumPoolSize(threads);
		}
		appliedThreads = threads;
	}

	private void collect(View view, Perf perf, Set<TileKey> wanted, List<Candidate> toCompute) {
		GenContextProvider.Lookup lookup = contexts.get(view.dimension());
		lastStatus.put(view.dimension(), lookup.status());
		GenContext ctx = lookup.context();
		if (ctx == null) {
			return;
		}
		loadCache(ctx);
		int centerX = tile((view.minX() + view.maxX()) / 2);
		int centerZ = tile((view.minZ() + view.maxZ()) / 2);
		int minX = Math.max(tile(view.minX() - 64), centerX - perf.tileRadius);
		int maxX = Math.min(tile(view.maxX() + 64), centerX + perf.tileRadius);
		int minZ = Math.max(tile(view.minZ() - 64), centerZ - perf.tileRadius);
		int maxZ = Math.min(tile(view.maxZ() + 64), centerZ + perf.tileRadius);
		List<Holder<StructureSet>> sets = enabledSets(ctx);
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

	/** Structure sets with at least one enabled structure; cached until a toggle changes. */
	private List<Holder<StructureSet>> enabledSets(GenContext ctx) {
		int version = StructureStyles.filterVersion();
		EnabledSets cached = enabledSets.get(ctx.id());
		if (cached != null && cached.filterVersion() == version) {
			return cached.sets();
		}
		List<Holder<StructureSet>> sets = setsMatching(ctx, StructureStyles::isEnabled);
		enabledSets.put(ctx.id(), new EnabledSets(version, sets));
		return sets;
	}

	private static List<Holder<StructureSet>> setsMatching(GenContext ctx, Predicate<String> structureFilter) {
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
		return List.copyOf(sets);
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
			found = List.copyOf(StructureFinder.findInTile(ctx, set, key.tileX(), key.tileZ()));
		} catch (Throwable t) {
			if (!errorLogged) {
				errorLogged = true;
				SeedMapClient.LOGGER.error("Structure search failed for {} in tile {},{}", key.set(), key.tileX(), key.tileZ(), t);
			}
			// Not cached on disk: a failure may be temporary.
			failed.add(key);
			results.put(key, List.of());
			completed.incrementAndGet();
			return List.of();
		}
		results.put(key, found);
		cacheDirty.put(ctx.id(), ctx.dimension());
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
		List<Holder<StructureSet>> sets = enabledSets(ctx);
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

	/** Like {@link #query}, but every type that has been computed, shown on the map or not. */
	public List<FoundStructure> queryAll(ResourceKey<Level> dimension, double minX, double minZ, double maxX, double maxZ) {
		GenContext ctx = contexts.get(dimension).context();
		if (ctx == null) {
			return List.of();
		}
		List<FoundStructure> out = new ArrayList<>();
		for (Holder<StructureSet> set : ctx.structureState().possibleStructureSets()) {
			if (set.unwrapKey().isEmpty()) {
				continue;
			}
			Identifier setId = set.unwrapKey().get().identifier();
			for (int tx = tile(minX); tx <= tile(maxX); tx++) {
				for (int tz = tile(minZ); tz <= tile(maxZ); tz++) {
					List<FoundStructure> tileResults = results.get(new TileKey(ctx.id(), setId, tx, tz));
					if (tileResults == null) {
						continue;
					}
					for (FoundStructure s : tileResults) {
						if (s.pos().getX() >= minX && s.pos().getX() <= maxX && s.pos().getZ() >= minZ && s.pos().getZ() <= maxZ) {
							out.add(s);
						}
					}
				}
			}
		}
		return out;
	}

	/** Structure ids (and variants such as the End city with a ship) the dimension's generator can place; empty if unknown. */
	public java.util.Set<String> possibleStructures(ResourceKey<Level> dimension) {
		GenContext ctx = context(dimension);
		java.util.Set<String> ids = new java.util.LinkedHashSet<>();
		if (ctx == null) {
			return ids;
		}
		for (Holder<StructureSet> set : ctx.structureState().possibleStructureSets()) {
			for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
				entry.structure().unwrapKey().ifPresent(k -> {
					String id = k.identifier().toString();
					ids.add(id);
					ids.addAll(StructureStyles.variants(id));
				});
			}
		}
		return ids;
	}

	/**
	 * Searches outward from (x, z) ring by ring for the closest structure accepted by {@code filter},
	 * up to {@code maxTiles} tiles away. Runs on a worker; computed tiles are cached for the map too.
	 */
	public CompletableFuture<Optional<FoundStructure>> nearest(
		ResourceKey<Level> dimension, double x, double z, Predicate<String> filter, Predicate<FoundStructure> accept, int maxTiles
	) {
		return nearestN(dimension, x, z, filter, accept, 1, maxTiles).thenApply(list -> list.stream().findFirst());
	}

	/** The {@code count} closest accepted structures, nearest first (fewer if there are not that many in range). */
	public CompletableFuture<List<FoundStructure>> nearestN(
		ResourceKey<Level> dimension, double x, double z, Predicate<String> filter, Predicate<FoundStructure> accept, int count, int maxTiles
	) {
		return CompletableFuture.supplyAsync(() -> {
			GenContext ctx = awaitContext(dimension);
			if (ctx == null) {
				return List.<FoundStructure>of();
			}
			loadCacheNow(ctx);
			List<Holder<StructureSet>> sets = setsMatching(ctx, filter);
			if (sets.isEmpty()) {
				return List.<FoundStructure>of();
			}
			int cx = tile(x);
			int cz = tile(z);
			List<FoundStructure> best = new ArrayList<>();
			Comparator<FoundStructure> byDistance = Comparator.comparingDouble(s -> s.distanceSqr(x, z));
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
								if (filter.test(s.displayId()) && accept.test(s)) {
									best.add(s);
								}
							}
						}
					}
				}
				best.sort(byDistance);
				if (best.size() > count) {
					best.subList(count, best.size()).clear();
				}
				// Everything in later rings is at least r tiles away.
				double reach = (double) r * StructureFinder.TILE_BLOCKS;
				if (best.size() == count && best.get(count - 1).distanceSqr(x, z) <= reach * reach) {
					break;
				}
			}
			return List.copyOf(best);
		}, search);
	}

	/** For the biome layer's disk cache: runs on the cache thread. */
	void executeIo(Runnable task) {
		io.execute(task);
	}

	/**
	 * All structures accepted by {@code filter} within {@code radius} blocks of (x, z), nearest first.
	 * Computes missing tiles; runs on the search thread.
	 */
	public CompletableFuture<List<FoundStructure>> around(
		ResourceKey<Level> dimension, double x, double z, double radius, Predicate<String> filter
	) {
		return CompletableFuture.supplyAsync(() -> {
			GenContext ctx = awaitContext(dimension);
			if (ctx == null) {
				return List.of();
			}
			loadCacheNow(ctx);
			List<Holder<StructureSet>> sets = setsMatching(ctx, filter);
			List<FoundStructure> out = new ArrayList<>();
			for (int tx = tile(x - radius); tx <= tile(x + radius); tx++) {
				for (int tz = tile(z - radius); tz <= tile(z + radius); tz++) {
					for (Holder<StructureSet> set : sets) {
						TileKey key = new TileKey(ctx.id(), set.unwrapKey().get().identifier(), tx, tz);
						List<FoundStructure> found = results.get(key);
						if (found == null) {
							found = compute(ctx, set, key);
						}
						for (FoundStructure s : found) {
							if (filter.test(s.displayId()) && s.distanceSqr(x, z) <= radius * radius) {
								out.add(s);
							}
						}
					}
				}
			}
			out.sort(Comparator.comparingDouble(s -> s.distanceSqr(x, z)));
			return out;
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

	// ---- disk cache ----

	private void loadCache(GenContext ctx) {
		if (SeedMapConfig.get().diskCache && cacheLoaded.add(ctx.id())) {
			io.execute(() -> readCache(ctx));
		}
	}

	/** For searches: read the cache on this thread so the search does not recompute what is on disk. */
	private void loadCacheNow(GenContext ctx) {
		if (SeedMapConfig.get().diskCache && cacheLoaded.add(ctx.id())) {
			readCache(ctx);
		}
	}

	private void readCache(GenContext ctx) {
		long start = Util.getMillis();
		List<TileCache.Tile> tiles = cache.read(ctx.id(), ctx.dimension());
		int added = 0;
		for (TileCache.Tile tile : tiles) {
			if (results.putIfAbsent(new TileKey(ctx.id(), tile.set(), tile.tileX(), tile.tileZ()), tile.structures()) == null) {
				added++;
			}
		}
		if (added > 0) {
			completed.incrementAndGet();
			SeedMapClient.LOGGER.info("Read {} structure tiles of {} from the disk cache in {} ms", added, ctx.dimension().identifier(),
				Util.getMillis() - start);
		}
	}

	/** Writes every context with new tiles; the snapshot is taken here, the file is written on the cache thread. */
	private void saveDirty() {
		if (!SeedMapConfig.get().diskCache || cacheDirty.isEmpty()) {
			cacheDirty.clear();
			return;
		}
		Map<String, ResourceKey<Level>> dirty = Map.copyOf(cacheDirty);
		cacheDirty.clear();
		Map<String, List<TileCache.Tile>> snapshot = new java.util.HashMap<>();
		for (Map.Entry<TileKey, List<FoundStructure>> e : results.entrySet()) {
			TileKey key = e.getKey();
			if (dirty.containsKey(key.contextId()) && !failed.contains(key)) {
				snapshot.computeIfAbsent(key.contextId(), k -> new ArrayList<>())
					.add(new TileCache.Tile(key.set(), key.tileX(), key.tileZ(), e.getValue()));
			}
		}
		io.execute(() -> snapshot.forEach(cache::write));
	}

	/** Frees the disk cache; returns the number of bytes deleted. Blocks until pending writes are done. */
	public long clearDiskCache() {
		cacheDirty.clear();
		try {
			return io.submit(cache::clear).get();
		} catch (Exception e) {
			return 0;
		}
	}

	public long diskCacheBytes() {
		return cache.sizeBytes();
	}

	/** Forget all computed results, e.g. after the seed or preset changed. Unsaved tiles are written first. */
	public void reset() {
		saveDirty();
		results.clear();
		failed.clear();
		views.clear();
		lastStatus.clear();
		enabledSets.clear();
		cacheLoaded.clear();
		contexts.invalidate();
		completed.incrementAndGet();
		errorLogged = false;
	}

	public void shutdown() {
		reset();
		contexts.shutdown();
		workers.shutdownNow();
		search.shutdownNow();
		io.shutdown();
		try {
			io.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
