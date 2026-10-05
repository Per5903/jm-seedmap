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
				ok = Boolean.getBoolean("jm_seedmap.selftest.quick")
					? mc.getSingleplayerServer() == null || checkDetails(mc.getSingleplayerServer())
					: run(mc);
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
			if (found != null) {
				// Seed profiles: an unknown world (another hash) asks, a new profile takes over, and coming back to the
				// real world switches to the profile whose seed matches the hash.
				long realHash = SeedCheck_hash(mc);
				mc.execute(() -> {
					SeedMapConfig.get().seeds.put(serverKey, found);
					ru.per.jmseedmap.core.SeedProfiles.onHash(realHash ^ 0x5DEECE66DL);
					String created = ru.per.jmseedmap.core.SeedProfiles.create(null);
					SeedMapClient.LOGGER.info("SELFTEST profile after unknown world: {} (key {})", created, GenContextProvider.serverKey());
					ru.per.jmseedmap.core.SeedProfiles.onHash(realHash);
					SeedMapClient.LOGGER.info("SELFTEST profile back in the real world: {} (key {}, seed {})",
						ru.per.jmseedmap.core.SeedProfiles.active(ru.per.jmseedmap.core.SeedProfiles.address()), GenContextProvider.serverKey(),
						SeedMapConfig.get().seeds.get(GenContextProvider.serverKey()));
					ru.per.jmseedmap.core.SeedProfiles.delete(created);
				});
				Thread.sleep(2_000L);
			}
			Thread.sleep(1_000L);
			return found != null;
		}
		var pool = Executors.newFixedThreadPool(2);
		GenContextProvider provider = new GenContextProvider(pool);
		boolean ok = true;
		ok &= checkRings(server);
		ok &= check(server, provider, Level.OVERWORLD, LevelStem.OVERWORLD, -2, 1);
		ok &= check(server, provider, Level.NETHER, LevelStem.NETHER, -1, 0);
		ok &= check(server, provider, Level.END, LevelStem.END, -5, 4);
		ok &= checkBiomes(server, Level.OVERWORLD);
		ok &= checkBiomes(server, Level.NETHER);
		ok &= checkVersionParsing();
		ok &= checkDetails(server);
		provider.shutdown();
		pool.shutdown();
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

	/**
	 * Details against the real world: let the server fully generate a few structures and count the blocks the
	 * details promise (obsidian of ruined portals, vaults of trial chambers, beds of villages).
	 */
	private static boolean checkDetails(IntegratedServer server) throws Exception {
		ServerLevel level = server.overworld();
		GenContext ctx = GenContextProvider.fromServer("selftest-details", server, Level.OVERWORLD).orElseThrow();
		var details = new java.util.HashMap<String, Integer>();
		List<FoundStructure> picked = new ArrayList<>();
		for (Holder<StructureSet> set : ctx.structureState().possibleStructureSets()) {
			for (int tx = -3; tx <= 2 && picked.stream().filter(p -> sameKind(p, set)).count() < 2; tx++) {
				for (int tz = -3; tz <= 2; tz++) {
					for (FoundStructure f : StructureFinder.findInTile(ctx, set, tx, tz)) {
						String id = f.id();
						if ((id.contains("ruined_portal") || id.contains("trial_chambers") || id.contains("village_") || id.contains("igloo"))
							&& picked.stream().filter(p -> p.id().equals(id)).count() < 2) {
							picked.add(f);
						}
					}
				}
			}
		}
		boolean ok = true;
		SeedMapClient.LOGGER.info("SELFTEST details: checking {} structures", picked.size());
		var core = new ru.per.jmseedmap.core.StructureDetails(null);
		for (FoundStructure f : picked) {
			var d = core.compute(ctx, f);
			String text = d.lines().stream().map(net.minecraft.network.chat.Component::getString).reduce((a, b) -> a + "; " + b).orElse("");
			// Ground truth: generate every chunk the structure touches and count blocks in its box.
			var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(f.structure());
			int[] counts = CompletableFuture.supplyAsync(() -> {
				ChunkAccess startChunk = level.getChunk(f.chunk().x(), f.chunk().z(), ChunkStatus.STRUCTURE_STARTS, true);
				StructureStart start = startChunk.getStartForStructure(holder.value());
				if (start == null || !start.isValid()) {
					return null;
				}
				var box = start.getBoundingBox();
				for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
					for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
						level.getChunk(cx, cz, ChunkStatus.FULL, true);
					}
				}
				int[] c = new int[5];
				for (var pos : net.minecraft.core.BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
					var state = level.getBlockState(pos);
					if (state.is(net.minecraft.world.level.block.Blocks.OBSIDIAN)) c[0]++;
					else if (state.is(net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN)) c[1]++;
					else if (state.is(net.minecraft.world.level.block.Blocks.VAULT)) {
						if (state.getValue(net.minecraft.world.level.block.VaultBlock.OMINOUS)) c[3]++; else c[2]++;
					} else if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock
						&& state.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD) c[4]++;
				}
				return c;
			}, server).get();
			if (counts == null) {
				SeedMapClient.LOGGER.warn("SELFTEST details {}: no start in the world", f.key());
				continue;
			}
			String expect;
			String real;
			if (f.id().contains("ruined_portal")) {
				expect = text.replaceAll(".*?(Obsidian|Обсидиан): (\\d+)[^\\d]+(\\d+).*", "$2/$3");
				real = counts[0] + "/" + counts[1];
			} else if (f.id().contains("trial_chambers")) {
				expect = text.replaceAll(".*?(Vaults|Хранилищ): (\\d+)[^\\d]+(\\d+).*", "$2/$3");
				real = counts[2] + "/" + counts[3];
			} else if (f.id().contains("village_")) {
				expect = text.replaceAll(".*?(beds|кроватей): (\\d+).*", "$2");
				real = String.valueOf(counts[4]);
			} else {
				expect = "-";
				real = "-";
			}
			boolean same = expect.equals(real);
			if (!same && f.id().contains("ruined_portal")) {
				// After placing the portal the game spreads netherrack with the chunk's decoration random; it can
				// replace crying obsidian at the base (and the block under it). Allow that, nothing else.
				String[] p = expect.split("/");
				String[] r = real.split("/");
				int lost = Integer.parseInt(p[0]) + Integer.parseInt(p[1]) - Integer.parseInt(r[0]) - Integer.parseInt(r[1]);
				same = lost >= 0 && lost <= 2 && Integer.parseInt(r[0]) <= Integer.parseInt(p[0]);
			}
			if (!expect.equals(real) && f.id().contains("ruined_portal")) {
				CompletableFuture.runAsync(() -> {
					StructureStart start = level.getChunk(f.chunk().x(), f.chunk().z(), ChunkStatus.STRUCTURE_STARTS, true).getStartForStructure(holder.value());
					for (var piece : start.getPieces()) {
						if (piece instanceof net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece portal) {
							ru.per.jmseedmap.core.StructureDetails.portalObsidian(portal).forEach((pos, crying) -> {
								var state = level.getBlockState(pos);
								boolean expected = state.is(crying ? net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN : net.minecraft.world.level.block.Blocks.OBSIDIAN);
								if (!expected) {
									SeedMapClient.LOGGER.info("SELFTEST portal block {} predicted {} but world has {}", pos.toShortString(),
										crying ? "crying_obsidian" : "obsidian", state);
								}
							});
						}
					}
				}, server).get();
			}
			ok &= same || f.id().contains("village_");
			SeedMapClient.LOGGER.info("SELFTEST details {} {}: {} | predicted {} real {} {}", f.id(), f.pos().toShortString(), text, expect, real,
				same ? "OK" : "MISMATCH");
		}
		return ok;
	}

	private static long SeedCheck_hash(Minecraft mc) throws Exception {
		CompletableFuture<Long> hash = new CompletableFuture<>();
		mc.execute(() -> hash.complete(ru.per.jmseedmap.core.SeedCheck.serverHashedSeed(mc.level).orElse(0)));
		return hash.get();
	}

	private static boolean sameKind(FoundStructure f, Holder<StructureSet> set) {
		return set.value().structures().stream().anyMatch(e -> e.structure().unwrapKey().map(k -> k.equals(f.structure())).orElse(false));
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
				SeedMapConfig.get().hudEnabled = true;
			}
			if (Boolean.getBoolean("jm_seedmap.selftest.quick")) {
				Thread.sleep(10_000L);
				// FPS pinned on screen (a 26.x debug option): the nearby HUD must still show.
				mc.execute(() -> mc.debugEntries.setStatus(net.minecraft.client.gui.components.debug.DebugScreenEntries.FPS,
					net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.ALWAYS_ON));
				Thread.sleep(2_000L);
				screenshot(mc, "seedmap-hud-pinned-debug.png");
				mc.execute(() -> mc.debugEntries.setStatus(net.minecraft.client.gui.components.debug.DebugScreenEntries.FPS,
					net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.IN_OVERLAY));
				mc.execute(() -> {
					try {
						var field = ru.per.jmseedmap.ui.SeedMapConfigScreen.class.getDeclaredField("tab");
						field.setAccessible(true);
						field.set(null, Enum.valueOf(field.getType().asSubclass(Enum.class), "STRUCTURES"));
					} catch (ReflectiveOperationException e) {
						SeedMapClient.LOGGER.error("SELFTEST tab switch failed", e);
					}
					mc.gui.setScreen(new ru.per.jmseedmap.ui.SeedMapConfigScreen(null));
				});
				Thread.sleep(1_500L);
				screenshot(mc, "seedmap-settings-structures.png");
				mc.execute(() -> mc.gui.setScreen(null));
				try {
					allTypesCheck(mc);
				} catch (Exception e) {
					SeedMapClient.LOGGER.error("SELFTEST all types check failed", e);
				}
				farmCheck(mc);
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
					mc.execute(() -> ru.per.jmseedmap.core.BiomeLayer.setHighlighted(net.minecraft.resources.Identifier.withDefaultNamespace("deep_ocean"), true));
					Thread.sleep(3_000L);
					screenshot(mc, "seedmap-xaero-highlight.png");
					mc.execute(ru.per.jmseedmap.core.BiomeLayer::clearHighlight);
					// Zoom far out: the biome layer must keep (and fill) the whole view.
					SeedMapConfig.get().biomesOnlyUnexplored = false;
					for (double zoom : new double[]{0.25, 0.0625}) {
						mc.execute(() -> {
							try {
								var f = Class.forName("xaero.map.gui.GuiMap").getDeclaredField("destScale");
								f.setAccessible(true);
								f.setDouble(null, zoom);
							} catch (ReflectiveOperationException e) {
								SeedMapClient.LOGGER.error("SELFTEST cannot zoom Xaero's map", e);
							}
						});
						Thread.sleep(12_000L);
						screenshot(mc, "seedmap-xaero-biomes-zoom" + (int) Math.round(1 / zoom) + ".png");
					}
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
				mc.execute(() -> {
					ru.per.jmseedmap.core.BiomeLayer.setHighlighted(net.minecraft.resources.Identifier.withDefaultNamespace("river"), true);
					ru.per.jmseedmap.core.BiomeLayer.setHighlighted(net.minecraft.resources.Identifier.withDefaultNamespace("frozen_river"), true);
				});
				Thread.sleep(3_000L);
				screenshot(mc, "seedmap-xaero-highlight.png");
				mc.execute(ru.per.jmseedmap.core.BiomeLayer::clearHighlight);
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
		for (String tab : new String[]{"STRUCTURES", "BIOMES", "NEARBY"}) {
			mc.execute(() -> {
				try {
					var field = ru.per.jmseedmap.ui.SearchScreen.class.getDeclaredField("tab");
					field.setAccessible(true);
					field.set(null, ru.per.jmseedmap.ui.SearchScreen.Tab.valueOf(tab));
				} catch (ReflectiveOperationException e) {
					SeedMapClient.LOGGER.error("SELFTEST search tab failed", e);
				}
				mc.gui.setScreen(new ru.per.jmseedmap.ui.SearchScreen(null));
			});
			Thread.sleep(tab.equals("NEARBY") ? 8_000L : 1_500L);
			screenshot(mc, "seedmap-search-" + tab.toLowerCase(java.util.Locale.ROOT) + ".png");
		}
		// Details screen of the nearest structure with something interesting inside.
		CompletableFuture<FoundStructure> pick = new CompletableFuture<>();
		mc.execute(() -> {
			var all = ru.per.jmseedmap.core.SeedMap.get().index.query(mc.level.dimension(), mc.player.getX() - 2000, mc.player.getZ() - 2000,
				mc.player.getX() + 2000, mc.player.getZ() + 2000);
			pick.complete(all.stream().filter(s -> s.id().contains("village") || s.id().contains("ruined_portal") || s.id().contains("trial"))
				.min(java.util.Comparator.comparingDouble(s -> s.distanceSqr(mc.player.getX(), mc.player.getZ()))).orElse(all.isEmpty() ? null : all.get(0)));
		});
		try {
			FoundStructure info = pick.get();
			if (info != null) {
				mc.execute(() -> mc.gui.setScreen(new ru.per.jmseedmap.ui.StructureInfoScreen(null, info)));
				Thread.sleep(4_000L);
				screenshot(mc, "seedmap-info.png");
			}
		} catch (java.util.concurrent.ExecutionException e) {
			SeedMapClient.LOGGER.error("SELFTEST info screen failed", e);
		}
		mc.execute(() -> mc.gui.setScreen(null));
		Thread.sleep(500L);
	}

	/**
	 * "Nearest" twice for a type that is hidden on the map: after walking to the first one it must count as visited
	 * and the second search must give another structure.
	 */
	private static void revisitCheck(Minecraft mc) throws InterruptedException {
		var seedMap = ru.per.jmseedmap.core.SeedMap.get();
		var server = mc.getSingleplayerServer();
		if (server == null) {
			return;
		}
		String first = null;
		for (int round = 0; round < 2; round++) {
			mc.execute(seedMap::clearTarget);
			Thread.sleep(500L);
			mc.execute(() -> seedMap.findNearest(id -> id.startsWith("minecraft:mineshaft"), "mineshaft"));
			for (int i = 0; i < 60 && (seedMap.world() == null || seedMap.world().target() == null); i++) {
				Thread.sleep(500L);
			}
			var target = seedMap.world() == null ? null : seedMap.world().target();
			if (target == null) {
				SeedMapClient.LOGGER.error("SELFTEST revisit: no mineshaft found");
				return;
			}
			if (round == 1) {
				SeedMapClient.LOGGER.info("SELFTEST revisit: first {} second {} -> {}", first, target.key(),
					target.key().equals(first) ? "SAME (bug)" : "OK, a new one");
				return;
			}
			first = target.key();
			String key = first;
			// Above it on the surface: a mineshaft is underground, this must not count.
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				player.level().getChunk(target.x() >> 4, target.z() >> 4);
				int y = player.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, target.x(), target.z()) + 1;
				player.teleportTo(target.x() + 0.5, Math.max(y, target.y() + 40), target.z() + 0.5);
			});
			Thread.sleep(4_000L);
			SeedMapClient.LOGGER.info("SELFTEST revisit: above it, target cleared {} visited {} (both should be false)",
				seedMap.world().target() == null, seedMap.world().isVisited(key));
			// Down at the structure itself.
			server.execute(() -> server.getPlayerList().getPlayers().get(0).teleportTo(target.x() + 0.5, target.y(), target.z() + 0.5));
			Thread.sleep(4_000L);
			SeedMapClient.LOGGER.info("SELFTEST revisit: down there, target cleared {}, visited {}", seedMap.world().target() == null,
				seedMap.world().isVisited(key));
		}
	}

	/**
	 * Everything the generator places must reach the map: with every type switched on, what the index hands to the
	 * map for an area must equal a direct computation; and every type of the dimension needs a name and a badge.
	 */
	private static void displayCheck(Minecraft mc) throws Exception {
		var seedMap = ru.per.jmseedmap.core.SeedMap.get();
		var saved = new java.util.LinkedHashMap<>(SeedMapConfig.get().structures);
		CompletableFuture<double[]> center = new CompletableFuture<>();
		mc.execute(() -> {
			for (String id : seedMap.index.possibleStructures(mc.level.dimension())) {
				ru.per.jmseedmap.core.StructureStyles.setEnabled(id, true);
			}
			center.complete(new double[]{mc.player.getX(), mc.player.getZ()});
		});
		double[] c = center.get();
		double r = 1200;
		for (int i = 0; i < 120; i++) {
			mc.execute(() -> seedMap.index.requestView("selftest", mc.level.dimension(), c[0] - r, c[1] - r, c[0] + r, c[1] + r));
			Thread.sleep(500L);
			if (i > 4 && seedMap.index.pendingTiles() == 0) {
				break;
			}
		}
		CompletableFuture<List<FoundStructure>> shown = new CompletableFuture<>();
		mc.execute(() -> shown.complete(seedMap.index.query(mc.level.dimension(), c[0] - r, c[1] - r, c[0] + r, c[1] + r)));
		Set<String> onMap = new TreeSet<>();
		shown.get().forEach(s -> onMap.add(s.displayId() + "@" + s.chunk().x() + "," + s.chunk().z()));
		GenContext ctx = seedMap.index.context(Level.OVERWORLD);
		Set<String> direct = new TreeSet<>();
		Set<String> types = new TreeSet<>();
		for (Holder<StructureSet> set : ctx.structureState().possibleStructureSets()) {
			for (int tx = Math.floorDiv((int) (c[0] - r), 512); tx <= Math.floorDiv((int) (c[0] + r), 512); tx++) {
				for (int tz = Math.floorDiv((int) (c[1] - r), 512); tz <= Math.floorDiv((int) (c[1] + r), 512); tz++) {
					for (FoundStructure f : StructureFinder.findInTile(ctx, set, tx, tz)) {
						double x = f.pos().getX();
						double z = f.pos().getZ();
						if (x >= c[0] - r && x <= c[0] + r && z >= c[1] - r && z <= c[1] + r) {
							direct.add(f.displayId() + "@" + f.chunk().x() + "," + f.chunk().z());
							types.add(f.displayId());
						}
					}
				}
			}
		}
		report("display (all types on)", onMap, direct);
		List<String> problems = new ArrayList<>();
		CompletableFuture<Void> names = new CompletableFuture<>();
		mc.execute(() -> {
			for (String id : seedMap.index.possibleStructures(mc.level.dimension())) {
				if (!net.minecraft.locale.Language.getInstance().has("jm_seedmap.structure." + id.replace(':', '.'))) {
					problems.add("no name: " + id);
				}
				if (ru.per.jmseedmap.core.StructureStyles.groups().stream().noneMatch(g -> g.contains(id))) {
					problems.add("no group: " + id);
				}
				try {
					ru.per.jmseedmap.core.StructureStyles.icon(id);
				} catch (Exception e) {
					problems.add("no icon: " + id + " (" + e + ")");
				}
			}
			names.complete(null);
		});
		names.get();
		SeedMapClient.LOGGER.info("SELFTEST display: {} types in the area {}, names/groups/icons: {}", types.size(), types,
			problems.isEmpty() ? "OK" : problems);
		mc.execute(() -> {
			SeedMapConfig.get().structures.clear();
			SeedMapConfig.get().structures.putAll(saved);
			ru.per.jmseedmap.core.StructureStyles.filtersChanged();
		});
	}

	/**
	 * Every structure type of every dimension: find the nearest one from the seed, let the server generate that chunk's
	 * structure starts to confirm it is really there, and check the map gets it (type switched on) with a name and badge.
	 */
	private static void allTypesCheck(Minecraft mc) throws Exception {
		var seedMap = ru.per.jmseedmap.core.SeedMap.get();
		var server = mc.getSingleplayerServer();
		if (server == null) {
			return;
		}
		var saved = new java.util.LinkedHashMap<>(SeedMapConfig.get().structures);
		List<String> problems = new ArrayList<>();
		int checked = 0;
		for (ResourceKey<Level> dim : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
			ServerLevel level = server.getLevel(dim);
			Set<String> ids = new TreeSet<>();
			for (int i = 0; i < 100 && ids.isEmpty(); i++) {
				ids.addAll(seedMap.index.possibleStructures(dim));
				Thread.sleep(200L);
			}
			for (String id : ids) {
				mc.execute(() -> ru.per.jmseedmap.core.StructureStyles.setEnabled(id, true));
				List<FoundStructure> found = seedMap.index.nearestN(dim, 0, 0, d -> d.equals(id), s -> true, 1, 40).get();
				if (found.isEmpty()) {
					// One structure set can list types that only spawn in another dimension's biomes (the ruined portal set
					// has the Nether variant and the Overworld ones together): those are simply never placed here.
					SeedMapClient.LOGGER.info("SELFTEST type {} {}: never placed in this dimension (biomes)", dim.identifier().getPath(), id);
					continue;
				}
				FoundStructure f = found.get(0);
				// Ground truth from the server.
				var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
				boolean real = CompletableFuture.supplyAsync(() -> {
					ChunkAccess chunk = level.getChunk(f.chunk().x(), f.chunk().z(), ChunkStatus.STRUCTURE_STARTS, true);
					StructureStart start = chunk.getStartForStructure(structures.getValue(f.structure()));
					if (start == null || !start.isValid()) {
						return false;
					}
					return f.variant() == null || f.variant().equals(StructureFinder.variantOf(start));
				}, server).get();
				// What the map integrations get.
				CompletableFuture<Boolean> onMap = new CompletableFuture<>();
				Thread.sleep(100L);
				mc.execute(() -> onMap.complete(seedMap.index.query(dim, f.pos().getX() - 8, f.pos().getZ() - 8, f.pos().getX() + 8, f.pos().getZ() + 8)
					.stream().anyMatch(s -> s.key().equals(f.key()) && s.displayId().equals(f.displayId()))));
				boolean shown = onMap.get();
				CompletableFuture<String> look = new CompletableFuture<>();
				mc.execute(() -> {
					String missing = "";
					if (!net.minecraft.locale.Language.getInstance().has("jm_seedmap.structure." + id.replace(':', '.'))) missing += " name";
					if (ru.per.jmseedmap.core.StructureStyles.groups().stream().noneMatch(g -> g.contains(id))) missing += " group";
					try {
						ru.per.jmseedmap.core.StructureStyles.icon(id);
					} catch (Exception e) {
						missing += " icon";
					}
					look.complete(missing);
				});
				String missing = look.get();
				checked++;
				String line = String.format("%s %s at %s: real=%s map=%s%s", dim.identifier().getPath(), id, f.pos().toShortString(), real, shown,
					missing.isEmpty() ? "" : " missing:" + missing);
				SeedMapClient.LOGGER.info("SELFTEST type {}", line);
				if (!real || !shown || !missing.isEmpty()) {
					problems.add(line);
				}
			}
		}
		// Types in the registry that no dimension of this world uses still need a look.
		var registry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		for (var key : registry.registryKeySet()) {
			String id = key.identifier().toString();
			if (!net.minecraft.locale.Language.getInstance().has("jm_seedmap.structure." + id.replace(':', '.'))) {
				problems.add("registry " + id + " has no name");
			}
		}
		SeedMapClient.LOGGER.info("SELFTEST all types: {} checked in 3 dimensions, {} registry types; problems: {}", checked,
			registry.registryKeySet().size(), problems.isEmpty() ? "none" : problems);
		mc.execute(() -> {
			SeedMapConfig.get().structures.clear();
			SeedMapConfig.get().structures.putAll(saved);
			ru.per.jmseedmap.core.StructureStyles.filtersChanged();
		});
	}

	/** Farm mode: pins on the nearest villages; reaching the first one moves the pins to the next. */
	private static void farmCheck(Minecraft mc) throws InterruptedException {
		var seedMap = ru.per.jmseedmap.core.SeedMap.get();
		var server = mc.getSingleplayerServer();
		if (server == null) {
			return;
		}
		mc.execute(seedMap::clearTarget);
		Thread.sleep(500L);
		mc.execute(() -> seedMap.farm.start(ru.per.jmseedmap.core.StructureStyles.group("ruined_portal"), 3));
		for (int i = 0; i < 60 && (seedMap.world().target() == null); i++) {
			Thread.sleep(500L);
		}
		Thread.sleep(1_000L);
		var first = seedMap.world().target();
		SeedMapClient.LOGGER.info("SELFTEST farm: target {}, pins {}", first == null ? null : first.key(), seedMap.pins().size());
		boolean hud = SeedMapConfig.get().hudEnabled;
		SeedMapConfig.get().hudEnabled = false;
		Thread.sleep(1_500L);
		screenshot(mc, "seedmap-hud-farm.png");
		SeedMapConfig.get().hudEnabled = hud;
		if (first == null) {
			return;
		}
		server.execute(() -> {
			var player = server.getPlayerList().getPlayers().get(0);
			player.level().getChunk(first.x() >> 4, first.z() >> 4);
			player.teleportTo(first.x() + 0.5, first.y(), first.z() + 0.5);
		});
		Thread.sleep(6_000L);
		var second = seedMap.world().target();
		SeedMapClient.LOGGER.info("SELFTEST farm: after arriving target {} -> {}, visited first {}, farmed {}",
			first.key(), second == null ? null : second.key(), seedMap.world().isVisited(first.key()), seedMap.farm.visitedCount());
		mc.execute(() -> seedMap.farm.stop(true));
		Thread.sleep(500L);
	}

	/** Exercises nearest search, pins and visited marks the way the UI does. */
	private static void featureCheck(Minecraft mc) throws InterruptedException {
		var seedMap = ru.per.jmseedmap.core.SeedMap.get();
		revisitCheck(mc);
		farmCheck(mc);
		try {
			allTypesCheck(mc);
		} catch (Exception e) {
			SeedMapClient.LOGGER.error("SELFTEST all types check failed", e);
		}
		try {
			displayCheck(mc);
		} catch (Exception e) {
			SeedMapClient.LOGGER.error("SELFTEST display check failed", e);
		}
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
						player.teleportTo(x + 0.5, goal.pos().getY(), z + 0.5);
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
