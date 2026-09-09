package com.mortylovelly.minecraftai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class AiBridgeServer {
    private static final int PORT = 8765;
    private static final Gson GSON = new Gson();
    private static final AtomicReference<MinecraftServer> SERVER = new AtomicReference<>();
    private static volatile boolean running;
    private static volatile boolean connected;
    private static ServerSocket serverSocket;

    private AiBridgeServer() {}

    public static void startLifecycleHooks() {
        ServerTickEvents.END_SERVER_TICK.register(server -> SERVER.set(server));
    }

    public static void onServerStarted(MinecraftServer server) {
        SERVER.set(server);
        startSocketThread();
    }

    public static void onServerStopping(MinecraftServer server) {
        running = false;
        connected = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        SERVER.set(null);
    }

    public static boolean isConnected() {
        return connected;
    }

    private static void startSocketThread() {
        if (running) {
            return;
        }
        running = true;

        Thread thread = new Thread(() -> {
            try (ServerSocket socket = new ServerSocket(PORT, 1, InetAddress.getLoopbackAddress())) {
                serverSocket = socket;
                while (running) {
                    try {
                        Socket client = socket.accept();
                        handleClient(client);
                    } catch (IOException exception) {
                        if (running) {
                            exception.printStackTrace();
                        }
                    }
                }
            } catch (IOException exception) {
                if (running) {
                    exception.printStackTrace();
                }
            } finally {
                connected = false;
            }
        }, "Minecraft-AI-Agent-Bridge");

        thread.setDaemon(true);
        thread.start();
    }

    private static void handleClient(Socket socket) {
        connected = true;
        try (Socket ignoredSocket = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            String line;
            while (running && (line = reader.readLine()) != null) {
                JsonObject request;
                try {
                    JsonElement parsed = JsonParser.parseString(line);
                    if (!parsed.isJsonObject()) {
                        writer.println(error(null, "Request must be a JSON object").toString());
                        continue;
                    }
                    request = parsed.getAsJsonObject();
                } catch (RuntimeException exception) {
                    writer.println(error(null, "Invalid JSON: " + exception.getMessage()).toString());
                    continue;
                }

                String requestId = string(request, "id", "");
                String tool = string(request, "tool", "");
                JsonObject arguments = object(request, "args");

                JsonObject response = executeTool(requestId, tool, arguments).join();
                writer.println(response.toString());
            }
        } catch (IOException ignored) {
        } finally {
            connected = false;
        }
    }

    private static CompletableFuture<JsonObject> executeTool(String requestId, String tool, JsonObject args) {
        MinecraftServer server = SERVER.get();
        if (server == null) {
            return CompletableFuture.completedFuture(error(requestId, "Minecraft server is not running"));
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                JsonObject data = switch (tool) {
                    case "get_player_state" -> getPlayerState(server, args);
                    case "get_block" -> getBlock(server, args);
                    case "scan_area" -> scanArea(server, args);
                    case "place_block" -> placeBlock(server, args);
                    case "break_block" -> breakBlock(server, args);
                    case "move_to" -> moveTo(server, args);
                    case "send_chat" -> sendChat(server, args);
                    default -> throw new IllegalArgumentException("Unknown tool: " + tool);
                };
                JsonObject response = new JsonObject();
                response.addProperty("id", requestId);
                response.addProperty("ok", true);
                response.add("data", data);
                future.complete(response);
            } catch (Exception exception) {
                future.complete(error(requestId, exception.getMessage() == null ? exception.toString() : exception.getMessage()));
            }
        });

        return future;
    }

    private static JsonObject getPlayerState(MinecraftServer server, JsonObject args) {
        String requestedName = string(args, "player", "");
        ServerPlayerEntity player = requestedName.isBlank()
                ? server.getPlayerManager().getPlayerList().stream().findFirst().orElse(null)
                : server.getPlayerManager().getPlayer(requestedName);

        if (player == null) {
            throw new IllegalArgumentException("No matching player is online");
        }

        JsonObject data = new JsonObject();
        data.addProperty("name", player.getGameProfile().name());
        data.addProperty("uuid", player.getUuidAsString());
        data.addProperty("x", player.getX());
        data.addProperty("y", player.getY());
        data.addProperty("z", player.getZ());
        data.addProperty("yaw", player.getYaw());
        data.addProperty("pitch", player.getPitch());
        data.addProperty("health", player.getHealth());
        data.addProperty("food", player.getHungerManager().getFoodLevel());
        data.addProperty("dimension", player.getWorld().getRegistryKey().getValue().toString());
        data.addProperty("game_mode", player.interactionManager.getGameMode().getName());
        return data;
    }

    private static JsonObject getBlock(MinecraftServer server, JsonObject args) {
        ServerWorld world = getOverworld(server);
        BlockPos pos = new BlockPos(
                requiredInt(args, "x"),
                requiredInt(args, "y"),
                requiredInt(args, "z")
        );

        var state = world.getBlockState(pos);
        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        data.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
        data.addProperty("solid", !state.isAir() && state.isSolidBlock(world, pos));
        data.addProperty("hardness", state.getHardness(world, pos));
        return data;
    }

    private static JsonObject scanArea(MinecraftServer server, JsonObject args) {
        ServerWorld world = getOverworld(server);
        int cx = requiredInt(args, "x");
        int cy = requiredInt(args, "y");
        int cz = requiredInt(args, "z");
        int radius = Math.min(8, Math.max(1, optionalInt(args, "radius", 4)));

        JsonObject counts = new JsonObject();
        int total = 0;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = cy - radius; y <= cy + radius; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    String id = Registries.BLOCK.getId(world.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
                    int previous = counts.has(id) ? counts.get(id).getAsInt() : 0;
                    counts.addProperty(id, previous + 1);
                    total++;
                }
            }
        }

        JsonObject data = new JsonObject();
        data.addProperty("center_x", cx);
        data.addProperty("center_y", cy);
        data.addProperty("center_z", cz);
        data.addProperty("radius", radius);
        data.addProperty("total_blocks", total);
        data.add("block_counts", counts);
        return data;
    }

    private static JsonObject placeBlock(MinecraftServer server, JsonObject args) {
        ServerWorld world = getOverworld(server);
        BlockPos pos = new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
        String blockId = string(args, "block", "");
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            throw new IllegalArgumentException("Unknown block: " + blockId);
        }

        Block block = Registries.BLOCK.get(id);
        boolean changed = world.setBlockState(pos, block.getDefaultState());

        JsonObject data = new JsonObject();
        data.addProperty("changed", changed);
        data.addProperty("block", blockId);
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        return data;
    }

    private static JsonObject breakBlock(MinecraftServer server, JsonObject args) {
        ServerWorld world = getOverworld(server);
        BlockPos pos = new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
        String before = Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString();
        boolean changed = world.breakBlock(pos, false);

        JsonObject data = new JsonObject();
        data.addProperty("changed", changed);
        data.addProperty("previous_block", before);
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        return data;
    }

    private static JsonObject moveTo(MinecraftServer server, JsonObject args) {
        String playerName = string(args, "player", "");
        ServerPlayerEntity player = playerName.isBlank()
                ? server.getPlayerManager().getPlayerList().stream().findFirst().orElse(null)
                : server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            throw new IllegalArgumentException("No matching player is online");
        }

        double x = requiredDouble(args, "x");
        double y = requiredDouble(args, "y");
        double z = requiredDouble(args, "z");
        float yaw = (float) optionalDouble(args, "yaw", player.getYaw());
        float pitch = (float) optionalDouble(args, "pitch", player.getPitch());
        player.teleport(player.getServerWorld(), x, y, z, yaw, pitch);

        JsonObject data = new JsonObject();
        data.addProperty("moved", true);
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        return data;
    }

    private static JsonObject sendChat(MinecraftServer server, JsonObject args) {
        String message = string(args, "message", "");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message cannot be empty");
        }
        server.getPlayerManager().broadcast(net.minecraft.text.Text.literal("[AI] " + message), false);
        JsonObject data = new JsonObject();
        data.addProperty("sent", true);
        data.addProperty("message", message);
        return data;
    }

    private static ServerWorld getOverworld(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        if (world == null) {
            throw new IllegalStateException("Overworld is unavailable");
        }
        return world;
    }

    private static JsonObject error(String requestId, String message) {
        JsonObject response = new JsonObject();
        if (requestId == null) {
            response.add("id", JsonNull.INSTANCE);
        } else {
            response.addProperty("id", requestId);
        }
        response.addProperty("ok", false);
        response.addProperty("error", message == null ? "Unknown error" : message);
        return response;
    }

    private static JsonObject object(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static int requiredInt(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing integer argument: " + name);
        }
        return value.getAsInt();
    }

    private static int optionalInt(JsonObject object, String name, int fallback) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static double requiredDouble(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing number argument: " + name);
        }
        return value.getAsDouble();
    }

    private static double optionalDouble(JsonObject object, String name, double fallback) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsDouble() : fallback;
    }
}
