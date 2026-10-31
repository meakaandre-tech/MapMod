package com.meakaandre.mapmod.selftest.client;

import com.meakaandre.mapmod.client.capture.SurfaceClass;
import com.meakaandre.mapmod.client.capture.SurfaceTile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Made-up but plausible tiles for the selftests (MM-4): rolling hills from smooth noise in world space (so neighbours
 * meet), sea and lakes below 62 with sand round them, forest patches, rock and snow on the heights. Used to give the
 * smoke run a large explored area to zoom out over without walking it, and by the bake cache check.
 */
final class SyntheticTiles {
    private SyntheticTiles() {
    }

    private static double hash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) / (double) (1L << 53);
    }

    private static double noise(double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        double ux = fx * fx * (3 - 2 * fx), uz = fz * fz * (3 - 2 * fz);
        double a = hash(ix, iz), b = hash(ix + 1, iz), c = hash(ix, iz + 1), d = hash(ix + 1, iz + 1);
        return a + (b - a) * ux + (c - a) * uz + (a - b - c + d) * ux * uz;
    }

    /** Octaves of noise over world blocks, 0 to 1. */
    private static double fbm(double x, double z, double scale) {
        double sum = 0, amp = 0.5, norm = 0;
        for (int o = 0; o < 4; o++) {
            sum += amp * noise(x / scale, z / scale);
            norm += amp;
            amp *= 0.5;
            scale /= 2;
        }
        return sum / norm;
    }

    static SurfaceTile make(int tx, int tz) {
        SurfaceTile t = new SurfaceTile(tx, tz);
        for (int sz = 0; sz < SurfaceTile.SIDE; sz++) {
            for (int sx = 0; sx < SurfaceTile.SIDE; sx++) {
                int i = SurfaceTile.index(sx, sz);
                double wx = tx * (double) SurfaceTile.BLOCKS + sx * SurfaceTile.SPACING + SurfaceTile.SPACING / 2.0;
                double wz = tz * (double) SurfaceTile.BLOCKS + sz * SurfaceTile.SPACING + SurfaceTile.SPACING / 2.0;
                int h = (int) Math.round(30 + fbm(wx, wz, 900) * 150);
                int water = Math.max(0, 62 - h);
                double forest = fbm(wx + 7000, wz - 3000, 300);
                byte cls;
                if (water > 0) cls = SurfaceClass.SAND;
                else if (h < 65) cls = SurfaceClass.SAND;
                else if (h > 175) cls = SurfaceClass.SNOW;
                else if (h > 140) cls = SurfaceClass.ROCK;
                else if (forest > 0.55) cls = SurfaceClass.FOREST;
                else cls = SurfaceClass.GRASS;
                t.height[i] = (short) h;
                t.water[i] = (byte) Math.min(255, water);
                t.surface[i] = cls;
                // grass green in RGB565, a little browner on the plains
                int g = 40 + (int) (forest * 20);
                t.tint[i] = (short) (12 << 11 | g << 5 | 8);
                t.canopy[i] = (byte) (cls == SurfaceClass.FOREST ? Math.min(255, (int) ((forest - 0.5) * 900)) : 0);
                // MM-8: colours as the capture would store them (water, sand, snow, stone, oak leaves, grass)
                t.colour[i] = water > 0 ? 0x2A4F86 : switch (cls) {
                    case SurfaceClass.SAND -> 0xDBCFA3;
                    case SurfaceClass.SNOW -> 0xF4F8F8;
                    case SurfaceClass.ROCK -> 0x7D7D7D;
                    case SurfaceClass.FOREST -> 0x3A6B22;
                    default -> 0x4E7A2C + (int) (forest * 12) * 0x010000;
                };
                t.explored[i >>> 6] |= 1L << i;
            }
        }
        return t;
    }

    /**
     * Writes made-up tiles in a square ring round a tile: within {@code outer} tiles of it but beyond {@code inner}, so
     * the ground the player really explored stays as captured. Returns how many were written.
     */
    static int writeRing(Path dir, int ctx, int ctz, int inner, int outer) throws IOException {
        Files.createDirectories(dir);
        int n = 0;
        for (int tz = ctz - outer; tz <= ctz + outer; tz++) {
            for (int tx = ctx - outer; tx <= ctx + outer; tx++) {
                if (Math.max(Math.abs(tx - ctx), Math.abs(tz - ctz)) <= inner) continue;
                Path f = dir.resolve(tx + "_" + tz + ".mmt");
                Files.write(f, make(tx, tz).pack());
                n++;
            }
        }
        return n;
    }
}
