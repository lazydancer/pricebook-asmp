package com.glumbo.pricebook.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "glumbo-pricebook.json";

    public boolean trackShops = true;
    public String senderId = "";
    public String apiBaseUrl = "http://localhost:49876";

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
        String user = Optional.ofNullable(MinecraftClient.getInstance())
                .map(MinecraftClient::getSession)
                .map(session -> session.getUsername())
                .filter(name -> !name.isBlank())
                .orElse("player");
        return user + "-" + UUID.randomUUID();
    }

    private void applyDefaults() {
        if (senderId == null || senderId.isBlank()) {
            senderId = generateSenderId();
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "http://localhost:49876";
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

    public String apiBaseUrl() {
        return Objects.requireNonNullElse(apiBaseUrl, "http://localhost:49876");
    }
}
