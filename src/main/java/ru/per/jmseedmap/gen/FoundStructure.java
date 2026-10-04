package ru.per.jmseedmap.gen;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * @param chunk the chunk the structure starts in (what vanilla stores the start under)
 * @param pos   where to put the marker
 */
public record FoundStructure(ResourceKey<Level> dimension, ResourceKey<Structure> structure, ChunkPos chunk, BlockPos pos) {
	public String id() {
		return structure.identifier().toString();
	}

	/** Stable identity of this structure instance, used for visited marks and pins. */
	public String key() {
		return dimension.identifier() + "|" + id() + "@" + chunk.x() + "," + chunk.z();
	}

	public double distanceSqr(double x, double z) {
		double dx = pos.getX() + 0.5 - x;
		double dz = pos.getZ() + 0.5 - z;
		return dx * dx + dz * dz;
	}
}
