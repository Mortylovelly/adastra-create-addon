package com.mortylovelly.minecraftai.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class AiChatScreen extends Screen {
    private final List<String> messages = new ArrayList<>();
    private TextFieldWidget messageInput;
    private ButtonWidget sendButton;
    private boolean waiting;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public AiChatScreen() {
        super(Text.literal("Minecraft AI Agent"));
        messages.add("AI: Готов. Я могу выполнять действия в мире через инструменты.");
        messages.add("AI: Например: «выдай мне алмазный меч» или «построй дом рядом со мной».");
    }

    @Override
    protected void init() {
        panelWidth = Math.min(860, width - 24);
        panelHeight = Math.min(500, height - 24);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        int inputY = panelTop + panelHeight - 34;
        messageInput = new TextFieldWidget(textRenderer, panelLeft + 14, inputY,
                panelWidth - 116, 22, Text.literal("Сообщение"));
        messageInput.setMaxLength(4000);
        messageInput.setPlaceholder(Text.literal("Напиши, что сделать в Minecraft..."));
        addDrawableChild(messageInput);

        sendButton = addDrawableChild(ButtonWidget.builder(
                        Text.literal("Отправить"), button -> sendMessage())
                .dimensions(panelLeft + panelWidth - 92, inputY, 78, 22)
                .build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("Проверить AI"), button -> testConnection())
                .dimensions(panelLeft + panelWidth - 126, panelTop + 9, 112, 20)
                .build());

        setInitialFocus(messageInput);
    }

    private void testConnection() {
        if (waiting) return;
        waiting = true;
        messages.add("AI: Проверяю подключение к backend...");
        AiAgentService.testBackend().handle((reply, throwable) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> {
                waiting = false;
                if (throwable != null) {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    messages.add("AI: Ошибка: " + cause.getMessage());
                } else {
                    messages.add("AI: " + reply);
                }
            });
            return null;
        });
    }

    private void sendMessage() {
        if (waiting || messageInput == null) return;

        String message = messageInput.getText().trim();
        if (message.isEmpty()) return;

        messages.add("Ты: " + message);
        messageInput.setText("");
        waiting = true;
        sendButton.active = false;

        CompletableFuture<String> future = AiAgentService.chat(message);
        future.handle((reply, throwable) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> {
                waiting = false;
                if (sendButton != null) sendButton.active = true;

                if (throwable != null) {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    messages.add("AI: Ошибка: " + cause.getMessage());
                } else {
                    messages.add("AI: " + reply);
                }
            });
            return null;
        });
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x55000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xF0121216);
        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 1, 0xFFFFFFFF);
        context.fill(panelLeft, panelTop + 39, panelLeft + panelWidth, panelTop + 40, 0xFF303038);

        context.drawTextWithShadow(textRenderer, title, panelLeft + 14, panelTop + 11, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                waiting ? "ИИ выполняет действия в мире..." : "Подключён через локальный AI backend",
                panelLeft + 14, panelTop + 46,
                waiting ? 0xA0FFA0 : 0xB0B0B8);

        int chatTop = panelTop + 65;
        int chatBottom = panelTop + panelHeight - 46;
        int maxWidth = panelWidth - 28;
        int y = chatBottom;

        outer:
        for (int i = messages.size() - 1; i >= 0; i--) {
            String[] lines = wrap(messages.get(i), maxWidth);
            for (int line = lines.length - 1; line >= 0; line--) {
                y -= 13;
                if (y < chatTop) break outer;
                context.drawTextWithShadow(textRenderer, lines[line], panelLeft + 14, y, 0xE8E8E8);
            }
            y -= 7;
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private String[] wrap(String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (textRenderer.getWidth(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
            } else {
                if (!current.isEmpty()) result.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (!current.isEmpty()) result.add(current.toString());
        if (result.isEmpty()) result.add("");
        return result.toArray(String[]::new);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 && messageInput != null && messageInput.isFocused()) {
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
