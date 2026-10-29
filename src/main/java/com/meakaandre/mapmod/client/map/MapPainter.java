package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapTile;
import com.mojang.blaze3d.platform.NativeImage;
import org.jspecify.annotations.Nullable;

/**
 * Paints a region of the map as a smooth, hand-drawn looking survey map, in the manner of Satisfactory's: the recorded
 * terrain (one sample for every 4 by 4 blocks) is treated as a continuous field, not as pixels. Heights, the land's
 * colours, where the water is and how deep are each blended and interpolated with smooth curves, so coastlines curve,
 * colours run into each other and hills are lit softly from the north-west at any zoom. A noise in world coordinates
 * bends the borders between colours and the coastlines a little and gives the ground a faint painted grain; steep slopes
 * turn to dark rock with an ink outline; rivers, shallows and coasts are bright turquoise with a pale sand rim, the open
 * sea a flat slate teal; contour lines are thin and soft. Every
 * field is read across the region's edges from its neighbours, so tiles join without a seam.
 */
final class MapPainter {
    /** Fog: a dark blue-grey with a faint diagonal weave (the same colours the selftests look for). */
    static final int FOG = 0x141A26, FOG_LINE = 0x19202D;
    /** Explored but not recorded yet. */
    static final int UNKNOWN = 0x3A424C;
    /** Data pixels between the fog weave's diagonal lines. */
    private static final int FOG_WEAVE = 6;
    /** Bare rock on steep slopes, and the dark ink line round it. */
    private static final int ROCK = 0x5E5E62, INK = 0x24252A;
    /** Bright turquoise shallows, rivers and coasts; the open sea's flat slate teal. */
    private static final int SHALLOW = 0x5DC0D2, OPEN_WATER = 0x4A757B;
    /** The pale sand rim along the shore. */
    private static final int SAND = 0xE3D7BE;
    /** Blocks of water at which it is open water. */
    private static final float DEEP_AT = 9f;
    /** Slope (blocks of rise a block) at which ground turns to cliff. */
    private static final float CLIFF = 0.95f;
    /** Blocks between contour lines. */
    private static final float CONTOUR = 32f;
    /** Data pixels of padding read from the neighbours round a region: enough for every blur, warp and curve. */
    static final int PAD = 8;
    /** How far (blocks) and how broadly (blocks) the noise bends coastlines and the borders between colours. */
    private static final float WARP = 5f, WARP_SCALE = 40f;
    /** The painted grain's strength and sizes (blocks). */
    private static final float GRAIN = 0.09f, GRAIN_SCALE = 22f, FINE_GRAIN_SCALE = 7f;
    /** Height exaggeration for the hill shading, and its strength. */
    private static final float RELIEF = 1.4f, RELIEF_LIGHT = 0.5f;

    private MapPainter() {
    }

    /** The data of a region and a border of its neighbours, in one grid of {@code REGION_PX + 2 * PAD} a side. */
    static final class Grid {
        final int side = MapGrid.REGION_PX + 2 * PAD;
        final int[] colour = new int[side * side];
        final float[] height = new float[side * side];
        final float[] depth = new float[side * side];
        final boolean[] known = new boolean[side * side];
        final boolean[] wet = new boolean[side * side];

        /** {@code near} holds the 3 by 3 tiles round the region (the region in the middle); missing ones are null. */
        Grid(@Nullable MapTile[] near) {
            int n = MapGrid.REGION_PX;
            for (int gz = 0; gz < side; gz++) {
                for (int gx = 0; gx < side; gx++) {
                    int px = gx - PAD, pz = gz - PAD;
                    int tx = px < 0 ? -1 : px >= n ? 1 : 0, tz = pz < 0 ? -1 : pz >= n ? 1 : 0;
                    MapTile t = near[(tz + 1) * 3 + tx + 1];
                    if (t == null) continue;
                    int i = MapTile.index(Math.floorMod(px, n), Math.floorMod(pz, n)), g = gz * side + gx;
                    if (!t.known(i)) continue;
                    int c = t.colour[i];
                    known[g] = true;
                    wet[g] = (c & MapTile.WATER) != 0;
                    colour[g] = c & 0xFFFFFF;
                    depth[g] = wet[g] ? MapTile.depth(c) : 0;
                    // under water the shape that counts is the floor's
                    height[g] = t.height[i] - depth[g];
                }
            }
        }
    }

