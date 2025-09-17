package com.glumbo.pricebook.scanner;

import net.minecraft.util.math.ChunkPos;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ShopScan(
        String scanId,
        String senderId,
        String dimension,
        int chunkX,
        int chunkZ,
        Instant scannedAt,
        List<ShopObservation> shops
) {
    public ShopScan {
        Objects.requireNonNull(scanId, "scanId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(scannedAt, "scannedAt");
        Objects.requireNonNull(shops, "shops");
    }

    public static ShopScan create(String senderId, String dimension, ChunkPos pos, List<ShopObservation> shops) {
        return new ShopScan(
                UUID.randomUUID().toString(),
                senderId,
                dimension,
                pos.x,
                pos.z,
                Instant.now(),
                List.copyOf(shops)
        );
    }

    public int observedCount() {
        return shops.size();
    }
}
