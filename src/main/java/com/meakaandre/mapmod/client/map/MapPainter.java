package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapTile;
import org.jspecify.annotations.Nullable;


/**
 * Paints a region of the map as a flat painted map, mostly in the manner of Elden Ring's and a little of Satisfactory's:
 * the land in soft colours on parchment (each block's own colour, blended with its neighbours' and pulled toward a
 * parchment tone, so areas read as patches of colour, not blocks), coastlines in dark ink, water in slate blue with faint
 * brush strokes and a pale band along the shore, a paper grain over everything, and empty parchment where nothing has been
 * explored. Nothing is shaded by height: the map is flat.
 */
final class MapPainter {
    /** Water: open, by the shore, and its brush strokes. */
    private static final int WATER = 0x5D7682, SHORE_WATER = 0x8FA3A4, STROKE = 0x9AA9A8;
    /** The ink of coastlines. */
    private static final int INK = 0x2F2A21;
    /** The parchment tone land colours are pulled toward (a ramp from dark sepia to light parchment), and the share pulled. */
    private static final int PARCHMENT_DARK = 0x5B4B33, PARCHMENT_LIGHT = 0xE2D5B0;
    private static final float PARCHMENT_SHARE = 0.68f;
    /** Pixels read from the neighbouring regions round a region: enough for the blends. */
    static final int PAD = 8;
    /** Blocks a land colour is blended over either way, and the water's outline. */
    private static final int COLOUR_BLUR = 2, WATER_BLUR = 1, SHORE_BLUR = 4;
    /** How much noise bends the edge of the explored land (in shares of a chunk's worth of blending). */
    private static final float FOG_BEND = 0.7f;

    /** A block's colour, as the client works it out (its texture tinted by its biome). */
    interface Colours {
        int of(MapTile tile, int cell);
    }

    /** Blocks between the samples of the smooth noises, which are blended between (much faster than working each out). */
    private static final int STEP = 8;

    private MapPainter() {
    }

    /**
     * Every colour a tile's palettes can make, painted: by block times biome (see {@link #paletteIndex}). Worked out
     * on the client thread, so painting itself may run on another.
     */
    static int[] palette(MapTile tile, Colours colours) {
        int biomes = Math.max(1, tile.biomes.size());
        int[] out = new int[tile.blocks.size() * biomes];
        for (int bl = 0; bl < tile.blocks.size(); bl++) {
            for (int bi = 0; bi < biomes; bi++) out[bl * biomes + bi] = parchment(colours.of(tile, MapTile.cell(bl, bi, 0, 0)));
        }
        return out;
    }

    private static int paletteIndex(MapTile tile, int cell) {
        return MapTile.blockOf(cell) * Math.max(1, tile.biomes.size()) + MapTile.biomeOf(cell);
    }

    /** A smooth noise sampled every STEP blocks over a region, for blending between. */
    private static final class Grid {
        final int side = MapGrid.REGION_PX / STEP + 2;
        final float[] v = new float[side * side];

        Grid(int x0, int z0, java.util.function.DoubleBinaryOperator f) {
            for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) v[j * side + i] = (float) f.applyAsDouble(x0 + i * STEP, z0 + j * STEP);
        }

