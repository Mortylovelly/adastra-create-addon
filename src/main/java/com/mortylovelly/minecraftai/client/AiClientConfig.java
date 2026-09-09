package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AiClientConfig {
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("minecraft_ai_agent.json");

    private static String apiKey = "";

    private AiClientConfig() {}

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            if (object.has("api_key")) apiKey = object.get("api_key").getAsString().trim();
        } catch (Exception exception) {
            System.err.println("[Minecraft AI Agent] Could not read config: " + exception.getMessage());
        }
    }

    public static String getApiKey() { return apiKey; }
    public static boolean hasApiKey() { return !apiKey.isBlank(); }

    public static void setApiKey(String value) {
        apiKey = value == null ? "" : value.trim();
        JsonObject object = new JsonObject();
        object.addProperty("api_key", apiKey);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, object.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            System.err.println("[Minecraft AI Agent] Could not save config: " + exception.getMessage());
        }
    }
}
