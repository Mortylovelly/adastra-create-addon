from pathlib import Path
import re

PATH = Path('src/main/java/com/mortylovelly/minecraftai/client/AiAgentService.java')
text = PATH.read_text(encoding='utf-8')
original = text

# Fix the known Minecraft 1.21.1 command API signature.
text = text.replace(
    '            int result = server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);',
    '            server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);',
    1,
)
text = text.replace('            data.addProperty("result", result);\n', '', 1)

# Replace the accidentally duplicated system prompt with one compact prompt.
prompt = '''    private static final String INSTRUCTIONS = """
            You are the AI agent inside a Minecraft 1.21.1 singleplayer world.
            You control the current world only through the provided Minecraft tools.
            Never claim a world action happened unless a tool result confirms it.
            For normal conversation or a question about whether you can do something, answer directly and do not use tools.
            Only use tools for an actual requested world action.
            For an actual building request, call get_player_state once, choose a compact safe location near the player, and build efficiently.
            Do not call get_block or scan_area unless the terrain or an exact existing block actually matters.
            Prefer fill_area for large rectangles and place_blocks for small details.
            Do not repeat an identical successful tool call.
            Do not perform destructive actions unless explicitly requested.
            Only use remember_memory or forget_memory when the player explicitly requests memory changes.
            If a tool fails, tell the player clearly instead of pretending it worked.
            After the requested action is confirmed, stop using tools and give a concise final answer.
            """.strip();'''
