package com.meakaandre.mapmod.selftest.client;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.Probe;
import com.meakaandre.mapmod.client.capture.SurfaceClass;
import com.meakaandre.mapmod.client.map.LocalMap;
import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.map.MapDraw;
import com.meakaandre.mapmod.client.map.MapScreen;
import com.meakaandre.mapmod.client.map.MobRadar;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * MM-7, on the server without MapMod: the smoke run builds a flat patch at y 150 round 0,0 in a mushroom fields biome
 * (mycelium west, grass blocks in the middle, stone east) and puts the player on it. Once the client has captured it,
 * the three paint as three clearly different colours (grass green, mycelium as dirt, stone grey; real block colours are
 * MM-8's); the run then
 * replaces the mycelium with grass blocks on the server, and the client's map must follow through its own block-change
 * hook alone. A dozen mooshrooms on the patch must show as a few faces with counts, not a dozen. Then the map's top bar
 * at GUI scales 3 and 4 (the run is 1920x1080): nothing in it overlaps, and the hint fits. Logs "SELFTEST colours shot
 * ..." where the run takes a screenshot, and "SELFTEST colours done" at the end.
 */
final class ColourClientTests {
    /** The patch's three spots (z 0): a cell wholly inside each strip. */
    static final int MYCELIUM_X = -16, GRASS_X = 0, STONE_X = 16, Z = 0, TOP = 150;
    /** Ticks of the client's own map before giving up on the patch being captured, and on the replacement reaching it. */
    private static final int CAPTURE_PATIENCE = 2400, REPLACE_PATIENCE = 1200;

    private static int state, waited, pass, fail, guiBefore = 2;
    private static int colourMycelium = -1;
    private static boolean asked;
    private static CompletableFuture<Probe.Result[]> pending;

    private ColourClientTests() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ColourClientTests::tick);
    }

    private static void check(String what, boolean ok, String detail) {
        MapMod.LOGGER.info("SELFTEST colours {} {}{}", ok ? "PASS" : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
        if (ok) pass++;
        else fail++;
    }

    private static CompletableFuture<Probe.Result[]> probeAll(Minecraft mc) {
        CompletableFuture<Probe.Result> m = Probe.at(mc.level, MYCELIUM_X, Z), g = Probe.at(mc.level, GRASS_X, Z), s = Probe.at(mc.level, STONE_X, Z);
        return CompletableFuture.allOf(m, g, s).thenApply(v -> new Probe.Result[]{m.join(), g.join(), s.join()});
    }

    private static boolean onPatch(Probe.Result r) {
        return r != null && r.captured() && r.storedHeight() >= TOP - 1 && r.storedHeight() <= TOP + 1;
    }

    private static void tick(Minecraft mc) {
        if (!LocalMap.active() || mc.player == null || mc.level == null || state < 0) return;
        int t = LocalMap.ticks();
        switch (state) {
            case 0 -> {
                // wait for the patch to be captured
                if (t >= CAPTURE_PATIENCE) {
                    check("the patch is captured", false, "not captured after " + t + " ticks");
                    finish();
                    return;
                }
                if (pending == null && t % 20 == 0) pending = probeAll(mc);
                if (pending == null || !pending.isDone()) return;
                Probe.Result[] r = pending.getNow(null);
                pending = null;
                if (r == null || !onPatch(r[0]) || !onPatch(r[1]) || !onPatch(r[2])) return;
                colours(r);
                state = 1;
            }
            case 1 -> {
                // after the client's own checks have opened and closed the map, a close look at the patch
                if (!MapClientTests.localDone) return;
                if (++waited == 100) {
                    // three markers of one icon and colour a block or two apart: one icon with a count
                    for (int i = 0; i < 3; i++) {
                        com.meakaandre.mapmod.map.MapMarker m = new com.meakaandre.mapmod.map.MapMarker();
                        m.name = "Stacked " + i;
                        m.x = -12 + i;
                        m.y = TOP + 1;
                        m.z = 12 + (i & 1);
                        m.icon = 0;
                        m.colour = 2;
                        LocalMap.apply(com.meakaandre.mapmod.map.MapPayloads.ADD, m);
                    }
                    MapScreen screen = new MapScreen();
                    screen.centre(0, 0, 3f);
                    MapClient.show(screen);
                }
                if (waited == 220) {
                    stacking();
                    MapMod.LOGGER.info("SELFTEST colours shot patch");
                    state = 2;
                    waited = 0;
                }
            }
            case 2 -> {
                // the run replaces the mycelium with grass blocks on the server: the map follows by itself
                if (++waited > REPLACE_PATIENCE) {
                    check("replacing mycelium with grass changes the painted colour", false, "no change after " + waited + " ticks");
                    state = 3;
                    waited = 0;
                    return;
                }
                if (pending == null && waited % 10 == 0) pending = probeAll(mc);
                if (pending == null || !pending.isDone()) return;
                Probe.Result[] r = pending.getNow(null);
                pending = null;
                if (r == null || r[0].storedClass() != SurfaceClass.GRASS) return;
                for (Probe.Result p : r) for (String line : p.lines()) MapMod.LOGGER.info("SELFTEST colours probe after: {}", line);
                check("replacing mycelium with grass changes the painted colour", r[0].colour() != colourMycelium && green(r[0].colour()),
                    String.format(Locale.ROOT, "#%06X -> #%06X after %d ticks", colourMycelium, r[0].colour(), waited));
                state = 3;
                waited = 0;
            }
            case 3 -> {
                if (++waited == 160) {
                    MapMod.LOGGER.info("SELFTEST colours shot replaced");
                    state = 4;
                    waited = 0;
                }
            }
            case 4, 5 -> {
                // the top bar at GUI scale 3, then 4, with the map open (the run's mouse is over the map, so the
                // position under it shows too)
                int gui = state == 4 ? 3 : 4;
                if (++waited == 1) {
                    guiBefore = mc.options.guiScale().get();
                    mc.options.guiScale().set(gui);
                    mc.resizeGui();
                }
                if (waited == 60) {
                    bar(mc, gui);
                    MapMod.LOGGER.info("SELFTEST colours shot gui{}", gui);
                }
                if (waited == 100) {
                    state++;
                    waited = 0;
                }
            }
            default -> {
                mc.options.guiScale().set(guiBefore);
                mc.resizeGui();
                layouts(mc);
                finish();
            }
        }
    }

    private static void finish() {
        if (mcScreenOpen()) Minecraft.getInstance().gui.setScreen(null);
        MapMod.LOGGER.info("SELFTEST colours done: {} passed, {} failed", pass, fail);
        state = -1;
    }

    private static boolean mcScreenOpen() {
        return Minecraft.getInstance().gui.screen() instanceof MapScreen;
    }

    /** The mooshrooms on the patch (a dozen, close together) show as a few faces with counts. */
    private static void stacking() {
        check("mobs of one kind close together are drawn as one face with a count", MobRadar.drawnMobs >= 10 && MobRadar.drawnStacks <= MobRadar.drawnMobs / 2,
            MobRadar.drawnMobs + " mobs as " + MobRadar.drawnStacks + " faces");
        MapDraw.Stacks<String> st = new MapDraw.Stacks<>(6);
        st.add("boat", 100, 100, "a");
        st.add("boat", 103, 101, "b");
        st.add("boat", 104, 99, "c");
        st.add("cart", 102, 100, "d");
        st.add("boat", 140, 100, "e");
        boolean ok = st.stacks().size() == 3 && st.stacks().get(0).count() == 3 && st.stacks().get(1).count() == 1 && st.stacks().get(2).count() == 1;
        check("boats, pins and markers of one kind close together stack, and different kinds or far apart do not", ok, st.stacks().size() + " stacks");
        MapDraw.View near = new MapDraw.View(0, 0, 2, 0, 0, 100, 100), far = new MapDraw.View(0, 0, 1 / 64.0, 0, 0, 100, 100);
        check("icons are full size zoomed in and smaller zoomed out", MapDraw.iconScale(near) == 1f && MapDraw.iconScale(far) < 0.7f,
            MapDraw.iconScale(near) + " / " + MapDraw.iconScale(far));
    }

    /** The open map's top bar as laid out now: nothing overlaps and everything is on screen; the hint fits its row. */
    private static void bar(Minecraft mc, int gui) {
        if (!(mc.gui.screen() instanceof MapScreen screen)) {
            check("the map is open at GUI scale " + gui, false, "");
            return;
        }
        MapScreen.Bar bar = MapScreen.layout(mc.font, screen.width, MapScreen.layerCount(), true, true, screen.getTitle());
        check("the top bar has nothing overlapping at GUI scale " + gui + " (" + screen.width + "x" + screen.height + ")", fits(mc, bar, screen.width),
            describe(bar));
    }

    /** The same for the widths 1920x1080 gives at GUI scales 2, 3 and 4, with the map's own layers and with four mods' more. */
    private static void layouts(Minecraft mc) {
        StringBuilder bad = new StringBuilder();
        for (int gui = 2; gui <= 4; gui++) {
            for (int layers : new int[]{5, 9}) {
                int w = 1920 / gui;
                MapScreen.Bar bar = MapScreen.layout(mc.font, w, layers, true, true, net.minecraft.network.chat.Component.translatable("mapmod.map.title"));
                if (!fits(mc, bar, w)) bad.append(" gui ").append(gui).append(" with ").append(layers).append(" layers: ").append(describe(bar));
            }
        }
        check("the top bar and the hint fit at GUI scales 2, 3 and 4 on 1920x1080, with 5 or 9 layer buttons", bad.isEmpty(), bad.toString().trim());
    }

    private static boolean fits(Minecraft mc, MapScreen.Bar bar, int width) {
        for (int i = 0; i < bar.boxes().size(); i++) {
            MapScreen.Box a = bar.boxes().get(i);
            if (a.x() < 0 || a.x() + a.w() > width) return false;
            for (int j = i + 1; j < bar.boxes().size(); j++) if (a.overlaps(bar.boxes().get(j))) return false;
        }
        for (var line : bar.hint()) if (mc.font.width(line) > bar.hintWidth()) return false;
        // the whole hint is shown (it wraps, but is never cut)
        int shown = 0;
        for (var line : bar.hint()) shown += mc.font.width(line);
        return shown + 8 * bar.hint().size() >= mc.font.width(net.minecraft.network.chat.Component.translatable("mapmod.map.help"));
    }

    private static String describe(MapScreen.Bar bar) {
        StringBuilder b = new StringBuilder();
        for (MapScreen.Box box : bar.boxes()) b.append(box.what()).append(' ').append(box.x()).append('+').append(box.w()).append(", ");
        return b + (bar.coordsInFoot() ? "position in the bottom row, " : "") + bar.hint().size() + " hint line(s)";
    }

    private static void colours(Probe.Result[] r) {
        for (Probe.Result p : r) for (String line : p.lines()) MapMod.LOGGER.info("SELFTEST colours probe: {}", line);
        Probe.Result m = r[0], g = r[1], s = r[2];
        check("each sample is the top block itself, not the one under it", m.storedHeight() == TOP && g.storedHeight() == TOP && s.storedHeight() == TOP,
            "heights " + m.storedHeight() + ", " + g.storedHeight() + ", " + s.storedHeight() + "; top at " + TOP);
        check("grass blocks are classed as grass", g.storedClass() == SurfaceClass.GRASS, g.block() + " is " + SurfaceClass.name(g.storedClass()));
        check("mycelium is classed as dirt/mud", m.storedClass() == SurfaceClass.DIRT, m.block() + " is " + SurfaceClass.name(m.storedClass()));
        check("stone is classed as rock", s.storedClass() == SurfaceClass.ROCK, s.block() + " is " + SurfaceClass.name(s.storedClass()));
        check("grass paints green", green(g.colour()), hex(g.colour()) + ", tint " + hex(g.storedTint()));
        check("stone paints grey", grey(s.colour()), hex(s.colour()));
        int gm = diff(g.colour(), m.colour()), gs = diff(g.colour(), s.colour()), ms = diff(m.colour(), s.colour());
        check("grass, mycelium and stone are three clearly different colours", gm >= 40 && gs >= 40 && ms >= 40,
            "differences " + gm + ", " + gs + ", " + ms);
        colourMycelium = m.colour();
    }

    static String hex(int c) {
        return String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF);
    }

    private static int r(int c) {
        return c >> 16 & 255;
    }

    private static int g(int c) {
        return c >> 8 & 255;
    }

    private static int b(int c) {
        return c & 255;
    }

    static boolean green(int c) {
        return g(c) >= r(c) + 12 && g(c) >= b(c) + 12;
    }

    static boolean grey(int c) {
        int max = Math.max(r(c), Math.max(g(c), b(c))), min = Math.min(r(c), Math.min(g(c), b(c)));
        return max - min <= 24;
    }

    /** Sum of the channel differences. */
    static int diff(int a, int b) {
        return Math.abs(r(a) - r(b)) + Math.abs(g(a) - g(b)) + Math.abs(b(a) - b(b));
    }
}
