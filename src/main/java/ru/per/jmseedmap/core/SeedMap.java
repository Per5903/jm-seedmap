package ru.per.jmseedmap.core;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.gen.FoundStructure;
import ru.per.jmseedmap.gen.GenContextProvider;
import ru.per.jmseedmap.map.MapBackend;

/**
 * Glue between the structure index, per-world state and the installed map mods.
 * <p>
 * Structures are a separate display layer; they only become real waypoints ("pins") when the player
 * asks for it, and pins live in their own group/origin in each map mod, so the player's own
 * waypoint lists stay clean and all pins can be hidden or wiped at once.
 */
public final class SeedMap {
	/** How far "nearest" searches go, in 512-block tiles. */
	private static final int NEAREST_MAX_TILES = 40;

	private static final SeedMap INSTANCE = new SeedMap();

	public final StructureIndex index = new StructureIndex();
	public final BiomeLayer biomes = new BiomeLayer(index);
	public final SeedCheck seedCheck = new SeedCheck();
	public final SeedFinder seedFinder = new SeedFinder();
	/** Tick at which to run a one-off seed check (after a seed was entered or found), or -1. */
	private int checkAt = -1;
	private final List<MapBackend> backends = new CopyOnWriteArrayList<>();
	private @Nullable WorldData world;
	private @Nullable String worldKey;
	private int revision;
	private int pinsRevision;
	private int ticks;

	public static SeedMap get() {
		return INSTANCE;
	}

	public void addBackend(MapBackend backend) {
		backends.add(backend);
		SeedMapClient.LOGGER.info("Map integration enabled: {}", backend.name());
	}

	public List<MapBackend> backends() {
		return backends;
	}

	/** Changes when anything that affects how markers look changes (toggles, visited marks...). */
	public int revision() {
		return revision;
	}

	public void invalidateVisuals() {
		StructureStyles.filtersChanged();
		revision++;
	}

	/** Changes when the set of pins changes or the world switches. */
	public int pinsRevision() {
		return pinsRevision;
	}

	public List<WorldData.Pin> pins() {
		return world == null ? List.of() : world.pins();
	}

	public @Nullable WorldData world() {
		return world;
	}

	public void tick(Minecraft mc) {
		ticks++;
		LocalPlayer player = mc.player;
		if (mc.level == null || player == null) {
			return;
		}
		ensureWorld(mc);
		SeedMapConfig config = SeedMapConfig.get();
		if (config.enabled) {
			// Keep the player's surroundings computed: feeds the minimap, visited marks and arrival checks.
			double r = Perf.current().playerRadius;
			index.requestView("player", mc.level.dimension(), player.getX() - r, player.getZ() - r, player.getX() + r, player.getZ() + r);
		}
		for (MapBackend backend : backends) {
			try {
				backend.tick(this);
			} catch (Throwable t) {
				SeedMapClient.LOGGER.error("Map integration {} failed; disabling it", backend.name(), t);
				backends.remove(backend);
			}
		}
		if (ticks % 4 == 0 && config.enabled) {
			index.tick();
		}
		if (ticks % 10 == 0) {
			trackVisits(mc, player, config);
		}
		if (checkAt >= 0 && ticks >= checkAt) {
			checkAt = -1;
			seedCheck.run(mc, index, false);
		} else if (ticks % 200 == 0 && config.seedCheckAuto) {
			seedCheck.run(mc, index, true);
		}
		if (ticks % 200 == 0 && world != null) {
			world.saveIfDirty();
		}
		if (ticks % 100 == 50) {
			ru.per.jmseedmap.compat.SeedCrackerCompat.tick(this, mc);
		}
	}

	private void ensureWorld(Minecraft mc) {
		String key = worldKey(mc);
		if (!key.equals(worldKey)) {
			if (world != null) {
				world.saveIfDirty();
			}
			worldKey = key;
			world = WorldData.load(key);
			pinsRevision++;
			revision++;
		}
	}

