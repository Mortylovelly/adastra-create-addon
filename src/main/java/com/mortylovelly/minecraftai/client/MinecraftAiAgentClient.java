package com.mortylovelly.minecraftai.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class MinecraftAiAgentClient implements ClientModInitializer {
    private static KeyBinding openPanelKey;

    @Override
    public void onInitializeClient() {
        AiClientConfig.load();

        openPanelKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.minecraft_ai_agent.open_panel",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                "category.minecraft_ai_agent"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(MinecraftAiAgentClient::tick);
    }

    private static void tick(MinecraftClient client) {
        while (openPanelKey.wasPressed()) {
            if (client.currentScreen == null && client.player != null) {
                client.setScreen(new AiChatScreen());
            }
        }
    }
}
