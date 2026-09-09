package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AiAgentService {
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final String MODEL = "gpt-5.6";
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

    private static String previousResponseId;

    private AiAgentService() {}

    public static CompletableFuture<String> chat(String message) {
        if (!AiClientConfig.hasApiKey()) {
            return CompletableFuture.completedFuture("Сначала укажи OpenAI API key в поле сверху.");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", MODEL);
        payload.addProperty("instructions", INSTRUCTIONS);
        payload.add("tools", toolsArray());
        payload.add("input", textInput(message));
        if (previousResponseId != null && !previousResponseId.isBlank()) {
            payload.addProperty("previous_response_id", previousResponseId);
        }

        return request(payload).thenCompose(response -> processResponse(response, 0));
    }

    public static CompletableFuture<Boolean> testConnection() {
        if (!AiClientConfig.hasApiKey()) return CompletableFuture.completedFuture(false);
        JsonObject payload = new JsonObject();
        payload.addProperty("model", MODEL);
        payload.addProperty("instructions", "Reply with exactly: OK");
        payload.addProperty("input", "Connection test");
        return request(payload)
                .thenApply(root -> root.has("output_text"));
    }

    private static CompletableFuture<String> processResponse(JsonObject response, int depth) {
        if (depth >= 24) {
            return CompletableFuture.completedFuture("ИИ достиг лимита действий для одной задачи.");
        }

        if (response.has("id")) {
            previousResponseId = response.get("id").getAsString();
        }

        JsonArray output = response.has("output") && response.get("output").isJsonArray()
                ? response.getAsJsonArray("output")
                : new JsonArray();

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
        for (JsonObject call : calls) {
            futures.add(executeToolAsync(call));
        }

        return sequence(futures).thenCompose(results -> {
            JsonArray toolOutputs = new JsonArray();
            for (int i = 0; i < calls.size(); i++) {
                JsonObject call = calls.get(i);
                JsonObject outputObject = new JsonObject();
                outputObject.addProperty("type", "function_call_output");
                outputObject.addProperty("call_id", string(call, "call_id", ""));
                outputObject.addProperty("output", results.get(i).toString());
                toolOutputs.add(outputObject);
            }

            JsonObject payload = new JsonObject();
            payload.addProperty("model", MODEL);
            payload.addProperty("instructions", INSTRUCTIONS);
            payload.add("tools", toolsArray());
            payload.addProperty("previous_response_id", previousResponseId);
            payload.add("input", toolOutputs);
            return request(payload).thenCompose(next -> processResponse(next, depth + 1));
        });
    }

    private static CompletableFuture<JsonObject> executeToolAsync(JsonObject call) {
        String name = string(call, "name", "");
        JsonObject arguments;
        try {
            arguments = JsonParser.parseString(string(call, "arguments", "{}"))
                    .getAsJsonObject();
        } catch (RuntimeException exception) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", "Invalid tool arguments: " + exception.getMessage());
            return CompletableFuture.completedFuture(error);
        }

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

    private static JsonArray toolsArray() {
        JsonArray array = new JsonArray();
        for (JsonObject tool : TOOLS) array.add(tool.deepCopy());
        return array;
    }

    private static JsonArray textInput(String message) {
        JsonArray input = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("role", "user");
        item.addProperty("content", message);
        input.add(item);
        return input;
    }

    private static CompletableFuture<JsonObject> request(JsonObject payload) {
        HttpRequest request = HttpRequest.newBuilder(RESPONSES_URI)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + AiClientConfig.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return CompletableFuture.completedFuture(JsonParser.parseString(response.body()).getAsJsonObject());
                    }
                    String body = response.body();
                    String detail = body.length() > 1000 ? body.substring(0, 1000) : body;
                    return CompletableFuture.failedFuture(new IOException("OpenAI API " + response.statusCode() + ": " + detail));
                });
    }

    private static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        return all.thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    private static List<JsonObject> createTools() {
        List<JsonObject> tools = new ArrayList<>();
        tools.add(function("get_player_state", "Read the current position and state of an online player.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player.")
        ), List.of()));
        tools.add(function("get_block", "Read the block at an exact overworld coordinate.", objectProperties(
                intProperty("x"), intProperty("y"), intProperty("z")
        ), List.of("x", "y", "z")));
        tools.add(function("scan_area", "Count block types inside a small cube around a coordinate.", objectProperties(
                intProperty("x"), intProperty("y"), intProperty("z"), intProperty("radius")
        ), List.of("x", "y", "z", "radius")));
        tools.add(function("place_blocks", "Place many blocks at exact overworld coordinates. Maximum 512 blocks per call.", objectProperties(
                arrayProperty("blocks", "Block placements. Each entry contains x, y, z and a Minecraft block ID such as minecraft:oak_planks.")
        ), List.of("blocks")));
        tools.add(function("break_block", "Break the block at an exact overworld coordinate.", objectProperties(
                intProperty("x"), intProperty("y"), intProperty("z")
        ), List.of("x", "y", "z")));
        tools.add(function("give_item", "Give a Minecraft item directly to a player without using chat.", objectProperties(
                property("player", "string", "Player name. Empty means the first online player."),
                property("item", "string", "Minecraft item ID such as minecraft:diamond_sword."),
                intProperty("count")
        ), List.of("item")));
        return tools;
    }

    private static JsonObject function(String name, String description, JsonObject properties, List<String> required) {
        JsonObject function = new JsonObject();
        function.addProperty("type", "function");
        function.addProperty("name", name);
        function.addProperty("description", description);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        JsonArray requiredArray = new JsonArray();
        for (String item : required) requiredArray.add(item);
        parameters.add("required", requiredArray);
        parameters.addProperty("additionalProperties", false);
        function.add("parameters", parameters);
        function.addProperty("strict", true);
        return function;
    }

    private static JsonObject objectProperties(JsonObject... properties) {
        JsonObject object = new JsonObject();
        for (JsonObject property : properties) object.add(property.get("name").getAsString(), property.get("schema"));
        return object;
    }

    private static JsonObject property(String name, String type, String description) {
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        schema.addProperty("description", description);
        result.add("schema", schema);
        return result;
    }

    private static JsonObject intProperty(String name) {
        return property(name, "integer", "Integer value for " + name + ".");
    }

    private static JsonObject arrayProperty(String name, String description) {
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.addProperty("description", description);
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        JsonObject itemProperties = new JsonObject();
        itemProperties.add("x", integerSchema());
        itemProperties.add("y", integerSchema());
        itemProperties.add("z", integerSchema());
        itemProperties.add("block", stringSchema());
        item.add("properties", itemProperties);
        JsonArray required = new JsonArray();
        required.add("x");
        required.add("y");
        required.add("z");
        required.add("block");
        item.add("required", required);
        item.addProperty("additionalProperties", false);
        schema.add("items", item);
        result.add("schema", schema);
        return result;
    }

    private static JsonObject integerSchema() {
        JsonObject object = new JsonObject();
        object.addProperty("type", "integer");
        return object;
    }

    private static JsonObject stringSchema() {
        JsonObject object = new JsonObject();
        object.addProperty("type", "string");
        return object;
    }

    private static String string(JsonObject object, String name, String fallback) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static int requiredInt(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) throw new IllegalArgumentException("Missing integer: " + name);
        return element.getAsInt();
    }

    private static int optionalInt(JsonObject object, String name, int fallback) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
    }
}
