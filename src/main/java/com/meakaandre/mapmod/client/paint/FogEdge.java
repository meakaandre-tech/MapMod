package com.meakaandre.mapmod.client.paint;

/**
 * The fog's edge at each level of the mip grid (MM-8c), and how a level's mask is worked out from the captured land.
 * <p>
 * The edge's width depends on the zoom: {@code edge} blocks (the palette's, 48 by default) at level 0 and close up,
 * shrinking towards {@value #MIN_PX} screen pixels as the map zooms out (level k is drawn at about 2^k blocks a pixel).
 * Where a chunk is several pixels across (levels 0 to 2) the edge lies inside the captured land, so the chunk staircase
 * the bake fades to paper along is under the fog; but never so far in that a small patch is swallowed: where the land is
 * thin (a fresh base, a small island) the edge moves out until the patch's middle shows in full. Further out a chunk is
 * a pixel or two, and the edge lies just outside the captured land, so all of it shows at any zoom. The land is read on
 * a grid of chunks (far out of a quarter of a mask texel, a couple of pixels), where any captured sample makes a cell
 * land, so a patch smaller than a cell still counts.
 * <p>
 * A mask holds the signed distance to where the fog is half way, {@code 0.5 + (d - offset) / range}, and the screen
 * shader cuts it with {@link Params#sharp()}: {@code smoothstep} over the edge's width. A distance interpolates exactly
 * between texels, so the edge runs as a smooth curve even where it is narrower than a texel, and the texels' own steps
 * never show. It is read at a position warped by the same world-space noise as the painted region edges, so the edge
 * never runs straight. Everything is worked out on grids fixed in world space, so neighbouring cells' masks meet
 * exactly. None of this is baked: the fog goes on as the screen's pixels are written.
 */
public final class FogEdge {
    /** The narrowest the edge gets on screen (pixels), far out. */
    static final float MIN_PX = 3;
    /** The first level whose chunks are too small to show steps: from here on the edge lies outside the captured land. */
    static final int FAR_LEVEL = 3;
    /** Grid cells the edge's offset is smoothed over (a tent each way). */
    static final int BLUR = 2;
    /** The land is read on a grid this many blocks fine at most (a chunk), and at least 4 cells to a mask texel far out. */
    static final int FINE = 16, FINE_PER_TEXEL = 4;
    /** How far the clouds can move the edge at most, in edge widths (the shaders' 0.75 times a cloud strength of 1, halved). */
    static final float CLOUD_REACH = 0.375f;
    /** The land's thickness the edge is fully inward from, in units of how far it moves in or out. */
    static final float THICK = 1.5f;
    /**
     * Far out, land thinner than a mask texel (a patch of a few pixels, which a texel's samples cannot hold the middle
     * of) gets a clearing this many texels round it, so it shows in full in the middle of it.
     */
    static final float CLEARING = 0.75f;
    private static final double FAR = 1e12;

    private FogEdge() {
    }

    /** The world-space warp the edge is read at: one axis at a position in noise units, -1 to 1. */
    public interface Warp {
        double at(double sx, double sz, int axis);
    }

    /**
     * A level's edge, in blocks: a mask's {@code texel}, the {@code cell} of the grid the land is read on, the edge's
     * {@code width} (fully fogged to fully shown), how far the {@code warp} moves it, where its middle lies when the land
     * is wide ({@code hide}: inward from the captured land's border when positive), the offset that shows all of the
     * captured land ({@code keep}, negative), where it lies round land of no thickness ({@code bare}: {@code keep} near,
     * a clearing round it far out), the land's thickness the edge lies at {@code hide} from ({@code thick}; thinner land
     * moves it out towards {@code bare}), how far round a cell that thickness is looked for ({@code reach}),
     * the distance range a mask holds; and the grid cells beyond a mask (apron included) its grid needs ({@code pad}).
     */
    public record Params(int level, int texel, int cell, float edge, float width, float warp, float hide, float keep, float bare, float thick,
                         float reach, float range, int pad, boolean far) {
        /** What the shaders multiply a mask's distance by: its range over the edge's width. */
        public float sharp() {
            return range / width;
        }

        /** Grid cells along a mask's side and its grid's side. */
        public int grid(int maskSize) {
            return maskSize * (texel / cell) + 2 * pad;
        }

        /** Tiles round a mask's cell of the mip grid whose capture changes the mask. */
        public int marginTiles(int tileBlocks) {
            long blocks = (long) pad * cell + texel;
            return (int) ((blocks + tileBlocks - 1) / tileBlocks);
        }
    }

