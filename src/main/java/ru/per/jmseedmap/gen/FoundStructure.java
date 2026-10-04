package ru.per.jmseedmap.gen;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jspecify.annotations.Nullable;

/**
 * One predicted structure start. The derived ids are computed once: map renderers ask for them every frame.
 *
 * <p>{@code chunk} is the chunk the structure starts in (what vanilla stores the start under), {@code pos} where
 * to put the marker, {@code variant} a notable sub-kind, e.g. "ship" for an End city with an elytra ship.
 */
public final class FoundStructure {
	private final ResourceKey<Level> dimension;
	private final ResourceKey<Structure> structure;
	private final ChunkPos chunk;
	private final BlockPos pos;
	private final @Nullable String variant;
	private final String id;
	private final String displayId;
	private final String key;

	public FoundStructure(ResourceKey<Level> dimension, ResourceKey<Structure> structure, ChunkPos chunk, BlockPos pos, @Nullable String variant) {
		this.dimension = dimension;
		this.structure = structure;
		this.chunk = chunk;
		this.pos = pos;
		this.variant = variant;
		this.id = structure.identifier().toString();
		this.displayId = variant == null ? id : id + "_" + variant;
		this.key = dimension.identifier() + "|" + id + "@" + chunk.x() + "," + chunk.z();
	}

	public ResourceKey<Level> dimension() {
		return dimension;
	}

	public ResourceKey<Structure> structure() {
		return structure;
	}

	public ChunkPos chunk() {
		return chunk;
	}

	public BlockPos pos() {
		return pos;
	}

	public @Nullable String variant() {
		return variant;
	}

	public String id() {
		return id;
	}

	/** Id used for looks, toggles and search: the structure id, or e.g. "minecraft:end_city_ship" for a variant. */
	public String displayId() {
		return displayId;
	}

	/** Stable identity of this structure instance, used for visited marks and pins. */
	public String key() {
		return key;
	}

	public double distanceSqr(double x, double z) {
		double dx = pos.getX() + 0.5 - x;
		double dz = pos.getZ() + 0.5 - z;
		return dx * dx + dz * dz;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof FoundStructure other && key.equals(other.key) && displayId.equals(other.displayId) && pos.equals(other.pos);
	}

	@Override
	public int hashCode() {
		return key.hashCode();
	}

	@Override
	public String toString() {
		return displayId + "@" + pos.toShortString();
	}
}
