package com.meakaandre.mapmod.client.capture;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The eight surface classes the painted map knows, and how any block (vanilla or modded) lands in one: block tags
 * first, then the block's {@link MapColor}. No per-mod code.
 */
public final class SurfaceClass {
    public static final byte GRASS = 0, FOREST = 1, SAND = 2, ROCK = 3, SNOW = 4, DIRT = 5, ICE = 6, LAVA = 7;
    public static final int COUNT = 8;
    private static final String[] NAMES = {"grass", "forest floor", "sand", "rock", "snow", "dirt/mud", "ice", "lava"};

    /** Classes per block state, filled as states are met. Only the capture worker touches it. */
    private static final Map<BlockState, Byte> CACHE = new IdentityHashMap<>();

    private SurfaceClass() {
    }

    public static String name(int c) {
        return c >= 0 && c < COUNT ? NAMES[c] : "?";
    }

    /** Forgets what was worked out (tags can differ between servers). */
    static void clearCache() {
        CACHE.clear();
    }

    /** The class of a block seen as the ground, cached; call on the capture worker only. */
    static byte cached(BlockState state) {
        Byte c = CACHE.get(state);
        if (c == null) {
            c = classify(state);
            CACHE.put(state, c);
        }
        return c;
    }

    /** The class of a block seen as the ground: tags first, then its map colour. Safe off the client thread. */
    public static byte classify(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) return LAVA;
        if (state.is(BlockTags.ICE)) return ICE;
        if (state.is(BlockTags.SNOW)) return SNOW;
        if (state.is(BlockTags.SAND)) return SAND;
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) return FOREST;
        if (state.is(BlockTags.BASE_STONE_OVERWORLD)) return ROCK;
        MapColor colour = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        // #dirt holds grass blocks, moss and mycelium as well as dirt and mud: green ones are grass
        if (state.is(BlockTags.DIRT)) return green(colour) ? GRASS : DIRT;
        return bucket(colour);
    }

    private static boolean green(MapColor c) {
        return c == MapColor.GRASS || c == MapColor.PLANT || c == MapColor.COLOR_GREEN || c == MapColor.COLOR_LIGHT_GREEN
            || c == MapColor.EMERALD || c == MapColor.WARPED_NYLIUM;
    }

    /** The map-colour fallback for blocks no tag places. */
    static byte bucket(MapColor c) {
        if (green(c)) return GRASS;
        if (c == MapColor.SAND || c == MapColor.COLOR_YELLOW) return SAND;
        if (c == MapColor.SNOW || c == MapColor.WOOL || c == MapColor.QUARTZ) return SNOW;
        if (c == MapColor.ICE) return ICE;
        if (c == MapColor.FIRE) return LAVA;
        if (c == MapColor.DIRT || c == MapColor.WOOD || c == MapColor.PODZOL || c == MapColor.CLAY || c == MapColor.COLOR_BROWN
            || c == MapColor.TERRACOTTA_BROWN || c == MapColor.CRIMSON_NYLIUM || c == MapColor.WATER) return DIRT;
        // stone, deepslate, terracotta, metals, glass and anything unknown read as bare rock
        return ROCK;
    }
}
