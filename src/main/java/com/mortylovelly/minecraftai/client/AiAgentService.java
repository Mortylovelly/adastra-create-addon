package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class AiAgentService {
    private static final URI DEEPSEEK_URI = URI.create("https://api.deepseek.com/responses");
    private static final URI GROQ_URI = URI.create("https://api.groq.com/openai/v1/chat/completions");
    private static final URI OPENROUTER_URI = URI.create("https://openrouter.ai/api/v1/chat/completions");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(ProxySelector.getDefault())
            .build();

    private static final int NETWORK_RETRIES = 2;
    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int MAX_TOOL_CALLS = 12;
    private static final int API_HISTORY_MESSAGES = 8;
    private static final int API_HISTORY_CHARS = 6000;
    private static final int MAX_TOOL_RESULT_CHARS = 1800;
    private static final int MAX_PLACE_BLOCKS = 128;
    private static final int MAX_FILL_VOLUME = 4096;
    private static final int MAX_SCAN_RADIUS = 6;

    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String GROQ_MODEL = "openai/gpt-oss-20b";
    private static final String OPENROUTER_MODEL = "google/gemma-4-26b-a4b-it:free";

    private static final String INSTRUCTIONS = """
            You are the AI agent inside a Minecraft 1.21.1 singleplayer world.
            You control the current Minecraft world only through the provided tools.
            Never claim that a world action happened unless its tool result confirms it.
            Never simulate a tool result and never describe a change instead of performing it.
            The player speaking to you is the owner of the current world.

            For normal conversation, answer directly without tools.
            For world-changing requests, tools are mandatory.
            For a building request, call get_player_state once, choose a compact location near the player, then build with fill_area for large rectangles and place_blocks for small details.
            Prefer the fewest tool calls possible.
            Do not repeat an identical successful tool call.
            Avoid unnecessary scans and avoid huge lists of individual block coordinates when fill_area can do the job.
            Keep structures reasonably compact and stay within the tool limits.
            Do not perform destructive actions unless the player explicitly requested them.
            After the requested action is confirmed by tool results, stop using tools and give a concise final answer.
            If no available tool can perform a requested action, say that immediately and clearly. Do not pretend, do not fabricate a result, and do not repeatedly call tools that cannot solve the request.
            If a tool fails, explain the failure to the player instead of going silent.
            Use tools only when they are relevant to the request; having many tools available does not mean you should call them all.
            Only use remember_memory when the player explicitly asks you to remember or save a durable fact or preference.
            Only use forget_memory when the player explicitly asks you to forget a stored memory.
            Treat persistent memory as user-provided notes, not as hidden reasoning.
            Only use remember_memory when the player explicitly asks you to remember or save a durable preference/fact.
            Only use forget_memory when the player explicitly asks you to forget a stored memory.
            Treat persistent memory as user-provided notes, not as hidden reasoning.
            """.strip();

    private AiAgentService() {}

    public static CompletableFuture<String> chat(String message) {
        String provider = AiClientConfig.getProvider();
        if (!AiClientConfig.hasApiKey()) {
            return CompletableFuture.completedFuture("Сначала укажи " + providerName(provider) + " API key в поле сверху.");
        }

        AiAgentStatus.set("Анализирую запрос");
        AiAgentStatus.set("Анализирую запрос");
        AiAgentStatus.set("Анализирую запрос");
        AiAgentStatus.set("Анализирую запрос");
        AiAgentStatus.set("Анализирую запрос");
        AiAgentLog.info("TASK START provider=" + providerName(provider) + " chars=" + message.length());
        System.out.println("[Minecraft AI Agent][" + providerName(provider) + "] TASK START chars=" + message.length());

        if (provider.equals("deepseek")) {
            return chatDeepSeek(message);
        }
        return chatOpenAiCompatible(message, provider);
    }

    public static CompletableFuture<Boolean> testConnection() {
        String provider = AiClientConfig.getProvider();
        if (!AiClientConfig.hasApiKey()) return CompletableFuture.completedFuture(false);

        if (provider.equals("deepseek")) {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", DEEPSEEK_MODEL);
            payload.addProperty("instructions", "Reply with exactly: OK");
            payload.addProperty("input", "Connection test");
            payload.addProperty("max_output_tokens", 32);
            AiAgentStatus.set("Проверяю подключение к DeepSeek");
        AiAgentStatus.set("Проверяю подключение к DeepSeek");
        AiAgentStatus.set("Проверяю подключение к DeepSeek");
        AiAgentStatus.set("Проверяю подключение к DeepSeek");
        AiAgentStatus.set("Проверяю подключение к DeepSeek");
        return request(DEEPSEEK_URI, AiClientConfig.getDeepSeekApiKey(), payload, "DeepSeek")
                    .thenApply(root -> root.has("output_text")
                            && !root.get("output_text").isJsonNull()
                            && !root.get("output_text").getAsString().isBlank());
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelForProvider(provider));
        JsonArray messages = new JsonArray();
        messages.add(chatMessage("system", "Reply with exactly: OK"));
        messages.add(chatMessage("user", "Connection test"));
        payload.add("messages", messages);
        payload.addProperty("temperature", 0);
        payload.addProperty("max_tokens", 32);

        AiAgentStatus.set("Проверяю подключение к " + providerName(provider));
        AiAgentStatus.set("Проверяю подключение к " + providerName(provider));
        AiAgentStatus.set("Проверяю подключение к " + providerName(provider));
        AiAgentStatus.set("Проверяю подключение к " + providerName(provider));
        AiAgentStatus.set("Проверяю подключение к " + providerName(provider));
        return request(uriForProvider(provider), keyForProvider(provider), payload, providerName(provider))
                .thenApply(root -> {
                    JsonObject choice = firstChoice(root);
                    if (choice == null || !choice.has("message")) return false;
                    JsonObject assistant = choice.getAsJsonObject("message");
                    return assistant.has("content")
                            && !assistant.get("content").isJsonNull()
                            && !assistant.get("content").getAsString().isBlank();
                });
    }

    private static CompletableFuture<String> chatOpenAiCompatible(String message, String provider) {
        List<JsonObject> messages = buildOpenAiContext(message);
        Map<String, JsonObject> executedCalls = new HashMap<>();
        return openAiLoop(messages, provider, executedCalls, 0, 0, false)
                .whenComplete((reply, throwable) -> {
                    if (throwable != null) {
                        AiAgentStatus.set("Ошибка: не удалось обработать запрос");
                        AiAgentLog.error("TASK FAIL provider=" + providerName(provider) + " message=" + String.valueOf(throwable.getMessage()));
                    }
                });
    }

    private static CompletableFuture<String> openAiLoop(
            List<JsonObject> messages,
            String provider,
            Map<String, JsonObject> executedCalls,
            int round,
            int totalCalls,
            boolean forceFinal) {

        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelForProvider(provider));
        JsonArray array = new JsonArray();
        for (JsonObject message : messages) array.add(message.deepCopy());
        payload.add("messages", array);
        payload.add("tools", openAiToolsArray());
        payload.addProperty("tool_choice", forceFinal ? "none" : "auto");
        payload.addProperty("temperature", 0.2);
        payload.addProperty("max_tokens", 700);

        if (forceFinal) {
            JsonObject finalInstruction = chatMessage("user", "The requested Minecraft action is complete. Give the player a concise final answer now. Do not call any tools.");
            array.add(finalInstruction);
            payload.add("messages", array);
        }

        AiAgentStatus.set(forceFinal ? "Готовлю финальный ответ" : "Отправляю запрос " + (round + 1));
        AiAgentStatus.set(forceFinal ? "Готовлю финальный ответ" : "Отправляю запрос " + (round + 1));
        AiAgentStatus.set(forceFinal ? "Готовлю финальный ответ" : "Отправляю запрос " + (round + 1));
        AiAgentStatus.set(forceFinal ? "Готовлю финальный ответ" : "Отправляю запрос " + (round + 1));
        AiAgentStatus.set(forceFinal ? "Готовлю финальный ответ" : "Отправляю запрос " + (round + 1));
        return request(uriForProvider(provider), keyForProvider(provider), payload, providerName(provider))
                .thenCompose(response -> {
                    JsonObject choice = firstChoice(response);
                    if (choice == null || !choice.has("message")) {
                        return CompletableFuture.completedFuture(providerName(provider) + " не вернул ожидаемый ответ.");
                    }

                    JsonObject assistant = choice.getAsJsonObject("message");
                    JsonArray toolCalls = assistant.has("tool_calls") && assistant.get("tool_calls").isJsonArray()
                            ? assistant.getAsJsonArray("tool_calls")
                            : new JsonArray();

                    messages.add(assistant.deepCopy());

                    if (toolCalls.isEmpty()) {
                        AiAgentStatus.set("Формирую ответ");
                        String text = assistant.has("content") && !assistant.get("content").isJsonNull()
                                ? assistant.get("content").getAsString()
                                : "Ответ без текста.";
                        AiAgentStatus.clear();
                        AiAgentStatus.clear();
                        AiAgentStatus.clear();
                        AiAgentLog.info("TASK END provider=" + providerName(provider) + " rounds=" + round + " toolCalls=" + totalCalls);
                        System.out.println("[Minecraft AI Agent][" + providerName(provider) + "] TASK END rounds=" + round + " toolCalls=" + totalCalls);
                        return CompletableFuture.completedFuture(text);
                    }

                    int nextCalls = totalCalls + toolCalls.size();
                    int nextRound = round + 1;
                    boolean nextForceFinal = nextRound >= MAX_TOOL_ROUNDS || nextCalls >= MAX_TOOL_CALLS;
                    List<CompletableFuture<JsonObject>> results = new ArrayList<>();
                    List<JsonObject> validCalls = new ArrayList<>();

                    for (JsonElement element : toolCalls) {
                        if (!element.isJsonObject()) continue;
                        JsonObject call = element.getAsJsonObject();
                        validCalls.add(call);
                        AiAgentStatus.set(statusForTool(toolName(call)));
                        results.add(executeOpenAiToolAsync(call, executedCalls));
                    }

                    return sequence(results).thenCompose(toolResults -> {
                        for (int i = 0; i < validCalls.size(); i++) {
                            JsonObject call = validCalls.get(i);
                            String toolName = toolName(call);
                            JsonObject toolResult = toolResults.get(i);

                            JsonObject resultMessage = new JsonObject();
                            resultMessage.addProperty("role", "tool");
                            resultMessage.addProperty("tool_call_id", string(call, "id", ""));
                            resultMessage.addProperty("content", compactJson(toolResult, MAX_TOOL_RESULT_CHARS));
                            messages.add(resultMessage);

                            System.out.println("[Minecraft AI Agent][" + providerName(provider) + "] TOOL RESULT name="
                                    + toolName + " ok=" + toolResult.has("ok") + "");
                        }

                        if (round + 1 >= MAX_TOOL_ROUNDS || totalCalls + validCalls.size() >= MAX_TOOL_CALLS) {
                            return openAiLoop(messages, provider, executedCalls, nextRound, nextCalls, true);
                        }
                        return openAiLoop(messages, provider, executedCalls, nextRound, nextCalls, nextForceFinal);
                    });
                });
    }

    private static CompletableFuture<JsonObject> executeOpenAiToolAsync(
            JsonObject call, Map<String, JsonObject> executedCalls) {
        JsonObject function = call.has("function") && call.get("function").isJsonObject()
                ? call.getAsJsonObject("function")
                : call;
        String name = string(function, "name", "");
        JsonObject arguments;
        try {
            arguments = JsonParser.parseString(string(function, "arguments", "{}")).getAsJsonObject();
        } catch (RuntimeException exception) {
            return completedToolError("Invalid JSON arguments: " + exception.getMessage());
        }
        return executeToolWithCache(name, arguments, executedCalls);
    }

    private static CompletableFuture<String> chatDeepSeek(String message) {
        List<JsonObject> input = buildDeepSeekContext(message);
        Map<String, JsonObject> executedCalls = new HashMap<>();
        return deepSeekLoop(input, executedCalls, 0, 0, false)
                .whenComplete((reply, throwable) -> {
                    if (throwable != null) {
                        AiAgentStatus.set("Ошибка: не удалось обработать запрос");
                        AiAgentLog.error("TASK FAIL provider=DeepSeek message=" + String.valueOf(throwable.getMessage()));
                    }
                });
    }

    private static CompletableFuture<String> deepSeekLoop(
            List<JsonObject> input,
            Map<String, JsonObject> executedCalls,
            int round,
            int totalCalls,
            boolean forceFinal) {

        JsonObject payload = new JsonObject();
        payload.addProperty("model", DEEPSEEK_MODEL);
        payload.addProperty("instructions", INSTRUCTIONS + "\n\nPersistent memory:\n" + AiAgentMemory.forPrompt());
        JsonArray inputArray = new JsonArray();
        for (JsonObject item : input) inputArray.add(item.deepCopy());
        payload.add("input", inputArray);
        payload.add("tools", deepSeekToolsArray());
        payload.addProperty("tool_choice", forceFinal ? "none" : "auto");
        payload.addProperty("temperature", 0.2);
        payload.addProperty("max_output_tokens", 700);

        if (forceFinal) {
            JsonObject message = new JsonObject();
            message.addProperty("type", "message");
            message.addProperty("role", "user");
            message.addProperty("content", "The requested Minecraft action is complete. Give the player a concise final answer now. Do not call any tools.");
            inputArray.add(message);
            payload.add("input", inputArray);
        }

        AiAgentStatus.set(forceFinal ? "Готовлю финальный ответ" : "Отправляю запрос " + (round + 1));
        AiAgentStatus.set(forceFinal ? "Готовлю финальный ответ" : "Отправляю запрос " + (round + 1));
        AiAgentStatus.set(forceFinal ? "Готовлю финальный ответ" : "Отправляю запрос " + (round + 1));
        AiAgentStatus.set(forceFinal ? "Готовлю финальный ответ" : "Отправляю запрос " + (round + 1));
        AiAgentStatus.set(forceFinal ? "Готовлю финальный ответ" : "Отправляю запрос " + (round + 1));
        return request(DEEPSEEK_URI, AiClientConfig.getDeepSeekApiKey(), payload, "DeepSeek")
                .thenCompose(response -> {
                    JsonArray output = response.has("output") && response.get("output").isJsonArray()
                            ? response.getAsJsonArray("output")
                            : new JsonArray();

                    List<JsonObject> calls = new ArrayList<>();
                    for (JsonElement element : output) {
                        if (!element.isJsonObject()) continue;
                        JsonObject item = element.getAsJsonObject();
                        if ("function_call".equals(string(item, "type", ""))) calls.add(item);
                    }

                    for (JsonElement element : output) {
                        if (element.isJsonObject()) input.add(element.getAsJsonObject().deepCopy());
                    }

                    if (calls.isEmpty()) {
                        AiAgentStatus.set("Формирую ответ");
                        String text = response.has("output_text") && !response.get("output_text").isJsonNull()
                                ? response.get("output_text").getAsString()
                                : "DeepSeek не вернул текстовый ответ.";
                        AiAgentStatus.clear();
                        AiAgentStatus.clear();
                        AiAgentStatus.clear();
                        AiAgentLog.info("TASK END provider=DeepSeek rounds=" + round + " toolCalls=" + totalCalls);
                        System.out.println("[Minecraft AI Agent][DeepSeek] TASK END rounds=" + round + " toolCalls=" + totalCalls);
                        return CompletableFuture.completedFuture(text);
                    }

                    int nextCalls = totalCalls + calls.size();
                    int nextRound = round + 1;
                    List<CompletableFuture<JsonObject>> results = new ArrayList<>();
                    for (JsonObject call : calls) {
                        AiAgentStatus.set(statusForTool(string(call, "name", "unknown")));
                        results.add(executeToolWithCache(
                                string(call, "name", ""),
                                parseArguments(call),
                                executedCalls));
                    }

                    return sequence(results).thenCompose(toolResults -> {
                        for (int i = 0; i < calls.size(); i++) {
                            JsonObject call = calls.get(i);
                            JsonObject result = new JsonObject();
                            result.addProperty("type", "function_call_output");
                            result.addProperty("call_id", string(call, "call_id", string(call, "id", "")));
                            result.addProperty("output", compactJson(toolResults.get(i), MAX_TOOL_RESULT_CHARS));
                            input.add(result);
                        }

                        boolean mustFinalize = nextRound >= MAX_TOOL_ROUNDS || nextCalls >= MAX_TOOL_CALLS;
                        return deepSeekLoop(input, executedCalls, nextRound, nextCalls, mustFinalize);
                    });
                });
    }

    private static JsonObject parseArguments(JsonObject call) {
        try {
            JsonElement raw = call.get("arguments");
            if (raw != null && raw.isJsonObject()) return raw.getAsJsonObject();
            return JsonParser.parseString(string(call, "arguments", "{}")).getAsJsonObject();
        } catch (RuntimeException exception) {
            return new JsonObject();
        }
    }

    private static CompletableFuture<JsonObject> executeToolWithCache(
            String name, JsonObject arguments, Map<String, JsonObject> executedCalls) {
        String key = name + "|" + arguments.toString();
        JsonObject cached = executedCalls.get(key);
        if (cached != null) {
            System.out.println("[Minecraft AI Agent] TOOL CACHE name=" + name);
            return CompletableFuture.completedFuture(cached.deepCopy());
        }

        AiAgentStatus.set(statusForTool(name));
        AiAgentStatus.set(statusForTool(name));
        AiAgentStatus.set(statusForTool(name));
        AiAgentStatus.set(statusForTool(name));
        AiAgentStatus.set(statusForTool(name));
        AiAgentLog.info("TOOL CALL name=" + name + " args=" + compactJson(arguments, 700));
        System.out.println("[Minecraft AI Agent] TOOL CALL name=" + name + " args=" + compactJson(arguments, 700));

        MinecraftClient client = MinecraftClient.getInstance();
        MinecraftServer server = client.getServer();
        if (server == null) return completedToolError("Нет запущенного локального Minecraft-сервера.");

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                JsonObject result = executeTool(server, name, arguments);
                AiAgentStatus.set("Готово: " + statusForTool(name));
                AiAgentLog.info("TOOL RESULT name=" + name + " ok=" + result.has("ok") + " data=" + compactJson(result, 500));
                executedCalls.put(key, result.deepCopy());
                future.complete(result);
            } catch (Exception exception) {
                JsonObject error = new JsonObject();
                error.addProperty("ok", false);
                error.addProperty("error", exception.getMessage() == null ? exception.toString() : exception.getMessage());
                AiAgentLog.error("TOOL ERROR name=" + name + " message=" + error.get("error").getAsString());
                future.complete(error);
            }
        });
        return future;
    }

    private static CompletableFuture<JsonObject> completedToolError(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("ok", false);
        error.addProperty("error", message);
        return CompletableFuture.completedFuture(error);
    }

    private static JsonObject executeTool(MinecraftServer server, String name, JsonObject args) {
        return switch (name) {
            case "get_player_state" -> getPlayerState(server, args);
            case "get_block" -> getBlock(server, args);
            case "scan_area" -> scanArea(server, args);
            case "place_blocks" -> placeBlocks(server, args);
            case "fill_area" -> fillArea(server, args);
            case "break_block" -> breakBlock(server, args);
            case "give_item" -> giveItem(server, args);
            case "get_inventory" -> getInventory(server, args);
            case "remember_memory" -> rememberMemory(args);
            case "forget_memory" -> forgetMemory(args);
            case "set_time" -> setTime(server, args);
            case "set_weather" -> setWeather(server, args);
            case "teleport_player" -> teleportPlayer(server, args);
            case "set_gamemode" -> setGamemode(server, args);
            case "heal_player" -> healPlayer(server, args);
            case "feed_player" -> feedPlayer(server, args);
            case "clear_effects" -> clearEffects(server, args);
            case "spawn_entity" -> spawnEntity(server, args);
            case "list_players" -> listPlayers(server);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    private static JsonObject getInventory(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        JsonArray items = new JsonArray();
        int nonEmpty = 0;

        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            nonEmpty++;

            JsonObject item = new JsonObject();
            item.addProperty("slot", slot);
            item.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
            item.addProperty("count", stack.getCount());
            items.add(item);
        }

        JsonObject data = new JsonObject();
        data.addProperty("name", player.getName().getString());
        data.addProperty("occupied_slots", nonEmpty);
        data.add("items", items);
        return data;
    }

    private static JsonObject rememberMemory(JsonObject args) {
        String text = string(args, "text", "");
        boolean added = AiAgentMemory.remember(text);
        JsonObject data = new JsonObject();
        data.addProperty("saved", added);
        data.addProperty("memory_count", AiAgentMemory.count());
        data.addProperty("text", text);
        return data;
    }

    private static JsonObject forgetMemory(JsonObject args) {
        String text = string(args, "text", "");
        boolean removed = AiAgentMemory.forget(text);
        JsonObject data = new JsonObject();
        data.addProperty("removed", removed);
        data.addProperty("memory_count", AiAgentMemory.count());
        data.addProperty("text", text);
        return data;
    }

    private static JsonObject commandResult(MinecraftServer server, String command) {
        try {
            int result = server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);
            JsonObject data = new JsonObject();
            data.addProperty("executed", true);
            data.addProperty("result", result);
            return data;
        } catch (Exception exception) {
            JsonObject data = new JsonObject();
            data.addProperty("executed", false);
            data.addProperty("error", exception.getMessage() == null ? exception.toString() : exception.getMessage());
            return data;
        }
    }

    private static JsonObject setTime(MinecraftServer server, JsonObject args) {
        String time = string(args, "time", "day");
        if (!(time.equals("day") || time.equals("night") || time.equals("noon") || time.equals("midnight"))) {
            throw new IllegalArgumentException("time must be day, night, noon or midnight");
        }
        return commandResult(server, "time set " + time);
    }

    private static JsonObject setWeather(MinecraftServer server, JsonObject args) {
        String weather = string(args, "weather", "clear");
        if (!(weather.equals("clear") || weather.equals("rain") || weather.equals("thunder"))) {
            throw new IllegalArgumentException("weather must be clear, rain or thunder");
        }
        return commandResult(server, "weather " + weather + " 1000000");
    }

    private static JsonObject teleportPlayer(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        return commandResult(server, "tp " + player.getName().getString() + " " + x + " " + y + " " + z);
    }

    private static JsonObject setGamemode(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        String mode = string(args, "mode", "survival");
        if (!(mode.equals("survival") || mode.equals("creative") || mode.equals("adventure") || mode.equals("spectator"))) {
            throw new IllegalArgumentException("mode must be survival, creative, adventure or spectator");
        }
        return commandResult(server, "gamemode " + mode + " " + player.getName().getString());
    }

    private static JsonObject healPlayer(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        return commandResult(server, "effect give " + player.getName().getString() + " minecraft:instant_health 1 10 true");
    }

    private static JsonObject feedPlayer(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        return commandResult(server, "effect give " + player.getName().getString() + " minecraft:saturation 1 10 true");
    }

    private static JsonObject clearEffects(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        return commandResult(server, "effect clear " + player.getName().getString());
    }

    private static JsonObject spawnEntity(MinecraftServer server, JsonObject args) {
        String entity = string(args, "entity", "minecraft:pig");
        Identifier id = Identifier.tryParse(entity);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) {
            throw new IllegalArgumentException("Unknown entity: " + entity);
        }
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        return commandResult(server, "summon " + entity + " " + x + " " + y + " " + z);
    }

    private static JsonObject listPlayers(MinecraftServer server) {
        JsonArray players = new JsonArray();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            players.add(player.getName().getString());
        }
        JsonObject data = new JsonObject();
        data.add("players", players);
        data.addProperty("count", players.size());
        return data;
    }

    private static JsonObject getPlayerState(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        JsonObject data = new JsonObject();
        data.addProperty("name", player.getName().getString());
        data.addProperty("x", player.getX());
        data.addProperty("y", player.getY());
        data.addProperty("z", player.getZ());
        data.addProperty("block_x", player.getBlockPos().getX());
        data.addProperty("block_y", player.getBlockPos().getY());
        data.addProperty("block_z", player.getBlockPos().getZ());
        data.addProperty("yaw", player.getYaw());
        data.addProperty("pitch", player.getPitch());
        data.addProperty("health", player.getHealth());
        data.addProperty("food", player.getHungerManager().getFoodLevel());
        data.addProperty("dimension", player.getWorld().getRegistryKey().getValue().toString());
        return data;
    }

    private static JsonObject getBlock(MinecraftServer server, JsonObject args) {
        ServerWorld world = getWorld(server);
        BlockPos pos = new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
        var state = world.getBlockState(pos);
        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        data.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
        data.addProperty("air", state.isAir());
        data.addProperty("solid", !state.isAir() && state.isSolidBlock(world, pos));
        return data;
    }

    private static JsonObject scanArea(MinecraftServer server, JsonObject args) {
        ServerWorld world = getWorld(server);
        int cx = requiredInt(args, "x");
        int cy = requiredInt(args, "y");
        int cz = requiredInt(args, "z");
        int radius = Math.min(MAX_SCAN_RADIUS, Math.max(1, requiredInt(args, "radius")));

        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = cy - radius; y <= cy + radius; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    String id = Registries.BLOCK.getId(world.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
                    counts.merge(id, 1, Integer::sum);
                    total++;
                }
            }
        }

        List<Map.Entry<String, Integer>> top = counts.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(12)
                .toList();

        JsonObject resultCounts = new JsonObject();
        for (Map.Entry<String, Integer> entry : top) resultCounts.addProperty(entry.getKey(), entry.getValue());

        JsonObject data = new JsonObject();
        data.addProperty("total_blocks", total);
        data.addProperty("unique_block_types", counts.size());
        data.add("top_blocks", resultCounts);
        return data;
    }

    private static JsonObject placeBlocks(MinecraftServer server, JsonObject args) {
        ServerWorld world = getWorld(server);
        if (!args.has("blocks") || !args.get("blocks").isJsonArray()) {
            throw new IllegalArgumentException("blocks must be an array");
        }
        JsonArray blocks = args.getAsJsonArray("blocks");
        if (blocks.size() > MAX_PLACE_BLOCKS) {
            throw new IllegalArgumentException("place_blocks is limited to " + MAX_PLACE_BLOCKS + " blocks per call");
        }

        Set<String> seen = new HashSet<>();
        int changed = 0;
        for (JsonElement element : blocks) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Each block entry must be an object");
            JsonObject entry = element.getAsJsonObject();
            int x = requiredInt(entry, "x");
            int y = requiredInt(entry, "y");
            int z = requiredInt(entry, "z");
            String key = x + "," + y + "," + z;
            if (!seen.add(key)) continue;

            String blockId = string(entry, "block", "");
            Identifier id = Identifier.tryParse(blockId);
            if (id == null || !Registries.BLOCK.containsId(id)) {
                throw new IllegalArgumentException("Unknown block: " + blockId);
            }
            Block block = Registries.BLOCK.get(id);
            if (world.setBlockState(new BlockPos(x, y, z), block.getDefaultState())) changed++;
        }

        JsonObject data = new JsonObject();
        data.addProperty("requested", blocks.size());
        data.addProperty("changed", changed);
        return data;
    }

    private static JsonObject fillArea(MinecraftServer server, JsonObject args) {
        ServerWorld world = getWorld(server);
        int x1 = requiredInt(args, "x1");
        int y1 = requiredInt(args, "y1");
        int z1 = requiredInt(args, "z1");
        int x2 = requiredInt(args, "x2");
        int y2 = requiredInt(args, "y2");
        int z2 = requiredInt(args, "z2");

        int minX = Math.min(x1, x2);
        int minY = Math.min(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2);
        int maxY = Math.max(y1, y2);
        int maxZ = Math.max(z1, z2);

        long volume = (long) (maxX - minX + 1)
                * (maxY - minY + 1)
                * (maxZ - minZ + 1);
        if (volume > MAX_FILL_VOLUME) {
            throw new IllegalArgumentException("fill_area is limited to " + MAX_FILL_VOLUME + " blocks per call");
        }

        String mode = string(args, "mode", "fill");
        if (!(mode.equals("fill") || mode.equals("hollow") || mode.equals("replace_air"))) {
            throw new IllegalArgumentException("mode must be fill, hollow or replace_air");
        }

        String blockId = string(args, "block", "");
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            throw new IllegalArgumentException("Unknown block: " + blockId);
        }

        Block block = Registries.BLOCK.get(id);
        int changed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mode.equals("hollow") && !boundary) continue;
                    if (mode.equals("replace_air") && !world.getBlockState(pos).isAir()) continue;
                    if (world.setBlockState(pos, block.getDefaultState())) changed++;
                }
            }
        }

        JsonObject data = new JsonObject();
        data.addProperty("requested", volume);
        data.addProperty("changed", changed);
        data.addProperty("block", id.toString());
        data.addProperty("mode", mode);
        return data;
    }

    private static JsonObject breakBlock(MinecraftServer server, JsonObject args) {
        ServerWorld world = getWorld(server);
        BlockPos pos = new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
        String previous = Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString();
        boolean changed = world.breakBlock(pos, false);
        JsonObject data = new JsonObject();
        data.addProperty("changed", changed);
        data.addProperty("previous_block", previous);
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        return data;
    }

    private static JsonObject giveItem(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        String itemId = string(args, "item", "");
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) {
            throw new IllegalArgumentException("Unknown item: " + itemId);
        }

        int count = Math.min(64, Math.max(1, optionalInt(args, "count", 1)));
        Item item = Registries.ITEM.get(id);
        ItemStack stack = new ItemStack(item, count);
        int before = stack.getCount();
        boolean inserted = player.getInventory().insertStack(stack);
        if (!inserted && !stack.isEmpty()) player.dropItem(stack, false);
        player.currentScreenHandler.sendContentUpdates();

        JsonObject data = new JsonObject();
        data.addProperty("item", itemId);
        data.addProperty("requested", count);
        data.addProperty("given", before - stack.getCount());
        data.addProperty("dropped", !inserted);
        return data;
    }

    private static ServerPlayerEntity getPlayer(MinecraftServer server, String name) {
        if (name == null || name.isBlank()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayerList().stream().findFirst().orElse(null);
            if (player == null) throw new IllegalArgumentException("No online player");
            return player;
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
        if (player == null) throw new IllegalArgumentException("No matching player: " + name);
        return player;
    }

    private static ServerWorld getWorld(MinecraftServer server) {
        return server.getPlayerManager().getPlayerList().stream()
                .findFirst()
                .map(ServerPlayerEntity::getServerWorld)
                .orElse(server.getOverworld());
    }

    private static List<JsonObject> buildOpenAiContext(String currentMessage) {
        List<JsonObject> result = new ArrayList<>();
        result.add(chatMessage("system", INSTRUCTIONS + "\n\nPersistent memory:\n" + AiAgentMemory.forPrompt()));
        for (AiChatHistory.Entry entry : AiChatHistory.getRecentForApi(API_HISTORY_MESSAGES, API_HISTORY_CHARS)) {
            result.add(chatMessage(entry.role(), entry.text()));
        }
        if (result.stream().noneMatch(message -> "user".equals(string(message, "role", ""))
                && currentMessage.equals(string(message, "content", "")))) {
            result.add(chatMessage("user", currentMessage));
        }
        return result;
    }

    private static List<JsonObject> buildDeepSeekContext(String currentMessage) {
        List<JsonObject> result = new ArrayList<>();
        for (AiChatHistory.Entry entry : AiChatHistory.getRecentForApi(API_HISTORY_MESSAGES, API_HISTORY_CHARS)) {
            result.add(deepSeekMessage(entry.role(), entry.text()));
        }
        if (result.stream().noneMatch(message -> "user".equals(string(message, "role", ""))
                && currentMessage.equals(string(message, "content", "")))) {
            result.add(deepSeekMessage("user", currentMessage));
        }
        return result;
    }

    private static URI uriForProvider(String provider) {
        return provider.equals("openrouter") ? OPENROUTER_URI : GROQ_URI;
    }

    private static String keyForProvider(String provider) {
        return provider.equals("openrouter")
                ? AiClientConfig.getOpenRouterApiKey()
                : AiClientConfig.getGroqApiKey();
    }

    private static String modelForProvider(String provider) {
        return provider.equals("openrouter") ? OPENROUTER_MODEL : GROQ_MODEL;
    }

    private static CompletableFuture<JsonObject> request(
            URI uri, String apiKey, JsonObject payload, String providerName) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        int bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8).length;
        System.out.println("[Minecraft AI Agent][" + providerName + "] HTTP REQUEST url=" + uri + " bodyBytes=" + bodyBytes);
        logNetworkDiagnostics(uri, providerName);

        return sendWithRetry(request, providerName, 0)
                .thenCompose(response -> {
                    String body = response.body();
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            return CompletableFuture.completedFuture(JsonParser.parseString(body).getAsJsonObject());
                        } catch (RuntimeException exception) {
                            return CompletableFuture.failedFuture(new IOException(
                                    "Неверный JSON-ответ " + providerName + ": " + exception.getMessage(), exception));
                        }
                    }

                    String errorText;
                    try {
                        errorText = extractError(JsonParser.parseString(body).getAsJsonObject());
                    } catch (RuntimeException exception) {
                        errorText = truncate(body, 900);
                    }

                    AiAgentLog.error("API ERROR provider=" + providerName + " status=" + response.statusCode() + " message=" + truncate(errorText, 500));
                    return CompletableFuture.failedFuture(new IOException(
                            providerName + " API " + response.statusCode() + ": " + errorText));
                });
    }

    private static CompletableFuture<HttpResponse<String>> sendWithRetry(
            HttpRequest request, String providerName, int attempt) {
        long started = System.nanoTime();
        System.out.println("[Minecraft AI Agent][" + providerName + "] HTTP START attempt="
                + (attempt + 1) + "/" + (NETWORK_RETRIES + 1));

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    if (throwable == null) {
                        System.out.println("[Minecraft AI Agent][" + providerName + "] HTTP RESPONSE status="
                                + response.statusCode() + " in=" + elapsed + " ms");
                        if (response.statusCode() == 429) {
                            String retryAfter = response.headers().firstValue("retry-after").orElse("unknown");
                            System.err.println("[Minecraft AI Agent][" + providerName + "] RATE LIMIT retry-after=" + retryAfter);
                        }
                        return CompletableFuture.completedFuture(response);
                    }

                    Throwable cause = rootCause(throwable);
                    AiAgentLog.error("HTTP FAIL provider=" + providerName + " type=" + cause.getClass().getSimpleName() + " message=" + String.valueOf(cause.getMessage()));
                    System.err.println("[Minecraft AI Agent][" + providerName + "] HTTP FAIL in="
                            + elapsed + " ms type=" + cause.getClass().getName()
                            + " message=" + String.valueOf(cause.getMessage()));

                    boolean retryable = cause instanceof ConnectException || cause instanceof HttpTimeoutException;
                    if (!retryable || attempt >= NETWORK_RETRIES) {
                        return CompletableFuture.<HttpResponse<String>>failedFuture(
                                new IOException(providerName + " connection failed: " + cause.getMessage(), cause));
                    }

                    long delay = 2L * (attempt + 1);
                    System.out.println("[Minecraft AI Agent][" + providerName + "] RETRY after " + delay + " s");
                    return CompletableFuture.supplyAsync(
                            () -> null,
                            CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS))
                            .thenCompose(ignored -> sendWithRetry(request, providerName, attempt + 1));
                })
                .thenCompose(future -> future);
    }

    private static void logNetworkDiagnostics(URI uri, String providerName) {
        CompletableFuture.runAsync(() -> {
            String host = uri.getHost();
            try {
                long start = System.nanoTime();
                InetAddress[] addresses = InetAddress.getAllByName(host);
                long dnsMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

                StringBuilder resolved = new StringBuilder();
                for (int i = 0; i < addresses.length; i++) {
                    if (i > 0) resolved.append(", ");
                    resolved.append(addresses[i].getHostAddress());
                }
                System.out.println("[Minecraft AI Agent][" + providerName + "] DNS OK " + dnsMs + " ms -> " + resolved);

                for (InetAddress address : addresses) {
                    long tcpStart = System.nanoTime();
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(address, 443), 3000);
                        long tcpMs = Duration.ofNanos(System.nanoTime() - tcpStart).toMillis();
                        System.out.println("[Minecraft AI Agent][" + providerName + "] TCP OK "
                                + address.getHostAddress() + " " + tcpMs + " ms");
                    } catch (Exception exception) {
                        System.err.println("[Minecraft AI Agent][" + providerName + "] TCP FAIL "
                                + address.getHostAddress() + ": " + exception.getClass().getSimpleName()
                                + " | " + exception.getMessage());
                    }
                }
            } catch (Exception exception) {
                System.err.println("[Minecraft AI Agent][" + providerName + "] DNS FAIL: "
                        + exception.getClass().getSimpleName() + " | " + exception.getMessage());
            }
        });
    }

    private static JsonArray deepSeekToolsArray() {
        JsonArray array = new JsonArray();
        for (JsonObject tool : TOOLS) array.add(tool.deepCopy());
        return array;
    }

    private static JsonArray openAiToolsArray() {
        JsonArray array = new JsonArray();
        for (JsonObject tool : TOOLS) {
            JsonObject function = tool.deepCopy();
            function.remove("type");
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            wrapper.add("function", function);
            array.add(wrapper);
        }
        return array;
    }

    private static final List<JsonObject> TOOLS = createTools();

    private static List<JsonObject> createTools() {
        List<JsonObject> tools = new ArrayList<>();
        tools.add(function("get_player_state", "Get the current player's exact position, rotation, health, hunger and dimension.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player.", false)
        )));
        tools.add(function("get_block", "Inspect one exact block in the player's current dimension.", objectProperties(
                intProperty("x"), intProperty("y"), intProperty("z")
        )));
        tools.add(function("scan_area", "Count block types in a small cube. Use this only when the nearby terrain matters.", objectProperties(
                intProperty("x"), intProperty("y"), intProperty("z"), intProperty("radius")
        )));
        tools.add(function("place_blocks", "Place up to 128 exact blocks. Use for doors, windows, stairs and small details, not large surfaces.", objectProperties(
                arrayProperty("blocks", "Each entry contains x, y, z and a Minecraft block ID such as minecraft:oak_planks.")
        )));
        tools.add(function("fill_area", "Fill a rectangular cuboid efficiently. mode=fill fills everything, hollow builds only the outer shell, replace_air only changes air blocks. Maximum 4096 blocks.", objectProperties(
                intProperty("x1"), intProperty("y1"), intProperty("z1"),
                intProperty("x2"), intProperty("y2"), intProperty("z2"),
                property("block", "string", "Minecraft block ID such as minecraft:oak_planks.", true),
                enumProperty("mode", new String[]{"fill", "hollow", "replace_air"}, "How the cuboid should be filled.", false)
        )));
        tools.add(function("break_block", "Break one exact block. Use only when the player explicitly asks to remove it.", objectProperties(
                intProperty("x"), intProperty("y"), intProperty("z")
        )));
        tools.add(function("get_inventory", "Inspect the current player's inventory so the agent can see which materials/items are available. This is read-only.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player.", false)
        )));
        tools.add(function("remember_memory", "Save one durable user-requested fact or preference to persistent local memory. Use only when the player explicitly asks to remember it.", objectProperties(
                property("text", "string", "One concise fact or preference to remember.", true)
        )));
        tools.add(function("forget_memory", "Remove one exact stored memory entry. Use only when the player explicitly asks to forget it.", objectProperties(
                property("text", "string", "The exact stored memory text to remove.", true)
        )));
        tools.add(function("set_time", "Set the Minecraft world time. Use only when requested.", objectProperties(
                enumProperty("time", new String[]{"day", "night", "noon", "midnight"}, "Desired world time.", true)
        )));
        tools.add(function("set_weather", "Set the Minecraft weather. Use only when requested.", objectProperties(
                enumProperty("weather", new String[]{"clear", "rain", "thunder"}, "Desired weather.", true)
        )));
        tools.add(function("teleport_player", "Teleport a player to exact integer coordinates.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player.", false),
                intProperty("x"), intProperty("y"), intProperty("z")
        )));
        tools.add(function("set_gamemode", "Change a player's gamemode.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player.", false),
                enumProperty("mode", new String[]{"survival", "creative", "adventure", "spectator"}, "Desired gamemode.", true)
        )));
        tools.add(function("heal_player", "Restore a player's health using a controlled vanilla effect command.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player.", false)
        )));
        tools.add(function("feed_player", "Restore a player's hunger using a controlled vanilla effect command.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player.", false)
        )));
        tools.add(function("clear_effects", "Remove all active potion effects from a player.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player.", false)
        )));
        tools.add(function("spawn_entity", "Spawn one vanilla or modded entity at exact coordinates.", objectProperties(
                property("entity", "string", "Entity ID such as minecraft:pig or another registered entity.", true),
                intProperty("x"), intProperty("y"), intProperty("z")
        )));
        tools.add(function("list_players", "List currently online Minecraft players. Read-only.", objectProperties()));
        tools.add(function("give_item", "Give an item directly to a player. Count is clamped to 1..64.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player.", false),
                property("item", "string", "Minecraft item ID such as minecraft:diamond_pickaxe.", true),
                property("count", "integer", "Amount from 1 to 64.", false)
        )));
        return tools;
    }

    private static JsonObject function(String name, String description, JsonObject parameters) {
        JsonObject function = new JsonObject();
        function.addProperty("type", "function");
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);
        return function;
    }

    private static JsonObject objectProperties(JsonObject... properties) {
        JsonObject object = new JsonObject();
        object.addProperty("type", "object");
        JsonObject map = new JsonObject();
        JsonArray required = new JsonArray();
        for (JsonObject property : properties) {
            String name = property.get("_name").getAsString();
            property.remove("_name");
            boolean isRequired = property.has("_required") && property.get("_required").getAsBoolean();
            property.remove("_required");
            map.add(name, property);
            if (isRequired) required.add(name);
        }
        object.add("properties", map);
        object.add("required", required);
        object.addProperty("additionalProperties", false);
        return object;
    }

    private static JsonObject property(String name, String type, String description, boolean required) {
        JsonObject property = new JsonObject();
        property.addProperty("_name", name);
        property.addProperty("type", type);
        property.addProperty("description", description);
        property.addProperty("_required", required);
        return property;
    }

    private static JsonObject enumProperty(String name, String[] values, String description, boolean required) {
        JsonObject property = new JsonObject();
        property.addProperty("_name", name);
        property.addProperty("type", "string");
        property.addProperty("description", description);
        JsonArray enumValues = new JsonArray();
        for (String value : values) enumValues.add(value);
        property.add("enum", enumValues);
        property.addProperty("_required", required);
        return property;
    }

    private static JsonObject intProperty(String name) {
        return property(name, "integer", "Integer value for " + name + ".", true);
    }

    private static JsonObject arrayProperty(String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("_name", name);
        property.addProperty("type", "array");
        property.addProperty("description", description);
        property.addProperty("_required", true);

        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        JsonObject itemProperties = new JsonObject();
        itemProperties.add("x", simpleType("integer"));
        itemProperties.add("y", simpleType("integer"));
        itemProperties.add("z", simpleType("integer"));
        JsonObject block = simpleType("string");
        block.addProperty("description", "Minecraft block ID.");
        itemProperties.add("block", block);
        items.add("properties", itemProperties);
        JsonArray required = new JsonArray();
        required.add("x");
        required.add("y");
        required.add("z");
        required.add("block");
        items.add("required", required);
        items.addProperty("additionalProperties", false);
        property.add("items", items);
        return property;
    }

    private static JsonObject simpleType(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        return object;
    }

    private static JsonObject chatMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject deepSeekMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "message");
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject firstChoice(JsonObject root) {
        if (!root.has("choices") || !root.get("choices").isJsonArray()) return null;
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) return null;
        return choices.get(0).getAsJsonObject();
    }

    private static String providerName(String provider) {
        return switch (provider) {
            case "groq" -> "Groq";
            case "openrouter" -> "OpenRouter";
            default -> "DeepSeek";
        };
    }

    private static String string(JsonObject object, String name, String fallback) {
        return object.has(name) && !object.get(name).isJsonNull()
                ? object.get(name).getAsString()
                : fallback;
    }

    private static int requiredInt(JsonObject object, String name) {
        if (!object.has(name)) throw new IllegalArgumentException("Missing required argument: " + name);
        return object.get(name).getAsInt();
    }

    private static int optionalInt(JsonObject object, String name, int fallback) {
        return object.has(name) ? object.get(name).getAsInt() : fallback;
    }

    private static String statusForTool(String name) {
        return switch (name) {
            case "get_player_state" -> "Получаю позицию игрока";
            case "get_block" -> "Проверяю блок";
            case "scan_area" -> "Сканирую область";
            case "place_blocks" -> "Строю блоки";
            case "fill_area" -> "Заполняю область";
            case "break_block" -> "Ломаю блок";
            case "give_item" -> "Выдаю предмет";
            default -> "Выполняю действие";
        };
    }

    private static String toolName(JsonObject call) {
        JsonObject function = call.has("function") && call.get("function").isJsonObject()
                ? call.getAsJsonObject("function") : call;
        return string(function, "name", "unknown");
    }

    private static String extractError(JsonObject body) {
        if (body.has("error")) {
            JsonElement error = body.get("error");
            if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                return error.getAsJsonObject().get("message").getAsString();
            }
            return error.toString();
        }
        return body.toString();
    }

    private static String compactJson(JsonElement element, int maxChars) {
        return truncate(element.toString(), maxChars);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "null";
        if (text.length() <= maxChars) return text;
        return text.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth++ < 16) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        return all.thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }
}
