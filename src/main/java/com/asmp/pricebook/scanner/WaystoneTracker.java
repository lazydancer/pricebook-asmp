package com.asmp.pricebook.scanner;

import com.asmp.pricebook.config.ModConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class WaystoneTracker {
    private final ModConfig config;
    private final HttpScanTransport transport;
    private final Map<WaystoneLocationKey, WaystoneRecord> reported = new HashMap<>();

    private Screen lastProcessedScreen;

    public WaystoneTracker(ModConfig config, HttpScanTransport transport) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> handleClientTick(client));
    }

    public void reset() {
        reported.clear();
        lastProcessedScreen = null;
    }

    private void handleClientTick(MinecraftClient client) {
        if (client == null) {
            return;
        }
        if (!config.trackWaystones()) {
            lastProcessedScreen = null;
            return;
        }

        Screen current = client.currentScreen;
        if (!(current instanceof HandledScreen<?> screen)) {
            lastProcessedScreen = null;
            return;
        }

        if (current == lastProcessedScreen) {
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        if (!isWaystoneScreen(handler)) {
            return;
        }

        ClientWorld world = client.world;
        if (world == null) {
            return;
        }

        BlockHitResult hit = client.crosshairTarget instanceof BlockHitResult blockHit ? blockHit : null;
        if (hit == null) {
            return;
        }

        BlockPos initialPos = hit.getBlockPos().toImmutable();
        BlockPos pos = resolveWaystonePosition(world, initialPos);
        if (pos == null) {
            return;
        }
        ChunkPos chunkPos = new ChunkPos(pos);
        String dimension = dimensionName(world);
        String owner = resolveOwner(handler, client);
        String name = screen.getTitle().getString().trim();
        if (name.isEmpty()) {
            name = "Unnamed Waystone";
        }

        WaystoneLocationKey locationKey = new WaystoneLocationKey(dimension, chunkPos.x, chunkPos.z, pos);
        WaystoneRecord record = new WaystoneRecord(name, owner);
        WaystoneRecord previous = reported.get(locationKey);
        if (record.equals(previous)) {
            lastProcessedScreen = current;
            return;
        }

        transport.sendWaystoneScan(config.senderId, dimension, chunkPos, pos, name, owner);
        reported.put(locationKey, record);
        lastProcessedScreen = current;
    }

    private static boolean isWaystoneScreen(ScreenHandler handler) {
        if (handler == null) {
            return false;
        }
        int waystoneSlots = 6 * 9;
        int playerInventorySlots = 36;
        if (handler.slots.size() < waystoneSlots + playerInventorySlots) {
            return false;
        }
        if (handler.slots.size() <= 49) {
            return false;
        }

        ItemStack head = handler.getSlot(5).getStack();
        if (head.isEmpty() || head.getItem() != Items.PLAYER_HEAD) {
            return false;
        }

        ItemStack spyglass = handler.getSlot(49).getStack();
        if (spyglass.isEmpty() || spyglass.getItem() != Items.SPYGLASS) {
            return false;
        }

        return true;
    }

    private static String resolveOwner(ScreenHandler handler, MinecraftClient client) {
        ItemStack head = handler.getSlot(5).getStack();
        if (head.isEmpty()) {
            return "Unknown";
        }
        Text customName = head.get(DataComponentTypes.CUSTOM_NAME);
        String owner = customName != null ? customName.getString() : "Unknown";
        if (owner.startsWith("Owner: ")) {
            owner = owner.substring("Owner: ".length());
        }
        owner = owner.trim();
        if (owner.isEmpty()) {
            owner = "Unknown";
        }
        if (owner.equals("Waystone Options") && client.getSession() != null) {
            owner = client.getSession().getUsername();
        }
        return owner;
    }

    private static BlockPos resolveWaystonePosition(ClientWorld world, BlockPos pos) {
        if (isWaystoneTop(world, pos)) {
            return pos;
        }
        BlockPos up = pos.up();
        if (isWaystoneTop(world, up)) {
            return up;
        }
        BlockPos down = pos.down();
        if (isWaystoneTop(world, down)) {
            return down;
        }
        return null;
    }

    private static boolean isWaystoneTop(ClientWorld world, BlockPos pos) {
        BlockState top = world.getBlockState(pos);
        BlockState bottom = world.getBlockState(pos.down());
        return ShopScanner.isWaystonePair(top.getBlock(), bottom.getBlock());
    }

    private static String dimensionName(ClientWorld world) {
        String id = world.getRegistryKey().getValue().toString();
        return switch (id) {
            case "minecraft:the_nether" -> "nether";
            case "minecraft:the_end" -> "end";
            default -> "overworld";
        };
    }

    private record WaystoneLocationKey(String dimension, int chunkX, int chunkZ, BlockPos position) {
    }

    private record WaystoneRecord(String name, String owner) {
    }
}
