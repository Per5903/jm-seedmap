package ru.per.jmseedmap.gen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.Util;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;

/**
 * Worldgen registries and structure templates loaded from the vanilla data pack bundled in the client jar,
 * the same way the "Create World" screen does it. Used when there is no integrated server to ask.
 * <p>
 * Data packs the player copied from the server (zips or folders in the server's datapack folder) are added on top,
 * like the {@code datapacks} folder of a world: new biomes, structures, changed structure spacing and replaced
 * dimensions all apply. Static registry tags are loaded as pending lookups only and never bound, so the tags the
 * client received from the connected server stay untouched.
 */
public final class VanillaWorldgen implements AutoCloseable {
	private final CloseableResourceManager resources;
	private final LevelStorageSource.LevelStorageAccess storage;
	public final RegistryAccess.Frozen registries;
	public final StructureTemplateManager templates;
	/** Dimensions defined by data packs ({@code data/<ns>/dimension/*.json}); they win over the world preset. */
	private final @Nullable Registry<LevelStem> packDimensions;
	/** Names of the extra (non-vanilla) data packs that were loaded. */
	public final List<String> extraPacks;
	private final Map<String, WorldDimensions> presets = new ConcurrentHashMap<>();

	private VanillaWorldgen(
		CloseableResourceManager resources, LevelStorageSource.LevelStorageAccess storage, RegistryAccess.Frozen registries,
		StructureTemplateManager templates, @Nullable Registry<LevelStem> packDimensions, List<String> extraPacks
	) {
		this.resources = resources;
		this.storage = storage;
		this.registries = registries;
		this.templates = templates;
		this.packDimensions = packDimensions;
		this.extraPacks = extraPacks;
	}

	/**
	 * Blocking; call off the render thread.
	 *
	 * @param packDir folder with extra data packs, or null for plain vanilla
	 */
	public static VanillaWorldgen load(Minecraft minecraft, @Nullable Path packDir) throws Exception {
		long start = Util.getMillis();
		List<RepositorySource> sources = new ArrayList<>();
		sources.add(new ServerPacksSource(minecraft.directoryValidator()));
		if (packDir != null && Files.isDirectory(packDir)) {
			sources.add(new FolderRepositorySource(packDir, PackType.SERVER_DATA, PackSource.WORLD, minecraft.directoryValidator()));
		}
		PackRepository packs = new PackRepository(sources.toArray(RepositorySource[]::new));
		// Init mode: like a new world, every pack from the folder is enabled automatically.
		MinecraftServer.configurePackRepository(packs, WorldDataConfiguration.DEFAULT, true, false);
		List<String> extra = new ArrayList<>();
		for (Pack pack : packs.getSelectedPacks()) {
			if (pack.getId().startsWith("file/")) {
				extra.add(pack.getId().substring("file/".length()));
			}
		}
		CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs.openAllSelected());
		try {
			LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
			List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
			RegistryAccess.Frozen worldgenContext = layers.getAccessForLoading(RegistryLayer.WORLDGEN);
			List<HolderLookup.RegistryLookup<?>> contextLookups = TagLoader.buildUpdatedLookups(worldgenContext, staticTags);
			RegistryAccess.Frozen worldgen = RegistryDataLoader.load(
				resources, contextLookups, RegistryDataLoader.WORLDGEN_REGISTRIES, Util.backgroundExecutor()
			).join();
			RegistryAccess.Frozen registries = layers.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();

			Registry<LevelStem> packDimensions = null;
			if (!extra.isEmpty()) {
				List<HolderLookup.RegistryLookup<?>> dimensionContext = Stream.concat(contextLookups.stream(), worldgen.listRegistries()).toList();
				RegistryAccess.Frozen dimensions = RegistryDataLoader.load(
					resources, dimensionContext, RegistryDataLoader.DIMENSION_REGISTRIES, Util.backgroundExecutor()
				).join();
				packDimensions = dimensions.lookupOrThrow(Registries.LEVEL_STEM);
			}

			// The template manager wants a world folder for "generated" structures; give it a private scratch one.
			Path dir = minecraft.gameDirectory.toPath().resolve("jm_seedmap");
			LevelStorageSource source = new LevelStorageSource(
				dir.resolve("saves"), dir.resolve("backups"), minecraft.directoryValidator(), minecraft.getFixerUpper()
			);
			LevelStorageSource.LevelStorageAccess storage;
			try {
				storage = source.createAccess("templates");
			} catch (Exception locked) {
				// Another game instance (or another pack set in this one) holds the lock.
				storage = source.createAccess("templates-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
			}
			StructureTemplateManager templates = new StructureTemplateManager(
				resources, storage, minecraft.getFixerUpper(), registries.lookupOrThrow(Registries.BLOCK)
			);
			SeedMapClient.LOGGER.info("Loaded worldgen data in {} ms{}", Util.getMillis() - start,
				extra.isEmpty() ? "" : ", extra data packs: " + extra);
			return new VanillaWorldgen(resources, storage, registries, templates, packDimensions, List.copyOf(extra));
		} catch (Exception e) {
			resources.close();
			throw e;
		}
	}

	/** The dimension as a new world of this preset would have it, data pack dimensions first. */
	public Optional<LevelStem> stem(String preset, ResourceKey<LevelStem> key) {
		if (packDimensions != null) {
			Optional<LevelStem> fromPack = packDimensions.getOptional(key);
			if (fromPack.isPresent()) {
				return fromPack;
			}
		}
		return dimensions(preset).get(key);
	}

	public WorldDimensions dimensions(String preset) {
		return presets.computeIfAbsent(preset, id -> {
			ResourceKey<WorldPreset> key = ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse(id));
			return registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(key).value().createWorldDimensions();
		});
	}

	@Override
	public void close() {
		try {
			storage.close();
		} catch (Exception e) {
			SeedMapClient.LOGGER.debug("Failed to close template storage", e);
		}
		resources.close();
	}
}
