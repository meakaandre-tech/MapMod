package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceTile;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Baked tiles and the mip pyramid over them, on the GPU, within a memory budget (MM-4). Level 0 holds tiles painted
 * once at one pixel a block ({@value #SIDE} square); level k holds 512-pixel cells 2^k tiles across, each made from the
 * four cells of the level below drawn into its quarters with linear filtering. A baked tile is out of date when the
 * revisions it was made from ({@link TileIndex#key3x3}) are not the index's any more; a mip keeps one dirty bit per
 * quarter, set when that child is re-made or when a tile under it changes, and cleared when the quarter is copied from
 * a child that is itself up to date. Least recently used cells go first when the budget is exceeded; they are made again
 * when next wanted.
 * <p>
 * MM-5: when a mip's quarter is out of date and the child under it was evicted, the child is made again from the mip's
 * own picture of that quarter (copied up texel for texel, so copying it back down gives the same picture) instead of
 * from the whole subtree; only its quarters whose tiles changed since ({@link TileIndex#changedAt}) are out of date, so
 * only the changed tiles bake again. Each quarter keeps how many levels coarser than its cell its picture is (its
 * deficit) and when it was made; a cell shown on screen wants a deficit of 0, one used to make its parent may be a level
 * coarser. Render thread only.
 */
final class BakeCache implements AutoCloseable {
    static final int SIDE = SurfaceTile.BLOCKS;
    static final int LEVELS = TileIndex.LEVELS;

    static final class Baked {
        final int level, x, z;
        final MapGpu.Target target;
        /** Level 0: what it was painted from (0 = not painted yet). */
        long key;
        /** Mips: quarters still to copy, bit (cx & 1) | (cz & 1) << 1. */
        int dirty;
        /** Mips: per quarter, how many levels coarser than this cell its picture is (0 = full detail). */
        final byte[] deficit = new byte[4];
        /** Per quarter (level 0: [0]), the index clock its picture is up to date as of. */
        final long[] builtAt = new long[4];
        long used;

        Baked(int level, int x, int z, MapGpu.Target target) {
            this.level = level;
            this.x = x;
            this.z = z;
            this.target = target;
        }

        int maxDeficit() {
            return Math.max(Math.max(deficit[0], deficit[1]), Math.max(deficit[2], deficit[3]));
        }
    }

    private final TileIndex index;
    @SuppressWarnings("unchecked")
    private final Long2ObjectOpenHashMap<Baked>[] levels = new Long2ObjectOpenHashMap[LEVELS];
    private long bytes, budget;
    private long frame;
    /** Counted since the cache was made. */
    long leafBakes, mipBuilds, evictions, splits;

    BakeCache(TileIndex index, long budget) {
        this.index = index;
        this.budget = budget;
        for (int k = 0; k < LEVELS; k++) levels[k] = new Long2ObjectOpenHashMap<>();
    }

    void budget(long b) {
        budget = b;
    }

    long budget() {
        return budget;
    }

    long bytes() {
        return bytes;
    }

    int count(int level) {
        return levels[level].size();
    }

    int mips() {
        int n = 0;
        for (int k = 1; k < LEVELS; k++) n += levels[k].size();
        return n;
    }

    void frame(long f) {
        frame = f;
    }

    @Nullable Baked get(int level, int x, int z) {
        return levels[level].get(TileIndex.key(x, z));
    }

    void touch(Baked b) {
        b.used = frame;
    }

    /** Up to date and in full detail: what a cell shown on screen wants. */
    boolean clean(Baked b) {
        return b.level == 0 ? leafClean(b) : b.dirty == 0 && b.maxDeficit() == 0;
    }

    private boolean leafClean(Baked b) {
        return b.key != 0 && b.key == index.key3x3(b.x, b.z);
    }

    /** Up to date, and no coarser than {@code allowed} levels below its own detail. */
    private boolean usable(Baked b, int allowed) {
        return b.level == 0 ? leafClean(b) : b.dirty == 0 && b.maxDeficit() <= allowed;
    }

    static int bit(int cx, int cz) {
        return (cx & 1) | (cz & 1) << 1;
    }

    /** The quarters of a cell whose children hold any tile. */
    int occupiedChildren(int level, int x, int z) {
        int bits = 0;
        for (int b = 0; b < 4; b++) if (index.occupied(level - 1, x * 2 + (b & 1), z * 2 + (b >> 1))) bits |= 1 << b;
        return bits;
    }

    /** Marks the quarter over {@code (level, x, z)} dirty in every mip above it that is held. */
    private void dirtyAbove(int level, int x, int z) {
        for (int j = level + 1; j < LEVELS; j++) {
            int s = j - level;
            Baked a = get(j, x >> s, z >> s);
            if (a != null) a.dirty |= 1 << bit(x >> (s - 1), z >> (s - 1));
        }
    }

    /** A tile's revision changed: it and its neighbours (whose aprons it is in) are out of date, and every mip over them. */
    void tileChanged(int tx, int tz) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) dirtyAbove(0, tx + dx, tz + dz);
    }

    /** The target a tile is baked into, made if needed. */
    Baked leaf(int tx, int tz) {
        Baked b = get(0, tx, tz);
        if (b == null) b = add(0, tx, tz);
        touch(b);
        return b;
    }

    /** A tile was baked from {@code key}: the mips over it take it again. */
    void baked(Baked b, long key) {
        b.key = key;
        b.builtAt[0] = index.clock();
        leafBakes++;
        dirtyAbove(0, b.x, b.z);
    }

    private Baked add(int level, int x, int z) {
        Baked b = new Baked(level, x, z, new MapGpu.Target("map " + (level == 0 ? "tile " : "mip " + level + " ") + x + "," + z, SIDE, SIDE));
        levels[level].put(TileIndex.key(x, z), b);
        bytes += b.target.bytes();
        return b;
    }

    /** What a cell is up to date as of: the oldest of its quarters that hold tiles. */
    private long builtAt(Baked c) {
        if (c.level == 0) return c.builtAt[0];
        long t = Long.MAX_VALUE;
        int occ = occupiedChildren(c.level, c.x, c.z);
        for (int q = 0; q < 4; q++) if ((occ & 1 << q) != 0) t = Math.min(t, c.builtAt[q]);
        return t;
    }

    /** A cell shown on screen: up to date and in full detail. */
    void need(int level, int x, int z, Jobs jobs) {
        need(level, x, z, 0, jobs);
    }

    /**
     * What wants making for a cell to be up to date with a deficit of at most {@code allowed}, depth first: tiles to bake
     * go to {@code jobs.leaf}, mips with at least one quarter ready to copy to {@code jobs.mip}, children to make again
     * from their parent's picture to {@code jobs.split}. Children are visited before their parent, so the work found
     * first is the work that unblocks the rest.
     */
    private void need(int level, int x, int z, int allowed, Jobs jobs) {
        if (jobs.full() || !index.occupied(level, x, z)) return;
        jobs.visits++;
        Baked b = get(level, x, z);
        if (b != null) touch(b);
        if (level == 0) {
            if (b == null || !leafClean(b)) jobs.leaf(x, z);
            return;
        }
        if (b != null && usable(b, allowed)) return;
        int occ = occupiedChildren(level, x, z);
        boolean ready = false;
        for (int q = 0; q < 4; q++) {
            if ((occ & 1 << q) == 0) continue;
            boolean dirty = b == null || (b.dirty & 1 << q) != 0;
            if (!dirty && b.deficit[q] <= allowed) continue;
            int cx = x * 2 + (q & 1), cz = z * 2 + (q >> 1);
            Baked c = get(level - 1, cx, cz);
            if (c != null && usable(c, allowed + 1)) {
                ready = true;
                touch(c);
            } else if (c == null && b != null && dirty && b.deficit[q] <= allowed && level - 1 >= 1) {
                // the child was evicted: made again from this cell's own picture of it, out of date only where it changed
                jobs.split(level, x, z, q);
            } else {
                need(level - 1, cx, cz, allowed + 1, jobs);
            }
        }
        if (ready) jobs.mip(level, x, z);
    }

    /** Where the work of a frame is gathered. */
    abstract static class Jobs {
        int visits;

        abstract boolean full();

        abstract void leaf(int tx, int tz);

        abstract void mip(int level, int x, int z);

        abstract void split(int level, int x, int z, int quarter);
    }

    /**
     * Copies a mip's quarters in from children that are up to date (made, and cleared to paper, if it is new): those
     * out of date, and those whose child is in more detail than the quarter; the mips over it take it again. Returns
     * false if there was nothing ready after all.
     */
    boolean buildMip(MapGpu gpu, int level, int x, int z, PaintPalette pal) {
        Baked b = get(level, x, z);
        boolean fresh = b == null;
        int occ = occupiedChildren(level, x, z);
        if (fresh) {
            b = add(level, x, z);
            b.dirty = occ;
            Arrays.fill(b.builtAt, Long.MAX_VALUE);
        }
        // a quarter with no tile under it holds paper and nothing in it can change
        b.dirty &= occ;
        touch(b);
        List<MapGpu.TexQuad> quads = new ArrayList<>(4);
        int h = SIDE / 2;
        for (int q = 0; q < 4; q++) {
            if ((occ & 1 << q) == 0) continue;
            Baked c = get(level - 1, x * 2 + (q & 1), z * 2 + (q >> 1));
            if (c == null || (c.level == 0 ? !leafClean(c) : c.dirty != 0)) continue;
            int deficit = Math.max(0, (c.level == 0 ? 0 : c.maxDeficit()) - 1);
            if ((b.dirty & 1 << q) == 0 && deficit >= b.deficit[q]) continue;
            touch(c);
            float x0 = (q & 1) * h, y0 = (q >> 1) * h;
            quads.add(new MapGpu.TexQuad(c.target.view, x0, y0, x0 + h, y0 + h, 0, 1, 1, 0));
            b.dirty &= ~(1 << q);
            b.deficit[q] = (byte) deficit;
            b.builtAt[q] = builtAt(c);
        }
        if (quads.isEmpty() && !fresh) return false;
        gpu.quads(b.target, fresh ? MapGpu.clear(pal) : Optional.empty(), quads, null, false);
        mipBuilds++;
        dirtyAbove(level, x, z);
        return true;
    }

    /**
     * Makes a mip's evicted child again from the mip's picture of that quarter, texel for texel (nearest, so the copy
     * back down is exact): a level coarser than the child's own detail, and out of date in each of its quarters whose
     * tiles changed since that picture was made. Returns false if there was nothing to do.
     */
    boolean split(MapGpu gpu, int level, int x, int z, int q) {
        Baked b = get(level, x, z);
        int cx = x * 2 + (q & 1), cz = z * 2 + (q >> 1);
        if (b == null || level < 2 || get(level - 1, cx, cz) != null) return false;
        Baked c = add(level - 1, cx, cz);
        touch(b);
        touch(c);
        float u0 = (q & 1) * 0.5f, vTop = 1 - (q >> 1) * 0.5f;
        gpu.quads(c.target, Optional.empty(), List.of(new MapGpu.TexQuad(b.target.view, 0, 0, SIDE, SIDE, u0, vTop, u0 + 0.5f, vTop - 0.5f)), null, true);
        long made = b.builtAt[q];
        byte deficit = (byte) Math.min(Byte.MAX_VALUE, b.deficit[q] + 1);
        int occ = occupiedChildren(level - 1, cx, cz);
        for (int q2 = 0; q2 < 4; q2++) {
            // a quarter with no tile under it is paper, as fine as it gets
            if ((occ & 1 << q2) == 0) continue;
            c.deficit[q2] = deficit;
            c.builtAt[q2] = made;
            if (index.changedAt(level - 2, cx * 2 + (q2 & 1), cz * 2 + (q2 >> 1)) > made) c.dirty |= 1 << q2;
        }
        splits++;
        return true;
    }

    /** Lets go of the least recently used cells (none used this frame) until the cache is within its budget. */
    void evict() {
        if (bytes <= budget) return;
        List<Baked> all = new ArrayList<>();
        for (Long2ObjectOpenHashMap<Baked> m : levels) for (Baked b : m.values()) if (b.used < frame) all.add(b);
        all.sort((a, b) -> Long.compare(a.used, b.used));
        for (int i = 0; i < all.size() && bytes > budget; i++) remove(all.get(i));
    }

    private void remove(Baked b) {
        levels[b.level].remove(TileIndex.key(b.x, b.z));
        bytes -= b.target.bytes();
        b.target.close();
        evictions++;
    }

    /** For the selftests: lets every cell below a level go, as a small budget would. */
    void evictBelow(int level) {
        List<Baked> gone = new ArrayList<>();
        for (int k = 0; k < level; k++) gone.addAll(levels[k].values());
        for (Baked b : gone) remove(b);
    }

    /** Everything goes (the palette or contours changed, or the world unloaded). */
    void clear() {
        for (Long2ObjectOpenHashMap<Baked> m : levels) {
            for (Baked b : m.values()) b.target.close();
            m.clear();
        }
        bytes = 0;
    }

    @Override
    public void close() {
        clear();
    }
}
