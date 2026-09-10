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

PATH.write_text(s, encoding='utf-8')
print('AI routing tightened for player lists and entity removal requests')
