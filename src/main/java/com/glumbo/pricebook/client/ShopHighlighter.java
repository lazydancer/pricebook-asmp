package com.glumbo.pricebook.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.util.shape.VoxelShape;

import java.time.Duration;

public final class ShopHighlighter {
    private static final long HIGHLIGHT_DURATION_MILLIS = Duration.ofSeconds(30).toMillis();

    private static BlockPos targetPos;
    private static String targetDimension = "";
    private static long expiresAtMillis;

    private ShopHighlighter() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (targetPos == null) {
                return;
            }
            if (System.currentTimeMillis() > expiresAtMillis) {
                clear();
            }
        });

        WorldRenderEvents.AFTER_TRANSLUCENT.register(ShopHighlighter::render);
    }

    public static void clear() {
        targetPos = null;
        targetDimension = "";
        expiresAtMillis = 0;
    }

    /**
     * Highlights the provided position. Returns true if the highlight will be visible immediately in the current
     * dimension, false if the player must switch dimensions first.
     */
    public static boolean highlight(BlockPos pos, String dimension) {
        if (pos == null) {
            return false;
        }

        targetPos = pos.toImmutable();
        targetDimension = normalizeDimension(dimension);
        expiresAtMillis = System.currentTimeMillis() + HIGHLIGHT_DURATION_MILLIS;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) {
            return false;
        }

        return targetMatchesWorld(client.world);
    }

    private static void render(WorldRenderContext context) {
        if (targetPos == null) {
            return;
        }
        if (System.currentTimeMillis() > expiresAtMillis) {
            clear();
            return;
        }

        World world = context.world();
        if (world == null || !targetMatchesWorld(world)) {
            return;
        }

        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        Camera camera = context.camera();
        if (matrices == null || camera == null) {
            return;
        }

        double camX = camera.getPos().x;
        double camY = camera.getPos().y;
        double camZ = camera.getPos().z;

        matrices.push();
        matrices.translate(targetPos.getX() - camX, targetPos.getY() - camY, targetPos.getZ() - camZ);

        VertexConsumer vertexConsumer = consumers.getBuffer(RenderLayer.getLines());
        Box highlightBox = new Box(0, 0, 0, 1, 1, 1);
        BlockState state = world.getBlockState(targetPos);
        if (state != null && !state.isAir()) {
            VoxelShape shape = state.getOutlineShape(world, targetPos, ShapeContext.absent());
            if (!shape.isEmpty()) {
                Box shapeBox = shape.getBoundingBox().expand(0.01);
                if (shapeBox.getAverageSideLength() > 0) {
                    highlightBox = shapeBox;
                }
            }
        }
        VertexRendering.drawBox(matrices, vertexConsumer, highlightBox,
                0.2f, 0.9f, 0.9f, 0.8f);

        matrices.pop();
    }

    private static boolean targetMatchesWorld(World world) {
        if (targetDimension.isEmpty()) {
            return true;
        }
        String worldDimension = normalizeDimension(world.getRegistryKey().getValue().toString());
        return targetDimension.equals(worldDimension);
    }

    private static String normalizeDimension(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return switch (raw) {
            case "minecraft:the_nether", "the_nether", "nether" -> "nether";
            case "minecraft:the_end", "the_end", "end" -> "end";
            case "minecraft:overworld", "overworld" -> "overworld";
            default -> raw;
        };
    }
}
