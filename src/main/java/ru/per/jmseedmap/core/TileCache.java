package ru.per.jmseedmap.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.gen.FoundStructure;

/**
 * Computed structure tiles on disk, one gzip file per generator context (seed + dimension + data packs...),
 * so a world opened again fills its map instantly instead of recomputing everything.
 * The context id is part of the file, so a file never feeds results into a different world.
 */
final class TileCache {
	/** Bump when the search logic changes what it finds. */
	private static final int FORMAT = 1;
	private static final int MAGIC = 0x534D4331;
	private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

	/** One computed tile of one structure set. */
	record Tile(Identifier set, int tileX, int tileZ, List<FoundStructure> structures) {
	}

	private final Path dir;

	TileCache(Path dir) {
		this.dir = dir;
	}

	private Path file(String contextId) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(contextId.getBytes(StandardCharsets.UTF_8));
			return dir.resolve(HexFormat.of().formatHex(hash, 0, 12) + ".bin");
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	List<Tile> read(String contextId, ResourceKey<Level> dimension) {
		Path file = file(contextId);
		if (!Files.isRegularFile(file)) {
			return List.of();
		}
		List<Tile> tiles = new ArrayList<>();
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			if (in.readInt() != MAGIC || in.readInt() != FORMAT || !in.readUTF().equals(versionTag()) || !in.readUTF().equals(contextId)) {
				return List.of();
			}
			String[] strings = new String[in.readInt()];
			for (int i = 0; i < strings.length; i++) {
				strings[i] = in.readUTF();
			}
			int count = in.readInt();
			for (int t = 0; t < count; t++) {
				Identifier set = Identifier.parse(strings[in.readInt()]);
				int tileX = in.readInt();
				int tileZ = in.readInt();
				int n = in.readInt();
				List<FoundStructure> list = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					var structure = ResourceKey.create(Registries.STRUCTURE, Identifier.parse(strings[in.readInt()]));
					int variant = in.readInt();
					ChunkPos chunk = new ChunkPos(in.readInt(), in.readInt());
					BlockPos pos = new BlockPos(in.readInt(), in.readInt(), in.readInt());
					list.add(new FoundStructure(dimension, structure, chunk, pos, variant < 0 ? null : strings[variant]));
				}
				tiles.add(new Tile(set, tileX, tileZ, List.copyOf(list)));
			}
			// Touch it so the size limit removes the least recently used worlds first.
			Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
		} catch (Exception e) {
			SeedMapClient.LOGGER.warn("Ignoring broken structure cache {}", file, e);
			return List.of();
		}
		return tiles;
	}

	void write(String contextId, List<Tile> tiles) {
		Path file = file(contextId);
		Map<String, Integer> index = new java.util.LinkedHashMap<>();
		for (Tile tile : tiles) {
			index.putIfAbsent(tile.set().toString(), index.size());
			for (FoundStructure s : tile.structures()) {
				index.putIfAbsent(s.id(), index.size());
				if (s.variant() != null) {
					index.putIfAbsent(s.variant(), index.size());
				}
			}
		}
		try {
			Files.createDirectories(dir);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
				out.writeInt(MAGIC);
				out.writeInt(FORMAT);
				out.writeUTF(versionTag());
				out.writeUTF(contextId);
				out.writeInt(index.size());
				for (String s : index.keySet()) {
					out.writeUTF(s);
				}
				out.writeInt(tiles.size());
				for (Tile tile : tiles) {
					out.writeInt(index.get(tile.set().toString()));
					out.writeInt(tile.tileX());
					out.writeInt(tile.tileZ());
					out.writeInt(tile.structures().size());
					for (FoundStructure s : tile.structures()) {
						out.writeInt(index.get(s.id()));
						out.writeInt(s.variant() == null ? -1 : index.get(s.variant()));
						out.writeInt(s.chunk().x());
						out.writeInt(s.chunk().z());
						out.writeInt(s.pos().getX());
						out.writeInt(s.pos().getY());
						out.writeInt(s.pos().getZ());
					}
				}
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			trim();
		} catch (IOException e) {
			SeedMapClient.LOGGER.warn("Failed to write structure cache {}", file, e);
		}
	}

	/** Removes the least recently used files while the folder is over the size limit. */
	private void trim() throws IOException {
		List<Path> files;
		try (Stream<Path> stream = Files.list(dir)) {
			files = new ArrayList<>(stream.filter(p -> p.toString().endsWith(".bin")).toList());
		}
		long total = 0;
		for (Path f : files) {
			total += Files.size(f);
		}
		if (total <= MAX_TOTAL_BYTES) {
			return;
		}
		files.sort(Comparator.comparingLong(f -> {
			try {
				return Files.getLastModifiedTime(f).toMillis();
			} catch (IOException e) {
				return 0L;
			}
		}));
		for (Path f : files) {
			if (total <= MAX_TOTAL_BYTES) {
				break;
			}
			total -= Files.size(f);
			Files.deleteIfExists(f);
		}
	}

	/** Deletes every cache file; returns how many bytes were freed. */
	long clear() {
		long freed = 0;
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> stream = Files.list(dir)) {
			for (Path f : stream.toList()) {
				freed += Files.size(f);
				Files.deleteIfExists(f);
			}
		} catch (IOException e) {
			SeedMapClient.LOGGER.warn("Failed to clear structure cache", e);
		}
		return freed;
	}

	long sizeBytes() {
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> stream = Files.list(dir)) {
			long total = 0;
			for (Path f : stream.toList()) {
				total += Files.size(f);
			}
			return total;
		} catch (IOException e) {
			return 0;
		}
	}

	private static String versionTag() {
		return SharedConstants.getCurrentVersion().name();
	}
}