    /** The edge at a level for the palette's edge width (blocks); a texel there is {@code texel} blocks. */
    public static Params params(int level, float edge, int texel) {
        double scale = 1L << level;
        float e = (float) Math.max(edge / Math.sqrt(scale), MIN_PX * scale);
        float width = 0.625f * e, warp = e / 3;
        boolean far = level >= FAR_LEVEL;
        // everything captured shows in full, whatever the warp and the clouds do
        float keep = -((0.5f + CLOUD_REACH) * width + warp);
        // near: the edge's fogged side reaches the captured land's border only where the warp pushes it furthest
        float hide = far ? keep : warp + 0.5f * width + 0.0625f * e;
        // round land of no thickness: near, all of it shows; far out, in a clearing (a texel holds no middle of a patch smaller)
        float bare = far ? keep - CLEARING * texel : keep;
        // the land's thickness the edge lies at hide from (a fresh base, an island thinner than this shows more)
        float thick = far ? texel : THICK * (hide - keep);
        // looked for this far round: as deep as that, and far enough that a change in it moves the edge only where both
        // sides of it are fogged anyway
        float reach = Math.max(thick, -bare + (0.5f + CLOUD_REACH) * width);
        float range = 1.75f * width + 4f * texel;
        int cell = Math.max(Math.min(FINE, texel), texel / FINE_PER_TEXEL);
        float distance = range / 2 + Math.max(Math.abs(hide), -bare);
        int pad = (int) Math.ceil((warp + BLUR * cell + reach + distance) / cell) + 2;
        return new Params(level, texel, cell, e, width, warp, hide, keep, bare, thick, reach, range, pad, far);
    }

