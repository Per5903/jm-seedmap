package ru.per.jmseedmap.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

/**
 * Finds structure starts in a square tile of chunks by replaying vanilla's
 * {@code ChunkGenerator#createStructures} decision logic without generating any blocks.
 */
public final class StructureFinder {
	/** Tile edge length in chunks (512 blocks). */
	public static final int TILE_CHUNKS = 32;
	public static final int TILE_BLOCKS = TILE_CHUNKS * 16;

	private StructureFinder() {
	}

	public static List<FoundStructure> findInTile(GenContext ctx, Holder<StructureSet> set, int tileX, int tileZ) {
		int minX = tileX * TILE_CHUNKS;
		int minZ = tileZ * TILE_CHUNKS;
		int maxX = minX + TILE_CHUNKS - 1;
		int maxZ = minZ + TILE_CHUNKS - 1;
		List<FoundStructure> found = new ArrayList<>();
		StructurePlacement placement = set.value().placement();

		if (placement instanceof RandomSpreadStructurePlacement spread) {
			int spacing = spread.spacing();
			for (int rx = Math.floorDiv(minX, spacing); rx <= Math.floorDiv(maxX, spacing); rx++) {
				for (int rz = Math.floorDiv(minZ, spacing); rz <= Math.floorDiv(maxZ, spacing); rz++) {
					ChunkPos chunk = spread.getPotentialStructureChunk(ctx.seed(), rx * spacing, rz * spacing);
					if (inside(chunk, minX, minZ, maxX, maxZ)) {
						tryChunk(ctx, set, chunk, found);
					}
				}
			}
		} else if (placement instanceof ConcentricRingsStructurePlacement rings) {
			List<ChunkPos> positions = ctx.structureState().getRingPositionsFor(rings);
			if (positions != null) {
				for (ChunkPos chunk : positions) {
					if (inside(chunk, minX, minZ, maxX, maxZ)) {
						tryChunk(ctx, set, chunk, found);
					}
				}
			}
		} else {
			// Unknown (modded) placement type: ask it about every chunk.
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					tryChunk(ctx, set, new ChunkPos(x, z), found);
				}
			}
		}
		return found;
	}

	private static boolean inside(ChunkPos chunk, int minX, int minZ, int maxX, int maxZ) {
		return chunk.x() >= minX && chunk.x() <= maxX && chunk.z() >= minZ && chunk.z() <= maxZ;
	}

	private static void tryChunk(GenContext ctx, Holder<StructureSet> set, ChunkPos chunk, List<FoundStructure> out) {
		if (!set.value().placement().isStructureChunk(ctx.structureState(), chunk.x(), chunk.z())) {
			return;
		}
		List<StructureSet.StructureSelectionEntry> entries = set.value().structures();
		if (entries.size() == 1) {
			tryGenerate(ctx, entries.get(0), chunk).ifPresent(out::add);
			return;
		}

		// Same weighted pick-and-retry as vanilla, so mixed sets (fortress/bastion, villages) resolve identically.
		List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(entries);
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setLargeFeatureSeed(ctx.structureState().getLevelSeed(), chunk.x(), chunk.z());
		int total = 0;
		for (StructureSet.StructureSelectionEntry option : options) {
			total += option.weight();
		}
		while (!options.isEmpty()) {
			int choice = random.nextInt(total);
			int index = 0;
			for (StructureSet.StructureSelectionEntry option : options) {
				choice -= option.weight();
				if (choice < 0) {
					break;
				}
				index++;
			}
			StructureSet.StructureSelectionEntry selected = options.get(index);
			Optional<FoundStructure> result = tryGenerate(ctx, selected, chunk);
			if (result.isPresent()) {
				out.add(result.get());
				return;
			}
			options.remove(index);
			total -= selected.weight();
		}
	}

	private static Optional<FoundStructure> tryGenerate(GenContext ctx, StructureSet.StructureSelectionEntry entry, ChunkPos chunk) {
		Holder<Structure> holder = entry.structure();
		Optional<ResourceKey<Structure>> key = holder.unwrapKey();
		if (key.isEmpty()) {
			return Optional.empty();
		}
		Structure structure = holder.value();
		if (structure instanceof JigsawStructure) {
			// A jigsaw stub always yields at least its start piece, so the start is valid exactly when the stub
			// exists. Skipping the (lazy) piece assembly makes villages, trial chambers and cities much cheaper.
			Structure.GenerationContext context = new Structure.GenerationContext(
				ctx.registryAccess(),
				ctx.generator(),
				ctx.generator().getBiomeSource(),
				ctx.randomState(),
				ctx.templates(),
				ctx.structureState().getLevelSeed(),
				chunk,
				ctx.heightAccessor(),
				structure.biomes()::contains
			);
			return structure.findValidGenerationPoint(context)
				.map(stub -> new FoundStructure(ctx.dimension(), key.get(), chunk, stub.position()));
		}
		StructureStart start = structure.generate(
			holder,
			ctx.dimension(),
			ctx.registryAccess(),
			ctx.generator(),
			ctx.generator().getBiomeSource(),
			ctx.randomState(),
			ctx.templates(),
			ctx.structureState().getLevelSeed(),
			chunk,
			0,
			ctx.heightAccessor(),
			structure.biomes()::contains
		);
		if (!start.isValid()) {
			return Optional.empty();
		}
		// The first piece is the "heart" of the structure: stronghold stairs, fortress start, mansion entrance...
		BoundingBox box = start.getPieces().isEmpty() ? start.getBoundingBox() : start.getPieces().get(0).getBoundingBox();
		BlockPos center = box.getCenter();
		return Optional.of(new FoundStructure(ctx.dimension(), key.get(), chunk, center));
	}
}
