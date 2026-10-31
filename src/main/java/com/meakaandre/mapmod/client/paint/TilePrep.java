package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceClass;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import com.meakaandre.mapmod.map.MapGrid;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.jspecify.annotations.Nullable;

/**
 * Works out one tile's GPU textures from the tile and its eight neighbours, on a background thread (MM-3): the render
 * thread only copies the result into the textures ({@link TileTextures#upload}). Each texture is {@link #SIZE} square:
 * the tile's 256x256 samples plus an apron of {@link #APRON} samples from the neighbours on every side, wide enough for
 * the shader's warp and cubic sampling, so tiles meet without a seam. Pixels are ARGB, as {@code NativeImage.setPixel}
 * takes them:
 * <pre>
 *   fields   r, g  height + 32768, high and low byte   b  water depth (blocks)   a  255 explored, 0 not
 *   surface  rgb   the land's painted colour (MM-8)    a  canopy density
 *   effects  r     blurred share of forest floor       g  blurred share of snow   (of the land round it, 0-255)
 *   water    rgb   the water's own colour (its biome's) a  255 on water, 0 elsewhere
 *   shore    r     signed distance to the shore in samples, 128 + 4 * d (water positive, land negative, ±31 at most)
 * </pre>
 * Everything is computed over a wider window ({@link #PAD} more samples on each side) and cropped, so the blur, the
 * brush and the distance field are the same in a tile's apron as in the neighbour it copies.
 * <p>
 * MM-8, the land's colour: each sample's real block colour ({@link SurfaceTile#colour}, or its class's colour where a
 * palette overrides it), pulled toward a muted painted palette ({@link PaintPalette#paint}), then run through a
 * Kuwahara filter: of eight windows round the sample (four quarters and four half-sides, each
 * {@code colour.brush_size} blocks across) the flattest ones win, so mixed, speckled ground settles into soft patches
 * of one colour while the border between two colours stays where it is and stays sharp. It runs here, once per tile on
 * the prep thread, with summed-area tables (a few milliseconds a tile): bakes and live paint both read its result, and
 * no shader pass pays for it.
 */
public final class TilePrep {
    public static final int APRON = 6, SIZE = SurfaceTile.SIDE + 2 * APRON;
    /** Distance field cap (samples): far enough for the deep-water gradient (62 blocks). */
    public static final int SHORE_CAP = 31;
    /** Samples beyond the apron that the blur, the brush and the distance field look at. */
    static final int PAD = SHORE_CAP;
    static final int GRID = SIZE + 2 * PAD;
    /** The brush's windows are this many samples across at most. */
    static final int MAX_BRUSH = 8;
    /** Tent blur of the effect weights: 1 2 3 2 1. */
    private static final int[] BLUR = {1, 2, 3, 2, 1};
    /** Rounds of spreading known heights into unexplored samples. */
    private static final int SPREAD = 3;
    /** Height for unexplored samples nothing known is near (they are paper anyway). */
    private static final int NO_HEIGHT = 63;
    private static final float FAR = 1e9f;

    public static final int FIELDS = 0, SURFACE = 1, EFFECTS = 2, WATER = 3, SHORE = 4, TEXTURES = 5;

    private TilePrep() {
    }

    /** The textures' pixels, ARGB, {@code pixels[texture][x + z * SIZE]}, and how long the brush took. */
    public record Result(int[][] pixels, long brushNanos) {
        public int pixel(int texture, int ax, int az) {
            return pixels[texture][ax + az * SIZE];
        }
    }

    /** Works out a tile's textures with the palette in use now. */
    public static Result prepare(@Nullable SurfaceTile[][] around) {
        return prepare(around, PaintPalette.current());
    }

