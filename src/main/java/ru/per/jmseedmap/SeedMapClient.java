package ru.per.jmseedmap;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.gen.GenContextProvider;
import ru.per.jmseedmap.ui.SearchScreen;
import ru.per.jmseedmap.ui.SeedMapConfigScreen;

public final class SeedMapClient implements ClientModInitializer {
	public static final String MOD_ID = "jm_seedmap";
	public static final Logger LOGGER = LoggerFactory.getLogger("SeedMap");

	@Override
	public void onInitializeClient() {
		SeedMapConfig.load();
		SeedMap seedMap = SeedMap.get();

		// Xaero integrations only touch Xaero classes when the mod is actually there. A Xaero version with a changed
		// API must not take the game down: the integration is skipped and the rest keeps working.
		if (FabricLoader.getInstance().isModLoaded("xaerominimap") || FabricLoader.getInstance().isModLoaded("xaerominimapfair")) {
			addSafely(seedMap, "Xaero's Minimap", () -> new ru.per.jmseedmap.map.xaero.XaeroMinimapBackend());
		}
		if (FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
			addSafely(seedMap, "Xaero's World Map", () -> new ru.per.jmseedmap.map.xaero.XaeroWorldMapBackend());
		}
		// JourneyMap registers itself through the "journeymap" entrypoint (SeedMapPlugin).

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.jm_seedmap.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, category));
		KeyMapping nearestKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.jm_seedmap.nearest", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PERIOD, category));
		KeyMapping settingsKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.jm_seedmap.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, category));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> SeedMapCommands.register(dispatcher));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.consumeClick()) {
				seedMap.toggleLayer();
			}
			while (nearestKey.consumeClick()) {
				client.gui.setScreen(new SearchScreen(null));
			}
			while (settingsKey.consumeClick()) {
				client.gui.setScreen(new SeedMapConfigScreen(null));
			}
			seedMap.tick(client);
			SelfTest.tick(client);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			seedMap.resetStructures();
			ru.per.jmseedmap.compat.VersionCheck.onJoin(client);
			SelfTest.onJoin(client);
			if (client.getSingleplayerServer() == null && SeedMapConfig.get().enabled
				&& !SeedMapConfig.get().seeds.containsKey(GenContextProvider.serverKey())) {
				client.gui.chatListener().handleSystemMessage(Component.translatable("jm_seedmap.hint.enter_seed")
					.withStyle(style -> style.withColor(ChatFormatting.YELLOW).withClickEvent(new ClickEvent.SuggestCommand("/seedmap seed "))), false);
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> seedMap.onDisconnect());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> seedMap.shutdown());
	}

	private static void addSafely(SeedMap seedMap, String name, java.util.function.Supplier<ru.per.jmseedmap.map.MapBackend> backend) {
		try {
			seedMap.addBackend(backend.get());
		} catch (Throwable t) {
			LOGGER.error("Cannot enable the {} integration (unsupported version?); the other maps still work", name, t);
		}
	}
}
