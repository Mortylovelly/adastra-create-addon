from pathlib import Path

PATH = Path('src/main/java/com/mortylovelly/minecraftai/client/AiAgentService.java')
s = PATH.read_text(encoding='utf-8')

# DeepSeek must use the same OpenAI-compatible agent loop as Groq/OpenRouter.
chat_uri_line = '    private static final URI DEEPSEEK_CHAT_URI = URI.create("https://api.deepseek.com/chat/completions");'
s = s.replace(chat_uri_line + '\n' + chat_uri_line, chat_uri_line)
if chat_uri_line not in s:
    s = s.replace(
        '    private static final URI DEEPSEEK_URI = URI.create("https://api.deepseek.com/responses");',
        '    private static final URI DEEPSEEK_URI = URI.create("https://api.deepseek.com/responses");\n' + chat_uri_line
    )

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
s = s.replace('case "deepseek" -> chatOpenAiCompatible(task, message, "deepseek");', 'case "deepseek" -> chatOpenAiCompatible(task, message, "deepseek");')

# Keep the connection test, but make it use the same Chat Completions protocol.
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

# Make the provider routing shared with the common OpenAI-compatible loop.
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

# Preserve existing fixes from earlier normalization passes.
s = s.replace('AiAgentStatus.set(statusForTool(name));', 'AiAgentStatus.set("Выполняю действие");')
s = s.replace(
    'AiAgentStatus.set(statusForTool(name));\n                        results.add(executeOpenAiToolAsync(task, call, cache));',
    'AiAgentStatus.set("Выполняю действие");\n                        results.add(executeOpenAiToolAsync(task, call, cache));'
)
s = s.replace('totalRequested += Math.min(volume, Integer.MAX_VALUE);', 'totalRequested += (int) Math.min(volume, (long) Integer.MAX_VALUE);')

old = '''String raw = string(function, "arguments", "{}");
        JsonObject args;
        try { args = JsonParser.parseString(raw).getAsJsonObject(); }
        catch (RuntimeException exception) { args = new JsonObject(); }'''
new = '''JsonObject args;
        JsonElement argumentElement = function.get("arguments");
        try {
            if (argumentElement != null && argumentElement.isJsonObject()) args = argumentElement.getAsJsonObject().deepCopy();
            else if (argumentElement != null && argumentElement.isJsonPrimitive()) args = JsonParser.parseString(argumentElement.getAsString()).getAsJsonObject();
            else args = new JsonObject();
        } catch (RuntimeException exception) {
            args = new JsonObject();
        }'''
s = s.replace(old, new)

old = 'if (!name.isBlank()) components.add("custom_name=\\\'" + new com.google.gson.Gson().toJson(new JsonObject()) + "\\\'");'
new = '''if (!name.isBlank()) {
            JsonObject nameObject = new JsonObject();
            nameObject.addProperty("text", name);
            components.add("custom_name='" + new com.google.gson.Gson().toJson(nameObject) + "'");
        }'''
s = s.replace(old, new)

old = 'return lower.endsWith("?") && (lower.contains("можешь") || lower.contains("сможешь") || lower.contains("умеешь") || lower.contains("можно ли") || lower.contains("способен") || lower.contains("умеет ли"));'
new = '''return (lower.endsWith("?") && (lower.contains("можешь") || lower.contains("сможешь") || lower.contains("умеешь") || lower.contains("можно ли") || lower.contains("способен") || lower.contains("умеет ли")))
                || lower.startsWith("можешь ли") || lower.startsWith("сможешь ли") || lower.startsWith("умеешь ли");'''
s = s.replace(old, new)

PATH.write_text(s, encoding='utf-8')
print('AI service normalized with shared DeepSeek tool loop')
