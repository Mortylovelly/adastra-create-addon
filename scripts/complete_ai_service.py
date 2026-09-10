from pathlib import Path
import re

PATH = Path('src/main/java/com/mortylovelly/minecraftai/client/AiAgentService.java')
s = PATH.read_text(encoding='utf-8')

# --- Transport / safety limits: smaller prompts, smaller tool loops, faster recovery. ---
replacements = {
    r'private static final int MAX_TOOL_ROUNDS = \d+;': 'private static final int MAX_TOOL_ROUNDS = 4;',
    r'private static final int MAX_TOOL_CALLS = \d+;': 'private static final int MAX_TOOL_CALLS = 10;',
    r'private static final int MAX_TOOL_RESULT_CHARS = \d+;': 'private static final int MAX_TOOL_RESULT_CHARS = 1800;',
    r'private static final int MAX_ENTITY_RESULTS = \d+;': 'private static final int MAX_ENTITY_RESULTS = 20;',
}
for pattern, value in replacements.items():
    s = re.sub(pattern, value, s)

# Keep the existing full mechanics, but make the model instructions substantially smaller.
compact_instructions = '''private static final String INSTRUCTIONS = """
        You are Minecraft AI Agent for Minecraft 1.21.1.
        You control the current singleplayer world only through tools; tool results are ground truth.
        Understand natural Russian and colloquial wording. Never claim an action without a successful tool result.
        Capability questions ("можешь ли ты...") are answered directly without tools; real requests should be executed.
        Use the smallest suitable tool. Use observe_world only when location/orientation/environment is actually needed; use inspect_region for exact blocks.
        For houses use build_house; for custom structures use build_blueprint. Avoid a plain solid box.
        Destruction must stay inside the requested area. Prefer one high-level action over many tiny actions.
        You may call independent world tools together. Stop once the requested result is confirmed.
        Persistent memory is per chat. Save only durable facts such as preferences, named locations, coordinates and build styles.
        If a tool fails, report the failure.
        """.strip();'''
s = re.sub(
    r'private static final String INSTRUCTIONS = """.*?"""\.strip\(\);',
    compact_instructions,
    s,
    count=1,
    flags=re.S,
)

# --- Persistent/API context: send only the last tiny piece of conversation. ---
new_context = '''private static List<JsonObject> buildOpenAiContext(String chatId, String currentMessage, String provider) {
        List<JsonObject> result = new ArrayList<>();
        String memory = truncate(AiAgentMemory.forPrompt(chatId), 1200);
        result.add(chatMessage("system", INSTRUCTIONS + "\\nMemory:" + memory));
        for (AiChatHistory.Entry entry : AiChatHistory.getRecentForApi(chatId, 2, 2400)) {
            result.add(chatMessage(entry.role(), truncate(entry.text(), 900)));
        }
        if (!currentMessage.isBlank()) {
            boolean already = result.stream().anyMatch(message -> string(message, "role", "").equals("user") && string(message, "content", "").equals(currentMessage));
            if (!already) result.add(chatMessage("user", truncate(currentMessage, 1200)));
        }
        return result;
    }'''
s = re.sub(
    r'private static List<JsonObject> buildOpenAiContext\(String chatId, String currentMessage, String provider\) \{.*?\n    \}',
    new_context,
    s,
    count=1,
    flags=re.S,
)

