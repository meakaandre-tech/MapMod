package com.meakaandre.mapmod.selftest.client;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.Probe;
import com.meakaandre.mapmod.client.capture.SurfaceClass;
import com.meakaandre.mapmod.client.map.LocalMap;
import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.map.MapScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * MM-7, on the server without MapMod: the smoke run builds a flat patch at y 150 round 0,0 in a mushroom fields biome
 * (mycelium west, grass blocks in the middle, stone east) and puts the player on it. Once the client has captured it,
 * the three paint as three clearly different colours (grass green, mycelium purple-grey, stone grey); the run then
 * replaces the mycelium with grass blocks on the server, and the client's map must follow through its own block-change
 * hook alone. Logs "SELFTEST colours shot ..." where the run takes a screenshot, and "SELFTEST colours done" at the end.
 */
final class ColourClientTests {
    /** The patch's three spots (z 0): a cell wholly inside each strip. */
    static final int MYCELIUM_X = -16, GRASS_X = 0, STONE_X = 16, Z = 0, TOP = 150;
    /** Ticks of the client's own map before giving up on the patch being captured, and on the replacement reaching it. */
    private static final int CAPTURE_PATIENCE = 2400, REPLACE_PATIENCE = 1200;

    private static int state, waited, pass, fail;
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
        return r != null && r.captured() && r.storedHeight() >= TOP - 1;
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
                    MapScreen screen = new MapScreen();
                    screen.centre(0, 0, 3f);
                    MapClient.show(screen);
                }
                if (waited == 220) {
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
            default -> finish();
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

    private static void colours(Probe.Result[] r) {
        for (Probe.Result p : r) for (String line : p.lines()) MapMod.LOGGER.info("SELFTEST colours probe: {}", line);
        Probe.Result m = r[0], g = r[1], s = r[2];
        check("grass blocks are classed as grass", g.storedClass() == SurfaceClass.GRASS, g.block() + " is " + SurfaceClass.name(g.storedClass()));
        check("mycelium is classed as dirt/mud", m.storedClass() == SurfaceClass.DIRT, m.block() + " is " + SurfaceClass.name(m.storedClass()));
        check("stone is classed as rock", s.storedClass() == SurfaceClass.ROCK, s.block() + " is " + SurfaceClass.name(s.storedClass()));
        check("grass paints green", green(g.colour()), hex(g.colour()) + ", tint " + hex(g.storedTint()));
        check("mycelium paints purple-grey", purpleGrey(m.colour()), hex(m.colour()) + ", tint " + hex(m.storedTint()));
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

    static boolean purpleGrey(int c) {
        int max = Math.max(r(c), Math.max(g(c), b(c))), min = Math.min(r(c), Math.min(g(c), b(c)));
        return b(c) >= g(c) + 8 && r(c) >= g(c) + 4 && max - min <= 80;
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
