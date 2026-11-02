package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceTile;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * The per-block picture on the GPU, for the selftests (MM-9; the fog check before): a painter of its own over a 3x3
 * block of made-up tiles with whole chunks and single blocks left unexplored in a pattern, stepped a few frames at a time from the client
 * tick, the picture it draws read back from the GPU each time it settles:
 * <ul>
 *   <li>close up (half a block a pixel): every pixel is its block's prepared colour exactly (read nearest: blocks are
 *   crisp squares), and unexplored blocks are the background exactly, so the edge is the chunks' hard edge;</li>
 *   <li>far out (4 blocks a pixel, level 2): every pixel is the mean of the 4x4 blocks under it laid over the background
 *   (premultiplied mips: unexplored blocks average in as background), within rounding;</li>
 *   <li>then a level closer, which blends the old level out and settles.</li>
 * </ul>
 * A read-back's rows run bottom to top on some backends and top to bottom on others: both are tried, and the one that
 * matches is reported. Render thread only.
 */
public final class EdgeCheck implements AutoCloseable {
    private static final int T0 = 2000, N = 3;
    private static final int PATIENCE = 2000;

    private final TileIndex index;
    private final MapPainter painter;
    private final Long2ObjectOpenHashMap<SurfaceTile> world = new Long2ObjectOpenHashMap<>();
    private final List<BakeCheck.Result> results = new ArrayList<>();
    private int phase, frames, settledFrames;
    private long fadeAt;
    private boolean done;
    private MapGpu.@Nullable Target picture;
    private @Nullable CompletableFuture<int[]> reading;
    private final PaintPalette pal = PaintPalette.current();

