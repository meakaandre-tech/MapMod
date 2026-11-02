package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceTile;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Which blocks this client has captured, as the map last prepared them (MM-9; part of the fog masks before): each
 * tile's explored bits, kept while in the world, unlike the tiles' data, which the open map lets go once it has baked
 * them. The mob radar shows mobs only over captured land. Render thread only.
 */
final class Coverage {
    private final Long2ObjectOpenHashMap<long[]> bits = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap revisions = new Long2LongOpenHashMap();

    /** A tile's explored bits as prepared from data of this revision (an older one than held is ignored). */
    void set(int tx, int tz, long[] explored, long revision) {
        long k = TileIndex.key(tx, tz);
        if (revisions.containsKey(k) && revision <= revisions.get(k)) return;
        revisions.put(k, revision);
        bits.put(k, explored.clone());
    }

    boolean captured(int x, int z) {
        long[] c = bits.get(TileIndex.key(Math.floorDiv(x, BakeCache.SIDE), Math.floorDiv(z, BakeCache.SIDE)));
        if (c == null) return false;
        int i = SurfaceTile.indexOfBlock(x, z);
        return (c[i >>> 6] & 1L << i) != 0;
    }

    /** For the selftests: the explored bits kept for a tile, or null. */
    long @Nullable [] of(int tx, int tz) {
        return bits.get(TileIndex.key(tx, tz));
    }

    int tiles() {
        return bits.size();
    }

    void clear() {
        bits.clear();
        revisions.clear();
    }
}
