package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The fog over unexplored land (MM-5): one small mask per cell of the mip grid that is drawn ({@link TilePrep#fogMask},
 * worked out off the render thread), applied as the screen's pixels are written and never baked, so exploring changes a
 * mask and no bake. A mask is made from the explored samples of the tiles round its cell (kept here from the data the
 * tiles were last prepared from, so a fog change needs no tile data) and the group's fog bits of their regions. Each
 * mask counts the changes that reach it and remembers which count its texture shows; a cell whose mask is behind is
 * made again (two at a time), and until then the old one, or a coarser level's, is drawn. Render thread only.
 */
final class FogMasks implements AutoCloseable {
    static final int SIZE = TilePrep.MASK_SIZE;
    /** Masks kept on the GPU at most (17 KB each); the least recently drawn go first. */
    private static final int CAP = 192;
    /** Masks being made off-thread at once, and uploaded in one frame, at most. */
    private static final int IN_FLIGHT = 2, UPLOADS = 4;

    /** The group's fog: a region's chunk bits (a region is a tile), or null where nothing is explored. */
    interface Fog {
        long @Nullable [] bits(long region);
    }

    /** A mask and its texture coordinates at the corners of a rectangle (v down, as the mask's rows). */
    record Uv(GpuTextureView view, float u0, float v0, float u1, float v1) {
    }

    private static final class Mask {
        final int level, x, z;
        @Nullable DynamicTexture tex;
        /** Changes that reached it, and the count its texture shows (0: none yet). */
        int wanted = 1, built;
        boolean busy;
        long used;

        Mask(int level, int x, int z) {
            this.level = level;
            this.x = x;
            this.z = z;
        }
    }

    private record Done(Mask mask, int wanted, byte[] texels) {
    }

    private final TileIndex index;
    private final Fog fog;
    private final @Nullable Executor prep;
    /** Each tile's explored samples, and the revision they are from. */
    private final Long2ObjectOpenHashMap<long[]> coverage = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap coverageRevision = new Long2LongOpenHashMap();
    @SuppressWarnings("unchecked")
    private final Long2ObjectOpenHashMap<Mask>[] levels = new Long2ObjectOpenHashMap[TileIndex.LEVELS];
    private final ConcurrentLinkedQueue<Done> done = new ConcurrentLinkedQueue<>();
    private int busy;
    private long frame;
    private float edge = PaintPalette.DEFAULT.fogEdge();
    /** Bumped whenever a mask's texture changes: a picture painted before is out of date. */
    long version;
    /** Counted since made. */
    long builds, uploads;
    final AtomicLong buildNanos = new AtomicLong(), buildMaxNanos = new AtomicLong();

    /** @param prep where masks are made; null makes them at once on this thread (the selftests) */
    FogMasks(TileIndex index, Fog fog, @Nullable Executor prep) {
        this.index = index;
        this.fog = fog;
        this.prep = prep;
        for (int k = 0; k < levels.length; k++) levels[k] = new Long2ObjectOpenHashMap<>();
    }

    /** The palette's edge width (blocks): every mask is made again when it changes. */
    void edge(float e) {
        if (e == edge) return;
        edge = e;
        reset();
    }

    /** The samples a tile was prepared from: its mask, and its neighbours', change if they did. */
    void coverage(int tx, int tz, long[] explored, long revision) {
        long k = TileIndex.key(tx, tz);
        if (revision <= coverageRevision.get(k)) return;
        coverageRevision.put(k, revision);
        long[] old = coverage.get(k);
        if (old != null && Arrays.equals(old, explored)) return;
        coverage.put(k, explored.clone());
        changed(tx, tz);
    }

    /** A region's fog (or a tile's coverage) changed: every mask that reads it is out of date. */
    void changed(int tx, int tz) {
        for (int lv = 0; lv < levels.length; lv++) {
            int m = TilePrep.maskMarginTiles(lv, edge);
            for (int z = (tz - m) >> lv; z <= (tz + m) >> lv; z++) {
                for (int x = (tx - m) >> lv; x <= (tx + m) >> lv; x++) {
                    Mask mask = levels[lv].get(TileIndex.key(x, z));
                    if (mask != null) mask.wanted++;
                }
            }
        }
    }

    /** All the fog was replaced: every mask is out of date. */
    void reset() {
        for (Long2ObjectOpenHashMap<Mask> m : levels) for (Mask mask : m.values()) mask.wanted++;
    }

    /** Starts a frame: finished masks go up (a few a frame). */
    void frame(long f) {
        frame = f;
        for (int n = 0; n < UPLOADS; n++) {
            Done d = done.poll();
            if (d == null) break;
            Mask m = d.mask;
            m.busy = false;
            busy--;
            if (levels[m.level].get(TileIndex.key(m.x, m.z)) != m) continue;
            if (m.tex == null) m.tex = new DynamicTexture(() -> MapMod.ID + " map fog " + m.level + " " + m.x + "," + m.z, new NativeImage(SIZE, SIZE, false));
            NativeImage image = m.tex.getPixels();
            if (image == null) continue;
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    int v = d.texels[x + z * SIZE] & 0xFF;
                    image.setPixel(x, z, 0xFF000000 | v << 16 | v << 8 | v);
                }
            }
            m.tex.upload();
            m.built = d.wanted;
            uploads++;
            version++;
        }
    }

    /** A cell is drawn this frame: its mask is kept, and made (again) if it is behind. Returns true while it is behind. */
    boolean need(int level, int x, int z) {
        long k = TileIndex.key(x, z);
        Mask m = levels[level].get(k);
        if (m == null) levels[level].put(k, m = new Mask(level, x, z));
        m.used = frame;
        if (m.built == m.wanted) return false;
        if (!m.busy && busy < IN_FLIGHT) submit(m);
        return true;
    }

    /** True while masks are being made or wait to go up. */
    boolean pending() {
        return busy > 0 || !done.isEmpty();
    }

    private void submit(Mask m) {
        int margin = TilePrep.maskMarginTiles(m.level, edge);
        int x0 = (m.x << m.level) - margin, x1 = ((m.x + 1) << m.level) - 1 + margin;
        int z0 = (m.z << m.level) - margin, z1 = ((m.z + 1) << m.level) - 1 + margin;
        Long2ObjectOpenHashMap<long[]> cov = new Long2ObjectOpenHashMap<>(), bits = new Long2ObjectOpenHashMap<>();
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > coverage.size()) {
            for (var e : coverage.long2ObjectEntrySet()) {
                int tx = (int) (e.getLongKey() >> 32), tz = (int) e.getLongKey();
                if (tx >= x0 && tx <= x1 && tz >= z0 && tz <= z1) take(e.getLongKey(), e.getValue(), cov, bits);
            }
        } else {
            for (int tz = z0; tz <= z1; tz++) {
                for (int tx = x0; tx <= x1; tx++) {
                    long k = TileIndex.key(tx, tz);
                    long[] c = coverage.get(k);
                    if (c != null) take(k, c, cov, bits);
                }
            }
        }
        m.busy = true;
        busy++;
        int wanted = m.wanted, level = m.level, cx = m.x, cz = m.z;
        float e = edge;
        Runnable job = () -> {
            long t0 = System.nanoTime();
            byte[] texels = TilePrep.fogMask(level, cx, cz, e, cov, bits);
            long ns = System.nanoTime() - t0;
            buildNanos.addAndGet(ns);
            buildMaxNanos.accumulateAndGet(ns, Math::max);
            done.add(new Done(m, wanted, texels));
        };
        builds++;
        if (prep == null) job.run();
        else prep.execute(job);
    }

    /** A tile's coverage and its region's fog, copied (the fog changes in place on the client thread). */
    private void take(long k, long[] c, Long2ObjectOpenHashMap<long[]> cov, Long2ObjectOpenHashMap<long[]> bits) {
        long[] f = fog.bits(k);
        if (f == null) return;
        cov.put(k, c);
        bits.put(k, f.clone());
    }

    /**
     * The mask to draw a world rectangle (blocks) of a cell with, and its coordinates there: the cell's own, else the
     * nearest coarser level's that is made; null if none is (all unexplored until one is).
     */
    @Nullable Uv uv(int level, int x, int z, double wx0, double wz0, double wx1, double wz1) {
        for (int lv = level; lv < levels.length; lv++) {
            int s = lv - level;
            Mask m = levels[lv].get(TileIndex.key(x >> s, z >> s));
            if (m == null || m.tex == null || m.built == 0) continue;
            m.used = frame;
            double t = TilePrep.maskTexel(lv), ox = (double) (x >> s) * ((long) BakeCache.SIDE << lv), oz = (double) (z >> s) * ((long) BakeCache.SIDE << lv);
            return new Uv(m.tex.getTextureView(), (float) (((wx0 - ox) / t + 1) / SIZE), (float) (((wz0 - oz) / t + 1) / SIZE),
                (float) (((wx1 - ox) / t + 1) / SIZE), (float) (((wz1 - oz) / t + 1) / SIZE));
        }
        return null;
    }

    /** Keeps at most {@link #CAP} masks on the GPU: the least recently drawn (none drawn this frame) go. */
    void evict() {
        int held = 0;
        for (Long2ObjectOpenHashMap<Mask> m : levels) held += m.size();
        if (held <= CAP) return;
        List<Mask> all = new ArrayList<>();
        for (Long2ObjectOpenHashMap<Mask> m : levels) for (Mask mask : m.values()) if (mask.used < frame) all.add(mask);
        all.sort((a, b) -> Long.compare(a.used, b.used));
        for (int i = 0; i < all.size() && held > CAP; i++, held--) {
            Mask m = all.get(i);
            levels[m.level].remove(TileIndex.key(m.x, m.z));
            if (m.tex != null) m.tex.close();
        }
    }

    int held() {
        int n = 0;
        for (Long2ObjectOpenHashMap<Mask> m : levels) n += m.size();
        return n;
    }

    /** For the selftests: whether a cell's mask is made and up to date. */
    boolean current(int level, int x, int z) {
        Mask m = levels[level].get(TileIndex.key(x, z));
        return m != null && m.built != 0 && m.built == m.wanted;
    }

    /** For the selftests: the level-0 mask at a block, made now on this thread from what is held, 0 to 1 (-1: nothing held there). */
    float valueAt(int x, int z, float e) {
        int tx = Math.floorDiv(x, BakeCache.SIDE), tz = Math.floorDiv(z, BakeCache.SIDE);
        if (!coverage.containsKey(TileIndex.key(tx, tz))) return -1;
        Long2ObjectOpenHashMap<long[]> cov = new Long2ObjectOpenHashMap<>(), bits = new Long2ObjectOpenHashMap<>();
        int m = TilePrep.maskMarginTiles(0, e);
        for (int dz = -m; dz <= m; dz++) {
            for (int dx = -m; dx <= m; dx++) {
                long k = TileIndex.key(tx + dx, tz + dz);
                long[] c = coverage.get(k);
                if (c != null) take(k, c, cov, bits);
            }
        }
        byte[] texels = TilePrep.fogMask(0, tx, tz, e, cov, bits);
        int t = TilePrep.maskTexel(0);
        double fx = Math.floorMod(x, BakeCache.SIDE) / (double) t + 0.5, fz = Math.floorMod(z, BakeCache.SIDE) / (double) t + 0.5;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double ax = fx - ix, az = fz - iz;
        double a = texels[ix + iz * SIZE] & 0xFF, b = texels[ix + 1 + iz * SIZE] & 0xFF, c = texels[ix + (iz + 1) * SIZE] & 0xFF, d = texels[ix + 1 + (iz + 1) * SIZE] & 0xFF;
        return (float) (((a + (b - a) * ax) * (1 - az) + (c + (d - c) * ax) * az) / 255);
    }

    /** For the selftests: the explored samples kept for a tile, or null. */
    long @Nullable [] coverageOf(int tx, int tz) {
        return coverage.get(TileIndex.key(tx, tz));
    }

    @Override
    public void close() {
        for (Long2ObjectOpenHashMap<Mask> m : levels) {
            for (Mask mask : m.values()) if (mask.tex != null) mask.tex.close();
            m.clear();
        }
        // masks still being made are dropped when they finish: they are no longer in the maps
    }
}