# --- Dynamic tool routing: only expose tools relevant to the current request. ---
marker = 'private static final List<JsonObject> TOOLS = createTools();'
router = r'''
    private static Set<String> selectToolNames(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Set<String> selected = new java.util.LinkedHashSet<>();
        boolean all = lower.isBlank();

        if (lower.contains("стро") || lower.contains("дом") || lower.contains("башн") || lower.contains("замок") || lower.contains("построй") || lower.contains("постро") || lower.contains("build")) {
            selected.addAll(Set.of("observe_world", "inspect_region", "build_house", "build_blueprint", "set_block", "fill_area", "get_block", "break_block", "clear_area"));
        }
        if (lower.contains("деревн") || lower.contains("шахт") || lower.contains("кораб") || lower.contains("портал") || lower.contains("крепост") || lower.contains("найди") || lower.contains("телепорт")) {
            selected.addAll(Set.of("locate_structure", "observe_world", "teleport_player", "get_player_state"));
        }
        if (lower.contains("дай") || lower.contains("выдай") || lower.contains("предмет") || lower.contains("меч") || lower.contains("кирк") || lower.contains("брон") || lower.contains("лук") || lower.contains("инвентар")) {
            selected.addAll(Set.of("give_item", "get_inventory", "clear_inventory", "get_player_state"));
        }
        if (lower.contains("эффект") || lower.contains("зель") || lower.contains("скорост") || lower.contains("сил") || lower.contains("реген") || lower.contains("здоров") || lower.contains("голод") || lower.contains("сердц")) {
            selected.addAll(Set.of("apply_effect", "set_player_stats", "get_player_state"));
        }
        if (lower.contains("спектатор") || lower.contains("креатив") || lower.contains("выжива") || lower.contains("приключ") || lower.contains("режим")) {
            selected.addAll(Set.of("set_gamemode", "get_player_state"));
        }
        if (lower.contains("день") || lower.contains("ноч") || lower.contains("полдн") || lower.contains("полноч") || lower.contains("врем")) {
            selected.add("set_time");
        }
        if (lower.contains("дожд") || lower.contains("гроз") || lower.contains("погода") || lower.contains("солнеч") || lower.contains("ясн")) {
            selected.add("set_weather");
        }
        if (lower.contains("зомби") || lower.contains("скелет") || lower.contains("крипер") || lower.contains("моб") || lower.contains("существ") || lower.contains("призв") || lower.contains("спавн")) {
            selected.addAll(Set.of("spawn_entity", "find_entities", "observe_world"));
        }
        if (lower.contains("блок") || lower.contains("куб") || lower.contains("заполн") || lower.contains("слома") || lower.contains("разруш") || lower.contains("очист") || lower.contains("удали")) {
            selected.addAll(Set.of("get_block", "set_block", "fill_area", "break_block", "clear_area", "inspect_region"));
        }
        if (lower.contains("xp") || lower.contains("опыт") || lower.contains("уров") && lower.contains("выдай")) {
            selected.add("give_experience");
        }
        if (lower.contains("точк") && (lower.contains("возрожд") || lower.contains("респаун") || lower.contains("спавн"))) {
            selected.add("set_spawnpoint");
        }
        if (lower.contains("запомн") || lower.contains("помни") || lower.contains("забудь") || lower.contains("памят")) {
            selected.addAll(Set.of("remember_memory", "forget_memory"));
        }
        if (lower.contains("команд") || lower.startsWith("/") || lower.contains("выполни")) {
            selected.add("run_minecraft_command");
        }
        if (lower.contains("напиши в чат") || lower.contains("сообщен") || lower.contains("скажи всем")) {
            selected.add("send_chat");
        }
        if (selected.isEmpty() || all) {
            selected.addAll(Set.of("get_player_state", "observe_world", "run_minecraft_command", "give_item", "teleport_player", "set_gamemode", "set_time", "set_weather"));
        }
        return selected;
    }

    private static String latestUserText(List<JsonObject> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonObject message = messages.get(i);
            if ("user".equals(string(message, "role", ""))) return string(message, "content", "");
        }
        return "";
    }

    private static JsonArray geminiToolsArray(Set<String> selectedTools) {
        JsonArray array = new JsonArray();
        for (JsonObject tool : TOOLS) {
            if (selectedTools.contains(string(tool, "name", ""))) array.add(tool.deepCopy());
        }
        return array;
    }

    private static JsonArray openAiToolsArray(Set<String> selectedTools) {
        JsonArray array = new JsonArray();
        for (JsonObject tool : TOOLS) {
            if (!selectedTools.contains(string(tool, "name", ""))) continue;
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            JsonObject function = tool.deepCopy();
            function.remove("type");
            wrapper.add("function", function);
            array.add(wrapper);
        }
        return array;
    }
'''
if 'selectToolNames(String message)' not in s:
    s = s.replace(marker, router + '\n    ' + marker)
