package com.asmp.pricebook;

import com.asmp.pricebook.command.PricebookCommand;
import com.asmp.pricebook.command.PricebookQueryService;
import com.asmp.pricebook.config.ModConfig;
import com.asmp.pricebook.scanner.HttpScanTransport;
import com.asmp.pricebook.scanner.ShopScanner;
import com.asmp.pricebook.scanner.WaystoneScanner;
import com.asmp.pricebook.waypoint.WaypointManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class Pricebook implements ClientModInitializer {
    private static final ModConfig CONFIG = ModConfig.load();
    private static final WaystoneScanner WAYSTONE_SCANNER = new WaystoneScanner(CONFIG);

    private static Session session;

    @Override
    public void onInitializeClient() {
        WaypointManager.initClient();
        WAYSTONE_SCANNER.registerListeners();
        PricebookCommand.register();

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> endSession());

        refreshSession();
    }

    public static void onMultiplayerJoin() {
        refreshSession();
    }

    public static boolean isEnabled() {
        return session != null;
    }

    public static ShopScanner scanner() {
        return session == null ? null : session.shopScanner;
    }

    public static PricebookQueryService queryService() {
        return session == null ? null : session.queryService;
    }

    public static List<String> itemCatalog() {
        return session == null ? List.of() : session.itemCatalog;
    }

    private static void refreshSession() {
        if (!shouldEnableForCurrentServer()) {
            endSession();
            return;
        }

        if (session == null) {
            session = new Session();
        } else {
            session.refreshCatalog();
        }
    }

    private static void endSession() {
        if (session != null) {
            session.close();
            session = null;
        }
        WAYSTONE_SCANNER.attachTransport(null);
    }

    private static boolean shouldEnableForCurrentServer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.isIntegratedServerRunning()) {
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
        if (normalized.isEmpty() || normalized.startsWith("[")) {
            return false;
        }

        if (Objects.equals(normalized, "asmp.cc")) {
            return true;
        }

        return normalized.startsWith("asmp.cc:");
    }

    private static final class Session {
        private final HttpScanTransport transport;
        private final ShopScanner shopScanner;
        private final PricebookQueryService queryService;
        private volatile List<String> itemCatalog = List.of();

        private Session() {
            this.transport = new HttpScanTransport(CONFIG);
            this.shopScanner = new ShopScanner(CONFIG, transport);
            this.queryService = new PricebookQueryService(CONFIG);

            transport.bootstrap();
            WAYSTONE_SCANNER.attachTransport(transport);
            refreshCatalog();
        }

        private void refreshCatalog() {
            CompletableFuture<List<String>> future = queryService.fetchCatalog();
            future.thenAccept(list -> {
                List<String> safeList = list == null ? List.of() : List.copyOf(list);
                MinecraftClient client = MinecraftClient.getInstance();
                Runnable update = () -> {
                    if (session == this) {
                        itemCatalog = safeList;
                    }
                };
                if (client != null) {
                    client.execute(update);
                } else {
                    update.run();
                }
            });
        }

        private void close() {
            shopScanner.reset();
            transport.clear();
            itemCatalog = List.of();
        }
    }
}
