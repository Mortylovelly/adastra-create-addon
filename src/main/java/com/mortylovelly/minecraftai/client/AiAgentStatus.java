package com.mortylovelly.minecraftai.client;

import java.util.function.Consumer;

public final class AiAgentStatus {
    private static volatile Consumer<String> listener;
    private static volatile String status = "";

    private AiAgentStatus() {}

    public static void setListener(Consumer<String> newListener) {
        listener = newListener;
        if (newListener != null && !status.isBlank()) {
            newListener.accept(status);
        }
    }

    public static void clearListener() {
        listener = null;
    }

    public static void set(String newStatus) {
        status = newStatus == null ? "" : newStatus;
        Consumer<String> current = listener;
        if (current != null) {
            current.accept(status);
        }
    }

    public static String get() {
        return status;
    }

    public static void clear() {
        set("");
    }
}
