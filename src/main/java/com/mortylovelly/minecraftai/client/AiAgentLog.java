package com.mortylovelly.minecraftai.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class AiAgentLog {
    private static final Path LOG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("minecraft_ai_agent.log");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();
    private static final long MAX_LOG_BYTES = 1_000_000L;

    private AiAgentLog() {}

    public static void info(String message) {
        write("INFO", message);
    }

    public static void warn(String message) {
        write("WARN", message);
    }

    public static void error(String message) {
        write("ERROR", message);
    }

    private static void write(String level, String message) {
        String safe = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
        String line = "[" + FORMAT.format(LocalDateTime.now()) + "][" + level + "] " + safe + System.lineSeparator();

        synchronized (LOCK) {
            try {
                Files.createDirectories(LOG_PATH.getParent());
                rotateIfNeeded();
                Files.writeString(
                        LOG_PATH,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException exception) {
                System.err.println("[Minecraft AI Agent] Could not write agent log: " + exception.getMessage());
            }
        }
    }

    private static void rotateIfNeeded() throws IOException {
        if (!Files.exists(LOG_PATH) || Files.size(LOG_PATH) <= MAX_LOG_BYTES) return;

        Path backup = LOG_PATH.resolveSibling("minecraft_ai_agent.log.old");
        Files.deleteIfExists(backup);
        Files.move(LOG_PATH, backup);
    }
}
