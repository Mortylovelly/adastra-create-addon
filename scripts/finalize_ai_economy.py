from pathlib import Path
import re

PATH = Path('src/main/java/com/mortylovelly/minecraftai/client/AiAgentService.java')
s = PATH.read_text(encoding='utf-8')


def sub_once(pattern: str, replacement: str, label: str) -> None:
    global s
    new, count = re.subn(pattern, replacement, s, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f'Could not transform {label}')
    s = new


def ensure(text: str, label: str) -> None:
    if text not in s:
        raise RuntimeError(f'Missing expected source section: {label}')

# Smaller recurring request payloads and bounded agent loops.
s = re.sub(r'private static final int MAX_TOOL_ROUNDS = \d+;', 'private static final int MAX_TOOL_ROUNDS = 4;', s)
s = re.sub(r'private static final int MAX_TOOL_CALLS = \d+;', 'private static final int MAX_TOOL_CALLS = 8;', s)
s = re.sub(r'private static final int MAX_TOOL_RESULT_CHARS = \d+;', 'private static final int MAX_TOOL_RESULT_CHARS = 1800;', s)
s = re.sub(r'private static final int MAX_ENTITY_RESULTS = \d+;', 'private static final int MAX_ENTITY_RESULTS = 20;', s)

# Much smaller system prompt.
sub_once(
    r'private static final String INSTRUCTIONS = """.*?"""\.strip\(\);',
    '''private static final String INSTRUCTIONS = """
            You are Minecraft AI Agent for Minecraft 1.21.1.
            Control the current singleplayer world through tools; tool results are ground truth.
            Understand natural Russian and colloquial requests. Never claim an action without a successful tool result.
            Capability questions are answered directly without tools; real requests should be executed.
            Use the smallest suitable tool. Observe only when position/orientation/environment matters; inspect_region is for exact blocks.
            For houses use build_house; for custom structures use build_blueprint. Avoid plain solid boxes.
            Keep destruction inside the requested area. Prefer one high-level action over many tiny calls.
            Independent tool calls may be grouped. Stop after the requested result is confirmed.
            Memory is per chat; store only durable facts such as preferences, locations, coordinates and styles.
            If a tool fails, report the failure.
            """.strip();''',
    'compact instructions'
)

# Only a tiny recent context goes to OpenAI-compatible providers.
sub_once(
    r'private static List<JsonObject> buildOpenAiContext\(String chatId, String currentMessage, String provider\) \{.*?\n    \}',
    '''private static List<JsonObject> buildOpenAiContext(String chatId, String currentMessage, String provider) {
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
    }''',
    'compact OpenAI context'
)

# Stable request-specific tool routing. It is deliberately local and deterministic.
router = '''
    private static Set<String> selectToolNames(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT).replace('ё', 'е');
        Set<String> selected = new java.util.LinkedHashSet<>();

        if (containsAny(lower, "стро", "дом", "башн", "замок", "построй", "постро", "build")) {
            selected.addAll(Set.of("observe_world", "inspect_region", "build_house", "build_blueprint", "set_block", "fill_area", "get_block", "break_block", "clear_area"));
        }
        if (containsAny(lower, "деревн", "шахт", "кораб", "портал", "крепост", "найди", "телепорт")) {
            selected.addAll(Set.of("locate_structure", "observe_world", "teleport_player", "get_player_state"));
        }
        if (containsAny(lower, "дай", "выдай", "предмет", "меч", "кирк", "брон", "лук", "инвентар")) {
            selected.addAll(Set.of("give_item", "get_inventory", "clear_inventory", "get_player_state"));
        }
        if (containsAny(lower, "эффект", "зель", "скорост", "сил", "реген", "здоров", "голод", "сердц")) {
            selected.addAll(Set.of("apply_effect", "set_player_stats", "get_player_state"));
        }
        if (containsAny(lower, "спектатор", "креатив", "выжива", "приключ", "режим")) {
            selected.addAll(Set.of("set_gamemode", "get_player_state"));
        }
        if (containsAny(lower, "день", "ноч", "полдн", "полноч", "врем")) selected.add("set_time");
        if (containsAny(lower, "дожд", "гроз", "погода", "солнеч", "ясн")) selected.add("set_weather");
        if (containsAny(lower, "зомби", "скелет", "крипер", "моб", "существ", "призв", "спавн")) {
            selected.addAll(Set.of("spawn_entity", "find_entities", "observe_world"));
        }
        if (containsAny(lower, "блок", "куб", "заполн", "слома", "разруш", "очист", "удали")) {
            selected.addAll(Set.of("get_block", "set_block", "fill_area", "break_block", "clear_area", "inspect_region"));
        }
        if (containsAny(lower, "xp", "опыт") || (lower.contains("уров") && lower.contains("выдай"))) selected.add("give_experience");
        if (lower.contains("точк") && containsAny(lower, "возрожд", "респаун", "спавн")) selected.add("set_spawnpoint");
        if (containsAny(lower, "запомн", "помни", "забудь", "памят")) selected.addAll(Set.of("remember_memory", "forget_memory"));
        if (lower.contains("команд") || lower.startsWith("/") || lower.contains("выполни")) selected.add("run_minecraft_command");
        if (containsAny(lower, "напиши в чат", "сообщен", "скажи всем")) selected.add("send_chat");

        if (selected.isEmpty()) {
            selected.addAll(Set.of("get_player_state", "observe_world", "run_minecraft_command", "give_item", "teleport_player", "set_gamemode", "set_time", "set_weather"));
        }
        return selected;
    }

    private static boolean containsAny(String text, String... parts) {
        for (String part : parts) if (text.contains(part)) return true;
        return false;
    }
'''
if 'private static Set<String> selectToolNames(String message)' not in s:
    s = s.replace('    private static final List<JsonObject> TOOLS = createTools();', router + '\n    private static final List<JsonObject> TOOLS = createTools();', 1)

