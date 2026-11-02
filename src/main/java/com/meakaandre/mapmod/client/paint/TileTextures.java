package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * One tile's picture on the GPU (MM-9): a {@link TilePrep#SIZE}-square RGBA8 texture, a texel a block, as {@link TilePrep}
 * worked it out. The work is done off-thread; {@link #upload} only copies the prepared pixels in. Row 0 of the texture is
 * the tile's north edge (v 0). Render thread only.
 */
final class TileTextures implements AutoCloseable {
    static final int SIZE = TilePrep.SIZE;

    final DynamicTexture texture;
    /** The stamps of the tile and its neighbours this upload was made from. */
    long key;

    private TileTextures(int tx, int tz) {
        texture = new DynamicTexture(() -> MapMod.ID + " map tile " + tx + "," + tz, new NativeImage(SIZE, SIZE, false));
    }

    static TileTextures create(int tx, int tz) {
        return new TileTextures(tx, tz);
    }

    GpuTextureView view() {
        return texture.getTextureView();
    }

    /** Copies prepared pixels in and uploads the texture. */
    void upload(TilePrep.Result prepared) {
        NativeImage image = texture.getPixels();
        if (image == null) return;
        int[] px = prepared.pixels();
        for (int z = 0; z < SIZE; z++) {
            int row = z * SIZE;
            for (int x = 0; x < SIZE; x++) image.setPixel(x, z, px[row + x]);
        }
        texture.upload();
    }

    /** A texel as it was uploaded (ARGB), for the selftests. */
    int pixel(int x, int z) {
        NativeImage image = texture.getPixels();
        return image == null ? 0 : image.getPixel(x, z);
    }

    @Override
    public void close() {
        texture.close();
    }
}
