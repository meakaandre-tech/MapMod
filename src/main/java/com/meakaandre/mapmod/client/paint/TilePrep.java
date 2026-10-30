package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceClass;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import com.meakaandre.mapmod.map.MapGrid;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.jspecify.annotations.Nullable;

/**
 * Works out one tile's GPU textures from the tile and its eight neighbours, on a background thread (MM-3): the render
 * thread only copies the result into the textures ({@link TileTextures#upload}). Each texture is {@link #SIZE} square:
 * the tile's 128x128 samples plus an apron of {@link #APRON} samples from the neighbours on every side, wide enough for
 * the shader's warp and cubic sampling, so tiles meet without a seam. Pixels are ARGB, as {@code NativeImage.setPixel}
 * takes them:
 * <pre>
 *   fields   r, g  height + 32768, high and low byte   b  water depth (blocks)   a  255 explored, 0 not
 *   surface  rgb   biome tint                          a  canopy density
 *   classA   argb  blurred weights of classes 0-3 (r = 0, g = 1, b = 2, a = 3), 0-255, summing to 255 on land
 *   classB   argb  blurred weights of classes 4-7
 *   shore    r     signed distance to the shore in samples, 128 + 8 * d (water positive, land negative, ±16 at most)
 * </pre>
 * Everything is computed over a wider window ({@link #PAD} more samples on each side) and cropped, so the blur and the
 * distance field are the same in a tile's apron as in the neighbour it copies.
 */
public final class TilePrep {
    public static final int APRON = 6, SIZE = SurfaceTile.SIDE + 2 * APRON;
    /** Distance field cap (samples): far enough for the deep-water gradient. */
    public static final int SHORE_CAP = 16;
    /** Samples beyond the apron that the blur and distance field look at. */
    static final int PAD = SHORE_CAP;
    static final int GRID = SIZE + 2 * PAD;
    /** Tent blur of the class weights: 1 2 3 2 1. */
    private static final int[] BLUR = {1, 2, 3, 2, 1};
    /** Rounds of spreading known heights into unexplored samples. */
    private static final int SPREAD = 3;
    /** Height for unexplored samples nothing known is near (they are paper anyway). */
    private static final int NO_HEIGHT = 63;
    private static final float FAR = 1e9f;

    public static final int FIELDS = 0, SURFACE = 1, CLASS_A = 2, CLASS_B = 3, SHORE = 4, TEXTURES = 5;

    private TilePrep() {
    }

    /** The textures' pixels, ARGB, {@code pixels[texture][x + z * SIZE]}. */
    public record Result(int[][] pixels) {
        public int pixel(int texture, int ax, int az) {
            return pixels[texture][ax + az * SIZE];
        }
    }

