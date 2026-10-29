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
        Camera camera = mc.gameRenderer.mainCamera();
        if (!camera.isInitialized()) return;
        Vec3 eye = camera.position();
        Vector3fc fwd = camera.forwardVector(), up = camera.upVector(), left = camera.leftVector();
        double tan = Math.tan(Math.toRadians(camera.getFov()) / 2);
        int w = g.guiWidth(), h = g.guiHeight();
        double aspect = (double) w / h;
        List<MapClient.Pin> reached = new ArrayList<>();
        for (MapClient.Pin pin : MapClient.PINS.values()) {
            double dist = Math.sqrt(player.distanceToSqr(pin.x() + 0.5, pin.y(), pin.z() + 0.5));
            boolean death = pin.icon() == MapGlyphs.SKULL;
            if (death && dist < REACHED) {
                reached.add(pin);
                continue;
            }
            double rx = pin.x() + 0.5 - eye.x, ry = pin.y() + LIFT - eye.y, rz = pin.z() + 0.5 - eye.z;
            double depth = rx * fwd.x() + ry * fwd.y() + rz * fwd.z();
            if (depth < 0.5) continue;
            double sx = (rx * left.x() + ry * left.y() + rz * left.z()) / depth / tan / aspect;
            double sy = (rx * up.x() + ry * up.y() + rz * up.z()) / depth / tan;
            int x = (int) Math.round(w / 2.0 - sx * w / 2.0), y = (int) Math.round(h / 2.0 - sy * h / 2.0);
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
}