# Replace tool-array helpers with request-filtered versions while keeping no-arg compatibility for old helpers.
sub_once(
    r'    private static JsonArray geminiToolsArray\(\) \{.*?    \}\n\n    private static JsonArray openAiToolsArray\(\) \{.*?    \}',
    '''    private static JsonArray geminiToolsArray() { return geminiToolsArray(new java.util.HashSet<>(TOOLS.stream().map(t -> string(t, "name", "")).toList())); }

    private static JsonArray geminiToolsArray(Set<String> selectedTools) {
        JsonArray array = new JsonArray();
        for (JsonObject tool : TOOLS) if (selectedTools.contains(string(tool, "name", ""))) array.add(tool.deepCopy());
        return array;
    }

    private static JsonArray openAiToolsArray() { return openAiToolsArray(new java.util.HashSet<>(TOOLS.stream().map(t -> string(t, "name", "")).toList())); }

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
    }''',
    'filtered tool arrays'
)

# OpenAI-compatible loop receives only the selected schemas, and keeps that selection across follow-ups.
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

# Gemini gets the same request-specific tool selection.
s = s.replace(
    'return geminiLoop(task, message, previous, 0, 0, capability, false);',
    'return geminiLoop(task, message, previous, 0, 0, capability, false, selectToolNames(message));'
)
s = s.replace(
    'boolean capability,\n            boolean recovery) {',
    'boolean capability,\n            boolean recovery,\n            Set<String> selectedTools) {'
)
s = s.replace('payload.add("tools", geminiToolsArray());', 'payload.add("tools", geminiToolsArray(selectedTools));')
s = s.replace('geminiLoop(task, message, "", round, totalCalls, capability, true);', 'geminiLoop(task, message, "", round, totalCalls, capability, true, selectedTools);')
s = s.replace(
    'return geminiFollowUp(task, interactionId, inputResults, round + 1, totalCalls + calls.size());',
    'return geminiFollowUp(task, interactionId, inputResults, round + 1, totalCalls + calls.size(), selectedTools);'
)
s = s.replace(
    'private static CompletableFuture<String> geminiFollowUp(ActiveTask task, String interactionId, JsonArray results, int round, int totalCalls) {',
    'private static CompletableFuture<String> geminiFollowUp(ActiveTask task, String interactionId, JsonArray results, int round, int totalCalls, Set<String> selectedTools) {'
)
s = s.replace('payload.add("tools", geminiToolsArray());', 'payload.add("tools", geminiToolsArray(selectedTools));')
s = s.replace('geminiFollowUp(task, newId, nextResults, round + 1, totalCalls + calls.size());', 'geminiFollowUp(task, newId, nextResults, round + 1, totalCalls + calls.size(), selectedTools);')

