package com.meakaandre.mapmod.selftest.client;

import com.meakaandre.mapmod.api.MapGlyphs;
import com.meakaandre.mapmod.api.MapLock;
import com.meakaandre.mapmod.api.MapMarkers;
import com.meakaandre.mapmod.api.MapOverlay;
import com.meakaandre.mapmod.api.MapPalette;
import com.meakaandre.mapmod.api.MapSurface;
import com.meakaandre.mapmod.api.MapTiles;
import com.meakaandre.mapmod.api.MapView;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * MM-6's test of the public API, written the way another mod would use it: it imports nothing of MapMod's but
 * {@code com.meakaandre.mapmod.api} (the CI build checks that). It adds an overlay layer with a top-bar switch, a tile
 * listener (both at start-up, as a mod's client initializer would), markers, a palette override, a block's surface
 * class, and locks the whole map and single layers, and checks what the API itself says back. What only MapMod's insides
 * can show (a re-bake, the locked panel, a marker drawn, a new sample's class) is checked by {@link MapClientTests} at
 * the points this test names ({@link #effects}). Every line says "SELFTEST api"; failures say FAIL.
 */
public final class ApiClientTests {
    private static final Logger LOG = LogManager.getLogger("MapMod API test");
    public static final String NS = "mapmod_selftest";
    public static final Identifier OVERLAY = Identifier.fromNamespaceAndPath(NS, "rings");
    /** Whose locks these are. */
    public static final Identifier OWNER = Identifier.fromNamespaceAndPath(NS, "api_test"), OTHER = Identifier.fromNamespaceAndPath(NS, "other_mod");
    public static final Identifier MARKER_A = Identifier.fromNamespaceAndPath(NS, "truck"), MARKER_B = Identifier.fromNamespaceAndPath(NS, "drone"),
        MARKER_NETHER = Identifier.fromNamespaceAndPath(NS, "portal");
    public static final int OVERRIDE_RGB = 0x8040C0;

    /** Points where {@link MapClientTests} checks what the API did inside MapMod: each returns when its check is done. */
    public interface Effects {
        BooleanSupplier at(String point);
    }

    public static Effects effects = point -> () -> true;

    private static int passed, failed;
    /** The overlay's draw calls, and whether every view it got turned world to screen and back. */
    private static volatile long draws;
    private static volatile boolean viewsRoundTrip = true;
    /** The middle of the last view drawn: markers are put where the map shows them. */
    private static volatile double viewX, viewZ;
    /** Tile events heard, the last revision, and whether all came on the client thread. */
    private static volatile long events, lastRevision;
    private static volatile boolean eventsOnClientThread = true;
    private static final List<BooleanSupplier> STEPS = new ArrayList<>();
    private static int waited;

    private ApiClientTests() {
    }

    /** What a mod does in its client initializer: an overlay layer and a tile listener. */
    public static void register() {
        MapOverlay.register(OVERLAY, Component.literal("Test rings"), MapGlyphs.RING, ApiClientTests::drawRings);
        MapTiles.addListener((tx, tz, revision) -> {
            events++;
            lastRevision = revision;
            if (!Minecraft.getInstance().isSameThread()) eventsOnClientThread = false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> step());
    }

    /** Rings round the player every 32 blocks, bent with the painted map's warp, and a check of the view's arithmetic. */
    private static void drawRings(GuiGraphicsExtractor g, MapView view) {
        draws++;
        viewX = view.centreX();
        viewZ = view.centreZ();
        double sx = view.left() + view.width() * 0.3, sy = view.top() + view.height() * 0.7;
        if (Math.abs(view.screenX(view.worldX(sx)) - sx) > 1e-6 || Math.abs(view.screenY(view.worldZ(sy)) - sy) > 1e-6
            || Math.abs(view.screenX(view.centreX()) - (view.left() + view.width() / 2.0)) > 1e-6) viewsRoundTrip = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        for (int r = 32; r <= 96; r += 32) {
            for (int a = 0; a < 48; a++) {
                double t = a * Math.PI * 2 / 48;
                double x = mc.player.getX() + Math.cos(t) * r, z = mc.player.getZ() + Math.sin(t) * r;
                double px = view.screenX(x - MapOverlay.warpX(x, z)), pz = view.screenY(z - MapOverlay.warpZ(x, z));
                if (view.visible(px, pz, 0)) g.fill((int) px, (int) pz, (int) px + 1, (int) pz + 1, 0xC0E05050);
            }
        }
    }

    private static void expect(String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        LOG.info("SELFTEST api {} {}{}", ok ? "PASS" : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
    }

    /** A check made elsewhere ({@link MapClientTests}'s effects), counted here. */
    public static void report(String what, boolean ok, String detail) {
        expect(what, ok, detail);
    }

    private static void step() {
        while (!STEPS.isEmpty()) {
            BooleanSupplier s = STEPS.get(0);
            boolean done;
            try {
                done = s.getAsBoolean();
            } catch (RuntimeException e) {
                LOG.error("SELFTEST api step threw", e);
                expect("a step runs to the end", false, e.toString());
                done = true;
            }
            if (!done) {
                waited++;
                return;
            }
            STEPS.remove(0);
            waited = 0;
        }
    }

    private static void then(Runnable r) {
        STEPS.add(() -> {
            r.run();
            return true;
        });
    }

    /** Waits a number of ticks. */
    private static void ticks(int n) {
        int[] left = {n};
        STEPS.add(() -> --left[0] <= 0);
    }

    /** Waits for one of {@link #effects}' checks (at most a minute). */
    private static void effect(String point) {
        BooleanSupplier[] check = {null};
        STEPS.add(() -> {
            if (check[0] == null) check[0] = effects.at(point);
            if (check[0].getAsBoolean()) return true;
            if (waited < 1200) return false;
            expect("the check at " + point + " finishes", false, "gave up after a minute");
            return true;
        });
    }

    // ------------------------------------------------------------------------------------------------ the run

    /** "check api": everything up to the map being locked, with the map open. */
    public static void start() {
        passed = failed = 0;
        Minecraft mc = Minecraft.getInstance();
        long[] at = new long[1];
        // ---- the overlay layer: drawn while the map is open, its switch saved with the map's layers
        then(() -> {
            expect("an overlay layer is registered with a top-bar switch, on by default", MapOverlay.layers().stream().anyMatch(r -> r.id().equals(OVERLAY))
                && MapOverlay.enabled(OVERLAY) && MapOverlay.shown(OVERLAY), MapOverlay.layers().toString());
            at[0] = draws;
        });
        ticks(10);
        then(() -> {
            expect("the overlay draws every frame the map is open, through a view that turns world to screen and back", draws > at[0] && viewsRoundTrip,
                (draws - at[0]) + " draws in 10 ticks");
            MapOverlay.setEnabled(OVERLAY, false);
            at[0] = draws;
        });
        ticks(10);
        then(() -> {
            String settings = settings();
            expect("its switch turns it off, and is saved with the map's layers in settings.json", !MapOverlay.enabled(OVERLAY) && draws == at[0]
                && settings.contains("\"" + OVERLAY + "\":false"), (draws - at[0]) + " draws while off");
            MapOverlay.setEnabled(OVERLAY, true);
            at[0] = draws;
        });
        ticks(10);
        then(() -> {
            expect("and on again", MapOverlay.enabled(OVERLAY) && draws > at[0] && settings().contains("\"" + OVERLAY + "\":true"), "");
            double wx = MapOverlay.warpX(1000.5, -2000.25), wx2 = MapOverlay.warpX(1000.5 + MapOverlay.WARP_PERIOD, -2000.25 - MapOverlay.WARP_PERIOD);
            double most = 0;
            for (int i = 0; i < 400; i++) most = Math.max(most, Math.abs(MapOverlay.warpZ(i * 37.0, i * 53.0)));
            expect("the warp the painted borders follow repeats every 8192 blocks and stays within the palette's edges.warp",
                MapOverlay.WARP_PERIOD == 8192 && Math.abs(wx - wx2) < 1e-3 && most > 0 && most <= 5 * 1.0001,
                String.format(Locale.ROOT, "%.4f vs %.4f, at most %.3f blocks", wx, wx2, most));
        });

        // ---- markers: added, moved, removed, per dimension
        then(() -> {
            double px = viewX, pz = viewZ;
            MapMarkers.add(new MapMarkers.Marker(MARKER_A, Level.OVERWORLD, px + 15, pz - 10, MapGlyphs.TRUCK, 0xFFFFC060, Component.literal("Test truck")));
            MapMarkers.add(new MapMarkers.Marker(MARKER_B, Level.OVERWORLD, px - 20, pz + 15, MapGlyphs.DRONE, 0xFF60C0FF, null));
            MapMarkers.add(new MapMarkers.Marker(MARKER_NETHER, Level.NETHER, px / 8, pz / 8, MapGlyphs.GATE, 0xFFB060FF, Component.literal("Portal")));
            boolean added = MapMarkers.in(Level.OVERWORLD).size() == 2 && MapMarkers.in(Level.NETHER).size() == 1;
            boolean moved = MapMarkers.move(MARKER_A, px + 20, pz - 15) && MapMarkers.get(MARKER_A).x() == px + 20 && MapMarkers.get(MARKER_A).z() == pz - 15
                && MapMarkers.get(MARKER_A).dimension().equals(Level.OVERWORLD);
            boolean removed = MapMarkers.remove(MARKER_B) && MapMarkers.get(MARKER_B) == null && !MapMarkers.remove(MARKER_B) && !MapMarkers.move(MARKER_B, 0, 0);
            expect("markers are added per dimension, moved and removed", added && moved && removed && MapMarkers.in(Level.OVERWORLD).size() == 1,
                "added " + added + ", moved " + moved + ", removed " + removed);
        });
        effect("markers");

        // ---- the palette: one override over the resource packs' palette, and the map paints again
        then(() -> {
            int before = MapPalette.version();
            MapPalette.classColour(MapSurface.GRASS, OVERRIDE_RGB);
            boolean threw = false;
            try {
                MapPalette.colour("no.such.key", 0);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            boolean typed = false;
            try {
                MapPalette.colour("hillshade", 0);
            } catch (IllegalArgumentException e) {
                typed = true;
            }
            expect("a palette key is overridden from code, and the palette's version moves",
                MapPalette.version() > before && "#8040c0".equals(MapPalette.overrides().get("classes.0.colour")) && MapPalette.keys().contains("fog.edge"),
                before + " -> " + MapPalette.version() + ", " + MapPalette.overrides());
            expect("an unknown key or a number given as a colour is refused", threw && typed, "");
            int v = MapPalette.version();
            MapPalette.edit().number("hillshade", 0.5f).colour("water.shore", 0x102030).apply();
            MapPalette.edit().clear("hillshade").clear("water.shore").apply();
            expect("a builder applies several overrides at once, and they clear again", MapPalette.version() == v + 2
                && MapPalette.overrides().keySet().equals(java.util.Set.of("classes.0.colour")), MapPalette.overrides().toString());
        });
        effect("palette");

        // ---- surface classes: a block and a tag put in a class of the mod's choice, safely from any thread
        then(() -> {
            BlockState sand = Blocks.SAND.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
            boolean before = MapSurface.classOf(sand) == MapSurface.SAND && MapSurface.classOf(stone) == MapSurface.ROCK;
            MapSurface.override(BlockTags.SAND, MapSurface.SNOW);
            boolean tag = MapSurface.classOf(sand) == MapSurface.SNOW && MapSurface.classOf(Blocks.RED_SAND.defaultBlockState()) == MapSurface.SNOW;
            MapSurface.override(Blocks.SAND, MapSurface.ICE);
            boolean block = MapSurface.classOf(sand) == MapSurface.ICE && MapSurface.classOf(Blocks.RED_SAND.defaultBlockState()) == MapSurface.SNOW;
            MapSurface.clear(Blocks.SAND);
            MapSurface.clear(BlockTags.SAND);
            boolean back = MapSurface.classOf(sand) == MapSurface.SAND;
            boolean refused = false;
            try {
                MapSurface.override(Blocks.STONE, 8);
            } catch (IllegalArgumentException e) {
                refused = true;
            }
            expect("a tag, then a block, are put in a class of the mod's choice (the block wins), and cleared again",
                before && tag && block && back && refused, "before " + before + ", tag " + tag + ", block " + block + ", back " + back);
            // four threads classify while the overrides change under them: always one of the two answers, never a throw
            AtomicBoolean wrong = new AtomicBoolean();
            List<Thread> threads = new ArrayList<>();
            long until = System.nanoTime() + 200_000_000L;
            for (int i = 0; i < 4; i++) {
                Thread t = new Thread(() -> {
                    while (System.nanoTime() < until) {
                        try {
                            int c = MapSurface.classOf(stone);
                            if (c != MapSurface.ROCK && c != MapSurface.LAVA) wrong.set(true);
                        } catch (RuntimeException e) {
                            wrong.set(true);
                        }
                    }
                }, "selftest classify " + i);
                threads.add(t);
                t.start();
            }
            int flips = 0;
            while (System.nanoTime() < until) {
                MapSurface.override(Blocks.STONE, MapSurface.LAVA);
                MapSurface.clear(Blocks.STONE);
                flips++;
            }
            for (Thread t : threads) {
                try {
                    t.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            expect("classifying off-thread while overrides change is safe", !wrong.get() && MapSurface.classOf(stone) == MapSurface.ROCK, flips + " changes");
        });
        effect("surface");

        // ---- tile events
        then(() -> expect("tile events reach a listener on the client thread, merged per tile, with long revisions",
            events > 0 && lastRevision > 1_000_000_000_000L && eventsOnClientThread, events + " events, last revision " + lastRevision));

        // ---- locks: a layer, the overlay, then the whole map
        then(() -> {
            MapLock.lockLayer(OWNER, MapLock.POIS);
            MapLock.lockLayer(OWNER, OVERLAY);
            at[0] = draws;
            expect("a layer and an overlay are locked", MapLock.layerLocked(MapLock.POIS) && MapLock.layerLocked(OVERLAY) && !MapOverlay.shown(OVERLAY)
                && MapOverlay.enabled(OVERLAY) && !MapLock.layerLocked(MapLock.PINS), "");
        });
        ticks(10);
        then(() -> expect("a locked overlay is not drawn, though its switch stays on", draws == at[0], (draws - at[0]) + " draws"));
        effect("layers");
        then(() -> {
            MapLock.lockMap(OWNER, Component.literal("Finish the Field Research to use the map"));
            expect("the whole map is locked, with the mod's reason", MapLock.mapLocked() && MapLock.reason() != null
                && MapLock.reason().getString().startsWith("Finish"), "");
            at[0] = draws;
        });
        ticks(20);
        then(() -> {
            expect("nothing is drawn on a locked map", draws == at[0], (draws - at[0]) + " overlay draws");
        });
        effect("locked");
        then(() -> LOG.info("SELFTEST api locked shown"));
    }

    /** "check apiunlock": the locks lifted, two owners' locks kept apart, and the rest put back. */
    public static void unlock() {
        long[] at = new long[1];
        then(() -> {
            MapLock.unlockMap(OWNER);
            MapLock.unlockLayer(OWNER, MapLock.POIS);
            MapLock.unlockLayer(OWNER, OVERLAY);
            at[0] = draws;
            expect("lifting the locks opens the map and its layers again", !MapLock.mapLocked() && MapLock.reason() == null
                && !MapLock.layerLocked(MapLock.POIS) && MapOverlay.shown(OVERLAY), "");
        });
        ticks(10);
        then(() -> expect("and the overlay draws again", draws > at[0], (draws - at[0]) + " draws"));
        effect("unlocked");
        then(() -> {
            MapLock.lockMap(OWNER, Component.literal("one"));
            MapLock.lockMap(OTHER, Component.literal("two"));
            MapLock.lockLayer(OTHER, MapLock.CONTOURS);
            MapLock.unlockMap(OWNER);
            boolean stillLocked = MapLock.mapLocked() && MapLock.reason().getString().equals("two");
            MapLock.unlockAll(OTHER);
            expect("one mod lifting its lock leaves another mod's in place; unlockAll lifts all of a mod's",
                stillLocked && !MapLock.mapLocked() && !MapLock.layerLocked(MapLock.CONTOURS), "");
            MapMarkers.removeAll(NS);
            expect("a mod's markers all go at once", MapMarkers.all().isEmpty(), MapMarkers.all().toString());
            LOG.info("SELFTEST api done: {} passed, {} failed", passed, failed);
        });
    }

    /** The palette override is laid over again after F3+T ("check locked" runs after the reload); then cleared. */
    public static void afterReload(int versionBefore) {
        expect("a palette override is laid over the resource packs' palette again after F3+T, with a new version",
            "#8040c0".equals(MapPalette.overrides().get("classes.0.colour")) && MapPalette.version() > versionBefore,
            versionBefore + " -> " + MapPalette.version());
        MapPalette.clearAll();
        expect("clearAll takes every override away", MapPalette.overrides().isEmpty(), "");
        LOG.info("SELFTEST api done after reload: {} passed, {} failed", passed, failed);
    }

    private static String settings() {
        try {
            return Files.readString(FabricLoader.getInstance().getGameDir().resolve("mapmod").resolve("settings.json")).replace(" ", "");
        } catch (Exception e) {
            return e.toString();
        }
    }
}
