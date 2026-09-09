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
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class AiAgentService {
    private static final URI DEEPSEEK_URI = URI.create("https://api.deepseek.com/responses");
    private static final URI GROQ_URI = URI.create("https://api.groq.com/openai/v1/chat/completions");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(ProxySelector.getDefault())
            .build();
    private static final int NETWORK_RETRIES = 2;

    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String GROQ_MODEL = "openai/gpt-oss-20b";

    private static final String INSTRUCTIONS = """
            You are the AI agent inside a Minecraft 1.21.1 world.
            You can inspect and control the world only through the provided Minecraft tools.
            Never claim an action succeeded unless the tool result confirms it.
            The player speaking to you is the owner of the current world.
            For building requests, first inspect the player's position and nearby area, then plan a compact layout and use place_blocks.
            For item requests, use give_item directly instead of sending chat messages.
            Do not use Minecraft chat to communicate actions. Your final response is shown in the AI panel.
            Do not perform destructive actions unless the user explicitly requested them.
            Keep tool calls targeted and avoid unnecessary world changes.
            """.strip();

    private static final List<JsonObject> TOOLS = createTools();
    private static final JsonArray DEEPSEEK_CONVERSATION = new JsonArray();
    private static final JsonArray GROQ_CONVERSATION = new JsonArray();
    private static String historyProvider = "";

    private AiAgentService() {}

    public static CompletableFuture<String> chat(String message) {
        String provider = AiClientConfig.getProvider();
        if (!AiClientConfig.hasApiKey()) {
            return CompletableFuture.completedFuture("Сначала укажи " + providerName(provider) + " API key в поле сверху.");
        }

        resetHistoryIfProviderChanged(provider);

        if (provider.equals("groq")) {
            synchronized (GROQ_CONVERSATION) {
                GROQ_CONVERSATION.add(chatMessage("user", message));
            }
            return groqRequest(buildGroqPayload()).thenCompose(response -> processGroqResponse(response, 0));
        }

        synchronized (DEEPSEEK_CONVERSATION) {
            DEEPSEEK_CONVERSATION.add(deepSeekMessage(message));
        }
        return deepSeekRequest(buildDeepSeekPayload()).thenCompose(response -> processDeepSeekResponse(response, 0));
    }

    public static CompletableFuture<Boolean> testConnection() {
        String provider = AiClientConfig.getProvider();
        if (!AiClientConfig.hasApiKey()) return CompletableFuture.completedFuture(false);

        if (provider.equals("groq")) {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", GROQ_MODEL);
            JsonArray messages = new JsonArray();
            messages.add(chatMessage("system", "Reply with exactly: OK"));
            messages.add(chatMessage("user", "Connection test"));
            payload.add("messages", messages);
            payload.addProperty("temperature", 0);

            return groqRequest(payload).thenApply(root -> {
                JsonObject choice = firstChoice(root);
                if (choice == null || !choice.has("message")) return false;
                JsonObject assistant = choice.getAsJsonObject("message");
                return assistant.has("content") && !assistant.get("content").isJsonNull()
                        && !assistant.get("content").getAsString().isBlank();
            });
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", DEEPSEEK_MODEL);
        payload.addProperty("instructions", "Reply with exactly: OK");
        payload.addProperty("input", "Connection test");
        return deepSeekRequest(payload).thenApply(root ->
                root.has("output_text") && !root.get("output_text").getAsString().isBlank());
    }

    private static void resetHistoryIfProviderChanged(String provider) {
        synchronized (DEEPSEEK_CONVERSATION) {
            synchronized (GROQ_CONVERSATION) {
                if (!provider.equals(historyProvider)) {
                    while (!DEEPSEEK_CONVERSATION.isEmpty()) DEEPSEEK_CONVERSATION.remove(DEEPSEEK_CONVERSATION.size() - 1);
                    while (!GROQ_CONVERSATION.isEmpty()) GROQ_CONVERSATION.remove(GROQ_CONVERSATION.size() - 1);
                    historyProvider = provider;
                }
            }
        }
    }

    private static JsonObject buildDeepSeekPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", DEEPSEEK_MODEL);
        payload.addProperty("instructions", INSTRUCTIONS);
        synchronized (DEEPSEEK_CONVERSATION) {
            payload.add("input", DEEPSEEK_CONVERSATION.deepCopy());
        }
        payload.add("tools", deepSeekToolsArray());
        payload.addProperty("tool_choice", "auto");
        return payload;
    }

    private static JsonObject buildGroqPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", GROQ_MODEL);
        JsonArray messages = new JsonArray();
        messages.add(chatMessage("system", INSTRUCTIONS));
        synchronized (GROQ_CONVERSATION) {
            for (JsonElement element : GROQ_CONVERSATION) {
                messages.add(element.deepCopy());
            }
        }
        payload.add("messages", messages);
        payload.add("tools", groqToolsArray());
        payload.addProperty("tool_choice", "auto");
        return payload;
    }

    private static CompletableFuture<String> processDeepSeekResponse(JsonObject response, int depth) {
        if (depth >= 24) {
            return CompletableFuture.completedFuture("ИИ достиг лимита действий для одной задачи.");
        }

        JsonArray output = response.has("output") && response.get("output").isJsonArray()
                ? response.getAsJsonArray("output")
                : new JsonArray();

        if (output.size() > 0) {
            synchronized (DEEPSEEK_CONVERSATION) {
                for (JsonElement element : output) {
                    DEEPSEEK_CONVERSATION.add(element.deepCopy());
                }
            }
        }

        List<JsonObject> calls = new ArrayList<>();
        for (JsonElement element : output) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if ("function_call".equals(string(item, "type", ""))) {
                calls.add(item);
            }
        }

        if (calls.isEmpty()) {
            String text = response.has("output_text")
                    ? response.get("output_text").getAsString()
                    : "ИИ не вернул текстовый ответ.";
            return CompletableFuture.completedFuture(text);
        }

        List<CompletableFuture<JsonObject>> futures = new ArrayList<>();
        for (JsonObject call : calls) futures.add(executeToolAsync(call));

        return sequence(futures).thenCompose(results -> {
            synchronized (DEEPSEEK_CONVERSATION) {
                for (int i = 0; i < calls.size(); i++) {
                    JsonObject call = calls.get(i);
                    JsonObject result = new JsonObject();
                    result.addProperty("type", "function_call_output");
                    result.addProperty("call_id", string(call, "call_id", string(call, "id", "")));
                    result.addProperty("output", results.get(i).toString());
                    DEEPSEEK_CONVERSATION.add(result);
                }
            }
            return deepSeekRequest(buildDeepSeekPayload())
                    .thenCompose(next -> processDeepSeekResponse(next, depth + 1));
        });
    }

    private static CompletableFuture<String> processGroqResponse(JsonObject response, int depth) {
        if (depth >= 24) {
            return CompletableFuture.completedFuture("ИИ достиг лимита действий для одной задачи.");
        }

        JsonObject choice = firstChoice(response);
        if (choice == null || !choice.has("message")) {
            return CompletableFuture.completedFuture("Groq не вернул ожидаемый ответ.");
        }

        JsonObject assistant = choice.getAsJsonObject("message");
        JsonArray toolCalls = assistant.has("tool_calls") && assistant.get("tool_calls").isJsonArray()
                ? assistant.getAsJsonArray("tool_calls")
                : new JsonArray();

        synchronized (GROQ_CONVERSATION) {
            GROQ_CONVERSATION.add(assistant.deepCopy());
        }

        if (toolCalls.isEmpty()) {
            String text = assistant.has("content") && !assistant.get("content").isJsonNull()
                    ? assistant.get("content").getAsString()
                    : "Groq не вернул текстовый ответ.";
            return CompletableFuture.completedFuture(text);
        }

        List<CompletableFuture<JsonObject>> futures = new ArrayList<>();
        List<JsonObject> validCalls = new ArrayList<>();
        for (JsonElement element : toolCalls) {
            if (!element.isJsonObject()) continue;
            JsonObject call = element.getAsJsonObject();
            validCalls.add(call);
            futures.add(executeGroqToolAsync(call));
        }

        return sequence(futures).thenCompose(results -> {
            synchronized (GROQ_CONVERSATION) {
                for (int i = 0; i < validCalls.size(); i++) {
                    JsonObject call = validCalls.get(i);
                    JsonObject result = new JsonObject();
                    result.addProperty("role", "tool");
                    result.addProperty("tool_call_id", string(call, "id", ""));
                    result.addProperty("content", results.get(i).toString());
                    GROQ_CONVERSATION.add(result);
                }
            }
            return groqRequest(buildGroqPayload())
                    .thenCompose(next -> processGroqResponse(next, depth + 1));
        });
    }

    private static CompletableFuture<JsonObject> executeGroqToolAsync(JsonObject call) {
        JsonObject function = call.has("function") && call.get("function").isJsonObject()
                ? call.getAsJsonObject("function")
                : call;
        String name = string(function, "name", "");
        JsonObject arguments;
        try {
            arguments = JsonParser.parseString(string(function, "arguments", "{}")).getAsJsonObject();
        } catch (RuntimeException exception) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", "Invalid tool arguments: " + exception.getMessage());
            return CompletableFuture.completedFuture(error);
        }
        return executeToolAsync(name, arguments);
    }

    private static CompletableFuture<JsonObject> executeToolAsync(JsonObject call) {
        String name = string(call, "name", "");
        JsonObject arguments;
        try {
            JsonElement raw = call.get("arguments");
            if (raw != null && raw.isJsonObject()) arguments = raw.getAsJsonObject();
            else arguments = JsonParser.parseString(string(call, "arguments", "{}")).getAsJsonObject();
        } catch (RuntimeException exception) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", "Invalid tool arguments: " + exception.getMessage());
            return CompletableFuture.completedFuture(error);
        }
        return executeToolAsync(name, arguments);
    }

    private static CompletableFuture<JsonObject> executeToolAsync(String name, JsonObject arguments) {
        MinecraftClient client = MinecraftClient.getInstance();
        MinecraftServer server = client.getServer();
        if (server == null) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", "No integrated Minecraft server is running. The current version supports singleplayer/local worlds.");
            return CompletableFuture.completedFuture(error);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(executeTool(server, name, arguments));
            } catch (Exception exception) {
                JsonObject error = new JsonObject();
                error.addProperty("ok", false);
                error.addProperty("error", exception.getMessage() == null ? exception.toString() : exception.getMessage());
                future.complete(error);
            }
        });
        return future;
    }

    private static JsonObject executeTool(MinecraftServer server, String name, JsonObject args) {
        return switch (name) {
            case "get_player_state" -> getPlayerState(server, args);
            case "get_block" -> getBlock(server, args);
            case "scan_area" -> scanArea(server, args);
            case "place_blocks" -> placeBlocks(server, args);
            case "break_block" -> breakBlock(server, args);
            case "give_item" -> giveItem(server, args);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    private static JsonObject getPlayerState(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        JsonObject data = new JsonObject();
        data.addProperty("name", player.getName().getString());
        data.addProperty("x", player.getX());
        data.addProperty("y", player.getY());
        data.addProperty("z", player.getZ());
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
        data.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
        data.addProperty("solid", !state.isAir() && state.isSolidBlock(world, pos));
        return data;
    }

    private static JsonObject scanArea(MinecraftServer server, JsonObject args) {
        ServerWorld world = getWorld(server);
        int cx = requiredInt(args, "x");
        int cy = requiredInt(args, "y");
        int cz = requiredInt(args, "z");
        int radius = Math.min(8, Math.max(1, requiredInt(args, "radius")));

        JsonObject counts = new JsonObject();
        int total = 0;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = cy - radius; y <= cy + radius; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    String id = Registries.BLOCK.getId(world.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
                    counts.addProperty(id, counts.has(id) ? counts.get(id).getAsInt() + 1 : 1);
                    total++;
                }
            }
        }

        JsonObject data = new JsonObject();
        data.addProperty("total_blocks", total);
        data.add("block_counts", counts);
        return data;
    }

    private static JsonObject placeBlocks(MinecraftServer server, JsonObject args) {
        ServerWorld world = getWorld(server);
        JsonArray blocks = args.getAsJsonArray("blocks");
        if (blocks.size() > 512) throw new IllegalArgumentException("place_blocks is limited to 512 blocks per call");

        int changed = 0;
        for (JsonElement element : blocks) {
            JsonObject entry = element.getAsJsonObject();
            BlockPos pos = new BlockPos(
                    requiredInt(entry, "x"),
                    requiredInt(entry, "y"),
                    requiredInt(entry, "z")
            );
            Identifier id = Identifier.tryParse(string(entry, "block", ""));
            if (id == null || !Registries.BLOCK.containsId(id)) {
                throw new IllegalArgumentException("Unknown block: " + id);
            }
            Block block = Registries.BLOCK.get(id);
            if (world.setBlockState(pos, block.getDefaultState())) changed++;
        }

        JsonObject data = new JsonObject();
        data.addProperty("requested", blocks.size());
        data.addProperty("changed", changed);
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
        return server.getOverworld();
    }

    private static JsonArray deepSeekToolsArray() {
        JsonArray array = new JsonArray();
        for (JsonObject tool : TOOLS) array.add(tool.deepCopy());
        return array;
    }

    private static JsonArray groqToolsArray() {
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
        JsonObject x = new JsonObject();
        x.addProperty("type", "integer");
        JsonObject y = new JsonObject();
        y.addProperty("type", "integer");
        JsonObject z = new JsonObject();
        z.addProperty("type", "integer");
        JsonObject block = new JsonObject();
        block.addProperty("type", "string");
        block.addProperty("description", "Minecraft block ID.");
        itemProperties.add("x", x);
        itemProperties.add("y", y);
        itemProperties.add("z", z);
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

    private static List<JsonObject> createTools() {
        List<JsonObject> tools = new ArrayList<>();
        tools.add(function("get_player_state", "Read the current position and state of an online player.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player.", false)
        )));
        tools.add(function("get_block", "Read the block at an exact overworld coordinate.", objectProperties(
                intProperty("x"), intProperty("y"), intProperty("z")
        )));
        tools.add(function("scan_area", "Count block types inside a small cube around a coordinate.", objectProperties(
                intProperty("x"), intProperty("y"), intProperty("z"), intProperty("radius")
        )));
        tools.add(function("place_blocks", "Place many blocks at exact overworld coordinates. Maximum 512 blocks per call.", objectProperties(
                arrayProperty("blocks", "Block placements. Each entry contains x, y, z and a Minecraft block ID such as minecraft:oak_planks.")
        )));
        tools.add(function("break_block", "Break the block at an exact overworld coordinate.", objectProperties(
                intProperty("x"), intProperty("y"), intProperty("z")
        )));
        tools.add(function("give_item", "Give a Minecraft item directly to a player without using chat.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player.", false),
                property("item", "string", "Minecraft item ID such as minecraft:diamond_sword.", true),
                property("count", "integer", "Amount from 1 to 64. Defaults to 1.", false)
        )));
        return tools;
    }

    private static CompletableFuture<JsonObject> deepSeekRequest(JsonObject payload) {
        return request(DEEPSEEK_URI, AiClientConfig.getDeepSeekApiKey(), payload, "DeepSeek");
    }

    private static CompletableFuture<JsonObject> groqRequest(JsonObject payload) {
        return request(GROQ_URI, AiClientConfig.getGroqApiKey(), payload, "Groq");
    }

    private static CompletableFuture<JsonObject> request(URI uri, String apiKey, JsonObject payload, String providerName) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        return sendWithRetry(request, providerName, 0)
                .thenCompose(response -> {
                    try {
                        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            return CompletableFuture.completedFuture(body);
                        }
                        return CompletableFuture.failedFuture(new IOException(
                                providerName + " API " + response.statusCode() + ": " + extractError(body)
                        ));
                    } catch (RuntimeException exception) {
                        return CompletableFuture.failedFuture(new IOException(
                                "Неверный ответ " + providerName + " API: " + exception.getMessage()
                        ));
                    }
                });
    }

    private static CompletableFuture<HttpResponse<String>> sendWithRetry(
            HttpRequest request, String providerName, int attempt) {
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    if (throwable == null) {
                        return CompletableFuture.completedFuture(response);
                    }

                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    boolean retryable = cause instanceof ConnectException
                            || cause instanceof HttpTimeoutException;

                    if (!retryable || attempt >= NETWORK_RETRIES) {
                        return CompletableFuture.<HttpResponse<String>>failedFuture(
                                new IOException(providerName + " connection failed: " + cause.getMessage(), cause));
                    }

                    long delay = 2L * (attempt + 1);
                    return CompletableFuture.supplyAsync(
                            () -> null,
                            CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS))
                            .thenCompose(ignored -> sendWithRetry(request, providerName, attempt + 1));
                })
                .thenCompose(future -> future);
    }

    private static JsonObject deepSeekMessage(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "message");
        message.addProperty("role", "user");
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject chatMessage(String role, String content) {
        JsonObject message = new JsonObject();
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
        return provider.equals("groq") ? "Groq" : "DeepSeek";
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

    private static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        return all.thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }
}
