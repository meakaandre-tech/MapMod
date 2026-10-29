package com.meakaandre.mapmod.client.map;

/**
 * The clouds over unexplored land, drawn as on Elden Ring's map: puffy parchment clouds with an inked rim, over a
 * dark slate sky, the same everywhere for everyone (they depend only on the world position). The pattern repeats every
 * {@link #PERIOD} blocks, so one texture of it can fill the map's background where nothing is explored at all.
 */
public final class Clouds {
    /** Blocks after which the pattern repeats. */
    public static final int PERIOD = 1024;
    /** The sky between clouds, the clouds, their shaded underside and their inked rim. */
    static final int SKY = 0x3B4650, CLOUD = 0xD6CAA6, CLOUD_SHADE = 0xB4A684, RIM = 0x5C5140;
    /** Blocks across a lattice cell of the big and the small noise (each divides PERIOD). */
    private static final int BIG = 128, SMALL = 32;

    private Clouds() {
    }

    /** The colour (0xRRGGBB) of the clouds at a world position. */
    public static int at(double x, double z) {
        return colour(value(x, z), x, z, noise(x, z, SMALL, 7));
    }

    /** How cloudy a spot is (a cloud from 0.5 up); smooth, so it may be sampled sparsely and blended. */
    static double value(double x, double z) {
        // a slow bend, then big puffs with smaller ones round their edges
        double wx = x + (noise(x, z, SMALL * 4, 11) - 0.5) * 60, wz = z + (noise(x, z, SMALL * 4, 12) - 0.5) * 60;
        return noise(wx, wz, BIG * 2, 1) * 0.55 + noise(wx, wz, BIG, 2) * 0.3 + noise(wx, wz, SMALL, 3) * 0.15;
    }

    /** The clouds' colour for a cloudiness {@code v}, with {@code wobble} (0 to 1, smooth) bending the brush lines. */
    static int colour(double v, double x, double z, double wobble) {
        final double edge = 0.5;
        if (v < edge - 0.012) {
            // the sky, with faint streaks along it
            double streak = Math.sin((z + wobble * 30) / 3.0);
            return streak > 0.9 ? 0x434F59 : SKY;
        }
        if (v < edge) return RIM;
        // a cloud: shaded near its rim, lighter inside, with faint brush lines
        float inside = (float) Math.min(1, (v - edge) / 0.07);
        int c = MapPainter.mix(CLOUD_SHADE, CLOUD, inside);
        double lines = Math.sin((x * 0.7 + z * 0.35) / 3.0 + wobble * 6);
        if (lines > 0.92) c = MapPainter.mix(c, CLOUD_SHADE, 0.35f);
        return c;
    }

    /** Smooth value noise from 0 to 1 with lattice cells of {@code cell} blocks, repeating every PERIOD blocks. */
    static double noise(double x, double z, int cell, int seed) {
        int n = PERIOD / cell;
        double gx = x / cell, gz = z / cell;
        int x0 = (int) Math.floor(gx), z0 = (int) Math.floor(gz);
        double fx = gx - x0, fz = gz - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = hash(x0, z0, n, seed), b = hash(x0 + 1, z0, n, seed), c = hash(x0, z0 + 1, n, seed), d = hash(x0 + 1, z0 + 1, n, seed);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }

    private static double hash(int x, int z, int n, int seed) {
        x = Math.floorMod(x, n);
        z = Math.floorMod(z, n);
        int h = x * 0x27D4EB2D ^ z * 0x165667B1 ^ seed * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }
}
