package ru.per.jmseedmap;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.levelgen.WorldOptions;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.StructureStyles;
import ru.per.jmseedmap.gen.GenContextProvider;
import ru.per.jmseedmap.map.MapBackend;
import ru.per.jmseedmap.ui.SeedMapConfigScreen;

/**
 * {@code /seedmap ...} client commands.
 */
public final class SeedMapCommands {
	private static final List<String> PRESETS = List.of("normal", "large_biomes", "amplified");

	private SeedMapCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		SuggestionProvider<FabricClientCommandSource> structureIds = (ctx, builder) -> {
			Set<String> ids = new TreeSet<>(StructureStyles.knownIds());
			ids.addAll(SeedMapConfig.get().structures.keySet());
			StructureStyles.groups().forEach(g -> ids.add(g.key()));
			ids.add("all");
			return SharedSuggestionProvider.suggest(ids, builder);
		};
		SuggestionProvider<FabricClientCommandSource> biomes = (ctx, builder) -> {
			Minecraft mc = ctx.getSource().getClient();
			return SharedSuggestionProvider.suggestResource(
				mc.level == null ? List.of() : SeedMap.get().index.possibleBiomes(mc.level.dimension()), builder);
		};
		SuggestionProvider<FabricClientCommandSource> groups = (ctx, builder) ->
			SharedSuggestionProvider.suggest(StructureStyles.groups().stream().map(StructureStyles.Group::key), builder);

