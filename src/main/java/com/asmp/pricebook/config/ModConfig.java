package com.asmp.pricebook.config;

import com.asmp.pricebook.util.Loggers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ModConfig {
    private static final Logger LOGGER = Loggers.APP;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "pricebook-asmp.json";
    private static final String DEFAULT_API_BASE_URL = "https://pricebook-asmp-server.fly.dev";
    private static final int SENDER_ID_UUID_LENGTH = 8;

    public String senderId = "";
    public String apiBaseUrl = DEFAULT_API_BASE_URL;

    public static ModConfig load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path file = configDir.resolve(CONFIG_FILE_NAME);

        if (!Files.exists(file)) {
            LOGGER.info("Config file not found, creating default configuration at {}", file);
            ModConfig defaults = new ModConfig();
            defaults.senderId = generateSenderId();
            defaults.save(file);
            LOGGER.debug("Created default config with senderId={}, apiBaseUrl={}", defaults.senderId, defaults.apiBaseUrl);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            LOGGER.debug("Loading config from {}", file);
            ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
            if (loaded == null) {
                throw new JsonParseException("Config file empty");
            }
            loaded.applyDefaults();
            loaded.save(file);
            LOGGER.info("Loaded config: senderId={}, apiBaseUrl={}", loaded.senderId, loaded.apiBaseUrl());
            return loaded;
        } catch (IOException | JsonParseException ex) {
            LOGGER.warn("Failed to load config from {}: {}", file, ex.getMessage());
            LOGGER.info("Using fallback configuration");
            ModConfig fallback = new ModConfig();
            fallback.senderId = generateSenderId();
            fallback.save(file);
            return fallback;
        }
    }

    private static String generateSenderId() {
        String user = Optional.ofNullable(MinecraftClient.getInstance())
                .map(MinecraftClient::getSession)
                .map(session -> session.getUsername())
                .filter(name -> !name.isBlank())
                .orElse("player");
        return user + "-" + UUID.randomUUID().toString().substring(0, SENDER_ID_UUID_LENGTH);
    }

    private void applyDefaults() {
        if (senderId == null || senderId.isBlank()) {
            senderId = generateSenderId();
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = DEFAULT_API_BASE_URL;
        }
    }

    private void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
            LOGGER.debug("Saved config to {}", file);
        } catch (IOException ex) {
            LOGGER.warn("Failed to save config to {}: {}", file, ex.getMessage());
        }
    }

    public String apiBaseUrl() {
        return Objects.requireNonNullElse(apiBaseUrl, DEFAULT_API_BASE_URL);
    }

}