    /** Works out a tile's textures from it and its neighbours ({@code around[dz + 1][dx + 1]}, null where none). */
    public static Result prepare(@Nullable SurfaceTile[][] around, PaintPalette pal) {
        int n = GRID * GRID;
        boolean[] known = new boolean[n], wet = new boolean[n];
        int[] height = new int[n];
        byte[] cls = new byte[n];
        // the land's colour, r g b in 0-1, after the palette's pull (only where land[g])
        float[] colour = new float[n * 3];
        for (int gz = 0; gz < GRID; gz++) {
            int sz = gz - PAD - APRON, dz = Math.floorDiv(sz, SurfaceTile.SIDE), lz = Math.floorMod(sz, SurfaceTile.SIDE);
            for (int gx = 0; gx < GRID; gx++) {
                int sx = gx - PAD - APRON, dx = Math.floorDiv(sx, SurfaceTile.SIDE), lx = Math.floorMod(sx, SurfaceTile.SIDE);
                SurfaceTile t = around[dz + 1][dx + 1];
                int g = gx + gz * GRID, i = SurfaceTile.index(lx, lz);
                if (t == null || !t.explored(i)) continue;
                known[g] = true;
                height[g] = t.height[i];
                wet[g] = (t.water[i] & 0xFF) > 0;
                int c = Math.clamp(t.surface[i], 0, SurfaceClass.COUNT - 1);
                cls[g] = (byte) c;
                if (wet[g]) continue;
                int rgb = pal.paint(pal.landBase(c, t.colour[i], SurfaceTile.tintRgb(t.tint[i])));
                colour[g * 3] = (rgb >> 16 & 255) / 255f;
                colour[g * 3 + 1] = (rgb >> 8 & 255) / 255f;
                colour[g * 3 + 2] = (rgb & 255) / 255f;
            }
        }
        int[][] out = new int[TEXTURES][SIZE * SIZE];
        surfaceAndFields(around, out);
        effects(known, wet, cls, out);
        long t0 = System.nanoTime();
        brush(known, wet, colour, pal.brushSamples(), out);
        long brush = System.nanoTime() - t0;
        float[] sdf = signedDistance(wet, GRID, GRID, SHORE_CAP);
        heights(known, height, out);
        for (int az = 0; az < SIZE; az++) {
            for (int ax = 0; ax < SIZE; ax++) {
                float d = sdf[(ax + PAD) + (az + PAD) * GRID];
                int v = Math.clamp(Math.round(128 + 4 * d), 0, 255);
                out[SHORE][ax + az * SIZE] = 0xFF000000 | v << 16;
            }
        }
        return new Result(out, brush);
    }

    /** Canopy, water depth, the water's colour and the explored bit, straight from the samples. */
    private static void surfaceAndFields(@Nullable SurfaceTile[][] around, int[][] out) {
        for (int az = 0; az < SIZE; az++) {
            int sz = az - APRON, dz = Math.floorDiv(sz, SurfaceTile.SIDE), lz = Math.floorMod(sz, SurfaceTile.SIDE);
            for (int ax = 0; ax < SIZE; ax++) {
                int sx = ax - APRON, dx = Math.floorDiv(sx, SurfaceTile.SIDE), lx = Math.floorMod(sx, SurfaceTile.SIDE);
                SurfaceTile t = around[dz + 1][dx + 1];
                int a = ax + az * SIZE, i = SurfaceTile.index(lx, lz);
                if (t == null || !t.explored(i)) continue;
                out[SURFACE][a] = (t.canopy[i] & 0xFF) << 24;
                out[FIELDS][a] = 0xFF000000 | (t.water[i] & 0xFF);
                if ((t.water[i] & 0xFF) > 0) out[WATER][a] = 0xFF000000 | t.colour[i] & 0xFFFFFF;
            }
        }
    }

