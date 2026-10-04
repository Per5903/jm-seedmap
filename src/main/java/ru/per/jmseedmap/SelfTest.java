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

	static void onJoin(Minecraft mc) {
		if (!ENABLED || started) {
			return;
		}
		started = true;
		Thread thread = new Thread(() -> {
			boolean ok = false;
			try {
				ok = run(mc);
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
			// Multiplayer: behave like a player typing /seedmap seed <seed>, then let the map fill.
			long seed = Long.getLong("jm_seedmap.selftest.seed", 12345L);
			mc.execute(() -> mc.player.connection.sendCommand("seedmap seed " + seed));
			Thread.sleep(2_000L);
			boolean ok = Long.valueOf(seed).equals(SeedMapConfig.get().seeds.get(GenContextProvider.serverKey()));
			SeedMapClient.LOGGER.info("SELFTEST multiplayer seed stored for {}: {}", GenContextProvider.serverKey(), ok);
			return ok;
		}
		GenContextProvider provider = new GenContextProvider(Executors.newFixedThreadPool(2));
		boolean ok = true;
		ok &= check(server, provider, Level.OVERWORLD, LevelStem.OVERWORLD, -2, 1);
		ok &= check(server, provider, Level.NETHER, LevelStem.NETHER, -1, 0);
		ok &= check(server, provider, Level.END, LevelStem.END, -3, 2);
		provider.shutdown();
		return ok;
	}

	private static boolean check(
		IntegratedServer server, GenContextProvider provider, ResourceKey<Level> dim, ResourceKey<LevelStem> stem, int minTile, int maxTile
	) throws Exception {
		ServerLevel level = server.getLevel(dim);
		GenContext sp = GenContextProvider.fromServer("selftest-sp", server, dim).orElseThrow();
		GenContext mp = provider.fromVanilla("selftest-mp", level.getSeed(), "minecraft:normal", stem, dim).orElseThrow();

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
							result.add(structures.getKey(e.getKey()) + "@" + x + "," + z);
						}
					}
				}
			}
			return result;
		}, server).get();
		long t3 = System.nanoTime();

		boolean ok = report(dim + " integrated", predicted, actual) & report(dim + " seed-only", predictedMp, actual);
		SeedMapClient.LOGGER.info("SELFTEST {}: {} structures; finder sp {} ms, mp {} ms, vanilla {} ms",
			dim.identifier(), actual.size(), (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000);
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
						result.add(f.structure().identifier() + "@" + f.chunk().x() + "," + f.chunk().z());
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
			featureCheck(mc);
			Thread.sleep(8_000L);
			screenshot(mc, "seedmap-minimap.png");
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
				mc.execute(() -> openXaeroWorldMap(mc));
				Thread.sleep(Long.getLong("jm_seedmap.selftest.wait", 15_000L));
				logState(mc);
				screenshot(mc, "seedmap-xaero-worldmap.png");
				mc.execute(() -> mc.gui.setScreen(null));
				Thread.sleep(1_000L);
			}
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
			Optional.ofNullable(System.getProperty("jm_seedmap.selftest.quit")).ifPresent(v -> mc.execute(mc::stop));
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
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
						int y = player.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
						player.teleportTo(x + 0.5, y, z + 0.5);
						SeedMapClient.LOGGER.info("SELFTEST teleported next to {}", goal.key());
					});
					Thread.sleep(6_000L);
					mc.execute(() -> SeedMapClient.LOGGER.info("SELFTEST {} visited after walking up: {}", goal.key(), seedMap.isVisited(goal)));
				}
			}
		} catch (Exception e) {
			SeedMapClient.LOGGER.error("SELFTEST feature check failed", e);
		}
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
