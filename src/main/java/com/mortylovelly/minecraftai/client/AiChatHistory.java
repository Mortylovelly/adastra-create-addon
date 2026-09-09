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
import java.util.Collections;
import java.util.List;

public final class AiChatHistory {
    private static final Path HISTORY_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("minecraft_ai_agent_history.json");

    private static final int MAX_STORED_MESSAGES = 60;
    private static final int MAX_STORED_TOTAL_CHARS = 30000;

    public record Entry(String role, String text) {}

    private static final List<Entry> HISTORY = new ArrayList<>();
    private static boolean loaded;

    private AiChatHistory() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        HISTORY.clear();

        if (!Files.exists(HISTORY_PATH)) return;

        try {
            String json = Files.readString(HISTORY_PATH, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return;

            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (!object.has("role") || !object.has("text")) continue;
                String role = object.get("role").getAsString();
                String text = object.get("text").getAsString();
                if (!(role.equals("user") || role.equals("assistant"))) continue;
                if (text.isBlank()) continue;
                HISTORY.add(new Entry(role, text));
            }
            trimStorage();
        } catch (Exception exception) {
            System.err.println("[Minecraft AI Agent] Could not read chat history: " + exception.getMessage());
        }
    }

    public static synchronized List<Entry> getAll() {
        load();
        return List.copyOf(HISTORY);
    }

    public static synchronized List<Entry> getRecentForApi(int maxMessages, int maxChars) {
        load();
        if (HISTORY.isEmpty() || maxMessages <= 0 || maxChars <= 0) return List.of();

        List<Entry> result = new ArrayList<>();
        int chars = 0;

        for (int i = HISTORY.size() - 1; i >= 0 && result.size() < maxMessages; i--) {
            Entry entry = HISTORY.get(i);
            String text = entry.text();
            int remaining = maxChars - chars;
            if (remaining <= 0) break;

            if (text.length() > remaining) {
                text = text.substring(Math.max(0, text.length() - remaining));
            }

            result.add(new Entry(entry.role(), text));
            chars += text.length();
        }

        Collections.reverse(result);
        return result;
    }

    public static synchronized void add(String role, String text) {
        load();
        if (!(role.equals("user") || role.equals("assistant"))) return;
        if (text == null || text.isBlank()) return;

        HISTORY.add(new Entry(role, text));
        trimStorage();
        save();
    }

    public static synchronized void clear() {
        load();
        HISTORY.clear();
        save();
    }

    private static void trimStorage() {
        while (HISTORY.size() > MAX_STORED_MESSAGES) {
            HISTORY.remove(0);
        }

        int chars = totalChars();
        while (chars > MAX_STORED_TOTAL_CHARS && HISTORY.size() > 1) {
            chars -= HISTORY.get(0).text().length();
            HISTORY.remove(0);
        }
    }

    private static int totalChars() {
        int total = 0;
        for (Entry entry : HISTORY) total += entry.text().length();
        return total;
    }

    private static void save() {
        JsonArray array = new JsonArray();
        for (Entry entry : HISTORY) {
            JsonObject object = new JsonObject();
            object.addProperty("role", entry.role());
            object.addProperty("text", entry.text());
            array.add(object);
        }

        try {
            Files.createDirectories(HISTORY_PATH.getParent());
            Files.writeString(HISTORY_PATH, array.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            System.err.println("[Minecraft AI Agent] Could not save chat history: " + exception.getMessage());
        }
    }
}
