package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AiClientConfig {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("minecraft_ai_agent.json");

    private static String deepSeekApiKey = "";
    private static String groqApiKey = "";
    private static String openRouterApiKey = "";
    private static String geminiApiKey = "";
    private static String provider = "gemini";

    private AiClientConfig() {}

    public static synchronized void load() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();

            deepSeekApiKey = readString(object, "deepseek_api_key", readString(object, "api_key", ""));
            groqApiKey = readString(object, "groq_api_key", "");
            openRouterApiKey = readString(object, "openrouter_api_key", "");
            geminiApiKey = readString(object, "gemini_api_key", "");

            if (object.has("provider")) {
                String saved = object.get("provider").getAsString().trim().toLowerCase();
                if (isProvider(saved)) provider = saved;
            }
        } catch (Exception exception) {
            System.err.println("[Minecraft AI Agent] Could not read config: " + exception.getMessage());
        }
    }

    public static synchronized String getProvider() { return provider; }

    public static synchronized void setProvider(String value) {
        String normalized = value == null ? "gemini" : value.trim().toLowerCase();
        provider = isProvider(normalized) ? normalized : "gemini";
        save();
    }

    public static synchronized String getApiKey() {
        return switch (provider) {
            case "groq" -> groqApiKey;
            case "openrouter" -> openRouterApiKey;
            case "gemini" -> geminiApiKey;
            default -> deepSeekApiKey;
        };
    }

    public static synchronized String getDeepSeekApiKey() { return deepSeekApiKey; }
    public static synchronized String getGroqApiKey() { return groqApiKey; }
    public static synchronized String getOpenRouterApiKey() { return openRouterApiKey; }
    public static synchronized String getGeminiApiKey() { return geminiApiKey; }

    public static synchronized boolean hasApiKey() { return !getApiKey().isBlank(); }

    public static synchronized void setApiKey(String value) {
        String normalized = value == null ? "" : value.trim();
        switch (provider) {
            case "groq" -> groqApiKey = normalized;
            case "openrouter" -> openRouterApiKey = normalized;
            case "gemini" -> geminiApiKey = normalized;
            default -> deepSeekApiKey = normalized;
        }
        save();
    }

    private static boolean isProvider(String value) {
        return value.equals("gemini") || value.equals("groq") || value.equals("openrouter") || value.equals("deepseek");
    }

    private static String readString(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString().trim() : fallback;
    }

    private static synchronized void save() {
        JsonObject object = new JsonObject();
        object.addProperty("provider", provider);
        object.addProperty("deepseek_api_key", deepSeekApiKey);
        object.addProperty("groq_api_key", groqApiKey);
        object.addProperty("openrouter_api_key", openRouterApiKey);
        object.addProperty("gemini_api_key", geminiApiKey);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, object.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            System.err.println("[Minecraft AI Agent] Could not save config: " + exception.getMessage());
        }
    }
}
