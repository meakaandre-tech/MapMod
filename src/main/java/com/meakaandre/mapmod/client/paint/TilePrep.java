package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.client.capture.SurfaceTile;
import org.jspecify.annotations.Nullable;

/**
 * Works out one tile's picture from the tile and its neighbours, on a background thread (MM-9): the render thread only
 * copies the result into the tile's texture ({@link TileTextures#upload}) and draws it. The picture is {@link #SIZE}
 * square, one texel per block, ARGB as {@code NativeImage.setPixel} takes it, premultiplied: an explored block is opaque
 * in its final colour, an unexplored one is 0 (transparent black), so averaging texels into mips stays right and the
 * screen lays the flat background under what is not there.
 * <p>
 * A block's colour is its stored colour ({@link SurfaceTile#colour}, or its class's where the palette overrides it,
 * {@link PaintPalette#landBase}), then the height shading ({@link PaintPalette#shade}): how much higher it is than its
 * north neighbour plus how much higher than its west one, read across tile borders from the neighbouring tiles, so tiles
 * meet without a seam. On land the height is the top that shows (a tree's crown stands up); under water it is the floor,
 * which then shows through the water ({@link PaintPalette#water}), darker with depth, the water in its biome's colour. A
 * dry block next to water compares with the water's surface, so coasts are lit like any rise.
 */
public final class TilePrep {
    public static final int SIZE = SurfaceTile.SIDE;

    private TilePrep() {
    }

    /** The picture's pixels, ARGB premultiplied, {@code pixels[x + z * SIZE]}. */
    public record Result(int[] pixels) {
        public int pixel(int x, int z) {
            return pixels[x + z * SIZE];
        }
    }

    /** Works out a tile's picture with the palette in use now. */
    public static Result prepare(@Nullable SurfaceTile[][] around) {
        return prepare(around, PaintPalette.current());
    }

    /** Works out a tile's picture from it and its neighbours ({@code around[dz + 1][dx + 1]}, null where none). */
    public static Result prepare(@Nullable SurfaceTile[][] around, PaintPalette pal) {
        int[] out = new int[SIZE * SIZE];
        SurfaceTile t = around[1][1];
        if (t == null) return new Result(out);
        SurfaceTile north = around[0][1], west = around[1][0];
        int side = SurfaceTile.SIDE;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int i = SurfaceTile.index(x, z);
                if (!t.explored(i)) continue;
                int depth = t.water[i] & 0xFF;
                boolean wet = depth > 0;
                int self = wet ? t.height[i] : top(t, i);
                // the neighbour to the north and to the west, in this tile or the next
                SurfaceTile nt = z > 0 ? t : north, wt = x > 0 ? t : west;
                int ni = SurfaceTile.index(x, z > 0 ? z - 1 : side - 1), wi = SurfaceTile.index(x > 0 ? x - 1 : side - 1, z);
                int rise = rise(self, wet, nt, ni) + rise(self, wet, wt, wi);
                int base = pal.landBase(t.surface[i], t.colour[i], SurfaceTile.tintRgb(t.tint[i]));
                int c = pal.shade(base, rise);
                if (wet) c = pal.water(c, SurfaceTile.tintRgb(t.waterColour[i]), depth);
                out[x + z * SIZE] = 0xFF000000 | c;
            }
        }
        return new Result(out);
    }

    /** The top that shows from above on land: the ground plus what stands on it (a tree's crown). */
    private static int top(SurfaceTile t, int i) {
        return t.height[i] + (t.above[i] & 0xFF);
    }

    /**
     * How much higher a block is than a neighbour (0 if the neighbour is unknown). A wet block compares floors with wet
     * neighbours and its floor with a dry neighbour's top; a dry block compares its top with a wet neighbour's surface.
     */
    private static int rise(int self, boolean wet, @Nullable SurfaceTile n, int i) {
        if (n == null || !n.explored(i)) return 0;
        int depth = n.water[i] & 0xFF;
        int other = depth > 0 ? (wet ? n.height[i] : n.height[i] + depth) : top(n, i);
        return self - other;
    }
}
