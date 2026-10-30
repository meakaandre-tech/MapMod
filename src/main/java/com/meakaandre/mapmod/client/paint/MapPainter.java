package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceTile;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Paints one frame of the map (MM-4) into a picture the size of the map on screen, in real pixels.
 * <p>
 * Below one pixel a block every visible cell of the mip level whose texels are about a pixel is drawn as one quad from
 * the {@link BakeCache}; a cell not made yet borrows from the nearest coarser level, else from the level below, else is
 * paper. Above one pixel a block the visible tiles are painted live with the full shader (as in MM-3), each from its
 * {@link TileTextures}, and a tile without them yet borrows its baked texture. Either way the picture then gets the
 * grain and the vignette in one pass at screen resolution.
 * <p>
 * The work that keeps all that up to date is found each frame (visible cells first, nearest the middle first, depth
 * first under each: see {@link BakeCache#need}) and done at most {@link #OPS} steps a frame, a step being an upload of a
 * tile's prepared textures, a bake of a tile, or a copy into a mip. A tile's textures are worked out on the prep thread
 * first ({@link TilePrep}) from its data and its neighbours' ({@link Provider}). Render thread only.
 */
final class MapPainter implements AutoCloseable {
    /** Uploads, bakes and mip copies in one frame at most, together. */
    static final int OPS = 2;
    /** Tiles being prepared off-thread at once, at most. */
    private static final int PREP_MAX = 2;
    /** Tiles' data asked for in one frame at most (they arrive over the next frames). */
    private static final int ASKS = 12;
    /** Cells looked at in one frame at most while finding work. */
    private static final int VISITS = 20000;

    /** Where tiles' data comes from: around a tile, once it and its neighbours are in (else asked for, and null). */
    interface Provider {
        void update(long frame);

        PaintTiles.@Nullable Around around(int tx, int tz);

        void trim();
    }

    /** The view to paint: the picture's size in pixels, the world position of its top-left corner, blocks a pixel. */
    record Frame(int width, int height, double wx0, double wz0, double blocksPerPixel) {
    }

    private record Prepared(int generation, long key, TilePrep.Result result) {
    }

    final TileIndex index;
    final BakeCache cache;
    private final MapGpu gpu = new MapGpu();
    private @Nullable Provider data;
    private final @Nullable Executor prep;
    private final Long2ObjectOpenHashMap<TileTextures> tex = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap texUsed = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap preparing = new Long2LongOpenHashMap();
    private final ConcurrentHashMap<Long, Prepared> prepared = new ConcurrentHashMap<>();
    private volatile int generation;
    private MapGpu.@Nullable Target content, picture;
    private long frame;
    private int lookVersion = Integer.MIN_VALUE;
    private boolean lookContours;

    /** Counted since the painter was made. */
    long uploads, frames;
    final AtomicLong prepNanos = new AtomicLong(), prepMaxNanos = new AtomicLong();
    private final AtomicLong prepDone = new AtomicLong();
    /** The last frame's numbers. */
    int lastOps, lastUploads, lastBakes, lastMips, lastQuads, lastPainted, lastShown, lastLevel, lastWork;
    boolean lastLive, lastContours;

    /**
     * @param prep where tiles are prepared; null prepares them at once on this thread (the selftests)
     */
    MapPainter(TileIndex index, long budget, @Nullable Executor prep) {
        this.index = index;
        this.cache = new BakeCache(index, budget);
        this.prep = prep;
    }

    void provider(@Nullable Provider p) {
        data = p;
    }

    /** Tiles prepared since the painter was made. */
    long prepared() {
        return prepDone.get();
    }

    int waiting() {
        return prepared.size();
    }

    int textures() {
        return tex.size();
    }

    /** True if the last frame found nothing to do: everything visible is up to date. */
    boolean idle() {
        return lastWork == 0 && preparing.isEmpty();
    }

    /** The mip level whose texels are about a pixel at this many blocks a pixel. */
    static int level(double blocksPerPixel) {
        if (blocksPerPixel <= 1) return 0;
        return Math.clamp(Math.round(Math.log(blocksPerPixel) / Math.log(2)), 0, BakeCache.LEVELS - 1);
    }

    // ------------------------------------------------------------------------------------------------ a frame

    /** Paints the frame and returns the picture to draw (v from 1 at the top to 0 at the bottom). */
    MapGpu.Target paint(Frame f, PaintPalette pal, int palVersion, boolean contours, boolean terrain) {
        frame++;
        frames++;
        cache.frame(frame);
        if (data != null) data.update(frame);
        if (palVersion != lookVersion || contours != lookContours) {
            // what the baked tiles look like changed: all of them go
            cache.clear();
            closeTextures();
            lookVersion = palVersion;
            lookContours = contours;
        }
        boolean live = f.blocksPerPixel() < 1;
        int level = live ? 0 : level(f.blocksPerPixel());
        lastLive = live;
        lastLevel = level;
        lastContours = contours;
        gpu.beginFrame(96);

        int cs = BakeCache.SIDE << level;
        double wx1 = f.wx0() + f.width() * f.blocksPerPixel(), wz1 = f.wz0() + f.height() * f.blocksPerPixel();
        int cx0 = Math.floorDiv((int) Math.floor(f.wx0()), cs), cx1 = Math.floorDiv((int) Math.floor(wx1), cs);
        int cz0 = Math.floorDiv((int) Math.floor(f.wz0()), cs), cz1 = Math.floorDiv((int) Math.floor(wz1), cs);
        double mx = (f.wx0() + wx1) / 2 / cs - 0.5, mz = (f.wz0() + wz1) / 2 / cs - 0.5;
        LongArrayList cells = new LongArrayList();
        for (int z = cz0; z <= cz1; z++) for (int x = cx0; x <= cx1; x++) if (index.occupied(level, x, z)) cells.add(TileIndex.key(x, z));
        cells.sort((long a, long b) -> Double.compare(dist(a, mx, mz), dist(b, mx, mz)));
        lastShown = (cx1 - cx0 + 1) * (cz1 - cz0 + 1);

        int ops = 0;
        long bakes0 = cache.leafBakes, mips0 = cache.mipBuilds, up0 = uploads;
        if (terrain) {
            FrameJobs jobs = new FrameJobs();
            if (live) for (int i = 0; i < cells.size(); i++) jobs.live(x(cells.getLong(i)), z(cells.getLong(i)));
            for (int i = 0; i < cells.size(); i++) cache.need(level, x(cells.getLong(i)), z(cells.getLong(i)), jobs);
            lastWork = jobs.work.size() + jobs.asked;
            for (Job j : jobs.work) {
                if (ops >= OPS) break;
                ops += run(j, pal, palVersion, contours, OPS - ops);
            }
        } else {
            lastWork = 0;
        }
        lastOps = ops;
        lastUploads = (int) (uploads - up0);
        lastBakes = (int) (cache.leafBakes - bakes0);
        lastMips = (int) (cache.mipBuilds - mips0);

        content = sized(content, "map picture", f.width(), f.height());
        picture = sized(picture, "map finished", f.width(), f.height());
        List<MapGpu.TexQuad> quads = new ArrayList<>();
        List<MapGpu.PaintQuad> paints = new ArrayList<>();
        if (terrain) {
            for (int i = 0; i < cells.size(); i++) {
                int x = x(cells.getLong(i)), z = z(cells.getLong(i));
                TileTextures t = live ? tex.get(TileIndex.key(x, z)) : null;
                if (t != null) {
                    texUsed.put(TileIndex.key(x, z), frame);
                    paints.add(paintQuad(t, x, z, f));
                } else {
                    drawCell(level, x, z, f, quads);
                }
            }
        }
        lastQuads = quads.size();
        lastPainted = paints.size();
        gpu.quads(content, MapGpu.clear(pal), quads);
        gpu.paint(content, Optional.empty(), paints, pal, palVersion, contours);
        gpu.finish(content, picture, pal);

        cache.evict();
        if (data != null) data.trim();
        trimTextures();
        return picture;
    }

    private static double dist(long k, double mx, double mz) {
        double dx = x(k) - mx, dz = z(k) - mz;
        return dx * dx + dz * dz;
    }

    private static int x(long k) {
        return (int) (k >> 32);
    }

    private static int z(long k) {
        return (int) k;
    }

    private static MapGpu.Target sized(MapGpu.@Nullable Target t, String name, int w, int h) {
        if (t != null && t.width == w && t.height == h) return t;
        if (t != null) t.close();
        return new MapGpu.Target(name, w, h);
    }

    /** A tile painted live over its place in the picture. */
    private static MapGpu.PaintQuad paintQuad(TileTextures t, int tx, int tz, Frame f) {
        double bpp = f.blocksPerPixel();
        double x0 = (tx * (double) BakeCache.SIDE - f.wx0()) / bpp, x1 = x0 + BakeCache.SIDE / bpp;
        double y0 = (tz * (double) BakeCache.SIDE - f.wz0()) / bpp, y1 = y0 + BakeCache.SIDE / bpp;
        return tileQuad(t, tx, tz, (float) x0, (float) y0, (float) x1, (float) y1);
    }

    /**
     * A tile's paint quad over the given rectangle. Sample i stands for its cell's middle (block i * 4 + 2), so the
     * tile's edge is half a sample before the first and after the last; texel i + APRON holds sample i. Texel (0, 0)
     * is world sample (tx * 128 - APRON, ...), wrapped to the noise's period so neighbouring tiles agree.
     */
    private static MapGpu.PaintQuad tileQuad(TileTextures t, int tx, int tz, float x0, float y0, float x1, float y1) {
        float u0 = TileTextures.APRON - 0.5f, u1 = u0 + SurfaceTile.SIDE;
        int ox = Math.floorMod(tx * SurfaceTile.SIDE - TileTextures.APRON, MapGpu.NOISE_PERIOD);
        int oz = Math.floorMod(tz * SurfaceTile.SIDE - TileTextures.APRON, MapGpu.NOISE_PERIOD);
        return new MapGpu.PaintQuad(t, x0, y0, x1, y1, u0, u0, u1, u1, ox, oz);
    }

    /** A cell from the cache, else from the nearest coarser level, else from the level below, else paper. */
    private void drawCell(int level, int x, int z, Frame f, List<MapGpu.TexQuad> out) {
        double bpp = f.blocksPerPixel(), size = (double) (BakeCache.SIDE << level) / bpp;
        float x0 = (float) ((x * (double) (BakeCache.SIDE << level) - f.wx0()) / bpp), y0 = (float) ((z * (double) (BakeCache.SIDE << level) - f.wz0()) / bpp);
        float x1 = (float) (x0 + size), y1 = (float) (y0 + size);
        BakeCache.Baked b = cache.get(level, x, z);
        if (b != null) {
            cache.touch(b);
            out.add(new MapGpu.TexQuad(b.target.view, x0, y0, x1, y1, 0, 1, 1, 0));
            return;
        }
        for (int j = level + 1; j < BakeCache.LEVELS; j++) {
            int s = j - level, n = 1 << s;
            BakeCache.Baked a = cache.get(j, x >> s, z >> s);
            if (a == null) continue;
            cache.touch(a);
            float ox = x & (n - 1), oz = z & (n - 1);
            out.add(new MapGpu.TexQuad(a.target.view, x0, y0, x1, y1, ox / n, 1 - oz / n, (ox + 1) / n, 1 - (oz + 1) / n));
            return;
        }
        if (level == 0) return;
        float h = (float) (size / 2);
        for (int q = 0; q < 4; q++) {
            BakeCache.Baked c = cache.get(level - 1, x * 2 + (q & 1), z * 2 + (q >> 1));
            if (c == null) continue;
            cache.touch(c);
            float qx = x0 + (q & 1) * h, qy = y0 + (q >> 1) * h;
            out.add(new MapGpu.TexQuad(c.target.view, qx, qy, qx + h, qy + h, 0, 1, 1, 0));
        }
    }

    // ------------------------------------------------------------------------------------------------ the work

    private enum Kind { UPLOAD, BAKE, MIP }

    private record Job(Kind kind, int level, int x, int z, boolean bake) {
    }

    /** The work found in a frame: GPU steps in the order found, and how many tiles' data was asked for. */
    private final class FrameJobs extends BakeCache.Jobs {
        final List<Job> work = new ArrayList<>();
        final LongOpenHashSet seen = new LongOpenHashSet();
        int asked;

        @Override
        boolean full() {
            return visits > VISITS || work.size() >= OPS * 2 && asked >= ASKS;
        }

        /** A tile shown live wants its textures (not a bake: {@link #need} asks for that). */
        void live(int tx, int tz) {
            tile(tx, tz, false);
        }

        @Override
        void leaf(int tx, int tz) {
            tile(tx, tz, true);
        }

        private void tile(int tx, int tz, boolean bake) {
            long k = TileIndex.key(tx, tz);
            long want = index.key3x3(tx, tz);
            TileTextures t = tex.get(k);
            if (t != null && t.key == want) {
                texUsed.put(k, frame);
                if (bake && seen.add(k)) work.add(new Job(Kind.BAKE, 0, tx, tz, true));
                return;
            }
            Prepared p = prepared.get(k);
            if (p != null && p.generation == generation && p.key == want) {
                if (seen.add(k ^ 0x5DEECE66DL)) work.add(new Job(Kind.UPLOAD, 0, tx, tz, bake));
                return;
            }
            if (preparing.containsKey(k) && preparing.get(k) == want) return;
            if (preparing.size() >= PREP_MAX || asked >= ASKS || data == null) {
                asked++;
                return;
            }
            asked++;
            PaintTiles.Around a = data.around(tx, tz);
            if (a == null || a.key() != want) return;
            submit(k, want, a.tiles());
            p = prepared.get(k);
            // prepared at once (the selftests): it can go up this frame
            if (p != null && p.key == want && seen.add(k ^ 0x5DEECE66DL)) work.add(new Job(Kind.UPLOAD, 0, tx, tz, bake));
        }

        @Override
        void mip(int level, int x, int z) {
            if (seen.add(TileIndex.key(x, z) * 31 + level)) work.add(new Job(Kind.MIP, level, x, z, false));
        }
    }

    private void submit(long k, long key, @Nullable SurfaceTile[][] around) {
        preparing.put(k, key);
        int gen = generation;
        Runnable job = () -> {
            if (gen != generation) return;
            long t0 = System.nanoTime();
            TilePrep.Result r = TilePrep.prepare(around);
            long ns = System.nanoTime() - t0;
            prepDone.incrementAndGet();
            prepNanos.addAndGet(ns);
            prepMaxNanos.accumulateAndGet(ns, Math::max);
            if (gen == generation) prepared.put(k, new Prepared(gen, key, r));
        };
        if (prep == null) job.run();
        else prep.execute(job);
    }

    /** Runs one job; returns the steps it took. */
    private int run(Job j, PaintPalette pal, int palVersion, boolean contours, int left) {
        long k = TileIndex.key(j.x, j.z);
        switch (j.kind) {
            case UPLOAD -> {
                Prepared p = prepared.remove(k);
                preparing.remove(k);
                if (p == null || p.key != index.key3x3(j.x, j.z)) return 0;
                TileTextures t = tex.get(k);
                if (t == null) tex.put(k, t = TileTextures.create(j.x, j.z));
                t.upload(p.result);
                t.key = p.key;
                texUsed.put(k, frame);
                uploads++;
                if (j.bake && left > 1) return 1 + bake(j.x, j.z, t, pal, palVersion, contours);
                return 1;
            }
            case BAKE -> {
                TileTextures t = tex.get(k);
                if (t == null || t.key != index.key3x3(j.x, j.z)) return 0;
                return bake(j.x, j.z, t, pal, palVersion, contours);
            }
            case MIP -> {
                return cache.buildMip(gpu, j.level, j.x, j.z, pal) ? 1 : 0;
            }
        }
        return 0;
    }

    private int bake(int tx, int tz, TileTextures t, PaintPalette pal, int palVersion, boolean contours) {
        BakeCache.Baked b = cache.leaf(tx, tz);
        int s = BakeCache.SIDE;
        gpu.paint(b.target, MapGpu.clear(pal), List.of(tileQuad(t, tx, tz, 0, 0, s, s)), pal, palVersion, contours);
        cache.baked(b, t.key);
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ letting go

    /** Textures not wanted this frame go: a tile shown live or about to be baked is wanted again every frame. */
    private void trimTextures() {
        if (tex.isEmpty()) return;
        LongArrayList gone = new LongArrayList();
        for (var e : tex.long2ObjectEntrySet()) if (texUsed.get(e.getLongKey()) < frame) gone.add(e.getLongKey());
        for (long k : gone) {
            TileTextures t = tex.remove(k);
            texUsed.remove(k);
            if (t != null) t.close();
        }
    }

    private void closeTextures() {
        for (TileTextures t : tex.values()) t.close();
        tex.clear();
        texUsed.clear();
        generation++;
        preparing.clear();
        prepared.clear();
    }

    /** The screen closed: the pictures, the tiles' textures and their data go; the baked tiles stay. */
    void release() {
        closeTextures();
        if (content != null) content.close();
        if (picture != null) picture.close();
        content = picture = null;
        gpu.close();
        data = null;
    }

    /** True while the GPU holds a picture (the map is open and painted). */
    boolean painting() {
        return picture != null;
    }

    @Override
    public void close() {
        release();
        cache.close();
    }

    /** For the selftests: the cache's cell, or null. */
    BakeCache.@Nullable Baked cell(int level, int x, int z) {
        return cache.get(level, x, z);
    }

    /** For the selftests: the number of passes and draws of the last frame. */
    int lastPasses() {
        return gpu.passes;
    }

    int lastDraws() {
        return gpu.draws;
    }

    static String compileCheck() {
        String s = MapGpu.compileCheck();
        return s == null ? "" : s;
    }
}
