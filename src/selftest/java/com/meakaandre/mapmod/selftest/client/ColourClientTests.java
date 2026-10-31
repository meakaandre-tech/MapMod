package com.meakaandre.mapmod.selftest.client;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.Capture;
import com.meakaandre.mapmod.client.capture.Probe;
import com.meakaandre.mapmod.client.capture.SurfaceClass;
import com.meakaandre.mapmod.client.map.LocalMap;
import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.map.MapDraw;
import com.meakaandre.mapmod.client.map.MapScreen;
import com.meakaandre.mapmod.client.map.MobRadar;
import com.meakaandre.mapmod.client.paint.PaintMap;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.PackRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * MM-7 and MM-8, on the server without MapMod: the smoke run builds a flat patch at y 150 round 0,0 in a mushroom fields
 * biome, five strips 16 blocks wide from west to east (mycelium, grass blocks, sand, stone, orange terracotta), and puts
 * the player on it. Once the client has captured it, each strip is stored and painted in its block's real colour
 * (mycelium purple-grey, grass green, sand sandy, stone grey, terracotta orange). The map is then opened on the patch at
 * three zooms (live close up, baked at a pixel a block, far): the run takes a screenshot of each, and check-selftests.py
 * reads the strips' colours off them, and that each is a soft area with sharp borders and no block grid, from the
 * geometry logged here ("SELFTEST colours geometry ..."). The run then replaces the mycelium with grass blocks on the
 * server, and the client's map must follow through its own block-change hook alone. A resource pack that turns stone's
 * texture blue is switched on: the stone strip must turn blue on the map, and back when it is switched off. A dozen
 * mooshrooms on the patch must show as a few faces with counts, not a dozen. Then the map's top bar at GUI scales 3 and
 * 4 (the run is 1920x1080): nothing in it overlaps, and the hint fits. Logs "SELFTEST colours shot ..." where the run
 * takes a screenshot, and "SELFTEST colours done" at the end.
 */
final class ColourClientTests {
    /** The patch's five strips (west to east) and the middle of each; the probes are on row Z. */
    static final int MYCELIUM_X = -32, GRASS_X = -16, SAND_X = 0, STONE_X = 16, TERRACOTTA_X = 32, Z = 0, TOP = 150;
    private static final int[] XS = {MYCELIUM_X, GRASS_X, SAND_X, STONE_X, TERRACOTTA_X};
    private static final String[] NAMES = {"mycelium", "grass", "sand", "stone", "terracotta"};
    /** Ticks of the client's own map before giving up on the patch being captured, the replacement, and the resource pack. */
    private static final int CAPTURE_PATIENCE = 2400, REPLACE_PATIENCE = 1200, PACK_PATIENCE = 2400, SETTLE_PATIENCE = 900;
    /** The test resource pack: stone's texture made one plain blue. */
    static final String PACK = "mapmod_selftest_stone";
    static final int PACK_BLUE = 0x2850C8;