# Tool result payloads are intentionally tiny; rich reads can still be requested explicitly.
s = re.sub(r'if \(blocks\.size\(\) >= 900\) continue;', 'if (blocks.size() >= 320) continue;', s)
s = re.sub(r'int rx = Math\.min\(6, Math\.max\(1, optionalInt\(args, "radius", 4\)\)\);', 'int rx = Math.min(5, Math.max(1, optionalInt(args, "radius", 3)));', s)
s = re.sub(r'int ry = Math\.min\(5, Math\.max\(1, optionalInt\(args, "vertical", 3\)\)\);', 'int ry = Math.min(4, Math.max(1, optionalInt(args, "vertical", 2)));', s)
s = s.replace(
    'int radius = Math.min(MAX_OBSERVE_RADIUS, Math.max(2, optionalInt(args, "radius", 8)));',
    'int radius = Math.min(MAX_OBSERVE_RADIUS, Math.max(2, optionalInt(args, "radius", 6)));\n        String detail = string(args, "detail", "minimal").toLowerCase(Locale.ROOT);\n        boolean fullDetail = detail.equals("full");'
)
s = re.sub(r'\.limit\(MAX_ENTITY_RESULTS\)\.forEach\(entity -> \{', '.limit(fullDetail ? MAX_ENTITY_RESULTS : Math.min(8, MAX_ENTITY_RESULTS)).forEach(entity -> {', s)

# Make the expensive terrain/block portion opt-in.
def indent_block(text: str, prefix: str = '    ') -> str:
    return '\n'.join(prefix + line if line else line for line in text.splitlines())

if 'if (fullDetail) {' not in s:
    pattern = r'        JsonArray surface = new JsonArray\(\);.*?        data\.add\("nearby_blocks", nearbyBlocks\);'
    match = re.search(pattern, s, flags=re.S)
    if not match:
        raise RuntimeError('Could not isolate observe_world terrain scan')
    body = match.group(0)
    wrapped = '        if (fullDetail) {\n' + indent_block(body, '    ') + '\n        }'
    s = s[:match.start()] + wrapped + s[match.end():]

# Add detail switch to the observe_world schema.
s = s.replace(
    'intPropertyOptional("radius", "Horizontal observation radius, 2..10.")\n        )));',
    'intPropertyOptional("radius", "Horizontal radius 2..10."),\n                enumProperty("detail", new String[]{"minimal","full"}, "Use full only for terrain/block layout.", false)\n        )));'
)

# Expand spawn_entity so repeated mobs can be requested in one model tool call.
old_spawn = '''    private static JsonObject spawnEntity(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        String entity = string(args, "entity", "minecraft:pig");
        Identifier id = Identifier.tryParse(entity);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) return errorResult("Неизвестное существо: " + entity);
        int x = args.has("x") ? args.get("x").getAsInt() : player.getBlockPos().getX();
        int y = args.has("y") ? args.get("y").getAsInt() : player.getBlockPos().getY();
        int z = args.has("z") ? args.get("z").getAsInt() : player.getBlockPos().getZ();
        return runCommand(server, "summon " + entity + " " + x + " " + y + " " + z);
    }'''
new_spawn = '''    private static JsonObject spawnEntity(MinecraftServer server, JsonObject args) {
        ServerPlayerEntity player = getPlayer(server, string(args, "player", ""));
        String entity = string(args, "entity", "minecraft:pig");
        Identifier id = Identifier.tryParse(entity);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) return errorResult("Неизвестное существо: " + entity);
        int x = args.has("x") ? args.get("x").getAsInt() : player.getBlockPos().getX();
        int y = args.has("y") ? args.get("y").getAsInt() : player.getBlockPos().getY();
        int z = args.has("z") ? args.get("z").getAsInt() : player.getBlockPos().getZ();
        int count = Math.min(16, Math.max(1, optionalInt(args, "count", 1)));
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            JsonObject result = runCommand(server, "summon " + entity + " " + x + " " + y + " " + z);
            if (!toolResultOk(result)) return result;
            spawned++;
        }
        JsonObject data = new JsonObject();
        data.addProperty("spawned", spawned);
        data.addProperty("entity", entity);
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        return okResult(data);
    }'''
s = s.replace(old_spawn, new_spawn)
s = s.replace(
    'property("entity", "string", "Entity ID.", true), property("player", "string", "Player name; empty means current player.", false), intPropertyOptional("x", "X."), intPropertyOptional("y", "Y."), intPropertyOptional("z", "Z.")',
    'property("entity", "string", "Entity ID.", true), property("player", "string", "Player name; empty means current player.", false), intPropertyOptional("x", "X."), intPropertyOptional("y", "Y."), intPropertyOptional("z", "Z."), intPropertyOptional("count", "1..16 entities in one call.")'
)

