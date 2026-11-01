package com.meakaandre.mapmod.selftest.client;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapLock;
import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.map.MapDraw;
import com.meakaandre.mapmod.client.map.MapHud;
import com.meakaandre.mapmod.client.map.MapScreen;
import com.meakaandre.mapmod.client.map.MapToast;
import com.meakaandre.mapmod.client.map.WaypointScreen;
import com.meakaandre.mapmod.client.map.Waypoints;
import com.meakaandre.mapmod.client.paint.PaintMap;
import com.mojang.blaze3d.platform.InputConstants;
import com.meakaandre.mapmod.map.MapMarker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * MM-8c's waypoint key and its "New waypoint" screen, on a server with MapMod, driven by the smoke run ({@code check
 * waypoints}, then {@code check waypoints rejoin} once the server has sent everything again, and {@code check waypoints
 * nether} in the Nether). The key is pressed the way the keyboard does (its mapping clicked, so the game's own tick opens
 * the screen; on the open map a key event sent to the screen, with the mouse where it is), and the screen is filled in
 * through its boxes, saved or cancelled with its buttons' actions or with Enter and Esc. Logs "SELFTEST client" checks and "SELFTEST client done
 * waypoints[ rejoin| nether]" at the end of each part; the first part ends with "SELFTEST client waypoints left".
 */
final class WaypointClientTests {
    /** Whose locks the lock check sets (and lifts). */
    private static final Identifier OWNER = Identifier.fromNamespaceAndPath("mapmod_selftest", "waypoints");
    /** Ticks to wait for a marker to come back from the server at most. */
    private static final int PATIENCE = 200;

    private static String part = "";
    private static int step, ticks, pass, fail;
    private static int n0, px, py, pz, cursorX, cursorY, cursorZ;
    private static long refused0, toastsAt, framesAt;
    private static boolean openedLocked;
    private static String cursorName = "";
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

    private static @Nullable WaypointScreen form(Minecraft mc) {
        return mc.gui.screen() instanceof WaypointScreen w ? w : null;
    }

    /** A key press on a screen, as the keyboard sends it. */
    private static void key(Screen screen, int key) {
        screen.keyPressed(new KeyEvent(key, 0, 0));
    }

