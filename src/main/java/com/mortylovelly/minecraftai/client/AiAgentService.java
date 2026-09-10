package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class AiAgentService {
    private static final URI DEEPSEEK_URI = URI.create("https://api.deepseek.com/responses");
    private static final URI GROQ_URI = URI.create("https://api.groq.com/openai/v1/chat/completions");
    private static final URI OPENROUTER_URI = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private static final URI GEMINI_URI = URI.create("https://generativelanguage.googleapis.com/v1beta/interactions");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(ProxySelector.getDefault())
            .build();

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int MAX_TOOL_CALLS = 16;
    private static final int MAX_TOOL_RESULT_CHARS = 7000;
    private static final int MAX_PLACE_BLOCKS = 512;
    private static final int MAX_BLUEPRINT_OPS = 96;
    private static final int MAX_BUILD_VOLUME = 20000;
    private static final int MAX_CLEAR_VOLUME = 32768;
    private static final int MAX_OBSERVE_RADIUS = 10;
    private static final int MAX_ENTITY_RESULTS = 60;

    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String GROQ_MODEL = "openai/gpt-oss-20b";
    private static final String OPENROUTER_MODEL = "google/gemma-4-26b-a4b-it:free";
    private static final String GEMINI_MODEL = "gemini-3.7-flash";

    private static final Set<String> FAST_ACTION_TOOLS = Set.of(
            "give_item", "apply_effect", "set_player_stats", "clear_inventory",
            "teleport_player", "set_gamemode", "set_time", "set_weather",
            "spawn_entity", "clear_area", "break_block", "run_minecraft_command",
            "send_chat", "remember_memory", "forget_memory"
    );

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "advancement", "attribute", "clear", "clone", "damage", "data", "difficulty",
            "effect", "enchant", "execute", "experience", "fill", "function", "gamemode",
            "give", "item", "kill", "locate", "loot", "particle", "place", "playsound",
            "recipe", "ride", "say", "schedule", "setblock", "spawnpoint", "spreadplayers",
            "summon", "tag", "team", "teleport", "tellraw", "time", "title", "trigger",
            "weather", "xp", "spectate"
    );

    private static final String INSTRUCTIONS = """
            You are the Minecraft AI Agent for a Minecraft 1.21.1 singleplayer world.
            You have direct access to the current world through Minecraft tools. Treat tool results as ground truth.
            Understand natural Russian language and colloquial requests: "дай спектатора" means spectator mode, "убери блоки вокруг" means clear an area, "выдай меч с остротой" means give an enchanted item.
            Never claim a world action happened without a successful tool result.
            For a capability question such as "можешь ли ты построить дом?", answer directly without tools.
            For a real requested action, act instead of explaining how the player could do it manually.
            Before spatially sensitive work, observe the world. Use observe_world for position, orientation, nearby blocks and entities; inspect_region for exact local block layout; locate_structure for villages and other structures.
            Reason in Minecraft coordinates carefully. Do not invent locations, blocks, mobs or structures.
            For building, do not make a plain solid box. Prefer build_house for house requests and build_blueprint for custom structures. A good house should have a foundation/floor, walls with openings, windows, an entrance, a roof with shape, and a few details. Keep it compact unless the user asks for a large build.
            For destruction, affect only the requested area and verify the result. Do not destroy unrelated terrain unless explicitly requested.
            Prefer one high-level world tool over dozens of tiny calls. You can call multiple independent tools in parallel when appropriate.
            Remember useful durable facts yourself: player preferences, named locations, important coordinates, preferred building materials or styles and other information likely to help later. Use remember_memory when such a durable fact is learned; do not save trivial one-off chatter.
            Each chat has its own persistent memory. Do not assume memories from another chat.
            If a tool fails, explain the failure instead of pretending it succeeded.
            Keep the number of model rounds small and finish after the requested world action is confirmed.
            """.strip();

    private static final AtomicReference<ActiveTask> ACTIVE = new AtomicReference<>();

    private AiAgentService() {}

    private static final class ActiveTask {
        final String chatId;
        final CompletableFuture<String> result = new CompletableFuture<>();
        volatile boolean cancelled;

        ActiveTask(String chatId) {
            this.chatId = chatId;
        }
    }

    public static CompletableFuture<String> chat(String message) {
        return chat(AiChatHistory.getCurrentChatId(), message);
    }

    public static CompletableFuture<String> chat(String chatId, String message) {
        AiClientConfig.load();
        if (!AiClientConfig.hasApiKey()) {
            return CompletableFuture.completedFuture("Сначала укажи API key для " + providerName(AiClientConfig.getProvider()) + ".");
        }
        ActiveTask task = new ActiveTask(chatId);
        if (!ACTIVE.compareAndSet(null, task)) {
            return CompletableFuture.completedFuture("Другой запрос уже выполняется. Дождись его завершения или нажми «Стоп».");
        }

        AiChatHistory.add(chatId, "user", message);
        AiAgentStatus.set("Анализирую запрос");
        System.out.println("[Minecraft AI Agent][" + providerName(AiClientConfig.getProvider()) + "] TASK START chars=" + message.length() + " chat=" + chatId);

        CompletableFuture<String> work;
        try {
            work = switch (AiClientConfig.getProvider()) {
                case "gemini" -> chatGemini(task, message);
                case "deepseek" -> chatDeepSeek(task, message);
                default -> chatOpenAiCompatible(task, message, AiClientConfig.getProvider());
            };
        } catch (Exception exception) {
            work = CompletableFuture.failedFuture(exception);
        }

        work.whenComplete((reply, throwable) -> {
            if (task.cancelled) return;
            String finalReply;
            if (throwable != null) {
                Throwable cause = rootCause(throwable);
                finalReply = "Ошибка: " + (cause.getMessage() == null ? cause.toString() : cause.getMessage());
                AiAgentStatus.set("Ошибка");
            } else {
                finalReply = reply == null || reply.isBlank() ? "Я не получил текстового ответа." : reply;
                AiAgentStatus.clear();
            }
            AiChatHistory.add(chatId, "assistant", finalReply);
            System.out.println("[Minecraft AI Agent][" + providerName(AiClientConfig.getProvider()) + "] TASK END chat=" + chatId);
            ACTIVE.compareAndSet(task, null);
            if (throwable == null) task.result.complete(finalReply);
            else task.result.complete(finalReply);
        });
        return task.result;
    }

    public static boolean isBusy() {
        ActiveTask task = ACTIVE.get();
        return task != null && !task.cancelled;
    }

    public static String getActiveChatId() {
        ActiveTask task = ACTIVE.get();
        return task == null ? "" : task.chatId;
    }

    public static void cancelCurrentTask() {
        ActiveTask task = ACTIVE.getAndSet(null);
        if (task == null) return;
        task.cancelled = true;
        AiAgentStatus.set("Запрос остановлен");
        AiChatHistory.add(task.chatId, "assistant", "Запрос остановлен пользователем.");
        task.result.complete("Запрос остановлен пользователем.");
        System.out.println("[Minecraft AI Agent] TASK CANCEL chat=" + task.chatId);
    }

    public static CompletableFuture<Boolean> testConnection() {
        AiClientConfig.load();
        if (!AiClientConfig.hasApiKey()) return CompletableFuture.completedFuture(false);
        String provider = AiClientConfig.getProvider();
        if (provider.equals("gemini")) {
            return geminiRequest(null, "Проверка подключения. Ответь ровно OK.", true)
                    .thenApply(root -> !geminiOutputText(root).isBlank());
        }
        if (provider.equals("deepseek")) {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", DEEPSEEK_MODEL);
            payload.addProperty("instructions", "Reply with exactly: OK");
            payload.addProperty("input", "Connection test");
            payload.addProperty("max_output_tokens", 32);
            return requestJson(DEEPSEEK_URI, AiClientConfig.getDeepSeekApiKey(), payload, "DeepSeek")
                    .thenApply(root -> !string(root, "output_text", "").isBlank());
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelForProvider(provider));
        JsonArray messages = new JsonArray();
        messages.add(chatMessage("system", "Reply with exactly: OK"));
        messages.add(chatMessage("user", "Connection test"));
        payload.add("messages", messages);
        payload.addProperty("temperature", 0);
        payload.addProperty("max_tokens", 32);
        return requestJson(uriForProvider(provider), keyForProvider(provider), payload, providerName(provider))
                .thenApply(root -> {
                    JsonObject choice = firstChoice(root);
                    return choice != null && choice.has("message") && !string(choice.getAsJsonObject("message"), "content", "").isBlank();
                });
    }

    private static CompletableFuture<String> chatGemini(ActiveTask task, String message) {
        boolean capability = isCapabilityQuestion(message);
        String previous = AiChatHistory.getGeminiInteractionId(task.chatId);
        return geminiLoop(task, message, previous, 0, 0, capability, false);
    }

    private static CompletableFuture<String> geminiLoop(
            ActiveTask task,
            String message,
            String previousInteractionId,
            int round,
            int totalCalls,
            boolean capability,
            boolean recovery) {
        if (task.cancelled) return CompletableFuture.completedFuture("Запрос остановлен пользователем.");
        AiAgentStatus.set(capability ? "Готовлю ответ" : round == 0 ? "Отправляю запрос Gemini" : "Продолжаю выполнение");

        String input = message;
        if (recovery) input = buildRecoveryInput(task.chatId, message);
        JsonElement inputElement = new com.google.gson.JsonPrimitive(input);
        JsonObject payload = new JsonObject();
        payload.addProperty("model", GEMINI_MODEL);
        payload.addProperty("store", true);
        payload.addProperty("system_instruction", INSTRUCTIONS + "\n\nPersistent memory for this chat:\n" + AiAgentMemory.forPrompt(task.chatId));
        payload.add("input", inputElement);
        if (!capability) {
            payload.add("tools", geminiToolsArray());
            JsonObject generation = new JsonObject();
            generation.addProperty("thinking_level", "low");
            generation.addProperty("temperature", 0.15);
            generation.addProperty("max_output_tokens", 4096);
            payload.add("generation_config", generation);
        } else {
            JsonObject generation = new JsonObject();
            generation.addProperty("thinking_level", "low");
            generation.addProperty("temperature", 0.2);
            generation.addProperty("max_output_tokens", 1200);
            payload.add("generation_config", generation);
        }
        if (!previousInteractionId.isBlank()) payload.addProperty("previous_interaction_id", previousInteractionId);

        return requestJson(GEMINI_URI, AiClientConfig.getGeminiApiKey(), payload, "Gemini")
                .handle((response, error) -> new GeminiResult(response, error))
                .thenCompose(result -> {
                    if (result.error != null) {
                        Throwable cause = rootCause(result.error);
                        if (!previousInteractionId.isBlank() && !recovery && isBadGeminiInteraction(cause)) {
                            return geminiLoop(task, message, "", round, totalCalls, capability, true);
                        }
                        return CompletableFuture.failedFuture(cause);
                    }
                    JsonObject response = result.response;
                    String interactionId = string(response, "id", "");
                    if (!interactionId.isBlank()) AiChatHistory.setGeminiInteractionId(task.chatId, interactionId);

                    List<JsonObject> calls = new ArrayList<>();
                    if (response.has("steps") && response.get("steps").isJsonArray()) {
                        for (JsonElement element : response.getAsJsonArray("steps")) {
                            if (!element.isJsonObject()) continue;
                            JsonObject step = element.getAsJsonObject();
                            if ("function_call".equals(string(step, "type", ""))) calls.add(step);
                        }
                    }
                    String outputText = geminiOutputText(response);
                    if (calls.isEmpty()) {
                        AiAgentStatus.clear();
                        return CompletableFuture.completedFuture(outputText.isBlank() ? "Gemini не вернул текстовый ответ." : outputText);
                    }
                    if (round >= MAX_TOOL_ROUNDS || totalCalls + calls.size() > MAX_TOOL_CALLS) {
                        return CompletableFuture.completedFuture("Я остановил выполнение: достигнут безопасный предел действий за один запрос.");
                    }

                    List<CompletableFuture<JsonObject>> futures = new ArrayList<>();
                    for (JsonObject call : calls) {
                        if (task.cancelled) return CompletableFuture.completedFuture("Запрос остановлен пользователем.");
                        String name = string(call, "name", "unknown");
                        AiAgentStatus.set(statusForTool(name));
                        futures.add(executeToolWithCache(task, name, callArguments(call), new HashMap<>()));
                    }
                    return sequence(futures).thenCompose(results -> {
                        if (task.cancelled) return CompletableFuture.completedFuture("Запрос остановлен пользователем.");
                        JsonArray inputResults = new JsonArray();
                        for (int i = 0; i < calls.size(); i++) {
                            JsonObject call = calls.get(i);
                            JsonObject resultObject = new JsonObject();
                            resultObject.addProperty("type", "function_result");
                            resultObject.addProperty("name", string(call, "name", "unknown"));
                            resultObject.addProperty("call_id", string(call, "id", ""));
                            JsonArray resultText = new JsonArray();
                            JsonObject text = new JsonObject();
                            text.addProperty("type", "text");
                            text.addProperty("text", compactJson(results.get(i), MAX_TOOL_RESULT_CHARS));
                            resultText.add(text);
                            resultObject.add("result", resultText);
                            inputResults.add(resultObject);
                        }
                        if (allFastCalls(calls) && results.stream().allMatch(AiAgentService::toolResultOk)) {
                            return CompletableFuture.completedFuture(localActionSummary(calls));
                        }
                        return geminiFollowUp(task, interactionId, inputResults, round + 1, totalCalls + calls.size());
                    });
                });
    }

    private static CompletableFuture<String> geminiFollowUp(ActiveTask task, String interactionId, JsonArray results, int round, int totalCalls) {
        if (task.cancelled) return CompletableFuture.completedFuture("Запрос остановлен пользователем.");
        JsonObject payload = new JsonObject();
        payload.addProperty("model", GEMINI_MODEL);
        payload.addProperty("store", true);
        payload.addProperty("previous_interaction_id", interactionId);
        payload.add("input", results);
        payload.addProperty("system_instruction", INSTRUCTIONS + "\n\nPersistent memory for this chat:\n" + AiAgentMemory.forPrompt(task.chatId));
        payload.add("tools", geminiToolsArray());
        JsonObject generation = new JsonObject();
        generation.addProperty("thinking_level", "low");
        generation.addProperty("temperature", 0.15);
        generation.addProperty("max_output_tokens", 4096);
        payload.add("generation_config", generation);

        return requestJson(GEMINI_URI, AiClientConfig.getGeminiApiKey(), payload, "Gemini")
                .thenCompose(response -> {
                    String newId = string(response, "id", interactionId);
                    if (!newId.isBlank()) AiChatHistory.setGeminiInteractionId(task.chatId, newId);
                    List<JsonObject> calls = new ArrayList<>();
                    if (response.has("steps") && response.get("steps").isJsonArray()) {
                        for (JsonElement element : response.getAsJsonArray("steps")) {
                            if (!element.isJsonObject()) continue;
                            JsonObject step = element.getAsJsonObject();
                            if ("function_call".equals(string(step, "type", ""))) calls.add(step);
                        }
                    }
                    if (calls.isEmpty()) {
                        AiAgentStatus.clear();
                        String text = geminiOutputText(response);
                        return CompletableFuture.completedFuture(text.isBlank() ? "Готово." : text);
                    }
                    if (round >= MAX_TOOL_ROUNDS || totalCalls + calls.size() > MAX_TOOL_CALLS) {
                        return CompletableFuture.completedFuture("Я остановил выполнение: достигнут безопасный предел действий за один запрос.");
                    }
                    List<CompletableFuture<JsonObject>> futures = new ArrayList<>();
                    Map<String, JsonObject> cache = new HashMap<>();
                    for (JsonObject call : calls) futures.add(executeToolWithCache(task, string(call, "name", "unknown"), callArguments(call), cache));
                    return sequence(futures).thenCompose(toolResults -> {
                        if (allFastCalls(calls) && toolResults.stream().allMatch(AiAgentService::toolResultOk)) {
                            return CompletableFuture.completedFuture(localActionSummary(calls));
                        }
                        JsonArray nextResults = new JsonArray();
                        for (int i = 0; i < calls.size(); i++) {
                            JsonObject resultObject = new JsonObject();
                            resultObject.addProperty("type", "function_result");
                            resultObject.addProperty("name", string(calls.get(i), "name", "unknown"));
                            resultObject.addProperty("call_id", string(calls.get(i), "id", ""));
                            JsonArray resultText = new JsonArray();
                            JsonObject text = new JsonObject();
                            text.addProperty("type", "text");
                            text.addProperty("text", compactJson(toolResults.get(i), MAX_TOOL_RESULT_CHARS));
                            resultText.add(text);
                            resultObject.add("result", resultText);
                            nextResults.add(resultObject);
                        }
                        return geminiFollowUp(task, newId, nextResults, round + 1, totalCalls + calls.size());
                    });
                });
    }

    private static CompletableFuture<String> chatOpenAiCompatible(ActiveTask task, String message, String provider) {
        List<JsonObject> messages = buildOpenAiContext(task.chatId, message, provider);
        return openAiLoop(task, messages, provider, new HashMap<>(), 0, 0, false, isCapabilityQuestion(message));
    }

    private static CompletableFuture<String> openAiLoop(
            ActiveTask task,
            List<JsonObject> messages,
            String provider,
            Map<String, JsonObject> cache,
            int round,
            int totalCalls,
            boolean ignored,
            boolean capability) {
        if (task.cancelled) return CompletableFuture.completedFuture("Запрос остановлен пользователем.");
        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelForProvider(provider));
        JsonArray array = new JsonArray();
        for (JsonObject message : messages) array.add(message.deepCopy());
        payload.add("messages", array);
        if (!capability) {
            payload.add("tools", openAiToolsArray());
            payload.addProperty("tool_choice", "auto");
        } else {
            payload.addProperty("tool_choice", "none");
        }
        payload.addProperty("temperature", 0.15);
        payload.addProperty("max_tokens", 2200);
        AiAgentStatus.set(round == 0 ? "Отправляю запрос " + providerName(provider) : "Продолжаю выполнение");

        return requestJson(uriForProvider(provider), keyForProvider(provider), payload, providerName(provider))
                .thenCompose(response -> {
                    JsonObject choice = firstChoice(response);
                    if (choice == null || !choice.has("message")) return CompletableFuture.completedFuture("Провайдер не вернул ожидаемый ответ.");
                    JsonObject assistant = choice.getAsJsonObject("message");
                    JsonArray calls = assistant.has("tool_calls") && assistant.get("tool_calls").isJsonArray()
                            ? assistant.getAsJsonArray("tool_calls") : new JsonArray();
                    messages.add(assistant.deepCopy());
                    if (calls.isEmpty()) {
                        AiAgentStatus.clear();
                        return CompletableFuture.completedFuture(string(assistant, "content", "Ответ без текста."));
                    }
                    if (round >= MAX_TOOL_ROUNDS || totalCalls + calls.size() > MAX_TOOL_CALLS) {
                        return CompletableFuture.completedFuture("Я остановил выполнение: достигнут безопасный предел действий за один запрос.");
                    }
                    List<JsonObject> validCalls = new ArrayList<>();
                    List<CompletableFuture<JsonObject>> results = new ArrayList<>();
                    for (JsonElement element : calls) {
                        if (!element.isJsonObject()) continue;
                        JsonObject call = element.getAsJsonObject();
                        validCalls.add(call);
                        String name = toolName(call);
                        AiAgentStatus.set(statusForTool(name));
                        results.add(executeOpenAiToolAsync(task, call, cache));
                    }
                    return sequence(results).thenCompose(toolResults -> {
                        for (int i = 0; i < validCalls.size(); i++) {
                            JsonObject resultMessage = new JsonObject();
                            resultMessage.addProperty("role", "tool");
                            resultMessage.addProperty("tool_call_id", string(validCalls.get(i), "id", ""));
                            resultMessage.addProperty("content", compactJson(toolResults.get(i), MAX_TOOL_RESULT_CHARS));
                            messages.add(resultMessage);
                        }
                        if (validCalls.stream().allMatch(c -> FAST_ACTION_TOOLS.contains(toolName(c)))
                                && toolResults.stream().allMatch(AiAgentService::toolResultOk)) {
                            AiAgentStatus.clear();
                            return CompletableFuture.completedFuture(localActionSummary(validCalls));
                        }
                        return openAiLoop(task, messages, provider, cache, round + 1, totalCalls + validCalls.size(), false, false);
                    });
                });
    }

    private static CompletableFuture<JsonObject> executeOpenAiToolAsync(ActiveTask task, JsonObject call, Map<String, JsonObject> cache) {
        return executeToolWithCache(task, toolName(call), callArguments(call), cache);
    }

    private static CompletableFuture<JsonObject> executeToolWithCache(ActiveTask task, String name, JsonObject arguments, Map<String, JsonObject> cache) {
        String key = name + "|" + arguments;
        JsonObject cached = cache.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached.deepCopy());
        if (task.cancelled) return completedToolError("Запрос отменён.");
        AiAgentLog.info("TOOL CALL name=" + name + " args=" + compactJson(arguments, 1200));
        return runOnServer(task, name, arguments).thenApply(result -> {
            cache.put(key, result.deepCopy());
            System.out.println("[Minecraft AI Agent] TOOL RESULT name=" + name + " ok=" + toolResultOk(result));
            return result;
        });
    }

    private static CompletableFuture<JsonObject> runOnServer(ActiveTask task, String name, JsonObject args) {
        MinecraftServer server = net.minecraft.client.MinecraftClient.getInstance().getServer();
        if (server == null) return completedToolError("Нет запущенного локального Minecraft-сервера.");
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            if (task.cancelled) {
                future.complete(errorResult("Запрос отменён."));
                return;
            }
            try {
                JsonObject result = executeTool(server, name, args, task);
                future.complete(result);
            } catch (Exception exception) {
                future.complete(errorResult(exception.getMessage() == null ? exception.toString() : exception.getMessage()));
            }
        });
        return future;
    }

    private static JsonObject executeTool(MinecraftServer server, String name, JsonObject args, ActiveTask task) {
        return switch (name) {
            case "observe_world" -> observeWorld(server, args, task);
            case "inspect_region" -> inspectRegion(server, args, task);
            case "locate_structure" -> locateStructure(server, args);
            case "build_house" -> buildHouse(server, args, task);
            case "build_blueprint" -> buildBlueprint(server, args, task);
            case "clear_area" -> clearArea(server, args, task);
            case "break_block" -> breakBlock(server, args);
            case "give_item" -> giveItem(server, args);
            case "apply_effect" -> applyEffect(server, args);
            case "set_player_stats" -> setPlayerStats(server, args);
            case "clear_inventory" -> clearInventory(server, args);
            case "teleport_player" -> teleportPlayer(server, args);
            case "set_gamemode" -> setGamemode(server, args);
            case "set_time" -> setTime(server, args);
            case "set_weather" -> setWeather(server, args);
            case "spawn_entity" -> spawnEntity(server, args);
            case "get_inventory" -> getInventory(server, args);
            case "list_players" -> listPlayers(server);
            case "remember_memory" -> rememberMemory(args);
            case "forget_memory" -> forgetMemory(args);
            case "run_minecraft_command" -> runMinecraftCommand(server, args);
            case "send_chat" -> sendChat(server, args);
            default -> errorResult("Неизвестный инструмент: " + name);
        };
    }

    private static JsonObject observeWorld(MinecraftServer server, JsonObject args, ActiveTask task) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        int radius = Math.min(MAX_OBSERVE_RADIUS, Math.max(2, optionalInt(args, "radius", 8)));
        ServerWorld world = player.getServerWorld();
        int px = player.getBlockPos().getX();
        int py = player.getBlockPos().getY();
        int pz = player.getBlockPos().getZ();

        JsonObject data = playerState(player);
        data.addProperty("world_time", world.getTimeOfDay());
        data.addProperty("day_time", world.getTime());
        data.addProperty("raining", world.isRaining());
        data.addProperty("thundering", world.isThundering());
        data.addProperty("difficulty", world.getDifficulty().getName());

        JsonArray entities = new JsonArray();
        List<Entity> nearby = world.getOtherEntities(player,
                new Box(px - radius, py - radius, pz - radius, px + radius + 1, py + radius + 1, pz + radius + 1),
                entity -> true);
        nearby.stream().sorted(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(player))).limit(MAX_ENTITY_RESULTS).forEach(entity -> {
            JsonObject e = new JsonObject();
            e.addProperty("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
            e.addProperty("name", entity.getName().getString());
            e.addProperty("x", round(entity.getX()));
            e.addProperty("y", round(entity.getY()));
            e.addProperty("z", round(entity.getZ()));
            e.addProperty("distance", round(Math.sqrt(entity.squaredDistanceTo(player))));
            if (entity instanceof LivingEntity living) {
                e.addProperty("health", round(living.getHealth()));
                e.addProperty("max_health", round(living.getMaxHealth()));
            }
            entities.add(e);
        });
        data.add("nearby_entities", entities);

        JsonArray surface = new JsonArray();
        int bottom = Math.max(world.getBottomY(), py - 32);
        int top = Math.min(world.getTopY() - 1, py + 16);
        for (int dx = -radius; dx <= radius; dx++) {
            if (task.cancelled) return errorResult("Запрос отменён.");
            for (int dz = -radius; dz <= radius; dz++) {
                int x = px + dx;
                int z = pz + dz;
                for (int y = top; y >= bottom; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = world.getBlockState(pos);
                    if (!state.isAir() && state.isSolidBlock(world, pos)) {
                        JsonObject cell = new JsonObject();
                        cell.addProperty("dx", dx);
                        cell.addProperty("dz", dz);
                        cell.addProperty("y", y);
                        cell.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
                        surface.add(cell);
                        break;
                    }
                }
            }
        }
        data.add("surface", surface);

        JsonArray nearbyBlocks = new JsonArray();
        int localRadius = Math.min(3, radius);
        for (int dx = -localRadius; dx <= localRadius; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -localRadius; dz <= localRadius; dz++) {
                    BlockPos pos = new BlockPos(px + dx, py + dy, pz + dz);
                    var state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    JsonObject block = new JsonObject();
                    block.addProperty("dx", dx);
                    block.addProperty("dy", dy);
                    block.addProperty("dz", dz);
                    block.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
                    nearbyBlocks.add(block);
                }
            }
        }
        data.add("nearby_blocks", nearbyBlocks);
        return okResult(data);
    }

    private static JsonObject inspectRegion(MinecraftServer server, JsonObject args, ActiveTask task) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        ServerWorld world = player.getServerWorld();
        int cx = args.has("x") ? args.get("x").getAsInt() : player.getBlockPos().getX();
        int cy = args.has("y") ? args.get("y").getAsInt() : player.getBlockPos().getY();
        int cz = args.has("z") ? args.get("z").getAsInt() : player.getBlockPos().getZ();
        int rx = Math.min(6, Math.max(1, optionalInt(args, "radius", 4)));
        int ry = Math.min(5, Math.max(1, optionalInt(args, "vertical", 3)));
        JsonArray blocks = new JsonArray();
        int total = 0;
        for (int x = cx - rx; x <= cx + rx; x++) {
            for (int y = cy - ry; y <= cy + ry; y++) {
                for (int z = cz - rx; z <= cz + rx; z++) {
                    if (task.cancelled) return errorResult("Запрос отменён.");
                    var state = world.getBlockState(new BlockPos(x, y, z));
                    if (state.isAir()) continue;
                    total++;
                    if (blocks.size() >= 900) continue;
                    JsonObject block = new JsonObject();
                    block.addProperty("x", x);
                    block.addProperty("y", y);
                    block.addProperty("z", z);
                    block.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
                    blocks.add(block);
                }
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("center_x", cx);
        data.addProperty("center_y", cy);
        data.addProperty("center_z", cz);
        data.addProperty("total_non_air", total);
        data.add("blocks", blocks);
        return okResult(data);
    }

    private static JsonObject locateStructure(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        ServerWorld world = player.getServerWorld();
        String structure = string(args, "structure", "village").toLowerCase(Locale.ROOT);
        int radius = Math.min(128, Math.max(1, optionalInt(args, "radius", 32)));
        var tag = structureTag(structure);
        BlockPos found = world.locateStructure(tag, player.getBlockPos(), radius, false);
        JsonObject data = new JsonObject();
        data.addProperty("requested_structure", structure);
        data.addProperty("search_radius_chunks", radius);
        if (found == null) {
            data.addProperty("found", false);
        } else {
            data.addProperty("found", true);
            data.addProperty("x", found.getX());
            data.addProperty("y", found.getY());
            data.addProperty("z", found.getZ());
            data.addProperty("distance", Math.round(Math.sqrt(found.getSquaredDistance(player.getBlockPos())) * 10.0) / 10.0);
        }
        return okResult(data);
    }

    private static net.minecraft.registry.tag.TagKey<net.minecraft.world.gen.structure.Structure> structureTag(String name) {
        return switch (name) {
            case "village", "villages", "деревня", "деревни" -> StructureTags.VILLAGE;
            case "mineshaft", "шахта" -> StructureTags.MINESHAFT;
            case "shipwreck", "корабль", "кораблекрушение" -> StructureTags.SHIPWRECK;
            case "ocean_ruin", "подводные_руины" -> StructureTags.OCEAN_RUIN;
            case "ruined_portal", "разрушенный_портал" -> StructureTags.RUINED_PORTAL;
            case "stronghold", "крепость", "крепость_энд" -> StructureTags.EYE_OF_ENDER_LOCATED;
            default -> {
                Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
                if (id == null) id = Identifier.tryParse("minecraft:village");
                yield net.minecraft.registry.tag.TagKey.of(RegistryKeys.STRUCTURE, id);
            }
        };
    }

    private static JsonObject buildHouse(MinecraftServer server, JsonObject args, ActiveTask task) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        ServerWorld world = player.getServerWorld();
        int width = clampOdd(optionalInt(args, "width", 9), 7, 15);
        int depth = clampOdd(optionalInt(args, "depth", 7), 7, 15);
        int wallHeight = Math.min(6, Math.max(3, optionalInt(args, "wall_height", 4)));
        String wallId = string(args, "wall_block", "minecraft:oak_planks");
        String roofId = string(args, "roof_block", "minecraft:spruce_planks");
        String floorId = string(args, "floor_block", "minecraft:spruce_planks");
        String glassId = string(args, "window_block", "minecraft:glass_pane");
        validateBlockIds(wallId, roofId, floorId, glassId);

        int px = player.getBlockPos().getX();
        int pz = player.getBlockPos().getZ();
        double radians = Math.toRadians(player.getYaw());
        int forwardX = (int) Math.round(-Math.sin(radians));
        int forwardZ = (int) Math.round(Math.cos(radians));
        if (forwardX == 0 && forwardZ == 0) forwardZ = 1;
        int cx = px + forwardX * (Math.max(width, depth) / 2 + 6);
        int cz = pz + forwardZ * (Math.max(width, depth) / 2 + 6);
        int groundY = findGroundY(world, cx, cz, player.getBlockPos().getY());
        int minX = cx - width / 2;
        int maxX = cx + width / 2;
        int minZ = cz - depth / 2;
        int maxZ = cz + depth / 2;
        int changed = 0;

        Block floor = blockFromId(floorId);
        Block wall = blockFromId(wallId);
        Block roof = blockFromId(roofId);
        Block glass = blockFromId(glassId);
        Block foundation = blockFromId("minecraft:cobblestone");

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (task.cancelled) return errorResult("Запрос отменён.");
                if (world.setBlockState(new BlockPos(x, groundY, z), floor.getDefaultState())) changed++;
                if (world.setBlockState(new BlockPos(x, groundY - 1, z), foundation.getDefaultState())) changed++;
            }
        }

        for (int y = groundY + 1; y <= groundY + wallHeight; y++) {
            for (int x = minX; x <= maxX; x++) {
                changed += setBuildBlock(world, new BlockPos(x, y, minZ), wall.getDefaultState());
                changed += setBuildBlock(world, new BlockPos(x, y, maxZ), wall.getDefaultState());
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                changed += setBuildBlock(world, new BlockPos(minX, y, z), wall.getDefaultState());
                changed += setBuildBlock(world, new BlockPos(maxX, y, z), wall.getDefaultState());
            }
        }

        int centerX = (minX + maxX) / 2;
        int doorY = groundY + 1;
        world.setBlockState(new BlockPos(centerX, doorY, minZ), net.minecraft.block.Blocks.AIR.getDefaultState());
        world.setBlockState(new BlockPos(centerX, doorY + 1, minZ), net.minecraft.block.Blocks.AIR.getDefaultState());
        changed += setBuildBlock(world, new BlockPos(minX + 2, groundY + 2, minZ), glass.getDefaultState());
        changed += setBuildBlock(world, new BlockPos(maxX - 2, groundY + 2, minZ), glass.getDefaultState());
        changed += setBuildBlock(world, new BlockPos(minX + 2, groundY + 2, maxZ), glass.getDefaultState());
        changed += setBuildBlock(world, new BlockPos(maxX - 2, groundY + 2, maxZ), glass.getDefaultState());
        int sideZ = (minZ + maxZ) / 2;
        changed += setBuildBlock(world, new BlockPos(minX, groundY + 2, sideZ - 1), glass.getDefaultState());
        changed += setBuildBlock(world, new BlockPos(minX, groundY + 2, sideZ + 1), glass.getDefaultState());

        for (int layer = 0; layer <= width / 2; layer++) {
            int y = groundY + wallHeight + 1 + layer;
            int left = minX + layer;
            int right = maxX - layer;
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                changed += setBuildBlock(world, new BlockPos(left, y, z), roof.getDefaultState());
                changed += setBuildBlock(world, new BlockPos(right, y, z), roof.getDefaultState());
            }
            if (left >= right) break;
        }
        int ridgeX = centerX;
        int ridgeY = groundY + wallHeight + 1 + width / 2;
        for (int z = minZ - 1; z <= maxZ + 1; z++) changed += setBuildBlock(world, new BlockPos(ridgeX, ridgeY, z), roof.getDefaultState());

        Block chimney = blockFromId("minecraft:stone_bricks");
        for (int y = groundY + wallHeight + 1; y < ridgeY + 1; y++) changed += setBuildBlock(world, new BlockPos(maxX - 1, y, maxZ - 1), chimney.getDefaultState());

        // Add a simple entrance porch so the result does not look like a box.
        for (int z = minZ - 2; z <= minZ - 1; z++) {
            for (int x = centerX - 1; x <= centerX + 1; x++) changed += setBuildBlock(world, new BlockPos(x, groundY, z), floor.getDefaultState());
        }
        Block fence = blockFromId("minecraft:spruce_fence");
        changed += setBuildBlock(world, new BlockPos(centerX - 2, groundY + 1, minZ - 1), fence.getDefaultState());
        changed += setBuildBlock(world, new BlockPos(centerX + 2, groundY + 1, minZ - 1), fence.getDefaultState());

        JsonObject data = new JsonObject();
        data.addProperty("built", true);
        data.addProperty("center_x", cx);
        data.addProperty("base_y", groundY);
        data.addProperty("center_z", cz);
        data.addProperty("width", width);
        data.addProperty("depth", depth);
        data.addProperty("changed_blocks", changed);
        data.addProperty("description", "compact house with floor, walls, windows, entrance porch, stepped gable roof and chimney");
        return okResult(data);
    }

    private static JsonObject buildBlueprint(MinecraftServer server, JsonObject args, ActiveTask task) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        ServerWorld world = player.getServerWorld();
        JsonArray operations = args.get("operations").isJsonArray() ? args.getAsJsonArray("operations") : new JsonArray();
        if (operations.size() > MAX_BLUEPRINT_OPS) return errorResult("Слишком много операций в чертеже.");
        int totalChanged = 0;
        int totalRequested = 0;
        for (JsonElement element : operations) {
            if (task.cancelled) return errorResult("Запрос отменён.");
            if (!element.isJsonObject()) return errorResult("Каждая операция должна быть объектом.");
            JsonObject op = element.getAsJsonObject();
            String type = string(op, "type", "");
            switch (type) {
                case "fill", "hollow", "clear" -> {
                    int x1 = requiredInt(op, "x1");
                    int y1 = requiredInt(op, "y1");
                    int z1 = requiredInt(op, "z1");
                    int x2 = requiredInt(op, "x2");
                    int y2 = requiredInt(op, "y2");
                    int z2 = requiredInt(op, "z2");
                    long volume = volume(x1, y1, z1, x2, y2, z2);
                    totalRequested += (int) Math.min(volume, (long) Integer.MAX_VALUE);
                    if (volume > MAX_BUILD_VOLUME) return errorResult("Одна операция слишком большая.");
                    Block block = type.equals("clear") ? net.minecraft.block.Blocks.AIR : blockFromId(string(op, "block", ""));
                    String mode = type.equals("hollow") ? "hollow" : "fill";
                    int changed = applyCuboid(world, x1, y1, z1, x2, y2, z2, block, mode, task);
                    if (changed < 0) return errorResult("Запрос отменён.");
                    totalChanged += changed;
                }
                case "set" -> {
                    Block block = blockFromId(string(op, "block", ""));
                    totalRequested++;
                    if (setBuildBlock(world, new BlockPos(requiredInt(op, "x"), requiredInt(op, "y"), requiredInt(op, "z")), block.getDefaultState()) > 0) totalChanged++;
                }
                case "line" -> {
                    Block block = blockFromId(string(op, "block", ""));
                    int x1 = requiredInt(op, "x1");
                    int y1 = requiredInt(op, "y1");
                    int z1 = requiredInt(op, "z1");
                    int x2 = requiredInt(op, "x2");
                    int y2 = requiredInt(op, "y2");
                    int z2 = requiredInt(op, "z2");
                    int steps = Math.max(Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)), Math.abs(z2 - z1));
                    if (steps > 128) return errorResult("Операция line слишком длинная.");
                    for (int i = 0; i <= steps; i++) {
                        if (task.cancelled) return errorResult("Запрос отменён.");
                        int x = x1 + Math.round((x2 - x1) * (i / (float) steps));
                        int y = y1 + Math.round((y2 - y1) * (i / (float) steps));
                        int z = z1 + Math.round((z2 - z1) * (i / (float) steps));
                        totalRequested++;
                        totalChanged += setBuildBlock(world, new BlockPos(x, y, z), block.getDefaultState());
                    }
                }
                case "roof_gable" -> {
                    int x1 = requiredInt(op, "x1");
                    int x2 = requiredInt(op, "x2");
                    int z1 = requiredInt(op, "z1");
                    int z2 = requiredInt(op, "z2");
                    int y = requiredInt(op, "y");
                    int height = Math.min(8, Math.max(1, optionalInt(op, "height", 3)));
                    Block roof = blockFromId(string(op, "block", "minecraft:spruce_planks"));
                    int width = Math.abs(x2 - x1) + 1;
                    for (int layer = 0; layer <= height; layer++) {
                        int left = Math.min(x1, x2) + Math.min(layer, width / 2);
                        int right = Math.max(x1, x2) - Math.min(layer, width / 2);
                        for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                            if (task.cancelled) return errorResult("Запрос отменён.");
                            totalRequested += Math.max(0, right - left + 1);
                            if (totalRequested > MAX_BUILD_VOLUME) return errorResult("Чертёж слишком большой.");
                            for (int x = left; x <= right; x++) totalChanged += setBuildBlock(world, new BlockPos(x, y + layer, z), roof.getDefaultState());
                        }
                    }
                }
                default -> { return errorResult("Неизвестная операция чертежа: " + type); }
            }
            if (totalRequested > MAX_BUILD_VOLUME) return errorResult("Чертёж слишком большой.");
        }
        JsonObject data = new JsonObject();
        data.addProperty("built", true);
        data.addProperty("operations", operations.size());
        data.addProperty("requested_blocks", totalRequested);
        data.addProperty("changed_blocks", totalChanged);
        return okResult(data);
    }

    private static JsonObject clearArea(MinecraftServer server, JsonObject args, ActiveTask task) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        ServerWorld world = player.getServerWorld();
        int cx = args.has("x") ? args.get("x").getAsInt() : player.getBlockPos().getX();
        int cy = args.has("y") ? args.get("y").getAsInt() : player.getBlockPos().getY();
        int cz = args.has("z") ? args.get("z").getAsInt() : player.getBlockPos().getZ();
        int radius = Math.min(32, Math.max(1, optionalInt(args, "radius", 5)));
        int vertical = Math.min(radius, Math.max(1, optionalInt(args, "vertical", radius)));
        boolean sphere = !string(args, "shape", "sphere").equalsIgnoreCase("cube");
        long estimated = sphere ? Math.round(4.18879 * radius * radius * radius) : (2L * radius + 1) * (2L * radius + 1) * (2L * vertical + 1);
        if (estimated > MAX_CLEAR_VOLUME) return errorResult("Запрошенная область слишком большая. Уменьши радиус.");
        int changed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -vertical; dy <= vertical; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (task.cancelled) return errorResult("Запрос отменён.");
                    if (sphere && (dx * dx + dy * dy + dz * dz > radius * radius)) continue;
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    var state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    if (world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState())) changed++;
                }
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("cleared", true);
        data.addProperty("center_x", cx);
        data.addProperty("center_y", cy);
        data.addProperty("center_z", cz);
        data.addProperty("radius", radius);
        data.addProperty("changed_blocks", changed);
        return okResult(data);
    }

    private static JsonObject breakBlock(MinecraftServer server, JsonObject args) {
        ServerWorld world = getPlayer(server, string(args, "player", "")).getServerWorld();
        BlockPos pos = new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
        String previous = Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString();
        boolean changed = world.breakBlock(pos, false);
        JsonObject data = new JsonObject();
        data.addProperty("changed", changed);
        data.addProperty("previous_block", previous);
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        return okResult(data);
    }

    private static JsonObject giveItem(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        String itemId = string(args, "item", "");
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) return errorResult("Неизвестный предмет: " + itemId);
        int count = Math.min(64, Math.max(1, optionalInt(args, "count", 1)));
        StringBuilder stack = new StringBuilder(itemId);
        List<String> components = new ArrayList<>();
        String enchantments = string(args, "enchantments", "").trim();
        if (!enchantments.isBlank()) {
            StringBuilder levels = new StringBuilder();
            for (String entry : enchantments.split(",")) {
                String[] pair = entry.trim().split("[=:]", 2);
                if (pair.length != 2) continue;
                String ench = pair[0].trim();
                if (!ench.contains(":")) ench = "minecraft:" + ench;
                int level;
                try { level = Math.min(255, Math.max(1, Integer.parseInt(pair[1].trim()))); }
                catch (NumberFormatException ignored) { continue; }
                if (!levels.isEmpty()) levels.append(",");
                levels.append(ench).append(":").append(level);
            }
            if (!levels.isEmpty()) components.add("enchantments={levels:{" + levels + "}}");
        }
        if (args.has("unbreakable") && args.get("unbreakable").getAsBoolean()) components.add("unbreakable={}");
        String name = string(args, "custom_name", "").trim();
        if (!name.isBlank()) {
            JsonObject nameObject = new JsonObject();
            nameObject.addProperty("text", name);
            components.add("custom_name='" + new com.google.gson.Gson().toJson(nameObject) + "'");
        }
        if (!components.isEmpty()) stack.append("[").append(String.join(",", components)).append("]");
        String command = "give " + player.getName().getString() + " " + stack + " " + count;
        JsonObject result = runCommand(server, command);
        result.addProperty("item", itemId);
        result.addProperty("count", count);
        result.addProperty("enchantments", enchantments);
        return result;
    }

    private static JsonObject applyEffect(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        String effect = string(args, "effect", "").trim().toLowerCase(Locale.ROOT);
        if (effect.isBlank()) return errorResult("Не указан эффект.");
        if (!effect.contains(":")) effect = "minecraft:" + effect;
        if (args.has("clear") && args.get("clear").getAsBoolean()) return runCommand(server, "effect clear " + player.getName().getString() + " " + effect);
        int duration = Math.min(1000000, Math.max(1, optionalInt(args, "duration", 30)));
        int level = Math.min(255, Math.max(1, optionalInt(args, "level", 1)));
        boolean particles = !args.has("show_particles") || args.get("show_particles").getAsBoolean();
        String command = "effect give " + player.getName().getString() + " " + effect + " " + duration + " " + (level - 1) + " " + (!particles);
        return runCommand(server, command);
    }

    private static JsonObject setPlayerStats(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        if (args.has("health")) player.setHealth(Math.max(0.0F, Math.min(player.getMaxHealth(), args.get("health").getAsFloat())));
        if (args.has("hunger")) player.getHungerManager().setFoodLevel(Math.max(0, Math.min(20, args.get("hunger").getAsInt())));
        if (args.has("saturation")) player.getHungerManager().setSaturationLevel(Math.max(0.0F, Math.min(20.0F, args.get("saturation").getAsFloat())));
        player.currentScreenHandler.sendContentUpdates();
        return okResult(playerState(player));
    }

    private static JsonObject clearInventory(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        int removed = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) { removed += stack.getCount(); player.getInventory().setStack(i, ItemStack.EMPTY); }
        }
        player.currentScreenHandler.sendContentUpdates();
        JsonObject data = new JsonObject();
        data.addProperty("cleared", true);
        data.addProperty("items_removed", removed);
        return okResult(data);
    }

    private static JsonObject teleportPlayer(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        double x = requiredDouble(args, "x");
        double y = requiredDouble(args, "y");
        double z = requiredDouble(args, "z");
        String dimension = string(args, "dimension", "").trim();
        String command = "tp " + player.getName().getString() + " " + x + " " + y + " " + z;
        if (!dimension.isBlank()) command = "execute in " + (dimension.contains(":") ? dimension : "minecraft:" + dimension) + " run " + command;
        return runCommand(server, command);
    }

    private static JsonObject setGamemode(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        String mode = normalizeGamemode(string(args, "mode", "survival"));
        if (!(mode.equals("survival") || mode.equals("creative") || mode.equals("adventure") || mode.equals("spectator"))) return errorResult("Неизвестный режим: " + mode);
        return runCommand(server, "gamemode " + mode + " " + player.getName().getString());
    }

    private static String normalizeGamemode(String value) {
        return switch (value.toLowerCase(Locale.ROOT).trim()) {
            case "спектатор", "спектаторский", "spectate", "spec" -> "spectator";
            case "креатив", "творческий" -> "creative";
            case "выживание", "сурвайвал", "выживач" -> "survival";
            case "приключение" -> "adventure";
            default -> value.toLowerCase(Locale.ROOT).trim();
        };
    }

    private static JsonObject setTime(MinecraftServer server, JsonObject args) {
        String time = string(args, "time", "day").toLowerCase(Locale.ROOT);
        return runCommand(server, "time set " + time);
    }

    private static JsonObject setWeather(MinecraftServer server, JsonObject args) {
        String weather = string(args, "weather", "clear").toLowerCase(Locale.ROOT);
        return runCommand(server, "weather " + weather + " 1000000");
    }

    private static JsonObject spawnEntity(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        String entity = string(args, "entity", "minecraft:pig");
        Identifier id = Identifier.tryParse(entity);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) return errorResult("Неизвестное существо: " + entity);
        int x = args.has("x") ? args.get("x").getAsInt() : player.getBlockPos().getX();
        int y = args.has("y") ? args.get("y").getAsInt() : player.getBlockPos().getY();
        int z = args.has("z") ? args.get("z").getAsInt() : player.getBlockPos().getZ();
        return runCommand(server, "summon " + entity + " " + x + " " + y + " " + z);
    }

    private static JsonObject getInventory(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        JsonArray items = new JsonArray();
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("slot", slot);
            item.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
            item.addProperty("count", stack.getCount());
            items.add(item);
        }
        JsonObject data = new JsonObject();
        data.add("items", items);
        data.addProperty("occupied_slots", items.size());
        return okResult(data);
    }

    private static JsonObject listPlayers(MinecraftServer server) {
        JsonArray players = new JsonArray();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) players.add(player.getName().getString());
        JsonObject data = new JsonObject();
        data.add("players", players);
        return okResult(data);
    }

    private static JsonObject rememberMemory(JsonObject args) {
        String chatId = AiChatHistory.getCurrentChatId();
        String text = string(args, "text", "");
        // The active chat is the memory namespace; service switches it immediately before tool execution.
        if (args.has("_chat_id")) chatId = args.get("_chat_id").getAsString();
        return boolResult("saved", AiAgentMemory.remember(chatId, text), text);
    }

    private static JsonObject forgetMemory(JsonObject args) {
        String chatId = AiChatHistory.getCurrentChatId();
        String text = string(args, "text", "");
        if (args.has("_chat_id")) chatId = args.get("_chat_id").getAsString();
        return boolResult("removed", AiAgentMemory.forget(chatId, text), text);
    }

    private static JsonObject runMinecraftCommand(MinecraftServer server, JsonObject args) {
        String command = string(args, "command", "").trim();
        if (command.startsWith("/")) command = command.substring(1).trim();
        if (command.isBlank()) return errorResult("Пустая команда.");
        String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED_COMMANDS.contains(root)) return errorResult("Команда запрещена: " + root);
        return runCommand(server, command);
    }

    private static JsonObject sendChat(MinecraftServer server, JsonObject args) {
        String message = string(args, "message", "").trim();
        if (message.isBlank()) return errorResult("Пустое сообщение.");
        server.getPlayerManager().broadcast(net.minecraft.text.Text.literal("[AI] " + message), false);
        JsonObject data = new JsonObject();
        data.addProperty("sent", true);
        data.addProperty("message", message);
        return okResult(data);
    }

    private static JsonObject runCommand(MinecraftServer server, String command) {
        try {
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);
            JsonObject data = new JsonObject();
            data.addProperty("executed", true);
            return okResult(data);
        } catch (Exception exception) {
            return errorResult(exception.getMessage() == null ? exception.toString() : exception.getMessage());
        }
    }

    private static JsonObject playerState(ServerPlayerEntity player) {
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
        data.addProperty("max_health", player.getMaxHealth());
        data.addProperty("food", player.getHungerManager().getFoodLevel());
        data.addProperty("saturation", player.getHungerManager().getSaturationLevel());
        data.addProperty("game_mode", player.interactionManager.getGameMode().getName());
        data.addProperty("dimension", player.getWorld().getRegistryKey().getValue().toString());
        return data;
    }

    private static ServerPlayerEntity getPlayer(MinecraftServer server, String name) {
        if (name == null || name.isBlank()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayerList().stream().findFirst().orElse(null);
            if (player == null) throw new IllegalArgumentException("Нет игроков онлайн.");
            return player;
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
        if (player == null) throw new IllegalArgumentException("Игрок не найден: " + name);
        return player;
    }

    private static int findGroundY(ServerWorld world, int x, int z, int aroundY) {
        int bottom = Math.max(world.getBottomY(), aroundY - 24);
        int top = Math.min(world.getTopY() - 2, aroundY + 16);
        for (int y = top; y >= bottom; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            var state = world.getBlockState(pos);
            var above = world.getBlockState(pos.up());
            if (!state.isAir() && state.isSolidBlock(world, pos) && above.isAir()) return y + 1;
        }
        return aroundY;
    }

    private static int applyCuboid(ServerWorld world, int x1, int y1, int z1, int x2, int y2, int z2, Block block, String mode, ActiveTask task) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int changed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (task.cancelled) return -1;
                    boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    if (mode.equals("hollow") && !boundary) continue;
                    if (world.setBlockState(new BlockPos(x, y, z), block.getDefaultState())) changed++;
                }
            }
        }
        return changed;
    }

    private static int setBuildBlock(ServerWorld world, BlockPos pos, net.minecraft.block.BlockState state) {
        return world.setBlockState(pos, state) ? 1 : 0;
    }

    private static Block blockFromId(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null || !Registries.BLOCK.containsId(identifier)) throw new IllegalArgumentException("Неизвестный блок: " + id);
        return Registries.BLOCK.get(identifier);
    }

    private static void validateBlockIds(String... ids) {
        for (String id : ids) blockFromId(id);
    }

    private static long volume(int x1, int y1, int z1, int x2, int y2, int z2) {
        return (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
    }

    private static int clampOdd(int value, int min, int max) {
        int result = Math.min(max, Math.max(min, value));
        if ((result & 1) == 0) result--;
        return Math.max(min, result);
    }

    private static JsonObject okResult(JsonObject data) {
        data.addProperty("ok", true);
        return data;
    }

    private static JsonObject errorResult(String error) {
        JsonObject data = new JsonObject();
        data.addProperty("ok", false);
        data.addProperty("error", error);
        return data;
    }

    private static JsonObject boolResult(String key, boolean value, String text) {
        JsonObject data = new JsonObject();
        data.addProperty("ok", true);
        data.addProperty(key, value);
        data.addProperty("text", text);
        return data;
    }

    private static CompletableFuture<JsonObject> completedToolError(String message) {
        return CompletableFuture.completedFuture(errorResult(message));
    }

    private static boolean toolResultOk(JsonObject result) {
        return result.has("ok") && result.get("ok").getAsBoolean();
    }

    private static boolean allFastCalls(List<JsonObject> calls) {
        return !calls.isEmpty() && calls.stream().allMatch(call -> FAST_ACTION_TOOLS.contains(string(call, "name", "")));
    }

    private static String localActionSummary(List<JsonObject> calls) {
        if (calls.size() == 1) {
            return switch (string(calls.get(0), "name", "")) {
                case "give_item" -> "Готово — предмет выдан.";
                case "apply_effect" -> "Готово — эффект применён.";
                case "set_gamemode" -> "Готово — режим игры изменён.";
                case "teleport_player" -> "Готово — телепортация выполнена.";
                case "clear_inventory" -> "Готово — инвентарь очищен.";
                case "clear_area" -> "Готово — область очищена.";
                case "set_player_stats" -> "Готово — характеристики игрока изменены.";
                case "spawn_entity" -> "Готово — существо создано.";
                default -> "Готово.";
            };
        }
        return "Готово — запрошенные действия выполнены.";
    }

    private static JsonObject callArguments(JsonObject call) {
        JsonObject function = call.has("function") && call.get("function").isJsonObject() ? call.getAsJsonObject("function") : call;
        JsonObject args;
        JsonElement argumentElement = function.get("arguments");
        try {
            if (argumentElement != null && argumentElement.isJsonObject()) args = argumentElement.getAsJsonObject().deepCopy();
            else if (argumentElement != null && argumentElement.isJsonPrimitive()) args = JsonParser.parseString(argumentElement.getAsString()).getAsJsonObject();
            else args = new JsonObject();
        } catch (RuntimeException exception) {
            args = new JsonObject();
        }
        // Memory is always scoped to the chat that owns the task.
        String chatId = getActiveChatId();
        if (!chatId.isBlank() && (string(function, "name", "").equals("remember_memory") || string(function, "name", "").equals("forget_memory"))) args.addProperty("_chat_id", chatId);
        return args;
    }

    private static String toolName(JsonObject call) {
        JsonObject function = call.has("function") && call.get("function").isJsonObject() ? call.getAsJsonObject("function") : call;
        return string(function, "name", "unknown");
    }

    private static JsonArray geminiToolsArray() {
        JsonArray array = new JsonArray();
        for (JsonObject tool : TOOLS) array.add(tool.deepCopy());
        return array;
    }

    private static JsonArray openAiToolsArray() {
        JsonArray array = new JsonArray();
        for (JsonObject tool : TOOLS) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            JsonObject function = tool.deepCopy();
            function.remove("type");
            wrapper.add("function", function);
            array.add(wrapper);
        }
        return array;
    }

    private static final List<JsonObject> TOOLS = createTools();

    private static List<JsonObject> createTools() {
        List<JsonObject> tools = new ArrayList<>();
        tools.add(function("observe_world", "Observe the current player, exact position/rotation, time/weather, nearby blocks, surface heightmap and nearby entities/mobs. Use before spatial work.", objectProperties(
                property("player", "string", "Player name; empty means current player.", false),
                intPropertyOptional("radius", "Horizontal observation radius, 2..10.")
        )));
        tools.add(function("inspect_region", "Inspect exact non-air blocks in a local rectangular volume around a point. Use when exact block layout matters.", objectProperties(
                property("player", "string", "Player name; empty means current player.", false),
                intPropertyOptional("x", "Center X; defaults to player."), intPropertyOptional("y", "Center Y; defaults to player."), intPropertyOptional("z", "Center Z; defaults to player."),
                intPropertyOptional("radius", "Horizontal radius up to 6."), intPropertyOptional("vertical", "Vertical radius up to 5.")
        )));
        tools.add(function("locate_structure", "Find a nearby structure such as village, mineshaft, shipwreck, ocean ruin, ruined portal or stronghold and return exact coordinates.", objectProperties(
                property("structure", "string", "Structure name, e.g. village, stronghold, mineshaft, shipwreck, ruined_portal.", true),
                property("player", "string", "Player name; empty means current player.", false),
                intPropertyOptional("radius", "Search radius in chunks, up to 128.")
        )));
        tools.add(function("build_house", "Build a real compact house rather than a plain box: foundation, floor, wall openings, windows, entrance porch, stepped gable roof and chimney. Automatically chooses a safe location near and in front of the player.", objectProperties(
                property("player", "string", "Player name; empty means current player.", false),
                intPropertyOptional("width", "Odd width 7..15; default 9."), intPropertyOptional("depth", "Odd depth 7..15; default 7."), intPropertyOptional("wall_height", "Wall height 3..6; default 4."),
                property("wall_block", "string", "Wall block ID; default oak planks.", false), property("roof_block", "string", "Roof block ID; default spruce planks.", false),
                property("floor_block", "string", "Floor block ID; default spruce planks.", false), property("window_block", "string", "Window block ID; default glass pane.", false)
        )));
        tools.add(function("build_blueprint", "Build a custom structure efficiently from up to 96 primitives: fill, hollow, clear, set, line and roof_gable. Use this for detailed builds rather than many tiny calls.", blueprintProperties()));
        tools.add(function("clear_area", "Delete blocks in a radius around a point. Defaults to the current player position. shape=sphere is best for natural clearing; use cube for a rectangular clearing. Does not intentionally remove entities.", objectProperties(
                property("player", "string", "Player name; empty means current player.", false), intPropertyOptional("x", "Center X; defaults to player."), intPropertyOptional("y", "Center Y; defaults to player."), intPropertyOptional("z", "Center Z; defaults to player."),
                intPropertyOptional("radius", "Radius up to 32."), intPropertyOptional("vertical", "Vertical radius."), enumProperty("shape", new String[]{"sphere", "cube"}, "Clearing shape.", false)
        )));
        tools.add(function("break_block", "Break one exact block at coordinates.", objectProperties(
                property("player", "string", "Player name; empty means current player.", false), intProperty("x"), intProperty("y"), intProperty("z")
        )));
        tools.add(function("give_item", "Give an item directly. Supports enchantments like 'minecraft:sharpness=5,minecraft:unbreaking=3', custom count and unbreakable flag.", objectProperties(
                property("player", "string", "Player name; empty means current player.", false), property("item", "string", "Item ID such as minecraft:diamond_sword.", true), intPropertyOptional("count", "Amount 1..64."),
                property("enchantments", "string", "Comma-separated enchantment=level pairs.", false), property("custom_name", "string", "Optional visible custom name.", false), property("unbreakable", "boolean", "Make item unbreakable.", false)
        )));
        tools.add(function("apply_effect", "Apply or clear a potion effect. level is the human level (1 means amplifier 0).", objectProperties(
                property("player", "string", "Player name; empty means current player.", false), property("effect", "string", "Effect ID such as minecraft:speed or minecraft:night_vision.", true),
                intPropertyOptional("duration", "Duration in seconds."), intPropertyOptional("level", "Effect level 1..255."), property("clear", "boolean", "Clear this effect instead of applying it.", false), property("show_particles", "boolean", "Whether particles are shown.", false)
        )));
        tools.add(function("set_player_stats", "Directly change health, hunger and/or saturation.", objectProperties(
                property("player", "string", "Player name; empty means current player.", false), property("health", "number", "Absolute health value; omitted leaves it unchanged.", false), property("hunger", "integer", "Food level 0..20.", false), property("saturation", "number", "Saturation 0..20.", false)
        )));
        tools.add(function("clear_inventory", "Remove everything from a player's inventory.", objectProperties(property("player", "string", "Player name; empty means current player.", false))));
        tools.add(function("get_inventory", "Read the current inventory and item counts.", objectProperties(property("player", "string", "Player name; empty means current player.", false))));
        tools.add(function("teleport_player", "Teleport a player to exact coordinates, optionally in another dimension.", objectProperties(
                property("player", "string", "Player name; empty means current player.", false), property("x", "number", "Destination X.", true), property("y", "number", "Destination Y.", true), property("z", "number", "Destination Z.", true), property("dimension", "string", "Optional dimension ID such as minecraft:the_nether.", false)
        )));
        tools.add(function("set_gamemode", "Change game mode. Understand natural aliases such as 'спектатор'.", objectProperties(
                property("player", "string", "Player name; empty means current player.", false), property("mode", "string", "survival, creative, adventure or spectator; colloquial aliases are accepted.", true)
        )));
        tools.add(function("set_time", "Set Minecraft time.", objectProperties(enumProperty("time", new String[]{"day","night","noon","midnight"}, "Desired time.", true))));
        tools.add(function("set_weather", "Set Minecraft weather.", objectProperties(enumProperty("weather", new String[]{"clear","rain","thunder"}, "Desired weather.", true))));
        tools.add(function("spawn_entity", "Spawn any registered Minecraft or modded entity at coordinates; omitted coordinates use the player.", objectProperties(
                property("entity", "string", "Entity ID.", true), property("player", "string", "Player name; empty means current player.", false), intPropertyOptional("x", "X."), intPropertyOptional("y", "Y."), intPropertyOptional("z", "Z.")
        )));
        tools.add(function("list_players", "List online players.", objectProperties()));
        tools.add(function("remember_memory", "Remember a useful durable fact automatically when it will help in future turns: preferences, named locations, coordinates, build styles or other stable facts. Do not store trivial chat.", objectProperties(property("text", "string", "Durable fact to remember.", true))));
        tools.add(function("forget_memory", "Forget a stored durable memory.", objectProperties(property("text", "string", "Memory text to remove.", true))));
        tools.add(function("run_minecraft_command", "Run a normal in-game Minecraft command for supported gameplay actions when no specialized tool is enough. Do not use admin/server-control commands such as op, ban, stop or whitelist.", objectProperties(property("command", "string", "Minecraft command without or with a leading slash.", true))));
        tools.add(function("send_chat", "Send a message to all players from the AI.", objectProperties(property("message", "string", "Message text.", true))));
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
            boolean needed = property.get("_required").getAsBoolean();
            property.remove("_name");
            property.remove("_required");
            map.add(name, property);
            if (needed) required.add(name);
        }
        object.add("properties", map);
        object.add("required", required);
        object.addProperty("additionalProperties", false);
        return object;
    }

    private static JsonObject blueprintProperties() {
        JsonObject object = objectProperties(property("player", "string", "Player name; empty means current player.", false), property("operations", "array", "Build operations.", true));
        JsonObject operations = object.getAsJsonObject("properties").getAsJsonObject("operations");
        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("type", enumPropertyRaw(new String[]{"fill","hollow","clear","set","line","roof_gable"}, "Primitive operation."));
        for (String n : new String[]{"x1","y1","z1","x2","y2","z2","x","y","z","height"}) props.add(n, simpleType("integer"));
        JsonObject block = simpleType("string"); block.addProperty("description", "Block ID."); props.add("block", block);
        items.add("properties", props);
        items.addProperty("additionalProperties", true);
        operations.add("items", items);
        return object;
    }

    private static JsonObject property(String name, String type, String description, boolean required) {
        JsonObject property = new JsonObject();
        property.addProperty("_name", name);
        property.addProperty("_required", required);
        property.addProperty("type", type);
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject intProperty(String name) { return property(name, "integer", "Integer value for " + name + ".", true); }
    private static JsonObject intPropertyOptional(String name, String description) { return property(name, "integer", description, false); }

    private static JsonObject enumProperty(String name, String[] values, String description, boolean required) {
        JsonObject property = property(name, "string", description, required);
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        property.add("enum", array);
        return property;
    }

    private static JsonObject enumPropertyRaw(String[] values, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        property.add("enum", array);
        return property;
    }

    private static JsonObject simpleType(String type) { JsonObject object = new JsonObject(); object.addProperty("type", type); return object; }

    private static List<JsonObject> buildOpenAiContext(String chatId, String currentMessage, String provider) {
        List<JsonObject> result = new ArrayList<>();
        result.add(chatMessage("system", INSTRUCTIONS + "\n\nPersistent memory for this chat:\n" + AiAgentMemory.forPrompt(chatId)));
        int maxMessages = provider.equals("groq") ? 4 : 8;
        int maxChars = provider.equals("groq") ? 3500 : 9000;
        for (AiChatHistory.Entry entry : AiChatHistory.getRecentForApi(chatId, maxMessages, maxChars)) result.add(chatMessage(entry.role(), entry.text()));
        if (!currentMessage.isBlank()) {
            boolean already = result.stream().anyMatch(message -> string(message, "role", "").equals("user") && string(message, "content", "").equals(currentMessage));
            if (!already) result.add(chatMessage("user", currentMessage));
        }
        return result;
    }

    private static String buildRecoveryInput(String chatId, String message) {
        StringBuilder result = new StringBuilder(message).append("\n\nRecent local chat context:\n");
        for (AiChatHistory.Entry entry : AiChatHistory.getRecentForApi(chatId, 8, 9000)) result.append(entry.role()).append(": ").append(entry.text()).append('\n');
        return result.toString();
    }

    private static boolean isCapabilityQuestion(String message) {
        String lower = message.toLowerCase(Locale.ROOT).trim();
        return (lower.endsWith("?") && (lower.contains("можешь") || lower.contains("сможешь") || lower.contains("умеешь") || lower.contains("можно ли") || lower.contains("способен") || lower.contains("умеет ли")))
                || lower.startsWith("можешь ли") || lower.startsWith("сможешь ли") || lower.startsWith("умеешь ли");
    }

    private static String geminiOutputText(JsonObject response) {
        if (response.has("output_text") && !response.get("output_text").isJsonNull()) return response.get("output_text").getAsString();
        if (!response.has("steps") || !response.get("steps").isJsonArray()) return "";
        StringBuilder result = new StringBuilder();
        for (JsonElement element : response.getAsJsonArray("steps")) {
            if (!element.isJsonObject()) continue;
            JsonObject step = element.getAsJsonObject();
            if (!"model_output".equals(string(step, "type", "")) || !step.has("content")) continue;
            JsonElement content = step.get("content");
            if (!content.isJsonArray()) continue;
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && "text".equals(string(part.getAsJsonObject(), "type", ""))) {
                    if (!result.isEmpty()) result.append('\n');
                    result.append(string(part.getAsJsonObject(), "text", ""));
                }
            }
        }
        return result.toString().trim();
    }

    private record GeminiResult(JsonObject response, Throwable error) {}

    private static CompletableFuture<JsonObject> geminiRequest(ActiveTask task, String input, boolean noTools) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", GEMINI_MODEL);
        payload.addProperty("input", input);
        payload.addProperty("store", false);
        payload.addProperty("system_instruction", "Reply concisely. " + INSTRUCTIONS);
        if (!noTools) payload.add("tools", geminiToolsArray());
        return requestJson(GEMINI_URI, AiClientConfig.getGeminiApiKey(), payload, "Gemini");
    }

    private static CompletableFuture<JsonObject> requestJson(URI uri, String apiKey, JsonObject payload, String provider) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(90))
                .header(provider.equals("Gemini") ? "x-goog-api-key" : "Authorization", provider.equals("Gemini") ? apiKey : "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        int bytes = payload.toString().getBytes(StandardCharsets.UTF_8).length;
        System.out.println("[Minecraft AI Agent][" + provider + "] HTTP REQUEST bodyBytes=" + bytes);
        return sendOnce(request, provider).thenCompose(response -> {
            String body = response.body();
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                try { return CompletableFuture.completedFuture(JsonParser.parseString(body).getAsJsonObject()); }
                catch (RuntimeException exception) { return CompletableFuture.failedFuture(new IOException("Неверный JSON-ответ " + provider, exception)); }
            }
            String error;
            try { error = extractError(JsonParser.parseString(body).getAsJsonObject()); }
            catch (RuntimeException exception) { error = truncate(body, 1000); }
            return CompletableFuture.failedFuture(new IOException(provider + " API " + response.statusCode() + ": " + error));
        });
    }

    private static CompletableFuture<HttpResponse<String>> sendOnce(HttpRequest request, String provider) {
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    System.out.println("[Minecraft AI Agent][" + provider + "] HTTP RESPONSE status=" + response.statusCode());
                    if (response.statusCode() == 429) System.err.println("[Minecraft AI Agent][" + provider + "] RATE LIMITED - failing fast");
                    return response;
                })
                .exceptionallyCompose(throwable -> {
                    Throwable cause = rootCause(throwable);
                    boolean retryable = cause instanceof ConnectException || cause instanceof HttpTimeoutException;
                    if (!retryable) return CompletableFuture.failedFuture(cause);
                    return CompletableFuture.failedFuture(new IOException(provider + " connection failed: " + cause.getMessage(), cause));
                });
    }

    private static boolean isBadGeminiInteraction(Throwable cause) {
        String text = String.valueOf(cause.getMessage());
        return text.contains("API 400") || text.contains("API 404") || text.contains("previous_interaction");
    }

    private static URI uriForProvider(String provider) { return provider.equals("openrouter") ? OPENROUTER_URI : GROQ_URI; }
    private static String keyForProvider(String provider) { return provider.equals("openrouter") ? AiClientConfig.getOpenRouterApiKey() : AiClientConfig.getGroqApiKey(); }
    private static String modelForProvider(String provider) { return provider.equals("openrouter") ? OPENROUTER_MODEL : GROQ_MODEL; }
    private static String providerName(String provider) { return switch (provider) { case "gemini" -> "Gemini"; case "groq" -> "Groq"; case "openrouter" -> "OpenRouter"; default -> "DeepSeek"; }; }
    private static JsonObject chatMessage(String role, String content) { JsonObject object = new JsonObject(); object.addProperty("role", role); object.addProperty("content", content); return object; }
    private static JsonObject firstChoice(JsonObject root) { if (!root.has("choices") || !root.get("choices").isJsonArray() || root.getAsJsonArray("choices").isEmpty()) return null; return root.getAsJsonArray("choices").get(0).getAsJsonObject(); }
    private static String string(JsonObject object, String name, String fallback) { return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback; }
    private static int requiredInt(JsonObject object, String name) { if (!object.has(name)) throw new IllegalArgumentException("Не указан аргумент: " + name); return object.get(name).getAsInt(); }
    private static double requiredDouble(JsonObject object, String name) { if (!object.has(name)) throw new IllegalArgumentException("Не указан аргумент: " + name); return object.get(name).getAsDouble(); }
    private static int optionalInt(JsonObject object, String name, int fallback) { return object.has(name) ? object.get(name).getAsInt() : fallback; }
    private static String truncate(String text, int maxChars) { if (text == null || text.length() <= maxChars) return text; return text.substring(0, Math.max(0, maxChars - 3)) + "..."; }
    private static String compactJson(JsonElement element, int maxChars) { return truncate(element.toString(), maxChars); }
    private static double round(double value) { return Math.round(value * 10.0) / 10.0; }
    private static String extractError(JsonObject body) { if (!body.has("error")) return body.toString(); JsonElement error = body.get("error"); if (error.isJsonObject() && error.getAsJsonObject().has("message")) return error.getAsJsonObject().get("message").getAsString(); return error.toString(); }
    private static JsonObject errorResultFromThrowable(Throwable throwable) { return errorResult(throwable.getMessage() == null ? throwable.toString() : throwable.getMessage()); }
    private static Throwable rootCause(Throwable throwable) { Throwable current = throwable; int depth = 0; while (current.getCause() != null && current.getCause() != current && depth++ < 16) current = current.getCause(); return current; }
    private static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) { return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList()); }
}
