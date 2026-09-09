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
    private TextFieldWidget apiKeyInput;
    private ButtonWidget sendButton;
    private ButtonWidget deepSeekButton;
    private ButtonWidget groqButton;
    private ButtonWidget openRouterButton;
    private ButtonWidget testButton;
    private ButtonWidget clearChatButton;
    private boolean waiting;
    private String liveStatus = "";
    private long statusAnimationTick;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public AiChatScreen() {
        super(Text.literal("Minecraft AI Agent"));
        AiChatHistory.load();
        rebuildVisibleHistory();
    }

    @Override
    protected void init() {
        AiClientConfig.load();
        AiChatHistory.load();
        rebuildVisibleHistory();
        AiAgentStatus.setListener(status -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> liveStatus = status == null ? "" : status);
        });

        panelWidth = Math.min(860, width - 24);
        panelHeight = Math.min(500, height - 24);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        int keyY = panelTop + 31;
        apiKeyInput = new TextFieldWidget(
                textRenderer, panelLeft + 14, keyY,
                panelWidth - 150, 20, Text.literal("API key")
        );
        apiKeyInput.setMaxLength(300);
        apiKeyInput.setText(AiClientConfig.getApiKey());
        apiKeyInput.setPlaceholder(Text.literal("API key выбранного провайдера — сохраняется локально"));
        addDrawableChild(apiKeyInput);

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("Сохранить key"), button -> saveApiKey())
                .dimensions(panelLeft + panelWidth - 126, keyY, 112, 20)
                .build());

        int providerY = panelTop + 57;
        deepSeekButton = addDrawableChild(ButtonWidget.builder(
                        Text.literal("DeepSeek"), button -> selectProvider("deepseek"))
                .dimensions(panelLeft + 14, providerY, 108, 20)
                .build());

        groqButton = addDrawableChild(ButtonWidget.builder(
                        Text.literal("Groq"), button -> selectProvider("groq"))
                .dimensions(panelLeft + 128, providerY, 108, 20)
                .build());

        openRouterButton = addDrawableChild(ButtonWidget.builder(
                        Text.literal("OpenRouter"), button -> selectProvider("openrouter"))
                .dimensions(panelLeft + 242, providerY, 108, 20)
                .build());

        refreshProviderButtons();

        testButton = addDrawableChild(ButtonWidget.builder(
                        Text.literal("Проверить AI"), button -> testConnection())
                .dimensions(panelLeft + panelWidth - 246, panelTop + 9, 112, 20)
                .build());

        clearChatButton = addDrawableChild(ButtonWidget.builder(
                        Text.literal("Очистить"), button -> clearChat())
                .dimensions(panelLeft + panelWidth - 126, panelTop + 9, 112, 20)
                .build());

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

        setInitialFocus(messageInput);
    }

    private void rebuildVisibleHistory() {
        messages.clear();
        List<AiChatHistory.Entry> history = AiChatHistory.getAll();
        if (history.isEmpty()) {
            messages.add("AI: Я готов. Напиши, что сделать в мире.");
            return;
        }
        for (AiChatHistory.Entry entry : history) {
            messages.add((entry.role().equals("user") ? "Ты: " : "AI: ") + entry.text());
        }
    }

    private void selectProvider(String provider) {
        if (waiting) return;
        saveApiKeySilently();
        AiClientConfig.setProvider(provider);
        apiKeyInput.setText(AiClientConfig.getApiKey());
        messages.add("AI: Выбран провайдер " + providerDisplayName() + ".");
        refreshProviderButtons();
    }

    private void refreshProviderButtons() {
        String provider = AiClientConfig.getProvider();
        if (deepSeekButton != null) deepSeekButton.active = !provider.equals("deepseek");
        if (groqButton != null) groqButton.active = !provider.equals("groq");
        if (openRouterButton != null) openRouterButton.active = !provider.equals("openrouter");
    }

    private void saveApiKeySilently() {
        if (apiKeyInput != null) {
            AiClientConfig.setApiKey(apiKeyInput.getText());
        }
    }

    private void saveApiKey() {
        AiClientConfig.setApiKey(apiKeyInput.getText());
        messages.add(AiClientConfig.hasApiKey()
                ? "AI: " + providerDisplayName() + " API key сохранён локально."
                : "AI: API key очищен.");
    }

    private void clearChat() {
        if (waiting) return;
        AiChatHistory.clear();
        rebuildVisibleHistory();
        messages.add("AI: История чата очищена.");
    }

    private void testConnection() {
        if (waiting) return;
        saveApiKeySilently();
        if (!AiClientConfig.hasApiKey()) {
            messages.add("AI: Сначала вставь API key выбранного провайдера и нажми «Сохранить key».");
            return;
        }

        waiting = true;
        liveStatus = "Подключаюсь к " + providerDisplayName() + "...";
        statusAnimationTick = 0;
        if (testButton != null) testButton.active = false;
        if (clearChatButton != null) clearChatButton.active = false;
        if (sendButton != null) sendButton.active = false;
        String provider = providerDisplayName();
        messages.add("AI: Проверяю подключение к " + provider + "...");
        AiAgentService.testConnection().handle((ok, throwable) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> {
                waiting = false;
                liveStatus = "";
                if (testButton != null) testButton.active = true;
                if (clearChatButton != null) clearChatButton.active = true;
                if (sendButton != null) sendButton.active = true;
                if (throwable != null) {
                    Throwable cause = deepestCause(throwable);
                    messages.add("AI: Ошибка: " + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                } else {
                    messages.add(ok
                            ? "AI: " + provider + " подключён и отвечает."
                            : "AI: " + provider + " не вернул ожидаемый ответ.");
                }
            });
            return null;
        });
    }

    private void sendMessage() {
        if (waiting || messageInput == null) return;

        saveApiKeySilently();
        String message = messageInput.getText().trim();
        if (message.isEmpty()) return;

        if (!AiClientConfig.hasApiKey()) {
            messages.add("AI: Сначала вставь " + providerDisplayName() + " API key сверху и нажми «Сохранить key».");
            return;
        }

        messages.add("Ты: " + message);
        AiChatHistory.add("user", message);
        messageInput.setText("");
        waiting = true;
        liveStatus = "Анализирую запрос...";
        statusAnimationTick = 0;
        sendButton.active = false;
        if (testButton != null) testButton.active = false;
        if (clearChatButton != null) clearChatButton.active = false;

        CompletableFuture<String> future = AiAgentService.chat(message);
        future.handle((reply, throwable) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> {
                waiting = false;
                liveStatus = "";
                if (sendButton != null) sendButton.active = true;
                if (testButton != null) testButton.active = true;
                if (clearChatButton != null) clearChatButton.active = true;

                if (throwable != null) {
                    Throwable cause = deepestCause(throwable);
                    String error = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    messages.add("AI: Ошибка: " + error);
                    AiChatHistory.add("assistant", "Ошибка: " + error);
                } else {
                    String finalReply = reply == null || reply.isBlank()
                            ? "Я не получил текстового ответа."
                            : reply;
                    messages.add("AI: " + finalReply);
                    AiChatHistory.add("assistant", finalReply);
                }
            });
            return null;
        });
    }

    private static Throwable deepestCause(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth++ < 16) {
            current = current.getCause();
        }
        return current;
    }

    private String providerDisplayName() {
        return switch (AiClientConfig.getProvider()) {
            case "groq" -> "Groq";
            case "openrouter" -> "OpenRouter";
            default -> "DeepSeek";
        };
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x66000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xF0121216);
        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 1, 0xFFFFFFFF);
        context.fill(panelLeft, panelTop + 82, panelLeft + panelWidth, panelTop + 83, 0xFF303038);

        context.drawTextWithShadow(textRenderer, title, panelLeft + 14, panelTop + 10, 0xFFFFFF);
        String statusText = waiting
                ? buildAnimatedStatus()
                : providerDisplayName() + " AI Agent";
        int statusColor = waiting ? 0xA0FFA0 : 0xB0B0B8;
        context.drawTextWithShadow(textRenderer, statusText, panelLeft + 14, panelTop + 92, statusColor);
        context.drawTextWithShadow(textRenderer,
                "Провайдер:", panelLeft + 364, panelTop + 62, 0xC0C0C8);

        int chatTop = panelTop + 112;
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

    private String buildAnimatedStatus() {
        statusAnimationTick++;
        String base = liveStatus == null || liveStatus.isBlank()
                ? "ИИ работает..."
                : liveStatus;
        String[] dots = {"", ".", "..", "..."};
        return "● " + base + dots[(int) ((statusAnimationTick / 8) % dots.length)];
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
        AiAgentStatus.clearListener();
        MinecraftClient.getInstance().setScreen(null);
    }
}
