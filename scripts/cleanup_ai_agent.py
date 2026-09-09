from pathlib import Path
import re

path = Path('src/main/java/com/mortylovelly/minecraftai/client/AiAgentService.java')
text = path.read_text(encoding='utf-8')
original = text

# Split statements that were accidentally concatenated onto one Java line by older CI mutations.
text = re.sub(r';[ \t]+(?=(AiAgentStatus\.set\(|AiAgentStatus\.clear\(|AiAgentLog\.info\(|AiAgentLog\.error\())', ';\n        ', text)

# Collapse immediately repeated identical diagnostic statements while keeping valid line breaks.
patterns = [
    r'(?m)^(\s*AiAgentStatus\.set\([^\n]+\);)\n(?:\s*\1\n)+',
    r'(?m)^(\s*AiAgentStatus\.clear\(\);)\n(?:\s*\1\n)+',
    r'(?m)^(\s*AiAgentLog\.info\([^\n]+\);)\n(?:\s*\1\n)+',
    r'(?m)^(\s*AiAgentLog\.error\([^\n]+\);)\n(?:\s*\1\n)+',
]
for pattern in patterns:
    text = re.sub(pattern, r'\1\n', text)

# Never leave the old compile-breaking command assignment behind.
text = text.replace(
    '            int result = server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);',
    '            server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);',
)
text = text.replace('            data.addProperty("result", result);\n', '')

path.write_text(text, encoding='utf-8')
print('Cleanup changed source:', text != original)
print('Broken command assignment remains:', 'int result = server.getCommandManager().executeWithPrefix' in text)
