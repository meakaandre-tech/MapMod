package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceTile;
import com.mojang.renderpearl.api.textures.GpuTextureView;
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
 * Draws one frame of the map (MM-4) into a picture the size of the map on screen, in real pixels.
 * <p>
 * Below one pixel a block every visible cell of the mip level whose texels are about a pixel is drawn as one quad from
 * the {@link BakeCache}, its pixels averaged over their footprint (MM-9: no shimmer); a cell not made yet borrows from
 * the nearest coarser level, else from the level below, else shows the background. Above one pixel a block the visible
 * tiles are drawn straight from their {@link TileTextures} (or their baked texture until those are up), nearest, so every
 * block is a crisp square. Every pixel is laid over the flat background as it is written, so unexplored land (nothing in
 * the textures) is that background, with the hard edges of the chunks captured. When the level shown changes, the old
 * level is blended out over {@link #FADE_NANOS} (both drawn from the cache in one pass), and the frame after that settles
 * like any other.
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
    /** How long a change of the level shown blends the old one out. */
    static final long FADE_NANOS = 150_000_000L;
    /** Levels apart a blend is made over at most (a bigger jump just changes). */
    private static final int FADE_LEVELS = 2;

    /** Where tiles' data comes from: around a tile, once it and its neighbours are in (else asked for, and null). */
    interface Provider {
        void update(long frame);

        PaintTiles.@Nullable Around around(int tx, int tz);

        void trim();
    }

    /** The view to paint: the picture's size in pixels, the world position of its top-left corner, blocks a pixel. */
    record Frame(int width, int height, double wx0, double wz0, double blocksPerPixel) {
    }

    /** A tile's picture worked out off-thread, and the explored samples and revisions of it and its neighbours. */
    private record Prepared(int generation, long key, TilePrep.Result result, long[][] explored, long[] revisions) {
    }

    /** What a painted picture showed: if a frame would show the same, the picture is shown again. */
    private record Shown(Frame frame, int palVersion, boolean terrain, long evictions) {
    }

    /** A part of the picture: a world rectangle (blocks) shown from a texture at those coordinates (v up). */
    private record Piece(GpuTextureView src, double wx0, double wz0, double wx1, double wz1, float u0, float v0, float u1, float v1) {
        float u(double wx) {
            return (float) (u0 + (u1 - u0) * (wx - wx0) / (wx1 - wx0));
        }

        float v(double wz) {
            return (float) (v0 + (v1 - v0) * (wz - wz0) / (wz1 - wz0));
        }
    }

    private @Nullable Shown lastShownKey;
    /** True if the last painted picture was painted with nothing left to do. */
    private boolean reusable;
    /** Frames painted, and frames that showed the last picture again. */
    long painted, reused;
    boolean lastReused;
    /** Whether an unchanged picture is shown again (the selftests switch it off to time a redraw). */
    static boolean reuse = true;

    final TileIndex index;
    final BakeCache cache;
    final Coverage coverage = new Coverage();
    private final MapGpu gpu = new MapGpu();
    private @Nullable Provider data;
    private final @Nullable Executor prep;
    private final Long2ObjectOpenHashMap<TileTextures> tex = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap texUsed = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap preparing = new Long2LongOpenHashMap();
    private final ConcurrentHashMap<Long, Prepared> prepared = new ConcurrentHashMap<>();
    private volatile int generation;
    /** The pictures painted, a few in turn: a frame never draws into the one the screen read the frame before. */
    private final MapGpu.@Nullable Target[] pictures = new MapGpu.Target[MapGpu.FRAMES];
    private int pictureSlot;
    private long frame;
    private int lookVersion = Integer.MIN_VALUE;
    /** The level shown last (-1 live or none), and the one being blended out with when it started (-1 none). */
    private int shownLevel = -1, fadeFrom = -1;
    private long fadeStart;
    /** Frames painted mid-blend (for the selftests: a change of level does not pop). */
    long fadeFrames;
    /** For the selftests: the clock a blend reads (null: the real one). */
    static java.util.function.@Nullable LongSupplier clock;

    /** Counted since the painter was made. */
    long uploads, frames;
    final AtomicLong prepNanos = new AtomicLong(), prepMaxNanos = new AtomicLong();
    /** CPU time spent issuing bake passes (MM-8: with the prep, the bake time per tile). */
    long bakeNanos, bakeMaxNanos;
    private final AtomicLong prepDone = new AtomicLong();
    /** The last frame's numbers. */
    int lastOps, lastUploads, lastBakes, lastMips, lastQuads, lastPainted, lastShown, lastLevel, lastWork;
    boolean lastLive, lastFading;

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

    /** True if the last frame found nothing to do: everything visible is up to date, and no blend is under way. */
    boolean idle() {
        return lastWork == 0 && preparing.isEmpty() && !lastFading;
    }

    /** Why the last frame was not idle (for the selftests' logs). */
    String busy() {
        return lastWork + " jobs, " + preparing.size() + " preparing, " + prepared.size() + " prepared" + (lastFading ? ", blending" : "");
    }

    /** The mip level whose texels are about a pixel at this many blocks a pixel. */
    static int level(double blocksPerPixel) {
        if (blocksPerPixel <= 1) return 0;
        return Math.clamp(Math.round(Math.log(blocksPerPixel) / Math.log(2)), 0, BakeCache.LEVELS - 1);
    }

    // ------------------------------------------------------------------------------------------------ a frame

    /** Draws the frame and returns the picture to show (v from 1 at the top to 0 at the bottom). */
    MapGpu.Target paint(Frame f, PaintPalette pal, int palVersion, boolean terrain) {
        frame++;
        frames++;
        cache.frame(frame);
        if (data != null) data.update(frame);
        if (palVersion != lookVersion) {
            // what the baked tiles look like changed: all of them go
            cache.clear();
            closeTextures();
            lookVersion = palVersion;
        }
        boolean live = f.blocksPerPixel() < 1;
        // close up every block is a crisp square: read nearest
        boolean crisp = f.blocksPerPixel() <= 1;
        int level = live ? 0 : level(f.blocksPerPixel());
        lastLive = live;
        lastLevel = level;
        gpu.beginFrame(96);

        // a change of the level shown blends the old one out
        long now = clock == null ? System.nanoTime() : clock.getAsLong();
        int shown = live ? -1 : level;
        if (shown != shownLevel) {
            fadeFrom = shown >= 0 && shownLevel >= 0 && Math.abs(shown - shownLevel) <= FADE_LEVELS ? shownLevel : -1;
            fadeStart = now;
            shownLevel = shown;
        }
        float fade = fadeFrom < 0 ? 0 : 1 - (float) (now - fadeStart) / FADE_NANOS;
        if (fade <= 0) {
            fade = 0;
            fadeFrom = -1;
        }
        lastFading = fade > 0;

        LongArrayList cells = visible(f, level);
        lastShown = cells.size();

        int ops = 0;
        long bakes0 = cache.leafBakes, mips0 = cache.mipBuilds, up0 = uploads;
        if (terrain) {
            FrameJobs jobs = new FrameJobs();
            if (live) for (int i = 0; i < cells.size(); i++) jobs.live(x(cells.getLong(i)), z(cells.getLong(i)));
            for (int i = 0; i < cells.size(); i++) cache.need(level, x(cells.getLong(i)), z(cells.getLong(i)), jobs);
            lastWork = jobs.work.size() + jobs.asked;
            for (Job j : jobs.work) {
                if (ops >= OPS) break;
                ops += run(j, OPS - ops);
            }
            dropUnwanted(jobs.wanted);
        } else {
            lastWork = 0;
        }
        lastOps = ops;
        lastUploads = (int) (uploads - up0);
        lastBakes = (int) (cache.leafBakes - bakes0);
        lastMips = (int) (cache.mipBuilds - mips0);

        // nothing to do, nothing done, no blend, the same view as a picture drawn when there was nothing to do either: it
        // is the picture this frame would draw (icons go on top of it on screen), so it is shown again
        Shown key = new Shown(f, palVersion, terrain, cache.evictions);
        boolean still = ops == 0 && lastWork == 0 && preparing.isEmpty() && fade == 0;
        MapGpu.Target last = pictures[pictureSlot];
        if (reuse && still && reusable && key.equals(lastShownKey) && last != null) {
            reused++;
            lastReused = true;
            return last;
        }
        lastReused = false;
        painted++;
        reusable = still;
        lastShownKey = key;
        if (fade > 0) fadeFrames++;

        pictureSlot = (pictureSlot + 1) % pictures.length;
        MapGpu.Target picture = pictures[pictureSlot] = sized(pictures[pictureSlot], "map picture " + pictureSlot, f.width(), f.height());
        List<MapGpu.TexQuad> quads = new ArrayList<>();
        // the background first, under everything: where no tile lies
        quads.add(MapGpu.TexQuad.background(picture));
        int liveTiles = 0;
        if (terrain) {
            List<Piece> pieces = new ArrayList<>();
            List<MapGpu.TexQuad> tiles = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) {
                int x = x(cells.getLong(i)), z = z(cells.getLong(i));
                TileTextures t = live ? tex.get(TileIndex.key(x, z)) : null;
                if (t != null) {
                    texUsed.put(TileIndex.key(x, z), frame);
                    tiles.add(tileQuad(t, x, z, f));
                } else {
                    cellPieces(level, x, z, pieces);
                }
            }
            for (Piece p : pieces) quads.add(quad(p, f).nearest(crisp));
            if (fade > 0) {
                // the old level drawn over the new where both have a picture, weighted by what is left of the blend
                List<Piece> old = new ArrayList<>();
                LongArrayList was = visible(f, fadeFrom);
                for (int i = 0; i < was.size(); i++) cellPieces(fadeFrom, x(was.getLong(i)), z(was.getLong(i)), old);
                for (Piece p : pieces) {
                    for (Piece o : old) {
                        double ix0 = Math.max(p.wx0, o.wx0), iz0 = Math.max(p.wz0, o.wz0), ix1 = Math.min(p.wx1, o.wx1), iz1 = Math.min(p.wz1, o.wz1);
                        if (ix0 >= ix1 || iz0 >= iz1) continue;
                        Piece cut = new Piece(p.src, ix0, iz0, ix1, iz1, p.u(ix0), p.v(iz0), p.u(ix1), p.v(iz1));
                        quads.add(quad(cut, f).withFade(o.src, o.u(ix0), o.v(iz0), o.u(ix1), o.v(iz1), fade).nearest(crisp));
                    }
                }
            }
            quads.addAll(tiles);
            liveTiles = tiles.size();
        }
        lastQuads = quads.size() - 1 - liveTiles;
        lastPainted = liveTiles;
        gpu.quads(picture, Optional.empty(), quads, pal);

        cache.evict();
        if (data != null) data.trim();
        trimTextures();
        return picture;
    }

    /** The cells of a level over the view that hold any tile, nearest the middle first. */
    private LongArrayList visible(Frame f, int level) {
        int cs = BakeCache.SIDE << level;
        double wx1 = f.wx0() + f.width() * f.blocksPerPixel(), wz1 = f.wz0() + f.height() * f.blocksPerPixel();
        int cx0 = Math.floorDiv((int) Math.floor(f.wx0()), cs), cx1 = Math.floorDiv((int) Math.floor(wx1), cs);
        int cz0 = Math.floorDiv((int) Math.floor(f.wz0()), cs), cz1 = Math.floorDiv((int) Math.floor(wz1), cs);
        double mx = (f.wx0() + wx1) / 2 / cs - 0.5, mz = (f.wz0() + wz1) / 2 / cs - 0.5;
        LongArrayList cells = new LongArrayList();
        for (int z = cz0; z <= cz1; z++) for (int x = cx0; x <= cx1; x++) if (index.occupied(level, x, z)) cells.add(TileIndex.key(x, z));
        cells.sort((long a, long b) -> Double.compare(dist(a, mx, mz), dist(b, mx, mz)));
        return cells;
    }

    /** A piece on screen: its rectangle in the picture's pixels. */
    private static MapGpu.TexQuad quad(Piece p, Frame f) {
        double bpp = f.blocksPerPixel();
        return new MapGpu.TexQuad(p.src, (float) ((p.wx0 - f.wx0()) / bpp), (float) ((p.wz0 - f.wz0()) / bpp),
            (float) ((p.wx1 - f.wx0()) / bpp), (float) ((p.wz1 - f.wz0()) / bpp), p.u0, p.v0, p.u1, p.v1);
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

    /** A tile's own picture over its place in the picture, nearest (its texture has v 0 at the tile's north edge). */
    private static MapGpu.TexQuad tileQuad(TileTextures t, int tx, int tz, Frame f) {
        double bpp = f.blocksPerPixel();
        double x0 = (tx * (double) BakeCache.SIDE - f.wx0()) / bpp, x1 = x0 + BakeCache.SIDE / bpp;
        double y0 = (tz * (double) BakeCache.SIDE - f.wz0()) / bpp, y1 = y0 + BakeCache.SIDE / bpp;
        return new MapGpu.TexQuad(t.view(), (float) x0, (float) y0, (float) x1, (float) y1, 0, 0, 1, 1).nearest(true);
    }

    /** A cell from the cache, else from the nearest coarser level, else from the level below, else nothing (the background). */
    private void cellPieces(int level, int x, int z, List<Piece> out) {
        double size = BakeCache.SIDE << level;
        double x0 = x * size, z0 = z * size;
        BakeCache.Baked b = cache.get(level, x, z);
        if (b != null) {
            cache.touch(b);
            out.add(new Piece(b.target.view, x0, z0, x0 + size, z0 + size, 0, 1, 1, 0));
            return;
        }
        for (int j = level + 1; j < BakeCache.LEVELS; j++) {
            int s = j - level, n = 1 << s;
            BakeCache.Baked a = cache.get(j, x >> s, z >> s);
            if (a == null) continue;
            cache.touch(a);
            float ox = x & (n - 1), oz = z & (n - 1);
            out.add(new Piece(a.target.view, x0, z0, x0 + size, z0 + size, ox / n, 1 - oz / n, (ox + 1) / n, 1 - (oz + 1) / n));
            return;
        }
        if (level == 0) return;
        double h = size / 2;
        for (int q = 0; q < 4; q++) {
            BakeCache.Baked c = cache.get(level - 1, x * 2 + (q & 1), z * 2 + (q >> 1));
            if (c == null) continue;
            cache.touch(c);
            double qx = x0 + (q & 1) * h, qz = z0 + (q >> 1) * h;
            out.add(new Piece(c.target.view, qx, qz, qx + h, qz + h, 0, 1, 1, 0));
        }
    }

    // ------------------------------------------------------------------------------------------------ the work

    private enum Kind { UPLOAD, BAKE, MIP, SPLIT }

    private record Job(Kind kind, int level, int x, int z, boolean bake, int quarter) {
        Job(Kind kind, int level, int x, int z, boolean bake) {
            this(kind, level, x, z, bake, 0);
        }
    }

    /** The work found in a frame: GPU steps in the order found, and how many tiles' data was asked for. */
    private final class FrameJobs extends BakeCache.Jobs {
        final List<Job> work = new ArrayList<>();
        final LongOpenHashSet seen = new LongOpenHashSet();
        /** Tiles whose pictures this frame wanted (prepared results for any other tile are let go). */
        final LongOpenHashSet wanted = new LongOpenHashSet();
        int asked;

        @Override
        boolean full() {
            return visits > VISITS || work.size() >= OPS * 2 && asked >= ASKS;
        }

        /** A tile shown live wants its picture (not a bake: {@link #need} asks for that). */
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
            wanted.add(k);
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
            if (p != null) {
                // prepared from data that has changed since: let it go, so it neither blocks nor waits forever
                prepared.remove(k);
                preparing.remove(k);
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

        @Override
        void split(int level, int x, int z, int quarter) {
            int cx = x * 2 + (quarter & 1), cz = z * 2 + (quarter >> 1);
            if (seen.add(TileIndex.key(cx, cz) * 37 + level - 1)) work.add(new Job(Kind.SPLIT, level, x, z, false, quarter));
        }
    }

    /**
     * Prepared pictures for tiles no frame wants any more go, with their place among those being prepared: otherwise
     * they wait for an upload that never comes, and count against {@link #PREP_MAX} and the painter's idleness.
     */
    private void dropUnwanted(LongOpenHashSet wanted) {
        if (prepared.isEmpty()) return;
        for (Long k : List.copyOf(prepared.keySet())) {
            if (wanted.contains(k)) continue;
            prepared.remove(k);
            preparing.remove((long) k);
        }
    }

    private void submit(long k, long key, @Nullable SurfaceTile[][] around) {
        preparing.put(k, key);
        int gen = generation;
        PaintPalette pal = PaintPalette.current();
        Runnable job = () -> {
            if (gen != generation) return;
            long t0 = System.nanoTime();
            TilePrep.Result r = TilePrep.prepare(around, pal);
            // the explored samples of the tile and its neighbours, for what the map knows was captured
            long[][] explored = new long[9][];
            long[] revisions = new long[9];
            for (int i = 0; i < 9; i++) {
                SurfaceTile t = around == null ? null : around[i / 3][i % 3];
                if (t == null) continue;
                explored[i] = t.explored.clone();
                revisions[i] = t.revision;
            }
            long ns = System.nanoTime() - t0;
            prepDone.incrementAndGet();
            prepNanos.addAndGet(ns);
            prepMaxNanos.accumulateAndGet(ns, Math::max);
            if (gen == generation) prepared.put(k, new Prepared(gen, key, r, explored, revisions));
        };
        if (prep == null) job.run();
        else prep.execute(job);
    }

    /** Runs one job; returns the steps it took. */
    private int run(Job j, int left) {
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
                for (int i = 0; i < 9; i++) if (p.explored[i] != null) coverage.set(j.x + i % 3 - 1, j.z + i / 3 - 1, p.explored[i], p.revisions[i]);
                texUsed.put(k, frame);
                uploads++;
                if (j.bake && left > 1) return 1 + bake(j.x, j.z, t);
                return 1;
            }
            case BAKE -> {
                TileTextures t = tex.get(k);
                if (t == null || t.key != index.key3x3(j.x, j.z)) return 0;
                return bake(j.x, j.z, t);
            }
            case MIP -> {
                return cache.buildMip(gpu, j.level, j.x, j.z) ? 1 : 0;
            }
            case SPLIT -> {
                return cache.split(gpu, j.level, j.x, j.z, j.quarter) ? 1 : 0;
            }
        }
        return 0;
    }

    /** A tile's picture copied into its baked texture, texel for texel. */
    private int bake(int tx, int tz, TileTextures t) {
        long t0 = System.nanoTime();
        BakeCache.Baked b = cache.leaf(tx, tz);
        int s = BakeCache.SIDE;
        gpu.quads(b.target, MapGpu.TRANSPARENT, List.of(new MapGpu.TexQuad(t.view(), 0, 0, s, s, 0, 0, 1, 1).nearest(true)), null);
        cache.baked(b, t.key);
        long ns = System.nanoTime() - t0;
        bakeNanos += ns;
        bakeMaxNanos = Math.max(bakeMaxNanos, ns);
        return 1;
    }

    /**
     * Bake time per tile, averaged since the painter was made (MM-8): {prep ms (off-thread), bake pass µs (render
     * thread)}; zeros before the first.
     */
    double[] bakeTimes() {
        long n = prepDone.get(), bakes = cache.leafBakes;
        return new double[]{n == 0 ? 0 : prepNanos.get() / 1e6 / n, bakes == 0 ? 0 : bakeNanos / 1e3 / bakes};
    }

    // ------------------------------------------------------------------------------------------------ letting go

    /** Pictures not wanted this frame go: a tile shown live or about to be baked is wanted again every frame. */
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
        lastShownKey = null;
        reusable = false;
        shownLevel = fadeFrom = -1;
        for (int i = 0; i < pictures.length; i++) {
            if (pictures[i] != null) pictures[i].close();
            pictures[i] = null;
        }
        gpu.close();
        data = null;
    }

    /** True while the GPU holds a picture (the map is open and painted). */
    boolean painting() {
        return pictures[pictureSlot] != null;
    }

    @Override
    public void close() {
        release();
        cache.close();
        coverage.clear();
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
