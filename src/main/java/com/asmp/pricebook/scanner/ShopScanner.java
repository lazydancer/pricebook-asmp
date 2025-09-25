package com.asmp.pricebook.scanner;

import com.asmp.pricebook.config.ModConfig;
import com.asmp.pricebook.util.Dimensions;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.Mutable;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private static final Comparator<BlockPos> BLOCK_POS_ORDER = Comparator
            .comparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ);

    private static final Map<Block, List<WaystonePattern>> WAYSTONE_PATTERNS = createWaystonePatterns();

    private final ModConfig config;
    private final HttpScanTransport transport;
    private final Long2ObjectMap<ChunkSnapshot> lastKnownChunks = new Long2ObjectOpenHashMap<>();

    public ShopScanner(ModConfig config, HttpScanTransport transport) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public void scanChunk(ClientWorld world, int chunkX, int chunkZ) {
        if (world == null) {
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
        if (world == null || chunk == null) {
            return;
        }

        ChunkPos pos = chunk.getPos();
        long key = pos.toLong();

        Set<ShopSignParser.ShopEntry> currentShops = collectShops(world, chunk);
        Set<BlockPos> currentWaystones = collectWaystones(world, chunk);

        ChunkSnapshot previous = lastKnownChunks.get(key);
        ChunkSnapshot current = new ChunkSnapshot(Set.copyOf(currentShops), Set.copyOf(currentWaystones));
        if (previous != null && previous.equals(current)) {
            return;
        }

        lastKnownChunks.put(key, current);

        List<ShopSignParser.ShopEntry> sorted = currentShops.stream()
                .sorted(ENTRY_ORDER)
                .collect(Collectors.toList());
        List<BlockPos> waystones = currentWaystones.stream()
                .sorted(BLOCK_POS_ORDER)
                .collect(Collectors.toList());

        String dimension = Dimensions.canonical(world);
        boolean empty = sorted.isEmpty() && waystones.isEmpty();
        if (empty && !transport.shouldTransmitEmpty(dimension, pos)) {
            return;
        }

        transport.sendScan(config.senderId, dimension, pos, sorted, waystones);
    }

    public void forgetChunk(ChunkPos pos) {
        lastKnownChunks.remove(pos.toLong());
    }

    public void reset() {
        lastKnownChunks.clear();
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

    private Set<BlockPos> collectWaystones(ClientWorld world, WorldChunk chunk) {
        Set<BlockPos> positions = new HashSet<>();
        ChunkPos chunkPos = chunk.getPos();
        Mutable bottomPos = new Mutable();
        Mutable topPos = new Mutable();
        int minY = world.getBottomY();
        int maxY = minY + world.getHeight() - 1;

        for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkPos.getStartX() + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = chunkPos.getStartZ() + localZ;
                for (int y = minY; y < maxY; y++) {
                    bottomPos.set(worldX, y, worldZ);
                    Block bottomBlock = chunk.getBlockState(bottomPos).getBlock();
                    List<WaystonePattern> patterns = WAYSTONE_PATTERNS.get(bottomBlock);
                    if (patterns == null || patterns.isEmpty()) {
                        continue;
                    }

                    topPos.set(worldX, y + 1, worldZ);
                    Block topBlock = chunk.getBlockState(topPos).getBlock();
                    if (isWaystonePair(topBlock, bottomBlock)) {
                        positions.add(topPos.toImmutable());
                    }
                }
            }
        }
        return positions;
    }

    private static Map<Block, List<WaystonePattern>> createWaystonePatterns() {
        Map<Block, List<WaystonePattern>> mapping = new HashMap<>();
        addPattern(mapping, Blocks.LODESTONE, Blocks.SMOOTH_STONE_SLAB);
        addPattern(mapping, Blocks.WAXED_WEATHERED_CHISELED_COPPER, Blocks.WAXED_WEATHERED_CUT_COPPER_SLAB);
        addPattern(mapping, Blocks.RED_MUSHROOM_BLOCK, Blocks.SMOOTH_QUARTZ_SLAB);
        addPattern(mapping, Blocks.CHISELED_RESIN_BRICKS, Blocks.RESIN_BRICK_SLAB);
        addPattern(mapping, Blocks.WAXED_OXIDIZED_CHISELED_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB);
        addPattern(mapping, Blocks.CHISELED_POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB);
        addPattern(mapping, Blocks.WAXED_CHISELED_COPPER, Blocks.WAXED_CUT_COPPER_SLAB);
        addPattern(mapping, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.SMOOTH_QUARTZ_SLAB);
        addPattern(mapping, Blocks.WAXED_EXPOSED_CHISELED_COPPER, Blocks.WAXED_EXPOSED_CUT_COPPER_SLAB);
        return mapping;
    }

    private static void addPattern(Map<Block, List<WaystonePattern>> mapping, Block top, Block bottom) {
        mapping.computeIfAbsent(bottom, key -> new ArrayList<>()).add(new WaystonePattern(top));
    }

    static boolean isWaystonePair(Block top, Block bottom) {
        List<WaystonePattern> patterns = WAYSTONE_PATTERNS.get(bottom);
        if (patterns == null) {
            return false;
        }
        for (WaystonePattern pattern : patterns) {
            if (pattern.top() == top) {
                return true;
            }
        }
        return false;
    }

    private record WaystonePattern(Block top) {
    }

    private record ChunkSnapshot(Set<ShopSignParser.ShopEntry> shops, Set<BlockPos> waystones) {
    }
}
