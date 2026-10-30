package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceTile;
import com.meakaandre.mapmod.map.MapGrid;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The fog and the blend between mip levels, for the selftests (MM-5). First the mask on its own: it is the explored land
 * (captured and cleared of fog, both), its edge is soft with no chunk corner and wanders off the chunk grid, and two
 * cells' masks meet exactly. Then on the GPU, with a painter of its own over a 3x3 block of made-up tiles, stepped a few
 * frames at a time from the client tick: half the block explored settles, then more of it is explored, which makes the
 * masks again and paints a new picture but bakes nothing; then a change of level is blended over
 * {@link MapPainter#FADE_NANOS} (on a clock of its own) and settles, so the picture is shown again after. Render thread
 * only.
 */
public final class FogCheck implements AutoCloseable {
    /** The block of tiles. */
    private static final int T0 = 2000, N = 3;
    private static final int PATIENCE = 2000;

    private final TileIndex index;
    private final MapPainter painter;
    private final Long2ObjectOpenHashMap<long[]> fog = new Long2ObjectOpenHashMap<>();
    private final List<BakeCheck.Result> results = new ArrayList<>();
    private int phase, frames, settledFrames;
    private long bakesAt, buildsAt, paintedAt, fadeAt, now;
    private boolean sawMidFade;
    private boolean done;

    /** @param make a made-up tile at a position */
    public FogCheck(BiFunction<Integer, Integer, SurfaceTile> make) {
        MapPainter[] self = new MapPainter[1];
        index = new TileIndex((tx, tz) -> {
            if (self[0] != null) self[0].cache.tileChanged(tx, tz);
        });
        painter = new MapPainter(index, 64L * BakeCache.SIDE * BakeCache.SIDE * 4, null, fog::get);
        self[0] = painter;
        Long2ObjectOpenHashMap<SurfaceTile> world = new Long2ObjectOpenHashMap<>();
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
        // the west half of the block explored
        reveal(T0 * MapGrid.REGION_CHUNKS, (T0 + N) * MapGrid.REGION_CHUNKS - 1, T0 * MapGrid.REGION_CHUNKS, T0 * MapGrid.REGION_CHUNKS + N * MapGrid.REGION_CHUNKS / 2);
        MapPainter.clock = () -> now;
    }

    /** Clears the fog over a rectangle of chunks (z from z0 to z1, x from x0 to x1, inclusive) and tells the masks. */
    private void reveal(int z0, int z1, int x0, int x1) {
        for (int cz = z0; cz <= z1; cz++) {
            for (int cx = x0; cx <= x1; cx++) {
                long region = MapGrid.regionOfChunk(cx, cz);
                long[] bits = fog.computeIfAbsent(region, k -> new long[MapGrid.FOG_LONGS]);
                if (MapGrid.set(bits, MapGrid.fogBit(cx, cz))) painter.masks.changed(MapGrid.keyX(region), MapGrid.keyZ(region));
            }
        }
    }

    private void expect(String what, boolean ok, String detail) {
        results.add(new BakeCheck.Result("fog: " + what, ok, detail));
    }

    /** A frame over the block's middle tile at a level's distance (2^level blocks a pixel), 512 pixels square. */
    private void frame(int level) {
        double bpp = 1 << level;
        int px = BakeCache.SIDE;
        double mid = (T0 + N / 2.0) * BakeCache.SIDE;
        painter.paint(new MapPainter.Frame(px, px, mid - px * bpp / 2, mid - px * bpp / 2, bpp), PaintPalette.current(), PaintPalette.version(), false, true);
        frames++;
        settledFrames = painter.idle() ? settledFrames + 1 : 0;
    }

    private boolean settled() {
        return settledFrames >= 3;
    }

    /** Runs a few frames; returns the results once done, else null. */
    public @Nullable List<BakeCheck.Result> step() {
        if (done) return results;
        if (phase == 0) {
            masks();
            phase = 1;
            return null;
        }
        for (int i = 0; i < 4 && !done; i++) advance();
        return done ? results : null;
    }

    private void advance() {
        BakeCache c = painter.cache;
        switch (phase) {
            case 1 -> {
                frame(0);
                if (!settled() && frames < PATIENCE) return;
                expect("half the block explored settles, with its fog masks made", settled() && painter.masks.current(0, T0 + 1, T0 + 1),
                    frames + " frames, " + c.leafBakes + " bakes, " + painter.masks.builds + " masks made");
                bakesAt = c.leafBakes;
                buildsAt = painter.masks.builds;
                paintedAt = painter.painted;
                reveal(T0 * MapGrid.REGION_CHUNKS, (T0 + N) * MapGrid.REGION_CHUNKS - 1, T0 * MapGrid.REGION_CHUNKS, (T0 + N) * MapGrid.REGION_CHUNKS - 1);
                phase = 2;
                frames = settledFrames = 0;
            }
            case 2 -> {
                frame(0);
                if (!settled() && frames < PATIENCE) return;
                expect("more land may show (the fog source MapLock will feed): the masks are made again and a new picture painted, and nothing bakes",
                    settled() && c.leafBakes == bakesAt && painter.masks.builds > buildsAt && painter.painted > paintedAt
                        && painter.masks.current(0, T0 + 1, T0 + 1),
                    (c.leafBakes - bakesAt) + " bakes, " + (painter.masks.builds - buildsAt) + " masks made, " + (painter.painted - paintedAt) + " pictures painted");
                expect("once settled again, the picture is shown again", painter.lastReused, MapPainter.reuse ? "" : "reuse is off");
                // settle a level further out, then come back: the change of level blends
                phase = 3;
                frames = settledFrames = 0;
            }
            case 3 -> {
                frame(1);
                now += MapPainter.FADE_NANOS / 4;
                if (!settled() && frames < PATIENCE) return;
                fadeAt = painter.fadeFrames;
                sawMidFade = false;
                phase = 4;
                frames = settledFrames = 0;
            }
            case 4 -> {
                frame(0);
                if (painter.lastFading && painter.lastQuads > 1) sawMidFade = true;
                now += MapPainter.FADE_NANOS / 6;
                if (!settled() && frames < PATIENCE) return;
                long blended = painter.fadeFrames - fadeAt;
                expect("a change of level blends the old one out over about " + MapPainter.FADE_NANOS / 1_000_000 + " ms, then settles",
                    sawMidFade && blended >= 3 && blended <= 8 && !painter.lastFading && settled(), blended + " frames blended, mid-blend seen " + sawMidFade);
                frame(0);
                expect("after the blend the picture is shown again", painter.lastReused, "");
                done = true;
                close();
            }
            default -> done = true;
        }
    }

    /** The mask on its own, on the CPU. */
    private void masks() {
        float edge = PaintPalette.DEFAULT.fogEdge();
        // tile (0, 0) and its neighbours all captured; the fog cleared over a square of chunks 8 to 23 in tile (0, 0)
        Long2ObjectOpenHashMap<long[]> cov = new Long2ObjectOpenHashMap<>(), bits = new Long2ObjectOpenHashMap<>();
        long[] all = new long[SurfaceTile.AREA / 64];
        java.util.Arrays.fill(all, -1L);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                cov.put(TileIndex.key(dx, dz), all);
                bits.put(TileIndex.key(dx, dz), new long[MapGrid.FOG_LONGS]);
            }
        }
        long[] mine = bits.get(TileIndex.key(0, 0));
        for (int cz = 8; cz < 24; cz++) for (int cx = 8; cx < 24; cx++) MapGrid.set(mine, MapGrid.fogBit(cx, cz));
        byte[] m = TilePrep.fogMask(0, 0, 0, edge, cov, bits);
        int t = TilePrep.maskTexel(0);
        // texel i is centred on block (i - 0.5) * t
        java.util.function.IntBinaryOperator at = (bx, bz) -> m[(int) Math.round(bx / (double) t + 0.5) + (int) Math.round(bz / (double) t + 0.5) * FogMasks.SIZE] & 0xFF;
        boolean inside = at.applyAsInt(256, 256) == 255, outside = at.applyAsInt(40, 40) == 0 && at.applyAsInt(480, 256) == 0;
        expect("the mask is 255 well inside the explored land and 0 well outside it", inside && outside,
            "inside " + at.applyAsInt(256, 256) + ", outside " + at.applyAsInt(40, 40) + " and " + at.applyAsInt(480, 256));
        // the west edge (block x 128) along its middle, read as the GPU reads it (bilinear): where each row crosses half
        // way wanders off the chunk line
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int bz = 176; bz <= 336; bz += t) {
            double cross = -1;
            for (double bx = 64; bx <= 224; bx += 0.5) {
                if (read(m, bx, bz) >= 127.5) {
                    cross = bx;
                    break;
                }
            }
            lo = Math.min(lo, cross);
            hi = Math.max(hi, cross);
        }
        expect("the fog's edge wanders off the chunk grid: no straight run along a chunk line", lo > 0 && hi - lo >= 4,
            "half way crossed between block " + lo + " and " + hi + " along the edge at 128");
        // the corner (128, 128): a chunk corner would reach half way right at it, a soft edge only well inside
        double in = -1;
        for (double d = 0; d <= 64; d += 0.5) {
            if (read(m, 128 + d, 128 + d) >= 127.5) {
                in = d;
                break;
            }
        }
        expect("the fog's edge has no chunk corner: it rounds the corner of the explored square", in >= 8, "half way " + in + " blocks in along the diagonal");
        // the neighbouring cell to the east reads the same at the same world positions, so masks meet without a seam
        byte[] east = TilePrep.fogMask(0, 1, 0, edge, cov, bits);
        int worst = 0;
        for (int z = 0; z < FogMasks.SIZE; z++) worst = Math.max(worst, Math.abs((m[FogMasks.SIZE - 1 + z * FogMasks.SIZE] & 0xFF) - (east[1 + z * FogMasks.SIZE] & 0xFF)));
        expect("two cells' masks meet exactly", worst <= 1, "largest difference " + worst);
        // captured but not explored by the group, and explored but not captured: both fog
        Long2ObjectOpenHashMap<long[]> none = new Long2ObjectOpenHashMap<>();
        for (long k : cov.keySet()) none.put(k, new long[SurfaceTile.AREA / 64]);
        byte[] uncaptured = TilePrep.fogMask(0, 0, 0, edge, none, bits);
        expect("land the group explored but the client never captured stays under the fog", (uncaptured[33 + 33 * FogMasks.SIZE] & 0xFF) == 0,
            Integer.toString(uncaptured[33 + 33 * FogMasks.SIZE] & 0xFF));
    }

    /** A level-0 mask read bilinearly at a block position in its cell, 0 to 255. */
    private static double read(byte[] m, double bx, double bz) {
        double x = bx / TilePrep.maskTexel(0) + 0.5, z = bz / TilePrep.maskTexel(0) + 0.5;
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        int s = FogMasks.SIZE;
        double a = m[ix + iz * s] & 0xFF, b = m[ix + 1 + iz * s] & 0xFF, c = m[ix + (iz + 1) * s] & 0xFF, d = m[ix + 1 + (iz + 1) * s] & 0xFF;
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    @Override
    public void close() {
        MapPainter.clock = null;
        painter.close();
    }
}
