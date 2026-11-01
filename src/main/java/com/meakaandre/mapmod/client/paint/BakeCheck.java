package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceTile;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The bake cache end to end on the GPU, for the selftests (MM-4): a painter of its own over a 4x4 block of made-up
 * tiles, with a budget of {@value #BUDGET_TILES} cells so the pyramid cannot all stay, stepped a few frames at a time
 * from the client tick. It settles at far zoom, then one tile's revision changes (the mips over it go dirty, it and
 * its neighbours bake again, the mips are made again), then a zoom in to a tile that was evicted makes it bake again.
 * MM-5: then, with room enough, every cell under the top one is let go and a tile in the middle changes: the cells
 * under the top one are made again from its picture and only the changed tile and its neighbours bake, not all 16.
 * MM-8d: then a second block, one level-3 cell, with a corner's tiles not loaded at first: the quarter over them stays
 * dirty (never clean over paper) until they arrive, bake and are copied in; then, its children evicted and a changed
 * tile held back, it keeps its picture (never paper) until baked again. The mip is read back from the GPU to check its
 * quarters' colour. Render thread only.
 */
public final class BakeCheck implements AutoCloseable {
    /** The cache holds this many 512-pixel cells at most. */
    static final int BUDGET_TILES = 6;
    /** The block of tiles: 4x4 from here, so one level-2 cell holds them all. */
    private static final int T0 = 1000, N = 4;
    /** Frames before a phase gives up settling. */
    private static final int PATIENCE = 2000;

    public record Result(String what, boolean ok, String detail) {
    }

    private final TileIndex index;
    private final MapPainter painter;
    private final Long2ObjectOpenHashMap<SurfaceTile> world = new Long2ObjectOpenHashMap<>();
    private final BiFunction<Integer, Integer, SurfaceTile> make;
    private final List<Result> results = new ArrayList<>();
    private int phase, frames, settledFrames;
    private long maxOps, bakesAt, mipsAt, evictedAt, splitsAt, avoidedAt;
    private boolean done;
    /** MM-8d: the second block (8x8 tiles from here, one level-3 cell), and the tiles whose data is held back for now. */
    private static final int T1 = 3000, N1 = 8;
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet withheld = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    /** A read-back of the level-3 cell under way, and what the phase waiting on it checks. */
    private java.util.concurrent.@Nullable CompletableFuture<int[]> reading;

