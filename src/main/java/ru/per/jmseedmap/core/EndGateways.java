package ru.per.jmseedmap.core;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.gen.FoundStructure;
import ru.per.jmseedmap.gen.GenContext;

/**
 * The End gateways on the outer islands that lead back to the main island. Each of the 20 gateways around the main
 * island creates its partner the first time it is used, about 1024 blocks further out in the same direction; where
 * exactly follows from the terrain, which follows from the seed:
 * <ol>
 *   <li>from 1024 blocks out, step back (16 blocks at a time) past chunks with blocks, then forward to the first
 *       chunk that has blocks;</li>
 *   <li>in that chunk the end stone with two free blocks above that is closest to (0, 0, 0);</li>
 *   <li>the tallest block within 16 blocks of it; the gateway is 10 blocks above that.</li>
 * </ol>
 * (TheEndGatewayBlockEntity#findOrCreateValidTeleportPos.) The terrain comes from the noise generator, and the
 * small islands the decoration step scatters over the void are replayed too ({@link Terrain}).
 * <p>
 * The main island's gateways open one per dragon kill, in an order shuffled with the world seed
 * (EnderDragonFight), so each one also gets its number.
 */
public final class EndGateways {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("jm_seedmap", "end_gateway");
	public static final ResourceKey<Structure> KEY = ResourceKey.create(Registries.STRUCTURE, ID);

	/** @param dragon after which dragon kill this pair of gateways opens (1-20) */
	public record Gateway(int dragon, BlockPos inner, BlockPos exit) {
	}

	private static final Map<String, Gateway> BY_KEY = new ConcurrentHashMap<>();

	private EndGateways() {
	}

	public static boolean isEnd(GenContext ctx) {
		return ctx.generator().getBiomeSource() instanceof TheEndBiomeSource;
	}

	/** The gateway behind a marker from {@link #compute}, or null. */
	public static @Nullable Gateway of(FoundStructure structure) {
		return BY_KEY.get(structure.key());
	}

	/** Gateway indices (0-19) in the order they open: after the first dragon, the second... */
	public static List<Integer> openingOrder(long seed) {
		ObjectArrayList<Integer> gateways = new ObjectArrayList<>();
		for (int i = 0; i < 20; i++) {
			gateways.add(i);
		}
		Util.shuffle(gateways, RandomSource.createThreadLocalInstance(seed));
		// The fight takes them from the end of the list.
		List<Integer> order = new ArrayList<>(gateways);
		java.util.Collections.reverse(order);
		return order;
	}

	public static BlockPos innerPos(int index) {
		int x = Mth.floor(96.0 * Math.cos(2.0 * (-Math.PI + (Math.PI / 20) * index)));
		int z = Mth.floor(96.0 * Math.sin(2.0 * (-Math.PI + (Math.PI / 20) * index)));
		return new BlockPos(x, 75, z);
	}

	/** All 20 outer gateways as map markers. Takes a moment (terrain heights); call off the render thread. */
	public static List<FoundStructure> compute(GenContext ctx) {
		List<FoundStructure> out = new ArrayList<>();
		Terrain terrain = new Terrain(ctx);
		List<Integer> order = openingOrder(ctx.seed());
		for (int dragon = 1; dragon <= order.size(); dragon++) {
			BlockPos inner = innerPos(order.get(dragon - 1));
			BlockPos exit = exitFor(terrain, inner);
			FoundStructure marker = new FoundStructure(ctx.dimension(), KEY, new ChunkPos(exit.getX() >> 4, exit.getZ() >> 4), exit, null);
			BY_KEY.put(marker.key(), new Gateway(dragon, inner, exit));
			out.add(marker);
		}
		return out;
	}

