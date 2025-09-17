package com.glumbo.pricebook.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "glumbo-pricebook.json";
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(5);

    public boolean trackShops = true;
    public int ticksBetweenSends = 600;
    public String senderId = "";
    public String apiBaseUrl = "http://localhost:49876";
    public int maxQueuedScans = 128;
    public int reconnectBackoffSeconds = (int) DEFAULT_RETRY_BACKOFF.getSeconds();

    public static ModConfig load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path file = configDir.resolve(CONFIG_FILE_NAME);

        if (!Files.exists(file)) {
            ModConfig defaults = new ModConfig();
            defaults.senderId = generateSenderId();
            defaults.save(file);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
            if (loaded == null) {
                throw new JsonParseException("Config file empty");
            }
            loaded.applyDefaults();
            loaded.save(file);
            return loaded;
        } catch (IOException | JsonParseException ex) {
            ModConfig fallback = new ModConfig();
            fallback.senderId = generateSenderId();
            fallback.save(file);
            return fallback;
        }
    }

    private static String generateSenderId() {
        String user = System.getProperty("user.name", "player");
        return user + "-" + UUID.randomUUID();
    }

    private void applyDefaults() {
        if (ticksBetweenSends <= 0) {
            ticksBetweenSends = 600;
        }
        if (maxQueuedScans <= 0) {
            maxQueuedScans = 128;
        }
        if (senderId == null || senderId.isBlank()) {
            senderId = generateSenderId();
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "http://localhost:49876";
        }
        if (reconnectBackoffSeconds <= 0) {
            reconnectBackoffSeconds = (int) DEFAULT_RETRY_BACKOFF.getSeconds();
        }
    }

    private void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException ignored) {
            // Swallow config persistence issues to avoid breaking gameplay.
        }
    }

    public Duration reconnectBackoff() {
        return Duration.ofSeconds(Math.max(1, reconnectBackoffSeconds));
    }

    public String apiBaseUrl() {
        return Objects.requireNonNullElse(apiBaseUrl, "http://localhost:49876");
    }
}
