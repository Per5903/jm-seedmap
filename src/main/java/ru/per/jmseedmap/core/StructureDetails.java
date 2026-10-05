package ru.per.jmseedmap.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.gen.FoundStructure;
import ru.per.jmseedmap.gen.GenContext;

/**
 * What is inside a structure: the map only needs where a structure starts, but here all its pieces are assembled
 * the way the game does it (no blocks are placed) and the templates they use are looked at.
 * <ul>
 *   <li>everything: size, number of chests and spawners;</li>
 *   <li>villages: houses, beds, notable buildings, zombie villages; bastions: their type; igloos: the basement;</li>
 *   <li>strongholds: libraries and the portal room; fortresses: blaze spawners and nether wart;</li>
 *   <li>trial chambers: vaults and ominous vaults;</li>
 *   <li>ruined portals: how much obsidian there will be. The game turns obsidian into crying obsidian with a
 *       random number seeded by the block position only, so this is exact, except that the netherrack spread
 *       afterwards (chunk decoration random) occasionally eats a block or two at the base.</li>
 * </ul>
 * Assembling a big structure takes up to a few hundred milliseconds, so it happens on demand in the background.
 */
public final class StructureDetails {
	public record Details(List<Component> lines, @Nullable BlockPos portalRoom) {
	}

	private static final int MAX_CACHED = 2000;
	private static final List<Block> BEDS = BuiltInRegistries.BLOCK.stream().filter(b -> b instanceof BedBlock).toList();
	private static final Field PLACE_SETTINGS = field(TemplateStructurePiece.class, "placeSettings");
	private static final Field TEMPLATE_POSITION = field(TemplateStructurePiece.class, "templatePosition");
	private static final Field TEMPLATE_NAME = field(TemplateStructurePiece.class, "templateName");
	private static final Field PORTAL_PLACEMENT = field(RuinedPortalPiece.class, "verticalPlacement");

	private final @Nullable StructureIndex index;
	private final Map<String, CompletableFuture<Details>> cache = new ConcurrentHashMap<>();
	/** Block counts per template, shared by all structures. */
	private final Map<String, TemplateCounts> templateCounts = new ConcurrentHashMap<>();
	private volatile int version;

	/** @param index where to compute in the background; null if only {@link #compute} is used */
	public StructureDetails(@Nullable StructureIndex index) {
		this.index = index;
	}

	/** Increases whenever new details are ready. */
	public int version() {
		return version;
	}

	/** The details if they are ready; otherwise starts computing them and returns null. */
	public @Nullable Details get(FoundStructure structure) {
		CompletableFuture<Details> future = request(structure);
		return future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
	}

	public CompletableFuture<Details> request(FoundStructure structure) {
		if (cache.size() > MAX_CACHED) {
			cache.clear();
		}
		return cache.computeIfAbsent(structure.key(), k -> {
			CompletableFuture<Details> future = new CompletableFuture<>();
			index.executeBackground(() -> {
				try {
					GenContext ctx = index.context(structure.dimension());
					future.complete(ctx == null ? new Details(List.of(), null) : compute(ctx, structure));
				} catch (Throwable t) {
					SeedMapClient.LOGGER.warn("Could not assemble {} for details", structure, t);
					future.complete(new Details(List.of(Component.translatable("jm_seedmap.details.error")), null));
				}
				version++;
			});
			return future;
		});
	}

	public void reset() {
		cache.clear();
		version++;
	}

