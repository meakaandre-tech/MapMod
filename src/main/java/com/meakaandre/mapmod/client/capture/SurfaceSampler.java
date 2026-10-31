package com.meakaandre.mapmod.client.capture;

import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * Turns a {@link ChunkSnapshot} into the chunk's 8x8 samples, one per 2x2 block cell (MM-8; 4x4 cells before), on the
 * capture worker.
 * <p>
 * Each column is read top down: air, plants, snow layers, leaves, logs and huge mushrooms are looked past (they count as
 * canopy, so tree trunks do not poke up through the terrain; only trees make forest floor), water is looked through, and
 * the first other solid block (one that blocks motion) is the ground. A sample takes its height, water depth, class and
 * biome tint from its cell's middle column (block 1,1 of the cell) and its canopy from all four.
 * <p>
 * MM-8: its colour is what is seen from above, averaged over the cell's columns that are as wet as the middle one: the
 * first thing each column shows (a snow layer, leaves or a mushroom cap, the water, the ground), as {@link BlockColours}
 * gives it: the top face's average texture colour times its tint (the biome's grass, foliage or water colour). The
 * class is kept for effects only (water, forest, snow) and for a palette's class colour overrides.
 */
final class SurfaceSampler {
    static final int N = SurfaceTile.PER_CHUNK, SP = SurfaceTile.SPACING;
    /** Of a cell's columns, how many must have canopy over grass or dirt for it to count as forest floor. */
    private static final int FOREST_COLUMNS = SP * SP / 2;

    /** One chunk's samples, sx + sz * {@link #N}. */
    static final class Samples {
        final int cx, cz;
        final short[] height = new short[N * N];
        final byte[] water = new byte[N * N], surface = new byte[N * N], canopy = new byte[N * N];
        final short[] tint = new short[N * N];
        final int[] colour = new int[N * N];

        Samples(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    /** One column as the capture reads it. */
    static final class Columns {
        final int[] ground = new int[256], waterTop = new int[256];
        final byte[] cls = new byte[256];
        final boolean[] canopy = new boolean[256], trees = new boolean[256], snowed = new boolean[256];
        /** The first thing the column shows from above (null: nothing but air). */
        final @Nullable BlockState[] top = new BlockState[256];
    }

    private SurfaceSampler() {
    }

    static Columns columns(ChunkSnapshot s) {
        Columns c = new Columns();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) column(s, x, z, c);
        return c;
    }

    static Samples sample(ChunkSnapshot s) {
        Samples out = new Samples(s.cx, s.cz);
        Columns c = columns(s);
        for (int sz = 0; sz < N; sz++) {
            for (int sx = 0; sx < N; sx++) {
                int n = sx + sz * N;
                int covered = 0, wooded = 0;
                for (int dz = 0; dz < SP; dz++) {
                    for (int dx = 0; dx < SP; dx++) {
                        int j = (sx * SP + dx) + (sz * SP + dz) * 16;
                        if (c.canopy[j]) covered++;
                        if (c.trees[j]) wooded++;
                    }
                }
                int mid = mid(sx, sz);
                int depth = c.waterTop[mid] == Integer.MIN_VALUE ? 0 : Math.clamp(c.waterTop[mid] - c.ground[mid], 1, 255);
                byte cls = c.cls[mid];
                if (depth == 0 && c.snowed[mid]) cls = SurfaceClass.SNOW;
                boolean forest = depth == 0 && wooded >= FOREST_COLUMNS && (cls == SurfaceClass.GRASS || cls == SurfaceClass.DIRT);
                if (forest) cls = SurfaceClass.FOREST;
                out.height[n] = (short) c.ground[mid];
                out.water[n] = (byte) depth;
                out.surface[n] = cls;
                out.canopy[n] = (byte) (covered * 255 / (SP * SP));
                int x = (s.cx << 4) + sx * SP + SP / 2, z = (s.cz << 4) + sz * SP + SP / 2;
                Holder<Biome> biome = s.biome(sx * SP + SP / 2, sz * SP + SP / 2);
                out.tint[n] = rgb565(tint(biome, cls == SurfaceClass.FOREST, x, z));
                out.colour[n] = cellColour(s, c, sx, sz, biome);
            }
        }
        return out;
    }

