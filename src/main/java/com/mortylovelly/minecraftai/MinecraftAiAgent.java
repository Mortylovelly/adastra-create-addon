package com.mortylovelly.minecraftai;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

public final class MinecraftAiAgent implements ModInitializer {
    public static final String MOD_ID = "minecraft_ai_agent";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("aiagent")
                        .then(CommandManager.literal("status")
                                .executes(context -> {
                                    context.getSource().sendFeedback(
                                            () -> Text.literal("Minecraft AI Agent: direct in-game AI service"),
                                            false
                                    );
                                    return 1;
                                }))));
    }
}