        float at(int px, int pz) {
            int i = px / STEP, j = pz / STEP;
            float fx = (px % STEP) / (float) STEP, fz = (pz % STEP) / (float) STEP;
            int k = j * side + i;
            float a = v[k] + (v[k + 1] - v[k]) * fx, b = v[k + side] + (v[k + side + 1] - v[k + side]) * fx;
            return a + (b - a) * fz;
        }
    }

    /**
     * Paints the region whose north-west corner is at block (x0, z0) into {@code out} (0xRRGGBB a pixel). {@code near}
     * holds the 3 by 3 tiles round it (it in the middle; missing ones null) and {@code palettes} their {@link #palette}s,
     * {@code fog} their explored chunks. Reads nothing else, so it may run off the client thread.
     */
    static void paint(int[] out, int x0, int z0, @Nullable MapTile[] near, int[] @Nullable [] palettes, long[] @Nullable [] fog) {
        int n = MapGrid.REGION_PX, side = n + 2 * PAD;
        // the padded grid: land colour (weighted by w, land only), whether it is water, whether anything is known
        float[] r = new float[side * side], g = new float[side * side], b = new float[side * side], w = new float[side * side];
        float[] wet = new float[side * side], known = new float[side * side];
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                int px = gx - PAD, pz = gz - PAD;
                int tx = px < 0 ? -1 : px >= n ? 1 : 0, tz = pz < 0 ? -1 : pz >= n ? 1 : 0;
                int ti = (tz + 1) * 3 + tx + 1;
                MapTile t = near[ti];
                int[] pal = palettes[ti];
                int k = gz * side + gx;
                if (t == null || pal == null) continue;
                int i = MapTile.index(Math.floorMod(px, n), Math.floorMod(pz, n));
                if (!t.known(i)) continue;
                int cell = t.cell[i];
                known[k] = 1;
                if (MapTile.water(cell)) {
                    wet[k] = 1;
                    continue;
                }
                int pi = paletteIndex(t, cell);
                if (pi >= pal.length) continue;
                int c = pal[pi];
                r[k] = c >> 16 & 255;
                g[k] = c >> 8 & 255;
                b[k] = c & 255;
                w[k] = 1;
            }
        }
        // land colours blended with their neighbours (land only), water and what is known smoothed a little
        blur(r, side, COLOUR_BLUR);
        blur(g, side, COLOUR_BLUR);
        blur(b, side, COLOUR_BLUR);
        blur(w, side, COLOUR_BLUR);
        float[] shore = wet.clone();
        blur(wet, side, WATER_BLUR);
        blur(shore, side, SHORE_BLUR);
        blur(known, side, 1);
        float[] explored = fogField(fog);
        Grid bend = new Grid(x0, z0, (x, z) -> Parchment.noise(x, z, 32, 31));
        Grid broad = new Grid(x0, z0, (x, z) -> Parchment.noise(x, z, 32, 41));
        Grid paper = new Grid(x0, z0, Parchment::value);
        Grid strokeAt = new Grid(x0, z0, (x, z) -> Parchment.noise(x, z, 64, 21));
        Grid strokeOn = new Grid(x0, z0, (x, z) -> Parchment.noise(x, z, 32, 22));
        int cs = 3 * MapGrid.REGION_CHUNKS;
        for (int pz = 0; pz < n; pz++) {
            for (int px = 0; px < n; px++) {
                int k = (pz + PAD) * side + px + PAD;
                int bx = x0 + px, bz = z0 + pz;
                // explored: the explored chunks blended between their middles, the edge bent by noise into a soft ragged rim
                float cx = (px + n) / (float) MapGrid.CHUNK_PX - 0.5f, cz = (pz + n) / (float) MapGrid.CHUNK_PX - 0.5f;
                float seen = bilinear(explored, cs, cx, cz) + (bend.at(px, pz) - 0.5f) * FOG_BEND;
                float grainLight = (float) (1 + (hash(bx, bz) - 0.5) * 0.05 + (broad.at(px, pz) - 0.5) * 0.08);
                int rgb;
                if (seen < 0.5f) {
                    rgb = Parchment.colour(paper.at(px, pz), bx, bz);
                } else if (known[k] < 0.5f) {
                    rgb = Parchment.colour(paper.at(px, pz), bx, bz);
                } else if (wet[k] >= 0.5f) {
                    if (edge(wet, side, k)) {
                        // ink where the water meets the land
                        rgb = INK;
                    } else {
                        // water: paler by the shore, with faint strokes
                        rgb = mix(SHORE_WATER, WATER, smooth(0.55f, 0.95f, shore[k]));
                        double stroke = Math.sin((bz + strokeAt.at(px, pz) * 24) / 2.2);
                        if (stroke > 0.94 && strokeOn.at(px, pz) > 0.45f) rgb = mix(rgb, STROKE, 0.35f);
                        rgb = scale(rgb, grainLight);
                    }
                } else if (w[k] < 1e-3f) {
                    rgb = Parchment.colour(paper.at(px, pz), bx, bz);
                } else {
                    float wt = w[k];
                    rgb = scale(Math.clamp((int) (r[k] / wt), 0, 255) << 16 | Math.clamp((int) (g[k] / wt), 0, 255) << 8
                        | Math.clamp((int) (b[k] / wt), 0, 255), grainLight);
                }
                out[pz * n + px] = rgb;
            }
        }
    }

    /** The explored chunks of the 3 by 3 regions round a region, as 1 or 0 in one grid of chunks. */
    private static float[] fogField(long[] @Nullable [] fog) {
        int c = MapGrid.REGION_CHUNKS, side = 3 * c;
        float[] out = new float[side * side];
        for (int rz = 0; rz < 3; rz++) {
            for (int rx = 0; rx < 3; rx++) {
                long[] bits = fog[rz * 3 + rx];
                if (bits == null) continue;
                for (int z = 0; z < c; z++) for (int x = 0; x < c; x++) if (MapGrid.get(bits, z * c + x)) out[(rz * c + z) * side + rx * c + x] = 1;
            }
        }
        return out;
    }

    private static float bilinear(float[] v, int side, float x, float z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        float fx = x - x0, fz = z - z0;
        int xa = Math.clamp(x0, 0, side - 1), xb = Math.clamp(x0 + 1, 0, side - 1), za = Math.clamp(z0, 0, side - 1), zb = Math.clamp(z0 + 1, 0, side - 1);
        float a = v[za * side + xa] + (v[za * side + xb] - v[za * side + xa]) * fx;
        float b2 = v[zb * side + xa] + (v[zb * side + xb] - v[zb * side + xa]) * fx;
        return a + (b2 - a) * fz;
    }

    /** True for water next to land (after smoothing): where the coast's ink line goes. */
    private static boolean edge(float[] wet, int side, int k) {
        return wet[k - 1] < 0.5f || wet[k + 1] < 0.5f || wet[k - side] < 0.5f || wet[k + side] < 0.5f;
    }

    /**
     * A block colour as the map paints it: pulled toward parchment (a ramp from dark sepia to light parchment by the
     * colour's lightness), keeping enough of its own hue to tell grass, sand, snow and stone apart.
     */
    static int parchment(int c) {
        int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
        float light = (r * 0.3f + g * 0.59f + b * 0.11f) / 255f;
        int tone = mix(PARCHMENT_DARK, PARCHMENT_LIGHT, Math.clamp(light * 1.15f, 0f, 1f));
        // a little lighter and softer overall, as paint on paper
        return mix(mix(c, tone, PARCHMENT_SHARE), PARCHMENT_LIGHT, 0.08f);
    }

    private static double hash(int x, int z) {
        int h = x * 0x27D4EB2D ^ z * 0x165667B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h & 0xFFFF) / 65535.0;
    }

    /** A box blur either way, in place (edges clamped). */
    private static void blur(float[] v, int side, int radius) {
        float[] tmp = new float[v.length];
        float norm = 1f / (2 * radius + 1);
        for (int z = 0; z < side; z++) {
            int row = z * side;
            for (int x = 0; x < side; x++) {
                float s = 0;
                for (int d = -radius; d <= radius; d++) s += v[row + Math.clamp(x + d, 0, side - 1)];
                tmp[row + x] = s * norm;
            }
        }
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                float s = 0;
                for (int d = -radius; d <= radius; d++) s += tmp[Math.clamp(z + d, 0, side - 1) * side + x];
                v[z * side + x] = s * norm;
            }
        }
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    static int mix(int a, int b, float t) {
        t = Math.clamp(t, 0f, 1f);
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t + 0.5f);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t + 0.5f);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t + 0.5f);
        return r << 16 | g << 8 | bl;
    }

    private static int scale(int c, float light) {
        int r = Math.clamp((int) ((c >> 16 & 255) * light), 0, 255), g = Math.clamp((int) ((c >> 8 & 255) * light), 0, 255);
        int b = Math.clamp((int) ((c & 255) * light), 0, 255);
        return r << 16 | g << 8 | b;
    }
}
