package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AiAgentMemory {
    private static final Path MEMORY_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("minecraft_ai_agent_memory.json");
    private static final int MAX_MEMORY_ITEMS = 80;
    private static final int MAX_MEMORY_ITEM_CHARS = 500;
    private static final int MAX_PROMPT_CHARS = 5000;

    private static final Map<String, List<String>> MEMORY_BY_CHAT = new LinkedHashMap<>();
    private static boolean loaded;

    private AiAgentMemory() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        MEMORY_BY_CHAT.clear();
        if (!Files.exists(MEMORY_PATH)) return;

        try {
            JsonElement root = JsonParser.parseString(Files.readString(MEMORY_PATH, StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().has("chats")) {
                JsonObject chats = root.getAsJsonObject().getAsJsonObject("chats");
                for (Map.Entry<String, JsonElement> chat : chats.entrySet()) {
                    MEMORY_BY_CHAT.put(chat.getKey(), parseList(chat.getValue()));
                }
            } else if (root.isJsonArray()) {
                // Migrate the old global memory into the first chat when history creates it.
                MEMORY_BY_CHAT.put("legacy", parseList(root));
                save();
            }
        } catch (Exception exception) {
            System.err.println("[Minecraft AI Agent] Could not read memory: " + exception.getMessage());
        }
    }

    public static synchronized boolean remember(String chatId, String text) {
        load();
        String normalized = normalize(text);
        if (normalized.isBlank()) return false;
        List<String> memory = MEMORY_BY_CHAT.computeIfAbsent(chatId, key -> new ArrayList<>());
        for (String existing : memory) if (existing.equalsIgnoreCase(normalized)) return false;
        if (memory.size() >= MAX_MEMORY_ITEMS) memory.remove(0);
        memory.add(normalized);
        save();
        return true;
    }

    public static synchronized boolean forget(String chatId, String text) {
        load();
        String normalized = normalize(text);
        List<String> memory = MEMORY_BY_CHAT.get(chatId);
        if (memory == null) return false;
        boolean removed = memory.removeIf(existing -> existing.equalsIgnoreCase(normalized));
        if (removed) save();
        return removed;
    }

    public static synchronized void clear(String chatId) {
        load();
        MEMORY_BY_CHAT.remove(chatId);
        save();
    }

    public static synchronized int count(String chatId) {
        load();
        return MEMORY_BY_CHAT.getOrDefault(chatId, List.of()).size();
    }

    public static synchronized String forPrompt(String chatId) {
        load();
        List<String> memory = MEMORY_BY_CHAT.get(chatId);
        if (memory == null || memory.isEmpty()) {
            if ("legacy".equals(chatId)) return "нет сохранённых заметок";
            List<String> legacy = MEMORY_BY_CHAT.get("legacy");
            if (legacy == null || legacy.isEmpty()) return "нет сохранённых заметок";
            memory = legacy;
        }

        StringBuilder result = new StringBuilder();
        int remaining = MAX_PROMPT_CHARS;
        for (int i = memory.size() - 1; i >= 0; i--) {
            String line = "- " + memory.get(i) + "\n";
            if (line.length() > remaining) break;
            result.insert(0, line);
            remaining -= line.length();
        }
        return result.toString().trim();
    }

    public static synchronized boolean migrateLegacyToChat(String chatId) {
        load();
        List<String> legacy = MEMORY_BY_CHAT.remove("legacy");
        if (legacy == null || legacy.isEmpty()) return false;
        List<String> target = MEMORY_BY_CHAT.computeIfAbsent(chatId, key -> new ArrayList<>());
        for (String text : legacy) {
            if (target.size() >= MAX_MEMORY_ITEMS) break;
            if (target.stream().noneMatch(existing -> existing.equalsIgnoreCase(text))) target.add(text);
        }
        save();
        return true;
    }

    private static List<String> parseList(JsonElement element) {
        List<String> result = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return result;
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonPrimitive()) continue;
            String normalized = normalize(value.getAsString());
            if (!normalized.isBlank() && result.stream().noneMatch(existing -> existing.equalsIgnoreCase(normalized))) result.add(normalized);
            if (result.size() >= MAX_MEMORY_ITEMS) break;
        }
        return result;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() > MAX_MEMORY_ITEM_CHARS
                ? normalized.substring(0, MAX_MEMORY_ITEM_CHARS - 3) + "..."
                : normalized;
    }

    private static void save() {
        JsonObject root = new JsonObject();
        JsonObject chats = new JsonObject();
        for (Map.Entry<String, List<String>> chat : MEMORY_BY_CHAT.entrySet()) {
            JsonArray array = new JsonArray();
            for (String item : chat.getValue()) array.add(item);
            chats.add(chat.getKey(), array);
        }
        root.add("chats", chats);
        try {
            Files.createDirectories(MEMORY_PATH.getParent());
            Files.writeString(MEMORY_PATH, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            System.err.println("[Minecraft AI Agent] Could not save memory: " + exception.getMessage());
        }
    }
}