    /** @param make a made-up tile at a position */
    public EdgeCheck(BiFunction<Integer, Integer, SurfaceTile> make) {
        MapPainter[] self = new MapPainter[1];
        index = new TileIndex((tx, tz) -> {
            if (self[0] != null) self[0].cache.tileChanged(tx, tz);
        });
        painter = new MapPainter(index, 64L * 1024 * 1024, null);
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
                for (int i = 0; i < SurfaceTile.AREA; i++) {
                    int bx = x * SurfaceTile.BLOCKS + i % SurfaceTile.SIDE, bz = z * SurfaceTile.BLOCKS + i / SurfaceTile.SIDE;
                    // whole chunks, as the capture leaves them, and a sprinkle of single blocks, so mip texels hold part
                    // land and part nothing (their averaging must stay premultiplied)
                    if (Math.floorMod((bx >> 4) * 7 + (bz >> 4) * 3, 5) == 0 || Math.floorMod(bx * 3 + bz * 5, 7) == 0) t.explored[i >>> 6] &= ~(1L << i);
                }
                t.revision = 1;
                world.put(TileIndex.key(x, z), t);
                index.set(x, z, 1);
            }
        }
    }

    public @Nullable List<BakeCheck.Result> step() {
        if (done) return results;
        for (int i = 0; i < 4 && !done; i++) advance();
        return done ? results : null;
    }

    /** The middle tile's top-left corner, and a view of 512 pixels at this many blocks a pixel centred on it. */
    private void frame(double bpp) {
        double mid = (T0 + 1.5) * SurfaceTile.BLOCKS, half = 256 * bpp;
        picture = painter.paint(new MapPainter.Frame(512, 512, mid - half, mid - half, bpp), pal, PaintPalette.version(), true);
        frames++;
        settledFrames = painter.idle() ? settledFrames + 1 : 0;
    }

    private void advance() {
        switch (phase) {
            case 0, 2 -> {
                double bpp = phase == 0 ? 0.5 : 4;
                frame(bpp);
                if (settledFrames < 3 && frames < PATIENCE) return;
                expect("the picture settles " + (phase == 0 ? "close up" : "far out"), settledFrames >= 3, frames + " frames: " + painter.busy());
                reading = picture == null ? null : MapGpu.readBack(picture);
                phase++;
            }
            case 1, 3 -> {
                frame(phase == 1 ? 0.5 : 4);
                if (reading == null || !reading.isDone()) {
                    if (frames > PATIENCE * 2) {
                        expect("the picture is read back from the GPU", false, "nothing came back");
                        phase = 4;
                    }
                    return;
                }
                int[] px = reading.isCompletedExceptionally() ? null : reading.getNow(null);
                if (phase == 1) close(px);
                else far(px);
                reading = null;
                frames = 0;
                phase++;
                if (phase == 4) fadeAt = painter.fadeFrames;
            }
            case 4 -> {
                // a level closer: the old level blends out, then it settles again
                frame(2);
                if ((settledFrames < 3 || painter.fadeFrames == fadeAt) && frames < PATIENCE) return;
                expect("changing level blends the old one out over a few frames, then settles", painter.fadeFrames > fadeAt && settledFrames >= 3,
                    (painter.fadeFrames - fadeAt) + " blended frames, " + frames + " frames");
                done = true;
            }
            default -> done = true;
        }
    }

    /** The colour a block was prepared in (premultiplied ARGB), or 0 if unexplored. */
    private int block(int bx, int bz) {
        int tx = Math.floorDiv(bx, SurfaceTile.BLOCKS), tz = Math.floorDiv(bz, SurfaceTile.BLOCKS);
        SurfaceTile[][] around = new SurfaceTile[3][3];
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) around[dz + 1][dx + 1] = world.get(TileIndex.key(tx + dx, tz + dz));
        if (around[1][1] == null) return 0;
        long k = TileIndex.key(tx, tz);
        TilePrep.Result r = prepared.get(k);
        if (r == null) prepared.put(k, r = TilePrep.prepare(around, pal));
        return r.pixel(Math.floorMod(bx, SurfaceTile.BLOCKS), Math.floorMod(bz, SurfaceTile.BLOCKS));
    }

    private final Long2ObjectOpenHashMap<TilePrep.Result> prepared = new Long2ObjectOpenHashMap<>();

    /** A premultiplied colour laid over the background, as the screen does. */
    private int over(int r, int g, int b, int a) {
        int bg = pal.background();
        float k = 1 - a / 255f;
        return Math.clamp(Math.round(r + (bg >> 16 & 255) * k), 0, 255) << 16 | Math.clamp(Math.round(g + (bg >> 8 & 255) * k), 0, 255) << 8
            | Math.clamp(Math.round(b + (bg & 255) * k), 0, 255);
    }

    /** Pixel x, y (y down) of a read-back, its rows bottom to top or top to bottom. */
    private static int at(int[] px, int x, int y, boolean flipped) {
        return px[x + (flipped ? 511 - y : y) * 512] & 0xFFFFFF;
    }

    private void close(int @Nullable [] px) {
        if (px == null || px.length != 512 * 512) {
            expect("close up, every pixel is its block's colour and unexplored land the background", false, "no read-back");
            return;
        }
        double bpp = 0.5, x0 = (T0 + 1.5) * SurfaceTile.BLOCKS - 256 * bpp;
        int[] wrong = new int[2], unexplored = new int[2], n = {0};
        String[] first = new String[2];
        for (int y = 1; y < 512; y += 3) {
            for (int x = 1; x < 512; x += 3) {
                int bx = (int) Math.floor(x0 + (x + 0.5) * bpp), bz = (int) Math.floor(x0 + (y + 0.5) * bpp);
                int c = block(bx, bz), want = c == 0 ? pal.background() : c & 0xFFFFFF;
                if (c == 0) unexplored[0]++;
                n[0]++;
                for (int f = 0; f < 2; f++) {
                    int got = at(px, x, y, f == 1);
                    if (got != want) {
                        wrong[f]++;
                        if (first[f] == null) first[f] = String.format(Locale.ROOT, "block %d,%d: #%06X, expected #%06X", bx, bz, got, want);
                    }
                }
            }
        }
        int f = wrong[1] < wrong[0] ? 1 : 0;
        expect("close up, every pixel is its block's colour exactly (crisp squares) and unexplored chunks the background exactly",
            wrong[f] == 0 && unexplored[0] > 0, wrong[f] + " of " + n[0] + " wrong" + (first[f] == null ? "" : ", first " + first[f]) + ", "
                + unexplored[0] + " unexplored; rows " + (f == 1 ? "bottom to top" : "top to bottom"));
    }

    private void far(int @Nullable [] px) {
        if (px == null || px.length != 512 * 512) {
            expect("far out, every pixel is the mean of the blocks under it", false, "no read-back");
            return;
        }
        double bpp = 4, x0 = (T0 + 1.5) * SurfaceTile.BLOCKS - 256 * bpp;
        int[] worst = new int[2], over = new int[2];
        int n = 0;
        for (int y = 2; y < 510; y += 7) {
            for (int x = 2; x < 510; x += 7) {
                int bx0 = (int) Math.floor(x0 + x * bpp), bz0 = (int) Math.floor(x0 + y * bpp);
                long r = 0, g = 0, b = 0, a = 0;
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int c = block(bx0 + dx, bz0 + dz);
                        r += c >> 16 & 255;
                        g += c >> 8 & 255;
                        b += c & 255;
                        a += c >>> 24;
                    }
                }
                int want = over((int) (r / 16), (int) (g / 16), (int) (b / 16), (int) (a / 16));
                n++;
                for (int f = 0; f < 2; f++) {
                    int got = at(px, x, y, f == 1);
                    int d = Math.max(Math.abs((got >> 16 & 255) - (want >> 16 & 255)), Math.max(Math.abs((got >> 8 & 255) - (want >> 8 & 255)),
                        Math.abs((got & 255) - (want & 255))));
                    worst[f] = Math.max(worst[f], d);
                    if (d > 4) over[f]++;
                }
            }
        }
        int f = over[1] < over[0] ? 1 : 0;
        expect("far out (level 2), every pixel is the mean of the 4x4 blocks under it over the background, within rounding",
            over[f] * 100 <= n, over[f] + " of " + n + " off by more than 4, " + worst[f] + " at most; rows " + (f == 1 ? "bottom to top" : "top to bottom"));
    }

    private void expect(String what, boolean ok, String detail) {
        results.add(new BakeCheck.Result("picture: " + what, ok, detail));
    }

    @Override
    public void close() {
        painter.close();
    }
}
