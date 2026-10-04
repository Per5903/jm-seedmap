package ru.per.jmseedmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import ru.per.jmseedmap.gen.FoundStructure;
import ru.per.jmseedmap.gen.GenContext;
import ru.per.jmseedmap.gen.GenContextProvider;
import ru.per.jmseedmap.gen.StructureFinder;

/**
 * Development check, enabled with {@code -Djm_seedmap.selftest=true}: compares the structures this mod
 * predicts with the starts vanilla really generates, for both the integrated-server path and the
 * "seed only" multiplayer path. Prints {@code SELFTEST PASS/FAIL} to the log.
 */
final class SelfTest {
	static final boolean ENABLED = Boolean.getBoolean("jm_seedmap.selftest");
	private static boolean started;

	private SelfTest() {
	}

	/**
	 * The data pack test world replaces the Overworld, so the game asks to confirm an "experimental" world.
	 * Only in the selftest: answer "I know what I'm doing" so the run needs no clicks.
	 */
	private static Object confirmed;

	static void tick(Minecraft mc) {
		if (ENABLED && mc.gui.screen() instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen && screen != confirmed) {
			confirmed = screen;
			for (var child : screen.children()) {
				if (child instanceof net.minecraft.client.gui.components.Button button
					&& button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
					&& t.getKey().equals("selectWorld.backupJoinSkipButton")) {
					SeedMapClient.LOGGER.info("SELFTEST confirming the experimental test world");
					button.onPress(new net.minecraft.client.input.MouseButtonInfo(0, 0));
					return;
				}
			}
		}
	}

	static void onJoin(Minecraft mc) {
		if (!ENABLED || started) {
			return;
		}
		started = true;
		Thread thread = new Thread(() -> {
			boolean ok = false;
			try {
				ok = Boolean.getBoolean("jm_seedmap.selftest.quick") || run(mc);
			} catch (Throwable t) {
				SeedMapClient.LOGGER.error("SELFTEST crashed", t);
			}
			SeedMapClient.LOGGER.info("SELFTEST {}", ok ? "PASS" : "FAIL");
			if (Boolean.getBoolean("jm_seedmap.selftest.screenshot")) {
				screenshotMap(mc);
			}
		}, "SeedMap selftest");
		thread.setDaemon(true);
		thread.start();
	}