text, count = re.subn(
    r'    private static final String INSTRUCTIONS = """.*?"""\.strip\(\);',
    prompt,
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit('Could not find INSTRUCTIONS block')

# Smaller limits are much safer for free providers and prevent unnecessary API bursts.
text = text.replace('    private static final int MAX_TOOL_ROUNDS = 6;', '    private static final int MAX_TOOL_ROUNDS = 4;')
text = text.replace('    private static final int MAX_TOOL_CALLS = 12;', '    private static final int MAX_TOOL_CALLS = 8;')
text = text.replace('    private static final int API_HISTORY_MESSAGES = 8;', '    private static final int API_HISTORY_MESSAGES = 6;')
text = text.replace('    private static final int API_HISTORY_CHARS = 6000;', '    private static final int API_HISTORY_CHARS = 3500;')
text = text.replace('    private static final int MAX_TOOL_RESULT_CHARS = 1800;', '    private static final int MAX_TOOL_RESULT_CHARS = 1200;')

# Remove duplicate diagnostics left by previous CI mutations.
def dedupe_exact_calls(source, pattern):
    return re.sub(pattern, r'\1', source, flags=re.M)

text = dedupe_exact_calls(
    text,
    r'^(\s*AiAgentStatus\.set\([^\n]+\);)\n(?=\s*AiAgentStatus\.set\([^\n]+\);)',
)
text = dedupe_exact_calls(
    text,
    r'^(\s*AiAgentLog\.info\([^\n]+\);)\n(?=\s*AiAgentLog\.info\([^\n]+\);)',
)
text = dedupe_exact_calls(
    text,
    r'^(\s*AiAgentLog\.error\([^\n]+\);)\n(?=\s*AiAgentLog\.error\([^\n]+\);)',
)
text = dedupe_exact_calls(
    text,
    r'^(\s*AiAgentStatus\.clear\(\);)\n(?=\s*AiAgentStatus\.clear\(\);)',
)

# A successful tool result normally has no `ok` property. The old diagnostic interpreted that as false.
text = text.replace(
    'toolName + " ok=" + toolResult.has("ok") + ""',
    'toolName + " ok=" + (!toolResult.has("ok") || toolResult.get("ok").getAsBoolean()) + ""',
    1,
)

# Retry HTTP 429 according to Retry-After. Network failures keep the existing retry behavior.
start = text.index('    private static CompletableFuture<HttpResponse<String>> sendWithRetry(')
end = text.index('    private static void logNetworkDiagnostics(', start)
retry_method = '''    private static CompletableFuture<HttpResponse<String>> sendWithRetry(
            HttpRequest request, String providerName, int attempt) {
        long started = System.nanoTime();
        System.out.println("[Minecraft AI Agent][" + providerName + "] HTTP START attempt="
                + (attempt + 1) + "/" + (NETWORK_RETRIES + 1));

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    System.out.println("[Minecraft AI Agent][" + providerName + "] HTTP RESPONSE status="
                            + response.statusCode() + " in=" + elapsed + " ms");

                    if (response.statusCode() == 429 && attempt < NETWORK_RETRIES) {
                        String retryAfter = response.headers().firstValue("retry-after").orElse("");
                        long delay = parseRetryAfterSeconds(retryAfter, attempt);
                        System.err.println("[Minecraft AI Agent][" + providerName + "] RATE LIMIT retry-after="
                                + (retryAfter.isBlank() ? "unknown" : retryAfter));
                        System.out.println("[Minecraft AI Agent][" + providerName + "] RETRY after " + delay + " s");
                        return CompletableFuture.supplyAsync(
                                () -> null,
                                CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS))
                                .thenCompose(ignored -> sendWithRetry(request, providerName, attempt + 1));
                    }
                    return CompletableFuture.completedFuture(response);
                })
                .exceptionallyCompose(throwable -> {
                    Throwable cause = rootCause(throwable);
                    AiAgentLog.error("HTTP FAIL provider=" + providerName + " type="
                            + cause.getClass().getSimpleName() + " message=" + String.valueOf(cause.getMessage()));
                    System.err.println("[Minecraft AI Agent][" + providerName + "] HTTP FAIL type="
                            + cause.getClass().getName() + " message=" + String.valueOf(cause.getMessage()));

                    boolean retryable = cause instanceof ConnectException || cause instanceof HttpTimeoutException;
                    if (!retryable || attempt >= NETWORK_RETRIES) {
                        return CompletableFuture.failedFuture(
                                new IOException(providerName + " connection failed: " + cause.getMessage(), cause));
                    }

                    long delay = 2L * (attempt + 1);
                    System.out.println("[Minecraft AI Agent][" + providerName + "] RETRY after " + delay + " s");
                    return CompletableFuture.supplyAsync(
                            () -> null,
                            CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS))
                            .thenCompose(ignored -> sendWithRetry(request, providerName, attempt + 1));
                });
    }

    private static long parseRetryAfterSeconds(String header, int attempt) {
        try {
            long seconds = Long.parseLong(header.trim());
            return Math.max(1L, Math.min(30L, seconds));
        } catch (NumberFormatException ignored) {
            return Math.min(15L, 4L * (attempt + 1));
        }
    }

'''
text = text[:start] + retry_method + text[end:]

# If the selected Gemma free endpoint is rate-limited, retry through OpenRouter's free router.
fallback = '''                    if (response.statusCode() == 429
                            && "OpenRouter".equals(providerName)
                            && payload.has("model")
                            && OPENROUTER_MODEL.equals(payload.get("model").getAsString())) {
                        JsonObject fallbackPayload = payload.deepCopy();
                        fallbackPayload.addProperty("model", "openrouter/free");
                        System.out.println("[Minecraft AI Agent][OpenRouter] Gemma free endpoint is rate-limited; trying openrouter/free");
                        return request(OPENROUTER_URI, apiKey, fallbackPayload, "OpenRouter");
                    }

'''
marker = '                    String errorText;\n'
if fallback not in text:
    idx = text.index(marker)
    text = text[:idx] + fallback + text[idx:]

PATH.write_text(text, encoding='utf-8')

print('Changed:', text != original)
print('Broken command assignment remains:', 'int result = server.getCommandManager().executeWithPrefix' in text)
print('Duplicate prompt markers:', text.count('If no available tool can perform a requested action'))
