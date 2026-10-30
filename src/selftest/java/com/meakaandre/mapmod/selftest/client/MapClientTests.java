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
import com.meakaandre.mapmod.client.paint.PaintMap;
import com.meakaandre.mapmod.client.paint.PaintPalette;
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
import java.util.Set;

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
        MapScreen screen = new MapScreen();
        screen.centre(px, pz, 0.5f);
        MapClient.show(screen);
        boolean open = mc.gui.screen() instanceof MapScreen;
        MapMod.LOGGER.info("SELFTEST local {} M opens the fullscreen map", open ? "PASS" : "FAIL");
        if (open) pass++;
        else fail++;
        MapMod.LOGGER.info("SELFTEST local done: {} passed, {} failed", pass, fail);
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
        expect("the painted map's shader compiles", compiled == null, compiled == null ? "" : compiled);
        PaintPalette pal = PaintPalette.current();
        expect("map_palette.json is read from the resources", PaintPalette.loads() > 0 && PaintPalette.lastError().isEmpty()
            && pal.paper() == 0xE6DCC6 && pal.classColour()[0] == 0xAAB38E && pal.snowAbove() == 190 && pal.hillshade() == 0.4f,
            PaintPalette.loads() + " loads " + PaintPalette.lastError());
        PaintPalette broken = PaintPalette.parse(JsonParser.parseString(
            "{\"classes\": {\"2\": {\"colour\": \"#123456\"}, \"3\": {\"colour\": \"not a colour\"}}, \"snow\": {\"above\": 150}}").getAsJsonObject());
        expect("a palette keeps the built-in value for whatever it leaves out or gets wrong",
            broken.classColour()[2] == 0x123456 && broken.classColour()[3] == PaintPalette.DEFAULT.classColour()[3]
                && broken.snowAbove() == 150 && broken.paper() == PaintPalette.DEFAULT.paper(), "");
    }

    private static void expect(String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        MapMod.LOGGER.info("SELFTEST client {} {}{}", ok ? "PASS" : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
    }

    private static void check(String what) {
        Minecraft mc = Minecraft.getInstance();
        passed = failed = 0;
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
            expect("the painted map uploads at most " + PaintMap.UPLOADS_PER_FRAME + " tiles a frame",
                PaintMap.maxUploadsPerFrame() <= PaintMap.UPLOADS_PER_FRAME, PaintMap.maxUploadsPerFrame() + " at most");
            expect("the painted map lets its tiles go when it closes", !PaintMap.open(), "");
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
