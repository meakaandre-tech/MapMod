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
 * Render thread only.
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
    private long maxOps, bakesAt, mipsAt, evictedAt;
    private boolean done;

    /** @param make a made-up tile at a position (its revision is set here) */
    public BakeCheck(BiFunction<Integer, Integer, SurfaceTile> make) {
        this.make = make;
        MapPainter[] self = new MapPainter[1];
        index = new TileIndex((tx, tz) -> {
            if (self[0] != null) self[0].cache.tileChanged(tx, tz);
        });
        painter = new MapPainter(index, (long) BUDGET_TILES * BakeCache.SIDE * BakeCache.SIDE * 4, null);
        self[0] = painter;
        painter.provider(new MapPainter.Provider() {
            @Override
            public void update(long frame) {
            }

            @Override
            public PaintTiles.@Nullable Around around(int tx, int tz) {
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
                done = true;
                close();
            }
            default -> done = true;
        }
    }

    @Override
    public void close() {
        painter.close();
    }
}
