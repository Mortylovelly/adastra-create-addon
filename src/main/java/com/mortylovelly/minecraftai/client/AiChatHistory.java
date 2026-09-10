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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AiChatHistory {
    private static final Path HISTORY_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("minecraft_ai_agent_history.json");
    private static final int MAX_STORED_MESSAGES = 120;
    private static final int MAX_STORED_TOTAL_CHARS = 60000;

    public record Entry(String role, String text) {}

    private static final Map<String, List<Entry>> CHATS = new LinkedHashMap<>();
    private static final Map<String, String> GEMINI_INTERACTION_IDS = new LinkedHashMap<>();
    private static String currentChatId;
    private static boolean loaded;

    private AiChatHistory() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        CHATS.clear();
        GEMINI_INTERACTION_IDS.clear();

        if (!Files.exists(HISTORY_PATH)) {
            currentChatId = newChatId();
            CHATS.put(currentChatId, new ArrayList<>());
            save();
            return;
        }

        try {
            JsonElement root = JsonParser.parseString(Files.readString(HISTORY_PATH, StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().has("chats")) {
                JsonObject chats = root.getAsJsonObject().getAsJsonObject("chats");
                for (Map.Entry<String, JsonElement> chat : chats.entrySet()) {
                    if (!chat.getValue().isJsonObject()) continue;
                    JsonObject chatObject = chat.getValue().getAsJsonObject();
                    List<Entry> entries = parseMessages(chatObject.get("messages"));
                    CHATS.put(chat.getKey(), entries);
                    if (chatObject.has("gemini_interaction_id") && !chatObject.get("gemini_interaction_id").isJsonNull()) {
                        GEMINI_INTERACTION_IDS.put(chat.getKey(), chatObject.get("gemini_interaction_id").getAsString());
                    }
                }
                currentChatId = root.getAsJsonObject().has("current_chat")
                        ? root.getAsJsonObject().get("current_chat").getAsString()
                        : null;
            } else if (root.isJsonArray()) {
                // Migrate the old single global history into one initial chat.
                currentChatId = newChatId();
                CHATS.put(currentChatId, parseMessages(root));
                save();
            }
        } catch (Exception exception) {
            System.err.println("[Minecraft AI Agent] Could not read chat history: " + exception.getMessage());
        }

        if (CHATS.isEmpty()) {
            currentChatId = newChatId();
            CHATS.put(currentChatId, new ArrayList<>());
        } else if (currentChatId == null || !CHATS.containsKey(currentChatId)) {
            currentChatId = CHATS.keySet().iterator().next();
        }
    }

    public static synchronized String getCurrentChatId() {
        load();
        return currentChatId;
    }

    public static synchronized void setCurrentChatId(String chatId) {
        load();
        if (!CHATS.containsKey(chatId)) return;
        currentChatId = chatId;
        save();
    }

    public static synchronized String createChat() {
        load();
        String id = newChatId();
        CHATS.put(id, new ArrayList<>());
        currentChatId = id;
        save();
        return id;
    }

    public static synchronized List<Entry> getAll() {
        return getAll(getCurrentChatId());
    }

    public static synchronized List<Entry> getAll(String chatId) {
        load();
        return List.copyOf(CHATS.getOrDefault(chatId, List.of()));
    }

    public static synchronized List<Entry> getRecentForApi(int maxMessages, int maxChars) {
        return getRecentForApi(getCurrentChatId(), maxMessages, maxChars);
    }

    public static synchronized List<Entry> getRecentForApi(String chatId, int maxMessages, int maxChars) {
        load();
        List<Entry> history = CHATS.getOrDefault(chatId, List.of());
        if (history.isEmpty() || maxMessages <= 0 || maxChars <= 0) return List.of();

        List<Entry> result = new ArrayList<>();
        int chars = 0;
        for (int i = history.size() - 1; i >= 0 && result.size() < maxMessages; i--) {
            Entry entry = history.get(i);
            int remaining = maxChars - chars;
            if (remaining <= 0) break;
            String text = entry.text();
            if (text.length() > remaining) text = text.substring(text.length() - remaining);
            result.add(new Entry(entry.role(), text));
            chars += text.length();
        }
        Collections.reverse(result);
        return result;
    }

    public static synchronized void add(String role, String text) {
        add(getCurrentChatId(), role, text);
    }

    public static synchronized void add(String chatId, String role, String text) {
        load();
        if (!(role.equals("user") || role.equals("assistant"))) return;
        if (text == null || text.isBlank()) return;
        List<Entry> history = CHATS.computeIfAbsent(chatId, key -> new ArrayList<>());
        history.add(new Entry(role, text));
        trimStorage(history);
        save();
    }

    public static synchronized void clear() {
        clear(getCurrentChatId());
    }

    public static synchronized void clear(String chatId) {
        load();
        CHATS.computeIfAbsent(chatId, key -> new ArrayList<>()).clear();
        GEMINI_INTERACTION_IDS.remove(chatId);
        save();
    }

    public static synchronized String getGeminiInteractionId(String chatId) {
        load();
        return GEMINI_INTERACTION_IDS.getOrDefault(chatId, "");
    }

    public static synchronized void setGeminiInteractionId(String chatId, String interactionId) {
        load();
        if (interactionId == null || interactionId.isBlank()) GEMINI_INTERACTION_IDS.remove(chatId);
        else GEMINI_INTERACTION_IDS.put(chatId, interactionId);
        save();
    }

    public static synchronized int getChatCount() {
        load();
        return CHATS.size();
    }

    private static List<Entry> parseMessages(JsonElement element) {
        List<Entry> result = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return result;
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonObject()) continue;
            JsonObject object = value.getAsJsonObject();
            if (!object.has("role") || !object.has("text")) continue;
            String role = object.get("role").getAsString();
            String text = object.get("text").getAsString();
            if ((role.equals("user") || role.equals("assistant")) && !text.isBlank()) result.add(new Entry(role, text));
        }
        trimStorage(result);
        return result;
    }

    private static void trimStorage(List<Entry> history) {
        while (history.size() > MAX_STORED_MESSAGES) history.remove(0);
        int chars = totalChars(history);
        while (chars > MAX_STORED_TOTAL_CHARS && history.size() > 1) {
            chars -= history.get(0).text().length();
            history.remove(0);
        }
    }

    private static int totalChars(List<Entry> history) {
        int total = 0;
        for (Entry entry : history) total += entry.text().length();
        return total;
    }

    private static String newChatId() {
        return UUID.randomUUID().toString();
    }

    private static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("current_chat", currentChatId);
        JsonObject chats = new JsonObject();
        for (Map.Entry<String, List<Entry>> chat : CHATS.entrySet()) {
            JsonObject chatObject = new JsonObject();
            JsonArray messages = new JsonArray();
            for (Entry entry : chat.getValue()) {
                JsonObject object = new JsonObject();
                object.addProperty("role", entry.role());
                object.addProperty("text", entry.text());
                messages.add(object);
            }
            chatObject.add("messages", messages);
            String interactionId = GEMINI_INTERACTION_IDS.get(chat.getKey());
            if (interactionId != null && !interactionId.isBlank()) chatObject.addProperty("gemini_interaction_id", interactionId);
            chats.add(chat.getKey(), chatObject);
        }
        root.add("chats", chats);

        try {
            Files.createDirectories(HISTORY_PATH.getParent());
            Files.writeString(HISTORY_PATH, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            System.err.println("[Minecraft AI Agent] Could not save chat history: " + exception.getMessage());
        }
    }
}
