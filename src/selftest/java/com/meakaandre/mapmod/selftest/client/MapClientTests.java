package com.meakaandre.mapmod.selftest.client;

import com.google.gson.JsonParser;
import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.api.MapGlyphs;
import com.meakaandre.mapmod.api.MapLock;
import com.meakaandre.mapmod.api.MapPalette;
import com.meakaandre.mapmod.api.MapSurface;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
import com.meakaandre.mapmod.client.capture.Capture;
import com.meakaandre.mapmod.client.map.LocalMap;
import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.map.MapDraw;
import com.meakaandre.mapmod.client.map.MapFilters;
import com.meakaandre.mapmod.client.map.MapHud;
import com.meakaandre.mapmod.client.map.MapScreen;
import com.meakaandre.mapmod.client.map.MobRadar;
import com.meakaandre.mapmod.client.capture.SurfaceClass;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import com.meakaandre.mapmod.client.paint.BakeCheck;
import com.meakaandre.mapmod.client.paint.FogCheck;
import com.meakaandre.mapmod.client.map.MapLayer;
import com.meakaandre.mapmod.client.paint.PaintMap;
import com.meakaandre.mapmod.client.paint.PaintPalette;
import com.meakaandre.mapmod.client.paint.TilePrep;
import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapMarker;
import com.meakaandre.mapmod.map.MapPayloads;
import com.meakaandre.mapmod.map.MapServer;
import com.meakaandre.mapmod.selftest.MapTests;
import com.meakaandre.mapmod.selftest.TestPois;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The smoke run's remote control of the map ({@link MapTests.Control}, from {@code /mapmod worldtest}): opens or closes
 * the map, centred and zoomed, switches or folds out a filter, pins a marker, and runs the checks only the client can
 * make (what arrived from the server, the filter panel, the unlocks). Every check prints "SELFTEST client"; failures say FAIL.
 */
public final class MapClientTests {
    private static int passed, failed;
    private static int palettesBeforeReload = Integer.MAX_VALUE, paletteVersionBeforeReload;