	/** For the selftest: the intermediate steps of the search as text. */
	public static String debug(GenContext ctx, BlockPos inner) {
		Terrain terrain = new Terrain(ctx);
		Vec3 direction = new Vec3(inner.getX(), 0.0, inner.getZ()).normalize();
		Vec3 pos = direction.scale(1024.0);
		for (int limit = 16; !terrain.chunkEmpty(pos) && limit-- > 0; pos = pos.add(direction.scale(-16.0))) {
		}
		for (int limit = 16; terrain.chunkEmpty(pos) && limit-- > 0; pos = pos.add(direction.scale(16.0))) {
		}
		BlockPos spawn = terrain.validSpawnInChunk(Mth.floor(pos.x / 16.0), Mth.floor(pos.z / 16.0));
		if (spawn != null) {
			terrain.addEndCitiesNear(spawn);
		}
		BlockPos tallest = spawn == null ? null : terrain.tallest(spawn, 16);
		return "tentative " + Math.round(pos.x) + "," + Math.round(pos.z) + " spawn " + spawn + " tallest " + tallest;
	}

	/** Gateways read back from the disk cache (in opening order): remember what each marker is. */
	static void register(GenContext ctx, List<FoundStructure> markers) {
		List<Integer> order = openingOrder(ctx.seed());
		for (int i = 0; i < markers.size() && i < order.size(); i++) {
			FoundStructure marker = markers.get(i);
			BY_KEY.put(marker.key(), new Gateway(i + 1, innerPos(order.get(i)), marker.pos()));
		}
	}

	static BlockPos exitFor(Terrain terrain, BlockPos inner) {
		Vec3 direction = new Vec3(inner.getX(), 0.0, inner.getZ()).normalize();
		Vec3 pos = direction.scale(1024.0);
		for (int limit = 16; !terrain.chunkEmpty(pos) && limit-- > 0; pos = pos.add(direction.scale(-16.0))) {
		}
		for (int limit = 16; terrain.chunkEmpty(pos) && limit-- > 0; pos = pos.add(direction.scale(16.0))) {
		}
		BlockPos spawn = terrain.validSpawnInChunk(Mth.floor(pos.x / 16.0), Mth.floor(pos.z / 16.0));
		if (spawn == null) {
			// The game places a small island here with its top at y=75.
			spawn = BlockPos.containing(pos.x + 0.5, 75.0, pos.z + 0.5);
		}
		// End cities stand on these islands, and their towers are the tallest blocks around.
		terrain.addEndCitiesNear(spawn);
		BlockPos tallest = terrain.tallest(spawn, 16);
		return tallest.above(10);
	}

	/**
	 * The End's blocks as far as the gateway search sees them: noise terrain (from the generator) plus the small
	 * islands the decoration step scatters over the void (replayed: same random, same placement rules).
	 */
	static final class Terrain {
		private final GenContext ctx;
		private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap tops = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> islands = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
		private final it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap empty = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
		private final int minY;
		private final @Nullable PlacedFeature islandFeature;
		private final int islandIndex;
		private final int islandStep;
		/** Natural End gateways (end_gateway_return): bedrock shells a few blocks above the highlands. */
		private final @Nullable PlacedFeature gatewayFeature;
		private final int gatewayIndex;
		private final int gatewayStep;
		private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> naturalGateways = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
		private final BiomeManager biomes;

		Terrain(GenContext ctx) {
			this.ctx = ctx;
			this.minY = ctx.heightAccessor().getMinY();
			tops.defaultReturnValue(Integer.MIN_VALUE);
			var source = ctx.generator().getBiomeSource();
			var sampler = ctx.randomState().sampler();
			this.biomes = new BiomeManager((qx, qy, qz) -> source.getNoiseBiome(qx, qy, qz, sampler), BiomeManager.obfuscateSeed(ctx.seed()));
			var placed = ctx.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
			int[] island = featureIndex(placed.getValue(Identifier.withDefaultNamespace("end_island_decorated")));
			this.islandFeature = island == null ? null : placed.getValue(Identifier.withDefaultNamespace("end_island_decorated"));
			this.islandIndex = island == null ? -1 : island[0];
			this.islandStep = island == null ? -1 : island[1];
			int[] gateway = featureIndex(placed.getValue(Identifier.withDefaultNamespace("end_gateway_return")));
			this.gatewayFeature = gateway == null ? null : placed.getValue(Identifier.withDefaultNamespace("end_gateway_return"));
			this.gatewayIndex = gateway == null ? -1 : gateway[0];
			this.gatewayStep = gateway == null ? -1 : gateway[1];
		}

