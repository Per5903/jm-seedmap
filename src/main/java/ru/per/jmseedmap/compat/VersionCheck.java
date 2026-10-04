package ru.per.jmseedmap.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;

/**
 * World generation differs between game versions, and the mod computes with this client's rules. Two ways to
 * end up on a server of another version are detected:
 * <ul>
 *   <li>ViaFabricPlus is set to another protocol version (read through its API by reflection);</li>
 *   <li>the server lists another version in its status (e.g. "Paper 1.21.11" that lets new clients in through
 *       ViaVersion).</li>
 * </ul>
 */
public final class VersionCheck {
	private static final Pattern VERSION = Pattern.compile("(?<![\\d.])(1\\.\\d{1,2}(?:\\.\\d{1,2})?|[2-9]\\d\\.\\d{1,2}(?:\\.\\d{1,2})?)(?![\\d.]*\\d)");
	private static @Nullable Component warning;
	private static @Nullable String detected;

	private VersionCheck() {
	}

	/** The current warning for the settings screen, or null if the server runs this version. */
	public static @Nullable Component warning() {
		return warning;
	}

	/** Version the server seems to run, or null if it matches. */
	public static @Nullable String detectedVersion() {
		return detected;
	}

	public static void onJoin(Minecraft mc) {
		warning = null;
		detected = null;
		if (mc.getSingleplayerServer() != null) {
			return;
		}
		String current = SharedConstants.getCurrentVersion().name();
		String via = viaFabricPlusTarget();
		if (via != null) {
			detected = via;
			warning = Component.translatable("jm_seedmap.version.via", via, current);
		} else {
			ServerData data = mc.getCurrentServer();
			String other = data == null ? null : otherVersion(data.version.getString(), current);
			if (other != null) {
				detected = other;
				warning = Component.translatable("jm_seedmap.version.server", other, current);
			}
		}
		if (warning != null) {
			SeedMapClient.LOGGER.info("Server version differs from the client: {}", detected);
			mc.gui.chatListener().handleSystemMessage(Component.literal("[SeedMap] ").append(warning).withStyle(ChatFormatting.GOLD), false);
		}
	}

	public static void reset() {
		warning = null;
		detected = null;
	}

	/**
	 * A game version named in the server's status text that is not this one, or null if it names this version
	 * (also inside ranges like "1.8-26.2") or none at all.
	 */
	public static @Nullable String otherVersion(String status, String current) {
		Matcher m = VERSION.matcher(status);
		List<String> found = new ArrayList<>();
		while (m.find()) {
			found.add(m.group(1));
		}
		if (found.isEmpty()) {
			return null;
		}
		String currentRelease = release(current);
		for (String v : found) {
			if (release(v).equals(currentRelease)) {
				return null;
			}
		}
		return found.get(found.size() - 1);
	}

	/** "26.2.1" -> "26.2"; for 1.x versions the patch matters ("1.21.4" stays). */
	private static String release(String version) {
		if (version.startsWith("1.")) {
			return version;
		}
		String[] parts = version.split("\\.");
		return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
	}

	/** Target version name if ViaFabricPlus translates to another protocol, else null. */
	private static @Nullable String viaFabricPlusTarget() {
		if (!FabricLoader.getInstance().isModLoaded("viafabricplus")) {
			return null;
		}
		try {
			Object impl = Class.forName("com.viaversion.viafabricplus.ViaFabricPlus").getMethod("getImpl").invoke(null);
			Object target = impl.getClass().getMethod("getTargetVersion").invoke(impl);
			if (target == null) {
				return null;
			}
			int protocol = (int) target.getClass().getMethod("getVersion").invoke(target);
			if (protocol == SharedConstants.getProtocolVersion()) {
				return null;
			}
			return String.valueOf(target.getClass().getMethod("getName").invoke(target));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			SeedMapClient.LOGGER.debug("Cannot ask ViaFabricPlus for the target version", e);
			return null;
		}
	}
}