    /**
     * Paints the region into the image at {@code up} texels a data pixel (1: a texel for 4 by 4 blocks; 4: a texel a
     * block). {@code fog} holds the explored bits of the 3 by 3 regions round it (null where nothing is explored).
     */
    static void paint(NativeImage image, int up, long region, @Nullable Grid grid, long[] @Nullable [] fog) {
        int n = MapGrid.REGION_PX, size = n * up;
        int baseX = MapGrid.keyX(region) * MapGrid.REGION_BLOCKS, baseZ = MapGrid.keyZ(region) * MapGrid.REGION_BLOCKS;
        float blocksPerTexel = (float) MapGrid.PX_BLOCKS / up;
        float[] fogField = fogField(fog);
        Fields f = grid == null ? null : new Fields(grid);
        // heights at texel resolution with a border of one, for their slopes
        int hs = size + 2;
        float[] h = null;
        if (f != null) {
            h = new float[hs * hs];
            for (int tz = -1; tz <= size; tz++) {
                for (int tx = -1; tx <= size; tx++) {
                    h[(tz + 1) * hs + tx + 1] = cubic(f.height, f.side, gx(tx, up), gx(tz, up));
                }
            }
        }
        for (int tz = 0; tz < size; tz++) {
            for (int tx = 0; tx < size; tx++) {
                // world position of the texel's centre (blocks)
                float wx = baseX + (tx + 0.5f) * blocksPerTexel, wz = baseZ + (tz + 0.5f) * blocksPerTexel;
                // fog first: a soft-edged mask of explored chunks, its edge bent by the noise
                float fx = (wx - baseX) / 16f + MapGrid.REGION_CHUNKS - 0.5f + (Noise.fbm(wx / 30f, wz / 30f, 7) - 0.5f) * 0.8f;
                float fz = (wz - baseZ) / 16f + MapGrid.REGION_CHUNKS - 0.5f + (Noise.fbm(wx / 30f, wz / 30f, 8) - 0.5f) * 0.8f;
                float seen = smooth(0.35f, 0.65f, bilinear(fogField, 3 * MapGrid.REGION_CHUNKS, fx, fz));
                int fogRgb = ((tx / up + tz / up) % FOG_WEAVE == 0) ? FOG_LINE : FOG;
                int rgb;
                if (seen <= 0f || f == null) {
                    rgb = seen <= 0f ? fogRgb : mix(fogRgb, UNKNOWN, seen);
                } else {
                    int land = terrain(f, h, hs, tx, tz, up, wx, wz, blocksPerTexel);
                    float k = smooth(0.35f, 0.65f, bilinear(f.known, f.side, gx(tx, up), gx(tz, up)));
                    rgb = mix(UNKNOWN, land, k);
                    if (seen < 1f) rgb = mix(fogRgb, rgb, seen);
                }
                image.setPixel(tx, tz, 0xFF000000 | rgb);
            }
        }
    }

    /** Grid coordinate (data pixels, padding included) of a texel's centre. */
    private static float gx(int t, int up) {
        return (t + 0.5f) / up - 0.5f + PAD;
    }

