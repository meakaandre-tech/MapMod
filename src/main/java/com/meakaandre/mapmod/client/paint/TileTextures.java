package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * One tile's fields on the GPU: the {@link TilePrep#TEXTURES} RGBA8 textures {@link TilePrep} lays out (fields, surface
 * with canopy, two of blurred class weights, shore distance), each {@link TilePrep#SIZE} square with the apron. The
 * work is done off-thread; {@link #upload} only copies the prepared pixels in. Render thread only.
 */
final class TileTextures implements AutoCloseable {
    static final int APRON = TilePrep.APRON, SIZE = TilePrep.SIZE;
    private static final String[] NAMES = {"fields", "surface", "classes a", "classes b", "shore"};

    final DynamicTexture[] textures = new DynamicTexture[TilePrep.TEXTURES];
    /** The stamps of the tile and its neighbours this upload was made from. */
    long key;

    private TileTextures(int tx, int tz) {
        for (int i = 0; i < textures.length; i++) {
            String name = NAMES[i];
            textures[i] = new DynamicTexture(() -> MapMod.ID + " map " + name + " " + tx + "," + tz, new NativeImage(SIZE, SIZE, false));
        }
    }

    static TileTextures create(int tx, int tz) {
        return new TileTextures(tx, tz);
    }

    /** Copies prepared pixels in and uploads every texture. */
    void upload(TilePrep.Result prepared) {
        for (int t = 0; t < textures.length; t++) {
            NativeImage image = textures[t].getPixels();
            if (image == null) return;
            int[] px = prepared.pixels()[t];
            for (int z = 0; z < SIZE; z++) {
                int row = z * SIZE;
                for (int x = 0; x < SIZE; x++) image.setPixel(x, z, px[row + x]);
            }
        }
        for (DynamicTexture t : textures) t.upload();
    }

    /** A texel as it was uploaded (ARGB), for the selftests. */
    int pixel(int texture, int x, int z) {
        NativeImage image = textures[texture].getPixels();
        return image == null ? 0 : image.getPixel(x, z);
    }

    @Override
    public void close() {
        for (DynamicTexture t : textures) t.close();
    }
}
