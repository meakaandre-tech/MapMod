package com.meakaandre.mapmod.selftest.client;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapLock;
import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.map.MapDraw;
import com.meakaandre.mapmod.client.map.MapHud;
import com.meakaandre.mapmod.client.map.MapScreen;
import com.meakaandre.mapmod.client.map.MapToast;
import com.meakaandre.mapmod.client.map.Waypoints;
import com.meakaandre.mapmod.map.MapMarker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * MM-8c's waypoint key, on a server with MapMod, driven by the smoke run ({@code check waypoints}, then {@code check
 * waypoints rejoin} once the server has sent everything again, and {@code check waypoints nether} in the Nether). The key
 * is pressed the way the keyboard does (its mapping clicked), so the game's own tick places the waypoint; on the open map
 * the screen's own handler is called with a spot on it. Logs "SELFTEST client" checks and "SELFTEST client done
 * waypoints[ rejoin| nether]" at the end of each part; the first part ends with "SELFTEST client waypoints left".
 */
final class WaypointClientTests {
    /** Whose locks the lock check sets (and lifts). */
    private static final Identifier OWNER = Identifier.fromNamespaceAndPath("mapmod_selftest", "waypoints");
    /** Ticks to wait for a marker to come back from the server at most. */
    private static final int PATIENCE = 200;

    private static String part = "";
    private static int step, ticks, pass, fail;
    private static int n0, px, py, pz, cursorX, cursorZ;
    private static long refusedAt, toastsAt, framesAt;
    private static int markersAt;
    private static @Nullable String rejoinId, rejoinName;
    private static @Nullable String deletedId;

    private WaypointClientTests() {
    }