    /** Works out a tile's textures from it and its neighbours ({@code around[dz + 1][dx + 1]}, null where none). */
    public static Result prepare(@Nullable SurfaceTile[][] around) {
        int n = GRID * GRID;
        boolean[] known = new boolean[n], wet = new boolean[n];
        int[] height = new int[n];
        byte[] cls = new byte[n];
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
                cls[g] = (byte) Math.clamp(t.surface[i], 0, SurfaceClass.COUNT - 1);
            }
        }
        int[][] out = new int[TEXTURES][SIZE * SIZE];
        surfaceAndFields(around, out);
        classWeights(known, wet, cls, out);
        float[] sdf = signedDistance(wet, GRID, GRID, SHORE_CAP);
        heights(known, height, out);
        for (int az = 0; az < SIZE; az++) {
            for (int ax = 0; ax < SIZE; ax++) {
                float d = sdf[(ax + PAD) + (az + PAD) * GRID];
                int v = Math.clamp(Math.round(128 + 8 * d), 0, 255);
                out[SHORE][ax + az * SIZE] = 0xFF000000 | v << 16;
            }
        }
        return new Result(out);
    }

    /** Tint, canopy, water depth and the explored bit, straight from the samples. */
    private static void surfaceAndFields(@Nullable SurfaceTile[][] around, int[][] out) {
        for (int az = 0; az < SIZE; az++) {
            int sz = az - APRON, dz = Math.floorDiv(sz, SurfaceTile.SIDE), lz = Math.floorMod(sz, SurfaceTile.SIDE);
            for (int ax = 0; ax < SIZE; ax++) {
                int sx = ax - APRON, dx = Math.floorDiv(sx, SurfaceTile.SIDE), lx = Math.floorMod(sx, SurfaceTile.SIDE);
                SurfaceTile t = around[dz + 1][dx + 1];
                int a = ax + az * SIZE, i = SurfaceTile.index(lx, lz);
                if (t == null || !t.explored(i)) continue;
                int tint = t.tint[i] & 0xFFFF;
                int r = (tint >> 11 & 31) * 255 / 31, g = (tint >> 5 & 63) * 255 / 63, b = (tint & 31) * 255 / 31;
                out[SURFACE][a] = (t.canopy[i] & 0xFF) << 24 | r << 16 | g << 8 | b;
                out[FIELDS][a] = 0xFF000000 | (t.water[i] & 0xFF);
            }
        }
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

    /** One weight per class on explored land, blurred with a tent five samples wide and normalised to sum to 255. */
    private static void classWeights(boolean[] known, boolean[] wet, byte[] cls, int[][] out) {
        int n = GRID * GRID, c8 = SurfaceClass.COUNT;
        // one-hot, blurred along x
        float[] rows = new float[n * c8];
        for (int gz = 0; gz < GRID; gz++) {
            for (int gx = 0; gx < GRID; gx++) {
                for (int k = 0; k < BLUR.length; k++) {
                    int x = gx + k - 2;
                    if (x < 0 || x >= GRID) continue;
                    int g = x + gz * GRID;
                    if (!known[g] || wet[g]) continue;
                    rows[(gx + gz * GRID) * c8 + cls[g]] += BLUR[k];
                }
            }
        }
        float[] w = new float[c8];
        for (int az = 0; az < SIZE; az++) {
            for (int ax = 0; ax < SIZE; ax++) {
                int gx = ax + PAD, gz = az + PAD;
                java.util.Arrays.fill(w, 0);
                float sum = 0;
                for (int k = 0; k < BLUR.length; k++) {
                    int z = gz + k - 2;
                    int base = (gx + z * GRID) * c8;
                    for (int c = 0; c < c8; c++) {
                        float v = rows[base + c] * BLUR[k];
                        w[c] += v;
                        sum += v;
                    }
                }
                int[] b = new int[c8];
                if (sum > 0) for (int c = 0; c < c8; c++) b[c] = Math.round(w[c] / sum * 255);
                int a = ax + az * SIZE;
                out[CLASS_A][a] = b[3] << 24 | b[0] << 16 | b[1] << 8 | b[2];
                out[CLASS_B][a] = b[7] << 24 | b[4] << 16 | b[5] << 8 | b[6];
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
    static final int NOISE_BLOCKS = MapGpu.NOISE_PERIOD * SurfaceTile.SPACING;

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
                for (int k = 0; k < 16; k++) {
                    int sx = lx * 4 + (k & 3), sz = lz * 4 + (k >> 2), i = SurfaceTile.index(sx, sz);
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
                double wx = warp(ws / SurfaceTile.SPACING, wt / SurfaceTile.SPACING, 0) * amp;
                double wz = warp(ws / SurfaceTile.SPACING, wt / SurfaceTile.SPACING, 1) * amp;
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

    /** One axis of the shader's warp at a world position in samples, at unit strength (-1 to 1). */
    static double warp(double sx, double sz, int axis) {
        double p = MapGpu.NOISE_PERIOD;
        double big = axis == 0 ? vnoise(sx / 16, sz / 16, p / 16) : vnoise(sx / 16 + 53, sz / 16 + 17, p / 16);
        double small = axis == 0 ? vnoise(sx / 4 + 7, sz / 4 + 91, p / 4) : vnoise(sx / 4 + 29, sz / 4 + 3, p / 4);
        return (big - 0.5) * 1.4 + (small - 0.5) * 0.6;
    }
}