    /** The middle column of a cell (its block 1,1). */
    static int mid(int sx, int sz) {
        return (sx * SP + SP / 2) + (sz * SP + SP / 2) * 16;
    }

    /** The cell's colour from above: the mean of its columns that are as wet as its middle one. */
    static int cellColour(ChunkSnapshot s, Columns c, int sx, int sz, @Nullable Holder<Biome> biome) {
        boolean wet = c.waterTop[mid(sx, sz)] != Integer.MIN_VALUE;
        int r = 0, g = 0, b = 0, n = 0;
        for (int dz = 0; dz < SP; dz++) {
            for (int dx = 0; dx < SP; dx++) {
                int x = sx * SP + dx, z = sz * SP + dz, j = x + z * 16;
                if ((c.waterTop[j] != Integer.MIN_VALUE) != wet) continue;
                int col = columnColour(c, j, biome, (s.cx << 4) + x, (s.cz << 4) + z);
                r += col >> 16 & 255;
                g += col >> 8 & 255;
                b += col & 255;
                n++;
            }
        }
        if (n == 0) return 0x808080;
        return (r + n / 2) / n << 16 | (g + n / 2) / n << 8 | (b + n / 2) / n;
    }

    /** What one column shows from above, tinted for the biome. */
    static int columnColour(Columns c, int j, @Nullable Holder<Biome> biome, int x, int z) {
        BlockState top = c.top[j];
        if (top == null) return 0x808080;
        return BlockColours.colour(top, biome, x, z);
    }

    /** One column read as the capture reads it, for {@code /mapmod probe}: {ground y, class}. */
    static int[] ground(ChunkSnapshot s, int x, int z) {
        Columns c = new Columns();
        column(s, x, z, c);
        int i = x + z * 16;
        return new int[]{c.ground[i], c.cls[i]};
    }

    private static void column(ChunkSnapshot s, int x, int z, Columns c) {
        int i = x + z * 16;
        c.waterTop[i] = Integer.MIN_VALUE;
        c.ground[i] = s.bottomY;
        c.cls[i] = SurfaceClass.ROCK;
        for (int y = s.top[i] - 1; y >= s.bottomY; y--) {
            BlockState state = s.get(x, y, z);
            if (state.isAir()) continue;
            FluidState fluid = state.getFluidState();
            boolean dry = c.waterTop[i] == Integer.MIN_VALUE;
            if (fluid.is(FluidTags.LAVA)) {
                c.ground[i] = y;
                c.cls[i] = SurfaceClass.LAVA;
                if (c.top[i] == null) c.top[i] = state;
                return;
            }
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                if (dry) {
                    c.canopy[i] = c.trees[i] = true;
                    if (c.top[i] == null) c.top[i] = state;
                }
                continue;
            }
            if (state.getBlock() instanceof HugeMushroomBlock) {
                // a huge mushroom's cap and stem: canopy over the ground, but not a forest
                if (dry) {
                    c.canopy[i] = true;
                    if (c.top[i] == null) c.top[i] = state;
                }
                continue;
            }
            boolean solid = state.isSolid();
            if (fluid.is(FluidTags.WATER) && !solid) {
                // water, and plants in it (kelp, seagrass): the water's colour is what shows
                if (dry) {
                    c.waterTop[i] = y;
                    if (c.top[i] == null) c.top[i] = fluid.createLegacyBlock();
                }
                continue;
            }
            if (solid) {
                c.ground[i] = y;
                c.cls[i] = SurfaceClass.cached(state);
                if (c.top[i] == null) c.top[i] = state;
                return;
            }
            // plants, torches and the like are looked past; a snow layer whitens what is under it
            if (dry && state.is(BlockTags.SNOW)) {
                c.snowed[i] = true;
                if (c.top[i] == null) c.top[i] = state;
            }
        }
    }

    private static int tint(@Nullable Holder<Biome> biome, boolean foliage, int x, int z) {
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