    private MapClientTests() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MapTests.Control.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> control(p)));
        ClientTickEvents.END_CLIENT_TICK.register(MapClientTests::local);
        ClientTickEvents.END_CLIENT_TICK.register(MapClientTests::watch);
        ApiClientTests.effects = MapClientTests::apiEffect;
        Capture.onTileChanged((tx, tz, revision) -> {
            events++;
            eventX = tx;
            eventZ = tz;
            eventRevision = revision;
        });
    }

    // ------------------------------------------------------------------------------------------------ MM-4

    /** Tile-changed events this client heard, and the last one. */
    private static int events, eventX, eventZ;
    private static long eventRevision;
    /** The bake cache check, stepped from the tick while it runs. */
    private static BakeCheck bakeCheck;
    private static int bakePass, bakeFail;
    /** "settle <tag>": the tag to log once the open map has had nothing to do for a while, and for how many ticks. */
    private static String settling;
    private static int idleTicks, settleTicks;
    /** "far": checks to make once the map has settled at far zoom and painted a while longer. */
    private static boolean farPending;
    private static int farTicks;
    private static String farGpu = "";
    private static double farRedraw = -1;
    /** Ticks the open map must have had nothing to do before it counts as settled, and ticks to give up after. */
    private static final int SETTLE = 20, SETTLE_GIVE_UP = 3600;
    /** MM-2's render time at far zoom (us/frame, CPU, averaged): MM-4 must not be slower once settled. */
    private static final double MM2_MICROS = 134;

    // ------------------------------------------------------------------------------------------------ MM-5

    /** The fog check, stepped from the tick while it runs. */
    private static FogCheck fogCheck;
    /** The glide check on the open map: ticks since it started (0: not running), where it started, and its results. */
    private static int glideTicks;
    private static double glideX, glideZ;
    private static int overlayPass, overlayFail;
    /** The dimension check: armed, the frees counted and the cache held when armed, and ticks waited. */
    private static boolean dimensionArmed;
    private static int dimensionFreesAt, dimensionTicks;
    private static long dimensionBytesAt;

    private static void report(String suite, String what, boolean ok, String detail) {
        MapMod.LOGGER.info("SELFTEST client {} {}{}", ok ? "PASS" : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
        if (suite.equals("overlays")) {
            if (ok) overlayPass++;
            else overlayFail++;
        }
    }

    /** Overlay layer switches are saved, and the glide's arithmetic is the same at any frame rate; the glide itself follows. */
    private static void overlays() {
        overlayPass = overlayFail = 0;
        Minecraft mc = Minecraft.getInstance();
        boolean all = true;
        StringBuilder seen = new StringBuilder();
        for (MapLayer l : MapLayer.values()) {
            MapScreen.toggleLayer(l);
            String off = settings();
            boolean savedOff = !l.on() && off.contains("\"" + l.id + "\":false");
            MapScreen.toggleLayer(l);
            String on = settings();
            boolean savedOn = l.on() && on.contains("\"" + l.id + "\":true");
            all &= savedOff && savedOn;
            seen.append(l.id).append(savedOff && savedOn ? " ok " : " NOT SAVED ");
        }
        report("overlays", "each overlay layer's switch in the top bar turns it off and on and is saved in settings.json", all, seen.toString().trim());
        // half a second at 60 frames a second, and at 20
        double far60 = 0, far20 = 0, v60 = 500, v20 = 500;
        for (int i = 0; i < 30; i++) {
            double[] g = MapScreen.glide(v60, 1 / 60.0);
            far60 += g[0];
            v60 = g[1];
        }
        for (int i = 0; i < 10; i++) {
            double[] g = MapScreen.glide(v20, 1 / 20.0);
            far20 += g[0];
            v20 = g[1];
        }
        report("overlays", "the glide after a drag goes the same way at any frame rate", Math.abs(far60 - far20) < 1e-6 * far60 && Math.abs(v60 - v20) < 1e-6 * v60,
            String.format(Locale.ROOT, "half a second: %.4f blocks at 60 fps, %.4f at 20", far60, far20));
        if (mc.gui.screen() instanceof MapScreen screen) {
            glideX = screen.centreX();
            glideZ = screen.centreZ();
            screen.fling(900, 300);
            glideTicks = 1;
        } else {
            report("overlays", "the map is open for the glide check", false, "");
            MapMod.LOGGER.info("SELFTEST client done overlays: {} passed, {} failed", overlayPass, overlayFail);
        }
    }

    private static String settings() {
        try {
            return Files.readString(MapClient.settingsPath()).replace(" ", "");
        } catch (Exception e) {
            return e.toString();
        }
    }

    /** Steps the glide check: it moves, slows to a stop by itself, and a click stops another at once. */
    private static void glideWatch(Minecraft mc) {
        if (glideTicks == 0) return;
        glideTicks++;
        if (!(mc.gui.screen() instanceof MapScreen screen)) {
            report("overlays", "the map stays open for the glide check", false, "");
            glideTicks = 0;
            MapMod.LOGGER.info("SELFTEST client done overlays: {} passed, {} failed", overlayPass, overlayFail);
            return;
        }
        if (screen.gliding() && glideTicks < 100) return;
        double moved = Math.hypot(screen.centreX() - glideX, screen.centreZ() - glideZ);
        report("overlays", "letting go of a drag glides the map on a moment, then it stops by itself", !screen.gliding() && moved > 1 && glideTicks < 100,
            String.format(Locale.ROOT, "moved %.1f blocks, stopped after %d ticks", moved, glideTicks));
        screen.fling(900, 0);
        boolean started = screen.gliding();
        screen.pressed();
        report("overlays", "a click stops a glide", started && !screen.gliding(), "");
        glideTicks = 0;
        MapMod.LOGGER.info("SELFTEST client done overlays: {} passed, {} failed", overlayPass, overlayFail);
    }

    /** Steps the dimension check: once the player is in another dimension, the cache must be gone. */
    private static void dimensionWatch(Minecraft mc) {
        if (!dimensionArmed) return;
        dimensionTicks++;
        boolean away = mc.level != null && !MapClient.mappedHere();
        if (!(away && PaintMap.dimensionFrees() > dimensionFreesAt) && dimensionTicks < 1200) return;
        dimensionArmed = false;
        report("dimension", "going to another dimension lets the baked tiles, mips and fog masks go at once",
            away && PaintMap.dimensionFrees() > dimensionFreesAt && PaintMap.cacheBytes() == 0 && PaintMap.bakedTiles() == 0 && PaintMap.mips() == 0
                && dimensionBytesAt > 0, dimensionBytesAt / 1048576 + " MB held before, " + PaintMap.cacheBytes() + " bytes after, " + dimensionTicks + " ticks");
        MapMod.LOGGER.info("SELFTEST client done dimension");
    }

    /** About where the captured land ends east of the player, in blocks: the chunks loaded round them (the close screenshot is centred on it). */
    private static int capturedEdge(Minecraft mc) {
        int pcx = mc.player.getBlockX() >> 4;
        // a little inside, so the close-up shows land, the fade and the fog
        return (pcx + mc.options.getEffectiveRenderDistance() + 1) * 16 - 24;
    }

    private static void watch(Minecraft mc) {
        if (fogCheck != null) {
            List<BakeCheck.Result> results = fogCheck.step();
            if (results != null) {
                fogCheck = null;
                int pass = 0, fail = 0;
                for (BakeCheck.Result r : results) {
                    MapMod.LOGGER.info("SELFTEST client {} {}{}", r.ok() ? "PASS" : "FAIL", r.what(), r.detail().isEmpty() ? "" : " (" + r.detail() + ")");
                    if (r.ok()) pass++;
                    else fail++;
                }
                MapMod.LOGGER.info("SELFTEST client done fog: {} passed, {} failed", pass, fail);
            }
        }
        glideWatch(mc);
        dimensionWatch(mc);
        ownFogWatch(mc);
        if (bakeCheck != null) {
            List<BakeCheck.Result> results = bakeCheck.step();
            if (results != null) {
                bakeCheck = null;
                for (BakeCheck.Result r : results) {
                    MapMod.LOGGER.info("SELFTEST client {} {}{}", r.ok() ? "PASS" : "FAIL", r.what(), r.detail().isEmpty() ? "" : " (" + r.detail() + ")");
                    if (r.ok()) bakePass++;
                    else bakeFail++;
                }
                MapMod.LOGGER.info("SELFTEST client done bake: {} passed, {} failed", bakePass, bakeFail);
            }
        }
        if (settling != null) {
            settleTicks++;
            idleTicks = mc.gui.screen() instanceof MapScreen && PaintMap.idle() ? idleTicks + 1 : 0;
            if (idleTicks >= SETTLE || settleTicks >= SETTLE_GIVE_UP) {
                MapMod.LOGGER.info("SELFTEST client settled {} after {} ticks{}: {}", settling, settleTicks,
                    idleTicks >= SETTLE ? "" : " (gave up)", PaintMap.lastFrame());
                for (String line : Capture.statsLines()) if (line.startsWith("  ") || line.contains("painted")) MapMod.LOGGER.info("map stats {}: {}", settling, line);
                settling = null;
                if (farPending) farTicks = 1;
            }
        }
        // after settling at far zoom, two seconds each: redrawing every frame with the GPU timed, redrawing with the
        // timer asleep (a redraw's CPU time), then as in play, the picture shown again while nothing changes
        if (farTicks > 0) {
            farTicks++;
            if (farTicks == 2) {
                PaintMap.reusePictures(false);
                PaintMap.timer().wake();
            }
            if (farTicks == 42) {
                farGpu = Capture.statsLines().stream().filter(l -> l.contains("GPU ")).findFirst().orElse("no GPU line");
                MapMod.LOGGER.info("map stats far, redrawn, GPU timed: {}", farGpu);
                PaintMap.timer().sleep();
            }
            if (farTicks == 82) {
                farRedraw = renderMicros(Capture.statsLines());
                MapMod.LOGGER.info("map stats far, redrawn, timer asleep: render {} us/frame avg", farRedraw);
                PaintMap.timer().sleep();
                PaintMap.reusePictures(true);
            }
            if (farTicks == 122) {
                // the world goes on while it is measured (the player's own capture may change a tile): a second that
                // held work is not a settled one, so it is measured again, a few times at most
                double steps = steps(Capture.statsLines());
                if (steps > 0 && farRetries < 5) {
                    farRetries++;
                    MapMod.LOGGER.info("map stats far: the last second held work ({} steps/frame), measured again ({} of 5)", steps, farRetries);
                    farTicks = 82;
                    return;
                }
                farTicks = 0;
                farPending = false;
                far();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ MM-6

    /** The cleanup, as the client sees it on joining a server with MapMod. */
    private static void cleanupJoin() {
        expect("the client tells a server with MapMod by its Hello first", LocalMap.serverHasMapBy().equals("hello") && !LocalMap.active(),
            "told by " + LocalMap.serverHasMapBy());
        expect("the terrain tile payloads are gone: the client cannot ask the server for tiles",
            !ClientPlayNetworking.canSend(new CustomPacketPayload.Type<>(MapMod.id("tile_request"))), "");
        // a tile MM-1 wrote (its own SurfaceTile code and aircompressor 0.27), read with this release's
        String fixture = "";
        boolean old = false, again = false, header = false;
        try (var in = MapClientTests.class.getResourceAsStream("/mapmod_selftest/mm1-tile.mmt")) {
            byte[] bytes = in == null ? new byte[0] : in.readAllBytes();
            SurfaceTile t = SurfaceTile.unpack(3, -2, bytes);
            SurfaceTile want = mm1Fixture();
            old = t.sameData(want) && t.exploredCount() == want.exploredCount();
            byte[] packed = want.pack();
            again = SurfaceTile.unpack(3, -2, packed).sameData(want);
            // the header (magic, format, spacing, side, body length) is the same bytes as MM-1 wrote
            header = java.util.Arrays.equals(java.util.Arrays.copyOf(bytes, 13), java.util.Arrays.copyOf(packed, 13));
            fixture = bytes.length + " bytes from MM-1, " + packed.length + " now, " + t.exploredCount() + " explored";
        } catch (Exception e) {
            fixture = e.toString();
        }
        expect("a tile MM-1 wrote reads back the same with this release's zstd", old, fixture);
        expect("a tile written now reads back the same, with MM-1's header (the tile format did not change)", again && header, fixture);
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT), arch = System.getProperty("os.arch", "");
        boolean nativeHere = (os.contains("linux") && (arch.equals("amd64") || arch.equals("aarch64") || arch.equals("ppc64le")))
            || (os.contains("mac") && arch.equals("aarch64"));
        expect("tiles use the native zstd codec where aircompressor carries one (no sun.misc.Unsafe), the pure-Java one elsewhere",
            SurfaceTile.codec().equals(nativeHere ? "native" : "java"), SurfaceTile.codec() + " on " + os + " " + arch);
    }

    /** The tile MM-1 wrote for the fixture: the same formula as the generator that wrote it. */
    private static SurfaceTile mm1Fixture() {
        SurfaceTile t = new SurfaceTile(3, -2);
        for (int i = 0; i < SurfaceTile.AREA; i++) {
            int sx = i % SurfaceTile.SIDE, sz = i / SurfaceTile.SIDE;
            int h = 40 + (sx * 7 + sz * 13) % 97 + (sx * sz) % 11;
            t.height[i] = (short) h;
            t.water[i] = (byte) (h < 62 ? 62 - h : 0);
            t.surface[i] = (byte) ((sx / 16 + sz / 16 + (sx * sz % 3 == 0 ? 1 : 0)) % 8);
            t.tint[i] = (short) (0x5A60 + sx * 3 + sz * 5);
            t.canopy[i] = (byte) ((sx * 31 + sz * 17) % 256);
            if ((sx + sz) % 9 != 0) t.explored[i >>> 6] |= 1L << i;
        }
        return t;
    }

    /** What the API test did, as MapMod's insides show it: one check per point, done when the supplier says so. */
    private static BooleanSupplier apiEffect(String point) {
        Minecraft mc = Minecraft.getInstance();
        switch (point) {
            case "markers" -> {
                long before = MapHud.apiMarkersDrawn;
                int[] t = {0};
                return () -> {
                    if (MapHud.apiMarkersDrawn == before && ++t[0] < 100) return false;
                    ApiClientTests.report("a mod's marker is drawn on the open map, in the markers layer", MapHud.apiMarkersDrawn > before,
                        (MapHud.apiMarkersDrawn - before) + " drawn");
                    return true;
                };
            }
            case "palette" -> {
                long before = PaintMap.bakes();
                int[] t = {0};
                return () -> {
                    if (PaintMap.bakes() == before && ++t[0] < 600) return false;
                    ApiClientTests.report("the override paints the map again: the baked tiles bake anew with the class colour",
                        PaintMap.bakes() > before && PaintPalette.current().classColour()[0] == ApiClientTests.OVERRIDE_RGB,
                        (PaintMap.bakes() - before) + " bakes, class 0 #" + Integer.toHexString(PaintPalette.current().classColour()[0]));
                    return true;
                };
            }
            case "surface" -> {
                return new SurfaceCheck(mc);
            }
            case "layers" -> {
                ApiClientTests.report("a locked layer is hidden whatever its switch says, and the switch is kept",
                    MapLayer.POIS.locked() && !MapLayer.POIS.shown() && MapLayer.POIS.on() && !MapLayer.PINS.locked(), "");
                return () -> true;
            }
            case "locked" -> {
                long frames = PaintMap.frames(), panels = MapScreen.lockedFrames;
                int[] t = {0};
                return () -> {
                    if (++t[0] < 10) return false;
                    boolean open = mc.gui.screen() instanceof MapScreen screen && screen.locked();
                    ApiClientTests.report("a locked map shows the locked panel, not an empty map, and paints no terrain",
                        open && MapScreen.lockedFrames > panels && PaintMap.frames() == frames,
                        (MapScreen.lockedFrames - panels) + " panel frames, " + (PaintMap.frames() - frames) + " painted frames");
                    MapClient.open();
                    ApiClientTests.report("M still opens a locked map (to its locked panel)", mc.gui.screen() instanceof MapScreen, "");
                    return true;
                };
            }
            case "unlocked" -> {
                long frames = PaintMap.frames();
                int[] t = {0};
                return () -> {
                    if (PaintMap.frames() == frames && ++t[0] < 100) return false;
                    ApiClientTests.report("unlocked, the map paints again", PaintMap.frames() > frames && !MapLock.mapLocked(), "");
                    return true;
                };
            }
            default -> {
                return () -> true;
            }
        }
    }

    /**
     * A block override takes effect on new samples only: a captured sample keeps its class until its chunk is captured
     * again, then takes the override; cleared, and captured again, it is back.
     */
    private static final class SurfaceCheck implements BooleanSupplier {
        private final Minecraft mc;
        private int stage, ticks, index, cx, cz, tx, tz, before;
        private long revision;
        private BlockState ground;

        SurfaceCheck(Minecraft mc) {
            this.mc = mc;
        }

        private SurfaceTile tile() {
            try {
                return Capture.tile(tx, tz).get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public boolean getAsBoolean() {
            ticks++;
            if (stage == 0) return start();
            if (ticks % 5 != 0 && ticks < 400) return false;
            SurfaceTile t = tile();
            boolean newer = t != null && t.revision > revision;
            if (stage == 1) {
                if ((!newer || t.surface[index] != MapSurface.LAVA) && ticks < 400) return false;
                ApiClientTests.report("captured again, the sample takes the block's override", t != null && t.surface[index] == MapSurface.LAVA,
                    t == null ? "no tile" : "class " + SurfaceClass.name(t.surface[index]) + " for " + ground);
                MapSurface.clear(ground.getBlock());
                revision = t == null ? 0 : t.revision;
                Capture.recapture(cx, cz);
                stage = 2;
                ticks = 0;
                return false;
            }
            if ((!newer || t.surface[index] != before) && ticks < 400) return false;
            ApiClientTests.report("the override cleared, captured again, the sample is back to its own class", t != null && t.surface[index] == before,
                t == null ? "no tile" : SurfaceClass.name(t.surface[index]) + ", was " + SurfaceClass.name(before));
            return true;
        }

        private boolean start() {
            stage = 1;
            ticks = 0;
            if (mc.player == null || mc.level == null) {
                ApiClientTests.report("a sample to put in another class", false, "no player");
                return true;
            }
            tx = SurfaceTile.tileOfBlock(mc.player.getBlockX());
            tz = SurfaceTile.tileOfBlock(mc.player.getBlockZ());
            SurfaceTile t = tile();
            int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
            for (int dz = -2; dz <= 2 && ground == null; dz++) {
                for (int dx = -2; dx <= 2 && ground == null; dx++) {
                    int ccx = pcx + dx, ccz = pcz + dz;
                    if (t == null || SurfaceTile.tileOfChunk(ccx) != tx || SurfaceTile.tileOfChunk(ccz) != tz || !mc.level.getChunkSource().hasChunk(ccx, ccz)) continue;
                    int x = ccx * 16 + 2, z = ccz * 16 + 2, i = SurfaceTile.indexOfBlock(x, z);
                    int c = t.surface[i];
                    if (!t.explored(i) || c == MapSurface.LAVA || c == MapSurface.SNOW || c == MapSurface.FOREST) continue;
                    BlockState s = mc.level.getBlockState(new BlockPos(x, t.height[i], z));
                    if (s.isAir() || MapSurface.classOf(s) != c) continue;
                    ground = s;
                    index = i;
                    before = c;
                    cx = ccx;
                    cz = ccz;
                    revision = t.revision;
                }
            }
            if (ground == null) {
                ApiClientTests.report("a captured sample to put in another class", false, t == null ? "no tile" : "none found round the player");
                return true;
            }
            MapSurface.override(ground.getBlock(), MapSurface.LAVA);
            SurfaceTile now = tile();
            ApiClientTests.report("an override does not touch what was captured before it", now != null && now.surface[index] == before,
                ground + " was " + SurfaceClass.name(before));
            Capture.recapture(cx, cz);
            return false;
        }
    }

    private static void far() {
        passed = failed = 0;
        String frame = PaintMap.lastFrame();
        List<String> stats = Capture.statsLines();
        for (String line : stats) MapMod.LOGGER.info("map stats far, timer asleep: {}", line);
        double micros = renderMicros(stats);
        expect("far zoom draws a large explored area from the mip pyramid as a few dozen quads", frame.startsWith("level ") && !frame.startsWith("level 0")
            && quads(frame) > 0 && quads(frame) <= 64, frame);
        expect(String.format(Locale.ROOT, "far zoom, settled, renders in no more CPU time than MM-2 (%.0f us/frame)", MM2_MICROS),
            micros >= 0 && micros <= MM2_MICROS, micros + " us/frame avg; redrawing every frame " + farRedraw + " us; redrawing with the GPU timed:"
                + farGpu.substring(Math.max(0, farGpu.indexOf(" render"))));
        expect("tiles read from disk fire the map's disk-load event", PaintMap.diskEvents() > 0, PaintMap.diskEvents() + " events");
        expect("the cache keeps within its budget, evicting as it goes", PaintMap.cacheBytes() <= (long) MapClient.cacheMB * 1048576,
            PaintMap.cacheBytes() / 1048576 + " MB of " + MapClient.cacheMB + "; " + PaintMap.bakedTiles() + " tiles, " + PaintMap.mips() + " mips");
        expect("at most " + PaintMap.UPLOADS_PER_FRAME + " uploads, bakes and mip copies a frame", PaintMap.maxUploadsPerFrame() <= PaintMap.UPLOADS_PER_FRAME,
            PaintMap.maxUploadsPerFrame() + " at most");
        expect("/mapmod stats shows GPU time (or n/a), baked tiles, mips, cache MB and bakes per frame",
            stats.stream().anyMatch(l -> l.contains("GPU ")) && stats.stream().anyMatch(l -> l.contains("baked tiles") && l.contains("mips") && l.contains(" MB"))
                && stats.stream().anyMatch(l -> l.contains("bakes") && l.contains("/frame")), String.join(" | ", stats));
        MapMod.LOGGER.info("SELFTEST client done far: {} passed, {} failed", passed, failed);
    }

    private static int quads(String frame) {
        try {
            String[] parts = frame.split(", ");
            return Integer.parseInt(parts[1].substring(0, parts[1].indexOf(' ')));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static int farRetries;

    /** Uploads, bakes and mip copies a frame over the last second, from the stats (-1 if not there). */
    private static double steps(List<String> stats) {
        for (String l : stats) {
            int i = l.indexOf("steps ");
            if (i < 0 || !l.contains("/frame avg")) continue;
            try {
                return Double.parseDouble(l.substring(i + 6, l.indexOf('/', i)));
            } catch (RuntimeException e) {
                return -1;
            }
        }
        return -1;
    }

    private static double renderMicros(List<String> stats) {
        for (String l : stats) {
            int i = l.indexOf("render ");
            if (i < 0 || !l.contains("us/frame")) continue;
            try {
                return Double.parseDouble(l.substring(i + 7, l.indexOf(' ', i + 7)));
            } catch (RuntimeException e) {
                return -1;
            }
        }
        return -1;
    }

    /** Writes made-up tiles round the player's ground, off the render thread, and logs when they are in. */
    private static void bigWorld() {
        Minecraft mc = Minecraft.getInstance();
        Path dir = Capture.dir();
        if (mc.player == null || dir == null) {
            expect("a large made-up explored area is written for the far zoom", false, "no player or no tile folder");
            return;
        }
        int ptx = SurfaceTile.tileOfBlock(mc.player.getBlockX()), ptz = SurfaceTile.tileOfBlock(mc.player.getBlockZ());
        Thread t = new Thread(() -> {
            try {
                int n = SyntheticTiles.writeRing(dir, ptx, ptz, 2, 6);
                MapMod.LOGGER.info("SELFTEST client bigworld written: {} tiles round tile {},{}", n, ptx, ptz);
            } catch (Exception e) {
                MapMod.LOGGER.warn("SELFTEST client FAIL writing the made-up explored area", e);
                MapMod.LOGGER.info("SELFTEST client bigworld written: none");
            }
        }, "selftest big world");
        t.setDaemon(true);
        t.start();
    }

    /** The change event: heard, and carrying the position and revision of what the capture holds there now (or older). */
    private static void changeEvents() {
        SurfaceTile held = null;
        try {
            held = Capture.tile(eventX, eventZ).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            MapMod.LOGGER.warn("change event test: no tile", e);
        }
        expect("the capture fires a tile-changed event with the tile's position and revision", events > 0 && Capture.tileEvents() >= events
            && held != null && held.tileX == eventX && held.tileZ == eventZ && held.revision >= eventRevision && eventRevision > 0,
            events + " events, last " + eventX + "," + eventZ + " at " + eventRevision + ", held " + (held == null ? "none" : held.revision));
    }

    /** GPU time in the stats, and "n/a" when timer queries are unsupported. */
    private static void timerFallback() {
        boolean real = PaintMap.timer().supported();
        PaintMap.timer().forceUnsupported(true);
        List<String> off = Capture.statsLines();
        PaintMap.timer().forceUnsupported(false);
        List<String> on = Capture.statsLines();
        expect("/mapmod stats shows GPU time, and n/a where timer queries are unsupported",
            off.stream().anyMatch(l -> l.contains("GPU n/a")) && on.stream().anyMatch(l -> l.contains("GPU ") && (real != l.contains("GPU n/a"))),
            "timer queries " + (real ? "supported" : "unsupported") + "; " + on.stream().filter(l -> l.contains("GPU ")).findFirst().orElse("no GPU line"));
    }

    /** Ticks of the client's own map before the screenshot of its HUD, and before the checks. */
    private static final int LOCAL_READY = 200, LOCAL_CHECK = 400;

    /**
     * On a server with no MapMod (and so no selftest mod either) the client checks its own map by itself: it logs
     * "SELFTEST local ready." once it has kept it for a while, then checks it and opens the fullscreen map.
     */
    private static void local(Minecraft mc) {
        if (!LocalMap.active() || mc.player == null) return;
        int t = LocalMap.ticks();
        if (t == LOCAL_READY) MapMod.LOGGER.info("SELFTEST local ready.");
        if (t == LOCAL_CHECK + 60) localLater();
        if (localFogTicks > 0) localFog(mc);
        if (t != LOCAL_CHECK) return;
        int pass = 0, fail = 0;
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        boolean[] checks = {
            MapClient.unlocked(MapFeature.TERRAIN) && MapClient.unlocked(MapFeature.FULLSCREEN),
            MapClient.mapId != null && MapClient.mapId.startsWith("server-"),
            MapClient.explored(px, pz) && MapClient.explored(px + 80, pz) && !MapClient.explored(px + 200, pz),
            LocalMap.active() && LocalMap.serverHasMapBy().isEmpty(),
        };
        String[] what = {
            "with no MapMod on the server the whole map is open",
            "the client keeps its own map for this server (" + MapClient.mapId + ")",
            "the client clears its own fog round the player",
            "a server that sends no Hello and takes none of MapMod's payloads is one without MapMod (the Hello first, MM-6)",
        };
        for (int i = 0; i < checks.length; i++) {
            MapMod.LOGGER.info("SELFTEST local {} {}", checks[i] ? "PASS" : "FAIL", what[i]);
            if (checks[i]) pass++;
            else fail++;
        }
        MapMarker m = new MapMarker();
        m.name = "Local marker";
        m.x = px + 30;
        m.y = 70;
        m.z = pz + 20;
        m.icon = 1;
        m.colour = 3;
        LocalMap.apply(MapPayloads.ADD, m);
        MapMarker placed = named("Local marker");
        boolean kept = placed != null;
        if (placed != null) {
            MapMarker rename = new MapMarker();
            rename.id = placed.id;
            rename.name = "Local, renamed";
            rename.icon = 2;
            LocalMap.apply(MapPayloads.UPDATE, rename);
            kept = named("Local, renamed") != null && named("Local marker") == null;
        }
        MapMod.LOGGER.info("SELFTEST local {} markers are placed and changed on the client's own map", kept ? "PASS" : "FAIL");
        if (kept) pass++;
        else fail++;
        LocalMap.save();
        Path dir = mc.gameDirectory.toPath().resolve(MapMod.ID).resolve(MapClient.mapId == null ? "none" : MapClient.mapId);
        boolean saved = Files.exists(dir.resolve("fog.bin")) && Files.exists(dir.resolve("markers.json"));
        MapMod.LOGGER.info("SELFTEST local {} its fog and markers are saved in the game folder ({})", saved ? "PASS" : "FAIL", dir);
        if (saved) pass++;
        else fail++;
        for (CaptureClientTests.Check c : CaptureClientTests.run(true)) {
            MapMod.LOGGER.info("SELFTEST local {} {}{}", c.ok() ? "PASS" : "FAIL", c.what(), c.detail().isEmpty() ? "" : " (" + c.detail() + ")");
            if (c.ok()) pass++;
            else fail++;
        }
        // the local screenshot shows contours: switched on here, checked once the map has painted for a while
        MapClient.setContours(true);
        MapScreen screen = new MapScreen();
        screen.centre(px, pz, 0.5f);
        MapClient.show(screen);
        boolean open = mc.gui.screen() instanceof MapScreen;
        MapMod.LOGGER.info("SELFTEST local {} M opens the fullscreen map", open ? "PASS" : "FAIL");
        if (open) pass++;
        else fail++;
        localPass = pass;
        localFail = fail;
        framesAtOpen = PaintMap.frames();
    }

    private static int localPass, localFail;
    /** The client's own checks on a server without MapMod have finished (MM-7's colour checks go on after them). */
    static boolean localDone;
    private static long framesAtOpen;

    /** A few seconds after the local map opened with contours on: they were painted. */
    private static void localLater() {
        boolean painted = PaintMap.frames() > framesAtOpen && PaintMap.contoursPainted();
        MapMod.LOGGER.info("SELFTEST local {} the map paints contour lines once they are switched on ({} frames)", painted ? "PASS" : "FAIL",
            PaintMap.frames() - framesAtOpen);
        if (painted) localPass++;
        else localFail++;
        // the fog checks follow once the open map holds the captured land round the player
        localFogTicks = 1;
    }

    private static int localFogTicks;

    private static void localCheck(String what, boolean ok, String detail) {
        MapMod.LOGGER.info("SELFTEST local {} {}{}", ok ? "PASS" : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
        if (ok) localPass++;
        else localFail++;
    }

    /** MM-5, on a server without MapMod: nothing this client captured is fogged. Waits (up to 30 s) for the open map. */
    private static void localFog(Minecraft mc) {
        if (++localFogTicks < 600 && !ownCaptureReady(mc)) return;
        localFogTicks = 0;
        for (BakeCheck.Result r : ownCapture(mc)) localCheck(r.what(), r.ok(), r.detail());
        MapMod.LOGGER.info("SELFTEST local done: {} passed, {} failed", localPass, localFail);
        localDone = true;
    }

    /** True once the open map holds the captured land round the player and has nothing left to do. */
    private static boolean ownCaptureReady(Minecraft mc) {
        return mc.player != null && PaintMap.idle() && PaintMap.fogAt(mc.player.getBlockX(), mc.player.getBlockZ()) >= 0;
    }

    /**
     * The fog is this client's own capture, whatever the group's fog says (MM-5): with the group's fog cleared for the
     * check (so every captured chunk lies past it, beyond the 6-chunk visit radius too), every captured chunk may show at
     * the fog's source, every one further in than the fog's soft edge shows, and so does the land round the player.
     */
    private static List<BakeCheck.Result> ownCapture(Minecraft mc) {
        List<BakeCheck.Result> out = new ArrayList<>();
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        Long2ObjectOpenHashMap<long[]> kept = new Long2ObjectOpenHashMap<>(MapClient.FOG);
        MapClient.FOG.clear();
        try {
            int pcx = px >> 4, pcz = pz >> 4, r = mc.options.getEffectiveRenderDistance() + 2;
            int rim = (int) Math.ceil(PaintPalette.current().fogEdge() / 16) + 1;
            int captured = 0, beyondVisit = 0, unexplored = 0, inner = 0, hidden = 0;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int bx = (pcx + dx) * 16 + 8, bz = (pcz + dz) * 16 + 8;
                    if (!PaintMap.captured(bx, bz)) continue;
                    captured++;
                    if (dx * dx + dz * dz > MapServer.VISIT_RADIUS * MapServer.VISIT_RADIUS) beyondVisit++;
                    if (!PaintMap.fogSourceExplores(bx, bz)) unexplored++;
                    boolean deep = true;
                    for (int k = -rim; k <= rim && deep; k++) {
                        for (int j = -rim; j <= rim && deep; j++) deep = PaintMap.captured(bx + j * 16, bz + k * 16);
                    }
                    if (!deep) continue;
                    inner++;
                    if (!PaintMap.fogShows(bx, bz)) hidden++;
                }
            }
            float here = PaintMap.fogAt(px, pz);
            out.add(new BakeCheck.Result("with the group's fog cleared, every captured chunk may show, those past the visit radius too",
                captured > 0 && beyondVisit > 0 && unexplored == 0, captured + " captured chunks, " + beyondVisit + " past the visit radius, " + unexplored + " fogged at the source"));
            out.add(new BakeCheck.Result("every captured chunk further in than the fog's soft edge (" + rim + " chunks) shows, and the land round the player fully",
                inner > 0 && hidden == 0 && here >= 0.99f, inner + " chunks that far in, " + hidden + " of them fogged; mask round the player " + here));
        } finally {
            MapClient.FOG.putAll(kept);
        }
        return out;
    }

    /** "ownfog" on a server with MapMod, in a real group: steps until the open map is ready, then checks. */
    private static int ownFogTicks;

    private static void ownFogWatch(Minecraft mc) {
        if (ownFogTicks == 0) return;
        if (++ownFogTicks < 600 && !ownCaptureReady(mc)) return;
        ownFogTicks = 0;
        passed = failed = 0;
        expect("the player is in a real group with a fog of its own", !MapClient.FOG.isEmpty(), MapClient.FOG.size() + " regions");
        for (BakeCheck.Result r : ownCapture(mc)) expect("in a real group: " + r.what(), r.ok(), r.detail());
        MapMod.LOGGER.info("SELFTEST client done ownfog: {} passed, {} failed", passed, failed);
    }

    private static MapMarker named(String name) {
        for (MapMarker m : MapClient.markers) if (m.name.equals(name)) return m;
        return null;
    }

    private static void control(MapTests.Control p) {
        Minecraft mc = Minecraft.getInstance();
        switch (p.action()) {
            case OPEN -> {
                MapScreen screen = new MapScreen();
                if (p.arg().equals("edge") && mc.player != null && mc.level != null) {
                    // centred where the captured land ends east of the player: the fog's edge, close up
                    screen.centre(capturedEdge(mc), mc.player.getBlockZ(), p.zoom());
                } else {
                    MapMarker m = p.arg().isEmpty() ? null : named(p.arg());
                    screen.centre(m == null ? p.x() : m.x, m == null ? p.z() : m.z, p.zoom());
                }
                MapClient.show(screen);
            }
            case CLOSE -> {
                if (mc.gui.screen() instanceof MapScreen) mc.gui.setScreen(null);
            }
            case HIDE -> MapClient.HIDDEN.add(p.arg());
            case SHOW -> MapClient.HIDDEN.remove(p.arg());
            case UNFOLD -> MapScreen.unfold(p.arg());
            case PIN_MARKER -> {
                MapMarker m = named(p.arg());
                if (m != null) MapClient.pin(MapClient.markerPin(m));
            }
            case EDIT -> {
                if (mc.gui.screen() instanceof MapScreen screen) screen.newMarker(p.x() + 20, p.z() + 20);
            }
            case CHECK -> check(p.arg());
            default -> {
            }
        }
    }

    /** The painted map's shader and palette, before the map has been opened. */
    private static void painted() {
        String compiled = PaintMap.compileCheck();
        expect("the painted map's shaders compile (paint, and quads for baked tiles, mips and the finish)", compiled == null, compiled == null ? "" : compiled);
        PaintPalette pal = PaintPalette.current();
        expect("map_palette.json is read from the resources", PaintPalette.loads() > 0 && PaintPalette.lastError().isEmpty()
            && pal.paper() == 0xE6DCC6 && pal.classColour()[0] == 0xAAB38E && pal.snowAbove() == 190 && pal.hillshade() == 0.4f,
            PaintPalette.loads() + " loads " + PaintPalette.lastError());
        PaintPalette broken = PaintPalette.parse(JsonParser.parseString(
            "{\"classes\": {\"2\": {\"colour\": \"#123456\"}, \"3\": {\"colour\": \"not a colour\"}}, \"snow\": {\"above\": 150}}").getAsJsonObject());
        expect("a palette keeps the built-in value for whatever it leaves out or gets wrong",
            broken.classColour()[2] == 0x123456 && broken.classColour()[3] == PaintPalette.DEFAULT.classColour()[3]
                && broken.snowAbove() == 150 && broken.paper() == PaintPalette.DEFAULT.paper(), "");
        expect("map_palette.json carries the MM-3 values (shore, edges, forest, contours, finish)",
            pal.shore() == 0xD2E2DC && pal.shoreWidth() == 6 && pal.deepDist() == 40 && pal.edgeWarp() == 5 && pal.forestDarken() == 0.28f
                && pal.contourInterval() == 16 && pal.grain() == 0.035f && pal.vignette() == 0.18f, pal.toString());
        PaintPalette partial = PaintPalette.parse(JsonParser.parseString(
            "{\"water\": {\"shore\": \"#102030\", \"shore_width\": \"wide\"}, \"forest\": {\"stipple\": 0.5}, \"contours\": {\"interval\": 8},"
                + " \"finish\": {\"vignette\": null}, \"edges\": 3}").getAsJsonObject());
        PaintPalette d = PaintPalette.DEFAULT;
        expect("each new palette value falls back on its own",
            partial.shore() == 0x102030 && partial.shoreWidth() == d.shoreWidth() && partial.deepDist() == d.deepDist()
                && partial.forestStipple() == 0.5f && partial.forestDarken() == d.forestDarken() && partial.contourInterval() == 8
                && partial.contourStrength() == d.contourStrength() && partial.vignette() == d.vignette() && partial.grain() == d.grain()
                && partial.edgeSoftness() == d.edgeSoftness() && partial.edgeWarp() == d.edgeWarp()
                && partial.waterShallow() == d.waterShallow(), partial.toString());
        PaintPalette fogged = PaintPalette.parse(JsonParser.parseString("{\"fog\": {\"edge\": 96, \"clouds\": \"lots\"}}").getAsJsonObject());
        expect("map_palette.json carries the fog (colour, edge width, clouds), each value falling back on its own",
            pal.fogColour() == 0xD8CFBA && pal.fogEdge() == 48 && pal.fogClouds() == 0.6f && fogged.fogEdge() == 96
                && fogged.fogClouds() == d.fogClouds() && fogged.fogColour() == d.fogColour(), pal.fogColour() + " " + pal.fogEdge() + " " + pal.fogClouds());
        distanceField();
        String canopy = PaintMap.canopyCheck();
        expect("canopy is prepared and uploaded as the surface texture's alpha", canopy == null, canopy == null ? "" : canopy);
        contourToggle();
    }

    /** The shore distance field on a small grid: a straight coast, a one-sample river, open land and the cap. */
    private static void distanceField() {
        int w = 40, h = 30;
        boolean[] wet = new boolean[w * h];
        // sea on the left (x < 10), a river one sample wide at x = 25
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < 10; x++) wet[x + z * w] = true;
            wet[25 + z * w] = true;
        }
        float[] d = TilePrep.signedDistance(wet, w, h, 16);
        boolean coast = Math.abs(d[9 + 15 * w] - 0.5f) < 1e-3 && Math.abs(d[10 + 15 * w] + 0.5f) < 1e-3;
        boolean deep = Math.abs(d[2 + 15 * w] - 7.5f) < 1e-3 && Math.abs(d[17 + 15 * w] + 7.5f) < 1e-3;
        boolean river = Math.abs(d[25 + 15 * w] - 0.5f) < 1e-3 && Math.abs(d[24 + 15 * w] + 0.5f) < 1e-3 && Math.abs(d[27 + 15 * w] + 1.5f) < 1e-3;
        boolean[] dry = new boolean[w * h];
        float[] none = TilePrep.signedDistance(dry, w, h, 16);
        boolean capped = none[0] == -16f && none[w * h - 1] == -16f;
        boolean[] diag = new boolean[w * h];
        diag[0] = true;
        float[] dd = TilePrep.signedDistance(diag, w, h, 16);
        boolean euclid = Math.abs(dd[3 + 4 * w] + 4.5f) < 1e-3;
        expect("the shore distance field: half a sample either side of the coast, exact inland and at sea, rivers, the cap",
            coast && deep && river && capped && euclid,
            "coast " + coast + " deep " + deep + " river " + river + " cap " + capped + " euclid " + euclid);
    }

    /** Contours are off by default; the map's switch turns them on and off and the setting is saved. */
    private static void contourToggle() {
        boolean before = MapClient.contours;
        MapScreen.toggleContours();
        boolean on = MapClient.contours;
        String saved = "";
        try {
            saved = Files.readString(MapClient.settingsPath());
        } catch (Exception e) {
            saved = e.toString();
        }
        boolean savedOn = saved.replace(" ", "").contains("\"contours\":true");
        MapScreen.toggleContours();
        boolean off = !MapClient.contours;
        try {
            saved = Files.readString(MapClient.settingsPath());
        } catch (Exception e) {
            saved = e.toString();
        }
        boolean savedOff = saved.replace(" ", "").contains("\"contours\":false");
        expect("contours are off by default, and the map's switch turns them on and off and saves it",
            !before && on && savedOn && off && savedOff, "before " + before + " on " + on + " saved " + savedOn + " off " + off + " saved " + savedOff);
    }

    private static void expect(String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        MapMod.LOGGER.info("SELFTEST client {} {}{}", ok ? "PASS" : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
    }

    private static void check(String what) {
        Minecraft mc = Minecraft.getInstance();
        passed = failed = 0;
        if (what.equals("bigworld")) {
            bigWorld();
            return;
        }
        if (what.startsWith("settle ")) {
            settling = what.substring(7);
            idleTicks = settleTicks = 0;
            return;
        }
        if (what.equals("far")) {
            // the far-zoom checks follow the next settle
            farPending = true;
            return;
        }
        if (what.equals("fog")) {
            fogCheck = new FogCheck(SyntheticTiles::make);
            return;
        }
        if (what.equals("overlays")) {
            overlays();
            return;
        }
        if (what.equals("ownfog")) {
            ownFogTicks = 1;
            return;
        }
        if (what.equals("dimension")) {
            dimensionArmed = true;
            dimensionTicks = 0;
            dimensionFreesAt = PaintMap.dimensionFrees();
            dimensionBytesAt = PaintMap.cacheBytes();
            return;
        }
        if (what.equals("api")) {
            ApiClientTests.start();
            return;
        }
        if (what.equals("apiunlock")) {
            ApiClientTests.unlock();
            return;
        }
        if (what.equals("bake")) {
            bakePass = bakeFail = 0;
            bakeCheck = new BakeCheck(SyntheticTiles::make);
            return;
        }
        if (what.equals("unload")) {
            PaintMap.unload();
            expect("leaving the world lets the baked tiles and mips go", PaintMap.cacheBytes() == 0 && PaintMap.bakedTiles() == 0 && PaintMap.mips() == 0,
                PaintMap.cacheBytes() + " bytes");
            MapMod.LOGGER.info("SELFTEST client done unload: {} passed, {} failed", passed, failed);
            return;
        }
        if (what.equals("join")) {
            // before any test provider steps in: a plain join with the map's defaults
            expect("on joining, with nothing but the map's defaults, everything is unlocked", MapClient.unlocks == MapFeature.ALL, Integer.toString(MapClient.unlocks));
            expect("on joining, the map's id arrives (the client's tile cache needs it)", MapClient.mapId != null, String.valueOf(MapClient.mapId));
            MapClient.open();
            expect("on joining, M opens the fullscreen map", mc.gui.screen() instanceof MapScreen, "");
            if (mc.gui.screen() instanceof MapScreen) mc.gui.setScreen(null);
            painted();
            cleanupJoin();
        } else if (what.equals("locked")) {
            expect("F3+T reloads the map palette", PaintPalette.loads() > palettesBeforeReload && PaintPalette.lastError().isEmpty(),
                palettesBeforeReload + " -> " + PaintPalette.loads() + " loads " + PaintPalette.lastError());
            ApiClientTests.afterReload(paletteVersionBeforeReload);
            expect("the provider's lock reaches the client: terrain on, the fullscreen map off",
                MapClient.unlocked(MapFeature.TERRAIN) && !MapClient.unlocked(MapFeature.FULLSCREEN), Integer.toString(MapClient.unlocks));
            MapClient.open();
            expect("the fullscreen map does not open while locked, with the provider's message", !(mc.gui.screen() instanceof MapScreen)
                && MapClient.lockedKey.equals("mapmod_selftest.locked"), MapClient.lockedKey);
        } else {
            expect("everything is unlocked by default", MapClient.unlocks == MapFeature.ALL, Integer.toString(MapClient.unlocks));
            expect("the group provider's word for a shared marker reaches the client", MapClient.sharedKey.equals("mapmod_selftest.shared"), MapClient.sharedKey);
            Set<String> kinds = new HashSet<>();
            int purities = 0;
            for (List<MapPoi> list : MapClient.POIS.values()) {
                for (MapPoi poi : list) {
                    kinds.add(poi.kind());
                    if (poi.kind().equals(TestPois.ORE.key())) purities |= 1 << poi.purity();
                }
            }
            expect("a POI source's POIs arrive (ores and a camp)", kinds.contains(TestPois.ORE.key()) && kinds.contains(TestPois.CAMP.key()), kinds.toString());
            expect("ores arrive with their purity", purities != 0, Integer.toBinaryString(purities));
            expect("the chunk hook's POIs arrive: one for everyone and one the group owns", kinds.contains(TestPois.BEACON.key()) && kinds.contains(TestPois.CACHE.key()), kinds.toString());
            long here = mc.player == null ? 0 : MapGrid.regionOfBlock(mc.player.getBlockX(), mc.player.getBlockZ());
            expect("the group's fog round the player arrives", MapClient.FOG.containsKey(here), "");
            expect("a shared marker from the group's other player and the player's own marker arrive",
                named("Placed by a group mate") != null && named("My marker") != null && MapClient.markers.stream().anyMatch(m -> m.death), MapClient.markers.size() + " markers");
            List<MovingPoi> moving = MapClient.moving;
            expect("moving POIs arrive", moving.size() == 1 && moving.get(0).kind().equals(TestPois.CART.key()), moving.size() + " moving");
            Set<String> filters = new HashSet<>();
            for (MapFilters.Filter f : MapFilters.all()) filters.add(f.key());
            expect("the filter panel has the registered category, its kinds and its sub-types, and the map's own",
                filters.contains(TestPois.FINDS.filterKey()) && filters.contains(TestPois.ORE.filterKey("iron")) && filters.contains(TestPois.CAMP.filterKey())
                    && !filters.contains(TestPois.ORE.filterKey()) && !filters.contains(TestPois.CART.filterKey())
                    && filters.contains("cat:mapmod:players") && filters.contains(MapFilters.DEATHS), filters.size() + " switches");
            expect("a registered glyph joins the stock set", TestPois.TENT >= 28 && MapGlyphs.count() > TestPois.TENT, Integer.toString(MapGlyphs.count()));
            MapPoi tin = MapPoi.of(TestPois.ORE, "tin", 2, 0, 64, 0);
            MapPoi gold = MapPoi.of(TestPois.ORE, "gold", 2, 0, 64, 0);
            expect("a sub-type's item falls back to the next one the game has", MapDraw.item(tin).is(Items.RAW_GOLD) && MapDraw.item(gold).isEmpty(),
                MapDraw.item(tin).toString());
            List<String> faceless = new ArrayList<>();
            int mobs = 0;
            for (Entity en : mc.level.entitiesForRendering()) {
                if (!(en instanceof LivingEntity living) || en instanceof Player) continue;
                if (en.distanceTo(mc.player) > 64) continue;
                mobs++;
                if (!MobRadar.hasFace(living)) faceless.add(EntityType.getKey(en.getType()) + (living.isBaby() ? " (baby)" : ""));
            }
            expect("the mob radar finds a face on every mob near the player, read off its model", mobs >= 8 && faceless.isEmpty(),
                mobs + " mobs, no face for " + faceless);
            expect("a pinned marker is kept", !MapClient.PINS.isEmpty(), MapClient.PINS.size() + " pins");
            expect("the map runs next to Quicksilver", FabricLoader.getInstance().isModLoaded("quicksilver"), "");
            for (CaptureClientTests.Check c : CaptureClientTests.run(false)) expect(c.what(), c.ok(), c.detail());
            // the map was opened, zoomed and closed by the script before this
            expect("the painted map painted frames and uploaded tiles while open", PaintMap.frames() > 0 && PaintMap.uploads() > 0,
                PaintMap.frames() + " frames, " + PaintMap.uploads() + " uploads, " + PaintMap.tilesPainted() + " tiles in the last");
            expect("the painted map uploads, bakes and copies mips at most " + PaintMap.UPLOADS_PER_FRAME + " times a frame",
                PaintMap.maxUploadsPerFrame() <= PaintMap.UPLOADS_PER_FRAME, PaintMap.maxUploadsPerFrame() + " at most");
            expect("the painted map lets its tiles' data go when it closes", !PaintMap.open(), "");
            expect("the baked tiles and mips stay while the map is closed in the same world", PaintMap.bakedTiles() + PaintMap.mips() > 0
                && PaintMap.cacheBytes() > 0, PaintMap.bakedTiles() + " tiles, " + PaintMap.mips() + " mips, " + PaintMap.cacheBytes() / 1048576 + " MB");
            expect("tiles are prepared off the render thread before they are uploaded", PaintMap.prepared() >= PaintMap.uploads(),
                PaintMap.prepared() + " prepared, " + PaintMap.uploads() + " uploaded");
            expect("tiles are baked once into the cache", PaintMap.bakes() > 0, PaintMap.bakes() + " bakes");
            changeEvents();
            timerFallback();
            List<String> stats = Capture.statsLines();
            expect("/mapmod stats shows render time and uploads per frame",
                stats.stream().anyMatch(l -> l.contains("us/frame")) && stats.stream().anyMatch(l -> l.contains("uploads")), String.join(" | ", stats));
            // F3+T: the reload runs while the next checks are made, and the "locked" check sees the palette read again
            palettesBeforeReload = PaintPalette.loads();
            paletteVersionBeforeReload = MapPalette.version();
            mc.reloadResourcePacks();
        }
        MapMod.LOGGER.info("SELFTEST client done {}: {} passed, {} failed", what, passed, failed);
    }
}
