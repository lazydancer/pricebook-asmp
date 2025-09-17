package com.glumbo.pricebook.scanner;

import com.glumbo.pricebook.config.ModConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class ShopScanner {
    private static final Comparator<ShopObservation> OBSERVATION_ORDER = Comparator
            .comparing(ShopObservation::owner, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ShopObservation::item, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(observation -> observation.position().getX())
            .thenComparingInt(observation -> observation.position().getY())
            .thenComparingInt(observation -> observation.position().getZ())
            .thenComparing(observation -> observation.action().apiValue());

    private final ModConfig config;
    private final ScanBuffer buffer;
    private final HttpScanTransport transport;
    private final Long2ObjectMap<Set<ShopObservation>> lastKnownShops = new Long2ObjectOpenHashMap<>();

    private int flushTicks;

    public ShopScanner(ModConfig config, ScanBuffer buffer, HttpScanTransport transport) {
        this.config = Objects.requireNonNull(config, "config");
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public void tick() {
        if (!config.trackShops) {
            return;
        }

        flushTicks++;
        if (flushTicks >= Math.max(1, config.ticksBetweenSends)) {
            flushTicks = 0;
            flushBuffer();
        }
    }

    public void scanChunk(ClientWorld world, int chunkX, int chunkZ) {
        if (world == null || !config.trackShops) {
            return;
        }
        WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
        if (chunk != null) {
            scanChunk(world, chunk);
        }
    }

    public void scanChunk(ClientWorld world, BlockPos pos) {
        scanChunk(world, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public void scanChunk(ClientWorld world, WorldChunk chunk) {
        if (world == null || chunk == null || !config.trackShops) {
            return;
        }

        ChunkPos pos = chunk.getPos();
        long key = pos.toLong();

        Set<ShopObservation> current = collectShops(world, chunk);
        Set<ShopObservation> previous = lastKnownShops.get(key);

        boolean changed = previous == null || !previous.equals(current);
        if (!changed) {
            return;
        }

        lastKnownShops.put(key, Set.copyOf(current));

        List<ShopObservation> sorted = current.stream()
                .sorted(OBSERVATION_ORDER)
                .collect(Collectors.toList());

        String dimension = DimensionUtil.lookup(world);
        if (sorted.isEmpty() && !transport.shouldTransmitEmpty(dimension, pos)) {
            return;
        }
        ShopScan scan = ShopScan.create(config.senderId, dimension, pos, sorted);
        buffer.enqueue(scan);
    }

    public void forgetChunk(ChunkPos pos) {
        lastKnownShops.remove(pos.toLong());
    }

    public void reset() {
        lastKnownShops.clear();
        flushTicks = 0;
        buffer.drain();
    }

    public void flushNow() {
        flushTicks = 0;
        flushBuffer();
    }

    public int pendingScanCount() {
        return buffer.size();
    }

    private Set<ShopObservation> collectShops(ClientWorld world, WorldChunk chunk) {
        Set<ShopObservation> observations = new HashSet<>();
        chunk.getBlockEntityPositions().forEach((BlockPos pos) -> {
            Optional<ShopObservation> parsed = parseShop(world, pos);
            parsed.ifPresent(observations::add);
        });
        return observations;
    }

    private Optional<ShopObservation> parseShop(ClientWorld world, BlockPos pos) {
        if (world == null) {
            return Optional.empty();
        }
        if (world.getBlockEntity(pos) instanceof net.minecraft.block.entity.SignBlockEntity sign) {
            return ShopSignParser.parse(world, pos, sign);
        }
        return Optional.empty();
    }

    private void flushBuffer() {
        List<ShopScan> scans = buffer.drain();
        if (!scans.isEmpty()) {
            transport.submit(scans);
        }
    }
}