else:
    # Replace an older router block if present.
    s = re.sub(r'\n    private static Set<String> selectToolNames\(String message\) \{.*?\n    private static final List<JsonObject> TOOLS = createTools\(\);', '\n' + router + '\n    ' + marker, s, count=1, flags=re.S)

# Remove old unfiltered tool-array implementations if any remain.
s = re.sub(r'\n    private static JsonArray geminiToolsArray\(\) \{.*?\n    \}\n\n    private static JsonArray openAiToolsArray\(\) \{.*?\n    \}', '', s, count=1, flags=re.S)

# --- OpenAI-compatible loop gets one stable, compact toolset per user request. ---
s = s.replace(
    'List<JsonObject> messages = buildOpenAiContext(task.chatId, message, provider);\n        return openAiLoop(task, messages, provider, new HashMap<>(), 0, 0, false, isCapabilityQuestion(message));',
    'List<JsonObject> messages = buildOpenAiContext(task.chatId, message, provider);\n        Set<String> selectedTools = selectToolNames(message);\n        return openAiLoop(task, messages, provider, new HashMap<>(), 0, 0, false, isCapabilityQuestion(message), selectedTools);'
)
s = s.replace(
    'boolean ignored,\n            boolean capability) {',
    'boolean ignored,\n            boolean capability,\n            Set<String> selectedTools) {'
)
s = s.replace('payload.add("tools", openAiToolsArray());', 'payload.add("tools", openAiToolsArray(selectedTools));')
s = s.replace(
    'return openAiLoop(task, messages, provider, cache, round + 1, totalCalls + validCalls.size(), false, false);',
    'return openAiLoop(task, messages, provider, cache, round + 1, totalCalls + validCalls.size(), false, false, selectedTools);'
)

# --- Gemini uses the same request-specific tool filtering. ---
s = s.replace(
    'return geminiLoop(task, message, previous, 0, 0, capability, false);',
    'return geminiLoop(task, message, previous, 0, 0, capability, false, selectToolNames(message));'
)
s = s.replace(
    'boolean capability,\n            boolean recovery) {',
    'boolean capability,\n            boolean recovery,\n            Set<String> selectedTools) {'
)
s = s.replace('payload.add("tools", geminiToolsArray());', 'payload.add("tools", geminiToolsArray(selectedTools));')
s = s.replace(
    'geminiLoop(task, message, "", round, totalCalls, capability, true);',
    'geminiLoop(task, message, "", round, totalCalls, capability, true, selectedTools);'
)
s = s.replace(
    'return geminiFollowUp(task, interactionId, inputResults, round + 1, totalCalls + calls.size());',
    'return geminiFollowUp(task, interactionId, inputResults, round + 1, totalCalls + calls.size(), selectedTools);'
)
s = s.replace(
    'private static CompletableFuture<String> geminiFollowUp(ActiveTask task, String interactionId, JsonArray results, int round, int totalCalls) {',
    'private static CompletableFuture<String> geminiFollowUp(ActiveTask task, String interactionId, JsonArray results, int round, int totalCalls, Set<String> selectedTools) {'
)
s = s.replace('payload.add("tools", geminiToolsArray());', 'payload.add("tools", geminiToolsArray(selectedTools));')
s = s.replace(
    'geminiFollowUp(task, newId, nextResults, round + 1, totalCalls + calls.size());',
    'geminiFollowUp(task, newId, nextResults, round + 1, totalCalls + calls.size(), selectedTools);'
)

# --- Tool result compaction and smaller observation defaults. ---
# Keep exact inspection available, but prevent it from becoming a multi-round megabyte payload.
s = re.sub(r'if \(blocks\.size\(\) >= 900\) continue;', 'if (blocks.size() >= 320) continue;', s)
s = re.sub(r'int rx = Math\.min\(6, Math\.max\(1, optionalInt\(args, "radius", 4\)\));', 'int rx = Math.min(5, Math.max(1, optionalInt(args, "radius", 3)));', s)
s = re.sub(r'int ry = Math\.min\(5, Math\.max\(1, optionalInt\(args, "vertical", 3\)\));', 'int ry = Math.min(4, Math.max(1, optionalInt(args, "vertical", 2)));', s)