# --- Extra direct world tools. ---
if 'AI_ECONOMY_TOOLS_V4' not in s:
    marker = '    private static JsonObject breakBlock(MinecraftServer server, JsonObject args) {'
    extra_methods = '''    // AI_ECONOMY_TOOLS_V4
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
        Block block = blockFromId(string(args, "block", ""));
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
        if (!mode.equals("fill") && !mode.equals("hollow") && !mode.equals("clear")) return errorResult("Неизвестный режим: " + mode);
        Block block = mode.equals("clear") ? net.minecraft.block.Blocks.AIR : blockFromId(string(args, "block", ""));
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
    if marker not in s:
        raise RuntimeError('Could not place extra tool methods')
    s = s.replace(marker, extra_methods + marker, 1)

# Switch cases for the new methods.
if 'case "get_player_state"' not in s:
    s = s.replace(
        '            case "send_chat" -> sendChat(server, args);\n',
        '            case "send_chat" -> sendChat(server, args);\n            case "get_player_state" -> getPlayerState(server, args);\n            case "get_block" -> getBlock(server, args);\n            case "set_block" -> setBlock(server, args);\n            case "fill_area" -> fillArea(server, args, task);\n            case "find_entities" -> findEntities(server, args);\n            case "give_experience" -> giveExperience(server, args);\n            case "set_spawnpoint" -> setSpawnpoint(server, args);\n',
        1,
    )

# Compact schemas for the extra tools.
if 'function("get_player_state"' not in s:
    schema_marker = '        tools.add(function("observe_world"'
    schemas = '''        tools.add(function("get_player_state", "Read compact player position, health, food, mode and dimension.", objectProperties(property("player", "string", "Player name.", false))));
        tools.add(function("get_block", "Read one exact block ID at coordinates.", objectProperties(property("player", "string", "Player name.", false), intProperty("x"), intProperty("y"), intProperty("z"))));
        tools.add(function("set_block", "Set one exact block at coordinates.", objectProperties(property("player", "string", "Player name.", false), intProperty("x"), intProperty("y"), intProperty("z"), property("block", "string", "Block ID.", true))));
        tools.add(function("fill_area", "Fill, hollow or clear one cuboid up to the safe build cap.", objectProperties(property("player", "string", "Player name.", false), intProperty("x1"), intProperty("y1"), intProperty("z1"), intProperty("x2"), intProperty("y2"), intProperty("z2"), enumProperty("mode", new String[]{"fill","hollow","clear"}, "Operation mode.", true), property("block", "string", "Block ID; omitted for clear.", false))));
        tools.add(function("find_entities", "Find nearby entities with compact positions.", objectProperties(property("player", "string", "Player name.", false), property("type", "string", "Optional entity ID filter.", false), intPropertyOptional("radius", "Search radius up to 32."), intPropertyOptional("limit", "Result limit up to 30."))));
        tools.add(function("give_experience", "Give experience levels or points.", objectProperties(property("player", "string", "Player name.", false), intProperty("amount"), enumProperty("unit", new String[]{"levels","points"}, "Experience unit.", true))));
        tools.add(function("set_spawnpoint", "Set the player's respawn point; omitted coordinates use the current position.", objectProperties(property("player", "string", "Player name.", false), intPropertyOptional("x", "Spawn X."), intPropertyOptional("y", "Spawn Y."), intPropertyOptional("z", "Spawn Z."))));