    /** @param make a made-up tile at a position (its revision is set here) */
    public BakeCheck(BiFunction<Integer, Integer, SurfaceTile> make) {
        this.make = make;
        MapPainter[] self = new MapPainter[1];
        index = new TileIndex((tx, tz) -> {
            if (self[0] != null) self[0].cache.tileChanged(tx, tz);
        });
        long[] clear = new long[com.meakaandre.mapmod.map.MapGrid.FOG_LONGS];
        java.util.Arrays.fill(clear, -1L);
        painter = new MapPainter(index, (long) BUDGET_TILES * BakeCache.SIDE * BakeCache.SIDE * 4, null, region -> clear);
        self[0] = painter;
        painter.provider(new MapPainter.Provider() {
            @Override
            public void update(long frame) {
            }

            @Override
            public PaintTiles.@Nullable Around around(int tx, int tz) {
                // a tile whose data (or a neighbour's) is not loaded yet is not ready, as the map's own tiles are not
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) if (withheld.contains(TileIndex.key(tx + dx, tz + dz))) return null;
                SurfaceTile[][] tiles = new SurfaceTile[3][3];
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) tiles[dz + 1][dx + 1] = world.get(TileIndex.key(tx + dx, tz + dz));
                return new PaintTiles.Around(tiles, index.key3x3(tx, tz));
            }

            @Override
            public void trim() {
            }
        });
        for (int z = T0; z < T0 + N; z++) {
            for (int x = T0; x < T0 + N; x++) {
                SurfaceTile t = make.apply(x, z);
                t.revision = 1;
                world.put(TileIndex.key(x, z), t);
                index.set(x, z, 1);
            }
        }
    }

    /** A frame looking at the block from a level's distance: level 2 shows it all, level 0 one tile at a pixel a block. */
    private void frame(int level, int tx, int tz) {
        double bpp = 1 << level;
        int px = BakeCache.SIDE;
        painter.paint(new MapPainter.Frame(px, px, tx * (double) BakeCache.SIDE, tz * (double) BakeCache.SIDE, bpp),
            PaintPalette.current(), PaintPalette.version(), false, true);
        maxOps = Math.max(maxOps, painter.lastOps);
        frames++;
        settledFrames = painter.idle() ? settledFrames + 1 : 0;
    }

    private void expect(String what, boolean ok, String detail) {
        results.add(new Result("bake cache: " + what, ok, detail));
    }

    private boolean settled() {
        return settledFrames >= 3;
    }

    /** Runs a few frames; returns the results once done, else null. */
    public @Nullable List<Result> step() {
        if (done) return results;
        for (int i = 0; i < 4 && !done; i++) advance();
        return done ? results : null;
    }

    private void advance() {
        BakeCache c = painter.cache;
        int cell = T0 >> 2;
        switch (phase) {
            case 0 -> {
                frame(2, T0, T0);
                if (!settled() && frames < PATIENCE) return;
                BakeCache.Baked top = c.get(2, cell, cell);
                expect("a far view settles with its level-2 cell made and up to date", top != null && c.clean(top),
                    frames + " frames, " + c.leafBakes + " bakes, " + c.mipBuilds + " mip copies");
                expect("every tile under it was baked once", c.leafBakes == N * N, c.leafBakes + " bakes for " + N * N + " tiles");
                expect("the cache stays within its budget, letting the least recently used go",
                    c.evictions > 0 && c.bytes() <= c.budget(), c.evictions + " evicted, " + c.bytes() / 1048576 + " MB of " + c.budget() / 1048576);
                expect("at most " + MapPainter.OPS + " uploads, bakes and mip copies a frame", maxOps <= MapPainter.OPS, maxOps + " at most");
                // one tile changes
                long k = TileIndex.key(T0 + 1, T0 + 1);
                SurfaceTile t = world.get(k);
                for (int i = 0; i < SurfaceTile.AREA; i += 3) t.height[i] += 9;
                t.revision = 2;
                bakesAt = c.leafBakes;
                mipsAt = c.mipBuilds;
                index.set(T0 + 1, T0 + 1, 2);
                BakeCache.Baked again = c.get(2, cell, cell);
                expect("a tile's new revision marks the mip over it dirty", again != null && again.dirty != 0 && !c.clean(again),
                    again == null ? "no mip" : "dirty quarters " + Integer.toBinaryString(again.dirty));
                phase = 1;
                frames = settledFrames = 0;
            }
            case 1 -> {
                frame(2, T0, T0);
                if (!settled() && frames < PATIENCE) return;
                BakeCache.Baked top = c.get(2, cell, cell);
                long rebaked = c.leafBakes - bakesAt;
                expect("the changed tile and its neighbours bake again, and the mip over them is made again",
                    rebaked >= 9 && c.mipBuilds > mipsAt && top != null && c.clean(top), rebaked + " bakes, " + (c.mipBuilds - mipsAt) + " mip copies");
                evictedAt = c.evictions;
                // zoom in on a corner tile: by now it has been let go
                BakeCache.Baked corner = c.get(0, T0, T0);
                expect("a tile baked at the start has been evicted since", corner == null, corner == null ? "" : "still held");
                bakesAt = c.leafBakes;
                phase = 2;
                frames = settledFrames = 0;
            }
            case 2 -> {
                frame(0, T0, T0);
                if (!settled() && frames < PATIENCE) return;
                BakeCache.Baked corner = c.get(0, T0, T0);
                expect("an evicted tile is baked again when it is wanted", corner != null && c.clean(corner) && c.leafBakes > bakesAt,
                    (c.leafBakes - bakesAt) + " bakes, " + (c.evictions - evictedAt) + " more evicted");
                // settle the far view again with room for everything, then let every cell under the top one go
                c.budget(64L * BakeCache.SIDE * BakeCache.SIDE * 4);
                phase = 3;
                frames = settledFrames = 0;
            }
            case 3 -> {
                frame(2, T0, T0);
                if (!settled() && frames < PATIENCE) return;
                c.evictBelow(2);
                long k = TileIndex.key(T0 + 1, T0 + 1);
                SurfaceTile t = world.get(k);
                for (int i = 0; i < SurfaceTile.AREA; i += 5) t.height[i] -= 7;
                t.revision = 3;
                bakesAt = c.leafBakes;
                splitsAt = c.splits;
                index.set(T0 + 1, T0 + 1, 3);
                phase = 4;
                frames = settledFrames = 0;
            }
            case 4 -> {
                frame(2, T0, T0);
                if (!settled() && frames < PATIENCE) return;
                BakeCache.Baked top = c.get(2, cell, cell);
                long rebaked = c.leafBakes - bakesAt, split = c.splits - splitsAt;
                expect("with the cells under a mip evicted, a changed tile makes them again from the mip's picture: only it and its neighbours bake",
                    top != null && c.clean(top) && rebaked == 9 && split == 4, rebaked + " bakes (16 would be the whole subtree), " + split + " cells made from their parent");
                // MM-8d: a second block, one level-3 cell, with the data of a corner's 2x2 tiles not loaded yet: the
                // level-2 quarter over them cannot be made at all while the other three can
                for (int z = T1; z < T1 + N1; z++) {
                    for (int x = T1; x < T1 + N1; x++) {
                        SurfaceTile t2 = make.apply(x, z);
                        t2.revision = 1;
                        world.put(TileIndex.key(x, z), t2);
                    }
                }
                for (int z = T1 + 6; z < T1 + 8; z++) for (int x = T1 + 6; x < T1 + 8; x++) withheld.add(TileIndex.key(x, z));
                for (int z = T1; z < T1 + N1; z++) for (int x = T1; x < T1 + N1; x++) index.set(x, z, 1);
                avoidedAt = c.emptySplitsAvoided;
                phase = 5;
                frames = settledFrames = 0;
            }
            case 5 -> {
                // built as far as it can be: the three quarters with all their tiles there copied in
                frame3();
                BakeCache.Baked top = c.get(3, T1 >> 3, T1 >> 3);
                boolean three = top != null && Integer.bitCount(top.dirty) == 1;
                if ((!three || frames < 300) && frames < PATIENCE) return;
                expect("a mip built while a child's tiles are missing keeps that quarter dirty: never clean over paper",
                    top != null && !c.clean(top) && Integer.bitCount(top.dirty) == 1 && c.emptySplitsAvoided > avoidedAt,
                    top == null ? "no level-3 cell after " + frames + " frames" : "dirty quarters " + Integer.toBinaryString(top.dirty) + ", missing children made from below "
                        + (c.emptySplitsAvoided - avoidedAt) + " times, " + frames + " frames");
                if (top != null) reading = MapGpu.readBack(top.target);
                phase = 6;
                frames = 0;
            }
            case 6 -> {
                frame3();
                if (reading != null && !reading.isDone() && frames < PATIENCE) return;
                int[] q = quarters(reading);
                expect("while the tiles are missing the mip shows paper in that quarter only, its other quarters real colour",
                    q != null && count(q, 2) == 1 && count(q, 0) == 3, describe(q));
                // the tiles' data arrives (as a tile read from disk does): the quarter is made and copied in
                withheld.clear();
                mipsAt = c.mipBuilds;
                bakesAt = c.leafBakes;
                reading = null;
                phase = 7;
                frames = settledFrames = 0;
            }
            case 7 -> {
                frame3();
                if (!settled() && frames < PATIENCE) return;
                BakeCache.Baked top = c.get(3, T1 >> 3, T1 >> 3);
                expect("once the missing tiles arrive they bake and the mip is made again; the view settles with nothing dirty",
                    top != null && c.clean(top) && c.mipBuilds > mipsAt && c.leafBakes > bakesAt && settled(),
                    (c.leafBakes - bakesAt) + " bakes, " + (c.mipBuilds - mipsAt) + " mip copies, " + frames + " frames");
                if (top != null) reading = MapGpu.readBack(top.target);
                phase = 8;
                frames = 0;
            }
            case 8 -> {
                frame3();
                if (reading != null && !reading.isDone() && frames < PATIENCE) return;
                int[] q = quarters(reading);
                expect("the rebuilt mip holds real colour in every quarter, the one that was missing too", q != null && count(q, 0) == 4, describe(q));
                // evicted and baked again: every cell under the level-3 one goes, a tile in the corner changes and its
                // data is held back a while; the level-3 cell keeps its picture (never paper) until it is baked again
                c.evictBelow(3);
                long k = TileIndex.key(T1 + 6, T1 + 6);
                SurfaceTile t = world.get(k);
                for (int i = 0; i < SurfaceTile.AREA; i += 4) t.height[i] += 11;
                t.revision = 2;
                withheld.add(k);
                index.set(T1 + 6, T1 + 6, 2);
                splitsAt = c.splits;
                bakesAt = c.leafBakes;
                reading = null;
                phase = 9;
                frames = settledFrames = 0;
            }
            case 9 -> {
                frame3();
                if (frames < 300 && frames < PATIENCE) return;
                BakeCache.Baked top = c.get(3, T1 >> 3, T1 >> 3);
                expect("with its children evicted and a changed tile not loaded yet, the mip over it stays dirty and its children are made again from its picture",
                    top != null && !c.clean(top) && c.splits > splitsAt, top == null ? "gone" : "dirty quarters " + Integer.toBinaryString(top.dirty) + ", "
                        + (c.splits - splitsAt) + " cells made from their parent");
                if (top != null) reading = MapGpu.readBack(top.target);
                phase = 10;
                frames = 0;
            }
            case 10 -> {
                frame3();
                if (reading != null && !reading.isDone() && frames < PATIENCE) return;
                int[] q = quarters(reading);
                expect("meanwhile it still shows real colour everywhere: an evicted child never turns into paper", q != null && count(q, 0) == 4, describe(q));
                withheld.clear();
                reading = null;
                phase = 11;
                frames = settledFrames = 0;
            }
            case 11 -> {
                frame3();
                if (!settled() && frames < PATIENCE) return;
                BakeCache.Baked top = c.get(3, T1 >> 3, T1 >> 3);
                expect("once the changed tile arrives it bakes and the mip is made again, clean", top != null && c.clean(top) && c.leafBakes > bakesAt && settled(),
                    (c.leafBakes - bakesAt) + " bakes, " + frames + " frames");
                if (top != null) reading = MapGpu.readBack(top.target);
                phase = 12;
                frames = 0;
            }
            case 12 -> {
                frame3();
                if (reading != null && !reading.isDone() && frames < PATIENCE) return;
                int[] q = quarters(reading);
                expect("re-baked after eviction, the mip holds real colour in every quarter", q != null && count(q, 0) == 4, describe(q));
                done = true;
                close();
            }
            default -> done = true;
        }
    }

    /** A frame over the second block from level 3's distance (8 blocks a pixel: the whole level-3 cell). */
    private void frame3() {
        frame(3, T1, T1);
    }

    /** Per quarter of a 512-pixel read-back: 0 real colour, 2 paper, 1 a mix; null if there is none. */
    private static int @Nullable [] quarters(java.util.concurrent.@Nullable CompletableFuture<int[]> f) {
        if (f == null || !f.isDone() || f.isCompletedExceptionally()) return null;
        int[] px = f.getNow(null);
        if (px == null || px.length != BakeCache.SIDE * BakeCache.SIDE) return null;
        int paper = PaintPalette.current().paper(), h = BakeCache.SIDE / 2;
        int[] out = new int[4];
        for (int q = 0; q < 4; q++) {
            int papery = 0, n = 0;
            for (int y = (q >> 1) * h + 8; y < (q >> 1) * h + h - 8; y += 4) {
                for (int x = (q & 1) * h + 8; x < (q & 1) * h + h - 8; x += 4) {
                    int c = px[x + y * BakeCache.SIDE];
                    n++;
                    if (Math.abs((c >> 16 & 255) - (paper >> 16 & 255)) <= 10 && Math.abs((c >> 8 & 255) - (paper >> 8 & 255)) <= 10
                        && Math.abs((c & 255) - (paper & 255)) <= 10) papery++;
                }
            }
            out[q] = papery * 10 < n * 2 ? 0 : papery * 10 > n * 9 ? 2 : 1;
        }
        return out;
    }

    private static int count(int @Nullable [] q, int v) {
        if (q == null) return -1;
        int n = 0;
        for (int x : q) if (x == v) n++;
        return n;
    }

    private static String describe(int @Nullable [] q) {
        if (q == null) return "no read-back";
        StringBuilder b = new StringBuilder("quarters:");
        for (int x : q) b.append(x == 0 ? " colour" : x == 2 ? " paper" : " mixed");
        return b.toString();
    }

    @Override
    public void close() {
        painter.close();
    }
}