# Observe tool: default to a small, useful snapshot. Full terrain/block scan is opt-in.
s = s.replace(
    'int radius = Math.min(MAX_OBSERVE_RADIUS, Math.max(2, optionalInt(args, "radius", 8)));',
    'int radius = Math.min(MAX_OBSERVE_RADIUS, Math.max(2, optionalInt(args, "radius", 6)));\n        String detail = string(args, "detail", "minimal").toLowerCase(Locale.ROOT);\n        boolean fullDetail = detail.equals("full");'
)
s = re.sub(r'\.limit\(MAX_ENTITY_RESULTS\)\.forEach\(entity -> \{', '.limit(fullDetail ? MAX_ENTITY_RESULTS : Math.min(8, MAX_ENTITY_RESULTS)).forEach(entity -> {', s)

# Wrap the expensive surface + nearby block scans in full-detail mode.
old_observation_tail = '''        JsonArray surface = new JsonArray();
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
        data.add("nearby_blocks", nearbyBlocks);'''
wrapped_observation_tail = '''        if (fullDetail) {
            JsonArray surface = new JsonArray();
            int bottom = Math.max(world.getBottomY(), py - 24);
            int top = Math.min(world.getTopY() - 1, py + 12);
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
        }'''
s = s.replace(old_observation_tail, wrapped_observation_tail)

# Add the detail field to the existing observe_world tool schema.
s = s.replace(
    'intPropertyOptional("radius", "Horizontal observation radius, 2..10.")\n        )));',
    'intPropertyOptional("radius", "Horizontal radius 2..10."),\n                enumProperty("detail", new String[]{"minimal","full"}, "Use full only when terrain/block layout is needed.", false)\n        )));'
)

# --- Direct local fast paths: no LLM request for unambiguous tiny commands. ---
fast_path = r'''
    private static CompletableFuture<String> tryFastAction(ActiveTask task, String message) {
        if (message == null) return null;
        String lower = message.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
        if (lower.length() > 100) return null;
        JsonObject args = new JsonObject();
        String name = null;
        if (lower.matches(".*\\b(спектатор|spectator)\\b.*") && !lower.contains("можешь") && !lower.contains("умеешь") && !lower.contains("сможешь")) {
            args.addProperty("mode", "spectator");
            name = "set_gamemode";
        } else if (lower.matches("^(включи|поставь|сделай|установи)?\\s*(креатив|creative)$")) {
            args.addProperty("mode", "creative");
            name = "set_gamemode";
        } else if (lower.matches("^(включи|поставь|сделай|установи)?\\s*(выживание|survival)$")) {
            args.addProperty("mode", "survival");
            name = "set_gamemode";
        } else if (lower.matches("^(сделай|поставь|включи)?\\s*(день|day)$")) {
            args.addProperty("time", "day");
            name = "set_time";
        } else if (lower.matches("^(сделай|поставь|включи)?\\s*(ночь|night)$")) {
            args.addProperty("time", "night");
            name = "set_time";
        } else if (lower.matches("^(включи|поставь)?\\s*(дождь|rain)$")) {
            args.addProperty("weather", "rain");
            name = "set_weather";
        } else if (lower.matches("^(включи|поставь)?\\s*(гроза|thunder)$")) {
            args.addProperty("weather", "thunder");
            name = "set_weather";
        } else if (lower.matches("^(убери|выключи|останови|сделай)\\s*(дождь|грозу|погоду|ясно|солнечно).*$") || lower.equals("ясно") || lower.equals("clear")) {
            args.addProperty("weather", "clear");
            name = "set_weather";
        } else if (lower.matches("^(очисти|очистить|удали|стереть)\\s+(весь\\s+)?инвентар(ь|я)$")) {
            name = "clear_inventory";
        }
        if (name == null) return null;
        return runOnServer(task, name, args).thenApply(result -> {
            if (!toolResultOk(result)) return "Не удалось выполнить действие: " + string(result, "error", "неизвестная ошибка");
            return localActionSummary(List.of(toolCallForSummary(name)));
        });
    }

    private static JsonObject toolCallForSummary(String name) {
        JsonObject call = new JsonObject();
        call.addProperty("name", name);
        return call;
    }
'''
if 'private static CompletableFuture<String> tryFastAction' not in s:
    s = s.replace('    private static CompletableFuture<String> chatOpenAiCompatible', fast_path + '\n    private static CompletableFuture<String> chatOpenAiCompatible', 1)