	public Details compute(GenContext ctx, FoundStructure found) {
		Holder<Structure> holder = ctx.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(found.structure());
		Structure structure = holder.value();
		StructureStart start = structure.generate(holder, ctx.dimension(), ctx.registryAccess(), ctx.generator(), ctx.generator().getBiomeSource(),
			ctx.randomState(), ctx.templates(), ctx.structureState().getLevelSeed(), found.chunk(), 0, ctx.heightAccessor(), structure.biomes()::contains);
		List<Component> lines = new ArrayList<>();
		if (!start.isValid()) {
			lines.add(Component.translatable("jm_seedmap.details.error"));
			return new Details(lines, null);
		}
		String path = found.structure().identifier().getPath();
		BoundingBox box = start.getBoundingBox();

		TemplateCounts total = new TemplateCounts();
		Set<String> templateNames = new LinkedHashSet<>();
		int houses = 0;
		int libraries = 0;
		int tallLibraries = 0;
		int blazeSpawners = 0;
		int wartRooms = 0;
		BlockPos portalRoom = null;
		RuinedPortalPiece portal = null;
		for (StructurePiece piece : start.getPieces()) {
			String name = templateName(piece);
			if (name != null) {
				templateNames.add(name);
				if (name.contains("/houses/")) {
					houses++;
				}
				StructureTemplate template = piece instanceof TemplateStructurePiece t ? t.template() : ctx.templates().getOrCreate(Identifier.parse(name));
				total.add(templateCounts.computeIfAbsent(name, n -> TemplateCounts.of(template)));
			}
			if (piece instanceof StrongholdPieces.Library) {
				libraries++;
				if (piece.getBoundingBox().getYSpan() > 6) {
					tallLibraries++;
				}
			} else if (piece instanceof StrongholdPieces.PortalRoom) {
				portalRoom = piece.getBoundingBox().getCenter();
			} else if (piece instanceof NetherFortressPieces.MonsterThrone) {
				blazeSpawners++;
			} else if (piece instanceof NetherFortressPieces.CastleStalkRoom) {
				wartRooms++;
			} else if (piece instanceof RuinedPortalPiece p) {
				portal = p;
			}
		}

		lines.add(Component.translatable("jm_seedmap.details.size", box.getXSpan(), box.getZSpan(), box.getYSpan()));
		if (path.startsWith("village_")) {
			if (templateNames.stream().anyMatch(n -> n.contains("/zombie/"))) {
				lines.add(Component.translatable("jm_seedmap.details.zombie_village"));
			}
			lines.add(Component.translatable("jm_seedmap.details.houses", houses, total.beds));
			List<Component> notable = new ArrayList<>();
			for (String building : new String[]{"weaponsmith", "toolsmith", "armorer", "temple", "library", "cartographer", "fletcher"}) {
				if (templateNames.stream().anyMatch(n -> n.contains("_" + building))) {
					notable.add(Component.translatable("jm_seedmap.details.building." + building));
				}
			}
			if (!notable.isEmpty()) {
				lines.add(Component.translatable("jm_seedmap.details.buildings", join(notable)));
			}
		} else if (path.equals("bastion_remnant")) {
			String type = templateNames.stream().findFirst().map(StructureDetails::bastionType).orElse("units");
			lines.add(Component.translatable("jm_seedmap.details.bastion." + type));
			if (total.gold > 0) {
				lines.add(Component.translatable("jm_seedmap.details.gold", total.gold));
			}
		} else if (path.equals("igloo")) {
			lines.add(Component.translatable(templateNames.stream().anyMatch(n -> n.endsWith("igloo/bottom"))
				? "jm_seedmap.details.igloo_basement" : "jm_seedmap.details.igloo_no_basement"));
		} else if (path.equals("stronghold")) {
			lines.add(Component.translatable("jm_seedmap.details.libraries", libraries, tallLibraries));
			if (portalRoom != null) {
				lines.add(Component.translatable("jm_seedmap.details.portal_room", portalRoom.getX(), portalRoom.getY(), portalRoom.getZ()));
			}
		} else if (path.equals("fortress")) {
			lines.add(Component.translatable("jm_seedmap.details.blaze", blazeSpawners));
			lines.add(Component.translatable("jm_seedmap.details.wart", wartRooms));
		} else if (path.equals("trial_chambers")) {
			lines.add(Component.translatable("jm_seedmap.details.vaults", total.vaults, total.ominousVaults));
			lines.add(Component.translatable("jm_seedmap.details.trial_spawners", total.trialSpawners));
		} else if (path.startsWith("ruined_portal") && portal != null) {
			addPortal(lines, portal);
		} else if (path.startsWith("ocean_ruin")) {
			lines.add(Component.translatable(templateNames.stream().anyMatch(n -> n.contains("big_"))
				? "jm_seedmap.details.ruin_big" : "jm_seedmap.details.ruin_small"));
		} else if (path.equals("end_city") && "ship".equals(found.variant())) {
			lines.add(Component.translatable("jm_seedmap.details.ship"));
		}
		if (total.chests > 0) {
			lines.add(Component.translatable("jm_seedmap.details.chests", total.chests));
		}
		if (total.spawners > 0 && !path.equals("fortress")) {
			lines.add(Component.translatable("jm_seedmap.details.spawners", total.spawners));
		}
		if (path.equals("ancient_city") || path.equals("mansion") || path.startsWith("village_")) {
			lines.add(Component.translatable("jm_seedmap.details.pieces", start.getPieces().size()));
		}
		return new Details(List.copyOf(lines), portalRoom);
	}

