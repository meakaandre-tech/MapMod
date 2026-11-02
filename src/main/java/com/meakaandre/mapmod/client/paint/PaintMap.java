package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.client.capture.Capture;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.map.MapDraw;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The map's terrain (MM-3; baked and mipped in MM-4; one pixel per block, Xaero-style, in MM-9). While in a world the map keeps an index of the tiles there are
 * ({@link TileIndex}) and, once it has been opened, their baked pictures and the mip pyramid over them
 * ({@link BakeCache}), kept up by the capture's change events; while it is open it also holds the data and textures of
 * the tiles it is working on ({@link PaintTiles}, {@link TileTextures}). Each frame {@link MapPainter} paints the view
 * into a picture the size of the map on screen in real pixels, which the screen then draws as one picture with its
 * icons on top. Closing the screen lets the data, textures and pictures go and keeps the baked tiles; leaving the world
 * lets everything go, and so does going to another dimension (MM-5), at once.
 * <p>
 * Unexplored land is exactly what this client has not captured, on every server and in every group: the group's fog of
 * war decides POIs, never terrain, so everything the player has seen shows. It is the palette's flat background, with
 * the hard edges of the chunks captured (MM-9: no soft fog). Hiding the map on purpose is {@code api.MapLock}'s, which
 * locks the whole map (the screen then draws nothing) or single layers, never parts of the land.
 */
public final class PaintMap {
    /** Uploads, bakes and mip copies in one frame at most, together. */
    public static final int UPLOADS_PER_FRAME = MapPainter.OPS;
    /** The GPU budget of baked tiles and mips when the settings name none (MB). */
    public static final int DEFAULT_CACHE_MB = 96;

