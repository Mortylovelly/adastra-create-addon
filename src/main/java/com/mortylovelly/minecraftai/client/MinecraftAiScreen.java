package com.mortylovelly.minecraftai.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinecraftAiScreen extends Screen {
    private static final URI CHAT_URI = URI.create("http://127.0.0.1:8787/chat");
    private static final HttpClient HTTP = HttpClient.newBuilder().build();

    private final List<ChatLine> messages = new ArrayList<>();
    private final String sessionId = UUID.randomUUID().toString();
    private TextFieldWidget input;
    private ButtonWidget sendButton;
    private boolean waitingForReply;

    public MinecraftAiScreen() {
        super(Text.literal("Minecraft AI Agent"));
    }

    @Override
    protected void init() {
        super.init();

        int panelWidth = Math.min(760, width - 40);
        int panelLeft = (width - panelWidth) / 2;
        int inputWidth = panelWidth - 96;

        input = new TextFieldWidget(
                textRenderer,
                panelLeft + 12,
                height - 38,
                inputWidth,
                24,
                Text.literal("Message")
        );
        input.setMaxLength(2000);
        input.setPlaceholder(Text.literal("Напиши AI, что сделать в мире..."));
        addSelectableChild(input);

        sendButton = ButtonWidget.builder(
                Text.literal("Отправить"),
                button -> sendCurrentMessage()
        ).dimensions(panelLeft + 18 + inputWidth, height - 38, 66, 24).build();
        addDrawableChild(sendButton);

        setInitialFocus(input);
    }

    @Override
    public void tick() {
        super.tick();
        if (input != null) {
            input.tick();
        }
    }

    private void sendCurrentMessage() {
        if (waitingForReply || input == null) {
            return;
        }

        String message = input.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        messages.add(new ChatLine(true, message));
        input.setText("");
        waitingForReply = true;
        sendButton.active = false;
        messages.add(new ChatLine(false, "Думаю и выполняю действия в мире..."));

        String body = "{\"message\":" + quoteJson(message)
                + ",\"session_id\":" + quoteJson(sessionId) + "}";

        HttpRequest request = HttpRequest.newBuilder(CHAT_URI)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<String> response = HTTP.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );
                return parseReply(response.statusCode(), response.body());
            } catch (IOException exception) {
                return "Не удалось подключиться к AI backend: " + exception.getMessage();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return "Запрос к AI был прерван.";
            }
        }).thenAccept(reply -> MinecraftClient.getInstance().execute(() -> {
            if (!messages.isEmpty()) {
                messages.remove(messages.size() - 1);
            }
            messages.add(new ChatLine(false, reply));
            waitingForReply = false;
            sendButton.active = true;
            input.setFocused(true);
        }));
    }

    private static String parseReply(int status, String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (status >= 200 && status < 300) {
                return json.has("reply") ? json.get("reply").getAsString() : "AI вернул пустой ответ.";
            }
            return json.has("detail")
                    ? "Ошибка: " + json.get("detail").getAsString()
                    : "Ошибка AI backend: HTTP " + status;
        } catch (RuntimeException exception) {
            return "Ошибка AI backend: HTTP " + status;
        }
    }

    private static String quoteJson(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                + "\"";
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        int panelWidth = Math.min(760, width - 40);
        int panelLeft = (width - panelWidth) / 2;
        int panelTop = 28;
        int panelBottom = height - 52;
        int chatLeft = panelLeft + 12;
        int chatWidth = panelWidth - 24;

        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelBottom, 0xE6101014);
        context.drawBorder(panelLeft, panelTop, panelWidth, panelBottom - panelTop, 0xFF555A66);
        context.drawTextWithShadow(textRenderer, title, chatLeft, 12, 0xFFFFFFFF);
        context.drawTextWithShadow(
                textRenderer,
                Text.literal("O — открыть/закрыть | команды выполняются прямо в мире, без игрового чата"),
                chatLeft,
                panelTop - 15,
                0xFFAAAAAA
        );

        int y = panelBottom - 10;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatLine line = messages.get(i);
            List<OrderedText> wrapped = textRenderer.wrapLines(
                    Text.literal((line.user ? "Ты: " : "AI: ") + line.text),
                    chatWidth
            );
            for (int j = wrapped.size() - 1; j >= 0; j--) {
                y -= textRenderer.fontHeight + 3;
                if (y >= panelTop + 8 && y <= panelBottom - 4) {
                    context.drawTextWithShadow(
                            textRenderer,
                            wrapped.get(j),
                            chatLeft,
                            y,
                            line.user ? 0xFFFFFFFF : 0xFFD6E7FF
                    );
                }
            }
            y -= 7;
            if (y < panelTop - 40) {
                break;
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            sendCurrentMessage();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private record ChatLine(boolean user, String text) {
    }
}