# Invoke fast paths before any provider call.
old_work = '''        CompletableFuture<String> work;
        try {
            work = switch (AiClientConfig.getProvider()) {'''
new_work = '''        CompletableFuture<String> work;
        CompletableFuture<String> fastAction = tryFastAction(task, message);
        try {
            work = fastAction != null ? fastAction : switch (AiClientConfig.getProvider()) {'''
s = s.replace(old_work, new_work, 1)

# --- Additional tools: keep world capability broad without increasing every request's schema. ---
additional_switch = '''            case "get_player_state" -> getPlayerState(server, args);
            case "get_block" -> getBlock(server, args);
            case "set_block" -> setBlock(server, args);
            case "fill_area" -> fillArea(server, args, task);
            case "find_entities" -> findEntities(server, args);
            case "give_experience" -> giveExperience(server, args);
            case "set_spawnpoint" -> setSpawnpoint(server, args);\n'''
if 'case "get_player_state"' not in s:
    s = s.replace('            case "send_chat" -> sendChat(server, args);\n', '            case "send_chat" -> sendChat(server, args);\n' + additional_switch, 1)

additional_methods = r'''
    // AI_ECONOMY_TOOLS_V3
    private static JsonObject getPlayerState(MinecraftServer server, JsonObject args) {
        return okResult(playerState(getPlayer(server, string(args, "player", ""))));
    }

    private static JsonObject getBlock(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        ServerWorld world = player.getServerWorld();
        BlockPos pos = new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
        var state = world.getBlockState(pos);
        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        data.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
        data.addProperty("air", state.isAir());
        return okResult(data);
    }

    private static JsonObject setBlock(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        ServerWorld world = player.getServerWorld();
        String blockId = string(args, "block", "");
        Block block = blockFromId(blockId);
        BlockPos pos = new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
        String previous = Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString();
        boolean changed = world.setBlockState(pos, block.getDefaultState());
        JsonObject data = new JsonObject();
        data.addProperty("changed", changed);
        data.addProperty("previous_block", previous);
        data.addProperty("block", Registries.BLOCK.getId(block).toString());
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        return okResult(data);
    }

    private static JsonObject fillArea(MinecraftServer server, JsonObject args, ActiveTask task) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        ServerWorld world = player.getServerWorld();
        int x1 = requiredInt(args, "x1"), y1 = requiredInt(args, "y1"), z1 = requiredInt(args, "z1");
        int x2 = requiredInt(args, "x2"), y2 = requiredInt(args, "y2"), z2 = requiredInt(args, "z2");
        long requested = volume(x1, y1, z1, x2, y2, z2);
        if (requested > MAX_BUILD_VOLUME) return errorResult("Область слишком большая.");
        String mode = string(args, "mode", "fill").toLowerCase(Locale.ROOT);
        Block block = mode.equals("clear") ? net.minecraft.block.Blocks.AIR : blockFromId(string(args, "block", ""));
        if (!(mode.equals("fill") || mode.equals("hollow") || mode.equals("clear"))) return errorResult("Неизвестный режим: " + mode);
        int changed = applyCuboid(world, x1, y1, z1, x2, y2, z2, block, mode, task);
        if (changed < 0) return errorResult("Запрос отменён.");
        JsonObject data = new JsonObject();
        data.addProperty("changed_blocks", changed);
        data.addProperty("requested_blocks", requested);
        data.addProperty("mode", mode);
        return okResult(data);
    }

    private static JsonObject findEntities(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        ServerWorld world = player.getServerWorld();
        int radius = Math.min(32, Math.max(1, optionalInt(args, "radius", 12)));
        int limit = Math.min(30, Math.max(1, optionalInt(args, "limit", 10)));
        String wanted = string(args, "type", "").trim().toLowerCase(Locale.ROOT);
        Box box = new Box(player.getBlockPos()).expand(radius);
        List<Entity> entities = world.getOtherEntities(player, box, entity -> {
            if (wanted.isBlank()) return true;
            return Registries.ENTITY_TYPE.getId(entity.getType()).toString().equals(wanted)
                    || entity.getType().getTranslationKey().toLowerCase(Locale.ROOT).contains(wanted);
        });
        JsonArray result = new JsonArray();
        entities.stream().sorted(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(player))).limit(limit).forEach(entity -> {
            JsonObject item = new JsonObject();
            item.addProperty("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
            item.addProperty("name", entity.getName().getString());
            item.addProperty("x", round(entity.getX()));
            item.addProperty("y", round(entity.getY()));
            item.addProperty("z", round(entity.getZ()));
            item.addProperty("distance", round(Math.sqrt(entity.squaredDistanceTo(player))));
            result.add(item);
        });
        JsonObject data = new JsonObject();
        data.add("entities", result);
        data.addProperty("count", result.size());
        data.addProperty("radius", radius);
        return okResult(data);
    }

    private static JsonObject giveExperience(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        int amount = Math.min(1000000, Math.max(1, requiredInt(args, "amount")));
        String unit = string(args, "unit", "levels").toLowerCase(Locale.ROOT);
        if (!unit.equals("levels") && !unit.equals("points")) return errorResult("unit должен быть levels или points.");
        return runCommand(server, "experience add " + player.getName().getString() + " " + amount + " " + unit);
    }

    private static JsonObject setSpawnpoint(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        int x = args.has("x") ? args.get("x").getAsInt() : player.getBlockPos().getX();
        int y = args.has("y") ? args.get("y").getAsInt() : player.getBlockPos().getY();
        int z = args.has("z") ? args.get("z").getAsInt() : player.getBlockPos().getZ();
        return runCommand(server, "spawnpoint " + player.getName().getString() + " " + x + " " + y + " " + z);
    }
'''
if 'AI_ECONOMY_TOOLS_V3' not in s:
    s = s.replace('    private static JsonObject locateStructure(MinecraftServer server, JsonObject args) {', additional_methods + '\n    private static JsonObject locateStructure(MinecraftServer server, JsonObject args) {', 1)

