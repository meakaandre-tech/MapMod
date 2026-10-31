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
 * Paints one frame of the map (MM-4) into a picture the size of the map on screen, in real pixels.
 * <p>
 * Below one pixel a block every visible cell of the mip level whose texels are about a pixel is drawn as one quad from
 * the {@link BakeCache}; a cell not made yet borrows from the nearest coarser level, else from the level below, else is
 * paper. Above one pixel a block the visible tiles are painted live with the full shader (as in MM-3), each from its
 * {@link TileTextures}, and a tile without them yet borrows its baked texture. Either way every screen pixel gets the
 * grain and the vignette as it is written (baked tiles carry neither), so they stay screen-fine at every zoom.
 * <p>
 * MM-5: the fog over unexplored land goes on there too, from each cell's {@link FogMasks} mask and a static cloud texture
 * in world space, so exploring never bakes anything again. When the level shown changes, the old level is blended out
 * over {@link #FADE_NANOS} (both drawn from the cache in one pass), and the frame after that settles like any other.
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

    /** A tile's textures worked out off-thread, and the explored samples and revisions of it and its neighbours. */
    private record Prepared(int generation, long key, TilePrep.Result result, long[][] explored, long[] revisions) {
    }

    /** What a painted picture showed: if a frame would show the same, the picture is shown again. */
    private record Shown(Frame frame, int palVersion, boolean contours, boolean terrain, long evictions, long fog) {
    }

    /**
     * A part of the picture: a world rectangle (blocks) shown from a texture at those coordinates (v up), and the cell
     * of the level being drawn it belongs to (whose fog mask it gets).
     */
    private record Piece(GpuTextureView src, double wx0, double wz0, double wx1, double wz1, float u0, float v0, float u1, float v1,
                         int level, int cx, int cz) {
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
    final FogMasks masks;
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
    private boolean lookContours;
    /** The level shown last (-1 live or none), and the one being blended out with when it started (-1 none). */
    private int shownLevel = -1, fadeFrom = -1;
    private long fadeStart;
    /** Frames painted mid-blend (for the selftests: a change of level does not pop). */
    long fadeFrames;
    /** For the selftests: the clock a blend reads (null: the real one). */
    static java.util.function.@Nullable LongSupplier clock;

    /** Counted since the painter was made. */
    long uploads, frames;
    final AtomicLong prepNanos = new AtomicLong(), prepMaxNanos = new AtomicLong(), brushNanos = new AtomicLong();
    /** CPU time spent issuing bake passes (MM-8: with the prep, the bake time per tile). */
    long bakeNanos, bakeMaxNanos;
    private final AtomicLong prepDone = new AtomicLong();
    /** The last frame's numbers. */
    int lastOps, lastUploads, lastBakes, lastMips, lastQuads, lastPainted, lastShown, lastLevel, lastWork;
    boolean lastLive, lastContours, lastFading, lastFogPending;

    /**
     * @param prep where tiles and fog masks are prepared; null prepares them at once on this thread (the selftests)
     */
    MapPainter(TileIndex index, long budget, @Nullable Executor prep, FogMasks.Fog fog) {
        this.index = index;
        this.cache = new BakeCache(index, budget);
        this.masks = new FogMasks(index, fog, prep);
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

    /** True if the last frame found nothing to do: everything visible is up to date, its fog too, and no blend is under way. */
    boolean idle() {
        return lastWork == 0 && preparing.isEmpty() && !lastFogPending && !lastFading;
    }

    /** Why the last frame was not idle (for the selftests' logs). */
    String busy() {
        return lastWork + " jobs, " + preparing.size() + " preparing, " + prepared.size() + " prepared" + (lastFogPending ? ", fog masks" : "")
            + (lastFading ? ", blending" : "");
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
        masks.frame(frame);
        masks.edge(pal.fogEdge());
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
        int cs = BakeCache.SIDE << level;
        lastShown = cells.size();

        int ops = 0;
        long bakes0 = cache.leafBakes, mips0 = cache.mipBuilds, up0 = uploads;
        boolean fogPending = false;
        if (terrain) {
            FrameJobs jobs = new FrameJobs();
            if (live) for (int i = 0; i < cells.size(); i++) jobs.live(x(cells.getLong(i)), z(cells.getLong(i)));
            for (int i = 0; i < cells.size(); i++) cache.need(level, x(cells.getLong(i)), z(cells.getLong(i)), jobs);
            lastWork = jobs.work.size() + jobs.asked;
            for (Job j : jobs.work) {
                if (ops >= OPS) break;
                ops += run(j, pal, palVersion, contours, OPS - ops);
            }
            dropUnwanted(jobs.wanted);
            for (int i = 0; i < cells.size(); i++) fogPending |= masks.need(level, x(cells.getLong(i)), z(cells.getLong(i)));
            fogPending |= masks.pending();
        } else {
            lastWork = 0;
        }
        lastFogPending = fogPending;
        lastOps = ops;
        lastUploads = (int) (uploads - up0);
        lastBakes = (int) (cache.leafBakes - bakes0);
        lastMips = (int) (cache.mipBuilds - mips0);

        // nothing to do, nothing done, no blend, the same view and fog as a picture painted when there was nothing to
        // do either: it is the picture this frame would paint (icons go on top of it on screen), so it is shown again
        Shown key = new Shown(f, palVersion, contours, terrain, cache.evictions, masks.version);
        boolean still = ops == 0 && lastWork == 0 && preparing.isEmpty() && !fogPending && fade == 0;
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
        // paper first, under everything: where no tile lies, all fog
        double wx1 = f.wx0() + f.width() * f.blocksPerPixel(), wz1 = f.wz0() + f.height() * f.blocksPerPixel();
        quads.add(world(MapGpu.TexQuad.paper(picture), f.wx0(), f.wz0(), wx1, wz1));
        List<MapGpu.PaintQuad> paints = new ArrayList<>();
        if (terrain) {
            List<Piece> pieces = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) {
                int x = x(cells.getLong(i)), z = z(cells.getLong(i));
                TileTextures t = live ? tex.get(TileIndex.key(x, z)) : null;
                if (t != null) {
                    texUsed.put(TileIndex.key(x, z), frame);
                    double tx0 = x * (double) cs, tz0 = z * (double) cs;
                    paints.add(paintQuad(t, x, z, f).withMask(masks.uv(0, x, z, tx0, tz0, tx0 + cs, tz0 + cs)));
                } else {
                    cellPieces(level, x, z, pieces);
                }
            }
            for (Piece p : pieces) quads.add(quad(p, f));
            if (fade > 0) {
                // the old level drawn over the new where both have a picture, weighted by what is left of the blend
                List<Piece> old = new ArrayList<>();
                LongArrayList was = visible(f, fadeFrom);
                for (int i = 0; i < was.size(); i++) cellPieces(fadeFrom, x(was.getLong(i)), z(was.getLong(i)), old);
                for (Piece p : pieces) {
                    for (Piece o : old) {
                        double ix0 = Math.max(p.wx0, o.wx0), iz0 = Math.max(p.wz0, o.wz0), ix1 = Math.min(p.wx1, o.wx1), iz1 = Math.min(p.wz1, o.wz1);
                        if (ix0 >= ix1 || iz0 >= iz1) continue;
                        Piece cut = new Piece(p.src, ix0, iz0, ix1, iz1, p.u(ix0), p.v(iz0), p.u(ix1), p.v(iz1), p.level, p.cx, p.cz);
                        quads.add(quad(cut, f).withFade(o.src, o.u(ix0), o.v(iz0), o.u(ix1), o.v(iz1), fade));
                    }
                }
            }
        }
        lastQuads = quads.size() - 1;
        lastPainted = paints.size();
        // the fog, grain and the vignette go on as the screen's pixels are written, by both passes
        gpu.quads(picture, Optional.empty(), quads, pal, false);
        gpu.paint(picture, Optional.empty(), paints, pal, palVersion, contours, true);

        cache.evict();
        masks.evict();
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

    /** A piece on screen: its rectangle in the picture's pixels, its fog mask, and its world position for the clouds. */
    private MapGpu.TexQuad quad(Piece p, Frame f) {
        double bpp = f.blocksPerPixel();
        MapGpu.TexQuad q = new MapGpu.TexQuad(p.src, (float) ((p.wx0 - f.wx0()) / bpp), (float) ((p.wz0 - f.wz0()) / bpp),
            (float) ((p.wx1 - f.wx0()) / bpp), (float) ((p.wz1 - f.wz0()) / bpp), p.u0, p.v0, p.u1, p.v1);
        return world(q, p.wx0, p.wz0, p.wx1, p.wz1).withMask(masks.uv(p.level, p.cx, p.cz, p.wx0, p.wz0, p.wx1, p.wz1));
    }

    /** A quad's world position at its corners, in noise units, its left and top wrapped to the noise's period. */
    private static MapGpu.TexQuad world(MapGpu.TexQuad q, double wx0, double wz0, double wx1, double wz1) {
        double sx = wx0 / MapGpu.NOISE_UNIT, sz = wz0 / MapGpu.NOISE_UNIT;
        double ox = sx - MapGpu.NOISE_PERIOD * Math.floor(sx / MapGpu.NOISE_PERIOD), oz = sz - MapGpu.NOISE_PERIOD * Math.floor(sz / MapGpu.NOISE_PERIOD);
        return q.withWorld((float) ox, (float) oz, (float) (ox + (wx1 - wx0) / MapGpu.NOISE_UNIT), (float) (oz + (wz1 - wz0) / MapGpu.NOISE_UNIT));
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
     * A tile's paint quad over the given rectangle. Sample i stands for its cell's middle (block i * 2 + 1), so the
     * tile's edge is half a sample before the first and after the last; texel i + APRON holds sample i. Texel (0, 0) is
     * at world sample (tx * 256 - APRON, ...): its position in noise units (half a sample each) is wrapped to the noise's
     * period, so neighbouring tiles agree.
     */
    private static MapGpu.PaintQuad tileQuad(TileTextures t, int tx, int tz, float x0, float y0, float x1, float y1) {
        float u0 = TileTextures.APRON - 0.5f, u1 = u0 + SurfaceTile.SIDE;
        int per = MapGpu.NOISE_UNIT / SurfaceTile.SPACING, wrap = MapGpu.NOISE_PERIOD * per;
        // in samples, wrapped to the period in samples (a whole number of noise units, since APRON is even)
        int sx = Math.floorMod(tx * SurfaceTile.SIDE - TileTextures.APRON, wrap), sz = Math.floorMod(tz * SurfaceTile.SIDE - TileTextures.APRON, wrap);
        return new MapGpu.PaintQuad(t, x0, y0, x1, y1, u0, u0, u1, u1, (float) sx / per, (float) sz / per);
    }

    /** A cell from the cache, else from the nearest coarser level, else from the level below, else nothing (paper). */
    private void cellPieces(int level, int x, int z, List<Piece> out) {
        double size = BakeCache.SIDE << level;
        double x0 = x * size, z0 = z * size;
        BakeCache.Baked b = cache.get(level, x, z);
        if (b != null) {
            cache.touch(b);
            out.add(new Piece(b.target.view, x0, z0, x0 + size, z0 + size, 0, 1, 1, 0, level, x, z));
            return;
        }
        for (int j = level + 1; j < BakeCache.LEVELS; j++) {
            int s = j - level, n = 1 << s;
            BakeCache.Baked a = cache.get(j, x >> s, z >> s);
            if (a == null) continue;
            cache.touch(a);
            float ox = x & (n - 1), oz = z & (n - 1);
            out.add(new Piece(a.target.view, x0, z0, x0 + size, z0 + size, ox / n, 1 - oz / n, (ox + 1) / n, 1 - (oz + 1) / n, level, x, z));
            return;
        }
        if (level == 0) return;
        double h = size / 2;
        for (int q = 0; q < 4; q++) {
            BakeCache.Baked c = cache.get(level - 1, x * 2 + (q & 1), z * 2 + (q >> 1));
            if (c == null) continue;
            cache.touch(c);
            double qx = x0 + (q & 1) * h, qz = z0 + (q >> 1) * h;
            out.add(new Piece(c.target.view, qx, qz, qx + h, qz + h, 0, 1, 1, 0, level, x, z));
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
        /** Tiles whose textures this frame wanted (prepared results for any other tile are let go). */
        final LongOpenHashSet wanted = new LongOpenHashSet();
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
     * Prepared textures for tiles no frame wants any more go, with their place among those being prepared: otherwise
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
            brushNanos.addAndGet(r.brushNanos());
            // the explored samples of the tile and its neighbours, for the fog masks
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
                for (int i = 0; i < 9; i++) if (p.explored[i] != null) masks.coverage(j.x + i % 3 - 1, j.z + i / 3 - 1, p.explored[i], p.revisions[i]);
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
            case SPLIT -> {
                return cache.split(gpu, j.level, j.x, j.z, j.quarter) ? 1 : 0;
            }
        }
        return 0;
    }

    private int bake(int tx, int tz, TileTextures t, PaintPalette pal, int palVersion, boolean contours) {
        long t0 = System.nanoTime();
        BakeCache.Baked b = cache.leaf(tx, tz);
        int s = BakeCache.SIDE;
        gpu.paint(b.target, MapGpu.clear(pal), List.of(tileQuad(t, tx, tz, 0, 0, s, s)), pal, palVersion, contours, false);
        cache.baked(b, t.key);
        long ns = System.nanoTime() - t0;
        bakeNanos += ns;
        bakeMaxNanos = Math.max(bakeMaxNanos, ns);
        return 1;
    }

    /**
     * Bake time per tile, averaged since the painter was made (MM-8): {prep ms (off-thread, the brush included), of it
     * the brush ms, bake pass µs (render thread)}; zeros before the first.
     */
    double[] bakeTimes() {
        long n = prepDone.get(), bakes = cache.leafBakes;
        return new double[]{n == 0 ? 0 : prepNanos.get() / 1e6 / n, n == 0 ? 0 : brushNanos.get() / 1e6 / n,
            bakes == 0 ? 0 : bakeNanos / 1e3 / bakes};
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

    /** The screen closed: the pictures, the tiles' textures and their data go; the baked tiles and fog masks stay. */
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
        masks.close();
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
