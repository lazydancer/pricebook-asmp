package com.asmp.pricebook.waypoint;

import com.asmp.pricebook.util.Dimensions;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Handles creation and rendering of the in-game waypoint overlay.
 */
public final class WaypointManager {
    private static final int AUTO_CLEAR_DISTANCE_BLOCKS = 10;
    private static final int WAYPOINT_LABEL_PADDING = 3;
    private static final int WAYPOINT_Y_OFFSET = 6;
    private static final int SCREEN_EDGE_MARGIN = 2;
    private static final double WAYPOINT_CENTER_OFFSET = 0.5;
    private static final double WAYPOINT_HEIGHT_OFFSET = 1.2;
    private static final int LABEL_BACKGROUND_COLOR = 0x80000000;
    private static final int LABEL_TEXT_COLOR = 0xFFFFFFFF;

    private WaypointManager() {}

    public static void initClient() {
        registerWorldProjection();
        registerHudElement();
        registerAutoClearNearGoal();
    }

    public static boolean createWaypoint(BlockPos position, String dimensionKey, String name) {
        if (position == null) {
            return false;
        }
        active = new Waypoint(position, dimensionKey, name);
        return true;
    }

    public static void clear() {
        active = null;
        visibleOnScreen = false;
    }

    private static volatile Waypoint active;
    private static volatile boolean visibleOnScreen = false;
    private static volatile int screenX = 0;
    private static volatile int screenY = 0;
    private static volatile int lastDistanceM = 0;

    private static void registerWorldProjection() {
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            visibleOnScreen = false;
            Waypoint waypoint = active;
            MinecraftClient mc = MinecraftClient.getInstance();

            if (waypoint == null || mc.player == null || mc.world == null) {
                return;
            }
            if (!Dimensions.same(mc.world, waypoint.dimKey)) {
                return;
            }

            Camera camera = ctx.camera();
            Vec3d worldPos = new Vec3d(
                    waypoint.pos.getX() + WAYPOINT_CENTER_OFFSET,
                    waypoint.pos.getY() + WAYPOINT_HEIGHT_OFFSET,
                    waypoint.pos.getZ() + WAYPOINT_CENTER_OFFSET);
            Matrix4f projectionMatrix = new Matrix4f(ctx.projectionMatrix());

            Matrix4f viewMatrix = new Matrix4f();
            viewMatrix.rotate(camera.getRotation().conjugate());
            viewMatrix.translate(-(float) camera.getPos().x, -(float) camera.getPos().y, -(float) camera.getPos().z);

            Matrix4f viewProjectionMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix);

            Vector4f pos = new Vector4f((float) worldPos.x, (float) worldPos.y, (float) worldPos.z, 1.0f);
            viewProjectionMatrix.transform(pos);

            if (pos.w <= 0.0f) {
                return;
            }

            pos.x /= pos.w;
            pos.y /= pos.w;

            int screenWidth = mc.getWindow().getScaledWidth();
            int screenHeight = mc.getWindow().getScaledHeight();
            screenX = (int) Math.round((pos.x + 1.0) / 2.0 * screenWidth);
            screenY = (int) Math.round((1.0 - pos.y) / 2.0 * screenHeight);

            if (screenX >= 0 && screenX <= screenWidth && screenY >= 0 && screenY <= screenHeight) {
                lastDistanceM = (int) Math.round(camera.getPos().distanceTo(worldPos));
                visibleOnScreen = true;
            }
        });
    }

    private static void registerHudElement() {
        HudElement element = (DrawContext context, RenderTickCounter tickCounter) -> {
            if (!visibleOnScreen) {
                return;
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            Waypoint waypoint = active;
            if (mc == null || waypoint == null) {
                return;
            }
            TextRenderer renderer = mc.textRenderer;
            String label = waypoint.name + " — " + lastDistanceM + "m";
            Text line = Text.literal(label);
            int textWidth = renderer.getWidth(line);
            int textHeight = renderer.fontHeight;
            int padding = WAYPOINT_LABEL_PADDING;
            int sw = context.getScaledWindowWidth();
            int sh = context.getScaledWindowHeight();
            int x = screenX - textWidth / 2;
            int y = screenY - textHeight - WAYPOINT_Y_OFFSET;
            x = Math.max(SCREEN_EDGE_MARGIN, Math.min(x, sw - textWidth - SCREEN_EDGE_MARGIN));
            y = Math.max(SCREEN_EDGE_MARGIN, Math.min(y, sh - textHeight - SCREEN_EDGE_MARGIN));
            int bg = LABEL_BACKGROUND_COLOR;
            context.fill(x - padding, y - padding, x + textWidth + padding, y + textHeight + padding, bg);
            context.drawText(renderer, line, x, y, LABEL_TEXT_COLOR, true);
        };
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
                Identifier.of("pricebook-asmp", "waypoint_label"), element);
    }

    private static void registerAutoClearNearGoal() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Waypoint waypoint = active;
            if (waypoint == null || client.player == null || client.world == null) {
                return;
            }
            if (!Dimensions.same(client.world, waypoint.dimKey)) {
                return;
            }
            if (client.player.getPos().distanceTo(Vec3d.ofCenter(waypoint.pos)) < AUTO_CLEAR_DISTANCE_BLOCKS) {
                clear();
            }
        });
    }

    private static final class Waypoint {
        private final BlockPos pos;
        private final String dimKey;
        private final String name;

        private Waypoint(BlockPos pos, String dimKey, String name) {
            this.pos = pos;
            this.dimKey = Dimensions.canonical(dimKey);
            this.name = (name == null || name.isBlank()) ? "Waypoint" : name.trim();
        }
    }
}
