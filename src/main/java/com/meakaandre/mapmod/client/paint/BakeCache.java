package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceTile;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Baked tiles and the mip pyramid over them, on the GPU, within a memory budget (MM-4). Level 0 holds tiles painted
 * once at one pixel a block ({@value #SIDE} square); level k holds 512-pixel cells 2^k tiles across, each made from the
 * four cells of the level below drawn into its quarters with linear filtering. A baked tile is out of date when the
 * revisions it was made from ({@link TileIndex#key3x3}) are not the index's any more; a mip keeps one dirty bit per
 * quarter, set when that child is re-made or when a tile under it changes, and cleared when the quarter is copied from
 * a child that is itself up to date. Least recently used cells go first when the budget is exceeded; they are made again
 * when next wanted. Render thread only.
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
        long used;

        Baked(int level, int x, int z, MapGpu.Target target) {
            this.level = level;
            this.x = x;
            this.z = z;
            this.target = target;
        }
    }

    private final TileIndex index;
    @SuppressWarnings("unchecked")
    private final Long2ObjectOpenHashMap<Baked>[] levels = new Long2ObjectOpenHashMap[LEVELS];
    private long bytes, budget;
    private long frame;
    /** Counted since the cache was made. */
    long leafBakes, mipBuilds, evictions;

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

    boolean clean(Baked b) {
        return b.level == 0 ? b.key != 0 && b.key == index.key3x3(b.x, b.z) : b.dirty == 0;
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
        leafBakes++;
        dirtyAbove(0, b.x, b.z);
    }

    private Baked add(int level, int x, int z) {
        Baked b = new Baked(level, x, z, new MapGpu.Target("map " + (level == 0 ? "tile " : "mip " + level + " ") + x + "," + z, SIDE, SIDE));
        levels[level].put(TileIndex.key(x, z), b);
        bytes += b.target.bytes();
        return b;
    }

    /**
     * What wants making for a cell to be up to date, depth first: tiles to bake go to {@code jobs.leaf}, mips with at
     * least one quarter ready to copy to {@code jobs.mip}. Children are visited before their parent, so the work found
     * first is the work that unblocks the rest.
     */
    void need(int level, int x, int z, Jobs jobs) {
        if (jobs.full() || !index.occupied(level, x, z)) return;
        jobs.visits++;
        Baked b = get(level, x, z);
        if (b != null) touch(b);
        if (level == 0) {
            if (b == null || !clean(b)) jobs.leaf(x, z);
            return;
        }
        if (b != null && b.dirty == 0) return;
        int bits = b == null ? occupiedChildren(level, x, z) : b.dirty;
        boolean ready = false;
        for (int q = 0; q < 4; q++) {
            if ((bits & 1 << q) == 0) continue;
            int cx = x * 2 + (q & 1), cz = z * 2 + (q >> 1);
            Baked c = get(level - 1, cx, cz);
            if (c != null && clean(c)) {
                ready = true;
                touch(c);
            } else {
                need(level - 1, cx, cz, jobs);
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
    }

    /**
     * Copies a mip's ready quarters in (made, and cleared to paper, if it is new); the mips over it take it again.
     * Returns false if there was nothing ready after all.
     */
    boolean buildMip(MapGpu gpu, int level, int x, int z, PaintPalette pal) {
        Baked b = get(level, x, z);
        boolean fresh = b == null;
        if (fresh) {
            b = add(level, x, z);
            b.dirty = occupiedChildren(level, x, z);
        }
        touch(b);
        List<MapGpu.TexQuad> quads = new ArrayList<>(4);
        int h = SIDE / 2;
        for (int q = 0; q < 4; q++) {
            if ((b.dirty & 1 << q) == 0) continue;
            Baked c = get(level - 1, x * 2 + (q & 1), z * 2 + (q >> 1));
            if (c == null || !clean(c)) continue;
            touch(c);
            float x0 = (q & 1) * h, y0 = (q >> 1) * h;
            quads.add(new MapGpu.TexQuad(c.target.view, x0, y0, x0 + h, y0 + h, 0, 1, 1, 0));
            b.dirty &= ~(1 << q);
        }
        if (quads.isEmpty() && !fresh) return false;
        gpu.quads(b.target, fresh ? MapGpu.clear(pal) : Optional.empty(), quads, null);
        mipBuilds++;
        dirtyAbove(level, x, z);
        return true;
    }

    /** Lets go of the least recently used cells (none used this frame) until the cache is within its budget. */
    void evict() {
        if (bytes <= budget) return;
        List<Baked> all = new ArrayList<>();
        for (Long2ObjectOpenHashMap<Baked> m : levels) for (Baked b : m.values()) if (b.used < frame) all.add(b);
        all.sort((a, b) -> Long.compare(a.used, b.used));
        for (int i = 0; i < all.size() && bytes > budget; i++) {
            Baked b = all.get(i);
            levels[b.level].remove(TileIndex.key(b.x, b.z));
            bytes -= b.target.bytes();
            b.target.close();
            evictions++;
        }
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