# Add compact schemas for the new tools without touching the existing mechanics.
additional_schema = '''        tools.add(function("get_player_state", "Read compact player position, health, food, mode and dimension.", objectProperties(property("player", "string", "Player name.", false))));
        tools.add(function("get_block", "Read one exact block ID at coordinates.", objectProperties(property("player", "string", "Player name.", false), intProperty("x"), intProperty("y"), intProperty("z"))));
        tools.add(function("set_block", "Set one exact block at coordinates.", objectProperties(property("player", "string", "Player name.", false), intProperty("x"), intProperty("y"), intProperty("z"), property("block", "string", "Block ID.", true))));
        tools.add(function("fill_area", "Fill, hollow or clear one cuboid up to the safe build cap.", objectProperties(property("player", "string", "Player name.", false), intProperty("x1"), intProperty("y1"), intProperty("z1"), intProperty("x2"), intProperty("y2"), intProperty("z2"), enumProperty("mode", new String[]{"fill","hollow","clear"}, "Operation mode.", true), property("block", "string", "Block ID; omitted for clear.", false))));
        tools.add(function("find_entities", "Find nearby entities with a compact result.", objectProperties(property("player", "string", "Player name.", false), property("type", "string", "Optional entity ID filter.", false), intPropertyOptional("radius", "Search radius up to 32."), intPropertyOptional("limit", "Result limit up to 30."))));
        tools.add(function("give_experience", "Give experience levels or points.", objectProperties(property("player", "string", "Player name.", false), intProperty("amount"), enumProperty("unit", new String[]{"levels","points"}, "Experience unit.", true))));
        tools.add(function("set_spawnpoint", "Set the player's respawn point to exact coordinates; omitted coordinates use the current position.", objectProperties(property("player", "string", "Player name.", false), intPropertyOptional("x", "Spawn X."), intPropertyOptional("y", "Spawn Y."), intPropertyOptional("z", "Spawn Z."))));
'''
if 'function("get_player_state"' not in s:
    s = s.replace('        tools.add(function("observe_world"', additional_schema + '        tools.add(function("observe_world"', 1)

