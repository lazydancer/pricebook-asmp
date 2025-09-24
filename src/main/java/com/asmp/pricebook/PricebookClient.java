package com.asmp.pricebook;

import com.asmp.pricebook.command.PricebookCommand;
import com.asmp.pricebook.command.PricebookQueryService;
import com.asmp.pricebook.config.ModConfig;
import com.asmp.pricebook.integration.WaypointHelper;
import com.asmp.pricebook.scanner.HttpScanTransport;
import com.asmp.pricebook.scanner.ShopScanner;
import com.asmp.pricebook.scanner.WaystoneTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class PricebookClient implements ClientModInitializer {
    private static ShopScanner scanner;
    private static HttpScanTransport transport;
    private static ModConfig config;
    private static boolean enabled;
    private static PricebookQueryService queryService;
    private static List<String> itemCatalog = List.of();
    private static WaystoneTracker waystoneTracker;

    @Override
    public void onInitializeClient() {
        config = ModConfig.load();

        transport = new HttpScanTransport(config);
        scanner = new ShopScanner(config, transport);
        queryService = new PricebookQueryService(config);
        waystoneTracker = new WaystoneTracker(config, transport);
        waystoneTracker.init();

        registerEvents();

        enabled = shouldEnableForCurrentServer();
        bootstrapTransport();
        refreshItemCatalog();

        PricebookCommand.register();
        WaypointHelper.initClient();
    }

    private void registerEvents() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetForNewWorld());
    }

    public static ModConfig config() {
        return config;
    }

    public static ShopScanner scanner() {
        return scanner;
    }

    public static HttpScanTransport transport() {
        return transport;
    }

    public static PricebookQueryService pricebookQueryService() {
        return queryService;
    }

    public static List<String> itemCatalog() {
        return itemCatalog;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void bootstrapTransport() {
        if (transport != null && enabled) {
            transport.bootstrap();
        }
    }

    public static void onMultiplayerJoin() {
        resetForNewWorld();
        enabled = shouldEnableForCurrentServer();
        if (enabled) {
            bootstrapTransport();
        }
        refreshItemCatalog();
    }

    public static void resetForNewWorld() {
        if (scanner != null) {
            scanner.reset();
        }
        if (transport != null) {
            transport.clear();
        }
        enabled = false;
        itemCatalog = List.of();
        if (waystoneTracker != null) {
            waystoneTracker.reset();
        }
    }

    private static boolean shouldEnableForCurrentServer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return false;
        }
        if (client.isIntegratedServerRunning()) {
            return false;
        }

        ServerInfo info = client.getCurrentServerEntry();
        if (info == null) {
            return false;
        }

        String address = info.address;
        if (address == null || address.isBlank()) {
            return false;
        }

        String normalized = address.toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) {
            return false;
        }

        if (normalized.startsWith("[")) {
            return false;
        }

        if (normalized.equals("asmp.cc")) {
            return true;
        }

        return normalized.startsWith("asmp.cc:");
    }

    private static void refreshItemCatalog() {
        if (!enabled || queryService == null) {
            itemCatalog = List.of();
            return;
        }

        CompletableFuture<List<String>> future = queryService.fetchCatalog();
        future.thenAccept(list -> {
            MinecraftClient client = MinecraftClient.getInstance();
            List<String> safeList = list == null ? List.of() : List.copyOf(list);
            if (client != null) {
                client.execute(() -> itemCatalog = safeList);
            } else {
                itemCatalog = safeList;
            }
        });
    }
}
