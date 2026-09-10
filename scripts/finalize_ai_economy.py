from pathlib import Path

PATH = Path('src/main/java/com/mortylovelly/minecraftai/client/AiAgentService.java')
s = PATH.read_text(encoding='utf-8')

# Make the local fast-path lambda Java-safe by capturing an effectively-final action name.
old = '''        if (name == null) return null;
        return runOnServer(task, name, args).thenApply(result -> {
            if (!toolResultOk(result)) return "Не удалось выполнить действие: " + string(result, "error", "неизвестная ошибка");
            return localActionSummary(List.of(toolCallForSummary(name)));
        });'''
new = '''        if (name == null) return null;
        String actionName = name;
        return runOnServer(task, actionName, args).thenApply(result -> {
            if (!toolResultOk(result)) return "Не удалось выполнить действие: " + string(result, "error", "неизвестная ошибка");
            return localActionSummary(List.of(toolCallForSummary(actionName)));
        });'''
if old in s:
    s = s.replace(old, new)

# Any non-test Gemini request that still uses the helper gets the same compact request-specific tool selection.
s = s.replace('if (!noTools) payload.add("tools", geminiToolsArray());', 'if (!noTools) payload.add("tools", geminiToolsArray(selectToolNames(input)));')

# Preserve the new limits even if another normalization pass touched the file.
s = s.replace('private static final int MAX_TOOL_ROUNDS = 6;', 'private static final int MAX_TOOL_ROUNDS = 4;')
s = s.replace('private static final int MAX_TOOL_CALLS = 16;', 'private static final int MAX_TOOL_CALLS = 10;')
s = s.replace('private static final int MAX_TOOL_RESULT_CHARS = 7000;', 'private static final int MAX_TOOL_RESULT_CHARS = 1800;')
s = s.replace('private static final int MAX_ENTITY_RESULTS = 60;', 'private static final int MAX_ENTITY_RESULTS = 20;')

# If a stale unfiltered tool-array helper somehow remains, remove it.
start = s.find('    private static JsonArray geminiToolsArray() {')
if start >= 0:
    end = s.find('    private static final List<JsonObject> TOOLS = createTools();', start)
    if end >= 0:
        s = s[:start] + s[end:]

PATH.write_text(s, encoding='utf-8')
print('Final AI economy fixes applied')
