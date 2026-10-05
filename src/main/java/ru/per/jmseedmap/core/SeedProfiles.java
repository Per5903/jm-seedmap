package ru.per.jmseedmap.core;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.biome.BiomeManager;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;

/**
 * Several worlds behind one server address (a lobby, survival and a resource world on a proxy) each need their own
 * seed. A server has profiles: "default" uses the plain address as its key, others use {@code address#name}. Seeds,
 * world types, data pack folders and per-world pins are all stored under that key.
 * <p>
 * Which world the player is in is recognized by the seed hash the server sends with every world: a profile
 * remembers the hashes seen while it was active, and a profile whose seed produces the hash matches too. A world
 * nobody knows yet triggers a chat prompt to pick or create a profile.
 */
public final class SeedProfiles {
	public static final String DEFAULT = "default";

	private static @Nullable Long lastHash;

	private SeedProfiles() {
	}

	/** The raw server address ("realms:<name>" for Realms). */
	public static String address() {
		ServerData data = Minecraft.getInstance().getCurrentServer();
		if (data == null) {
			return "unknown";
		}
		return data.isRealm() ? "realms:" + data.name : data.ip.toLowerCase(java.util.Locale.ROOT);
	}

	public static String active(String address) {
		return SeedMapConfig.get().activeProfiles.getOrDefault(address, DEFAULT);
	}

	public static String key(String address, String profile) {
		return profile.equals(DEFAULT) ? address : address + "#" + profile;
	}

	/** Key of the active profile of the current server. */
	public static String currentKey() {
		String address = address();
		return key(address, active(address));
	}

	/** Profiles of a server, "default" first. */
	public static List<String> names(String address) {
		SeedMapConfig c = SeedMapConfig.get();
		Set<String> names = new LinkedHashSet<>();
		names.add(DEFAULT);
		String prefix = address + "#";
		for (Map<String, ?> map : List.<Map<String, ?>>of(c.seeds, c.presets, c.profileHashes)) {
			for (String key : map.keySet()) {
				if (key.startsWith(prefix)) {
					names.add(key.substring(prefix.length()));
				}
			}
		}
		names.add(active(address));
		return new ArrayList<>(names);
	}

	/** Switches the current server to {@code profile} and remembers the world the player is in now for it. */
	public static void use(String profile) {
		String address = address();
		SeedMapConfig c = SeedMapConfig.get();
		if (profile.equals(DEFAULT)) {
			c.activeProfiles.remove(address);
		} else {
			c.activeProfiles.put(address, profile);
			c.profileHashes.computeIfAbsent(key(address, profile), k -> new ArrayList<>());
		}
		if (lastHash != null) {
			remember(key(address, profile), lastHash);
		}
		SeedMapConfig.save();
		SeedMap.get().onProfileChanged();
	}

	/** A new empty profile for the current world, named "world 2", "world 3"... unless a name is given. */
	public static String create(@Nullable String name) {
		String address = address();
		List<String> existing = names(address);
		String profile = name == null || name.isBlank() ? null : name.trim().replaceAll("[#\\s]+", "_");
		if (profile == null) {
			int n = existing.size() + 1;
			while (existing.contains("world_" + n)) {
				n++;
			}
			profile = "world_" + n;
		}
		use(profile);
		return profile;
	}

	public static boolean delete(String profile) {
		if (profile.equals(DEFAULT)) {
			return false;
		}
		String address = address();
		String key = key(address, profile);
		SeedMapConfig c = SeedMapConfig.get();
		boolean existed = c.seeds.remove(key) != null | c.presets.remove(key) != null | c.profileHashes.remove(key) != null;
		if (profile.equals(active(address))) {
			c.activeProfiles.remove(address);
			SeedMapConfig.save();
			SeedMap.get().onProfileChanged();
		} else {
			SeedMapConfig.save();
		}
		return existed;
	}

	public static void onDisconnect() {
		lastHash = null;
	}

	/**
	 * Called with the seed hash of the world the player is in, whenever it changes.
	 *
	 * @param worldSwitch true if the player moved to another world without reconnecting (proxy server switch)
	 */
	public static void onHash(long hash) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() != null) {
			return;
		}
		boolean worldSwitch = lastHash != null;
		lastHash = hash;
		String address = address();
		SeedMapConfig c = SeedMapConfig.get();
		List<String> names = names(address);
		String active = active(address);
		String hex = hex(hash);

		// 1. A profile that was active in this world before; 2. a profile whose seed is this world's seed.
		String match = null;
		for (String name : names) {
			List<String> hashes = c.profileHashes.get(key(address, name));
			if (hashes != null && hashes.contains(hex)) {
				match = name;
				break;
			}
		}
		if (match == null) {
			for (String name : names) {
				Long seed = c.seeds.get(key(address, name));
				if (seed != null && BiomeManager.obfuscateSeed(seed) == hash) {
					match = name;
					remember(key(address, name), hash);
					break;
				}
			}
		}
		if (match != null) {
			if (!match.equals(active)) {
				SeedMapClient.LOGGER.info("World with hash {} on {} -> profile {}", hex, address, match);
				use(match);
				message(Component.translatable("jm_seedmap.profile.switched", match).withStyle(ChatFormatting.AQUA));
			}
			return;
		}

		if (!worldSwitch && names.size() == 1) {
			// Joining a server with a single profile: this is its world (also covers servers that hide the hash).
			remember(key(address, active), hash);
			return;
		}
		// Another world than the profiles know: ask.
		MutableComponent prompt = Component.translatable("jm_seedmap.profile.unknown").withStyle(ChatFormatting.GOLD);
		for (String name : names) {
			prompt.append(" ").append(Component.literal("[" + name + "]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent.RunCommand("/seedmap profile use " + name))));
		}
		prompt.append(" ").append(Component.translatable("jm_seedmap.profile.new_button").withStyle(style -> style.withColor(ChatFormatting.AQUA)
			.withClickEvent(new ClickEvent.RunCommand("/seedmap profile new"))));
		message(prompt);
	}

	private static void remember(String key, long hash) {
		List<String> hashes = SeedMapConfig.get().profileHashes.computeIfAbsent(key, k -> new ArrayList<>());
		String hex = hex(hash);
		if (!hashes.contains(hex)) {
			hashes.add(hex);
			// Servers that randomize the hash per login would grow this forever.
			while (hashes.size() > 16) {
				hashes.remove(0);
			}
			SeedMapConfig.save();
		}
	}

	private static String hex(long hash) {
		return HexFormat.of().toHexDigits(hash);
	}

	private static void message(Component text) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.chatListener().handleSystemMessage(Component.literal("[SeedMap] ").append(text), false);
	}
}
