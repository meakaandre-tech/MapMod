package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.api.MapGlyphs;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Waypoints in the world, as Xaero's map shows them: every pinned place (your markers, finds you pinned, and the spot
 * where you last died, which is pinned by itself) drawn where it is in the world, with its icon, its name and how far it
 * is, so the way to it can be seen without opening the map. A death waypoint goes away once you get back to it.
 */
public final class Waypoints {
    /** Blocks above a waypoint's spot its icon floats. */
    private static final double LIFT = 1.5;
    /** Within this many blocks of a death waypoint it is reached, and unpinned. */
    private static final double REACHED = 4;
    /** Under this many blocks the name is left off (the icon is enough up close). */
    private static final double NAME_FROM = 6;

    private Waypoints() {
    }

    public static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !MapClient.mappedHere() || MapClient.PINS.isEmpty()) return;
        int w = g.guiWidth(), h = g.guiHeight();
        List<MapClient.Pin> reached = new ArrayList<>();
        for (MapClient.Pin pin : MapClient.PINS.values()) {
            double dist = Math.sqrt(player.distanceToSqr(pin.x() + 0.5, pin.y(), pin.z() + 0.5));
            boolean death = pin.icon() == MapGlyphs.SKULL;
            if (death && dist < REACHED) {
                reached.add(pin);
                continue;
            }
            double[] at = project(pin.x() + 0.5, pin.y() + LIFT, pin.z() + 0.5, w, h);
            if (at == null) continue;
            int x = (int) Math.round(at[0]), y = (int) Math.round(at[1]);
            if (x < -16 || y < -16 || x > w + 16 || y > h + 16) continue;
            MapIcons.centred(g, pin.icon(), x, y, pin.argb());
            String name = death ? Component.translatable("mapmod.map.death_marker").getString() : pin.label();
            String far = MapDraw.distance(dist);
            String text = dist < NAME_FROM || name.isEmpty() ? far : name + "  " + far;
            int tw = mc.font.width(text);
            g.fill(x - tw / 2 - 2, y + 7, x + tw / 2 + 2, y + 18, 0x90101418);
            g.centeredText(mc.font, text, x, y + 9, 0xFFE6E6E6);
        }
        for (MapClient.Pin pin : reached) MapClient.togglePin(pin);
    }

    /** Where a world point shows on a screen of w by h (GUI pixels), as the camera sees it now; null when behind it. */
    public static double @Nullable [] project(double x, double y, double z, int w, int h) {
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        if (!camera.isInitialized()) return null;
        Vec3 eye = camera.position();
        Vector3fc fwd = camera.forwardVector(), up = camera.upVector(), left = camera.leftVector();
        // the field of view (vertical), in degrees; taken as radians should it be that small
        double fov = camera.getFov();
        if (fov <= 0) fov = Minecraft.getInstance().options.fov().get();
        double tan = Math.tan((fov < 3.2 ? fov : Math.toRadians(fov)) / 2);
        double rx = x - eye.x, ry = y - eye.y, rz = z - eye.z;
        double depth = rx * fwd.x() + ry * fwd.y() + rz * fwd.z();
        if (depth < 0.5) return null;
        double sx = (rx * left.x() + ry * left.y() + rz * left.z()) / depth / tan / ((double) w / h);
        double sy = (rx * up.x() + ry * up.y() + rz * up.z()) / depth / tan;
        return new double[]{w / 2.0 - sx * w / 2.0, h / 2.0 - sy * h / 2.0};
    }
}
