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

    private static String deepSeekApiKey = "";
    private static String groqApiKey = "";
    private static String provider = "deepseek";

    private AiClientConfig() {}

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();

            if (object.has("deepseek_api_key")) {
                deepSeekApiKey = object.get("deepseek_api_key").getAsString().trim();
            } else if (object.has("api_key")) {
                deepSeekApiKey = object.get("api_key").getAsString().trim();
            }

            if (object.has("groq_api_key")) {
                groqApiKey = object.get("groq_api_key").getAsString().trim();
            }

            if (object.has("provider")) {
                String savedProvider = object.get("provider").getAsString().trim().toLowerCase();
                if (savedProvider.equals("groq") || savedProvider.equals("deepseek")) {
                    provider = savedProvider;
                }
            }
        } catch (Exception exception) {
            System.err.println("[Minecraft AI Agent] Could not read config: " + exception.getMessage());
        }
    }

    public static String getProvider() {
        return provider;
    }

    public static void setProvider(String value) {
        String normalized = value == null ? "deepseek" : value.trim().toLowerCase();
        provider = normalized.equals("groq") ? "groq" : "deepseek";
        save();
    }

    public static String getApiKey() {
        return provider.equals("groq") ? groqApiKey : deepSeekApiKey;
    }

    public static String getDeepSeekApiKey() {
        return deepSeekApiKey;
    }

    public static String getGroqApiKey() {
        return groqApiKey;
    }

    public static boolean hasApiKey() {
        return !getApiKey().isBlank();
    }

    public static void setApiKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (provider.equals("groq")) {
            groqApiKey = normalized;
        } else {
            deepSeekApiKey = normalized;
        }
        save();
    }

    private static void save() {
        JsonObject object = new JsonObject();
        object.addProperty("provider", provider);
        object.addProperty("deepseek_api_key", deepSeekApiKey);
        object.addProperty("groq_api_key", groqApiKey);

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, object.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            System.err.println("[Minecraft AI Agent] Could not save config: " + exception.getMessage());
        }
    }
}