    /**
     * A mask from the share of each cell of a grid that is captured: {@code grid} is {@link Params#grid} square, cell
     * {@code (i, j)} centred on world block {@code (gx0 + (i + 0.5) * cell, gz0 + (j + 0.5) * cell)} with
     * {@code gx0 = ox - texel - pad * cell}; the mask is {@code size} square, texel {@code i} centred on block
     * {@code ox + (i - 0.5) * texel}. Any share counts as land, so nothing captured is lost to the grid. Values 0 to 255.
     */
    public static byte[] mask(Params p, float[] grid, double ox, double oz, int size, Warp warp, double noiseUnit, double noiseBlocks) {
        int c = p.cell, n = p.grid(size);
        double gx0 = ox - p.texel - (double) p.pad * c, gz0 = oz - p.texel - (double) p.pad * c;
        boolean[] land = new boolean[n * n];
        for (int i = 0; i < land.length; i++) land[i] = grid[i] > 0;
        // signed distance to the border of the land, in blocks (positive on land), the border half a cell off its centres
        double[] in = squaredDistance(land, n, false), out = squaredDistance(land, n, true);
        double cap = p.range + p.reach + Math.abs(p.hide) - p.bare + 4.0 * p.texel;
        float[] d = new float[n * n], thick = new float[n * n];
        for (int i = 0; i < d.length; i++) {
            double v = land[i] ? (Math.sqrt(in[i]) - 0.5) * c : -(Math.sqrt(out[i]) - 0.5) * c;
            d[i] = (float) Math.clamp(v, -cap, cap);
            thick[i] = (float) Math.clamp(v, 0, p.thick);
        }
        // where the edge's middle lies: inward where the land round the cell is thick, further out the thinner it is, and
        // round the thinnest just outside it (far out with a clearing round it), so all of it shows
        float[] t2 = dilate(thick, n, (int) Math.ceil(p.reach / c));
        float[] offset = new float[n * n];
        for (int i = 0; i < offset.length; i++) offset[i] = p.bare + (p.hide - p.bare) * Math.min(1f, t2[i] / p.thick);
        offset = tent(offset, n, BLUR);
        float[] s = new float[n * n];
        for (int i = 0; i < s.length; i++) s[i] = 0.5f + (d[i] - offset[i]) / p.range;
        double scale = 1L << p.level;
        byte[] mask = new byte[size * size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                double px = ox + (i - 0.5) * p.texel, pz = oz + (j - 0.5) * p.texel;
                // the noise at the position scaled down to level 0's, wrapped to its period
                double qx = px / scale, qz = pz / scale;
                double ws = qx - noiseBlocks * Math.floor(qx / noiseBlocks), wt = qz - noiseBlocks * Math.floor(qz / noiseBlocks);
                double wx = warp.at(ws / noiseUnit, wt / noiseUnit, 0) * p.warp, wz = warp.at(ws / noiseUnit, wt / noiseUnit, 1) * p.warp;
                float v = bilinear(s, n, (float) ((px + wx - gx0) / c - 0.5), (float) ((pz + wz - gz0) / c - 0.5));
                mask[i + j * size] = (byte) Math.round(Math.clamp(v, 0f, 1f) * 255);
            }
        }
        return mask;
    }

    /**
     * A mask value (0 to 1) as the old scale reads it: 0.55 where the fog starts to clear, 0.95 where the land shows in
     * full, before the clouds roughen it (for the selftests).
     */
    public static float shown(Params p, float value) {
        return Math.clamp(0.75f + 0.4f * (value - 0.5f) * p.sharp(), 0f, 1f);
    }

    /** Squared distance (texels) from each texel to the nearest texel whose land is {@code target}; nothing beyond the grid. */
    private static double[] squaredDistance(boolean[] land, int n, boolean target) {
        double[] g = new double[n * n];
        for (int i = 0; i < g.length; i++) g[i] = land[i] == target ? 0 : FAR;
        double[] f = new double[n], dt = new double[n], z = new double[n + 1];
        int[] v = new int[n];
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) f[y] = g[x + y * n];
            edt(f, n, dt, v, z);
            for (int y = 0; y < n; y++) g[x + y * n] = dt[y];
        }
        for (int y = 0; y < n; y++) {
            System.arraycopy(g, y * n, f, 0, n);
            edt(f, n, dt, v, z);
            System.arraycopy(dt, 0, g, y * n, n);
        }
        return g;
    }

    /** One-dimensional squared distance transform (the lower envelope of parabolas); FAR stays far. */
    private static void edt(double[] f, int n, double[] d, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = -Double.MAX_VALUE;
        z[1] = Double.MAX_VALUE;
        for (int q = 1; q < n; q++) {
            double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.MAX_VALUE;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            double dq = q - v[k];
            d[q] = Math.min(FAR, dq * dq + f[v[k]]);
        }
    }

    /** A separable maximum over a square {@code r} texels each way. */
    private static float[] dilate(float[] g, int n, int r) {
        if (r <= 0) return g;
        float[] h = new float[n * n], out = new float[n * n];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                float m = 0;
                for (int k = Math.max(0, x - r); k <= Math.min(n - 1, x + r); k++) m = Math.max(m, g[k + z * n]);
                h[x + z * n] = m;
            }
        }
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                float m = 0;
                for (int k = Math.max(0, z - r); k <= Math.min(n - 1, z + r); k++) m = Math.max(m, h[x + k * n]);
                out[x + z * n] = m;
            }
        }
        return out;
    }

    /** A separable tent blur, {@code r} texels each way, the edge texels repeated beyond the grid. */
    private static float[] tent(float[] g, int n, int r) {
        if (r <= 0) return g;
        float norm = (float) (r + 1) * (r + 1);
        float[] h = new float[n * n], out = new float[n * n];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                float sum = 0;
                for (int k = -r; k <= r; k++) sum += g[Math.clamp(x + k, 0, n - 1) + z * n] * (r + 1 - Math.abs(k));
                h[x + z * n] = sum / norm;
            }
        }
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                float sum = 0;
                for (int k = -r; k <= r; k++) sum += h[x + Math.clamp(z + k, 0, n - 1) * n] * (r + 1 - Math.abs(k));
                out[x + z * n] = sum / norm;
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
}
