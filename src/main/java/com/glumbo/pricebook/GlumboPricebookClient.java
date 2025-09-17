package com.glumbo.pricebook;

import com.glumbo.pricebook.config.ModConfig;
import com.glumbo.pricebook.scanner.HttpScanTransport;
import com.glumbo.pricebook.scanner.ScanBuffer;
import com.glumbo.pricebook.scanner.ShopScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class GlumboPricebookClient implements ClientModInitializer {
    private static ShopScanner scanner;
    private static HttpScanTransport transport;
    private static ModConfig config;

    @Override
    public void onInitializeClient() {
        config = ModConfig.load();

        ScanBuffer buffer = new ScanBuffer(config.maxQueuedScans);
        transport = new HttpScanTransport(config);
        scanner = new ShopScanner(config, buffer, transport);

        registerEvents();

        bootstrapTransport();

        GlumboPricebook.LOGGER.info("Glumbo Pricebook client initialized: senderId={} flushTicks={}", config.senderId, config.ticksBetweenSends);
    }

    private void registerEvents() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != null && scanner != null && transport != null) {
                transport.onTransportTick();
                scanner.tick();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> flushPending());
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

    public static void flushPending() {
        if (scanner != null) {
            scanner.flushNow();
        }
    }
}