	private static boolean run(Minecraft mc) throws Exception {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			// Multiplayer: forget the seed, recover it from the server's hash, then check it.
			var seedMap = ru.per.jmseedmap.core.SeedMap.get();
			String serverKey = GenContextProvider.serverKey();
			SeedMapConfig.get().seeds.remove(serverKey);
			mc.execute(seedMap::resetStructures);
			Thread.sleep(2_000L);
			mc.execute(() -> seedMap.startSeedSearch(null));
			for (int i = 0; i < 600 && SeedMapConfig.get().seeds.get(serverKey) == null; i++) {
				Thread.sleep(500L);
				if (i % 20 == 0) {
					SeedMapClient.LOGGER.info("SELFTEST finder: {}% after {} s, {} seeds", seedMap.seedFinder.progress(),
						seedMap.seedFinder.elapsedMillis() / 1000, seedMap.seedFinder.checked());
				}
			}
			Long found = SeedMapConfig.get().seeds.get(serverKey);
			SeedMapClient.LOGGER.info("SELFTEST finder found {} in {} ms ({} seeds checked)", found, seedMap.seedFinder.elapsedMillis(),
				seedMap.seedFinder.checked());
			Thread.sleep(15_000L);
			mc.execute(() -> seedMap.seedCheck.run(mc, seedMap.index, false));
			Thread.sleep(1_000L);
			mc.execute(() -> SeedMapClient.LOGGER.info("SELFTEST seed check: {}", seedMap.seedCheck.describe().getString()));
			if (found != null) {
				long structureSeed = found & ((1L << 48) - 1);
				SeedMapConfig.get().seeds.remove(serverKey);
				mc.execute(() -> seedMap.startSeedSearch(structureSeed));
				for (int i = 0; i < 60 && SeedMapConfig.get().seeds.get(serverKey) == null; i++) {
					Thread.sleep(250L);
				}
				SeedMapClient.LOGGER.info("SELFTEST structure-seed finder found {} in {} ms", SeedMapConfig.get().seeds.get(serverKey),
					seedMap.seedFinder.elapsedMillis());
			}
			if (found != null && net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("seedcrackerx")) {
				// What SeedCrackerX does when it cracks a seed: call every "seedcrackerx" entrypoint.
				SeedMapConfig.get().seeds.remove(serverKey);
				long seed = found;
				mc.execute(() -> {
					try {
						var list = (java.util.List<?>) Class.forName("kaptainwutax.seedcrackerX.SeedCracker").getField("entrypoints").get(null);
						SeedMapClient.LOGGER.info("SELFTEST SeedCrackerX entrypoints: {}", list.size());
						var push = Class.forName("kaptainwutax.seedcrackerX.api.SeedCrackerAPI").getMethod("pushWorldSeed", long.class);
						for (Object entrypoint : list) {
							push.invoke(entrypoint, seed);
						}
					} catch (ReflectiveOperationException e) {
						SeedMapClient.LOGGER.error("SELFTEST cannot call SeedCrackerX entrypoints", e);
					}
				});
				Thread.sleep(2_000L);
				SeedMapClient.LOGGER.info("SELFTEST seed from SeedCrackerX entrypoint: {}", SeedMapConfig.get().seeds.get(serverKey));
			}
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("viafabricplus")) {
				// Pretend ViaFabricPlus is set to 1.21.4 and check that the warning picks it up.
				mc.execute(() -> {
					try {
						Object impl = Class.forName("com.viaversion.viafabricplus.ViaFabricPlus").getMethod("getImpl").invoke(null);
						Class<?> pv = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
						Object old = impl.getClass().getMethod("getTargetVersion").invoke(impl);
						Object v1214 = pv.getField("v1_21_4").get(null);
						var set = Class.forName("com.viaversion.viafabricplus.api.ViaFabricPlusBase").getMethod("setTargetVersion", pv);
						set.invoke(impl, v1214);
						ru.per.jmseedmap.compat.VersionCheck.onJoin(mc);
						SeedMapClient.LOGGER.info("SELFTEST ViaFabricPlus warning: {}", ru.per.jmseedmap.compat.VersionCheck.detectedVersion());
						set.invoke(impl, old);
						ru.per.jmseedmap.compat.VersionCheck.onJoin(mc);
						SeedMapClient.LOGGER.info("SELFTEST ViaFabricPlus back to native: {}", ru.per.jmseedmap.compat.VersionCheck.detectedVersion());
					} catch (ReflectiveOperationException e) {
						SeedMapClient.LOGGER.error("SELFTEST ViaFabricPlus check failed", e);
					}
				});
				Thread.sleep(1_000L);
			}
			Thread.sleep(1_000L);
			return found != null;
		}
		GenContextProvider provider = new GenContextProvider(Executors.newFixedThreadPool(2));
		boolean ok = true;
		ok &= checkRings(server);
		ok &= check(server, provider, Level.OVERWORLD, LevelStem.OVERWORLD, -2, 1);
		ok &= check(server, provider, Level.NETHER, LevelStem.NETHER, -1, 0);
		ok &= check(server, provider, Level.END, LevelStem.END, -5, 4);
		ok &= checkBiomes(server, Level.OVERWORLD);
		ok &= checkBiomes(server, Level.NETHER);
		ok &= checkVersionParsing();
		provider.shutdown();
		return ok;
	}

	private static boolean check(
		IntegratedServer server, GenContextProvider provider, ResourceKey<Level> dim, ResourceKey<LevelStem> stem, int minTile, int maxTile
	) throws Exception {
		ServerLevel level = server.getLevel(dim);
		GenContext sp = GenContextProvider.fromServer("selftest-sp", server, dim).orElseThrow();
		// A world with data packs: the seed-only path gets the same packs, as a player would copy them from the server.
		java.nio.file.Path packs = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR);
		boolean hasPacks = GenContextProvider.packFingerprint(packs).length() > 0;
		GenContext mp = provider.fromVanilla("selftest-mp", level.getSeed(), "minecraft:normal", stem, dim,
			hasPacks ? packs : null, hasPacks ? GenContextProvider.packFingerprint(packs) : "").orElseThrow();

		long t0 = System.nanoTime();
		Set<String> predicted = predict(sp, minTile, maxTile);
		long t1 = System.nanoTime();
		Set<String> predictedMp = predict(mp, minTile, maxTile);
		long t2 = System.nanoTime();

		// Ground truth: let the server run the STRUCTURE_STARTS stage for every chunk in the area.
		int minChunk = minTile * StructureFinder.TILE_CHUNKS;
		int maxChunk = (maxTile + 1) * StructureFinder.TILE_CHUNKS - 1;
		var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Set<String> actual = CompletableFuture.supplyAsync(() -> {
			Set<String> result = new TreeSet<>();
			for (int x = minChunk; x <= maxChunk; x++) {
				for (int z = minChunk; z <= maxChunk; z++) {
					ChunkAccess chunk = level.getChunk(x, z, ChunkStatus.STRUCTURE_STARTS, true);
					for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
						if (e.getValue().isValid()) {
							String variant = StructureFinder.variantOf(e.getValue());
							result.add(structures.getKey(e.getKey()) + (variant == null ? "" : "#" + variant) + "@" + x + "," + z);
						}
					}
				}
			}
			return result;
		}, server).get();
		long t3 = System.nanoTime();

		boolean ok = report(dim + " integrated", predicted, actual) & report(dim + " seed-only", predictedMp, actual);
		SeedMapClient.LOGGER.info("SELFTEST {}: variants {}", dim.identifier(), actual.stream().filter(k -> k.contains("#")).toList());
		SeedMapClient.LOGGER.info("SELFTEST {}: {} structures; finder sp {} ms, mp {} ms, vanilla {} ms",
			dim.identifier(), actual.size(), (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000);
		return ok;
	}

	/**
	 * The biome layer samples high above the terrain; compare with the biome vanilla assigns at the actual surface
	 * height of the same columns (computed from noise, no chunks generated).
	 */
	private static boolean checkBiomes(IntegratedServer server, ResourceKey<Level> dim) {
		ServerLevel level = server.getLevel(dim);
		GenContext ctx = GenContextProvider.fromServer("selftest-biomes", server, dim).orElseThrow();
		var chunks = level.getChunkSource();
		var generator = chunks.getGenerator();
		var random = chunks.randomState();
		int sampleY = dim.equals(Level.OVERWORLD) ? ctx.heightAccessor().getMaxY() : 64;
		int match = 0;
		int total = 0;
		java.util.Map<String, Integer> mismatches = new java.util.TreeMap<>();
		long start = System.nanoTime();
		for (int tx = -3; tx <= 2; tx++) {
			for (int tz = -3; tz <= 2; tz++) {
				var tile = ru.per.jmseedmap.core.BiomeLayer.compute(ctx, tx, tz, 16, sampleY);
				for (int i = 0; i < 24; i++) {
					int cx = (i * 7) % 32;
					int cz = (i * 13 + tx * 5 + tz) % 32;
					int x = tx * 512 + cx * 16 + 8;
					int z = tz * 512 + cz * 16 + 8;
					int y = dim.equals(Level.OVERWORLD)
						? generator.getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, random)
						: 64;
					var actual = generator.getBiomeSource().getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x),
						net.minecraft.core.QuartPos.fromBlock(y), net.minecraft.core.QuartPos.fromBlock(z), random.sampler());
					String actualId = actual.unwrapKey().map(k -> k.identifier().toString()).orElse("?");
					String ours = tile.biomeAt(x, z).toString();
					total++;
					if (ours.equals(actualId)) {
						match++;
					} else {
						mismatches.merge(ours + "->" + actualId, 1, Integer::sum);
					}
				}
			}
		}
		double rate = 100.0 * match / total;
		SeedMapClient.LOGGER.info("SELFTEST biomes {}: {}/{} columns match the surface biome ({}%), {} ms; differences {}", dim.identifier(),
			match, total, String.format(java.util.Locale.ROOT, "%.1f", rate), (System.nanoTime() - start) / 1_000_000, mismatches);
		return rate >= 90.0;
	}

	private static boolean checkVersionParsing() {
		String[][] cases = {
			{"Paper 1.21.11", "1.21.11"}, {"Velocity 3.4.0 (1.7.2-26.2)", null}, {"26.2", null}, {"26.2.1", null},
			{"Spigot 1.21.4", "1.21.4"}, {"BungeeCord 1.8.x-26.2.x", null}, {"Purpur 26.1", "26.1"}, {"Fabric", null},
		};
		boolean ok = true;
		for (String[] c : cases) {
			String got = ru.per.jmseedmap.compat.VersionCheck.otherVersion(c[0], "26.2");
			boolean same = java.util.Objects.equals(got, c[1]);
			ok &= same;
			if (!same) {
				SeedMapClient.LOGGER.error("SELFTEST version parsing: '{}' -> {} (expected {})", c[0], got, c[1]);
			}
		}
		SeedMapClient.LOGGER.info("SELFTEST version parsing: {}", ok ? "OK" : "MISMATCH");
		return ok;
	}

	/** Our copy of the stronghold ring algorithm must match the server's own result exactly. */
	private static boolean checkRings(IntegratedServer server) {
		ServerLevel level = server.overworld();
		var chunks = level.getChunkSource();
		var state = chunks.getGeneratorState();
		boolean ok = true;
		for (Holder<StructureSet> set : state.possibleStructureSets()) {
			if (set.value().placement() instanceof net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement rings) {
				var vanilla = state.getRingPositionsFor(rings);
				var ours = ru.per.jmseedmap.gen.RingPositions.ringPositions(rings, level.getSeed(), chunks.getGenerator().getBiomeSource(), chunks.randomState());
				boolean same = vanilla != null && vanilla.equals(ours);
				SeedMapClient.LOGGER.info("SELFTEST rings {}: {} positions, identical: {}", set.unwrapKey().map(Object::toString).orElse("?"), ours.size(), same);
				ok &= same;
			}
		}
		return ok;
	}

	private static Set<String> predict(GenContext ctx, int minTile, int maxTile) {
		Set<String> result = new TreeSet<>();
		StringBuilder timings = new StringBuilder();
		for (Holder<StructureSet> set : ctx.structureState().possibleStructureSets()) {
			long start = System.nanoTime();
			for (int tx = minTile; tx <= maxTile; tx++) {
				for (int tz = minTile; tz <= maxTile; tz++) {
					for (FoundStructure f : StructureFinder.findInTile(ctx, set, tx, tz)) {
						result.add(f.structure().identifier() + (f.variant() == null ? "" : "#" + f.variant()) + "@" + f.chunk().x() + "," + f.chunk().z());
					}
				}
			}
			timings.append(set.unwrapKey().map(k -> k.identifier().getPath()).orElse("?"))
				.append('=').append((System.nanoTime() - start) / 1_000_000).append("ms ");
		}
		SeedMapClient.LOGGER.info("SELFTEST timings {}: {}", ctx.id(), timings);
		return result;
	}

	private static boolean report(String label, Set<String> predicted, Set<String> actual) {
		List<String> missing = new ArrayList<>(actual);
		missing.removeAll(predicted);
		List<String> extra = new ArrayList<>(predicted);
		extra.removeAll(actual);
		if (missing.isEmpty() && extra.isEmpty()) {
			SeedMapClient.LOGGER.info("SELFTEST {}: OK, {} structures match", label, actual.size());
			return true;
		}
		SeedMapClient.LOGGER.error("SELFTEST {}: MISMATCH missing={} extra={}", label, missing, extra);
		return false;
	}

	private static void screenshotMap(Minecraft mc) {
		try {
			if (Boolean.getBoolean("jm_seedmap.selftest.slime")) {
				SeedMapConfig.get().showSlimeChunks = true;
			}
			if (Boolean.getBoolean("jm_seedmap.selftest.quick")) {
				Thread.sleep(10_000L);
				screenshot(mc, "seedmap-minimap.png");
				mc.execute(() -> mc.player.setYRot(mc.player.getYRot() + 60));
				Thread.sleep(2_000L);
				screenshot(mc, "seedmap-minimap-rotated.png");
				if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
					SeedMapConfig.get().showBiomes = true;
					SeedMapConfig.get().biomesOnlyUnexplored = false;
					mc.execute(() -> openXaeroWorldMap(mc));
					Thread.sleep(10_000L);
					screenshot(mc, "seedmap-xaero-biomes.png");
					SeedMapConfig.get().biomesOnlyUnexplored = true;
					Thread.sleep(3_000L);
					screenshot(mc, "seedmap-xaero-biomes-unexplored.png");
				}
				mc.execute(mc::stop);
				return;
			}
			featureCheck(mc);
			Thread.sleep(8_000L);
			screenshot(mc, "seedmap-minimap.png");
			uiScreenshots(mc);
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
				mc.execute(() -> openXaeroWorldMap(mc));
				Thread.sleep(Long.getLong("jm_seedmap.selftest.wait", 15_000L));
				logState(mc);
				screenshot(mc, "seedmap-xaero-worldmap.png");
				mc.execute(() -> mc.gui.setScreen(null));
				Thread.sleep(1_000L);
				// Biome layer everywhere, then only where the map is still empty.
				SeedMapConfig.get().showBiomes = true;
				SeedMapConfig.get().biomesOnlyUnexplored = false;
				mc.execute(() -> openXaeroWorldMap(mc));
				Thread.sleep(10_000L);
				screenshot(mc, "seedmap-xaero-biomes.png");
				SeedMapConfig.get().biomesOnlyUnexplored = true;
				Thread.sleep(3_000L);
				screenshot(mc, "seedmap-xaero-biomes-unexplored.png");
				mc.execute(() -> mc.gui.setScreen(null));
				Thread.sleep(1_000L);
			}
			SeedMapConfig.get().showBiomes = true;
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("journeymap")) {
				for (int attempt = 0; attempt < 5 && !(mc.gui.screen() != null && mc.gui.screen().getClass().getSimpleName().equals("Fullscreen")); attempt++) {
					mc.execute(() -> {
						try {
							Class<?> ui = Class.forName("journeymap.client.ui.UIManager");
							Object instance = ui.getField("INSTANCE").get(null);
							ui.getMethod("getOrOpenFullscreenMap").invoke(instance);
						} catch (Exception e) {
							SeedMapClient.LOGGER.error("SELFTEST cannot open JourneyMap", e);
						}
					});
					Thread.sleep(3_000L);
				}
				Thread.sleep(Long.getLong("jm_seedmap.selftest.wait", 15_000L));
				logState(mc);
				screenshot(mc, "seedmap-journeymap.png");
			}
			mc.execute(() -> SeedMapClient.LOGGER.info("SELFTEST seed check: {}", ru.per.jmseedmap.core.SeedMap.get().seedCheck.describe().getString()));
			Thread.sleep(500L);
			Optional.ofNullable(System.getProperty("jm_seedmap.selftest.quit")).ifPresent(v -> mc.execute(mc::stop));
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}

	/** Biome search plus screenshots of every settings tab and the search screen. */
	private static void uiScreenshots(Minecraft mc) throws InterruptedException {
		var seedMap = ru.per.jmseedmap.core.SeedMap.get();
		mc.execute(() -> seedMap.findNearestBiome(net.minecraft.resources.Identifier.withDefaultNamespace("mushroom_fields"), "mushroom fields"));
		Thread.sleep(20_000L);
		SeedMapClient.LOGGER.info("SELFTEST nearest biome target: {}", seedMap.world() == null ? null : seedMap.world().target());
		String[] tabs = {"MAP", "STRUCTURES", "WAYPOINTS", "SEED"};
		for (String tab : tabs) {
			mc.execute(() -> {
				try {
					var field = ru.per.jmseedmap.ui.SeedMapConfigScreen.class.getDeclaredField("tab");
					field.setAccessible(true);
					Class<?> tabType = field.getType();
					field.set(null, Enum.valueOf(tabType.asSubclass(Enum.class), tab));
				} catch (ReflectiveOperationException e) {
					SeedMapClient.LOGGER.error("SELFTEST tab switch failed", e);
				}
				mc.gui.setScreen(new ru.per.jmseedmap.ui.SeedMapConfigScreen(null));
			});
			Thread.sleep(1_500L);
			screenshot(mc, "seedmap-settings-" + tab.toLowerCase(java.util.Locale.ROOT) + ".png");
		}
		mc.execute(() -> mc.gui.setScreen(new ru.per.jmseedmap.ui.PinsScreen(null)));
		Thread.sleep(1_500L);
		screenshot(mc, "seedmap-pins.png");
		mc.execute(() -> mc.gui.setScreen(new ru.per.jmseedmap.ui.SearchScreen(null)));
		Thread.sleep(1_500L);
		screenshot(mc, "seedmap-search.png");
		mc.execute(() -> {
			try {
				var field = ru.per.jmseedmap.ui.SearchScreen.class.getDeclaredField("biomesTab");
				field.setAccessible(true);
				field.setBoolean(null, true);
			} catch (ReflectiveOperationException e) {
				SeedMapClient.LOGGER.error("SELFTEST biome tab failed", e);
			}
			mc.gui.setScreen(new ru.per.jmseedmap.ui.SearchScreen(null));
		});
		Thread.sleep(1_500L);
		screenshot(mc, "seedmap-search-biomes.png");
		mc.execute(() -> mc.gui.setScreen(null));
		Thread.sleep(500L);
	}

	/** Exercises nearest search, pins and visited marks the way the UI does. */
	private static void featureCheck(Minecraft mc) throws InterruptedException {
		var seedMap = ru.per.jmseedmap.core.SeedMap.get();
		seedMap.findNearest(id -> id.startsWith("minecraft:village_"), "village");
		for (int i = 0; i < 60 && (seedMap.world() == null || seedMap.world().target() == null); i++) {
			Thread.sleep(500L);
		}
		var target = seedMap.world() == null ? null : seedMap.world().target();
		SeedMapClient.LOGGER.info("SELFTEST nearest village target: {}", target);
		CompletableFuture<List<FoundStructure>> nearby = new CompletableFuture<>();
		mc.execute(() -> nearby.complete(seedMap.index.query(mc.level.dimension(), mc.player.getX() - 600, mc.player.getZ() - 600,
			mc.player.getX() + 600, mc.player.getZ() + 600)));
		try {
			List<FoundStructure> list = nearby.get();
			SeedMapClient.LOGGER.info("SELFTEST structures within 600 blocks: {}", list.size());
			if (!list.isEmpty()) {
				mc.execute(() -> {
					seedMap.setPinned(list.get(0), true);
					if (list.size() > 1) {
						seedMap.setVisited(list.get(1), true);
					}
					SeedMapClient.LOGGER.info("SELFTEST pinned {} visited {} -> pins={}", list.get(0).key(),
						list.size() > 1 ? list.get(1).key() : "-", seedMap.pins().size());
				});
				// Walk up to another structure: it should get auto-marked as visited and show on the minimap.
				FoundStructure goal = list.get(list.size() - 1);
				var server = mc.getSingleplayerServer();
				if (server != null) {
					server.execute(() -> {
						var player = server.getPlayerList().getPlayers().get(0);
						player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
						int x = goal.pos().getX() + 20;
						int z = goal.pos().getZ();
						// Load the chunk first: an unloaded chunk reports the bottom of the world as its height.
						player.level().getChunk(x >> 4, z >> 4);
						int y = player.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
						player.teleportTo(x + 0.5, y, z + 0.5);
						SeedMapClient.LOGGER.info("SELFTEST teleported next to {}", goal.key());
					});
					Thread.sleep(6_000L);
					mc.execute(() -> SeedMapClient.LOGGER.info("SELFTEST {} visited after walking up: {}", goal.key(), seedMap.isVisited(goal)));
				}
				cacheCheck(mc, list);
			}
		} catch (Exception e) {
			SeedMapClient.LOGGER.error("SELFTEST feature check failed", e);
		}
	}

	/** Forget everything (which writes the disk cache), let it be read back and compare with what was there. */
	private static void cacheCheck(Minecraft mc, List<FoundStructure> before) throws Exception {
		var seedMap = ru.per.jmseedmap.core.SeedMap.get();
		CompletableFuture<double[]> center = new CompletableFuture<>();
		mc.execute(() -> center.complete(new double[]{mc.player.getX(), mc.player.getZ()}));
		double[] c = center.get();
		Set<String> keysBefore = new TreeSet<>();
		CompletableFuture<List<FoundStructure>> now = new CompletableFuture<>();
		mc.execute(() -> now.complete(seedMap.index.query(mc.level.dimension(), c[0] - 300, c[1] - 300, c[0] + 300, c[1] + 300)));
		now.get().forEach(s -> keysBefore.add(s.key()));
		mc.execute(seedMap.index::reset);
		Thread.sleep(2_000L);
		SeedMapClient.LOGGER.info("SELFTEST disk cache: {} KB after reset", seedMap.index.diskCacheBytes() / 1024);
		Thread.sleep(4_000L);
		Set<String> keysAfter = new TreeSet<>();
		CompletableFuture<List<FoundStructure>> again = new CompletableFuture<>();
		mc.execute(() -> again.complete(seedMap.index.query(mc.level.dimension(), c[0] - 300, c[1] - 300, c[0] + 300, c[1] + 300)));
		again.get().forEach(s -> keysAfter.add(s.key()));
		SeedMapClient.LOGGER.info("SELFTEST disk cache round trip: before {} structures, after {}, identical: {}", keysBefore.size(), keysAfter.size(),
			keysBefore.equals(keysAfter));
	}

	private static void openXaeroWorldMap(Minecraft mc) {
		try {
			Object session = Class.forName("xaero.map.WorldMapSession").getMethod("getCurrentSession").invoke(null);
			Object processor = session.getClass().getMethod("getMapProcessor").invoke(session);
			Class<?> guiMap = Class.forName("xaero.map.gui.GuiMap");
			for (var ctor : guiMap.getConstructors()) {
				if (ctor.getParameterCount() == 4) {
					mc.gui.setScreen((net.minecraft.client.gui.screens.Screen) ctor.newInstance(null, null, processor, mc.getCameraEntity()));
					return;
				}
			}
		} catch (Exception e) {
			SeedMapClient.LOGGER.error("SELFTEST cannot open Xaero's World Map", e);
		}
	}

	private static void logState(Minecraft mc) {
		mc.execute(() -> {
			var seedMap = ru.per.jmseedmap.core.SeedMap.get();
			SeedMapClient.LOGGER.info("SELFTEST map: screen={}, status={}, pending={}, pins={}",
				mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName(),
				seedMap.index.status(mc.level.dimension()), seedMap.index.pendingTiles(), seedMap.pins().size());
		});
	}

	private static void screenshot(Minecraft mc, String name) throws InterruptedException {
		mc.execute(() -> Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
			message -> SeedMapClient.LOGGER.info("SELFTEST screenshot: {}", message.getString())));
		Thread.sleep(2_000L);
	}
}