# Shorten a few verbose schema descriptions that are duplicated on every request.
s = s.replace('"Observe the current player, exact position/rotation, time/weather, nearby blocks, surface heightmap and nearby entities/mobs. Use before spatial work."', '"Compact world snapshot: player, time/weather and nearby entities. Use only when spatial context is needed."')
s = s.replace('"Inspect exact non-air blocks in a local rectangular volume around a point. Use when exact block layout matters."', '"Read exact non-air blocks in a small local volume."')
s = s.replace('"Build a custom structure efficiently from up to 96 primitives: fill, hollow, clear, set, line and roof_gable. Use this for detailed builds rather than many tiny calls."', '"Build a custom structure efficiently from compact primitives."')

# Ensure old DeepSeek normalization remains intact.
chat_uri_line = '    private static final URI DEEPSEEK_CHAT_URI = URI.create("https://api.deepseek.com/chat/completions");'
s = s.replace(chat_uri_line + '\n' + chat_uri_line, chat_uri_line)
if chat_uri_line not in s:
    s = s.replace(
        '    private static final URI DEEPSEEK_URI = URI.create("https://api.deepseek.com/responses");',
        '    private static final URI DEEPSEEK_URI = URI.create("https://api.deepseek.com/responses");\n' + chat_uri_line
    )

# Preserve the known DeepSeek / argument / custom-name / capability fixes.
old_deepseek = '''case "deepseek" -> {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("model", DEEPSEEK_MODEL);
                    payload.addProperty("instructions", INSTRUCTIONS + "\\n\\nPersistent memory for this chat:\\n" + AiAgentMemory.forPrompt(task.chatId));
                    payload.addProperty("input", message);
                    payload.addProperty("max_output_tokens", 4096);
                    yield requestJson(DEEPSEEK_URI, AiClientConfig.getDeepSeekApiKey(), payload, "DeepSeek")
                            .thenApply(response -> {
                                if (task.cancelled) return "Запрос остановлен пользователем.";
                                String text = string(response, "output_text", "");
                                AiAgentStatus.clear();
                                return text.isBlank() ? "DeepSeek не вернул текстовый ответ." : text;
                            });
                }'''
s = s.replace(old_deepseek, 'case "deepseek" -> chatOpenAiCompatible(task, message, "deepseek");')

old_test = '''if (provider.equals("deepseek")) {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", DEEPSEEK_MODEL);
            payload.addProperty("instructions", "Reply with exactly: OK");
            payload.addProperty("input", "Connection test");
            payload.addProperty("max_output_tokens", 32);
            return requestJson(DEEPSEEK_URI, AiClientConfig.getDeepSeekApiKey(), payload, "DeepSeek")
                    .thenApply(root -> !string(root, "output_text", "").isBlank());
        }'''
new_test = '''if (provider.equals("deepseek")) {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", DEEPSEEK_MODEL);
            JsonArray messages = new JsonArray();
            messages.add(chatMessage("system", "Reply with exactly: OK"));
            messages.add(chatMessage("user", "Connection test"));
            payload.add("messages", messages);
            payload.addProperty("temperature", 0);
            payload.addProperty("max_tokens", 32);
            return requestJson(DEEPSEEK_CHAT_URI, AiClientConfig.getDeepSeekApiKey(), payload, "DeepSeek")
                    .thenApply(root -> {
                        JsonObject choice = firstChoice(root);
                        return choice != null && choice.has("message") && !string(choice.getAsJsonObject("message"), "content", "").isBlank();
                    });
        }'''
