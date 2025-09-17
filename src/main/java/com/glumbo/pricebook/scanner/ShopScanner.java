package com.glumbo.pricebook.scanner;

import com.glumbo.pricebook.config.ModConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class ShopScanner {
    private static final Comparator<ShopSignParser.ShopEntry> ENTRY_ORDER = Comparator
            .comparing(ShopSignParser.ShopEntry::owner, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ShopSignParser.ShopEntry::item, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(entry -> entry.position().getX())
            .thenComparingInt(entry -> entry.position().getY())
            .thenComparingInt(entry -> entry.position().getZ())
            .thenComparing(ShopSignParser.ShopEntry::action);

    private final ModConfig config;
    private final HttpScanTransport transport;
    private final Long2ObjectMap<Set<ShopSignParser.ShopEntry>> lastKnownShops = new Long2ObjectOpenHashMap<>();

    public ShopScanner(ModConfig config, HttpScanTransport transport) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
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

        Set<ShopSignParser.ShopEntry> current = collectShops(world, chunk);
        Set<ShopSignParser.ShopEntry> previous = lastKnownShops.get(key);
        if (previous != null && previous.equals(current)) {
            return;
        }

        lastKnownShops.put(key, Set.copyOf(current));

        List<ShopSignParser.ShopEntry> sorted = current.stream()
                .sorted(ENTRY_ORDER)
                .collect(Collectors.toList());

        String dimension = lookupDimension(world);
        if (sorted.isEmpty() && !transport.shouldTransmitEmpty(dimension, pos)) {
            return;
        }

        transport.sendScan(config.senderId, dimension, pos, sorted);
    }

    public void forgetChunk(ChunkPos pos) {
        lastKnownShops.remove(pos.toLong());
    }

    public void reset() {
        lastKnownShops.clear();
    }

    private Set<ShopSignParser.ShopEntry> collectShops(ClientWorld world, WorldChunk chunk) {
        Set<ShopSignParser.ShopEntry> entries = new HashSet<>();
        chunk.getBlockEntityPositions().forEach((BlockPos pos) -> {
            Optional<ShopSignParser.ShopEntry> parsed = parseShop(world, pos);
            parsed.ifPresent(entries::add);
        });
        return entries;
    }

    private Optional<ShopSignParser.ShopEntry> parseShop(ClientWorld world, BlockPos pos) {
        if (world == null) {
            return Optional.empty();
        }
        if (world.getBlockEntity(pos) instanceof net.minecraft.block.entity.SignBlockEntity sign) {
            return ShopSignParser.parse(world, pos, sign);
        }
        return Optional.empty();
    }

    private static String lookupDimension(World world) {
        RegistryKey<World> key = world.getRegistryKey();
        Identifier id = key.getValue();
        return switch (id.toString()) {
            case "minecraft:the_nether" -> "nether";
            case "minecraft:the_end" -> "end";
            default -> "overworld";
        };
    }
}
