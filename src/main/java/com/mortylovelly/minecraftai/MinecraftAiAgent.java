package com.mortylovelly.minecraftai;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

public final class MinecraftAiAgent implements ModInitializer {
    public static final String MOD_ID = "minecraft_ai_agent";

    @Override
    public void onInitialize() {
        AiBridgeServer.startLifecycleHooks();

        ServerLifecycleEvents.SERVER_STARTED.register(AiBridgeServer::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(AiBridgeServer::onServerStopping);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("aiagent")
                        .then(CommandManager.literal("status")
                                .executes(context -> {
                                    boolean connected = AiBridgeServer.isConnected();
                                    context.getSource().sendFeedback(
                                            () -> Text.literal("AI bridge: " + (connected ? "connected" : "not connected")),
                                            false
                                    );
                                    return connected ? 1 : 0;
                                }))));
    }
}
