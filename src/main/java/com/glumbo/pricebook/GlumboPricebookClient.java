package com.glumbo.pricebook;

import com.glumbo.pricebook.config.ModConfig;
import com.glumbo.pricebook.scanner.HttpScanTransport;
import com.glumbo.pricebook.scanner.ShopScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class GlumboPricebookClient implements ClientModInitializer {
    private static ShopScanner scanner;
    private static HttpScanTransport transport;
    private static ModConfig config;

    @Override
    public void onInitializeClient() {
        config = ModConfig.load();

        transport = new HttpScanTransport(config);
        scanner = new ShopScanner(config, transport);

        registerEvents();

        bootstrapTransport();
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

    public static void bootstrapTransport() {
        if (transport != null) {
            transport.bootstrap();
        }
    }

    public static void resetForNewWorld() {
        if (scanner != null) {
            scanner.reset();
        }
        if (transport != null) {
            transport.clear();
        }
    }
}