s = s.replace(old_test, new_test)

s = s.replace(
    'private static URI uriForProvider(String provider) { return provider.equals("openrouter") ? OPENROUTER_URI : GROQ_URI; }',
    '''private static URI uriForProvider(String provider) {
        return switch (provider) {
            case "deepseek" -> DEEPSEEK_CHAT_URI;
            case "openrouter" -> OPENROUTER_URI;
            default -> GROQ_URI;
        };
    }'''
)
s = s.replace(
    'private static String keyForProvider(String provider) { return provider.equals("openrouter") ? AiClientConfig.getOpenRouterApiKey() : AiClientConfig.getGroqApiKey(); }',
    '''private static String keyForProvider(String provider) {
        return switch (provider) {
            case "deepseek" -> AiClientConfig.getDeepSeekApiKey();
            case "openrouter" -> AiClientConfig.getOpenRouterApiKey();
            default -> AiClientConfig.getGroqApiKey();
        };
    }'''
)
s = s.replace(
    'private static String modelForProvider(String provider) { return provider.equals("openrouter") ? OPENROUTER_MODEL : GROQ_MODEL; }',
    '''private static String modelForProvider(String provider) {
        return switch (provider) {
            case "deepseek" -> DEEPSEEK_MODEL;
            case "openrouter" -> OPENROUTER_MODEL;
            default -> GROQ_MODEL;
        };
    }'''
)

s = s.replace('AiAgentStatus.set(statusForTool(name));', 'AiAgentStatus.set("Выполняю действие");')
s = s.replace('totalRequested += Math.min(volume, Integer.MAX_VALUE);', 'totalRequested += (int) Math.min(volume, (long) Integer.MAX_VALUE);')

old_args = '''String raw = string(function, "arguments", "{}");
        JsonObject args;
        try { args = JsonParser.parseString(raw).getAsJsonObject(); }
        catch (RuntimeException exception) { args = new JsonObject(); }'''
new_args = '''JsonObject args;
        JsonElement argumentElement = function.get("arguments");
        try {
            if (argumentElement != null && argumentElement.isJsonObject()) args = argumentElement.getAsJsonObject().deepCopy();
            else if (argumentElement != null && argumentElement.isJsonPrimitive()) args = JsonParser.parseString(argumentElement.getAsString()).getAsJsonObject();
            else args = new JsonObject();
        } catch (RuntimeException exception) {
            args = new JsonObject();
        }'''
s = s.replace(old_args, new_args)

old_name = 'if (!name.isBlank()) components.add("custom_name=\\\'" + new com.google.gson.Gson().toJson(new JsonObject()) + "\\\'");'
new_name = '''if (!name.isBlank()) {
            JsonObject nameObject = new JsonObject();
            nameObject.addProperty("text", name);
            components.add("custom_name='" + new com.google.gson.Gson().toJson(nameObject) + "'");
        }'''
s = s.replace(old_name, new_name)

old_cap = 'return lower.endsWith("?") && (lower.contains("можешь") || lower.contains("сможешь") || lower.contains("умеешь") || lower.contains("можно ли") || lower.contains("способен") || lower.contains("умеет ли"));'
new_cap = '''return (lower.endsWith("?") && (lower.contains("можешь") || lower.contains("сможешь") || lower.contains("умеешь") || lower.contains("можно ли") || lower.contains("способен") || lower.contains("умеет ли")))
                || lower.startsWith("можешь ли") || lower.startsWith("сможешь ли") || lower.startsWith("умеешь ли");'''
s = s.replace(old_cap, new_cap)

PATH.write_text(s, encoding='utf-8')
print('AI service normalized with token economy router V3 and expanded world tools')
