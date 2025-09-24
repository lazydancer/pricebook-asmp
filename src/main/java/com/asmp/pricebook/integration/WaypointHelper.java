package com.asmp.pricebook.integration;

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
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Locale;

public final class WaypointHelper {

    private WaypointHelper() {}

    // ---------- Public API ----------
    public static final class Waypoint {
        public final BlockPos pos;
        public final String dimKey;
        public final String name;
        public Waypoint(BlockPos pos, String dimKey, String name) {
            this.pos = pos;
            this.dimKey = normalizeDim(dimKey);
            this.name = (name == null || name.isBlank()) ? "Waypoint" : name.trim();
        }
    }

    public static boolean createWaypoint(BlockPos position, String dimensionKey, String name) {
        if (position == null) return false;
        active = new Waypoint(position, dimensionKey, name);
        return true;
    }

    public static void clear() {
        active = null;
        visibleOnScreen = false;
    }

    public static void initClient() {
        registerWorldProjection();
        registerHudElement();
        registerAutoClearNearGoal();
    }

    // ---------- Internal state ----------
    private static volatile Waypoint active;
    private static volatile boolean visibleOnScreen = false;
    private static volatile int screenX = 0;
    private static volatile int screenY = 0;
    private static volatile int lastDistanceM = 0;

    // ---------- Rendering / projection ----------
    private static void registerWorldProjection() {
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            visibleOnScreen = false;
            Waypoint wp = active;
            MinecraftClient mc = MinecraftClient.getInstance();

            if (wp == null || mc.player == null || mc.world == null) return;
            if (!sameDimension(mc.world, wp.dimKey)) return;

            // 1. Get positions and matrices
            Camera camera = ctx.camera();
            Vec3d worldPos = new Vec3d(wp.pos.getX() + 0.5, wp.pos.getY() + 1.2, wp.pos.getZ() + 0.5);
            Matrix4f projectionMatrix = new Matrix4f(ctx.projectionMatrix());

            // 2. Manually construct the View Matrix.
            Matrix4f viewMatrix = new Matrix4f();
            viewMatrix.rotate(camera.getRotation().conjugate()); // Apply inverse rotation
            viewMatrix.translate(-(float)camera.getPos().x, -(float)camera.getPos().y, -(float)camera.getPos().z); // Apply inverse translation

            // 3. Combine matrices into the final View-Projection matrix
            Matrix4f viewProjectionMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix);

            // 4. Create a 4D vector of our ABSOLUTE world position
            Vector4f pos = new Vector4f((float) worldPos.x, (float) worldPos.y, (float) worldPos.z, 1.0f);

            // 5. Transform the vector from World Space to Clip Space
            viewProjectionMatrix.transform(pos);

            // 6. If w is not positive, the point is behind the camera. Do not render.
            if (pos.w <= 0.0f) {
                return;
            }

            // 7. Perform perspective division to get Normalized Device Coordinates (NDC) [-1 to 1 range]
            pos.x /= pos.w;
            pos.y /= pos.w;

            // 8. Convert NDC to screen coordinates
            int screenWidth = mc.getWindow().getScaledWidth();
            int screenHeight = mc.getWindow().getScaledHeight();
            screenX = (int) Math.round((pos.x + 1.0) / 2.0 * screenWidth);
            screenY = (int) Math.round((1.0 - pos.y) / 2.0 * screenHeight);

            // 9. Check if the calculated coordinates are within the screen bounds
            if (screenX >= 0 && screenX <= screenWidth && screenY >= 0 && screenY <= screenHeight) {
                lastDistanceM = (int) Math.round(camera.getPos().distanceTo(worldPos));
                visibleOnScreen = true;
            }
        });
    }

    private static void registerHudElement() {
        HudElement element = (DrawContext context, RenderTickCounter tickCounter) -> {
            if (!visibleOnScreen) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            Waypoint wp = active;
            if (mc == null || wp == null) return;
            TextRenderer tr = mc.textRenderer;
            String label = wp.name + " — " + lastDistanceM + "m";
            Text line = Text.literal(label);
            int tw = tr.getWidth(line);
            int th = tr.fontHeight;
            int padding = 3;
            int sw = context.getScaledWindowWidth();
            int sh = context.getScaledWindowHeight();
            int x = screenX - tw / 2;
            int y = screenY - th - 6;
            x = Math.max(2, Math.min(x, sw - tw - 2));
            y = Math.max(2, Math.min(y, sh - th - 2));
            int bg = 0x80000000;
            context.fill(x - padding, y - padding, x + tw + padding, y + th + padding, bg);
            context.drawText(tr, line, x, y, 0xFFFFFFFF, true);
        };
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.of("pricebook-asmp", "waypoint_label"), element);
    }

    private static void registerAutoClearNearGoal() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Waypoint wp = active;
            if (wp == null || client.player == null || client.world == null) return;
            if (!sameDimension(client.world, wp.dimKey)) return;
            if (client.player.getPos().distanceTo(Vec3d.ofCenter(wp.pos)) < 10) {
                clear();
            }
        });
    }

    private static boolean sameDimension(World world, String wanted) {
        return normalizeDim(world.getRegistryKey().getValue().toString()).equals(normalizeDim(wanted));
    }

    private static String normalizeDim(String dim) {
        if (dim == null) return "minecraft:overworld";
        String d = dim.toLowerCase(Locale.ROOT);
        if (d.endsWith("overworld")) return "minecraft:overworld";
        if (d.contains("nether")) return "minecraft:the_nether";
        if (d.contains("end")) return "minecraft:the_end";
        return dim;
    }
}