    private static void main(Minecraft mc) {
        switch (step) {
            case 1 -> {
                close(mc);
                check("the key is \"Add waypoint\", B by default, under MapMod in the controls",
                    Waypoints.ADD.getDefaultKey().getValue() == Waypoints.DEFAULT_KEY && Waypoints.DEFAULT_KEY == InputConstants.KEY_B
                        && Component.translatable("key.category.mapmod.map").getString().equals("MapMod")
                        && Component.translatable(Waypoints.ADD.getName()).getString().equals("Add waypoint"),
                    Waypoints.ADD.getName() + ", " + Component.translatable("key.category.mapmod.map").getString());
                n0 = Waypoints.next();
                px = mc.player.getBlockX();
                py = mc.player.getBlockY();
                pz = mc.player.getBlockZ();
                markersAt = MapClient.markers.size();
                press();
                next();
            }
            case 2 -> {
                // B in game: the screen, filled in with the player's block, the next name (all selected) and colour, pinned
                WaypointScreen w = form(mc);
                if (w == null && ticks < 40) return;
                check("B in game opens \"New waypoint\" and places nothing yet", w != null && MapClient.markers.size() == markersAt, "");
                if (w == null) {
                    done();
                    return;
                }
                check("it is filled in with the player's block, \"" + nameOf(n0) + "\" (all of it selected, so typing replaces it), the next colour, pinned",
                    w.xValue().equals(Integer.toString(px)) && w.yValue().equals(Integer.toString(py)) && w.zValue().equals(Integer.toString(pz))
                        && w.nameValue().equals(nameOf(n0)) && w.nameSelected().equals(nameOf(n0)) && w.colourValue() == Waypoints.colour(n0) && w.pinValue()
                        && w.dimension().equals("minecraft:overworld") && w.saveActive() && w.parent() == null,
                    String.format(Locale.ROOT, "%s %s %s, \"%s\" (selected \"%s\"), colour %d, pin %s, in %s; the player at %d %d %d", w.xValue(), w.yValue(), w.zValue(),
                        w.nameValue(), w.nameSelected(), w.colourValue(), w.pinValue(), w.dimension(), px, py, pz));
                // a number that is not one turns Save off; Enter then saves nothing
                w.fill(null, "12x", null, null);
                boolean off = !w.saveActive();
                key(w, InputConstants.KEY_RETURN);
                check("a number that is not one turns Save off (and Enter saves nothing)", off && form(mc) == w && !w.saved(), "");
                w.fill(null, Integer.toString(px), null, null);
                toastsAt = MapToast.shown;
                // Enter saves it as it is
                key(w, InputConstants.KEY_RETURN);
                check("Enter saves, and the screen closes back to the game", w.saved() && mc.gui.screen() == null, String.valueOf(mc.gui.screen()));
                next();
            }
            case 3 -> {
                MapMarker m = mine(mc, nameOf(n0));
                if (m == null && ticks < PATIENCE) return;
                check("saved as filled in: \"" + nameOf(n0) + "\" where the player stands, in the Overworld", m != null && m.x == px && m.y == py && m.z == pz
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
            case 4 -> {
                if (MapHud.compassFrames < framesAt + 5 && ticks < 100) return;
                MapMarker m = byId(deletedId);
                check("the compass strip draws its pin", m != null && MapHud.lastPins.contains(m.pinKey()),
                    (MapHud.compassFrames - framesAt) + " compass frames, pins drawn " + MapHud.lastPins);
                press();
                next();
            }
            case 5 -> {
                // edited: name, all three numbers and the colour
                WaypointScreen w = form(mc);
                if (w == null && ticks < 40) return;
                check("B again opens it with the next name and colour", w != null && w.nameValue().equals(nameOf(n0 + 1)) && w.colourValue() == Waypoints.colour(n0 + 1)
                    && Waypoints.colour(n0 + 1) != Waypoints.colour(n0), w == null ? "no screen" : w.nameValue() + ", colour " + w.colourValue());
                if (w == null) {
                    done();
                    return;
                }
                w.fill("Base camp", Integer.toString(px + 5), Integer.toString(py + 2), Integer.toString(pz - 7));
                w.pick(6);
                w.save();
                next();
            }
            case 6 -> {
                MapMarker m = mine(mc, "Base camp");
                if (m == null && ticks < PATIENCE) return;
                check("saved with an edited name, numbers and colour, the waypoint is just that, and pinned", m != null && m.x == px + 5 && m.y == py + 2 && m.z == pz - 7
                    && m.colour == 6 && MapClient.pinned(m.pinKey()), m == null ? "none" : String.format(Locale.ROOT, "%d %d %d colour %d", m.x, m.y, m.z, m.colour));
                markersAt = MapClient.markers.size();
                press();
                next();
            }
            case 7 -> {
                // Cancel, and Esc: nothing
                WaypointScreen w = form(mc);
                if (w == null && ticks < 40) return;
                if (w == null) {
                    check("B opens it to cancel", false, "");
                    done();
                    return;
                }
                w.fill("Never placed", null, null, null);
                w.cancel();
                boolean closed = mc.gui.screen() == null;
                press();
                check("Cancel closes it back to the game", closed && w.cancelled(), "");
                next();
            }
            case 8 -> {
                WaypointScreen w = form(mc);
                if (w == null && ticks < 40) return;
                if (w != null) {
                    w.fill("Never placed either", null, null, null);
                    key(w, InputConstants.KEY_ESCAPE);
                }
                check("Esc cancels it", w != null && w.cancelled() && mc.gui.screen() == null, "");
                next();
            }
            case 9 -> {
                if (ticks < 40) return;
                check("Cancel and Esc place nothing", MapClient.markers.size() == markersAt && mine(mc, "Never placed") == null && mine(mc, "Never placed either") == null,
                    (MapClient.markers.size() - markersAt) + " new markers");
                // on the open map: where the mouse points
                MapScreen screen = new MapScreen();
                screen.centre(px, pz, 2f);
                MapClient.show(screen);
                next();
            }
            case 10 -> {
                if (!(mc.gui.screen() instanceof MapScreen screen)) {
                    if (ticks < 40) return;
                    check("the map opens for the waypoint on it", false, "");
                    done();
                    return;
                }
                if (ticks < 10) return;
                MapDraw.View v = screen.view();
                double sx = screen.lastMouseX(), sy = screen.lastMouseY();
                cursorX = (int) Math.floor(v.wx(sx));
                cursorZ = (int) Math.floor(v.wz(sy));
                Integer h = PaintMap.height(cursorX, cursorZ);
                cursorY = h != null ? h + 1 : mc.player.getBlockY();
                markersAt = MapClient.markers.size();
                // B on the map, as the keyboard sends it to the screen
                key(screen, InputConstants.KEY_B);
                WaypointScreen w = form(mc);
                check("B on the map opens \"New waypoint\" filled in with where the mouse points (Y the captured ground's there)",
                    w != null && w.xValue().equals(Integer.toString(cursorX)) && w.zValue().equals(Integer.toString(cursorZ)) && w.yValue().equals(Integer.toString(cursorY))
                        && w.parent() == screen, w == null ? "no screen (mouse at " + sx + ", " + sy + ")"
                        : w.xValue() + " " + w.yValue() + " " + w.zValue() + " for " + cursorX + " " + cursorY + " " + cursorZ + (h == null ? " (not captured)" : ""));
                if (w == null) {
                    done();
                    return;
                }
                // the name it was given (the numbering goes on from the highest "Waypoint N" left, "Base camp" not counting)
                cursorName = w.nameValue();
                w.save();
                check("saving it goes back to the map", mc.gui.screen() == screen, String.valueOf(mc.gui.screen()));
                next();
            }
            case 11 -> {
                MapMarker m = mine(mc, cursorName);
                if (m == null && ticks < PATIENCE) return;
                check("on the map it is placed where the mouse pointed, not where the player stands", m != null && m.x == cursorX && m.z == cursorZ && m.y == cursorY
                    && MapClient.pinned(m.pinKey()), m == null ? "none" : m.x + " " + m.y + " " + m.z + " for " + cursorX + " " + cursorY + " " + cursorZ);
                if (m == null || !(mc.gui.screen() instanceof MapScreen screen)) {
                    done();
                    return;
                }
                // Cancel from the map: back to the map, nothing placed
                int before = MapClient.markers.size();
                key(screen, InputConstants.KEY_B);
                WaypointScreen w = form(mc);
                if (w != null) w.cancel();
                check("cancelling one opened from the map goes back to the map and places nothing", w != null && mc.gui.screen() == screen && MapClient.markers.size() == before, "");
                // right click on it: the form, renamed and recoloured
                MapDraw.View v = screen.view();
                boolean form = screen.rightClick(v.sx(m.x + 0.5), v.sy(m.z + 0.5));
                MapMarker in = screen.formMarker();
                check("right click on the waypoint opens its form", form && in != null && in.id.equals(m.id), in == null ? "no form for it" : in.name);
                if (in != null) {
                    screen.formName("Camp");
                    screen.formColour(5);
                    screen.formSave();
                }
                deletedId = m.id;
                next();
            }
            case 12 -> {
                MapMarker m = byId(deletedId);
                if ((m == null || !m.name.equals("Camp")) && ticks < PATIENCE) return;
                check("its form renames it and changes its colour", m != null && m.name.equals("Camp") && m.colour == 5, m == null ? "gone" : m.name + ", colour " + m.colour);
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
            case 13 -> {
                MapMarker m = byId(deletedId);
                if (m != null && ticks < PATIENCE) return;
                check("its form removes it", m == null && MapClient.PINS.keySet().stream().noneMatch(k -> k.equals("marker:" + deletedId)), "");
                // the locks: the markers layer, then the whole map; no screen opens
                MapLock.lockLayer(OWNER, MapLock.MARKERS);
                refused0 = Waypoints.refused;
                markersAt = MapClient.markers.size();
                boolean opened = false;
                if (mc.gui.screen() instanceof MapScreen screen) {
                    key(screen, InputConstants.KEY_B);
                    opened = form(mc) != null;
                }
                close(mc);
                press();
                openedLocked = opened;
                next();
            }
            case 14 -> {
                // the toast shows for 2.5 s: looked at well within that
                if (ticks < 20) return;
                Component toast = MapToast.current();
                check("while a mod locks the markers, B opens no screen and says \"Map locked\" (in game and on the map)",
                    !openedLocked && form(mc) == null && Waypoints.refused >= refused0 + 2 && MapClient.markers.size() == markersAt && toast != null
                        && toast.getString().equals("Map locked"),
                    (Waypoints.refused - refused0) + " refused, screen " + mc.gui.screen() + ", toast " + (toast == null ? "none" : toast.getString()));
                MapLock.unlockLayer(OWNER, MapLock.MARKERS);
                MapLock.lockMap(OWNER, Component.literal("Locked by the waypoint check"));
                refused0 = Waypoints.refused;
                toastsAt = MapToast.shown;
                press();
                next();
            }
            case 15 -> {
                if (ticks < 20) return;
                Component toast = MapToast.current();
                check("while a mod locks the map, B opens no screen and says \"Map locked\"", form(mc) == null && Waypoints.refused >= refused0 + 1
                    && MapToast.shown > toastsAt && toast != null && toast.getString().equals("Map locked"),
                    (Waypoints.refused - refused0) + " refused, screen " + mc.gui.screen());
                MapLock.unlockAll(OWNER);
                // the one kept for the rejoin
                n0 = Waypoints.next();
                press();
                next();
            }
            case 16 -> {
                WaypointScreen w = form(mc);
                if (w == null && ticks < 40) return;
                if (w != null) w.save();
                next();
            }
            case 17 -> {
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

    /** In the Nether: the screen is the Nether's, and the waypoint saved there, where the player stands, on the compass. */
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
        if (step == 2) {
            WaypointScreen w = form(mc);
            if (w == null && ticks < 40) return;
            check("B in the Nether opens \"New waypoint\" for the Nether, at the player", w != null && !w.dimension().equals("minecraft:overworld")
                && w.xValue().equals(Integer.toString(px)) && w.zValue().equals(Integer.toString(pz)), w == null ? "no screen" : w.dimension());
            if (w != null) w.save();
            next();
            return;
        }
        MapMarker m = mine(mc, nameOf(n0));
        if ((m == null || !MapHud.lastPins.contains(m.pinKey())) && ticks < PATIENCE) return;
        String dim = mc.level.dimension().identifier().toString();
        check("saved in the Nether, it is the Nether's, where the player stands", m != null && m.dimension.equals(dim) && !dim.equals("minecraft:overworld")
            && m.x == px && m.z == pz, m == null ? "none" : m.dimension + " " + m.x + " " + m.z);
        check("the compass strip shows it in the Nether", m != null && MapHud.lastPins.contains(m.pinKey()), "pins drawn " + MapHud.lastPins);
        done();
    }
}