    /** One texel of known ground or water. */
    private static int terrain(Fields f, float[] h, int hs, int tx, int tz, int up, float wx, float wz, float bpt) {
        // the noise's bend, in data pixels
        float warpX = (Noise.fbm(wx / WARP_SCALE, wz / WARP_SCALE, 1) - 0.5f) * 2f * WARP / MapGrid.PX_BLOCKS;
        float warpZ = (Noise.fbm(wx / WARP_SCALE, wz / WARP_SCALE, 2) - 0.5f) * 2f * WARP / MapGrid.PX_BLOCKS;
        float gx = gx(tx, up), gz = gx(tz, up);
        float wgx = gx + warpX, wgz = gz + warpZ;
        float wet = cubic(f.wet, f.side, wgx, wgz);

        // the slope (blocks of rise a block), from the smooth heights
        int i = (tz + 1) * hs + tx + 1;
        float dx = (h[i + 1] - h[i - 1]) / (2 * bpt), dz = (h[i + hs] - h[i - hs]) / (2 * bpt);
        float steep = (float) Math.sqrt(dx * dx + dz * dz);
        // light from the north-west, a little above the horizon: brighter on slopes facing it, darker away from it
        float nx = -dx * RELIEF, nz = -dz * RELIEF;
        float len = (float) Math.sqrt(nx * nx + nz * nz + 1);
        final float lx = -0.55f, lz = -0.55f, ly = 0.63f;
        float lambert = (nx * lx + nz * lz + ly) / len;
        float relief = 1f + (lambert - ly) * RELIEF_LIGHT;
        float grain = 1f + GRAIN * ((Noise.fbm(wx / GRAIN_SCALE, wz / GRAIN_SCALE, 3) - 0.5f) * 1.4f + (Noise.value(wx / FINE_GRAIN_SCALE, wz / FINE_GRAIN_SCALE, 4) - 0.5f) * 0.6f);

        // land: its blended colour; cliffs as flat dark rock with an ink outline; relief and grain; faint contours
        int land = rgb(bilinear(f.r, f.side, wgx, wgz), bilinear(f.g, f.side, wgx, wgz), bilinear(f.b, f.side, wgx, wgz));
        float cliffEdge = 0.08f + 0.1f * bpt / MapGrid.PX_BLOCKS;
        land = mix(land, ROCK, 0.35f * smooth(CLIFF - cliffEdge, CLIFF + cliffEdge, steep));
        float ink = 1f - smooth(cliffEdge, cliffEdge * 3f, Math.abs(steep - CLIFF - cliffEdge * 2f));
        float landLight = Math.clamp(relief, 0.75f, 1.12f) * grain;
        float height = h[i];
        float off = Math.abs(height - Math.round(height / CONTOUR) * CONTOUR);
        float lineTexels = off / Math.max(steep, 0.08f) / bpt;
        landLight *= 1f - 0.07f * (1f - smooth(0.25f, 1.1f, lineTexels));
        land = scale(land, landLight);
        land = mix(land, INK, 0.75f * ink);
        // the pale sand rim along the shore
        land = mix(land, SAND, 0.85f * smooth(0.3f, 0.45f, wet) * (1f - smooth(CLIFF * 0.6f, CLIFF, steep)));

        // water: bright turquoise in rivers, shallows and along the coast, flat slate teal out at sea
        float depth = bilinear(f.depth, f.side, wgx, wgz);
        float open = smooth(0.6f, 0.95f, bilinear(f.wide, f.side, gx, gz));
        float deep = smooth(0f, 1f, Math.min(Math.clamp(depth / DEEP_AT, 0f, 1f), open));
        int water = mix(SHALLOW, OPEN_WATER, deep);
        water = scale(water, 1f + (grain - 1f) * 0.4f);

        // the coast: a smooth step over about one texel
        float edge = 0.2f / up;
        return mix(land, water, smooth(0.5f - edge, 0.5f + edge, wet));
    }

    // ------------------------------------------------------------------------------------------------ fields

    /** The grid's data turned into smooth fields of floats. */
    private static final class Fields {
        final int side;
        final float[] height, wet, wide, depth, known, r, g, b;

        Fields(Grid grid) {
            side = grid.side;
            int a = side * side;
            float[] k = new float[a];
            for (int i = 0; i < a; i++) k[i] = grid.known[i] ? 1 : 0;
            // what is not known, and the land's colour under water, are filled from round about so nothing unknown
            // bleeds into the blends
            boolean[] landKnown = new boolean[a];
            for (int i = 0; i < a; i++) landKnown[i] = grid.known[i] && !grid.wet[i];
            int[] landColour = fill(grid.colour, landKnown, side, 0x6A8A52);
            int[] anyColour = landColour;
            float[] hgt = fillF(grid.height, grid.known, side, 64f);
            float[] wetRaw = new float[a];
            for (int i = 0; i < a; i++) wetRaw[i] = grid.known[i] ? (grid.wet[i] ? 1 : 0) : Float.NaN;
            wetRaw = fillNaN(wetRaw, side, 0f);
            r = new float[a];
            g = new float[a];
            b = new float[a];
            for (int i = 0; i < a; i++) {
                int c = anyColour[i];
                r[i] = c >> 16 & 255;
                g[i] = c >> 8 & 255;
                b[i] = c & 255;
            }
            blur(r, side, 1.3f);
            blur(g, side, 1.3f);
            blur(b, side, 1.3f);
            height = hgt;
            blur(height, side, 1.0f);
            wet = wetRaw.clone();
            blur(wet, side, 0.8f);
            wide = wetRaw.clone();
            blur(wide, side, 2.4f);
            depth = grid.depth.clone();
            blur(depth, side, 1.8f);
            known = k;
            blur(known, side, 0.7f);
        }
    }

