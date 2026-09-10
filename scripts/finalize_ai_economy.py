from pathlib import Path
import re

PATH = Path('src/main/java/com/mortylovelly/minecraftai/client/AiAgentService.java')
s = PATH.read_text(encoding='utf-8')

# Preserve the already-tested compact architecture while adding two routing fixes.
needle = '        if (containsAny(lower, "напиши в чат", "сообщен", "скажи всем")) selected.add("send_chat");'
replacement = '''        if (containsAny(lower, "онлайн", "игроки", "кто играет", "кто на сервере", "кто здесь")) selected.add("list_players");
        if (containsAny(lower, "убей", "убить", "убирай моб", "удали моб", "зомби уб", "скелет уб", "крипер уб")) selected.add("run_minecraft_command");
        if (containsAny(lower, "напиши в чат", "сообщен", "скажи всем")) selected.add("send_chat");'''
if needle in s:
    s = s.replace(needle, replacement, 1)

# Improve the default tool set with the player-list reader, keeping it compact.
s = s.replace(
    'selected.addAll(Set.of("get_player_state", "observe_world", "run_minecraft_command", "give_item", "teleport_player", "set_gamemode", "set_time", "set_weather"));',
    'selected.addAll(Set.of("get_player_state", "list_players", "observe_world", "run_minecraft_command", "give_item", "teleport_player", "set_gamemode", "set_time", "set_weather"));',
    1,
)

# Make the extra tool schema descriptions short to keep per-request payloads small.
s = s.replace('"Find nearby entities with compact positions."', '"Find nearby entities compactly."')
s = s.replace('"Read compact player position, health, food, mode and dimension."', '"Read compact player state."')

# Repair source damage left by the previous automated transform.
s = s.replace('        return array;\n    }\n        return array;\n    }\n\n\n    private static Set<String> selectToolNames',
                '        return array;\n    }\n\n    private static Set<String> selectToolNames',
                1)
s = s.replace('result.add(chatMessage("system", INSTRUCTIONS + "\nMemory:" + memory));',
              'result.add(chatMessage("system", INSTRUCTIONS + "\\nMemory:" + memory));',
              1)

# Fix the Gemini follow-up scope: that method does not receive selectedTools.
s = s.replace('if (!noTools) payload.add("tools", geminiToolsArray(selectedTools));',
              'if (!noTools) payload.add("tools", geminiToolsArray());',
              1)

PATH.write_text(s, encoding='utf-8')
print('AI routing tightened and source normalization repaired')
