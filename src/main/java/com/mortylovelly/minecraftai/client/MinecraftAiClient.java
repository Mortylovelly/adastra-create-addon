package com.mortylovelly.minecraftai.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class MinecraftAiClient implements ClientModInitializer {
    public static final String MOD_ID = "minecraft_ai_agent";
    public static final KeyBinding OPEN_AI_SCREEN = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                    "key.minecraft_ai_agent.open_screen",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_O,
                    "category.minecraft_ai_agent"
            )
    );

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_AI_SCREEN.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new MinecraftAiScreen());
                }
            }
        });
    }
}
