package com.meakaandre.mapmod.client.paint;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Which tiles of the world exist and the revision of their latest data, without holding the data itself (MM-4): read
 * from the tile folder's listing (a file's revision is its modification time), the capture's held tiles and its change
 * events, and the data the map reads. Also which cells of each mip level hold any tile, so far zoom only looks at
 * explored ground. Render thread only.
 */
final class TileIndex {
    /** Mip levels kept: level k cells are 2^k tiles across. */
    static final int LEVELS = 13;

    /** Told when a tile's revision changes (or it appears). */
    interface Listener {
        void changed(int tx, int tz);
    }

    private final Long2LongOpenHashMap versions = new Long2LongOpenHashMap();
    private final LongOpenHashSet[] occupied = new LongOpenHashSet[LEVELS];
    /**
     * MM-5: per level, the clock at which anything a cell's bakes are made from last changed (a tile and the eight
     * whose aprons it is in), so a mip re-made from its parent's picture knows which of its quarters are out of date.
     */
    private final Long2LongOpenHashMap[] changed = new Long2LongOpenHashMap[LEVELS];
    /** Counts changes; 0 is "never". */
    private long clock;
    private final Listener listener;

    TileIndex(Listener listener) {
        this.listener = listener;
        for (int k = 0; k < LEVELS; k++) {
            occupied[k] = new LongOpenHashSet();
            changed[k] = new Long2LongOpenHashMap();
        }
    }

    /** The change clock now: a picture made now is up to date as of this. */
    long clock() {
        return clock;
    }

    /** The clock of the last change to anything the cell's bakes are made from (0 if none since the index was made). */
    long changedAt(int level, int x, int z) {
        return changed[level].get(key(x, z));
    }

    static long key(int x, int z) {
        return (long) x << 32 | z & 0xFFFFFFFFL;
    }

    /** The tile's latest revision, 0 where there is no tile. */
    long version(int tx, int tz) {
        return versions.get(key(tx, tz));
    }

    /** Sets a tile's revision (0 is "none" and is ignored); the listener hears of a change. */
    void set(int tx, int tz, long revision) {
        if (revision == 0) return;
        long k = key(tx, tz);
        long old = versions.put(k, revision);
        if (old == revision) return;
        if (old == 0) {
            for (int lv = 0; lv < LEVELS; lv++) occupied[lv].add(key(tx >> lv, tz >> lv));
        }
        clock++;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int lv = 0; lv < LEVELS; lv++) changed[lv].put(key((tx + dx) >> lv, (tz + dz) >> lv), clock);
            }
        }
        listener.changed(tx, tz);
    }

    /** True if the level's cell holds any tile. */
    boolean occupied(int level, int x, int z) {
        return occupied[level].contains(key(x, z));
    }

    int size() {
        return versions.size();
    }

    /**
     * One number from the revisions of a tile and its eight neighbours: what a bake of it is made from. It changes when
     * any of them does.
     */
    long key3x3(int tx, int tz) {
        long h = 1469598103934665603L;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) h = (h ^ version(tx + dx, tz + dz)) * 1099511628211L;
        }
        return h;
    }

    /** The same number from nine revisions, {@code r[(dz + 1) * 3 + dx + 1]}. */
    static long key3x3(long[] r) {
        long h = 1469598103934665603L;
        for (long v : r) h = (h ^ v) * 1099511628211L;
        return h;
    }
}