    private static void check(String what, boolean ok, String detail) {
        MapMod.LOGGER.info("SELFTEST client {} waypoints: {}{}", ok ? "PASS" : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
        if (ok) pass++;
        else fail++;
    }

    /** Starts a part: "" (the key in game and on the map, changing, removing, the locks; then leaves), "rejoin" or "nether". */
    static void start(String which) {
        part = which;
        step = 1;
        ticks = 0;
        pass = fail = 0;
    }

    private static void done() {
        MapMod.LOGGER.info("SELFTEST client done waypoints{}: {} passed, {} failed", part.isEmpty() ? "" : " " + part, pass, fail);
        MapLock.unlockAll(OWNER);
        step = 0;
    }

    private static void next() {
        step++;
        ticks = 0;
    }

    /** Presses the waypoint key the way the keyboard does: the game's tick takes the click and places the waypoint. */
    private static void press() {
        KeyMapping.click(Waypoints.ADD.getDefaultKey());
    }

    private static String me(Minecraft mc) {
        return mc.player == null ? "" : mc.player.getUUID().toString();
    }

    private static @Nullable MapMarker mine(Minecraft mc, String name) {
        for (MapMarker m : MapClient.markers) if (m.name.equals(name) && m.owner.equals(me(mc))) return m;
        return null;
    }

    private static @Nullable MapMarker byId(@Nullable String id) {
        for (MapMarker m : MapClient.markers) if (m.id.equals(id)) return m;
        return null;
    }

    private static String nameOf(int n) {
        return Component.translatable("mapmod.waypoint.name", Integer.toString(n)).getString();
    }

    private static void close(Minecraft mc) {
        if (mc.gui.screen() != null) mc.gui.setScreen(null);
    }

    static void tick(Minecraft mc) {
        if (step == 0 || mc.player == null || mc.level == null) return;
        ticks++;
        switch (part) {
            case "rejoin" -> rejoin(mc);
            case "nether" -> nether(mc);
            default -> main(mc);
        }
    }

    private static void main(Minecraft mc) {
        switch (step) {
            case 1 -> {
                close(mc);
                check("the key is \"Add waypoint\", B by default, under MapMod in the controls",
                    Waypoints.ADD.getDefaultKey().getValue() == Waypoints.DEFAULT_KEY && Waypoints.DEFAULT_KEY == com.mojang.blaze3d.platform.InputConstants.KEY_B
                        && Component.translatable("key.category.mapmod.map").getString().equals("MapMod")
                        && Component.translatable(Waypoints.ADD.getName()).getString().equals("Add waypoint"),
                    Waypoints.ADD.getName() + ", " + Component.translatable("key.category.mapmod.map").getString());
                n0 = Waypoints.next();
                px = mc.player.getBlockX();
                py = mc.player.getBlockY();
                pz = mc.player.getBlockZ();
                toastsAt = MapToast.shown;
                press();
                next();
            }
            case 2 -> {
                // the first waypoint: where the player stands, its name, colour and pin
                MapMarker m = mine(mc, nameOf(n0));
                if (m == null && ticks < PATIENCE) return;
                check("B in game places \"" + nameOf(n0) + "\" where the player stands, in the Overworld", m != null && m.x == px && m.y == py && m.z == pz
                    && m.dimension.equals("minecraft:overworld") && !m.shared && !m.death,
                    m == null ? "none after " + ticks + " ticks" : String.format(Locale.ROOT, "%d %d %d in %s; the player at %d %d %d", m.x, m.y, m.z, m.dimension, px, py, pz));
                Component toast = MapToast.current();
                check("a toast says so: \"Waypoint added (" + nameOf(n0) + ")\"", MapToast.shown > toastsAt && toast != null
                    && toast.getString().equals("Waypoint added (" + nameOf(n0) + ")"), toast == null ? "no toast" : toast.getString());
                check("it is pinned to the compass strip", m != null && MapClient.pinned(m.pinKey()), "");
                check("its icon and colour are the waypoint's", m != null && m.icon == Waypoints.ICON && m.colour == Waypoints.colour(n0),
                    m == null ? "" : "icon " + m.icon + ", colour " + m.colour);
                framesAt = MapHud.compassFrames;
                deletedId = m == null ? null : m.id;
                next();
            }
            case 3 -> {
                if (MapHud.compassFrames < framesAt + 5 && ticks < 100) return;
                MapMarker m = byId(deletedId);
                check("the compass strip draws its pin", m != null && MapHud.lastPins.contains(m.pinKey()),
                    (MapHud.compassFrames - framesAt) + " compass frames, pins drawn " + MapHud.lastPins);
                press();
                next();
            }
            case 4 -> {
                // the second: the next number, the next colour
                MapMarker m = mine(mc, nameOf(n0 + 1));
                if (m == null && ticks < PATIENCE) return;
                check("B again places \"" + nameOf(n0 + 1) + "\" in the next colour", m != null && m.colour == Waypoints.colour(n0 + 1)
                    && Waypoints.colour(n0 + 1) != Waypoints.colour(n0) && MapClient.pinned(m.pinKey()), m == null ? "none" : "colour " + m.colour);
                // on the open map: where the mouse points
                MapScreen screen = new MapScreen();
                screen.centre(px, pz, 2f);
                MapClient.show(screen);
                next();
            }
            case 5 -> {
                if (!(mc.gui.screen() instanceof MapScreen screen)) {
                    if (ticks < 40) return;
                    check("the map opens for the waypoint on it", false, "");
                    done();
                    return;
                }
                if (ticks < 10) return;
                MapDraw.View v = screen.view();
                double sx = v.sx(px + 40.5), sy = v.sy(pz - 24.5);
                cursorX = (int) Math.floor(v.wx(sx));
                cursorZ = (int) Math.floor(v.wz(sy));
                markersAt = MapClient.markers.size();
                check("B on the open map places a waypoint (the screen's own key handler, at a spot on the map)", screen.addWaypointAt(sx, sy), "");
                next();
            }
            case 6 -> {
                MapMarker m = mine(mc, nameOf(n0 + 2));
                if (m == null && ticks < PATIENCE) return;
                check("on the open map, B places it where the mouse points, not where the player stands", m != null && m.x == cursorX && m.z == cursorZ
                    && MapClient.pinned(m.pinKey()), m == null ? "none" : m.x + " " + m.z + " for " + cursorX + " " + cursorZ);
                if (m == null || !(mc.gui.screen() instanceof MapScreen screen)) {
                    done();
                    return;
                }
                // right click on it: the form, renamed and recoloured
                MapDraw.View v = screen.view();
                boolean form = screen.rightClick(v.sx(m.x + 0.5), v.sy(m.z + 0.5));
                MapMarker in = screen.formMarker();
                check("right click on the waypoint opens its form", form && in != null && in.id.equals(m.id), in == null ? "no form for it" : in.name);
                if (in != null) {
                    screen.formName("Camp");
                    screen.formColour(6);
                    screen.formSave();
                }
                deletedId = m.id;
                next();
            }
            case 7 -> {
                MapMarker m = byId(deletedId);
                if ((m == null || !m.name.equals("Camp")) && ticks < PATIENCE) return;
                check("its form renames it and changes its colour", m != null && m.name.equals("Camp") && m.colour == 6, m == null ? "gone" : m.name + ", colour " + m.colour);
                if (m == null || !(mc.gui.screen() instanceof MapScreen screen)) {
                    done();
                    return;
                }
                MapDraw.View v = screen.view();
                screen.rightClick(v.sx(m.x + 0.5), v.sy(m.z + 0.5));
                boolean button = screen.formTogglePin();
                check("its form unpins it", button && !MapClient.pinned(m.pinKey()), button ? "" : "no pin button");
                screen.formDelete();
                next();
            }
            case 8 -> {
                MapMarker m = byId(deletedId);
                if (m != null && ticks < PATIENCE) return;
                check("its form removes it", m == null && MapClient.PINS.keySet().stream().noneMatch(k -> k.equals("marker:" + deletedId)), "");
                // the locks: the markers layer, then the whole map
                MapLock.lockLayer(OWNER, MapLock.MARKERS);
                refusedAt = Waypoints.refused;
                markersAt = MapClient.markers.size();
                if (mc.gui.screen() instanceof MapScreen screen) screen.addWaypointAt(screen.view().left() + 30, screen.view().top() + 30);
                close(mc);
                press();
                next();
            }
            case 9 -> {
                // the toast shows for 2.5 s: looked at well within that
                if (ticks < 30) return;
                Component toast = MapToast.current();
                check("while a mod locks the markers, B does nothing but say \"Map locked\" (in game and on the map)",
                    Waypoints.refused >= refusedAt + 2 && MapClient.markers.size() == markersAt && toast != null && toast.getString().equals("Map locked"),
                    (Waypoints.refused - refusedAt) + " refused, " + (MapClient.markers.size() - markersAt) + " new markers, toast " + (toast == null ? "none" : toast.getString()));
                MapLock.unlockLayer(OWNER, MapLock.MARKERS);
                MapLock.lockMap(OWNER, Component.literal("Locked by the waypoint check"));
                refusedAt = Waypoints.refused;
                press();
                next();
            }
            case 10 -> {
                if (ticks < 30) return;
                check("while a mod locks the map, B does nothing but say \"Map locked\"", Waypoints.refused >= refusedAt + 1 && MapClient.markers.size() == markersAt,
                    (Waypoints.refused - refusedAt) + " refused, " + (MapClient.markers.size() - markersAt) + " new markers");
                MapLock.unlockAll(OWNER);
                // the one kept for the rejoin
                n0 = Waypoints.next();
                press();
                next();
            }
            case 11 -> {
                MapMarker m = mine(mc, nameOf(n0));
                if ((m == null || !MapClient.pinned(m.pinKey())) && ticks < PATIENCE) return;
                check("once the locks are lifted, B places waypoints again", m != null, "");
                rejoinId = m == null ? null : m.id;
                rejoinName = m == null ? null : m.name;
                // leaving: what the client held of the server goes, as it does on leaving
                MapClient.leaveForTests();
                check("leaving lets the markers and pins go (they are on the server and on disk)", MapClient.markers.isEmpty() && MapClient.PINS.isEmpty(), "");
                MapMod.LOGGER.info("SELFTEST client waypoints left");
                done();
            }
            default -> done();
        }
    }

    /** After the server sent everything again, as on a join: the waypoint is back, and still pinned. */
    private static void rejoin(Minecraft mc) {
        MapMarker m = byId(rejoinId);
        if ((m == null || !MapClient.pinned(m.pinKey())) && ticks < PATIENCE) return;
        check("the waypoint survives a rejoin: back from the server, its name and pin kept", m != null && m.name.equals(rejoinName) && MapClient.pinned(m.pinKey()),
            m == null ? "not back after " + ticks + " ticks" : m.name + (MapClient.pinned(m.pinKey()) ? ", pinned" : ", NOT pinned"));
        check("the one removed stays removed", byId(deletedId) == null && MapClient.markers.stream().noneMatch(x -> x.name.equals("Camp")), "");
        done();
    }

    /** In the Nether: the waypoint is the Nether's, where the player stands, and on the compass there. */
    private static void nether(Minecraft mc) {
        if (step == 1) {
            close(mc);
            n0 = Waypoints.next();
            px = mc.player.getBlockX();
            pz = mc.player.getBlockZ();
            press();
            next();
            return;
        }
        MapMarker m = mine(mc, nameOf(n0));
        if ((m == null || !MapHud.lastPins.contains(m.pinKey())) && ticks < PATIENCE) return;
        String dim = mc.level.dimension().identifier().toString();
        check("B in the Nether places a waypoint there: the Nether's, where the player stands", m != null && m.dimension.equals(dim) && !dim.equals("minecraft:overworld")
            && m.x == px && m.z == pz, m == null ? "none" : m.dimension + " " + m.x + " " + m.z);
        check("the compass strip shows it in the Nether", m != null && MapHud.lastPins.contains(m.pinKey()), "pins drawn " + MapHud.lastPins);
        done();
    }
}
