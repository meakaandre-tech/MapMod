package com.meakaandre.mapmod.client.capture;

import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Turns a {@link ChunkSnapshot} into the chunk's 4x4 samples, one per 4x4 block cell, on the capture worker.
 * <p>
 * Each column is read top down: air, plants, snow layers, leaves, logs and huge mushrooms are looked past (they count as
 * canopy, so tree trunks do not poke up through the terrain; only trees make forest floor), water is looked through, and
 * the first other solid block (one that blocks motion) is the ground. A sample takes its height, water depth, class and
 * tint from its cell's middle column (block 2,2 of the cell) and its canopy from all sixteen. The tint is the biome's grass
 * colour (its foliage colour on forest floor), except on dirt/mud, where it is the ground block's own map colour (MM-7):
 * mycelium, podzol, mud and dirt each paint as their own muted shade of the one class.
 */
final class SurfaceSampler {
    /** Of a cell's 16 columns, how many must have canopy over grass or dirt for it to count as forest floor. */
    private static final int FOREST_COLUMNS = 8;

    /** One chunk's 16 samples, sx + sz * 4. */
    static final class Samples {
        final int cx, cz;
        final short[] height = new short[16];
        final byte[] water = new byte[16], surface = new byte[16], canopy = new byte[16];
        final short[] tint = new short[16];

        Samples(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    private SurfaceSampler() {
    }

    static Samples sample(ChunkSnapshot s) {
        Samples out = new Samples(s.cx, s.cz);
        int[] ground = new int[256], waterTop = new int[256];
        byte[] cls = new byte[256];
        boolean[] canopy = new boolean[256], trees = new boolean[256], snowed = new boolean[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = x + z * 16;
                column(s, x, z, i, ground, waterTop, cls, canopy, trees, snowed);
            }
        }
        for (int sz = 0; sz < 4; sz++) {
            for (int sx = 0; sx < 4; sx++) {
                int n = sx + sz * 4;
                int covered = 0, wooded = 0;
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int j = (sx * 4 + dx) + (sz * 4 + dz) * 16;
                        if (canopy[j]) covered++;
                        if (trees[j]) wooded++;
                    }
                }
                int mid = (sx * 4 + 2) + (sz * 4 + 2) * 16;
                int depth = waterTop[mid] == Integer.MIN_VALUE ? 0 : Math.clamp(waterTop[mid] - ground[mid], 1, 255);
                byte c = cls[mid];
                if (depth == 0 && snowed[mid]) c = SurfaceClass.SNOW;
                boolean forest = depth == 0 && wooded >= FOREST_COLUMNS && (c == SurfaceClass.GRASS || c == SurfaceClass.DIRT);
                if (forest) c = SurfaceClass.FOREST;
                out.height[n] = (short) ground[mid];
                out.water[n] = (byte) depth;
                out.surface[n] = c;
                out.canopy[n] = (byte) (covered * 255 / 16);
                int x = (s.cx << 4) + sx * 4 + 2, z = (s.cz << 4) + sz * 4 + 2;
                out.tint[n] = rgb565(tint(s.biomes[n], c == SurfaceClass.FOREST, x, z));
            }
        }
        return out;
    }

    /** One column read as the capture reads it, for {@code /mapmod probe}: {ground y, class}. */
    static int[] ground(ChunkSnapshot s, int x, int z) {
        int[] ground = new int[256], waterTop = new int[256];
        byte[] cls = new byte[256];
        int i = x + z * 16;
        column(s, x, z, i, ground, waterTop, cls, new boolean[256], new boolean[256], new boolean[256]);
        return new int[]{ground[i], cls[i]};
    }

    private static void column(ChunkSnapshot s, int x, int z, int i, int[] ground, int[] waterTop, byte[] cls, boolean[] canopy,
                               boolean[] trees, boolean[] snowed) {
        waterTop[i] = Integer.MIN_VALUE;
        ground[i] = s.bottomY;
        cls[i] = SurfaceClass.ROCK;
        for (int y = s.top[i] - 1; y >= s.bottomY; y--) {
            BlockState state = s.get(x, y, z);
            if (state.isAir()) continue;
            FluidState fluid = state.getFluidState();
            if (fluid.is(FluidTags.LAVA)) {
                ground[i] = y;
                cls[i] = SurfaceClass.LAVA;
                return;
            }
            boolean dry = waterTop[i] == Integer.MIN_VALUE;
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                if (dry) canopy[i] = trees[i] = true;
                continue;
            }
            if (state.getBlock() instanceof HugeMushroomBlock) {
                // a huge mushroom's cap and stem: canopy over the ground, but not a forest
                if (dry) canopy[i] = true;
                continue;
            }
            boolean solid = state.isSolid();
            if (fluid.is(FluidTags.WATER) && !solid) {
                // water, and plants in it (kelp, seagrass)
                if (dry) waterTop[i] = y;
                continue;
            }
            if (solid) {
                ground[i] = y;
                cls[i] = SurfaceClass.cached(state);
                return;
            }
            // plants, torches and the like; a snow layer whitens what is under it
            if (dry && state.is(BlockTags.SNOW)) snowed[i] = true;
        }
    }

    private static int tint(Holder<Biome> biome, boolean foliage, int x, int z) {
        if (biome == null) return 0x7FB238;
        Biome b = biome.value();
        return foliage ? b.getFoliageColor() : b.getGrassColor(x, z);
    }

    static short rgb565(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return (short) ((r >> 3) << 11 | (g >> 2) << 5 | b >> 3);
    }

    /** RGB565 back to 0xRRGGBB (low bits filled from the high ones). */
    static int rgb888(short c) {
        int v = c & 0xFFFF;
        int r = v >> 11 & 31, g = v >> 5 & 63, b = v & 31;
        return (r << 3 | r >> 2) << 16 | (g << 2 | g >> 4) << 8 | (b << 3 | b >> 2);
    }
}
