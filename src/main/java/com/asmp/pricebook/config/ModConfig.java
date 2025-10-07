package com.asmp.pricebook.config;

import com.asmp.pricebook.util.Loggers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public final class ModConfig {
    private static final Logger LOGGER = Loggers.APP;
    private static final String CONFIG_FILE_NAME = "pricebook-asmp.json";
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_API_BASE_URL = "https://pricebook-asmp-server.fly.dev";
    private static final int SENDER_ID_UUID_LENGTH = 8;

    public String senderId = "";
    public String apiBaseUrl = DEFAULT_API_BASE_URL;
    public boolean enabled = true;

    public static ModConfig load() {
        if (!Files.exists(CONFIG_PATH)) {
            LOGGER.info("Config file not found, creating default configuration at {}", CONFIG_PATH);
            ModConfig defaults = new ModConfig();
            defaults.senderId = generateSenderId();
            defaults.save();
            LOGGER.debug("Created default config with senderId={}, apiBaseUrl={}", defaults.senderId, defaults.apiBaseUrl);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            LOGGER.debug("Loading config from {}", CONFIG_PATH);
            ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
            if (loaded == null) {
                throw new JsonParseException("Config file empty");
            }
            loaded.applyDefaults();
            loaded.save();
            LOGGER.info("Loaded config: senderId={}, apiBaseUrl={}", loaded.senderId, loaded.apiBaseUrl());
            return loaded;
        } catch (IOException | JsonParseException ex) {
            LOGGER.warn("Failed to load config from {}: {}", CONFIG_PATH, ex.getMessage());
            LOGGER.info("Using fallback configuration");
            ModConfig fallback = new ModConfig();
            fallback.senderId = generateSenderId();
            fallback.save();
            return fallback;
        }
    }

    private static String generateSenderId() {
        return UUID.randomUUID().toString().substring(0, SENDER_ID_UUID_LENGTH);
    }

    private void applyDefaults() {
        if (senderId == null || senderId.isBlank()) {
            senderId = generateSenderId();
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = DEFAULT_API_BASE_URL;
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
            LOGGER.debug("Saved config to {}", CONFIG_PATH);
        } catch (IOException ex) {
            LOGGER.warn("Failed to save config to {}: {}", CONFIG_PATH, ex.getMessage());
        }
    }

    public String apiBaseUrl() {
        return Objects.requireNonNullElse(apiBaseUrl, DEFAULT_API_BASE_URL);
    }

}