    private static @Nullable MapPainter painter;
    private static @Nullable TileIndex index;
    private static @Nullable PaintTiles tiles;
    /** The tile folder the index and the cache were made for. */
    private static @Nullable Path world;
    private static final GpuTimer TIMER = new GpuTimer();
    /** The dimension the player was in last tick: going to another lets the baked tiles go at once. */
    private static @Nullable Object dimension;
    /** Times the cache was let go on a change of dimension (for the selftests). */
    private static int dimensionFrees;
    private static final Stats STATS = new Stats();
    /** Works out tiles' textures off the render thread, one at a time. */
    private static final ExecutorService PREP = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, MapMod.NAME + " map prep");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** Render-thread numbers for {@code /mapmod stats}: per frame, averaged over the last second, and the worst. */
    private static final class Stats {
        long frames, uploads, bakes, mips;
        long windowStart, windowFrames, windowMicros, windowOps, windowBakes, windowMaxMicros, windowMaxOps, windowMaxBakes;
        double avgMicros, avgOps, avgBakes;
        long maxOps, lastMaxMicros, lastMaxOps, lastMaxBakes;
        long windowGpuNanos, windowGpuFrames;
        double avgGpuMicros = -1;
        int tilesPainted;
    }

    private PaintMap() {
    }

    /** The palette's reload listener, the capture's change events, and letting go on leaving a world. */
    public static void register() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(MapMod.id("map_palette"),
            (ResourceManagerReloadListener) PaintPalette::load);
        Capture.addStats(PaintMap::statsLines);
        Capture.onTileChanged((tx, tz, revision) -> {
            TileIndex i = index;
            if (i != null) i.set(tx, tz, revision);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(PaintMap::unload));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Object now = client.level == null ? null : client.level.dimension();
            if (now != null && dimension != null && !now.equals(dimension)) dimensionChanged();
            dimension = now;
        });
    }

    /** The player went to another dimension: the baked tiles and mips go at once (the map covers one). */
    private static void dimensionChanged() {
        unload();
        dimensionFrees++;
    }

    /** The index and the cache for the world being played, made (or made again for another world) if needed. */
    private static MapPainter painter() {
        Path dir = Capture.dir();
        MapPainter p = painter;
        if (p != null && Objects.equals(dir, world)) return p;
        unload();
        world = dir;
        TileIndex i = new TileIndex((tx, tz) -> {
            MapPainter q = painter;
            if (q != null) q.cache.tileChanged(tx, tz);
        });
        index = i;
        painter = p = new MapPainter(i, (long) MapClient.cacheMB * 1024 * 1024, PREP);
        return p;
    }

    /** Paints the terrain under a view and draws it there (the screen has clipped to the view). */
    public static void draw(GuiGraphicsExtractor g, MapDraw.View v) {
        long t0 = System.nanoTime();
        Capture.dispatchChanges();
        Minecraft mc = Minecraft.getInstance();
        int scale = Math.max(1, mc.getWindow().getGuiScale());
        int w = v.width() * scale, h = v.height() * scale;
        if (w <= 0 || h <= 0) return;
        MapPainter p = painter();
        TileIndex i = Objects.requireNonNull(index);
        if (tiles == null) {
            PaintTiles t = new PaintTiles(i, world);
            tiles = t;
            p.provider(new MapPainter.Provider() {
                @Override
                public void update(long frame) {
                    t.update(frame);
                }

                @Override
                public PaintTiles.@Nullable Around around(int tx, int tz) {
                    return t.around(tx, tz);
                }

                @Override
                public void trim() {
                    t.trim();
                }
            });
            t.scan();
        }
        p.cache.budget((long) MapClient.cacheMB * 1024 * 1024);
        double blocksPerPixel = 1.0 / (v.scale() * scale);
        MapPainter.Frame f = new MapPainter.Frame(w, h, v.wx(v.left()), v.wz(v.top()), blocksPerPixel);
        TIMER.begin();
        MapGpu.Target picture = p.paint(f, PaintPalette.current(), PaintPalette.version(), MapClient.unlocked(MapFeature.TERRAIN));
        TIMER.end();
        g.blit(picture.view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
            v.left(), v.top(), v.left() + v.width(), v.top() + v.height(), 0f, 1f, 1f, 0f);

        long micros = (System.nanoTime() - t0) / 1000;
        Stats s = STATS;
        s.frames++;
        s.uploads += p.lastUploads;
        s.bakes += p.lastBakes;
        s.mips += p.lastMips;
        s.tilesPainted = p.lastPainted;
        s.windowFrames++;
        s.windowMicros += micros;
        s.windowOps += p.lastOps;
        s.windowBakes += p.lastBakes;
        s.windowMaxMicros = Math.max(s.windowMaxMicros, micros);
        s.windowMaxOps = Math.max(s.windowMaxOps, p.lastOps);
        s.windowMaxBakes = Math.max(s.windowMaxBakes, p.lastBakes);
        s.maxOps = Math.max(s.maxOps, p.lastOps);
        long gpu = TIMER.poll();
        if (gpu >= 0) {
            s.windowGpuNanos += gpu;
            s.windowGpuFrames++;
        }
        long now = System.currentTimeMillis();
        if (now - s.windowStart >= 1000) {
            s.avgMicros = s.windowFrames == 0 ? 0 : (double) s.windowMicros / s.windowFrames;
            s.avgOps = s.windowFrames == 0 ? 0 : (double) s.windowOps / s.windowFrames;
            s.avgBakes = s.windowFrames == 0 ? 0 : (double) s.windowBakes / s.windowFrames;
            if (s.windowGpuFrames > 0) s.avgGpuMicros = s.windowGpuNanos / 1000.0 / s.windowGpuFrames;
            else if (!TIMER.awake()) s.avgGpuMicros = -1;
            s.lastMaxMicros = s.windowMaxMicros;
            s.lastMaxOps = s.windowMaxOps;
            s.lastMaxBakes = s.windowMaxBakes;
            s.windowStart = now;
            s.windowFrames = s.windowMicros = s.windowOps = s.windowBakes = s.windowMaxMicros = s.windowMaxOps = s.windowMaxBakes = 0;
            s.windowGpuNanos = s.windowGpuFrames = 0;
        }
    }

    /** The height the map holds at a block (the top of the ground, or the floor under water), or null if unknown. */
    public static @Nullable Integer height(int x, int z) {
        PaintTiles held = tiles;
        if (held == null) return null;
        PaintTiles.Entry e = held.get(SurfaceTile.tileOfBlock(x), SurfaceTile.tileOfBlock(z));
        if (e == null || e.data == null) return null;
        int i = SurfaceTile.indexOfBlock(x, z);
        return e.data.explored(i) ? (int) e.data.height[i] : null;
    }

    /** The map closed: the tiles' data, their textures and the pictures go; the baked tiles and mips stay. */
    public static void close() {
        PaintTiles held = tiles;
        tiles = null;
        if (held != null) held.close();
        MapPainter p = painter;
        if (p != null) p.release();
    }

    /** Leaving the world (or for another): everything goes, the baked tiles too. */
    public static void unload() {
        close();
        MapPainter p = painter;
        painter = null;
        index = null;
        world = null;
        if (p != null) p.close();
    }

    /** True while the map holds tiles (it is open). */
    public static boolean open() {
        return tiles != null;
    }

    // ------------------------------------------------------------------------------------------------ for the selftests

    /** Frames painted, steps (uploads, bakes, mip copies) and uploads since the game started, and the most steps in a frame. */
    public static long frames() {
        return STATS.frames;
    }

    public static long uploads() {
        return STATS.uploads;
    }

    public static long bakes() {
        return STATS.bakes;
    }

    public static long maxUploadsPerFrame() {
        return STATS.maxOps;
    }

    /** Tiles drawn straight from their own pictures (close up) in the last frame. */
    public static int tilesPainted() {
        return STATS.tilesPainted;
    }

    /** Tiles prepared off-thread since the world was joined (for the selftests). */
    public static long prepared() {
        MapPainter p = painter;
        return p == null ? 0 : p.prepared();
    }

    /** Baked tiles and mips held, and their GPU bytes (0 when there is no cache). */
    public static int bakedTiles() {
        MapPainter p = painter;
        return p == null ? 0 : p.cache.count(0);
    }

    public static int mips() {
        MapPainter p = painter;
        return p == null ? 0 : p.cache.mips();
    }

    public static long cacheBytes() {
        MapPainter p = painter;
        return p == null ? 0 : p.cache.bytes();
    }

    /**
     * Whether this client captured a block, as the map knows it from the tiles it last prepared: kept while in the
     * world, unlike the tiles' data, which the open map lets go once it has baked them.
     */
    public static boolean captured(int x, int z) {
        MapPainter p = painter;
        return p != null && p.coverage.captured(x, z);
    }

    /** Times the cache was let go on a change of dimension. */
    public static int dimensionFrees() {
        return dimensionFrees;
    }

    /** Frames drawn mid-blend between two levels. */
    public static long fadeFrames() {
        MapPainter p = painter;
        return p == null ? 0 : p.fadeFrames;
    }

    /** True while the last frame blended two levels. */
    public static boolean fading() {
        MapPainter p = painter;
        return p != null && p.lastFading;
    }

    /** True if the last frame showed the last picture again. */
    public static boolean reused() {
        MapPainter p = painter;
        return p != null && p.lastReused;
    }

    /** True if the last frame found nothing left to do for what is on screen. */
    public static boolean idle() {
        MapPainter p = painter;
        return p != null && p.idle();
    }

    /** The mip level and whether the last frame painted live, and its quads. */
    public static String lastFrame() {
        MapPainter p = painter;
        if (p == null) return "none";
        return (p.lastLive ? "live" : "level " + p.lastLevel) + (p.lastReused ? " (picture shown again)" : "") + ", " + p.lastQuads + " quads, " + p.lastPainted + " painted, "
            + p.lastPasses() + " passes, " + p.lastDraws() + " draws" + (p.idle() ? "" : "; busy: " + p.busy());
    }

    /** Disk-load events the open map's tiles fired (tiles read from disk). */
    public static int diskEvents() {
        PaintTiles t = tiles;
        return t == null ? 0 : t.diskEvents;
    }

    /** Shows an unchanged picture again instead of painting it (on by default; the selftests time a redraw without). */
    public static void reusePictures(boolean on) {
        MapPainter.reuse = on;
    }

    /** The GPU timer (the selftests switch it off to see the fallback). */
    public static GpuTimer timer() {
        return TIMER;
    }

    /**
     * The tile picture path end to end (for the selftests, MM-9): a tile with a known pattern of colours, heights and
     * explored blocks is prepared and uploaded into a real texture, and texels read back: an unexplored block is
     * transparent, an explored one opaque in its colour where it is level with its neighbours, lighter one block above
     * them and darker one below. Null if all were, else what was wrong. Render thread only.
     */
    public static @Nullable String pictureCheck() {
        SurfaceTile t = new SurfaceTile(0, 0);
        for (int i = 0; i < SurfaceTile.AREA; i++) {
            int sx = i % SurfaceTile.SIDE, sz = i / SurfaceTile.SIDE;
            t.height[i] = 64;
            t.colour[i] = 0x606060 + (sx % 32) * 0x10101;
            if ((sx / 64 + sz / 64) % 5 != 4) t.explored[i >>> 6] |= 1L << i;
        }
        // a block one higher than the land round it at 100, 100, one lower at 200, 150
        t.height[SurfaceTile.index(100, 100)] = 65;
        t.height[SurfaceTile.index(200, 150)] = 63;
        SurfaceTile[][] around = new SurfaceTile[3][3];
        around[1][1] = t;
        PaintPalette pal = PaintPalette.DEFAULT;
        TilePrep.Result r = TilePrep.prepare(around, pal);
        try (TileTextures tex = TileTextures.create(0, 0)) {
            tex.upload(r);
            for (int i = 0; i < SurfaceTile.AREA; i += 97) {
                int sx = i % SurfaceTile.SIDE, sz = i / SurfaceTile.SIDE;
                if (sx == 0 || sz == 0 || sx >= 99 && sx <= 101 || sx >= 199 && sx <= 201) continue;
                int got = tex.pixel(sx, sz), want = t.explored(i) ? 0xFF000000 | t.colour[i] : 0;
                // a block whose neighbour to the north or west is unexplored is not shaded against it
                if (got != want) return "block " + sx + "," + sz + ": texel #" + Integer.toHexString(got) + ", expected #" + Integer.toHexString(want);
            }
            int up = tex.pixel(100, 100) & 0xFFFFFF, down = tex.pixel(200, 150) & 0xFFFFFF;
            int c1 = t.colour[SurfaceTile.index(100, 100)], c2 = t.colour[SurfaceTile.index(200, 150)];
            if (up != pal.shade(c1, 2) || (up & 0xFF) <= (c1 & 0xFF)) return "a block one above its neighbours is #" + Integer.toHexString(up) + ", from #" + Integer.toHexString(c1);
            if (down != pal.shade(c2, -2) || (down & 0xFF) >= (c2 & 0xFF)) return "a block one below its neighbours is #" + Integer.toHexString(down) + ", from #" + Integer.toHexString(c2);
            // the block south of the raised one is lower than its north neighbour: darker
            int south = tex.pixel(100, 101) & 0xFFFFFF, cs = t.colour[SurfaceTile.index(100, 101)];
            if (south != pal.shade(cs, -1)) return "the block south of a raised one is #" + Integer.toHexString(south) + ", from #" + Integer.toHexString(cs);
        }
        return null;
    }

    /**
     * Bake time per tile (MM-8): the prep off the render thread and the bake pass on it, averaged since the world was
     * joined.
     */
    public static String bakeTimeLine() {
        MapPainter p = painter;
        if (p == null) return "  bake time per tile: no painter";
        double[] t = p.bakeTimes();
        return String.format(Locale.ROOT, "  bake time per tile: prep %.1f ms avg off-thread, bake pass %.0f us avg (%.0f us max) on the render thread; %d bakes",
            t[0], t[1], p.bakeMaxNanos / 1e3, p.cache.leafBakes);
    }

    /**
     * Tile memory and disk size (MM-9): bytes a tile's data takes in memory, and the tile files' count and total size in
     * this world's folder.
     */
    public static String tileSizeLine() {
        long files = 0, bytes = 0;
        Path dir = Capture.dir();
        if (dir != null) {
            try (var list = java.nio.file.Files.list(dir)) {
                for (Path f : (Iterable<Path>) list::iterator) {
                    if (!f.getFileName().toString().endsWith(".mmt")) continue;
                    files++;
                    bytes += java.nio.file.Files.size(f);
                }
            } catch (java.io.IOException | RuntimeException e) {
                // no folder yet
            }
        }
        return String.format(Locale.ROOT, "  tiles: %.2f MB each in memory; %d files, %.1f KB avg, %.2f MB on disk",
            SurfaceTile.memoryBytes() / 1048576.0, files, files == 0 ? 0 : bytes / 1024.0 / files, bytes / 1048576.0);
    }

    /** Compiles the shader now (for the selftests): null if it compiled, else what went wrong. */
    public static @Nullable String compileCheck() {
        return MapGpu.compileCheck();
    }

    private static List<String> statsLines() {
        Stats s = STATS;
        MapPainter p = painter;
        PaintTiles held = tiles;
        List<String> lines = new ArrayList<>();
        lines.add(MapMod.NAME + " map" + (held == null ? " (closed)" : " (open)"));
        boolean was = TIMER.awake();
        TIMER.wake();
        String gpu = !TIMER.supported() ? "n/a" : !was || s.avgGpuMicros < 0 ? "measuring for " + GpuTimer.AWAKE_MS / 1000 + " s, ask again"
            : String.format(Locale.ROOT, "%.0f us/frame avg", s.avgGpuMicros);
        lines.add(String.format(Locale.ROOT, "  render %.0f us/frame avg, %d us max (last second); GPU %s", s.avgMicros, s.lastMaxMicros, gpu));
        lines.add(String.format(Locale.ROOT, "  steps %.2f/frame avg, %d max (budget %d); bakes %.2f/frame avg, %d max; %d uploads, %d bakes, %d mip copies in %d frames",
            s.avgOps, s.lastMaxOps, UPLOADS_PER_FRAME, s.avgBakes, s.lastMaxBakes, s.uploads, s.bakes, s.mips, s.frames));
        if (p != null) {
            BakeCache c = p.cache;
            lines.add(String.format(Locale.ROOT, "  baked tiles %d, mips %d, cache %.1f / %d MB, evicted %d; tiles known %d; children made from below, not paper %d",
                c.count(0), c.mips(), c.bytes() / 1048576.0, c.budget() / 1048576, c.evictions, p.index.size(), c.emptySplitsAvoided));
            lines.add("  last frame: " + lastFrame() + ", textures " + p.textures() + "; pictures painted " + p.painted + ", shown again " + p.reused);
            long n = p.prepared();
            lines.add(String.format(Locale.ROOT, "  prepared off-thread %d tiles, %.1f ms avg, %.1f ms max; waiting %d",
                n, n == 0 ? 0 : p.prepNanos.get() / 1e6 / n, p.prepMaxNanos.get() / 1e6, p.waiting()));
            lines.add(bakeTimeLine());
            lines.add(String.format(Locale.ROOT, "  %d mips made again from their parent; %d frames blended; captured land known for %d tiles",
                c.splits, p.fadeFrames, p.coverage.tiles()));
        }
        lines.add(tileSizeLine());
        if (held != null) {
            lines.add(String.format(Locale.ROOT, "  tiles held %d, loading %d (read %d, from capture %d)",
                held.size(), held.loading(), held.read.get(), held.fromCapture.get()));
        }
        return lines;
    }
}
