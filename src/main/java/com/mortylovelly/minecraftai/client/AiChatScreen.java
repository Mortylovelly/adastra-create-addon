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
        super(Text.literal("Minecraft AI"));
        messages.add("AI: Я готов. Напиши, что нужно сделать в мире.");
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(600, width - 40);
        int panelHeight = Math.min(360, height - 40);
        int panelLeft = (width - panelWidth) / 2;
        int panelTop = (height - panelHeight) / 2;
        int inputY = panelTop + panelHeight - 32;

        input = new TextFieldWidget(
                textRenderer,
                panelLeft + 12,
                inputY,
                panelWidth - 104,
                20,
                Text.literal("Сообщение")
        );
        input.setMaxLength(2000);
        input.setPlaceholder(Text.literal("Напиши, что сделать в мире..."));
        addDrawableChild(input);

        sendButton = addDrawableChild(ButtonWidget.builder(
                        Text.literal("Отправить"),
                        button -> sendMessage()
                )
                .dimensions(panelLeft + panelWidth - 84, inputY, 72, 20)
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
                if (sendButton != null) {
                    sendButton.active = true;
                }

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
                    String reply = root.has("reply")
                            ? root.get("reply").getAsString()
                            : "Пустой ответ от AI.";
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
        // Intentionally do not call Screen.renderBackground(): that method
        // can blur the entire Minecraft scene behind the chat panel.
        context.fill(0, 0, width, height, 0x65000000);

        int panelWidth = Math.min(600, width - 40);
        int panelHeight = Math.min(360, height - 40);
        int panelLeft = (width - panelWidth) / 2;
        int panelTop = (height - panelHeight) / 2;
        int panelBottom = panelTop + panelHeight;

        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelBottom, 0xE5101010);
        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 1, 0xFFFFFFFF);

        context.drawTextWithShadow(
                textRenderer,
                title,
                panelLeft + 12,
                panelTop + 10,
                0xFFFFFF
        );

        int chatTop = panelTop + 32;
        int chatBottom = panelBottom - 42;
        int y = chatBottom - 4;
        int maxWidth = panelWidth - 24;

        List<String> renderedLines = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            String[] wrapped = wrap(messages.get(i), maxWidth);
            for (int line = wrapped.length - 1; line >= 0; line--) {
                renderedLines.add(0, wrapped[line]);
            }
            renderedLines.add(0, "");

            if (renderedLines.size() >= 24) {
                break;
            }
        }

        for (int i = renderedLines.size() - 1; i >= 0; i--) {
            String line = renderedLines.get(i);
            int lineHeight = line.isEmpty() ? 4 : 12;
            y -= lineHeight;
            if (y < chatTop) {
                break;
            }
            if (!line.isEmpty()) {
                context.drawTextWithShadow(
                        textRenderer,
                        line,
                        panelLeft + 12,
                        y,
                        0xE8E8E8
                );
            }
        }

        if (waiting) {
            context.drawTextWithShadow(
                    textRenderer,
                    "AI выполняет задачу...",
                    panelLeft + 12,
                    panelBottom - 48,
                    0xA0FFA0
            );
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
