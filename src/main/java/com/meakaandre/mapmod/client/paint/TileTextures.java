package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jspecify.annotations.Nullable;

/**
 * One tile's fields on the GPU, as two RGBA8 textures of its 128x128 samples plus an apron of {@link #APRON} samples
 * from its neighbours on every side, so smooth sampling and shading run on across tile edges without a seam:
 * <pre>
 *   fields   r, g  height + 32768, high and low byte   b  water depth (blocks)   a  255 explored, 0 not
 *   surface  rgb   biome tint                          a  surface class * 32 + 16
 * </pre>
 * Unexplored samples get the height of the explored ones beside them (a few samples out), so the relief at the edge of
 * what is known does not dive towards zero. Texel (i + APRON, j + APRON) is sample (i, j) of the tile.
 */
final class TileTextures implements AutoCloseable {
    static final int APRON = 3, SIZE = SurfaceTile.SIDE + 2 * APRON;
    /** Rounds of spreading known heights into unexplored samples. */
    private static final int SPREAD = APRON;
    /** Height for unexplored samples nothing known is near (they are paper anyway). */
    private static final int NO_HEIGHT = 63;

    final DynamicTexture fields, surface;
    /** The stamps of the tile and its neighbours this upload was made from. */
    long key;

    private TileTextures(int tx, int tz) {
        fields = new DynamicTexture(() -> MapMod.ID + " map fields " + tx + "," + tz, new NativeImage(SIZE, SIZE, false));
        surface = new DynamicTexture(() -> MapMod.ID + " map surface " + tx + "," + tz, new NativeImage(SIZE, SIZE, false));
    }

    static TileTextures create(int tx, int tz) {
        return new TileTextures(tx, tz);
    }

    /** Fills both textures from a tile and its neighbours ({@code around[dz + 1][dx + 1]}, null where none) and uploads them. */
    void upload(@Nullable SurfaceTile[][] around) {
        int n = SIZE * SIZE;
        int[] height = new int[n];
        boolean[] known = new boolean[n];
        NativeImage f = fields.getPixels(), s = surface.getPixels();
        if (f == null || s == null) return;
        for (int az = 0; az < SIZE; az++) {
            int sz = az - APRON, dz = Math.floorDiv(sz, SurfaceTile.SIDE), lz = Math.floorMod(sz, SurfaceTile.SIDE);
            for (int ax = 0; ax < SIZE; ax++) {
                int sx = ax - APRON, dx = Math.floorDiv(sx, SurfaceTile.SIDE), lx = Math.floorMod(sx, SurfaceTile.SIDE);
                SurfaceTile t = around[dz + 1][dx + 1];
                int a = ax + az * SIZE, i = SurfaceTile.index(lx, lz);
                if (t != null && t.explored(i)) {
                    known[a] = true;
                    height[a] = t.height[i];
                    int tint = t.tint[i] & 0xFFFF;
                    int r = (tint >> 11 & 31) * 255 / 31, g = (tint >> 5 & 63) * 255 / 63, b = (tint & 31) * 255 / 31;
                    int cls = Math.clamp(t.surface[i], 0, 7);
                    s.setPixel(ax, az, (cls * 32 + 16) << 24 | r << 16 | g << 8 | b);
                    f.setPixel(ax, az, 0xFF000000 | (t.water[i] & 0xFF));
                } else {
                    s.setPixel(ax, az, 16 << 24);
                    f.setPixel(ax, az, 0);
                }
            }
        }
        spread(height, known);
        for (int az = 0; az < SIZE; az++) {
            for (int ax = 0; ax < SIZE; ax++) {
                int a = ax + az * SIZE;
                int h = Math.clamp((known[a] ? height[a] : NO_HEIGHT) + 32768, 0, 65535);
                int argb = f.getPixel(ax, az);
                f.setPixel(ax, az, (argb & 0xFF0000FF) | (h >> 8) << 16 | (h & 0xFF) << 8);
            }
        }
        fields.upload();
        surface.upload();
    }

    /** Gives unexplored samples the mean height of the known ones beside them, a ring at a time. */
    private static void spread(int[] height, boolean[] known) {
        int n = SIZE * SIZE;
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
    }

    @Override
    public void close() {
        fields.close();
        surface.close();
    }
}
