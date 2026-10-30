package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceClass;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
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
}