    private static int state, waited, pass, fail, guiBefore = 2, idleTicks;
    private static int colourMycelium = -1, stoneBefore = -1, stonePainted = -1, recaptures;
    private static CompletableFuture<Probe.Result[]> pending;
    private static CompletableFuture<Void> reload;

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
        @SuppressWarnings("unchecked") CompletableFuture<Probe.Result>[] f = new CompletableFuture[XS.length];
        for (int i = 0; i < XS.length; i++) f[i] = Probe.at(mc.level, XS[i], Z);
        return CompletableFuture.allOf(f).thenApply(v -> {
            Probe.Result[] r = new Probe.Result[XS.length];
            for (int i = 0; i < r.length; i++) r[i] = f[i].join();
            return r;
        });
    }

    private static boolean onPatch(Probe.Result r) {
        return r != null && r.captured() && r.storedHeight() >= TOP - 1 && r.storedHeight() <= TOP + 1;
    }

    /** Probes every few ticks; the latest finished results, else null. */
    private static Probe.Result[] poll(Minecraft mc, int every) {
        if (pending == null && waited % every == 0) pending = probeAll(mc);
        if (pending == null || !pending.isDone()) return null;
        Probe.Result[] r = pending.getNow(null);
        pending = null;
        return r;
    }

    /** True once the open map has had nothing to do for a second (or after a while anyway). */
    private static boolean settled() {
        idleTicks = PaintMap.idle() ? idleTicks + 1 : 0;
        return idleTicks >= 20 || waited >= SETTLE_PATIENCE;
    }

    private static void open(float scale) {
        if (Minecraft.getInstance().gui.screen() instanceof MapScreen screen) {
            screen.centre(0, 0, scale);
            return;
        }
        MapScreen screen = new MapScreen();
        screen.centre(0, 0, scale);
        MapClient.show(screen);
    }

    /**
     * Where the patch is on screen, in real pixels: x of world x 0, y of world z 0, and pixels a block. The smoke run's
     * check reads the strips off the screenshot with it.
     */
    private static void geometry(String shot) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof MapScreen screen)) {
            check("the map is open for the " + shot + " shot", false, "");
            return;
        }
        MapDraw.View v = screen.view();
        int gs = Math.max(1, mc.getWindow().getGuiScale());
        MapMod.LOGGER.info(String.format(Locale.ROOT, "SELFTEST colours geometry %s x0 %.2f z0 %.2f ppb %.4f", shot, v.sx(0) * gs, v.sy(0) * gs, v.scale() * gs));
    }

    private static void next() {
        state++;
        waited = 0;
        idleTicks = 0;
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
                waited = t;
                Probe.Result[] r = poll(mc, 20);
                if (r == null) return;
                for (Probe.Result p : r) if (!onPatch(p)) return;
                colours(r);
                next();
            }
            case 1 -> {
                // after the client's own checks have opened and closed the map, a close look at the patch (painted live)
                if (!MapClientTests.localDone) return;
                if (++waited == 1) {
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
                    open(3f);
                    return;
                }
                if (waited < 100 || !settled()) return;
                stacking();
                geometry("patch");
                MapMod.LOGGER.info("SELFTEST colours shot patch");
                next();
            }
            case 2, 3 -> {
                // baked at a pixel a block, then far (level 2)
                String shot = state == 2 ? "patch-mid" : "patch-far";
                if (++waited == 40) open(state == 2 ? 0.5f : 0.125f);
                if (waited < 80 || !settled()) return;
                check("the map settles on the patch " + (state == 2 ? "baked at a pixel a block" : "far out"), PaintMap.idle(), PaintMap.lastFrame());
                geometry(shot);
                MapMod.LOGGER.info("SELFTEST colours shot {}", shot);
                next();
            }
            case 4 -> {
                // the run replaces the mycelium with grass blocks on the server: the map follows by itself
                if (++waited == 40) open(3f);
                if (waited > REPLACE_PATIENCE) {
                    check("replacing mycelium with grass changes the painted colour", false, "no change after " + waited + " ticks");
                    next();
                    return;
                }
                Probe.Result[] r = poll(mc, 10);
                if (r == null || r[0].storedClass() != SurfaceClass.GRASS) return;
                for (Probe.Result p : r) for (String line : p.lines()) MapMod.LOGGER.info("SELFTEST colours probe after: {}", line);
                check("replacing mycelium with grass changes the painted colour", r[0].colour() != colourMycelium && green(r[0].colour()),
                    String.format(Locale.ROOT, "%s -> %s after %d ticks", hex(colourMycelium), hex(r[0].colour()), waited));
                next();
            }
            case 5 -> {
                if (++waited >= 160 && settled()) {
                    MapMod.LOGGER.info("SELFTEST colours shot replaced");
                    next();
                }
            }
            case 6 -> {
                // a resource pack that turns stone blue: the stone strip turns blue on the map
                if (++waited == 1) {
                    recaptures = Capture.colourRecaptures();
                    String err = switchPack(mc, true);
                    check("a test resource pack that changes stone's texture is written and switched on", err.isEmpty(), err);
                    if (!err.isEmpty()) {
                        state = 9;
                        waited = 0;
                        return;
                    }
                }
                if (reload != null && !reload.isDone()) return;
                if (waited > PACK_PATIENCE) {
                    check("a resource pack that changes stone's texture changes the stone's colour on the map", false,
                        "no change after " + waited + " ticks; " + (Capture.colourRecaptures() - recaptures) + " recaptures");
                    next();
                    return;
                }
                if (Capture.colourRecaptures() == recaptures) return;
                Probe.Result[] r = poll(mc, 10);
                if (r == null || diff(r[3].storedColour(), stoneBefore) < 40) return;
                Probe.Result s = r[3];
                for (String line : s.lines()) MapMod.LOGGER.info("SELFTEST colours probe with the pack: {}", line);
                check("a resource pack that changes stone's texture changes the stone's colour on the map",
                    blue(s.average()) && blue(s.storedColour()) && blue(s.colour()) && diff(s.colour(), stonePainted) >= 60,
                    String.format(Locale.ROOT, "texture average %s, stored %s -> %s, painted %s -> %s after %d ticks",
                        hex(s.average()), hex(stoneBefore), hex(s.storedColour()), hex(stonePainted), hex(s.colour()), waited));
                check("the other strips keep their colours", green(r[1].colour()) && sandy(r[2].colour()) && orange(r[4].colour()),
                    hex(r[1].colour()) + ", " + hex(r[2].colour()) + ", " + hex(r[4].colour()));
                next();
            }
            case 7 -> {
                if (++waited == 1) open(3f);
                if (waited < 60 || !settled()) return;
                MapMod.LOGGER.info("SELFTEST colours shot pack");
                next();
            }
            case 8 -> {
                // the pack off again: stone is grey again
                if (++waited == 1) {
                    recaptures = Capture.colourRecaptures();
                    String err = switchPack(mc, false);
                    if (!err.isEmpty()) {
                        check("the test resource pack is switched off again", false, err);
                        next();
                        return;
                    }
                }
                if (reload != null && !reload.isDone()) return;
                if (waited > PACK_PATIENCE) {
                    check("switching the pack off brings stone's own colour back", false, "no change after " + waited + " ticks");
                    next();
                    return;
                }
                if (Capture.colourRecaptures() == recaptures) return;
                Probe.Result[] r = poll(mc, 10);
                if (r == null || diff(r[3].storedColour(), stoneBefore) > 6) return;
                check("switching the pack off brings stone's own colour back", grey(r[3].colour()),
                    hex(r[3].storedColour()) + ", painted " + hex(r[3].colour()));
                next();
            }
            case 9, 10 -> {
                // the top bar at GUI scale 3, then 4, with the map open (the run's mouse is over the map, so the
                // position under it shows too)
                int gui = state == 9 ? 3 : 4;
                if (++waited == 1) {
                    if (!(mc.gui.screen() instanceof MapScreen)) open(3f);
                    guiBefore = mc.options.guiScale().get();
                    mc.options.guiScale().set(gui);
                    mc.resizeGui();
                }
                if (waited == 60) {
                    bar(mc, gui);
                    MapMod.LOGGER.info("SELFTEST colours shot gui{}", gui);
                }
                if (waited == 100) next();
            }
            default -> {
                mc.options.guiScale().set(guiBefore);
                mc.resizeGui();
                layouts(mc);
                finish();
            }
        }
    }

    /**
     * Writes the test pack into the resource pack folder (pack.mcmeta for this game's resource format, and stone's
     * texture as one plain blue) and switches it on, or off again, and reloads the resources. "" if all went well.
     */
    private static String switchPack(Minecraft mc, boolean on) {
        try {
            Path dir = mc.getResourcePackDirectory().resolve(PACK);
            if (on) {
                PackFormat f = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
                String format = f.minor() == 0 ? Integer.toString(f.major()) : "[" + f.major() + ", " + f.minor() + "]";
                Files.createDirectories(dir.resolve("assets/minecraft/textures/block"));
                Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\": {\"description\": \"MapMod selftest: blue stone\", \"min_format\": "
                    + format + ", \"max_format\": " + format + "}}");
                try (NativeImage image = new NativeImage(16, 16, false)) {
                    for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setPixel(x, y, 0xFF000000 | PACK_BLUE);
                    image.writeToFile(dir.resolve("assets/minecraft/textures/block/stone.png"));
                }
            }
            PackRepository repo = mc.getResourcePackRepository();
            repo.reload();
            String id = "file/" + PACK;
            if (on && !repo.isAvailable(id)) return "the pack " + id + " is not found; available: " + repo.getAvailableIds();
            boolean changed = on ? repo.addPack(id) : repo.removePack(id);
            if (!changed) return "the pack " + id + " could not be switched " + (on ? "on" : "off") + "; selected: " + repo.getSelectedIds();
            MapMod.LOGGER.info("SELFTEST colours resource pack {} {}, reloading", id, on ? "on" : "off");
            reload = mc.reloadResourcePacks();
            return "";
        } catch (Exception e) {
            return e.toString();
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
        Probe.Result m = r[0], g = r[1], sa = r[2], s = r[3], te = r[4];
        boolean top = true;
        for (Probe.Result p : r) top &= p.storedHeight() == TOP;
        check("each sample is the top block itself, not the one under it", top, "heights at " + TOP + "?");
        check("grass blocks are classed as grass", g.storedClass() == SurfaceClass.GRASS, g.block() + " is " + SurfaceClass.name(g.storedClass()));
        check("mycelium is classed as dirt/mud", m.storedClass() == SurfaceClass.DIRT, m.block() + " is " + SurfaceClass.name(m.storedClass()));
        check("stone is classed as rock", s.storedClass() == SurfaceClass.ROCK, s.block() + " is " + SurfaceClass.name(s.storedClass()));
        check("sand is classed as sand", sa.storedClass() == SurfaceClass.SAND, sa.block() + " is " + SurfaceClass.name(sa.storedClass()));
        // MM-8: the real colours, from the top faces' textures
        check("the probe reads each top face's own texture, and grass is tinted by the biome",
            m.texture().contains("mycelium_top") && g.texture().contains("grass_block_top") && g.tintKind().equals("biome grass")
                && sa.texture().contains("block/sand") && s.texture().contains("block/stone") && te.texture().contains("orange_terracotta"),
            m.texture() + ", " + g.texture() + " (" + g.tintKind() + "), " + sa.texture() + ", " + s.texture() + ", " + te.texture());
        check("the stored colour is what the capture reads from the ground now", m.storedColour() == m.liveStored() && s.storedColour() == s.liveStored(),
            hex(m.storedColour()) + " / " + hex(m.liveStored()));
        check("mycelium paints purple-grey (its own colour, no longer dirt brown)", purpleGrey(m.colour()) && purpleGrey(m.storedColour()),
            "stored " + hex(m.storedColour()) + ", painted " + hex(m.colour()));
        check("grass paints green", green(g.colour()), hex(g.colour()) + ", stored " + hex(g.storedColour()));
        check("sand paints sandy", sandy(sa.colour()), hex(sa.colour()));
        check("stone paints grey", grey(s.colour()), hex(s.colour()));
        check("orange terracotta paints orange", orange(te.colour()), hex(te.colour()));
        StringBuilder diffs = new StringBuilder();
        int least = Integer.MAX_VALUE;
        for (int i = 0; i < r.length; i++) {
            for (int j = i + 1; j < r.length; j++) {
                int d = diff(r[i].colour(), r[j].colour());
                least = Math.min(least, d);
                diffs.append(NAMES[i]).append('/').append(NAMES[j]).append(' ').append(d).append(", ");
            }
        }
        check("the five strips are five clearly different colours", least >= 30, diffs.toString());
        colourMycelium = m.colour();
        stoneBefore = s.storedColour();
        stonePainted = s.colour();
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

    /** Mycelium's colour: a muted grey with a purple cast (red and blue a little over green). */
    static boolean purpleGrey(int c) {
        int max = Math.max(r(c), Math.max(g(c), b(c))), min = Math.min(r(c), Math.min(g(c), b(c)));
        return r(c) >= g(c) + 4 && b(c) >= g(c) + 2 && max - min <= 48;
    }

    static boolean sandy(int c) {
        return r(c) >= b(c) + 25 && g(c) >= b(c) + 15 && r(c) + g(c) + b(c) >= 420;
    }

    static boolean orange(int c) {
        return r(c) >= g(c) + 30 && g(c) >= b(c) + 10;
    }

    static boolean blue(int c) {
        return b(c) >= r(c) + 40 && b(c) >= g(c) + 20;
    }

    /** Sum of the channel differences. */
    static int diff(int a, int b) {
        return Math.abs(r(a) - r(b)) + Math.abs(g(a) - g(b)) + Math.abs(b(a) - b(b));
    }
}
