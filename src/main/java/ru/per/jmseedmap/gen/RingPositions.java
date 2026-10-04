package ru.per.jmseedmap.gen;

import com.mojang.datafixers.util.Pair;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import ru.per.jmseedmap.SeedMapClient;

/**
 * Fills a {@link ChunkGeneratorStructureState} (stronghold rings and structure placements) without calling
 * {@code ensureStructuresGenerated()}. Needed because some performance mods (ModernFix) move the ring
 * calculation onto a server-only executor, which does not exist on a multiplayer client. Same algorithm as
 * vanilla {@code generatePositions} / {@code generateRingPositions}, computed on the calling thread.
 */
public final class RingPositions {
	private RingPositions() {
	}

	/** Vanilla first; our own copy of the algorithm if a mod broke it. */
	static void ensure(ChunkGeneratorStructureState state, BiomeSource biomeSource, RandomState randomState) {
		try {
			state.ensureStructuresGenerated();
			return;
		} catch (Throwable t) {
			SeedMapClient.LOGGER.warn("Vanilla structure position setup failed ({}), computing stronghold rings ourselves", t.toString());
		}
		try {
			long start = System.nanoTime();
			fill(state, biomeSource, randomState);
			SeedMapClient.LOGGER.info("Stronghold rings computed in {} ms", (System.nanoTime() - start) / 1_000_000);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot set up structure positions", e);
		}
	}

	@SuppressWarnings("unchecked")
	private static void fill(ChunkGeneratorStructureState state, BiomeSource biomeSource, RandomState randomState)
		throws ReflectiveOperationException {
		Class<ChunkGeneratorStructureState> type = ChunkGeneratorStructureState.class;
		Map<Structure, List<StructurePlacement>> placements = (Map<Structure, List<StructurePlacement>>) field(type, "placementsForStructure").get(state);
		Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>> rings =
			(Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>>) field(type, "ringPositions").get(state);
		long ringsSeed = field(type, "concentricRingsSeed").getLong(state);

		// A failed vanilla attempt may have left these half filled.
		placements.clear();
		rings.clear();
		Set<Holder<Biome>> possibleBiomes = biomeSource.possibleBiomes();
		for (Holder<StructureSet> setHolder : state.possibleStructureSets()) {
			StructureSet set = setHolder.value();
			boolean placeable = false;
			for (StructureSet.StructureSelectionEntry entry : set.structures()) {
				Structure structure = entry.structure().value();
				if (structure.biomes().stream().anyMatch(possibleBiomes::contains)) {
					placements.computeIfAbsent(structure, s -> new ArrayList<>()).add(set.placement());
					placeable = true;
				}
			}
			if (placeable && set.placement() instanceof ConcentricRingsStructurePlacement ringsPlacement) {
				rings.put(ringsPlacement, CompletableFuture.completedFuture(ringPositions(ringsPlacement, ringsSeed, biomeSource, randomState)));
			}
		}
		field(type, "hasGeneratedPositions").setBoolean(state, true);
	}

	public static List<ChunkPos> ringPositions(ConcentricRingsStructurePlacement placement, long seed, BiomeSource biomeSource, RandomState randomState) {
		int count = placement.count();
		if (count == 0) {
			return List.of();
		}
		int distance = placement.distance();
		int spread = placement.spread();
		HolderSet<Biome> preferredBiomes = placement.preferredBiomes();
		RandomSource random = RandomSource.create();
		random.setSeed(seed);
		double angle = random.nextDouble() * Math.PI * 2.0;
		int positionInCircle = 0;
		int circle = 0;

		// The random sequence must be consumed in order; the biome searches themselves are independent.
		record Start(int x, int z, RandomSource random) {
		}
		List<Start> starts = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			double dist = 4 * distance + distance * circle * 6 + (random.nextDouble() - 0.5) * (distance * 2.5);
			int initialX = (int) Math.round(Math.cos(angle) * dist);
			int initialZ = (int) Math.round(Math.sin(angle) * dist);
			starts.add(new Start(initialX, initialZ, random.fork()));
			angle += (Math.PI * 2) / spread;
			if (++positionInCircle == spread) {
				circle++;
				positionInCircle = 0;
				spread += 2 * spread / (circle + 1);
				spread = Math.min(spread, count - i);
				angle += random.nextDouble() * Math.PI * 2.0;
			}
		}
		return starts.parallelStream().map(start -> {
			Pair<BlockPos, Holder<Biome>> closest = biomeSource.findBiomeHorizontal(
				SectionPos.sectionToBlockCoord(start.x(), 8), 0, SectionPos.sectionToBlockCoord(start.z(), 8), 112,
				preferredBiomes::contains, start.random(), randomState.sampler());
			return closest != null
				? new ChunkPos(SectionPos.blockToSectionCoord(closest.getFirst().getX()), SectionPos.blockToSectionCoord(closest.getFirst().getZ()))
				: new ChunkPos(start.x(), start.z());
		}).toList();
	}

	private static Field field(Class<?> type, String name) throws NoSuchFieldException {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