		/** (index, step) of a placed feature in the generator's decoration order, or null. */
		private @Nullable int[] featureIndex(@Nullable PlacedFeature feature) {
			if (feature == null) {
				return null;
			}
			try {
				java.lang.reflect.Field field = net.minecraft.world.level.chunk.ChunkGenerator.class.getDeclaredField("featuresPerStep");
				field.setAccessible(true);
				@SuppressWarnings("unchecked")
				var steps = ((java.util.function.Supplier<List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData>>) field.get(ctx.generator())).get();
				for (int i = 0; i < steps.size(); i++) {
					if (steps.get(i).features().contains(feature)) {
						return new int[]{steps.get(i).indexMapping().applyAsInt(feature), i};
					}
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				ru.per.jmseedmap.SeedMapClient.LOGGER.warn("End decorations cannot be replayed; gateways are approximate", e);
			}
			return null;
		}

		/**
		 * The natural gateway of chunk (cx, cz) as its center (x, y, z), or empty: end_gateway_return is rarity 1/700,
		 * a random spot in the chunk, on the surface (MOTION_BLOCKING) plus 3-9 blocks, only in the End highlands.
		 */
		int[] naturalGateway(int cx, int cz) {
			long key = ChunkPos.pack(cx, cz);
			int[] cached = naturalGateways.get(key);
			if (cached != null) {
				return cached;
			}
			int[] out = new int[0];
			if (gatewayFeature != null) {
				WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
				long decorationSeed = random.setDecorationSeed(ctx.seed(), cx * 16, cz * 16);
				random.setFeatureSeed(decorationSeed, gatewayIndex, gatewayStep);
				if (random.nextFloat() < 1.0F / 700) {
					int x = random.nextInt(16) + cx * 16;
					int z = random.nextInt(16) + cz * 16;
					int surface = groundTop(x, z);
					if (surface >= minY) {
						int y = surface + 1 + random.nextInt(9 - 3 + 1) + 3;
						if (biomes.getBiome(new BlockPos(x, y, z)).value().getGenerationSettings().hasFeature(gatewayFeature)) {
							out = new int[]{x, y, z};
						}
					}
				}
			}
			naturalGateways.put(key, out);
			return out;
		}

		/** Terrain and small islands only (what is there when the natural gateways are placed). */
		private int groundTop(int x, int z) {
			int top = noiseTop(x, z);
			int cx = x >> 4;
			int cz = z >> 4;
			for (int ox = -1; ox <= 1; ox++) {
				for (int oz = -1; oz <= 1; oz++) {
					int[] list = islands(cx + ox, cz + oz);
					for (int i = 0; i < list.length; i += 4) {
						if (covers(list, i, x, z)) {
							top = Math.max(top, list[i + 1]);
						}
					}
				}
			}
			return top;
		}

		/** Bedrock of a natural gateway in this column: top at center y+2, on the four sides y+1. */
		private int gatewayTop(int x, int z) {
			int top = Integer.MIN_VALUE;
			for (int ox = -1; ox <= 1; ox++) {
				for (int oz = -1; oz <= 1; oz++) {
					int[] g = naturalGateway((x >> 4) + ox, (z >> 4) + oz);
					if (g.length == 0) {
						continue;
					}
					int dx = Math.abs(x - g[0]);
					int dz = Math.abs(z - g[2]);
					if (dx == 0 && dz == 0) {
						top = Math.max(top, g[1] + 2);
					} else if (dx + dz == 1) {
						top = Math.max(top, g[1] + 1);
					}
				}
			}
			return top;
		}

		/** Y of the top block of the column, or below minY if it is empty. */
		int top(int x, int z) {
			long key = ChunkPos.pack(x, z);
			int cached = tops.get(key);
			if (cached != Integer.MIN_VALUE) {
				return cached;
			}
			int top = Math.max(noiseTop(x, z), structureTops.get(key));
			int cx = x >> 4;
			int cz = z >> 4;
			for (int ox = -1; ox <= 1; ox++) {
				for (int oz = -1; oz <= 1; oz++) {
					int[] list = islands(cx + ox, cz + oz);
					for (int i = 0; i < list.length; i += 4) {
						if (covers(list, i, x, z)) {
							top = Math.max(top, list[i + 1]);
						}
					}
				}
			}
			top = Math.max(top, gatewayTop(x, z));
			tops.put(key, top);
			return top;
		}

		private int noiseTop(int x, int z) {
			return ctx.generator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, ctx.heightAccessor(), ctx.randomState()) - 1;
		}

