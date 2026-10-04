package ru.per.jmseedmap.gen;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Everything vanilla needs to decide whether a structure starts in a chunk, for one dimension and seed.
 *
 * @param id identifies the seed source; results computed with different ids must never be mixed
 */
public record GenContext(
	String id,
	long seed,
	ResourceKey<Level> dimension,
	RegistryAccess registryAccess,
	ChunkGenerator generator,
	RandomState randomState,
	ChunkGeneratorStructureState structureState,
	StructureTemplateManager templates,
	LevelHeightAccessor heightAccessor
) {
}
