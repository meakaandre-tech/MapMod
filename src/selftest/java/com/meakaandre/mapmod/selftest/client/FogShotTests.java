package com.meakaandre.mapmod.selftest.client;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.Capture;
import com.meakaandre.mapmod.client.map.LocalMap;
import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.map.MapDraw;
import com.meakaandre.mapmod.client.map.MapScreen;
import com.meakaandre.mapmod.client.paint.PaintMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * MM-8c, on a server without MapMod after the colour checks: unexplored land at three zooms, for the smoke run's
 * screenshots. A made-up tile far east of the player holds an 80-block explored patch on its own (a fresh base); the map
 * opens close up on the land round the player (MM-9: unexplored land is the flat background, its edge the hard edge of
 * the chunks captured), then far out over both (8 and 32 blocks a pixel), where both must stay clearly visible, every
 * overlay layer off. Logs "SELFTEST fogshots geometry ..." (where world 0, 0
 * is on screen and the pixels a block) and "SELFTEST fogshots shot ..." where the run takes each screenshot, and
 * "SELFTEST fogshots done" at the end; check-selftests.py reads the shots.
 */
final class FogShotTests {
    /**
     * The patch: 5 chunks square, from block 8208, 208 (tile 16, 0), clear of the made-up land the first client of the
     * run wrote round its own ground (this client keeps its tiles for "localhost" too).
     */
    static final int PATCH_X0 = 8208, PATCH_Z0 = 208, PATCH_SIZE = 80, PATCH_TX = 16, PATCH_TZ = 0;
    /** The far shots are centred half way between the player (about 0, 0) and the patch. */
    private static final int MID_X = 4104, MID_Z = 124;
    private static final String[] SHOTS = {"fog-near", "fog-mid", "fog-far"};
    /** GUI pixels a block of each shot (the run is at GUI scale 2: 2, 1/8 and 1/32 real pixels a block). */
    private static final float[] SCALES = {1f, 1f / 16, 1f / 64};
    private static final int HOLD = 120, SETTLE_PATIENCE = 1200;

    private static int state = -1, waited, idle, shotAt, pass, fail;
    private static Path written;

    private FogShotTests() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FogShotTests::tick);
    }

    static void start() {
        state = 0;
        waited = 0;
    }

    private static void check(String what, boolean ok, String detail) {
        MapMod.LOGGER.info("SELFTEST fogshots {} {}{}", ok ? "PASS" : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
        if (ok) pass++;
        else fail++;
    }

    private static void tick(Minecraft mc) {
        if (state < 0 || !LocalMap.active() || mc.player == null) return;
        waited++;
        if (state == 0) {
            if (mc.gui.screen() != null) mc.gui.setScreen(null);
            Path dir = Capture.dir();
            try {
                written = dir == null ? null : SyntheticTiles.writePatch(dir, PATCH_TX, PATCH_TZ, PATCH_X0, PATCH_Z0, PATCH_SIZE);
            } catch (Exception e) {
                written = null;
                MapMod.LOGGER.warn("fog shots: could not write the patch", e);
            }
            check("a made-up tile with an 80-block explored patch on its own is written", written != null && Files.exists(written), String.valueOf(written));
            ColourClientTests.layers(false);
            state = 1;
            waited = 0;
            return;
        }
        if (state > SHOTS.length) {
            finish(mc);
            return;
        }
        int k = state - 1;
        if (waited == 1) {
            MapScreen screen = new MapScreen();
            int x = k == 0 ? mc.player.getBlockX() : MID_X, z = k == 0 ? mc.player.getBlockZ() : MID_Z;
            screen.centre(x, z, SCALES[k]);
            MapClient.show(screen);
            idle = 0;
            return;
        }
        if (waited < 40) return;
        if (shotAt == 0) {
            idle = PaintMap.idle() ? idle + 1 : 0;
            if (idle < 20 && waited < SETTLE_PATIENCE) return;
            check("the map settles for the " + SHOTS[k] + " shot", idle >= 20, idle >= 20 ? "" : "not idle after " + waited + " ticks: " + PaintMap.lastFrame());
            geometry(mc, SHOTS[k]);
            MapMod.LOGGER.info("SELFTEST fogshots shot {}", SHOTS[k]);
            shotAt = waited;
            return;
        }
        // each view is held a while after its shot is logged, as the run's screenshot can lag
        if (waited < shotAt + HOLD) return;
        shotAt = 0;
        state++;
        waited = 0;
    }

    /** Where world 0, 0 is on screen in real pixels, and real pixels a block. */
    private static void geometry(Minecraft mc, String shot) {
        if (!(mc.gui.screen() instanceof MapScreen screen)) {
            check("the map is open for the " + shot + " shot", false, "");
            return;
        }
        MapDraw.View v = screen.view();
        int gs = Math.max(1, mc.getWindow().getGuiScale());
        MapMod.LOGGER.info(String.format(Locale.ROOT, "SELFTEST fogshots geometry %s x0 %.2f z0 %.2f ppb %.6f px %d pz %d", shot, v.sx(0) * gs, v.sy(0) * gs,
            v.scale() * gs, mc.player.getBlockX(), mc.player.getBlockZ()));
    }

    private static void finish(Minecraft mc) {
        if (mc.gui.screen() instanceof MapScreen) mc.gui.setScreen(null);
        ColourClientTests.layers(true);
        try {
            if (written != null) Files.deleteIfExists(written);
        } catch (Exception e) {
            // left for the next run to write over
        }
        MapMod.LOGGER.info("SELFTEST fogshots done: {} passed, {} failed", pass, fail);
        state = -1;
    }
}
