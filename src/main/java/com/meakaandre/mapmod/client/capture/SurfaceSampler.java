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
 * Turns a {@link ChunkSnapshot} into the chunk's 16x16 samples, one per block (MM-9; one per 2x2 cell in MM-8), on the
 * capture worker.
 * <p>
 * Each column is read top down: air, plants and snow layers are looked past, leaves, logs and huge mushrooms are looked
 * past for the ground (so tree trunks do not poke up through it) but are what shows from above, water is looked through,
 * and the first other solid block (one that blocks motion) is the ground. A sample stores the ground's height, its class
 * and biome tint, the water's depth over it, the colour of what shows from above and how far above the ground that is
 * (the shading reads the top: a tree's crown stands up from the land round it).
 * <p>
 * The colour is {@link BlockColours}'s: the top face's average texture colour times its tint (the biome's grass, foliage
 * or water colour). On land it is the first thing the column shows (a snow layer, leaves or a mushroom cap, the
 * ground); under water it is the floor's, and the water's own colour is stored apart, so the map can show the floor
 * through the water, darker with depth. The class only picks which palette class colour override applies.
 */
final class SurfaceSampler {
    static final int N = SurfaceTile.PER_CHUNK;

    /** One chunk's samples, x + z * {@link #N}. */
    static final class Samples {
        final int cx, cz;
        final short[] height = new short[N * N];
        final byte[] water = new byte[N * N], surface = new byte[N * N], above = new byte[N * N];
        final short[] tint = new short[N * N], waterColour = new short[N * N];
        final int[] colour = new int[N * N];

        Samples(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    /** The chunk's columns as the capture reads them. */
    static final class Columns {
        final int[] ground = new int[256], waterTop = new int[256], top = new int[256];
        final byte[] cls = new byte[256];
        final boolean[] trees = new boolean[256], snowed = new boolean[256];
        /** What shows from above on land, the floor under water (null: nothing but air). */
        final @Nullable BlockState[] shown = new BlockState[256];
        /** The water's block where the column is wet. */
        final @Nullable BlockState[] waterState = new BlockState[256];
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
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                int j = x + z * 16;
                boolean wet = c.waterTop[j] != Integer.MIN_VALUE;
                int depth = wet ? Math.clamp(c.waterTop[j] - c.ground[j], 1, 255) : 0;
                byte cls = c.cls[j];
                if (!wet && c.snowed[j]) cls = SurfaceClass.SNOW;
                if (!wet && c.trees[j] && (cls == SurfaceClass.GRASS || cls == SurfaceClass.DIRT)) cls = SurfaceClass.FOREST;
                int bx = (s.cx << 4) + x, bz = (s.cz << 4) + z;
                Holder<Biome> biome = s.biome(x, z);
                out.height[j] = (short) c.ground[j];
                out.water[j] = (byte) depth;
                out.surface[j] = cls;
                out.above[j] = (byte) (wet || c.top[j] == Integer.MIN_VALUE ? 0 : Math.clamp((long) c.top[j] - c.ground[j], 0, 255));
                out.tint[j] = rgb565(tint(biome, cls == SurfaceClass.FOREST, bx, bz));
                BlockState shown = c.shown[j];
                out.colour[j] = shown == null ? 0x808080 : BlockColours.colour(shown, biome, bx, bz);
                BlockState w = c.waterState[j];
                // never 0 on water: 0 means dry
                out.waterColour[j] = w == null ? 0 : (short) Math.max(1, rgb565(BlockColours.colour(w, biome, bx, bz)) & 0xFFFF);
            }
        }
        return out;
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
        c.top[i] = Integer.MIN_VALUE;
        c.cls[i] = SurfaceClass.ROCK;
        for (int y = s.top[i] - 1; y >= s.bottomY; y--) {
            BlockState state = s.get(x, y, z);
            if (state.isAir()) continue;
            FluidState fluid = state.getFluidState();
            boolean dry = c.waterTop[i] == Integer.MIN_VALUE;
            if (fluid.is(FluidTags.LAVA)) {
                ground(c, i, y, SurfaceClass.LAVA, state);
                return;
            }
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.getBlock() instanceof HugeMushroomBlock) {
                // a tree's crown or a huge mushroom shows from above, but the ground is under it (only trees make forest)
                if (dry) {
                    if (!(state.getBlock() instanceof HugeMushroomBlock)) c.trees[i] = true;
                    shows(c, i, y, state);
                }
                continue;
            }
            boolean solid = state.isSolid();
            if (fluid.is(FluidTags.WATER) && !solid) {
                // water, and plants in it (kelp, seagrass): looked through to the floor
                if (dry && c.shown[i] == null) {
                    c.waterTop[i] = y;
                    c.waterState[i] = fluid.createLegacyBlock();
                }
                continue;
            }
            if (solid) {
                ground(c, i, y, SurfaceClass.cached(state), state);
                return;
            }
            // plants, torches and the like are looked past; a snow layer whitens what is under it
            if (dry && state.is(BlockTags.SNOW)) {
                c.snowed[i] = true;
                shows(c, i, y, state);
            }
        }
    }

    /** The first thing seen from above (on land). */
    private static void shows(Columns c, int i, int y, BlockState state) {
        if (c.shown[i] != null) return;
        c.shown[i] = state;
        c.top[i] = y;
    }

    /** The ground: under water or with nothing over it, also what shows. */
    private static void ground(Columns c, int i, int y, byte cls, BlockState state) {
        c.ground[i] = y;
        c.cls[i] = cls;
        if (c.shown[i] == null) {
            c.shown[i] = state;
            c.top[i] = y;
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
