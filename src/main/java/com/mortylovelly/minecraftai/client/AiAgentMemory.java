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
import java.util.List;

public final class AiAgentMemory {
    private static final Path MEMORY_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("minecraft_ai_agent_memory.json");

    private static final int MAX_MEMORY_ITEMS = 40;
    private static final int MAX_MEMORY_ITEM_CHARS = 300;
    private static final int MAX_PROMPT_CHARS = 3000;

    private static final List<String> MEMORY = new ArrayList<>();
    private static boolean loaded;

    private AiAgentMemory() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        MEMORY.clear();

        if (!Files.exists(MEMORY_PATH)) return;

        try {
            String json = Files.readString(MEMORY_PATH, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return;

            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonPrimitive()) continue;
                addLoaded(element.getAsString());
            }
        } catch (Exception exception) {
            System.err.println("[Minecraft AI Agent] Could not read memory: " + exception.getMessage());
        }
    }

    public static synchronized boolean remember(String text) {
        load();
        String normalized = normalize(text);
        if (normalized.isBlank()) return false;

        for (String existing : MEMORY) {
            if (existing.equalsIgnoreCase(normalized)) return false;
        }

        if (MEMORY.size() >= MAX_MEMORY_ITEMS) MEMORY.remove(0);
        MEMORY.add(normalized);
        save();
        return true;
    }

    public static synchronized boolean forget(String text) {
        load();
        String normalized = normalize(text);
        if (normalized.isBlank()) return false;

        boolean removed = MEMORY.removeIf(existing -> existing.equalsIgnoreCase(normalized));
        if (removed) save();
        return removed;
    }

    public static synchronized void clear() {
        load();
        MEMORY.clear();
        save();
    }

    public static synchronized int count() {
        load();
        return MEMORY.size();
    }

    public static synchronized String forPrompt() {
        load();
        if (MEMORY.isEmpty()) return "нет сохранённых заметок";

        StringBuilder result = new StringBuilder();
        int remaining = MAX_PROMPT_CHARS;
        for (int i = MEMORY.size() - 1; i >= 0; i--) {
            String line = "- " + MEMORY.get(i) + "\n";
            if (line.length() > remaining) break;
            result.insert(0, line);
            remaining -= line.length();
        }
        return result.toString().trim();
    }

    private static void addLoaded(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) return;
        for (String existing : MEMORY) {
            if (existing.equalsIgnoreCase(normalized)) return;
        }
        if (MEMORY.size() < MAX_MEMORY_ITEMS) MEMORY.add(normalized);
    }

    private static String normalize(String text) {
        if (text == null) return "";
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() > MAX_MEMORY_ITEM_CHARS) {
            normalized = normalized.substring(0, MAX_MEMORY_ITEM_CHARS - 3) + "...";
        }
        return normalized;
    }

    private static void save() {
        JsonArray array = new JsonArray();
        for (String item : MEMORY) array.add(item);

        try {
            Files.createDirectories(MEMORY_PATH.getParent());
            Files.writeString(MEMORY_PATH, array.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            System.err.println("[Minecraft AI Agent] Could not save memory: " + exception.getMessage());
        }
    }
}