	private static String worldKey(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			return "sp_" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
		}
		return "mp_" + GenContextProvider.serverKey();
	}

	public void onDisconnect() {
		if (world != null) {
			world.saveIfDirty();
		}
		world = null;
		worldKey = null;
		index.reset();
		biomes.reset();
		seedCheck.reset();
		seedFinder.cancel();
		ru.per.jmseedmap.compat.SeedCrackerCompat.reset();
		ru.per.jmseedmap.compat.VersionCheck.reset();
		checkAt = -1;
		pinsRevision++;
		revision++;
	}

	public void shutdown() {
		if (world != null) {
			world.saveIfDirty();
		}
		index.shutdown();
	}

	// ---- visited ----

	public boolean isVisited(FoundStructure structure) {
		return world != null && world.isVisited(structure.key());
	}

	/** True if the structure should not be drawn at all (visited + "hide" mode). */
	public boolean isHidden(FoundStructure structure) {
		return SeedMapConfig.get().visitedMode == SeedMapConfig.VisitedMode.HIDE && isVisited(structure);
	}

	/** True if the structure should be drawn faded. */
	public boolean isDimmed(FoundStructure structure) {
		return SeedMapConfig.get().visitedMode == SeedMapConfig.VisitedMode.DIM && isVisited(structure);
	}

	public void setVisited(FoundStructure structure, boolean visited) {
		if (world != null && world.setVisited(structure.key(), visited)) {
			revision++;
		}
	}

	public int clearVisited() {
		int n = world == null ? 0 : world.clearVisited();
		revision++;
		return n;
	}

	private void trackVisits(Minecraft mc, LocalPlayer player, SeedMapConfig config) {
		if (world == null || config.visitRadius <= 0) {
			return;
		}
		double r = config.visitRadius;
		ResourceKey<Level> dim = mc.level.dimension();
		for (FoundStructure s : index.query(dim, player.getX() - r, player.getZ() - r, player.getX() + r, player.getZ() + r)) {
			if (s.distanceSqr(player.getX(), player.getZ()) <= r * r && world.setVisited(s.key(), true)) {
				revision++;
				if (config.visitedMode != SeedMapConfig.VisitedMode.SHOW) {
					player.sendOverlayMessage(Component.translatable("jm_seedmap.msg.visited", StructureStyles.displayName(s.displayId())));
				}
			}
		}
		WorldData.Pin target = world.target();
		if (target != null && config.arrivalRadius > 0 && target.dimensionKey().equals(dim)) {
			double dx = target.x() + 0.5 - player.getX();
			double dz = target.z() + 0.5 - player.getZ();
			if (dx * dx + dz * dz <= (double) config.arrivalRadius * config.arrivalRadius) {
				world.clearTarget();
				pinsRevision++;
				player.sendOverlayMessage(Component.translatable("jm_seedmap.msg.arrived", target.name()).withStyle(ChatFormatting.GREEN));
			}
		}
	}

	// ---- pins ----

	public boolean isPinned(FoundStructure structure) {
		return world != null && world.pin(structure.key()) != null;
	}

	public void setPinned(FoundStructure structure, boolean pinned) {
		if (world == null) {
			return;
		}
		if (pinned) {
			world.addPin(pinFor(structure, false));
		} else {
			world.removePin(structure.key());
		}
		pinsRevision++;
		revision++;
	}

	public void setTarget(FoundStructure structure) {
		if (world == null) {
			return;
		}
		world.addPin(pinFor(structure, true));
		pinsRevision++;
	}

	public boolean clearTarget() {
		boolean changed = world != null && world.clearTarget();
		pinsRevision++;
		return changed;
	}

	public void renamePin(WorldData.Pin pin, String name) {
		if (world != null && world.replacePin(pin, new WorldData.Pin(pin.key(), pin.dimension(), pin.x(), pin.y(), pin.z(),
			pin.structure(), name, pin.color(), pin.target()))) {
			pinsRevision++;
		}
	}

	public void removePin(WorldData.Pin pin) {
		if (world != null && world.removeExact(pin)) {
			pinsRevision++;
			revision++;
		}
	}

	/** Makes a pin the navigation target (the pin itself stays). */
	public void targetPin(WorldData.Pin pin) {
		if (world != null) {
			String name = pin.name().startsWith("→ ") ? pin.name() : "→ " + pin.name();
			world.addPin(new WorldData.Pin(pin.key(), pin.dimension(), pin.x(), pin.y(), pin.z(), pin.structure(), name, pin.color(), true));
			pinsRevision++;
		}
	}

	public int clearPins() {
		int n = world == null ? 0 : world.clearPins();
		pinsRevision++;
		revision++;
		return n;
	}

	private static WorldData.Pin pinFor(FoundStructure s, boolean target) {
		String name = StructureStyles.displayName(s.displayId());
		return new WorldData.Pin(s.key(), s.dimension().identifier().toString(), s.pos().getX(), s.pos().getY(), s.pos().getZ(),
			s.displayId(), target ? "→ " + name : name, StructureStyles.style(s.displayId()).color(), target);
	}

	// ---- nearest ----

	/**
	 * Finds the closest not-yet-visited structure accepted by {@code filter} around the player and makes it the
	 * navigation target. Reports the result in chat.
	 */
	public void findNearest(Predicate<String> filter, String label) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		ResourceKey<Level> dim = mc.level.dimension();
		double x = player.getX();
		double z = player.getZ();
		player.sendOverlayMessage(Component.translatable("jm_seedmap.msg.searching", label));
		index.nearest(dim, x, z, filter, s -> !isVisited(s), NEAREST_MAX_TILES).whenComplete((result, error) -> mc.execute(() -> {
			LocalPlayer p = mc.player;
			if (p == null) {
				return;
			}
			if (error != null || result.isEmpty()) {
				GenContextProvider.Status status = index.status(dim);
				p.sendSystemMessage(status == GenContextProvider.Status.NO_SEED
					? Component.translatable("jm_seedmap.cmd.source_none", GenContextProvider.serverKey()).withStyle(ChatFormatting.YELLOW)
					: Component.translatable("jm_seedmap.msg.not_found", label, NEAREST_MAX_TILES * 512).withStyle(ChatFormatting.YELLOW));
				return;
			}
			FoundStructure s = result.get();
			setTarget(s);
			double dx = s.pos().getX() + 0.5 - p.getX();
			double dz = s.pos().getZ() + 0.5 - p.getZ();
			int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
			p.sendSystemMessage(Component.translatable("jm_seedmap.msg.nearest", StructureStyles.displayName(s.displayId()), distance,
					Component.translatable("jm_seedmap.dir." + direction(dx, dz)), s.pos().getX(), s.pos().getZ())
				.withStyle(ChatFormatting.AQUA));
		}));
	}

	private static String direction(double dx, double dz) {
		// Minecraft: -Z is north, +X is east.
		double angle = Math.toDegrees(Math.atan2(dx, -dz));
		String[] names = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
		return names[(int) Math.floorMod(Math.round(angle / 45.0), 8L)].toLowerCase(Locale.ROOT);
	}

	/** Check the seed in ~10 seconds, when the generator is ready and chunks are loaded. */
	public void scheduleSeedCheck() {
		checkAt = ticks + 200;
	}

	// ---- seed finding ----

	/**
	 * Starts recovering this server's seed from the hash it sends. Reports progress and the result in chat;
	 * a found seed is saved for the server like a typed one.
	 *
	 * @param structureSeed lower 48 bits if known (e.g. from SeedCrackerX), otherwise null for the 32-bit search
	 */
	public void startSeedSearch(@Nullable Long structureSeed) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		if (mc.getSingleplayerServer() != null) {
			player.sendSystemMessage(Component.translatable("jm_seedmap.cmd.sp_auto"));
			return;
		}
		java.util.OptionalLong hashed = SeedCheck.serverHashedSeed(mc.level);
		if (hashed.isEmpty()) {
			player.sendSystemMessage(Component.translatable("jm_seedmap.find.no_hash").withStyle(ChatFormatting.RED));
			return;
		}
		String serverKey = GenContextProvider.serverKey();
		SeedFinder.Mode mode = structureSeed == null ? SeedFinder.Mode.INT32 : SeedFinder.Mode.STRUCTURE_SEED;
		boolean started = seedFinder.start(mode, hashed.getAsLong(), structureSeed == null ? 0 : structureSeed,
			seed -> mc.execute(() -> onSeedFound(serverKey, seed)),
			() -> mc.execute(() -> {
				if (mc.player != null) {
					mc.player.sendSystemMessage(Component.translatable(mode == SeedFinder.Mode.INT32
						? "jm_seedmap.find.not_found_int" : "jm_seedmap.find.not_found_structure").withStyle(ChatFormatting.YELLOW));
				}
			}));
		player.sendSystemMessage(Component.translatable(started
			? (mode == SeedFinder.Mode.INT32 ? "jm_seedmap.find.started_int" : "jm_seedmap.find.started_structure")
			: "jm_seedmap.find.already"));
	}

	private void onSeedFound(String serverKey, long seed) {
		SeedMapConfig.get().seeds.put(serverKey, seed);
		SeedMapConfig.save();
		resetStructures();
		scheduleSeedCheck();
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			String text = Long.toString(seed);
			player.sendSystemMessage(Component.translatable("jm_seedmap.find.found",
					Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.GREEN)
						.withClickEvent(new net.minecraft.network.chat.ClickEvent.CopyToClipboard(text))),
					seedFinder.elapsedMillis() / 1000)
				.withStyle(ChatFormatting.AQUA));
		}
	}

	// ---- biomes ----

	public void findNearestBiome(net.minecraft.resources.Identifier biome, String label) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		ResourceKey<Level> dim = mc.level.dimension();
		player.sendOverlayMessage(Component.translatable("jm_seedmap.msg.searching", label));
		index.nearestBiome(dim, player.blockPosition(), biome, mc.level).whenComplete((result, error) -> mc.execute(() -> {
			LocalPlayer p = mc.player;
			if (p == null) {
				return;
			}
			if (error != null || result.isEmpty()) {
				p.sendSystemMessage(Component.translatable("jm_seedmap.msg.biome_not_found", label).withStyle(ChatFormatting.YELLOW));
				return;
			}
			net.minecraft.core.BlockPos pos = result.get();
			if (world != null) {
				world.addPin(new WorldData.Pin("biome|" + dim.identifier() + "|" + biome, dim.identifier().toString(),
					pos.getX(), pos.getY(), pos.getZ(), "biome:" + biome, "→ " + label, 0x4FA3E0, true));
				pinsRevision++;
			}
			double dx = pos.getX() + 0.5 - p.getX();
			double dz = pos.getZ() + 0.5 - p.getZ();
			int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
			p.sendSystemMessage(Component.translatable("jm_seedmap.msg.nearest", label, distance,
				Component.translatable("jm_seedmap.dir." + direction(dx, dz)), pos.getX(), pos.getZ()).withStyle(ChatFormatting.AQUA));
		}));
	}

	// ---- layer ----

	public void toggleLayer() {
		SeedMapConfig config = SeedMapConfig.get();
		config.enabled = !config.enabled;
		SeedMapConfig.save();
		revision++;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.sendOverlayMessage(Component.translatable(config.enabled ? "jm_seedmap.cmd.enabled" : "jm_seedmap.cmd.disabled"));
		}
	}

	/** After seed/preset change: recompute everything. */
	public void resetStructures() {
		index.reset();
		biomes.reset();
		seedCheck.reset();
		revision++;
	}

	/**
	 * A seed found by another mod (SeedCrackerX). Saved for the current server like a typed one; replaces a different
	 * seed that was entered before, since a cracked seed is verified against the world.
	 */
	public void onExternalSeed(long seed, String source) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() != null || mc.level == null || !SeedMapConfig.get().seedCrackerAuto) {
			return;
		}
		String serverKey = GenContextProvider.serverKey();
		Long previous = SeedMapConfig.get().seeds.put(serverKey, seed);
		if (previous != null && previous == seed) {
			return;
		}
		SeedMapConfig.save();
		seedFinder.cancel();
		resetStructures();
		scheduleSeedCheck();
		SeedMapClient.LOGGER.info("Seed {} for {} taken from {}", seed, serverKey, source);
		if (mc.player != null) {
			String text = Long.toString(seed);
			mc.player.sendSystemMessage(Component.translatable(previous == null ? "jm_seedmap.external.seed" : "jm_seedmap.external.seed_replaced",
					source, Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.GREEN)
						.withClickEvent(new net.minecraft.network.chat.ClickEvent.CopyToClipboard(text))), previous == null ? "" : previous.toString())
				.withStyle(ChatFormatting.AQUA));
		}
	}
}