		private static boolean covers(int[] list, int i, int x, int z) {
			int dx = x - list[i];
			int dz = z - list[i + 2];
			int size = list[i + 3];
			return Math.abs(dx) <= size && Math.abs(dz) <= size && dx * dx + dz * dz <= (size + 1) * (size + 1);
		}

		/**
		 * Small islands placed by the decoration of chunk (cx, cz), as (x, y, z, size) quadruples. Same random as
		 * ChunkGenerator#applyBiomeDecoration, same rules as end_island_decorated: rarity 1/14, 1 or 2 islands,
		 * random spot in the chunk at y 55-70, only in a biome that has the feature (EndIslandFeature for the shape).
		 */
		int[] islands(int cx, int cz) {
			long key = ChunkPos.pack(cx, cz);
			int[] cached = islands.get(key);
			if (cached != null) {
				return cached;
			}
			int[] out = new int[0];
			if (islandFeature != null) {
				WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
				long decorationSeed = random.setDecorationSeed(ctx.seed(), cx * 16, cz * 16);
				random.setFeatureSeed(decorationSeed, islandIndex, islandStep);
				if (random.nextFloat() < 1.0F / 14) {
					int count = random.nextInt(4) < 3 ? 1 : 2;
					List<Integer> found = new ArrayList<>();
					for (int n = 0; n < count; n++) {
						int x = random.nextInt(16) + cx * 16;
						int z = random.nextInt(16) + cz * 16;
						int y = random.nextInt(70 - 55 + 1) + 55;
						if (!biomes.getBiome(new BlockPos(x, y, z)).value().getGenerationSettings().hasFeature(islandFeature)) {
							continue;
						}
						float size = random.nextInt(3) + 4.0F;
						found.add(x);
						found.add(y);
						found.add(z);
						found.add((int) size);
						while (size > 0.5F) {
							size -= random.nextInt(2) + 0.5F;
						}
					}
					out = found.stream().mapToInt(Integer::intValue).toArray();
				}
			}
			islands.put(key, out);
			return out;
		}

		/**
		 * Whether the chunk has no blocks at all. The noise is interpolated between cell corners 8 blocks apart, so a
		 * chunk whose own corners are all empty can only have terrain coming in from the next cells: only then all
		 * 256 columns are checked.
		 */
		boolean chunkEmpty(Vec3 pos) {
			int cx = Mth.floor(pos.x / 16.0);
			int cz = Mth.floor(pos.z / 16.0);
			long key = ChunkPos.pack(cx, cz);
			if (empty.containsKey(key)) {
				return empty.get(key);
			}
			boolean result = computeEmpty(cx, cz);
			empty.put(key, result);
			return result;
		}

