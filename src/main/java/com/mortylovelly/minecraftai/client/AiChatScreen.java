package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AiChatScreen extends Screen {
    private static final URI CHAT_URI = URI.create("http://127.0.0.1:8787/chat");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final String SESSION_ID = UUID.randomUUID().toString();

    private final List<String> messages = new ArrayList<>();
    private TextFieldWidget input;
    private ButtonWidget sendButton;
    private boolean waiting;

    public AiChatScreen() {
        super(Text.literal("Minecraft AI Agent"));
        messages.add("AI: Я готов. Напиши, что нужно сделать в мире.");
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(720, width - 40);
        int panelLeft = (width - panelWidth) / 2;
        int inputY = height - 42;

        input = new TextFieldWidget(
                textRenderer,
                panelLeft,
                inputY,
                panelWidth - 92,
                20,
                Text.literal("Сообщение")
        );
        input.setMaxLength(2000);
        input.setPlaceholder(Text.literal("Напиши команду для AI..."));
        addDrawableChild(input);

        sendButton = addDrawableChild(ButtonWidget.builder(
                        Text.literal("Отправить"),
                        button -> sendMessage()
                )
                .dimensions(panelLeft + panelWidth - 86, inputY, 86, 20)
                .build());

        setInitialFocus(input);
    }

    private void sendMessage() {
        if (waiting || input == null) {
            return;
        }

        String message = input.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        messages.add("Ты: " + message);
        input.setText("");
        waiting = true;
        sendButton.active = false;

        String json = "{\"message\":" + quoteJson(message)
                + ",\"session_id\":" + quoteJson(SESSION_ID) + "}";
        HttpRequest request = HttpRequest.newBuilder(CHAT_URI)
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        CompletableFuture<HttpResponse<String>> future = HTTP.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        future.handle((response, throwable) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> {
                waiting = false;
                sendButton.active = true;

                if (throwable != null) {
                    messages.add("AI: Не удалось подключиться к backend. Запусти backend/start.bat.");
                    return;
                }

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    messages.add("AI: Backend вернул ошибку " + response.statusCode() + ".");
                    return;
                }

                try {
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    String reply = root.has("reply") ? root.get("reply").getAsString() : "Пустой ответ от AI.";
                    messages.add("AI: " + reply);
                } catch (RuntimeException exception) {
                    messages.add("AI: Получен некорректный ответ от backend.");
                }
            });
            return null;
        });
    }

    private static String quoteJson(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> result.append(character);
            }
        }
        return result.append('"').toString();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        int panelWidth = Math.min(720, width - 40);
        int panelLeft = (width - panelWidth) / 2;
        int panelTop = 28;
        int panelBottom = height - 52;

        context.fill(panelLeft - 10, panelTop - 10, panelLeft + panelWidth + 10, panelBottom + 10, 0xE0101010);
        context.drawTextWithShadow(textRenderer, title, panelLeft, panelTop, 0xFFFFFF);

        int y = panelTop + 20;
        int maxY = panelBottom - 8;
        int start = Math.max(0, messages.size() - 12);

        for (int i = start; i < messages.size(); i++) {
            String[] lines = wrap(messages.get(i), panelWidth - 8);
            for (String line : lines) {
                if (y > maxY) {
                    break;
                }
                context.drawTextWithShadow(textRenderer, line, panelLeft, y, 0xE0E0E0);
                y += 12;
            }
            y += 4;
        }

        if (waiting) {
            context.drawTextWithShadow(textRenderer, "AI выполняет задачу...", panelLeft, panelBottom - 4, 0xA0FFA0);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private String[] wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (textRenderer.getWidth(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
            } else {
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                }
                current.setLength(0);
                current.append(word);
            }
        }

        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines.toArray(String[]::new);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 && input != null && input.isFocused()) {
            sendMessage();
            return true;
        }
        if (keyCode == 256) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(null);
    }
}
