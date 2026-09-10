from pathlib import Path

PATH = Path('src/main/java/com/mortylovelly/minecraftai/client/AiAgentService.java')
s = PATH.read_text(encoding='utf-8')

s = s.replace(
    'case "deepseek" -> chatDeepSeek(task, message);',
    '''case "deepseek" -> {\n                    JsonObject payload = new JsonObject();\n                    payload.addProperty("model", DEEPSEEK_MODEL);\n                    payload.addProperty("instructions", INSTRUCTIONS + "\\n\\nPersistent memory for this chat:\\n" + AiAgentMemory.forPrompt(task.chatId));\n                    payload.addProperty("input", message);\n                    payload.addProperty("max_output_tokens", 4096);\n                    yield requestJson(DEEPSEEK_URI, AiClientConfig.getDeepSeekApiKey(), payload, "DeepSeek")\n                            .thenApply(response -> {\n                                if (task.cancelled) return "Запрос остановлен пользователем.";\n                                String text = string(response, "output_text", "");\n                                AiAgentStatus.clear();\n                                return text.isBlank() ? "DeepSeek не вернул текстовый ответ." : text;\n                            });\n                }'''
)

s = s.replace('AiAgentStatus.set(statusForTool(name));', 'AiAgentStatus.set("Выполняю действие");')
s = s.replace(
    'AiAgentStatus.set(statusForTool(name));\n                        results.add(executeOpenAiToolAsync(task, call, cache));',
    'AiAgentStatus.set("Выполняю действие");\n                        results.add(executeOpenAiToolAsync(task, call, cache));'
)

s = s.replace('totalRequested += Math.min(volume, Integer.MAX_VALUE);', 'totalRequested += (int) Math.min(volume, (long) Integer.MAX_VALUE);')

old = '''String raw = string(function, "arguments", "{}");\n        JsonObject args;\n        try { args = JsonParser.parseString(raw).getAsJsonObject(); }\n        catch (RuntimeException exception) { args = new JsonObject(); }'''
new = '''JsonObject args;\n        JsonElement argumentElement = function.get("arguments");\n        try {\n            if (argumentElement != null && argumentElement.isJsonObject()) args = argumentElement.getAsJsonObject().deepCopy();\n            else if (argumentElement != null && argumentElement.isJsonPrimitive()) args = JsonParser.parseString(argumentElement.getAsString()).getAsJsonObject();\n            else args = new JsonObject();\n        } catch (RuntimeException exception) {\n            args = new JsonObject();\n        }'''
s = s.replace(old, new)

old = 'if (!name.isBlank()) components.add("custom_name=\'" + new com.google.gson.Gson().toJson(new JsonObject()) + "\'");'
new = '''if (!name.isBlank()) {\n            JsonObject nameObject = new JsonObject();\n            nameObject.addProperty("text", name);\n            components.add("custom_name='" + new com.google.gson.Gson().toJson(nameObject) + "'");\n        }'''
s = s.replace(old, new)

old = 'return lower.endsWith("?") && (lower.contains("можешь") || lower.contains("сможешь") || lower.contains("умеешь") || lower.contains("можно ли") || lower.contains("способен") || lower.contains("умеет ли"));'
new = '''return (lower.endsWith("?") && (lower.contains("можешь") || lower.contains("сможешь") || lower.contains("умеешь") || lower.contains("можно ли") || lower.contains("способен") || lower.contains("умеет ли")))\n                || lower.startsWith("можешь ли") || lower.startsWith("сможешь ли") || lower.startsWith("умеешь ли");'''
s = s.replace(old, new)

PATH.write_text(s, encoding='utf-8')
print('AI service normalized')