    /**
     * The brush (MM-8): a Kuwahara filter over the land's colour, with windows {@code w} samples across (1: none). For
     * each land sample, eight windows that touch it (four quarters, and four halves along its sides) are read from
     * summed-area tables: their mean colour and how much the colour varies in them. Each mean is weighted by
     * 1 / (variance + e)^2, so where one window is flat and another straddles a border, the flat one all but wins: the
     * border stays where it is and stays sharp, and speckle inside a patch is averaged away. Water and unexplored
     * samples take no part. Writes the surface texture's rgb.
     */
    static void brush(boolean[] known, boolean[] wet, float[] colour, int w, int[][] out) {
        int stride = GRID + 1;
        // summed-area tables: land count, r, g, b, and r^2 + g^2 + b^2
        double[] sn = new double[stride * stride], sr = new double[stride * stride], sg = new double[stride * stride],
            sb = new double[stride * stride], sq = new double[stride * stride];
        for (int z = 0; z < GRID; z++) {
            double rn = 0, rr = 0, rg = 0, rb = 0, rq = 0;
            for (int x = 0; x < GRID; x++) {
                int g = x + z * GRID;
                if (known[g] && !wet[g]) {
                    float r = colour[g * 3], gg = colour[g * 3 + 1], b = colour[g * 3 + 2];
                    rn++;
                    rr += r;
                    rg += gg;
                    rb += b;
                    rq += r * r + gg * gg + b * b;
                }
                int s = (x + 1) + (z + 1) * stride, up = (x + 1) + z * stride;
                sn[s] = sn[up] + rn;
                sr[s] = sr[up] + rr;
                sg[s] = sg[up] + rg;
                sb[s] = sb[up] + rb;
                sq[s] = sq[up] + rq;
            }
        }
        int r = Math.clamp(w, 1, MAX_BRUSH) - 1, h = r / 2;
        // the windows as offsets of their corners from the sample: x0, z0, x1, z1 (inclusive)
        int[][] windows = {
            {-r, -r, 0, 0}, {0, -r, r, 0}, {-r, 0, 0, r}, {0, 0, r, r},
            {-h, -r, r - h, 0}, {-h, 0, r - h, r}, {-r, -h, 0, r - h}, {0, -h, r, r - h}};
        double[] sum = new double[5];
        for (int az = 0; az < SIZE; az++) {
            for (int ax = 0; ax < SIZE; ax++) {
                int gx = ax + PAD, gz = az + PAD, g = gx + gz * GRID, a = ax + az * SIZE;
                if (!known[g] || wet[g]) continue;
                double cr, cg, cb;
                if (r == 0) {
                    cr = colour[g * 3];
                    cg = colour[g * 3 + 1];
                    cb = colour[g * 3 + 2];
                } else {
                    double wsum = 0;
                    cr = cg = cb = 0;
                    for (int[] win : windows) {
                        int x0 = gx + win[0], z0 = gz + win[1], x1 = gx + win[2] + 1, z1 = gz + win[3] + 1;
                        rect(sn, sr, sg, sb, sq, stride, x0, z0, x1, z1, sum);
                        double cnt = sum[0];
                        if (cnt < 1) continue;
                        double mr = sum[1] / cnt, mg = sum[2] / cnt, mb = sum[3] / cnt;
                        double var = Math.max(0, sum[4] / cnt - (mr * mr + mg * mg + mb * mb));
                        double e = var + 2e-4;
                        double wt = cnt / (e * e);
                        cr += wt * mr;
                        cg += wt * mg;
                        cb += wt * mb;
                        wsum += wt;
                    }
                    cr /= wsum;
                    cg /= wsum;
                    cb /= wsum;
                }
                int ir = (int) Math.clamp(Math.round(cr * 255), 0, 255), ig = (int) Math.clamp(Math.round(cg * 255), 0, 255),
                    ib = (int) Math.clamp(Math.round(cb * 255), 0, 255);
                out[SURFACE][a] = out[SURFACE][a] & 0xFF000000 | ir << 16 | ig << 8 | ib;
            }
        }
    }

    /** The sums over cells [x0, x1) x [z0, z1) of the five tables (clamped to the grid). */
    private static void rect(double[] sn, double[] sr, double[] sg, double[] sb, double[] sq, int stride, int x0, int z0, int x1, int z1,
                             double[] out) {
        x0 = Math.clamp(x0, 0, GRID);
        z0 = Math.clamp(z0, 0, GRID);
        x1 = Math.clamp(x1, 0, GRID);
        z1 = Math.clamp(z1, 0, GRID);
        int a = x0 + z0 * stride, b = x1 + z0 * stride, c = x0 + z1 * stride, d = x1 + z1 * stride;
        out[0] = sn[d] - sn[b] - sn[c] + sn[a];
        out[1] = sr[d] - sr[b] - sr[c] + sr[a];
        out[2] = sg[d] - sg[b] - sg[c] + sg[a];
        out[3] = sb[d] - sb[b] - sb[c] + sb[a];
        out[4] = sq[d] - sq[b] - sq[c] + sq[a];
    }