		private boolean computeEmpty(int cx, int cz) {
			for (int ox = -1; ox <= 1; ox++) {
				for (int oz = -1; oz <= 1; oz++) {
					int[] list = islands(cx + ox, cz + oz);
					for (int i = 0; i < list.length; i += 4) {
						int size = list[i + 3];
						if (list[i] + size >= cx * 16 && list[i] - size < cx * 16 + 16 && list[i + 2] + size >= cz * 16 && list[i + 2] - size < cz * 16 + 16) {
							return false;
						}
					}
				}
			}
			boolean outerTerrain = false;
			for (int x = 0; x <= 16; x += 8) {
				for (int z = 0; z <= 16; z += 8) {
					if (noiseTop(cx * 16 + x, cz * 16 + z) >= minY) {
						if (x < 16 && z < 16) {
							return false;
						}
						outerTerrain = true;
					}
				}
			}
			if (!outerTerrain) {
				return true;
			}
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					if (noiseTop(cx * 16 + x, cz * 16 + z) >= minY) {
						return false;
					}
				}
			}
			return true;
		}

		/**
		 * Where the game's findValidSpawnInChunk ends up. It means to return the end stone (with two free blocks above,
		 * y >= 30) closest to (0, 0, 0), but it keeps a reference to the loop's mutable position, so what it returns is
		 * the last position of the loop: the chunk's max x and z, at the top of its highest non-empty section. Only
		 * x and z matter after that (the tallest-block search ignores y). Null if the chunk has no such end stone.
		 */
		@Nullable BlockPos validSpawnInChunk(int cx, int cz) {
			int highest = Integer.MIN_VALUE;
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					highest = Math.max(highest, top(cx * 16 + x, cz * 16 + z));
				}
			}
			if (highest < 30) {
				return null;
			}
			return new BlockPos(cx * 16 + 15, Math.floorDiv(highest, 16) * 16 + 15, cz * 16 + 15);
		}

		/** Highest block within {@code dist} of {@code around}, first one wins on ties, like findTallestBlock. */
		BlockPos tallest(BlockPos around, int dist) {
			BlockPos tallest = null;
			for (int xd = -dist; xd <= dist; xd++) {
				for (int zd = -dist; zd <= dist; zd++) {
					int x = around.getX() + xd;
					int z = around.getZ() + zd;
					// Only a column that can beat the current best is worth its exact (slow) height.
					if (tallest != null && upperBound(x, z) <= tallest.getY()) {
						continue;
					}
					int y = top(x, z);
					if (y >= minY && (tallest == null || y > tallest.getY())) {
						tallest = new BlockPos(x, y, z);
					}
				}
			}
			return tallest == null ? around : tallest;
		}

		/**
		 * The terrain is interpolated between noise cell corners (8 blocks apart, 4 blocks high in the End): above
		 * the cell layer that holds the highest of the four surrounding corner columns' tops, every corner is air,
		 * so nothing in between can be higher than that layer's last block. Small islands are added exactly.
		 */
		private int upperBound(int x, int z) {
			int x0 = Math.floorDiv(x, 8) * 8;
			int z0 = Math.floorDiv(z, 8) * 8;
			int corners = Math.max(Math.max(cornerTop(x0, z0), cornerTop(x0 + 8, z0)), Math.max(cornerTop(x0, z0 + 8), cornerTop(x0 + 8, z0 + 8)));
			int bound = corners < minY ? minY - 1 : Math.floorDiv(corners, 4) * 4 + 3;
			bound = Math.max(bound, structureTops.get(ChunkPos.pack(x, z)));
			bound = Math.max(bound, gatewayTop(x, z));
			int cx = x >> 4;
			int cz = z >> 4;
			for (int ox = -1; ox <= 1; ox++) {
				for (int oz = -1; oz <= 1; oz++) {
					int[] list = islands(cx + ox, cz + oz);
					for (int i = 0; i < list.length; i += 4) {
						if (covers(list, i, x, z)) {
							bound = Math.max(bound, list[i + 1]);
						}
					}
				}
			}
			return bound;
		}

		private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap corners = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		/** Highest full block of End city pieces per column. */
		private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap structureTops = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		private final java.util.Set<String> citiesDone = new java.util.HashSet<>();
		private static final java.lang.reflect.Field PALETTES = paletteField();

		{
			structureTops.defaultReturnValue(Integer.MIN_VALUE);
		}

		private static java.lang.reflect.@Nullable Field paletteField() {
			try {
				java.lang.reflect.Field f = net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.class.getDeclaredField("palettes");
				f.setAccessible(true);
				return f;
			} catch (ReflectiveOperationException e) {
				return null;
			}
		}

		/**
		 * Adds the full blocks of every End city whose start is within two tiles of {@code pos}: the pieces are
		 * assembled like the game does and their templates placed (on paper) with the same transform.
		 */
		void addEndCitiesNear(BlockPos pos) {
			var sets = ctx.structureState().possibleStructureSets();
			int tx = Math.floorDiv(pos.getX(), ru.per.jmseedmap.gen.StructureFinder.TILE_BLOCKS);
			int tz = Math.floorDiv(pos.getZ(), ru.per.jmseedmap.gen.StructureFinder.TILE_BLOCKS);
			for (var set : sets) {
				boolean city = set.value().structures().stream()
					.anyMatch(e -> e.structure().unwrapKey().map(k -> k.identifier().getPath().equals("end_city")).orElse(false));
				if (!city) {
					continue;
				}
				for (int x = tx - 1; x <= tx + 1; x++) {
					for (int z = tz - 1; z <= tz + 1; z++) {
						if (!citiesDone.add(x + "," + z)) {
							continue;
						}
						for (FoundStructure found : ru.per.jmseedmap.gen.StructureFinder.findInTile(ctx, set, x, z)) {
							addCity(found);
						}
					}
				}
			}
		}

		private void addCity(FoundStructure found) {
			var holder = ctx.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(found.structure());
			var structure = holder.value();
			var start = structure.generate(holder, ctx.dimension(), ctx.registryAccess(), ctx.generator(), ctx.generator().getBiomeSource(),
				ctx.randomState(), ctx.templates(), ctx.structureState().getLevelSeed(), found.chunk(), 0, ctx.heightAccessor(), structure.biomes()::contains);
			if (!start.isValid() || PALETTES == null) {
				return;
			}
			for (var piece : start.getPieces()) {
				if (!(piece instanceof net.minecraft.world.level.levelgen.structure.TemplateStructurePiece templatePiece)) {
					continue;
				}
				try {
					var settings = (net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings) PIECE_SETTINGS.get(templatePiece);
					BlockPos origin = (BlockPos) PIECE_POSITION.get(templatePiece);
					@SuppressWarnings("unchecked")
					var palettes = (List<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.Palette>) PALETTES.get(templatePiece.template());
					if (palettes.isEmpty()) {
						continue;
					}
					for (var info : settings.getRandomPalette(palettes, origin).blocks()) {
						if (!info.state().isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
							continue;
						}
						BlockPos world = net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
							.calculateRelativePosition(settings, info.pos()).offset(origin);
						long key = ChunkPos.pack(world.getX(), world.getZ());
						if (world.getY() > structureTops.get(key)) {
							structureTops.put(key, world.getY());
							tops.remove(key);
						}
					}
				} catch (ReflectiveOperationException e) {
					return;
				}
			}
		}

		private static final java.lang.reflect.Field PIECE_SETTINGS = pieceField("placeSettings");
		private static final java.lang.reflect.Field PIECE_POSITION = pieceField("templatePosition");

		private static java.lang.reflect.Field pieceField(String name) {
			try {
				java.lang.reflect.Field f = net.minecraft.world.level.levelgen.structure.TemplateStructurePiece.class.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		private int cornerTop(int x, int z) {
			long key = ChunkPos.pack(x, z);
			if (corners.containsKey(key)) {
				return corners.get(key);
			}
			int top = noiseTop(x, z);
			corners.put(key, top);
			return top;
		}
	}
}
