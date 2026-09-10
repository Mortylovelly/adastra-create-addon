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
    private ButtonWidget stopButton;
    private ButtonWidget geminiButton;
    private ButtonWidget deepSeekButton;
    private ButtonWidget groqButton;
    private ButtonWidget openRouterButton;
    private ButtonWidget testButton;
    private ButtonWidget newChatButton;
    private ButtonWidget clearChatButton;
    private boolean waiting;
    private String liveStatus = "";
    private long statusAnimationTick;
    private long knownHistorySize = -1;
    private String chatId;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public AiChatScreen() {
        super(Text.literal("Minecraft AI Agent"));
        AiChatHistory.load();
        AiAgentMemory.load();
        chatId = AiChatHistory.getCurrentChatId();
        AiAgentMemory.migrateLegacyToChat(chatId);
        rebuildVisibleHistory();
        syncTaskState();
    }

    @Override
    protected void init() {
        AiClientConfig.load();
        AiChatHistory.load();
        AiAgentMemory.load();
        chatId = AiChatHistory.getCurrentChatId();
        rebuildVisibleHistory();

        AiAgentStatus.setListener(status -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> liveStatus = status == null ? "" : status);
        });

        panelWidth = Math.min(760, width - 24);
        panelHeight = Math.min(500, height - 24);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        int keyY = panelTop + 31;
        apiKeyInput = new TextFieldWidget(textRenderer, panelLeft + 14, keyY, panelWidth - 140, 20, Text.literal("API key"));
        apiKeyInput.setMaxLength(500);
        apiKeyInput.setText(AiClientConfig.getApiKey());
        apiKeyInput.setPlaceholder(Text.literal("API key выбранного провайдера — хранится локально"));
        addDrawableChild(apiKeyInput);
        addDrawableChild(ButtonWidget.builder(Text.literal("Сохранить"), button -> saveApiKey())
                .dimensions(panelLeft + panelWidth - 112, keyY, 98, 20).build());

        int providerY = panelTop + 58;
        geminiButton = addDrawableChild(ButtonWidget.builder(Text.literal("Gemini"), button -> selectProvider("gemini"))
                .dimensions(panelLeft + 14, providerY, 98, 20).build());
        deepSeekButton = addDrawableChild(ButtonWidget.builder(Text.literal("DeepSeek"), button -> selectProvider("deepseek"))
                .dimensions(panelLeft + 118, providerY, 98, 20).build());
        groqButton = addDrawableChild(ButtonWidget.builder(Text.literal("Groq"), button -> selectProvider("groq"))
                .dimensions(panelLeft + 222, providerY, 98, 20).build());
        openRouterButton = addDrawableChild(ButtonWidget.builder(Text.literal("OpenRouter"), button -> selectProvider("openrouter"))
                .dimensions(panelLeft + 326, providerY, 112, 20).build());

        testButton = addDrawableChild(ButtonWidget.builder(Text.literal("Проверить"), button -> testConnection())
                .dimensions(panelLeft + panelWidth - 322, panelTop + 9, 96, 20).build());
        newChatButton = addDrawableChild(ButtonWidget.builder(Text.literal("Новый чат"), button -> newChat())
                .dimensions(panelLeft + panelWidth - 218, panelTop + 9, 96, 20).build());
        clearChatButton = addDrawableChild(ButtonWidget.builder(Text.literal("Очистить"), button -> clearChat())
                .dimensions(panelLeft + panelWidth - 114, panelTop + 9, 100, 20).build());

        int inputY = panelTop + panelHeight - 35;
        messageInput = new TextFieldWidget(textRenderer, panelLeft + 14, inputY, panelWidth - 206, 22, Text.literal("Сообщение"));
        messageInput.setMaxLength(16000);
        messageInput.setPlaceholder(Text.literal("Опиши задачу как угодно подробно..."));
        addDrawableChild(messageInput);

        sendButton = addDrawableChild(ButtonWidget.builder(Text.literal("Отправить"), button -> sendMessage())
                .dimensions(panelLeft + panelWidth - 184, inputY, 80, 22).build());
        stopButton = addDrawableChild(ButtonWidget.builder(Text.literal("Стоп"), button -> AiAgentService.cancelCurrentTask())
                .dimensions(panelLeft + panelWidth - 94, inputY, 80, 22).build());

        syncTaskState();
        setInitialFocus(messageInput);
    }

    @Override
    public void tick() {
        super.tick();
        syncTaskState();
        long size = AiChatHistory.getAll(chatId).size();
        if (size != knownHistorySize) {
            knownHistorySize = size;
            rebuildVisibleHistory();
        }
    }

    private void rebuildVisibleHistory() {
        messages.clear();
        List<AiChatHistory.Entry> history = AiChatHistory.getAll(chatId);
        if (history.isEmpty()) {
            messages.add("AI: Готов. Я могу работать с миром, предметами, мобами, постройками и командами.");
            return;
        }
        for (AiChatHistory.Entry entry : history) {
            messages.add((entry.role().equals("user") ? "Ты: " : "AI: ") + entry.text());
        }
    }

    private void syncTaskState() {
        waiting = AiAgentService.isBusy() && chatId.equals(AiAgentService.getActiveChatId());
        if (stopButton != null) stopButton.active = waiting;
        if (sendButton != null) sendButton.active = !AiAgentService.isBusy();
        if (testButton != null) testButton.active = !AiAgentService.isBusy();
        if (newChatButton != null) newChatButton.active = !AiAgentService.isBusy();
        if (clearChatButton != null) clearChatButton.active = !AiAgentService.isBusy();
        if (geminiButton != null) geminiButton.active = !AiAgentService.isBusy() && !AiClientConfig.getProvider().equals("gemini");
        if (deepSeekButton != null) deepSeekButton.active = !AiAgentService.isBusy() && !AiClientConfig.getProvider().equals("deepseek");
        if (groqButton != null) groqButton.active = !AiAgentService.isBusy() && !AiClientConfig.getProvider().equals("groq");
        if (openRouterButton != null) openRouterButton.active = !AiAgentService.isBusy() && !AiClientConfig.getProvider().equals("openrouter");
    }

    private void selectProvider(String provider) {
        if (AiAgentService.isBusy()) return;
        saveApiKeySilently();
        AiClientConfig.setProvider(provider);
        apiKeyInput.setText(AiClientConfig.getApiKey());
        messages.add("AI: Выбран провайдер " + providerDisplayName() + ".");
        refreshProviderButtons();
    }

    private void refreshProviderButtons() {
        syncTaskState();
    }

    private void saveApiKeySilently() {
        if (apiKeyInput != null) AiClientConfig.setApiKey(apiKeyInput.getText());
    }

    private void saveApiKey() {
        AiClientConfig.setApiKey(apiKeyInput.getText());
        messages.add(AiClientConfig.hasApiKey()
                ? "AI: " + providerDisplayName() + " API key сохранён локально."
                : "AI: API key очищен.");
    }

    private void newChat() {
        if (AiAgentService.isBusy()) return;
        chatId = AiChatHistory.createChat();
        AiAgentMemory.load();
        liveStatus = "";
        rebuildVisibleHistory();
        knownHistorySize = AiChatHistory.getAll(chatId).size();
    }

    private void clearChat() {
        if (AiAgentService.isBusy()) return;
        AiChatHistory.clear(chatId);
        rebuildVisibleHistory();
        messages.add("AI: История этого чата очищена. Память этого чата сохранена.");
    }

    private void testConnection() {
        if (AiAgentService.isBusy()) return;
        saveApiKeySilently();
        if (!AiClientConfig.hasApiKey()) {
            messages.add("AI: Сначала вставь API key выбранного провайдера.");
            return;
        }
        liveStatus = "Проверяю подключение...";
        messages.add("AI: Проверяю подключение к " + providerDisplayName() + "...");
        syncTaskState();
        AiAgentService.testConnection().whenComplete((ok, throwable) -> {
            MinecraftClient.getInstance().execute(() -> {
                liveStatus = "";
                if (throwable != null) {
                    Throwable cause = deepestCause(throwable);
                    messages.add("AI: Ошибка: " + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                } else {
                    messages.add(ok ? "AI: Подключение успешно." : "AI: Провайдер не вернул ожидаемый ответ.");
                }
            });
        });
    }

    private void sendMessage() {
        if (AiAgentService.isBusy() || messageInput == null) return;
        saveApiKeySilently();
        String message = messageInput.getText().trim();
        if (message.isEmpty()) return;
        if (!AiClientConfig.hasApiKey()) {
            messages.add("AI: Сначала вставь " + providerDisplayName() + " API key сверху.");
            return;
        }

        messages.add("Ты: " + message);
        messageInput.setText("");
        liveStatus = "Анализирую запрос...";
        statusAnimationTick = 0;
        syncTaskState();

        CompletableFuture<String> future = AiAgentService.chat(chatId, message);
        future.whenComplete((reply, throwable) -> MinecraftClient.getInstance().execute(this::syncTaskState));
    }

    private static Throwable deepestCause(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth++ < 16) current = current.getCause();
        return current;
    }

    private String providerDisplayName() {
        return switch (AiClientConfig.getProvider()) {
            case "gemini" -> "Gemini";
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
        context.fill(panelLeft, panelTop + 84, panelLeft + panelWidth, panelTop + 85, 0xFF303038);

        context.drawTextWithShadow(textRenderer, title, panelLeft + 14, panelTop + 10, 0xFFFFFF);
        String statusText = waiting ? buildAnimatedStatus() : providerDisplayName() + " AI Agent";
        context.drawTextWithShadow(textRenderer, statusText, panelLeft + 14, panelTop + 96, waiting ? 0xA0FFA0 : 0xB0B0B8);
        context.drawTextWithShadow(textRenderer, "Чат: " + chatId.substring(0, Math.min(8, chatId.length())), panelLeft + 190, panelTop + 96, 0x77777F);
        context.drawTextWithShadow(textRenderer, "Память: " + AiAgentMemory.count(chatId), panelLeft + panelWidth - 110, panelTop + 96, 0xB0B0B8);

        int chatTop = panelTop + 116;
        int chatBottom = panelTop + panelHeight - 49;
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
        String base = liveStatus == null || liveStatus.isBlank() ? "ИИ работает..." : liveStatus;
        String[] dots = {"", ".", "..", "..."};
        return "● " + base + dots[(int) ((statusAnimationTick / 8) % dots.length)];
    }

    private String[] wrap(String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (textRenderer.getWidth(candidate) <= maxWidth) current = new StringBuilder(candidate);
            else {
                if (!current.isEmpty()) result.add(current.toString());
                current = new StringBuilder(word);
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