    /** Heights into fields r and g; unexplored samples get the mean of the known ones beside them, a ring at a time. */
    private static void heights(boolean[] knownGrid, int[] heightGrid, int[][] out) {
        int n = SIZE * SIZE;
        boolean[] known = new boolean[n];
        int[] height = new int[n];
        for (int az = 0; az < SIZE; az++) {
            for (int ax = 0; ax < SIZE; ax++) {
                int g = (ax + PAD) + (az + PAD) * GRID;
                known[ax + az * SIZE] = knownGrid[g];
                height[ax + az * SIZE] = heightGrid[g];
            }
        }
        boolean[] next = new boolean[n];
        int[] h = new int[n];
        for (int round = 0; round < SPREAD; round++) {
            System.arraycopy(known, 0, next, 0, n);
            System.arraycopy(height, 0, h, 0, n);
            boolean any = false;
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    int a = x + z * SIZE;
                    if (known[a]) continue;
                    int sum = 0, count = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx, nz = z + dz;
                            if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) continue;
                            int b = nx + nz * SIZE;
                            if (!known[b]) continue;
                            sum += height[b];
                            count++;
                        }
                    }
                    if (count == 0) continue;
                    h[a] = Math.round((float) sum / count);
                    next[a] = true;
                    any = true;
                }
            }
            System.arraycopy(next, 0, known, 0, n);
            System.arraycopy(h, 0, height, 0, n);
            if (!any) break;
        }
        for (int a = 0; a < n; a++) {
            int v = Math.clamp((known[a] ? height[a] : NO_HEIGHT) + 32768, 0, 65535);
            out[FIELDS][a] = (out[FIELDS][a] & 0xFF0000FF) | (v >> 8) << 16 | (v & 0xFF) << 8;
        }
    }

    /**
     * The effects' weights: the share of forest floor and of snow among the explored land round each sample, blurred
     * with a tent five samples wide, 0-255.
     */
    private static void effects(boolean[] known, boolean[] wet, byte[] cls, int[][] out) {
        int n = GRID * GRID;
        // land, forest and snow counts, blurred along x
        float[] rows = new float[n * 3];
        for (int gz = 0; gz < GRID; gz++) {
            for (int gx = 0; gx < GRID; gx++) {
                for (int k = 0; k < BLUR.length; k++) {
                    int x = gx + k - 2;
                    if (x < 0 || x >= GRID) continue;
                    int g = x + gz * GRID;
                    if (!known[g] || wet[g]) continue;
                    int o = (gx + gz * GRID) * 3;
                    rows[o] += BLUR[k];
                    if (cls[g] == SurfaceClass.FOREST) rows[o + 1] += BLUR[k];
                    if (cls[g] == SurfaceClass.SNOW) rows[o + 2] += BLUR[k];
                }
            }
        }
        for (int az = 0; az < SIZE; az++) {
            for (int ax = 0; ax < SIZE; ax++) {
                int gx = ax + PAD, gz = az + PAD;
                float land = 0, forest = 0, snow = 0;
                for (int k = 0; k < BLUR.length; k++) {
                    int z = gz + k - 2;
                    int o = (gx + z * GRID) * 3;
                    land += rows[o] * BLUR[k];
                    forest += rows[o + 1] * BLUR[k];
                    snow += rows[o + 2] * BLUR[k];
                }
                int f = land > 0 ? Math.round(forest / land * 255) : 0, s = land > 0 ? Math.round(snow / land * 255) : 0;
                out[EFFECTS][ax + az * SIZE] = 0xFF000000 | f << 16 | s << 8;
            }
        }
    }

    /**
     * Signed distance from each cell's centre to the shore, in samples: positive in water (to the nearest land), negative
     * on land (to the nearest water), with the shore half way between a wet and a dry cell (so ±0.5 next to it), capped
     * at ±cap. Exact Euclidean distance (Felzenszwalb and Huttenlocher), no water at all is -cap everywhere.
     */
    public static float[] signedDistance(boolean[] wet, int w, int h, int cap) {
        float[] toLand = squaredDistance(wet, w, h, false), toWater = squaredDistance(wet, w, h, true);
        float[] d = new float[w * h];
        for (int i = 0; i < d.length; i++) {
            float v = wet[i] ? (float) Math.sqrt(toLand[i]) - 0.5f : -((float) Math.sqrt(toWater[i]) - 0.5f);
            d[i] = Math.clamp(v, -cap, cap);
        }
        return d;
    }

    /** Squared distance from each cell to the nearest cell whose wet flag equals {@code target}. */
    private static float[] squaredDistance(boolean[] wet, int w, int h, boolean target) {
        float[] g = new float[w * h];
        for (int i = 0; i < g.length; i++) g[i] = wet[i] == target ? 0 : FAR;
        int m = Math.max(w, h);
        float[] f = new float[m], dt = new float[m], z = new float[m + 1];
        int[] v = new int[m];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) f[y] = g[x + y * w];
            edt1(f, h, dt, v, z);
            for (int y = 0; y < h; y++) g[x + y * w] = dt[y];
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) f[x] = g[x + y * w];
            edt1(f, w, dt, v, z);
            for (int x = 0; x < w; x++) g[x + y * w] = dt[x];
        }
        return g;
    }

    /** One-dimensional squared distance transform of a sampled function (lower envelope of parabolas). */
    private static void edt1(float[] f, int n, float[] d, int[] v, float[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = -Float.MAX_VALUE;
        z[1] = Float.MAX_VALUE;
        for (int q = 1; q < n; q++) {
            float s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2f * q - 2f * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2f * q - 2f * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Float.MAX_VALUE;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            float dq = q - v[k];
            d[q] = dq * dq + f[v[k]];
        }
    }

    // ------------------------------------------------------------------------------------------------ fog (MM-5)

    /** Texels along a fog mask's cell, and the mask's side with an apron of one texel each way (so cells meet). */
    public static final int MASK_TEXELS = 64, MASK_SIZE = MASK_TEXELS + 2;
    /** The noise of region edges repeats every this many blocks (the shader's 2048 samples). */
    static final int NOISE_BLOCKS = MapGpu.NOISE_PERIOD * MapGpu.NOISE_UNIT;

    /** Blocks along one texel of a level's fog mask: 8 at level 0, doubling each level. */
    public static int maskTexel(int level) {
        return 8 << level;
    }

    /** Texels the fog's edge is blurred over at a level (at least one). */
    static int maskBlur(int level, float edge) {
        return Math.max(1, Math.round(edge / maskTexel(level)));
    }

    /**
     * How far the edge wanders at level 0 (blocks). Each level reads the same noise 2^level times coarser and wanders
     * 2^level times as far, so the edge wanders alike on screen at every zoom and never runs straight for long.
     */
    static float maskWarp(float edge) {
        return edge / 3;
    }

    /** Texels the coverage is eroded by before the blur: as far as the warp reaches, so the edge only ever wanders inward. */
    static int maskErode(int level, float edge) {
        return (int) Math.ceil(maskWarp(edge) * (1 << level) / maskTexel(level));
    }

    /** Texels beyond the mask's apron its coverage is read from: the erosion, the blur, the warp and one for the bilinear look-up. */
    static int maskPad(int level, float edge) {
        int warp = maskErode(level, edge) + 1;
        return maskErode(level, edge) + maskBlur(level, edge) + warp + 1;
    }

    /** Tiles round a cell whose coverage or fog changes its mask. */
    public static int maskMarginTiles(int level, float edge) {
        long blocks = (long) (maskPad(level, edge) + 1) * maskTexel(level);
        return (int) ((blocks + SurfaceTile.BLOCKS - 1) / SurfaceTile.BLOCKS);
    }

    /**
     * A cell's fog mask, {@link #MASK_SIZE} square, 0 (unexplored) to 255 (explored), texel {@code i} centred on block
     * {@code cell origin + (i - 0.5) * texel}: the share of each texel that is both captured ({@code coverage}: a tile's
     * explored samples, by tile) and cleared of the group's fog ({@code fog}: a region's chunk bits, by region; a region
     * is a tile), eroded by as far as the warp below reaches, blurred with a tent {@code edge} blocks wide so neither chunk corners nor sample steps show at any zoom,
     * then read at a position warped by the same world-space noise as the painted region edges, repeating every 8192
     * blocks (at level 0; read coarser and stronger at each level up, see {@link #maskWarp}), so the edge never runs
     * straight. Every cell works out the same value at the same world position, so masks
     * meet without a seam.
     */
    public static byte[] fogMask(int level, int cellX, int cellZ, float edge, Long2ObjectMap<long[]> coverage, Long2ObjectMap<long[]> fog) {
        int t = maskTexel(level), pad = maskPad(level, edge), n = MASK_TEXELS + 2 * (pad + 1);
        long cellBlocks = (long) SurfaceTile.BLOCKS << level;
        long gx0 = cellX * cellBlocks - (long) (pad + 1) * t, gz0 = cellZ * cellBlocks - (long) (pad + 1) * t;
        float[] grid = new float[n * n];
        float share = (float) (SurfaceTile.SPACING * SurfaceTile.SPACING) / ((float) t * t);
        for (var e : coverage.long2ObjectEntrySet()) {
            long key = e.getLongKey();
            long[] chunks = fog.get(key);
            if (chunks == null) continue;
            long[] samples = e.getValue();
            int tx = MapGrid.keyX(key), tz = MapGrid.keyZ(key);
            long bx0 = (long) tx * SurfaceTile.BLOCKS, bz0 = (long) tz * SurfaceTile.BLOCKS;
            if (bx0 + SurfaceTile.BLOCKS <= gx0 || bz0 + SurfaceTile.BLOCKS <= gz0 || bx0 >= gx0 + (long) n * t || bz0 >= gz0 + (long) n * t) continue;
            for (int c = 0; c < MapGrid.REGION_CHUNKS * MapGrid.REGION_CHUNKS; c++) {
                if (!MapGrid.get(chunks, c)) continue;
                int lx = c % MapGrid.REGION_CHUNKS, lz = c / MapGrid.REGION_CHUNKS;
                for (int k = 0; k < SurfaceTile.PER_CHUNK * SurfaceTile.PER_CHUNK; k++) {
                    int sx = lx * SurfaceTile.PER_CHUNK + k % SurfaceTile.PER_CHUNK, sz = lz * SurfaceTile.PER_CHUNK + k / SurfaceTile.PER_CHUNK;
                    int i = SurfaceTile.index(sx, sz);
                    if ((samples[i >>> 6] & 1L << i) == 0) continue;
                    long gx = Math.floorDiv(bx0 + (long) sx * SurfaceTile.SPACING - gx0, t), gz = Math.floorDiv(bz0 + (long) sz * SurfaceTile.SPACING - gz0, t);
                    if (gx < 0 || gz < 0 || gx >= n || gz >= n) continue;
                    grid[(int) gx + (int) gz * n] += share;
                }
            }
        }
        // eroded as far as the warp reaches: wandering never shows land past what is captured and explored, where the
        // bake fades to paper along the sample grid
        float[] blurred = blur(erode(grid, n, maskErode(level, edge)), n, maskBlur(level, edge));
        double scale = 1 << level, amp = maskWarp(edge) * scale;
        byte[] out = new byte[MASK_SIZE * MASK_SIZE];
        for (int j = 0; j < MASK_SIZE; j++) {
            for (int i = 0; i < MASK_SIZE; i++) {
                double px = cellX * (double) cellBlocks + (i - 0.5) * t, pz = cellZ * (double) cellBlocks + (j - 0.5) * t;
                // the noise at the position scaled down to level 0's, wrapped to its period
                double qx = px / scale, qz = pz / scale;
                double ws = qx - NOISE_BLOCKS * Math.floor(qx / NOISE_BLOCKS), wt = qz - NOISE_BLOCKS * Math.floor(qz / NOISE_BLOCKS);
                double wx = warp(ws / MapGpu.NOISE_UNIT, wt / MapGpu.NOISE_UNIT, 0) * amp;
                double wz = warp(ws / MapGpu.NOISE_UNIT, wt / MapGpu.NOISE_UNIT, 1) * amp;
                float v = bilinear(blurred, n, (float) ((px + wx - gx0) / t - 0.5), (float) ((pz + wz - gz0) / t - 0.5));
                out[i + j * MASK_SIZE] = (byte) Math.round(Math.clamp(v, 0f, 1f) * 255);
            }
        }
        return out;
    }

    /** A separable minimum over a square {@code r} texels each way, with nothing beyond the grid. */
    private static float[] erode(float[] g, int n, int r) {
        if (r <= 0) return g;
        float[] h = new float[n * n], out = new float[n * n];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                float m = 1;
                for (int k = -r; k <= r; k++) m = Math.min(m, x + k >= 0 && x + k < n ? g[x + k + z * n] : 0);
                h[x + z * n] = m;
            }
        }
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                float m = 1;
                for (int k = -r; k <= r; k++) m = Math.min(m, z + k >= 0 && z + k < n ? h[x + (z + k) * n] : 0);
                out[x + z * n] = m;
            }
        }
        return out;
    }

    /** A separable tent blur, {@code r} texels each way, with nothing beyond the grid. */
    private static float[] blur(float[] g, int n, int r) {
        float norm = (float) (r + 1) * (r + 1);
        float[] h = new float[n * n], out = new float[n * n];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                float sum = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = x + k;
                    if (xx >= 0 && xx < n) sum += g[xx + z * n] * (r + 1 - Math.abs(k));
                }
                h[x + z * n] = sum;
            }
        }
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                float sum = 0;
                for (int k = -r; k <= r; k++) {
                    int zz = z + k;
                    if (zz >= 0 && zz < n) sum += h[x + zz * n] * (r + 1 - Math.abs(k));
                }
                out[x + z * n] = sum / (norm * norm);
            }
        }
        return out;
    }

    private static float bilinear(float[] g, int n, float x, float z) {
        x = Math.clamp(x, 0, n - 1.001f);
        z = Math.clamp(z, 0, n - 1.001f);
        int ix = (int) x, iz = (int) z;
        float fx = x - ix, fz = z - iz;
        float a = g[ix + iz * n], b = g[ix + 1 + iz * n], c = g[ix + (iz + 1) * n], d = g[ix + 1 + (iz + 1) * n];
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    // the shader's noise (map_paint.fsh: hash, vnoise, warp), so the fog's edge wanders as region edges do

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    static float hash(float x, float y) {
        float p3x = fract(x * 0.1031f), p3y = fract(y * 0.1031f), p3z = fract(x * 0.1031f);
        float d = p3x * (p3y + 33.33f) + p3y * (p3z + 33.33f) + p3z * (p3x + 33.33f);
        p3x += d;
        p3y += d;
        p3z += d;
        return fract((p3x + p3y) * p3z);
    }

    /** Smooth value noise over cells of size 1, repeating every {@code period} cells. */
    static float vnoise(double x, double y, double period) {
        double ix = Math.floor(x), iy = Math.floor(y);
        float fx = (float) (x - ix), fy = (float) (y - iy);
        float ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
        double jx = ix + 1, jy = iy + 1;
        ix = ix - period * Math.floor(ix / period);
        iy = iy - period * Math.floor(iy / period);
        jx = jx - period * Math.floor(jx / period);
        jy = jy - period * Math.floor(jy / period);
        float a = hash((float) ix, (float) iy), b = hash((float) jx, (float) iy), c = hash((float) ix, (float) jy), d = hash((float) jx, (float) jy);
        return (a + (b - a) * ux) * (1 - uy) + (c + (d - c) * ux) * uy;
    }

    /** One axis of the shader's warp at a world position in noise units ({@link MapGpu#NOISE_UNIT} blocks), at unit strength (-1 to 1). */
    public static double warp(double sx, double sz, int axis) {
        double p = MapGpu.NOISE_PERIOD;
        double big = axis == 0 ? vnoise(sx / 16, sz / 16, p / 16) : vnoise(sx / 16 + 53, sz / 16 + 17, p / 16);
        double small = axis == 0 ? vnoise(sx / 4 + 7, sz / 4 + 91, p / 4) : vnoise(sx / 4 + 29, sz / 4 + 3, p / 4);
        return (big - 0.5) * 1.4 + (small - 0.5) * 0.6;
    }
}
