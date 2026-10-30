package com.meakaandre.mapmod.selftest.client;

import com.google.gson.JsonParser;
import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.api.MapGlyphs;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
import com.meakaandre.mapmod.client.capture.Capture;
import com.meakaandre.mapmod.client.map.LocalMap;
import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.map.MapDraw;
import com.meakaandre.mapmod.client.map.MapFilters;
import com.meakaandre.mapmod.client.map.MapScreen;
import com.meakaandre.mapmod.client.map.MobRadar;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import com.meakaandre.mapmod.client.paint.BakeCheck;
import com.meakaandre.mapmod.client.paint.PaintMap;
import com.meakaandre.mapmod.client.paint.PaintPalette;
import com.meakaandre.mapmod.client.paint.TilePrep;
import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapMarker;
import com.meakaandre.mapmod.map.MapPayloads;
import com.meakaandre.mapmod.selftest.MapTests;
import com.meakaandre.mapmod.selftest.TestPois;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
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

/**
 * The smoke run's remote control of the map ({@link MapTests.Control}, from {@code /mapmod worldtest}): opens or closes
 * the map, centred and zoomed, switches or folds out a filter, pins a marker, and runs the checks only the client can
 * make (what arrived from the server, the filter panel, the unlocks). Every check prints "SELFTEST client"; failures say FAIL.
 */
public final class MapClientTests {
    private static int passed, failed;
    private static int palettesBeforeReload = Integer.MAX_VALUE;

    private MapClientTests() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MapTests.Control.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> control(p)));
        ClientTickEvents.END_CLIENT_TICK.register(MapClientTests::local);
        ClientTickEvents.END_CLIENT_TICK.register(MapClientTests::watch);
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
    /** Ticks the open map must have had nothing to do before it counts as settled, and ticks to give up after. */
    private static final int SETTLE = 20, SETTLE_GIVE_UP = 3600;
    /** MM-2's render time at far zoom (us/frame, CPU, averaged): MM-4 must not be slower once settled. */
    private static final double MM2_MICROS = 134;

    private static void watch(Minecraft mc) {
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
        // after settling at far zoom: two seconds timing the GPU, then two with the timer asleep, so the last second's
        // CPU numbers are settled frames without its queries
        if (farTicks > 0) {
            farTicks++;
            if (farTicks == 2) PaintMap.timer().wake();
            if (farTicks == 42) {
                farGpu = Capture.statsLines().stream().filter(l -> l.contains("GPU ")).findFirst().orElse("no GPU line");
                MapMod.LOGGER.info("map stats far, GPU timed: {}", farGpu);
                PaintMap.timer().sleep();
            }
            if (farTicks == 82) {
                farTicks = 0;
                farPending = false;
                far();
            }
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
            micros >= 0 && micros <= MM2_MICROS, micros + " us/frame avg; with the GPU timer on:" + farGpu.substring(Math.max(0, farGpu.indexOf(" render") )));
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
        if (t != LOCAL_CHECK) return;
        int pass = 0, fail = 0;
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        boolean[] checks = {
            MapClient.unlocked(MapFeature.TERRAIN) && MapClient.unlocked(MapFeature.FULLSCREEN),
            MapClient.mapId != null && MapClient.mapId.startsWith("server-"),
            MapClient.explored(px, pz) && MapClient.explored(px + 80, pz) && !MapClient.explored(px + 200, pz),
        };
        String[] what = {
            "with no MapMod on the server the whole map is open",
            "the client keeps its own map for this server (" + MapClient.mapId + ")",
            "the client clears its own fog round the player",
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
    private static long framesAtOpen;

    /** A few seconds after the local map opened with contours on: they were painted. */
    private static void localLater() {
        boolean painted = PaintMap.frames() > framesAtOpen && PaintMap.contoursPainted();
        MapMod.LOGGER.info("SELFTEST local {} the map paints contour lines once they are switched on ({} frames)", painted ? "PASS" : "FAIL",
            PaintMap.frames() - framesAtOpen);
        if (painted) localPass++;
        else localFail++;
        MapMod.LOGGER.info("SELFTEST local done: {} passed, {} failed", localPass, localFail);
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
                MapMarker m = p.arg().isEmpty() ? null : named(p.arg());
                screen.centre(m == null ? p.x() : m.x, m == null ? p.z() : m.z, p.zoom());
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
        } else if (what.equals("locked")) {
            expect("F3+T reloads the map palette", PaintPalette.loads() > palettesBeforeReload && PaintPalette.lastError().isEmpty(),
                palettesBeforeReload + " -> " + PaintPalette.loads() + " loads " + PaintPalette.lastError());
            expect("the provider's lock reaches the client: terrain on, minimap and fullscreen map off",
                MapClient.unlocked(MapFeature.TERRAIN) && !MapClient.unlocked(MapFeature.MINIMAP) && !MapClient.unlocked(MapFeature.FULLSCREEN), Integer.toString(MapClient.unlocks));
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
            mc.reloadResourcePacks();
        }
        MapMod.LOGGER.info("SELFTEST client done {}: {} passed, {} failed", what, passed, failed);
    }
}