    /** Colours where {@code has} is false taken from the nearest that is (by a few rounds of spreading). */
    private static int[] fill(int[] colour, boolean[] has, int side, int fallback) {
        int[] c = colour.clone();
        boolean[] h = has.clone();
        for (int round = 0; round < PAD + 4; round++) {
            boolean[] next = h.clone();
            int[] nc = c.clone();
            boolean changed = false;
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    int i = z * side + x;
                    if (h[i]) continue;
                    int rr = 0, gg = 0, bb = 0, cnt = 0;
                    for (int o = 0; o < 4; o++) {
                        int xx = x + (o == 0 ? 1 : o == 1 ? -1 : 0), zz = z + (o == 2 ? 1 : o == 3 ? -1 : 0);
                        if (xx < 0 || zz < 0 || xx >= side || zz >= side) continue;
                        int j = zz * side + xx;
                        if (!h[j]) continue;
                        rr += c[j] >> 16 & 255;
                        gg += c[j] >> 8 & 255;
                        bb += c[j] & 255;
                        cnt++;
                    }
                    if (cnt > 0) {
                        nc[i] = (rr / cnt) << 16 | (gg / cnt) << 8 | (bb / cnt);
                        next[i] = true;
                        changed = true;
                    }
                }
            }
            c = nc;
            h = next;
            if (!changed) break;
        }
        for (int i = 0; i < c.length; i++) if (!h[i]) c[i] = fallback;
        return c;
    }

    private static float[] fillF(float[] v, boolean[] has, int side, float fallback) {
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = has[i] ? v[i] : Float.NaN;
        return fillNaN(out, side, fallback);
    }

    /** Fills NaNs from their neighbours, round by round; what is never reached takes the fallback. */
    private static float[] fillNaN(float[] v, int side, float fallback) {
        for (int round = 0; round < PAD + 4; round++) {
            float[] next = v.clone();
            boolean changed = false;
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    int i = z * side + x;
                    if (!Float.isNaN(v[i])) continue;
                    float sum = 0;
                    int cnt = 0;
                    if (x > 0 && !Float.isNaN(v[i - 1])) { sum += v[i - 1]; cnt++; }
                    if (x < side - 1 && !Float.isNaN(v[i + 1])) { sum += v[i + 1]; cnt++; }
                    if (z > 0 && !Float.isNaN(v[i - side])) { sum += v[i - side]; cnt++; }
                    if (z < side - 1 && !Float.isNaN(v[i + side])) { sum += v[i + side]; cnt++; }
                    if (cnt > 0) {
                        next[i] = sum / cnt;
                        changed = true;
                    }
                }
            }
            v = next;
            if (!changed) break;
        }
        for (int i = 0; i < v.length; i++) if (Float.isNaN(v[i])) v[i] = fallback;
        return v;
    }

    /** A gaussian blur in place (separable, edges clamped). */
    private static void blur(float[] v, int side, float sigma) {
        int radius = (int) Math.ceil(sigma * 2.5f);
        float[] w = new float[radius * 2 + 1];
        float sum = 0;
        for (int i = -radius; i <= radius; i++) sum += w[i + radius] = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
        for (int i = 0; i < w.length; i++) w[i] /= sum;
        float[] tmp = new float[v.length];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                float s = 0;
                for (int k = -radius; k <= radius; k++) s += w[k + radius] * v[z * side + Math.clamp(x + k, 0, side - 1)];
                tmp[z * side + x] = s;
            }
        }
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                float s = 0;
                for (int k = -radius; k <= radius; k++) s += w[k + radius] * tmp[Math.clamp(z + k, 0, side - 1) * side + x];
                v[z * side + x] = s;
            }
        }
    }

    /** The explored chunks of the 3 by 3 regions round a region, as 1 or 0 in one grid of chunks. */
    private static float[] fogField(long[] @Nullable [] fog) {
        int c = MapGrid.REGION_CHUNKS, side = 3 * c;
        float[] out = new float[side * side];
        for (int rz = 0; rz < 3; rz++) {
            for (int rx = 0; rx < 3; rx++) {
                long[] bits = fog == null ? null : fog[rz * 3 + rx];
                if (bits == null) continue;
                for (int z = 0; z < c; z++) {
                    for (int x = 0; x < c; x++) {
                        if (MapGrid.get(bits, z * c + x)) out[(rz * c + z) * side + rx * c + x] = 1;
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ sampling

    private static float at(float[] v, int side, int x, int z) {
        return v[Math.clamp(z, 0, side - 1) * side + Math.clamp(x, 0, side - 1)];
    }

    static float bilinear(float[] v, int side, float x, float z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        float fx = x - x0, fz = z - z0;
        float a = at(v, side, x0, z0) + (at(v, side, x0 + 1, z0) - at(v, side, x0, z0)) * fx;
        float b = at(v, side, x0, z0 + 1) + (at(v, side, x0 + 1, z0 + 1) - at(v, side, x0, z0 + 1)) * fx;
        return a + (b - a) * fz;
    }

    /** Catmull-Rom interpolation: a smooth curve through the samples. */
    static float cubic(float[] v, int side, float x, float z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        float fx = x - x0, fz = z - z0;
        return curve(row(v, side, x0, z0 - 1, fx), row(v, side, x0, z0, fx), row(v, side, x0, z0 + 1, fx), row(v, side, x0, z0 + 2, fx), fz);
    }

    private static float row(float[] v, int side, int x0, int z, float fx) {
        return curve(at(v, side, x0 - 1, z), at(v, side, x0, z), at(v, side, x0 + 1, z), at(v, side, x0 + 2, z), fx);
    }

    private static float curve(float p0, float p1, float p2, float p3, float t) {
        return 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
    }

    static float smooth(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    // ------------------------------------------------------------------------------------------------ colour

    private static int rgb(float r, float g, float b) {
        return Math.clamp((int) r, 0, 255) << 16 | Math.clamp((int) g, 0, 255) << 8 | Math.clamp((int) b, 0, 255);
    }

    static int mix(int a, int b, float t) {
        t = Math.clamp(t, 0f, 1f);
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t + 0.5f);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t + 0.5f);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t + 0.5f);
        return r << 16 | g << 8 | bl;
    }

    private static int scale(int c, float light) {
        return rgb((c >> 16 & 255) * light, (c >> 8 & 255) * light, (c & 255) * light);
    }

    /** Smooth value noise in world coordinates: the same everywhere for everyone, so tiles join. */
    static final class Noise {
        private Noise() {
        }

        private static float hash(int x, int z, int seed) {
            int h = x * 0x27D4EB2D ^ z * 0x165667B1 ^ seed * 0x9E3779B9;
            h ^= h >>> 15;
            h *= 0x85EBCA6B;
            h ^= h >>> 13;
            h *= 0xC2B2AE35;
            h ^= h >>> 16;
            return (h & 0xFFFFFF) / (float) 0xFFFFFF;
        }

        static float value(float x, float z, int seed) {
            int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
            float fx = x - x0, fz = z - z0;
            fx = fx * fx * (3 - 2 * fx);
            fz = fz * fz * (3 - 2 * fz);
            float a = hash(x0, z0, seed) + (hash(x0 + 1, z0, seed) - hash(x0, z0, seed)) * fx;
            float b = hash(x0, z0 + 1, seed) + (hash(x0 + 1, z0 + 1, seed) - hash(x0, z0 + 1, seed)) * fx;
            return a + (b - a) * fz;
        }

        /** Three octaves, from 0 to 1 (mostly near the middle). */
        static float fbm(float x, float z, int seed) {
            return (value(x, z, seed) * 4 + value(x * 2.03f, z * 2.03f, seed + 17) * 2 + value(x * 4.11f, z * 4.11f, seed + 31)) / 7f;
        }
    }
}