		dispatcher.register(literal("seedmap")
			.executes(SeedMapCommands::status)
			.then(literal("config").executes(ctx -> {
				// The chat screen closes right after the command; open ours on the next tick.
				Minecraft mc = ctx.getSource().getClient();
				mc.schedule(() -> mc.gui.setScreen(new SeedMapConfigScreen(null)));
				return 1;
			}))
			.then(literal("seed")
				.executes(SeedMapCommands::status)
				.then(argument("seed", StringArgumentType.greedyString()).executes(SeedMapCommands::setSeed)))
			.then(literal("clearseed").executes(SeedMapCommands::clearSeed))
			.then(literal("preset")
				.then(argument("preset", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PRESETS, builder))
					.executes(SeedMapCommands::setPreset)))
			.then(literal("on").executes(ctx -> toggle(ctx, c -> c.enabled = true, "jm_seedmap.cmd.enabled")))
			.then(literal("off").executes(ctx -> toggle(ctx, c -> c.enabled = false, "jm_seedmap.cmd.disabled")))
			.then(literal("minimap")
				.then(literal("on").executes(ctx -> toggle(ctx, c -> c.showOnMinimap = true, "jm_seedmap.cmd.minimap_on")))
				.then(literal("off").executes(ctx -> toggle(ctx, c -> c.showOnMinimap = false, "jm_seedmap.cmd.minimap_off"))))
			.then(literal("labels")
				.then(literal("on").executes(ctx -> toggle(ctx, c -> c.showLabels = true, "jm_seedmap.cmd.labels_on")))
				.then(literal("off").executes(ctx -> toggle(ctx, c -> c.showLabels = false, "jm_seedmap.cmd.labels_off"))))
			.then(literal("show")
				.then(argument("structure", StringArgumentType.greedyString()).suggests(structureIds)
					.executes(ctx -> setStructure(ctx, true))))
			.then(literal("hide")
				.then(argument("structure", StringArgumentType.greedyString()).suggests(structureIds)
					.executes(ctx -> setStructure(ctx, false))))
			.then(literal("list").executes(SeedMapCommands::list))
			.then(literal("nearest")
				.then(literal("biome")
					.then(argument("biome", IdentifierArgument.id()).suggests(biomes).executes(SeedMapCommands::nearestBiome)))
				.then(argument("type", StringArgumentType.word()).suggests(groups).executes(SeedMapCommands::nearest)))
			.then(literal("target")
				.then(literal("clear").executes(ctx -> {
					SeedMap.get().clearTarget();
					ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.target_cleared"));
					return 1;
				})))
			.then(literal("pins")
				.executes(ctx -> {
					Minecraft mc = ctx.getSource().getClient();
					mc.schedule(() -> mc.gui.setScreen(new ru.per.jmseedmap.ui.PinsScreen(null)));
					return 1;
				})
				.then(literal("show").executes(ctx -> toggle(ctx, c -> c.showPins = true, "jm_seedmap.cmd.pins_on")))
				.then(literal("hide").executes(ctx -> toggle(ctx, c -> c.showPins = false, "jm_seedmap.cmd.pins_off")))
				.then(literal("clear").executes(ctx -> {
					int n = SeedMap.get().clearPins();
					ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.pins_cleared", n));
					return n;
				})))
			.then(literal("visited")
				.then(literal("clear").executes(ctx -> {
					int n = SeedMap.get().clearVisited();
					ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.visited_cleared", n));
					return n;
				}))
				.then(literal("mode")
					.then(argument("mode", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("show", "dim", "hide"), builder))
						.executes(SeedMapCommands::visitedMode))))
			.then(literal("slime")
				.then(literal("on").executes(ctx -> toggle(ctx, c -> c.showSlimeChunks = true, "jm_seedmap.cmd.slime_on")))
				.then(literal("off").executes(ctx -> toggle(ctx, c -> c.showSlimeChunks = false, "jm_seedmap.cmd.slime_off")))
				.then(literal("here").executes(SeedMapCommands::slimeHere)))
			.then(literal("profile")
				.executes(SeedMapCommands::profiles)
				.then(literal("list").executes(SeedMapCommands::profiles))
				.then(literal("use")
					.then(argument("name", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ru.per.jmseedmap.core.SeedProfiles.names(
							ru.per.jmseedmap.core.SeedProfiles.address()), builder))
						.executes(ctx -> {
							String name = StringArgumentType.getString(ctx, "name");
							ru.per.jmseedmap.core.SeedProfiles.use(name);
							ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.profile.used", name));
							return 1;
						})))
				.then(literal("new")
					.executes(ctx -> newProfile(ctx, null))
					.then(argument("name", StringArgumentType.word()).executes(ctx -> newProfile(ctx, StringArgumentType.getString(ctx, "name")))))
				.then(literal("delete")
					.then(argument("name", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ru.per.jmseedmap.core.SeedProfiles.names(
							ru.per.jmseedmap.core.SeedProfiles.address()), builder))
						.executes(ctx -> {
							String name = StringArgumentType.getString(ctx, "name");
							boolean ok = ru.per.jmseedmap.core.SeedProfiles.delete(name);
							ctx.getSource().sendFeedback(Component.translatable(ok ? "jm_seedmap.profile.deleted" : "jm_seedmap.profile.not_deleted", name));
							return ok ? 1 : 0;
						}))))
			.then(literal("hud")
				.executes(ctx -> {
					SeedMap.get().toggleHud();
					return 1;
				})
				.then(literal("on").executes(ctx -> toggle(ctx, c -> c.hudEnabled = true, "jm_seedmap.cmd.hud_on")))
				.then(literal("off").executes(ctx -> toggle(ctx, c -> c.hudEnabled = false, "jm_seedmap.cmd.hud_off"))))
			.then(literal("nearby").executes(ctx -> {
				Minecraft mc = ctx.getSource().getClient();
				mc.schedule(() -> mc.gui.setScreen(ru.per.jmseedmap.ui.SearchScreen.nearby(null)));
				return 1;
			}))
			.then(literal("farm")
				.then(literal("stop").executes(ctx -> {
					SeedMap.get().farm.stop(true);
					return 1;
				}))
				.then(argument("type", StringArgumentType.word()).suggests(groups)
					.executes(ctx -> farm(ctx, 3))
					.then(argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10))
						.executes(ctx -> farm(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"))))))
			.then(literal("biomes")
				.then(literal("highlight")
					.then(argument("biome", IdentifierArgument.id()).suggests(biomes).executes(ctx -> {
						var id = ctx.getArgument("biome", net.minecraft.resources.Identifier.class);
						boolean on = !ru.per.jmseedmap.core.BiomeLayer.highlighted().contains(id);
						ru.per.jmseedmap.core.BiomeLayer.setHighlighted(id, on);
						SeedMapConfig.get().showBiomes |= on;
						SeedMapConfig.save();
						ctx.getSource().sendFeedback(Component.translatable(on ? "jm_seedmap.cmd.highlight_on" : "jm_seedmap.cmd.highlight_off",
							ru.per.jmseedmap.ui.SearchScreen.biomeNameOf(id)));
						return 1;
					})))
				.then(literal("clear").executes(ctx -> {
					ru.per.jmseedmap.core.BiomeLayer.clearHighlight();
					ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.highlight_cleared"));
					return 1;
				}))
				.then(literal("on").executes(ctx -> toggle(ctx, c -> c.showBiomes = true, "jm_seedmap.cmd.biomes_on")))
				.then(literal("off").executes(ctx -> toggle(ctx, c -> c.showBiomes = false, "jm_seedmap.cmd.biomes_off"))))
			.then(literal("datapacks").executes(SeedMapCommands::datapacks))
			.then(literal("cache")
				.then(literal("clear").executes(ctx -> {
					long bytes = SeedMap.get().index.clearDiskCache();
					ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.cache_cleared", bytes / 1024));
					return 1;
				})))
			.then(literal("perf")
				.then(argument("level", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("auto", "low", "normal", "high"), builder))
					.executes(SeedMapCommands::perf)))
			.then(literal("check").executes(ctx -> {
				SeedMap.get().seedCheck.run(ctx.getSource().getClient(), SeedMap.get().index, false);
				return 1;
			}))
			.then(literal("find")
				.executes(ctx -> {
					SeedMap.get().startSeedSearch(null);
					return 1;
				})
				.then(literal("structure")
					.then(argument("structureSeed", LongArgumentType.longArg()).executes(ctx -> {
						SeedMap.get().startSeedSearch(LongArgumentType.getLong(ctx, "structureSeed"));
						return 1;
					})))
				.then(literal("stop").executes(ctx -> {
					SeedMap.get().seedFinder.cancel();
					ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.find.stopped"));
					return 1;
				}))
				.then(literal("status").executes(SeedMapCommands::findStatus)))
			.then(literal("search").executes(ctx -> {
				Minecraft mc = ctx.getSource().getClient();
				mc.schedule(() -> mc.gui.setScreen(new ru.per.jmseedmap.ui.SearchScreen(null)));
				return 1;
			}))
			.then(literal("reload").executes(ctx -> {
				SeedMap.get().resetStructures();
				ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.reloaded"));
				return 1;
			})));
	}

	private static int farm(CommandContext<FabricClientCommandSource> ctx, int count) {
		String key = StringArgumentType.getString(ctx, "type");
		StructureStyles.Group group = StructureStyles.group(key);
		if (group == null) {
			ctx.getSource().sendError(Component.translatable("jm_seedmap.cmd.unknown_type", key));
			return 0;
		}
		SeedMap.get().farm.start(group, count);
		return 1;
	}

	private static int newProfile(CommandContext<FabricClientCommandSource> ctx, String name) {
		if (ctx.getSource().getClient().getSingleplayerServer() != null) {
			ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.sp_auto"));
			return 0;
		}
		String created = ru.per.jmseedmap.core.SeedProfiles.create(name);
		ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.profile.created", created)
			.withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand("/seedmap seed "))));
		return 1;
	}

	private static int profiles(CommandContext<FabricClientCommandSource> ctx) {
		String address = ru.per.jmseedmap.core.SeedProfiles.address();
		String active = ru.per.jmseedmap.core.SeedProfiles.active(address);
		ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.profile.list", address).withStyle(ChatFormatting.GOLD));
		for (String name : ru.per.jmseedmap.core.SeedProfiles.names(address)) {
			Long seed = SeedMapConfig.get().seeds.get(ru.per.jmseedmap.core.SeedProfiles.key(address, name));
			MutableComponent line = Component.literal((name.equals(active) ? "▶ " : "  ") + name + ": ")
				.append(seed == null ? Component.translatable("jm_seedmap.profile.no_seed") : Component.literal(seed.toString()));
			line.withStyle(style -> style.withColor(name.equals(active) ? ChatFormatting.GREEN : ChatFormatting.WHITE)
				.withClickEvent(new ClickEvent.RunCommand("/seedmap profile use " + name)));
			ctx.getSource().sendFeedback(line);
		}
		return 1;
	}

	private static int perf(CommandContext<FabricClientCommandSource> ctx) {
		String value = StringArgumentType.getString(ctx, "level").toUpperCase(Locale.ROOT);
		try {
			SeedMapConfig.get().performance = SeedMapConfig.Performance.valueOf(value);
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendError(Component.literal("auto | low | normal | high"));
			return 0;
		}
		SeedMapConfig.save();
		ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.perf", ru.per.jmseedmap.core.Perf.current().name().toLowerCase(Locale.ROOT)));
		return 1;
	}

	/** Opens (and creates) the data pack folder of this server and lists what is loaded from it. */
	private static int datapacks(CommandContext<FabricClientCommandSource> ctx) {
		Minecraft mc = ctx.getSource().getClient();
		if (mc.getSingleplayerServer() != null) {
			ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.datapacks.sp"));
			return 1;
		}
		java.nio.file.Path dir = GenContextProvider.packDir(GenContextProvider.serverKey());
		try {
			java.nio.file.Files.createDirectories(dir);
		} catch (java.io.IOException e) {
			ctx.getSource().sendError(Component.literal(e.toString()));
			return 0;
		}
		net.minecraft.util.Util.getPlatform().openPath(dir);
		List<String> loaded = SeedMap.get().index.contexts().loadedPacks();
		ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.datapacks.folder", dir.toString()));
		ctx.getSource().sendFeedback(loaded.isEmpty()
			? Component.translatable("jm_seedmap.datapacks.none")
			: Component.translatable("jm_seedmap.datapacks.loaded", String.join(", ", loaded)));
		ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.datapacks.hint").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource source = ctx.getSource();
		Minecraft mc = source.getClient();
		SeedMapConfig config = SeedMapConfig.get();
		SeedMap seedMap = SeedMap.get();
		source.sendFeedback(Component.translatable("jm_seedmap.cmd.title").withStyle(ChatFormatting.GOLD));
		source.sendFeedback(Component.translatable(config.enabled ? "jm_seedmap.cmd.state_on" : "jm_seedmap.cmd.state_off"));
		if (mc.getSingleplayerServer() != null) {
			source.sendFeedback(Component.translatable("jm_seedmap.cmd.source_sp", seedComponent(mc.getSingleplayerServer().overworld().getSeed())));
		} else {
			String key = GenContextProvider.serverKey();
			Long seed = config.seeds.get(key);
			if (seed == null) {
				source.sendFeedback(Component.translatable("jm_seedmap.cmd.source_none", key).withStyle(ChatFormatting.YELLOW));
			} else {
				source.sendFeedback(Component.translatable("jm_seedmap.cmd.source_mp", key, seedComponent(seed), GenContextProvider.preset(key)));
			}
		}
		String maps = String.join(", ", seedMap.backends().stream().map(MapBackend::name).toList());
		source.sendFeedback(Component.translatable("jm_seedmap.cmd.maps", maps.isEmpty() ? "-" : maps));
		if (mc.level != null) {
			GenContextProvider.Status status = seedMap.index.status(mc.level.dimension());
			String statusKey = status == null ? "jm_seedmap.status.map_closed" : "jm_seedmap.status." + status.name().toLowerCase(Locale.ROOT);
			source.sendFeedback(Component.translatable("jm_seedmap.cmd.dimension", mc.level.dimension().identifier().toString(),
				Component.translatable(statusKey), seedMap.index.pendingTiles()));
			source.sendFeedback(Component.translatable("jm_seedmap.cmd.pins_count", seedMap.pins().size()));
			source.sendFeedback(seedMap.seedCheck.describe());
		}
		return 1;
	}

	private static MutableComponent seedComponent(long seed) {
		String text = Long.toString(seed);
		return Component.literal(text).withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.CopyToClipboard(text))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.copy.click"))));
	}

	private static int setSeed(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource source = ctx.getSource();
		if (source.getClient().getSingleplayerServer() != null) {
			source.sendError(Component.translatable("jm_seedmap.cmd.sp_auto"));
			return 0;
		}
		OptionalLong seed = WorldOptions.parseSeed(StringArgumentType.getString(ctx, "seed"));
		if (seed.isEmpty()) {
			source.sendError(Component.translatable("jm_seedmap.cmd.bad_seed"));
			return 0;
		}
		String key = GenContextProvider.serverKey();
		SeedMapConfig.get().seeds.put(key, seed.getAsLong());
		SeedMapConfig.save();
		SeedMap.get().resetStructures();
		SeedMap.get().scheduleSeedCheck();
		source.sendFeedback(Component.translatable("jm_seedmap.cmd.seed_set", seedComponent(seed.getAsLong()), key));
		return 1;
	}

	private static int clearSeed(CommandContext<FabricClientCommandSource> ctx) {
		String key = GenContextProvider.serverKey();
		SeedMapConfig.get().seeds.remove(key);
		SeedMapConfig.save();
		SeedMap.get().resetStructures();
		ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.seed_cleared", key));
		return 1;
	}

	private static int setPreset(CommandContext<FabricClientCommandSource> ctx) {
		String preset = StringArgumentType.getString(ctx, "preset");
		if (!PRESETS.contains(preset)) {
			ctx.getSource().sendError(Component.translatable("jm_seedmap.cmd.bad_preset", String.join(", ", PRESETS)));
			return 0;
		}
		String key = GenContextProvider.serverKey();
		SeedMapConfig.get().presets.put(key, "minecraft:" + preset);
		SeedMapConfig.save();
		SeedMap.get().resetStructures();
		ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.preset_set", preset, key));
		return 1;
	}

	private static int toggle(CommandContext<FabricClientCommandSource> ctx, Consumer<SeedMapConfig> change, String message) {
		change.accept(SeedMapConfig.get());
		SeedMapConfig.save();
		SeedMap.get().invalidateVisuals();
		ctx.getSource().sendFeedback(Component.translatable(message));
		return 1;
	}

	private static int setStructure(CommandContext<FabricClientCommandSource> ctx, boolean visible) {
		String id = StringArgumentType.getString(ctx, "structure").trim();
		SeedMapConfig config = SeedMapConfig.get();
		String shownName;
		StructureStyles.Group group = StructureStyles.group(id);
		if (id.equals("all")) {
			for (String known : StructureStyles.knownIds()) {
				StructureStyles.setEnabled(known, visible);
			}
			config.structures.replaceAll((k, v) -> visible);
			StructureStyles.filtersChanged();
			shownName = id;
		} else if (group != null) {
			group.setEnabled(visible);
			shownName = group.displayName();
		} else {
			if (!id.contains(":")) {
				id = "minecraft:" + id;
			}
			StructureStyles.setEnabled(id, visible);
			shownName = StructureStyles.displayName(id);
		}
		SeedMapConfig.save();
		SeedMap.get().invalidateVisuals();
		ctx.getSource().sendFeedback(Component.translatable(visible ? "jm_seedmap.cmd.shown" : "jm_seedmap.cmd.hidden", shownName));
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> ctx) {
		ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.list_title").withStyle(ChatFormatting.GOLD));
		for (StructureStyles.Group group : StructureStyles.groups()) {
			boolean on = group.isEnabled();
			String command = "/seedmap " + (on ? "hide " : "show ") + group.key();
			ctx.getSource().sendFeedback(Component.literal(on ? "[✔] " : "[✘] ")
				.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED)
				.append(Component.literal(group.displayName() + " ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(group.key()).withStyle(ChatFormatting.DARK_GRAY))
				.withStyle(style -> style
					.withClickEvent(new ClickEvent.RunCommand(command))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(command)))));
		}
		return 1;
	}

	private static int nearest(CommandContext<FabricClientCommandSource> ctx) {
		String type = StringArgumentType.getString(ctx, "type");
		StructureStyles.Group group = StructureStyles.group(type);
		if (group == null) {
			String id = type.contains(":") ? type : "minecraft:" + type;
			group = StructureStyles.groupOf(id);
		}
		StructureStyles.Group target = group;
		SeedMap.get().findNearest(target::contains, target.displayName());
		return 1;
	}

	private static int nearestBiome(CommandContext<FabricClientCommandSource> ctx) {
		net.minecraft.resources.Identifier id = ctx.getArgument("biome", net.minecraft.resources.Identifier.class);
		SeedMap.get().findNearestBiome(id, ru.per.jmseedmap.ui.SearchScreen.biomeNameOf(id));
		return 1;
	}

	private static int findStatus(CommandContext<FabricClientCommandSource> ctx) {
		var finder = SeedMap.get().seedFinder;
		if (finder.isRunning()) {
			ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.find.progress", finder.progress(), finder.elapsedMillis() / 1000));
		} else if (finder.found() != null) {
			ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.find.done", finder.found()));
		} else {
			ctx.getSource().sendFeedback(Component.translatable(finder.finished() ? "jm_seedmap.find.idle_none" : "jm_seedmap.find.idle"));
		}
		return 1;
	}

	private static int slimeHere(CommandContext<FabricClientCommandSource> ctx) {
		Minecraft mc = ctx.getSource().getClient();
		if (mc.level == null || mc.player == null) {
			return 0;
		}
		var genContext = SeedMap.get().index.context(mc.level.dimension());
		if (genContext == null) {
			ctx.getSource().sendError(Component.translatable("jm_seedmap.status.no_seed"));
			return 0;
		}
		if (GenContextProvider.resolveStem(mc.level.dimension(), mc.level) != net.minecraft.world.level.dimension.LevelStem.OVERWORLD) {
			ctx.getSource().sendError(Component.translatable("jm_seedmap.cmd.slime_overworld"));
			return 0;
		}
		var chunk = mc.player.chunkPosition();
		boolean slime = ru.per.jmseedmap.core.SlimeChunks.isSlimeChunk(genContext.seed(), chunk.x(), chunk.z());
		ctx.getSource().sendFeedback(Component.translatable(slime ? "jm_seedmap.cmd.slime_yes" : "jm_seedmap.cmd.slime_no", chunk.x(), chunk.z())
			.withStyle(slime ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		return slime ? 1 : 0;
	}

	private static int visitedMode(CommandContext<FabricClientCommandSource> ctx) {
		String mode = StringArgumentType.getString(ctx, "mode").toUpperCase(Locale.ROOT);
		try {
			SeedMapConfig.get().visitedMode = SeedMapConfig.VisitedMode.valueOf(mode);
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendError(Component.literal("show | dim | hide"));
			return 0;
		}
		SeedMapConfig.save();
		SeedMap.get().invalidateVisuals();
		ctx.getSource().sendFeedback(Component.translatable("jm_seedmap.cmd.visited_mode",
			Component.translatable("jm_seedmap.visited." + mode.toLowerCase(Locale.ROOT))));
		return 1;
	}
}
