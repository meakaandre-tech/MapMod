package com.meakaandre.mapmod.client.map;

/**
 * Empty parchment, where nothing has been explored: a warm paper colour with soft mottling and faint stains, the same
 * everywhere for everyone (it depends only on the world position). The pattern repeats every {@link #PERIOD} blocks, so
 * one texture of it can fill the map's background where nothing is explored at all.
 */
public final class Parchment {
    /** Blocks after which the pattern repeats. */
    public static final int PERIOD = 1024;
    /** The paper, its darker stains and its lighter patches. */
    static final int PAPER = 0xD8CAA2, STAIN = 0xC9B78F, LIGHT = 0xE3D7B4;

    private Parchment() {
    }

    /** The parchment's colour (0xRRGGBB) at a world position. */
    public static int at(double x, double z) {
        return colour(noise(x, z, 256, 1) * 0.6 + noise(x, z, 64, 2) * 0.4, x, z);
    }

    /** The parchment for a smooth mottling value {@code v} (0 to 1, which may be sampled sparsely and blended). */
    static int colour(double v, double x, double z) {
        int c = v < 0.5 ? MapPainter.mix(STAIN, PAPER, (float) Math.min(1, (v - 0.15) / 0.35)) : MapPainter.mix(PAPER, LIGHT, (float) ((v - 0.5) / 0.5) * 0.6f);
        // a fine grain of paper fibres
        int h = (int) Math.floor(x) * 0x27D4EB2D ^ (int) Math.floor(z) * 0x165667B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        float grain = 1 + ((h & 0xFF) / 255f - 0.5f) * 0.05f;
        return Math.clamp((int) ((c >> 16 & 255) * grain), 0, 255) << 16 | Math.clamp((int) ((c >> 8 & 255) * grain), 0, 255) << 8
            | Math.clamp((int) ((c & 255) * grain), 0, 255);
    }

    /** How mottled the parchment is at a spot, for {@link #colour}. */
    static double value(double x, double z) {
        return noise(x, z, 256, 1) * 0.6 + noise(x, z, 64, 2) * 0.4;
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
