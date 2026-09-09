package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class AiAgentService {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private AiAgentService() {}

    public static CompletableFuture<String> chat(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("message", message);

        HttpRequest request = HttpRequest.newBuilder(URI.create(AiClientConfig.BACKEND_URL + "/chat"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            return CompletableFuture.completedFuture(
                                    json.has("reply") ? json.get("reply").getAsString() : "Backend вернул пустой ответ."
                            );
                        } catch (RuntimeException exception) {
                            return CompletableFuture.failedFuture(
                                    new IOException("Неверный ответ backend: " + exception.getMessage())
                            );
                        }
                    }
                    return CompletableFuture.failedFuture(
                            new IOException("AI backend " + response.statusCode() + ": " + extractError(response.body()))
                    );
                });
    }

    public static CompletableFuture<String> testBackend() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(AiClientConfig.BACKEND_URL + "/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            JsonObject bridge = json.has("bridge") && json.get("bridge").isJsonObject()
                                    ? json.getAsJsonObject("bridge")
                                    : new JsonObject();
                            boolean connected = bridge.has("connected") && bridge.get("connected").getAsBoolean();
                            String player = bridge.has("player") ? bridge.get("player").getAsString() : "unknown";
                            return CompletableFuture.completedFuture(
                                    connected
                                            ? "Backend работает. Minecraft подключён (игрок: " + player + ")."
                                            : "Backend работает, но Minecraft bridge не подключён."
                            );
                        } catch (RuntimeException exception) {
                            return CompletableFuture.failedFuture(
                                    new IOException("Неверный ответ backend: " + exception.getMessage())
                            );
                        }
                    }
                    return CompletableFuture.failedFuture(
                            new IOException("Backend " + response.statusCode() + ": " + extractError(response.body()))
                    );
                });
    }

    private static String extractError(String body) {
        if (body == null || body.isBlank()) return "пустой ответ";
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("detail")) return json.get("detail").getAsString();
            if (json.has("error")) return json.get("error").getAsString();
        } catch (RuntimeException ignored) {
        }
        return body.length() > 1000 ? body.substring(0, 1000) : body;
    }
}
