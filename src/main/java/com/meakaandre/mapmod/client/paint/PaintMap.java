package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.api.MapLock;
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
 * The painted map (MM-3, baked and mipped in MM-4). While in a world the map keeps an index of the tiles there are
 * ({@link TileIndex}) and, once it has been opened, their baked pictures and the mip pyramid over them
 * ({@link BakeCache}), kept up by the capture's change events; while it is open it also holds the data and textures of
 * the tiles it is working on ({@link PaintTiles}, {@link TileTextures}). Each frame {@link MapPainter} paints the view
 * into a picture the size of the map on screen in real pixels, which the screen then draws as one picture with its
 * icons on top. Closing the screen lets the data, textures and pictures go and keeps the baked tiles; leaving the world
 * lets everything go, and so does going to another dimension (MM-5), at once.
 * <p>
 * The fog over unexplored land (MM-5) is exactly what this client has captured, on every server and in every group: the
 * group's fog of war decides POIs, never terrain, so nothing the player has seen is fogged (but for the soft rim of the
 * fog's edge). The painter keeps it as masks ({@link FogMasks}), made from the captured samples and what may show
 * ({@link #mayShow}: everything). Hiding the map on purpose is {@code api.MapLock}'s, which locks the whole map (the
 * screen then paints nothing) or single layers, never parts of the land.
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
        boolean contours;
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

    /** The player went to another dimension: the baked tiles, mips and fog masks go at once (the map covers one). */
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
        painter = p = new MapPainter(i, (long) MapClient.cacheMB * 1024 * 1024, PREP, PaintMap::mayShow);
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
        // contour lines show while switched on, unless a mod locks them (MM-6)
        boolean contours = MapClient.contours && !MapLock.layerLocked(MapLock.CONTOURS);
        MapGpu.Target picture = p.paint(f, PaintPalette.current(), PaintPalette.version(), contours, MapClient.unlocked(MapFeature.TERRAIN));
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
        s.contours = p.lastContours;
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

    /** Tiles painted live in the last frame. */
    public static int tilesPainted() {
        return STATS.tilesPainted;
    }

    /** Whether the last frame painted with contour lines on (for the selftests). */
    public static boolean contoursPainted() {
        return STATS.contours;
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

    /** Every chunk of a region may show. */
    private static final long[] ALL_EXPLORED = new long[com.meakaandre.mapmod.map.MapGrid.FOG_LONGS];

    static {
        java.util.Arrays.fill(ALL_EXPLORED, -1L);
    }

    /**
     * What of a region may show over what was captured, by chunk: every chunk, so the fog is exactly what this client
     * captured (the group's fog or its own capture, which comes to the same).
     */
    static long @Nullable [] mayShow(long region) {
        return ALL_EXPLORED;
    }

    /**
     * For the selftests: the fog over a captured block, worked out the way the open map's mask is (0 fogged, 1 shown,
     * before the clouds roughen the edge), from the coverage the map holds. -1 if the map is not open or holds nothing there.
     */
    public static float fogAt(int x, int z) {
        MapPainter p = painter;
        return p == null ? -1 : p.masks.valueAt(x, z, PaintPalette.current().fogEdge());
    }

    /**
     * Whether this client captured a block, as the fog knows it: kept while in the world, unlike the tiles' data, which
     * the open map lets go once it has baked them.
     */
    public static boolean captured(int x, int z) {
        MapPainter p = painter;
        return p != null && p.masks.captured(x, z);
    }

    /** For the selftests: whether the fog the masks are made from counts a block's chunk as explored. */
    public static boolean fogSourceExplores(int x, int z) {
        long[] bits = mayShow(com.meakaandre.mapmod.map.MapGrid.regionOfBlock(x, z));
        return bits != null && com.meakaandre.mapmod.map.MapGrid.get(bits, com.meakaandre.mapmod.map.MapGrid.fogBit(x >> 4, z >> 4));
    }

    /** For the selftests: whether a captured block shows through the fog (its mask past where the fog starts to lift). */
    public static boolean fogShows(int x, int z) {
        return fogAt(x, z) >= 0.55f;
    }

    /** Times the cache was let go on a change of dimension. */
    public static int dimensionFrees() {
        return dimensionFrees;
    }

    /** Fog masks made, and frames painted mid-blend between two levels. */
    public static long fogBuilds() {
        MapPainter p = painter;
        return p == null ? 0 : p.masks.builds;
    }

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
     * The canopy path end to end (for the selftests): a tile with a known canopy pattern is prepared and uploaded into
     * real textures, and the surface texture's alpha read back. Null if every texel carried its sample's canopy, else
     * what was wrong. Render thread only.
     */
    public static @Nullable String canopyCheck() {
        SurfaceTile t = new SurfaceTile(0, 0);
        for (int i = 0; i < SurfaceTile.AREA; i++) {
            t.canopy[i] = (byte) (i * 7 % 256);
            t.explored[i >>> 6] |= 1L << i;
        }
        SurfaceTile[][] around = new SurfaceTile[3][3];
        around[1][1] = t;
        TilePrep.Result r = TilePrep.prepare(around);
        try (TileTextures tex = TileTextures.create(0, 0)) {
            tex.upload(r);
            for (int i = 0; i < SurfaceTile.AREA; i += 97) {
                int sx = i % SurfaceTile.SIDE, sz = i / SurfaceTile.SIDE;
                int got = tex.pixel(TilePrep.SURFACE, sx + TilePrep.APRON, sz + TilePrep.APRON) >>> 24;
                if (got != (t.canopy[i] & 0xFF)) return "sample " + sx + "," + sz + ": canopy " + (t.canopy[i] & 0xFF) + ", texel alpha " + got;
            }
            if (tex.pixel(TilePrep.SURFACE, 0, 0) >>> 24 != 0) return "the apron of a tile with no neighbours has canopy";
        }
        return null;
    }

    /** Compiles both shaders now (for the selftests): null if they compiled, else what went wrong. */
    public static @Nullable String compileCheck() {
        return MapGpu.compileCheck();
    }

    private static List<String> statsLines() {
        Stats s = STATS;
        MapPainter p = painter;
        PaintTiles held = tiles;
        List<String> lines = new ArrayList<>();
        lines.add(MapMod.NAME + " painted map" + (held == null ? " (closed)" : " (open)"));
        boolean was = TIMER.awake();
        TIMER.wake();
        String gpu = !TIMER.supported() ? "n/a" : !was || s.avgGpuMicros < 0 ? "measuring for " + GpuTimer.AWAKE_MS / 1000 + " s, ask again"
            : String.format(Locale.ROOT, "%.0f us/frame avg", s.avgGpuMicros);
        lines.add(String.format(Locale.ROOT, "  render %.0f us/frame avg, %d us max (last second); GPU %s", s.avgMicros, s.lastMaxMicros, gpu));
        lines.add(String.format(Locale.ROOT, "  steps %.2f/frame avg, %d max (budget %d); bakes %.2f/frame avg, %d max; %d uploads, %d bakes, %d mip copies in %d frames",
            s.avgOps, s.lastMaxOps, UPLOADS_PER_FRAME, s.avgBakes, s.lastMaxBakes, s.uploads, s.bakes, s.mips, s.frames));
        if (p != null) {
            BakeCache c = p.cache;
            lines.add(String.format(Locale.ROOT, "  baked tiles %d, mips %d, cache %.1f / %d MB, evicted %d; tiles known %d",
                c.count(0), c.mips(), c.bytes() / 1048576.0, c.budget() / 1048576, c.evictions, p.index.size()));
            lines.add("  last frame: " + lastFrame() + ", textures " + p.textures() + "; pictures painted " + p.painted + ", shown again " + p.reused);
            long n = p.prepared();
            lines.add(String.format(Locale.ROOT, "  prepared off-thread %d tiles, %.1f ms avg, %.1f ms max; waiting %d",
                n, n == 0 ? 0 : p.prepNanos.get() / 1e6 / n, p.prepMaxNanos.get() / 1e6, p.waiting()));
            FogMasks m = p.masks;
            lines.add(String.format(Locale.ROOT, "  fog masks %d held, %d made off-thread (%.1f ms avg, %.1f ms max), %d uploaded; %d mips made again from their parent; %d frames blended",
                m.held(), m.builds, m.builds == 0 ? 0 : m.buildNanos.get() / 1e6 / m.builds, m.buildMaxNanos.get() / 1e6, m.uploads, c.splits, p.fadeFrames));
        }
        if (held != null) {
            lines.add(String.format(Locale.ROOT, "  tiles held %d, loading %d (read %d, from capture %d); contours %s",
                held.size(), held.loading(), held.read.get(), held.fromCapture.get(), MapClient.contours ? "on" : "off"));
        }
        return lines;
    }
}
