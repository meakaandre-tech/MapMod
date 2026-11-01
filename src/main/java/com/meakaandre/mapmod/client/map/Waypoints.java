package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.api.MapGlyphs;
import com.meakaandre.mapmod.api.MapLock;
import com.meakaandre.mapmod.map.MapMarker;
import com.meakaandre.mapmod.map.MapPayloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Waypoints (MM-8c): a key ("Add waypoint", B by default, under MapMod in the controls) opens "New waypoint"
 * ({@link WaypointScreen}) filled in with where the player stands, in the dimension they are in, or, on the fullscreen
 * map, where the mouse points. Saved, a waypoint is a map marker like any other: named "Waypoint N" unless renamed (one
 * more than the highest number so far), a flag, its colour the next of a few unless another is picked, private, and
 * pinned to the compass strip once it is back unless that was unticked. It is kept with the other markers (on the
 * server, per world; on a server without MapMod by the client, per server) and changed or removed on the map with a
 * right click. While a mod locks the map or its markers ({@link MapLock}) the key opens nothing and says so.
 */
public final class Waypoints {
    /** The key's default. */
    public static final int DEFAULT_KEY = InputConstants.KEY_B;
    public static final KeyMapping ADD = new KeyMapping("key.mapmod.waypoint", DEFAULT_KEY, MapClient.CATEGORY);
    /** The colours new waypoints take in turn (of {@link MapMarker#COLOURS}): sky blue, orange, green, purple, red, yellow. */
    public static final int[] COLOURS = {4, 0, 3, 6, 1, 2};
    public static final int ICON = MapGlyphs.FLAG;
    /** How long a waypoint sent to the server waits to come back and be pinned. */
    private static final long PENDING_NANOS = 60_000_000_000L;

    /** A waypoint sent off and not back yet: pinned when it comes back with the markers, if it is to be. */
    private record Pending(String name, int x, int z, String dimension, boolean pin, long at) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    /** Waypoints placed, and key presses a lock (or a server not ready) turned away, since the game started (for the selftests). */
    public static long added, refused;
    /** The name of the last waypoint placed (for the selftests). */
    public static @Nullable String lastName;

    private Waypoints() {
    }

    static void register() {
        KeyMappingHelper.registerKeyMapping(ADD);
    }

    static void tick(Minecraft mc) {
        if (mc.gui.screen() == null) while (ADD.consumeClick()) atPlayer();
    }

    /** True while a mod locks the map or its markers: no waypoint can be placed. */
    public static boolean locked() {
        return MapLock.mapLocked() || MapLock.layerLocked(MapLock.MARKERS);
    }

    /** "New waypoint" filled in with where the player stands, in the dimension they are in (the key in game). */
    public static boolean atPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        return open(mc.gui.screen(), mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), mc.level.dimension().identifier().toString());
    }

    /**
     * Opens "New waypoint" at a block of a dimension, going back to {@code parent} when it closes; or, while a mod locks
     * the map or its markers (or the server's map is not there yet), opens nothing and says why. Returns whether it opened.
     */
    public static boolean open(@Nullable net.minecraft.client.gui.screens.Screen parent, int x, int y, int z, String dimension) {
        if (!allowed()) return false;
        Minecraft.getInstance().gui.setScreen(new WaypointScreen(parent, x, y, z, dimension));
        return true;
    }

    /** True if a waypoint may be placed now; else says why ("Map locked") and counts it refused. */
    private static boolean allowed() {
        if (locked()) {
            refused++;
            MapToast.show(Component.translatable("mapmod.map.locked_title"));
            return false;
        }
        if (!LocalMap.active() && !ClientPlayNetworking.canSend(MapPayloads.MarkerEdit.TYPE)) {
            refused++;
            MapToast.show(Component.translatable("mapmod.waypoint.not_yet"));
            return false;
        }
        return true;
    }

    /**
     * Places a waypoint as "New waypoint" saves it: sends it off, says so, and pins it once it is back if {@code pin}.
     * Says why not instead while locked. Returns whether it was placed.
     */
    public static boolean place(String name, int x, int y, int z, String dimension, int colour, boolean pin) {
        if (!allowed()) return false;
        if (name.length() > MapMarker.MAX_NAME) name = name.substring(0, MapMarker.MAX_NAME);
        MapMarker m = new MapMarker();
        m.x = x;
        m.y = y;
        m.z = z;
        m.dimension = dimension;
        m.name = name;
        m.icon = ICON;
        m.colour = Math.floorMod(colour, MapMarker.COLOURS.length);
        m.shared = false;
        prune();
        PENDING.add(new Pending(name, x, z, dimension, pin, System.nanoTime()));
        if (LocalMap.active()) LocalMap.apply(MapPayloads.ADD, m);
        else ClientPlayNetworking.send(new MapPayloads.MarkerEdit(MapPayloads.ADD, m));
        added++;
        lastName = name;
        MapToast.show(Component.translatable("mapmod.waypoint.added", name));
        return true;
    }

    /** The default name of the n-th waypoint ("Waypoint N"). */
    public static String name(int n) {
        String name = Component.translatable("mapmod.waypoint.name", Integer.toString(n)).getString();
        return name.length() > MapMarker.MAX_NAME ? name.substring(0, MapMarker.MAX_NAME) : name;
    }

    /** The colour of the n-th waypoint (of {@link MapMarker#COLOURS}). */
    public static int colour(int n) {
        return COLOURS[Math.floorMod(n - 1, COLOURS.length)];
    }

    /** The next waypoint's number: one more than the highest of the player's markers named as waypoints are. */
    public static int next() {
        Minecraft mc = Minecraft.getInstance();
        String me = mc.player == null ? "" : mc.player.getUUID().toString();
        String template = Component.translatable("mapmod.waypoint.name", "\u0000").getString();
        int cut = template.indexOf('\u0000');
        String before = cut < 0 ? template : template.substring(0, cut), after = cut < 0 ? "" : template.substring(cut + 1);
        int high = 0;
        for (MapMarker m : MapClient.markers) {
            if (m.death || !m.owner.equals(me) || m.name.length() <= before.length() + after.length()) continue;
            if (!m.name.startsWith(before) || !m.name.endsWith(after)) continue;
            String digits = m.name.substring(before.length(), m.name.length() - after.length());
            if (digits.isEmpty() || digits.length() > 6 || !digits.chars().allMatch(Character::isDigit)) continue;
            high = Math.max(high, Integer.parseInt(digits));
        }
        return high + 1;
    }

    /** A marker of the player's that has just arrived: true if it is a waypoint sent off from here to be pinned. */
    static boolean claim(MapMarker m) {
        prune();
        for (int i = 0; i < PENDING.size(); i++) {
            Pending p = PENDING.get(i);
            if (p.name.equals(m.name) && p.x == m.x && p.z == m.z && p.dimension.equals(m.dimension)) {
                PENDING.remove(i);
                return p.pin;
            }
        }
        return false;
    }

    private static void prune() {
        long now = System.nanoTime();
        PENDING.removeIf(p -> now - p.at > PENDING_NANOS);
    }

    /** Leaving the server: waypoints still on their way are dropped. */
    static void clear() {
        PENDING.clear();
    }
}
