package ru.per.jmseedmap.gen;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.Util;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import ru.per.jmseedmap.SeedMapClient;

/**
 * Worldgen registries and structure templates loaded from the vanilla data pack bundled in the client jar,
 * the same way the "Create World" screen does it. Used when there is no integrated server to ask.
 * <p>
 * Static registry tags are loaded as pending lookups only and never bound, so the tags the client
 * received from the connected server stay untouched.
 */
public final class VanillaWorldgen implements AutoCloseable {
	private final CloseableResourceManager resources;
	private final LevelStorageSource.LevelStorageAccess storage;
	public final RegistryAccess.Frozen registries;
	public final StructureTemplateManager templates;
	private final Map<String, WorldDimensions> presets = new ConcurrentHashMap<>();

	private VanillaWorldgen(
		CloseableResourceManager resources, LevelStorageSource.LevelStorageAccess storage, RegistryAccess.Frozen registries, StructureTemplateManager templates
	) {
		this.resources = resources;
		this.storage = storage;
		this.registries = registries;
		this.templates = templates;
	}

	/** Blocking; call off the render thread. */
	public static VanillaWorldgen load(Minecraft minecraft) throws Exception {
		long start = Util.getMillis();
		PackRepository packs = new PackRepository(new ServerPacksSource(minecraft.directoryValidator()));
		MinecraftServer.configurePackRepository(packs, WorldDataConfiguration.DEFAULT, true, false);
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

			// The template manager wants a world folder for "generated" structures; give it a private scratch one.
			Path dir = minecraft.gameDirectory.toPath().resolve("jm_seedmap");
			LevelStorageSource source = new LevelStorageSource(
				dir.resolve("saves"), dir.resolve("backups"), minecraft.directoryValidator(), minecraft.getFixerUpper()
			);
			LevelStorageSource.LevelStorageAccess storage;
			try {
				storage = source.createAccess("templates");
			} catch (Exception locked) {
				// Another game instance holds the lock.
				storage = source.createAccess("templates-" + ProcessHandle.current().pid());
			}
			StructureTemplateManager templates = new StructureTemplateManager(
				resources, storage, minecraft.getFixerUpper(), registries.lookupOrThrow(Registries.BLOCK)
			);
			SeedMapClient.LOGGER.info("Loaded vanilla worldgen data in {} ms", Util.getMillis() - start);
			return new VanillaWorldgen(resources, storage, registries, templates);
		} catch (Exception e) {
			resources.close();
			throw e;
		}
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
