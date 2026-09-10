package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.registry.Registries;

/**
 * High-level procedural builder for the AI agent.
 * The model describes a structure with a small number of primitives; Minecraft places
 * the resulting blocks locally in one server-side pass instead of requiring one tool
 * call per block.
 *
 * Local coordinates:
 *   +X = player right
 *   +Y = up
 *   +Z = player forward
 */
public final class AiBuildTool {
    private static final int MAX_PARTS = 40;
    private static final int MAX_BLOCKS = 60000;
    private static final int MAX_HORIZONTAL = 96;
    private static final int MAX_VERTICAL = 64;

    private AiBuildTool() {}

    public static JsonObject toolDefinition() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "function");
        root.addProperty("name", "build_structure");
        root.addProperty("description", "Build a custom Minecraft structure from compact box, line, cylinder and clear primitives. The AI designs the structure; Minecraft applies all blocks in one fast server-side pass. Local coordinates: +X right, +Y up, +Z forward from the player.");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("player", simpleProperty("string", "Player name; empty means current player."));
        properties.add("anchor_forward", integerProperty("Blocks in front of the player used as the local origin; default 4."));
        properties.add("replace", booleanProperty("Default replace mode for placement parts; false only places into air."));

        JsonObject parts = new JsonObject();
        parts.addProperty("type", "array");
        parts.addProperty("description", "Compact building primitives. Use roughly 4-20 parts for a normal structure rather than many tiny operations.");
        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        JsonObject itemProperties = new JsonObject();
        itemProperties.add("kind", enumProperty(new String[]{"box", "line", "cylinder", "clear"}, "Primitive type."));
        itemProperties.add("block", simpleProperty("string", "Block ID, e.g. minecraft:stone or minecraft:oak_planks."));
        itemProperties.add("x1", integerProperty("Box/line start X."));
        itemProperties.add("y1", integerProperty("Box/line start Y."));
        itemProperties.add("z1", integerProperty("Box/line start Z."));
        itemProperties.add("x2", integerProperty("Box/line end X."));
        itemProperties.add("y2", integerProperty("Box/line end Y."));
        itemProperties.add("z2", integerProperty("Box/line end Z."));
        itemProperties.add("hollow", booleanProperty("For box/cylinder: keep only the outer shell."));
        itemProperties.add("thickness", integerProperty("For line: radius around the line, normally 0 or 1."));
        itemProperties.add("cx", integerProperty("Cylinder center X."));
        itemProperties.add("cy", integerProperty("Cylinder base Y."));
        itemProperties.add("cz", integerProperty("Cylinder center Z."));
        itemProperties.add("radius", integerProperty("Cylinder radius, up to 24."));
        itemProperties.add("height", integerProperty("Cylinder height, up to 48."));
        itemProperties.add("replace", booleanProperty("Override global replace mode for this primitive."));
        items.add("properties", itemProperties);
        parts.add("items", items);
        properties.add("parts", parts);

        parameters.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("parts");
        parameters.add("required", required);
        root.add("parameters", parameters);
        return root;
    }

    public static JsonObject execute(MinecraftServer server, JsonObject args) {
        try {
            ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
            ServerWorld world = player.getServerWorld();
            JsonElement rawParts = args.get("parts");
            if (rawParts == null || !rawParts.isJsonArray()) return error("Не указан массив parts.");
            JsonArray parts = rawParts.getAsJsonArray();
            if (parts.isEmpty()) return error("Строительный план пустой.");
            if (parts.size() > MAX_PARTS) return error("Слишком много строительных частей: максимум " + MAX_PARTS + ".");

            int anchorForward = clamp(optionalInt(args, "anchor_forward", 4), 0, 16);
            boolean globalReplace = booleanValue(args, "replace", false);
            Direction facing = player.getHorizontalFacing();
            BlockPos playerPos = player.getBlockPos();
            BlockPos origin = playerPos.add(facing.getOffsetX() * anchorForward, 0, facing.getOffsetZ() * anchorForward);

            int changed = 0;
            for (JsonElement element : parts) {
                if (!element.isJsonObject()) return error("Каждая часть должна быть объектом.");
                JsonObject part = element.getAsJsonObject();
                String kind = string(part, "kind", "").toLowerCase();
                boolean replace = part.has("replace") ? booleanValue(part, "replace", globalReplace) : globalReplace;
                switch (kind) {
                    case "box" -> changed = drawBox(world, origin, facing, part, replace, changed, false);
                    case "clear" -> changed = drawBox(world, origin, facing, part, true, changed, true);
                    case "line" -> changed = drawLine(world, origin, facing, part, replace, changed);
                    case "cylinder" -> changed = drawCylinder(world, origin, facing, part, replace, changed);
                    default -> { return error("Неизвестный primitive kind: " + kind); }
                }
                if (changed > MAX_BLOCKS) return error("Постройка слишком большая: лимит " + MAX_BLOCKS + " изменённых блоков.");
            }

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("changed_blocks", changed);
            result.addProperty("parts", parts.size());
            result.addProperty("origin", origin.toShortString());
            result.addProperty("facing", facing.asString());
            result.addProperty("method", "procedural primitives, single server-side pass");
            return result;
        } catch (Exception exception) {
            return error(exception.getMessage() == null ? exception.toString() : exception.getMessage());
        }
    }

    private static int drawBox(ServerWorld world, BlockPos origin, Direction facing, JsonObject part, boolean replace, int changed, boolean clear) {
        int x1 = requiredInt(part, "x1");
        int y1 = requiredInt(part, "y1");
        int z1 = requiredInt(part, "z1");
        int x2 = requiredInt(part, "x2");
        int y2 = requiredInt(part, "y2");
        int z2 = requiredInt(part, "z2");
        validateCoordinate(x1, y1, z1);
        validateCoordinate(x2, y2, z2);
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        boolean hollow = booleanValue(part, "hollow", false);
        Block block = clear ? net.minecraft.block.Blocks.AIR : getBlock(string(part, "block", ""));

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (hollow && x != minX && x != maxX && y != minY && y != maxY && z != minZ && z != maxZ) continue;
                    changed += place(world, origin, facing, x, y, z, block, replace);
                }
            }
        }
        return changed;
    }

    private static int drawLine(ServerWorld world, BlockPos origin, Direction facing, JsonObject part, boolean replace, int changed) {
        int x1 = requiredInt(part, "x1");
        int y1 = requiredInt(part, "y1");
        int z1 = requiredInt(part, "z1");
        int x2 = requiredInt(part, "x2");
        int y2 = requiredInt(part, "y2");
        int z2 = requiredInt(part, "z2");
        validateCoordinate(x1, y1, z1);
        validateCoordinate(x2, y2, z2);
        Block block = getBlock(string(part, "block", ""));
        int thickness = clamp(optionalInt(part, "thickness", 0), 0, 3);
        int dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps == 0) return placeThickPoint(world, origin, facing, x1, y1, z1, thickness, block, replace, changed);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(x1 + dx * t);
            int y = (int) Math.round(y1 + dy * t);
            int z = (int) Math.round(z1 + dz * t);
            changed = placeThickPoint(world, origin, facing, x, y, z, thickness, block, replace, changed);
        }
        return changed;
    }

    private static int placeThickPoint(ServerWorld world, BlockPos origin, Direction facing, int x, int y, int z, int thickness, Block block, boolean replace, int changed) {
        for (int ox = -thickness; ox <= thickness; ox++) {
            for (int oy = -thickness; oy <= thickness; oy++) {
                for (int oz = -thickness; oz <= thickness; oz++) {
                    validateCoordinate(x + ox, y + oy, z + oz);
                    changed += place(world, origin, facing, x + ox, y + oy, z + oz, block, replace);
                }
            }
        }
        return changed;
    }

    private static int drawCylinder(ServerWorld world, BlockPos origin, Direction facing, JsonObject part, boolean replace, int changed) {
        int cx = requiredInt(part, "cx");
        int cy = requiredInt(part, "cy");
        int cz = requiredInt(part, "cz");
        int radius = clamp(requiredInt(part, "radius"), 1, 24);
        int height = clamp(requiredInt(part, "height"), 1, 48);
        validateCoordinate(cx - radius, cy, cz - radius);
        validateCoordinate(cx + radius, cy + height - 1, cz + radius);
        boolean hollow = booleanValue(part, "hollow", false);
        Block block = getBlock(string(part, "block", ""));
        int r2 = radius * radius;
        int inner = Math.max(0, radius - 1);
        int inner2 = inner * inner;

        for (int y = 0; y < height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    int d2 = x * x + z * z;
                    if (d2 > r2) continue;
                    if (hollow && y > 0 && y < height - 1 && d2 < inner2) continue;
                    changed += place(world, origin, facing, cx + x, cy + y, cz + z, block, replace);
                }
            }
        }
        return changed;
    }

    private static int place(ServerWorld world, BlockPos origin, Direction facing, int x, int y, int z, Block block, boolean replace) {
        BlockPos target = toWorld(origin, facing, x, y, z);
        if (!replace && !world.getBlockState(target).isAir()) return 0;
        if (world.getBlockState(target).isOf(block)) return 0;
        world.setBlockState(target, block.getDefaultState(), 3);
        return 1;
    }

    private static BlockPos toWorld(BlockPos origin, Direction facing, int x, int y, int z) {
        return switch (facing) {
            case NORTH -> origin.add(x, y, -z);
            case SOUTH -> origin.add(-x, y, z);
            case EAST -> origin.add(z, y, x);
            case WEST -> origin.add(-z, y, -x);
            default -> origin.add(x, y, z);
        };
    }

    private static Block getBlock(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null || !Registries.BLOCK.containsId(identifier)) throw new IllegalArgumentException("Неизвестный блок: " + id);
        return Registries.BLOCK.get(identifier);
    }

    private static ServerPlayerEntity getPlayer(MinecraftServer server, String requested) {
        if (requested != null && !requested.isBlank()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(requested);
            if (player != null) return player;
        }
        ServerPlayerEntity current = server.getPlayerManager().getPlayerList().stream().findFirst().orElse(null);
        if (current == null) throw new IllegalStateException("Игрок не найден.");
        return current;
    }

    private static void validateCoordinate(int x, int y, int z) {
        if (Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL || Math.abs(y) > MAX_VERTICAL) {
            throw new IllegalArgumentException("Строительная координата вышла за безопасный предел.");
        }
    }

    private static JsonObject error(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("ok", false);
        error.addProperty("error", message);
        return error;
    }

    private static JsonObject simpleProperty(String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject integerProperty(String description) {
        return simpleProperty("integer", description);
    }

    private static JsonObject booleanProperty(String description) {
        return simpleProperty("boolean", description);
    }

    private static JsonObject enumProperty(String[] values, String description) {
        JsonObject property = simpleProperty("string", description);
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        property.add("enum", array);
        return property;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static int requiredInt(JsonObject object, String key) {
        if (!object.has(key)) throw new IllegalArgumentException("Не указан аргумент: " + key);
        return object.get(key).getAsInt();
    }

    private static int optionalInt(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