	/** Placement and the exact obsidian count after aging (see class comment). */
	private static void addPortal(List<Component> lines, RuinedPortalPiece portal) {
		String name = String.valueOf(get(TEMPLATE_NAME, portal));
		boolean giant = name.contains("giant_portal");
		Object placement = get(PORTAL_PLACEMENT, portal);
		if (placement != null) {
			lines.add(Component.translatable("jm_seedmap.details.portal_placement." + placement.toString().toLowerCase(java.util.Locale.ROOT)));
		}
		StructurePlaceSettings settings = (StructurePlaceSettings) get(PLACE_SETTINGS, portal);
		BlockPos position = (BlockPos) get(TEMPLATE_POSITION, portal);
		if (settings == null || position == null) {
			return;
		}
		int obsidian = 0;
		int crying = 0;
		for (StructureTemplate.StructureBlockInfo info : portal.template().filterBlocks(position, settings, Blocks.OBSIDIAN)) {
			// BlockAgeProcessor: 15% of obsidian becomes crying obsidian, decided by a random seeded with the position.
			if (settings.getRandom(info.pos()).nextFloat() < 0.15F) {
				crying++;
			} else {
				obsidian++;
			}
		}
		crying += portal.template().filterBlocks(position, settings, Blocks.CRYING_OBSIDIAN).size();
		if (giant) {
			lines.add(Component.translatable("jm_seedmap.details.portal_giant"));
		}
		lines.add(Component.translatable("jm_seedmap.details.portal_obsidian", obsidian, crying));
		lines.add(obsidian >= 10
			? Component.translatable("jm_seedmap.details.portal_enough")
			: Component.translatable("jm_seedmap.details.portal_missing", 10 - obsidian));
	}

	/** World positions of the portal's obsidian, and whether each one becomes crying obsidian (for the selftest). */
	public static Map<BlockPos, Boolean> portalObsidian(RuinedPortalPiece portal) {
		Map<BlockPos, Boolean> out = new java.util.LinkedHashMap<>();
		StructurePlaceSettings settings = (StructurePlaceSettings) get(PLACE_SETTINGS, portal);
		BlockPos position = (BlockPos) get(TEMPLATE_POSITION, portal);
		if (settings == null || position == null) {
			return out;
		}
		for (StructureTemplate.StructureBlockInfo info : portal.template().filterBlocks(position, settings, Blocks.OBSIDIAN)) {
			out.put(info.pos(), settings.getRandom(info.pos()).nextFloat() < 0.15F);
		}
		for (StructureTemplate.StructureBlockInfo info : portal.template().filterBlocks(position, settings, Blocks.CRYING_OBSIDIAN)) {
			out.put(info.pos(), true);
		}
		return out;
	}

	private static String bastionType(String startTemplate) {
		if (startTemplate.contains("bastion/treasure")) return "treasure";
		if (startTemplate.contains("bastion/bridge")) return "bridge";
		if (startTemplate.contains("bastion/hoglin_stable")) return "hoglin_stable";
		return "units";
	}

	private static @Nullable String templateName(StructurePiece piece) {
		if (piece instanceof PoolElementStructurePiece pool && pool.getElement() instanceof SinglePoolElement single) {
			return single.getTemplateLocation().toString();
		}
		if (piece instanceof TemplateStructurePiece) {
			Object name = get(TEMPLATE_NAME, piece);
			return name == null ? null : name.toString();
		}
		return null;
	}

	private static Component join(List<Component> parts) {
		var out = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append(parts.get(i));
		}
		return out;
	}

	private static @Nullable Field field(Class<?> type, String name) {
		try {
			Field f = type.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException | RuntimeException e) {
			SeedMapClient.LOGGER.warn("Structure details: no field {}.{}", type.getSimpleName(), name);
			return null;
		}
	}

	private static @Nullable Object get(@Nullable Field field, Object owner) {
		try {
			return field == null ? null : field.get(owner);
		} catch (IllegalAccessException e) {
			return null;
		}
	}

	/** Interesting blocks in one template (or summed over a structure). */
	static final class TemplateCounts {
		int chests;
		int spawners;
		int trialSpawners;
		int vaults;
		int ominousVaults;
		int beds;
		int gold;

		static TemplateCounts of(StructureTemplate template) {
			TemplateCounts c = new TemplateCounts();
			StructurePlaceSettings settings = new StructurePlaceSettings();
			c.chests = count(template, settings, Blocks.CHEST) + count(template, settings, Blocks.TRAPPED_CHEST) + count(template, settings, Blocks.BARREL);
			c.spawners = count(template, settings, Blocks.SPAWNER);
			c.trialSpawners = count(template, settings, Blocks.TRIAL_SPAWNER);
			for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(BlockPos.ZERO, settings, Blocks.VAULT)) {
				if (info.state().getValue(VaultBlock.OMINOUS)) {
					c.ominousVaults++;
				} else {
					c.vaults++;
				}
			}
			for (Block bed : BEDS) {
				for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(BlockPos.ZERO, settings, bed)) {
					if (info.state().getValue(BedBlock.PART) == BedPart.HEAD) {
						c.beds++;
					}
				}
			}
			c.gold = count(template, settings, Blocks.GOLD_BLOCK);
			return c;
		}

		private static int count(StructureTemplate template, StructurePlaceSettings settings, Block block) {
			return template.filterBlocks(BlockPos.ZERO, settings, block).size();
		}

		void add(TemplateCounts o) {
			chests += o.chests;
			spawners += o.spawners;
			trialSpawners += o.trialSpawners;
			vaults += o.vaults;
			ominousVaults += o.ominousVaults;
			beds += o.beds;
			gold += o.gold;
		}
	}
}