'''
    if schema_marker not in s:
        raise RuntimeError('Could not place extra tool schemas')
    s = s.replace(schema_marker, schemas + schema_marker, 1)

# New action tools can finish without another model round.
s = s.replace(
    '"send_chat", "remember_memory", "forget_memory"',
    '"send_chat", "remember_memory", "forget_memory", "set_block", "fill_area", "give_experience", "set_spawnpoint"'
)

# Local fast path for unambiguous tiny commands: zero API tokens consumed.
if 'private static CompletableFuture<String> tryFastAction' not in s:
    fast_path = '''
    private static CompletableFuture<String> tryFastAction(ActiveTask task, String message) {
        if (message == null) return null;
        String lower = message.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
        if (lower.length() > 90) return null;
        JsonObject args = new JsonObject();
        String name = null;
        if (!lower.contains("можешь") && !lower.contains("умеешь") && !lower.contains("сможешь")) {
            if (lower.matches(".*\\\\b(спектатор|spectator)\\\\b.*")) {
                args.addProperty("mode", "spectator");
                name = "set_gamemode";
            } else if (lower.matches("^(включи|поставь|сделай|установи)?\\\\s*(креатив|creative)$")) {
                args.addProperty("mode", "creative");
                name = "set_gamemode";
            } else if (lower.matches("^(включи|поставь|сделай|установи)?\\\\s*(выживание|survival)$")) {
                args.addProperty("mode", "survival");
                name = "set_gamemode";
            } else if (lower.matches("^(сделай|поставь|включи)?\\\\s*(день|day)$")) {
                args.addProperty("time", "day");
                name = "set_time";
            } else if (lower.matches("^(сделай|поставь|включи)?\\\\s*(ночь|night)$")) {
                args.addProperty("time", "night");
                name = "set_time";
            } else if (lower.matches("^(включи|поставь)?\\\\s*(дождь|rain)$")) {
                args.addProperty("weather", "rain");
                name = "set_weather";
            } else if (lower.matches("^(включи|поставь)?\\\\s*(гроза|thunder)$")) {
                args.addProperty("weather", "thunder");
                name = "set_weather";
            } else if (lower.equals("ясно") || lower.equals("clear") || lower.matches("^(убери|выключи|останови|сделай)\\\\s*(дождь|грозу|погоду|солнечно|ясно)$")) {
                args.addProperty("weather", "clear");
                name = "set_weather";
            } else if (lower.matches("^(очисти|очистить|удали|стереть)\\\\s+(весь\\\\s+)?инвентар(ь|я)$")) {
                name = "clear_inventory";
            }
        }
        if (name == null) return null;
        final String actionName = name;
        return runOnServer(task, actionName, args).thenApply(result -> {
            if (!toolResultOk(result)) return "Не удалось выполнить действие: " + string(result, "error", "неизвестная ошибка");
            return localActionSummary(List.of(toolCallForSummary(actionName)));
        });
    }

    private static JsonObject toolCallForSummary(String name) {
        JsonObject call = new JsonObject();
        call.addProperty("name", name);
        return call;
    }
'''
    s = s.replace('    private static CompletableFuture<String> chatOpenAiCompatible', fast_path + '\n    private static CompletableFuture<String> chatOpenAiCompatible', 1)

if 'CompletableFuture<String> fastAction = tryFastAction(task, message);' not in s:
    s = s.replace(
        '        CompletableFuture<String> work;\n        try {\n            work = switch (AiClientConfig.getProvider()) {',
        '        CompletableFuture<String> work;\n        CompletableFuture<String> fastAction = tryFastAction(task, message);\n        try {\n            work = fastAction != null ? fastAction : switch (AiClientConfig.getProvider()) {',
        1,
    )

# Fast read/action summaries for the common new write tools are enough to skip confirmation calls.
s = s.replace(
    'case "spawn_entity" -> "Готово — существо создано.";',
    'case "spawn_entity" -> "Готово — существо создано.";'
)
s = s.replace(
    'case "spawn_entity" -> "Готово — существо создано.";\n                default -> "Готово.";',
    'case "spawn_entity" -> "Готово — существо создано.";\n                case "set_block" -> "Готово — блок установлен.";\n                case "fill_area" -> "Готово — область изменена.";\n                case "give_experience" -> "Готово — опыт выдан.";\n                case "set_spawnpoint" -> "Готово — точка возрождения установлена.";\n                default -> "Готово.";'
)

# Sanity checks before writing the generated Java source.
for required in [
    'private static Set<String> selectToolNames(String message)',
    'private static JsonArray openAiToolsArray(Set<String> selectedTools)',
    'Set<String> selectedTools = selectToolNames(message);',
    'private static CompletableFuture<String> tryFastAction',
    'case "get_player_state" -> getPlayerState(server, args);',
    'function("get_block"',
    'function("fill_area"',
    'function("find_entities"',
    'function("give_experience"',
    'function("set_spawnpoint"',
]:
    ensure(required, required)

PATH.write_text(s, encoding='utf-8')
print('AI service transformed: compact context, dynamic tool routing, fast paths, compact observation and extra world tools')
