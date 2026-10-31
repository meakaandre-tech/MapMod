package com.meakaandre.mapmod.client.capture;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The eight surface classes the painted map knows, and how any block (vanilla or modded) lands in one: block tags
 * first, then the block's {@link MapColor}. No per-mod code; mods may put a block or a tag in a class of their choice
 * ({@code api.MapSurface}), which wins over both.
 */
public final class SurfaceClass {
    public static final byte GRASS = 0, FOREST = 1, SAND = 2, ROCK = 3, SNOW = 4, DIRT = 5, ICE = 6, LAVA = 7;
    public static final int COUNT = 8;
    private static final String[] NAMES = {"grass", "forest floor", "sand", "rock", "snow", "dirt/mud", "ice", "lava"};

    /** Classes per block state, filled as states are met. Only the capture worker touches it. */
    private static final Map<BlockState, Byte> CACHE = new IdentityHashMap<>();
    /** The overrides (MM-6): replaced whole on every change, so any thread reads a consistent pair. */
    private record Overrides(Map<Block, Byte> blocks, Map<TagKey<Block>, Byte> tags, int version) {
    }

    private static volatile Overrides overrides = new Overrides(Map.of(), Map.of(), 0);
    /** The overrides' version the cache was filled under. Capture worker only. */
    private static int cachedVersion;

    private SurfaceClass() {
    }

    public static String name(int c) {
        return c >= 0 && c < COUNT ? NAMES[c] : "?";
    }

    /** Forgets what was worked out (tags can differ between servers). */
    static void clearCache() {
        CACHE.clear();
    }

    /** Puts a block, or every block in a tag, in a class; -1 takes the override away. Any thread. */
    public static synchronized void override(Object blockOrTag, byte classId) {
        Overrides o = overrides;
        Map<Block, Byte> blocks = new IdentityHashMap<>(o.blocks());
        Map<TagKey<Block>, Byte> tags = new LinkedHashMap<>(o.tags());
        if (blockOrTag instanceof Block b) {
            if (classId < 0) blocks.remove(b);
            else blocks.put(b, classId);
        } else {
            @SuppressWarnings("unchecked") TagKey<Block> t = (TagKey<Block>) blockOrTag;
            if (classId < 0) tags.remove(t);
            else tags.put(t, classId);
        }
        overrides = new Overrides(blocks, tags, o.version() + 1);
    }

    /** The class of a block seen as the ground, cached; call on the capture worker only. */
    static byte cached(BlockState state) {
        int v = overrides.version();
        if (v != cachedVersion) {
            // an override changed: classes are worked out again from the next sample on
            CACHE.clear();
            cachedVersion = v;
        }
        Byte c = CACHE.get(state);
        if (c == null) {
            c = classify(state);
            CACHE.put(state, c);
        }
        return c;
    }

    /** The class of a block seen as the ground: overrides, then tags, then its map colour. Safe off the client thread. */
    public static byte classify(BlockState state) {
        Overrides o = overrides;
        if (!o.blocks().isEmpty()) {
            Byte b = o.blocks().get(state.getBlock());
            if (b != null) return b;
        }
        for (Map.Entry<TagKey<Block>, Byte> e : o.tags().entrySet()) if (state.is(e.getKey())) return e.getValue();
        return builtIn(state);
    }

    /** The class from tags and map colour alone. */
    private static byte builtIn(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) return LAVA;
        if (state.is(BlockTags.ICE)) return ICE;
        if (state.is(BlockTags.SNOW)) return SNOW;
        if (state.is(BlockTags.SAND)) return SAND;
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) return FOREST;
        if (state.is(BlockTags.BASE_STONE_OVERWORLD)) return ROCK;
        if (state.is(BlockTags.MUD)) return DIRT;
        MapColor colour = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        // soil: green is grass, the rest dirt/mud. 26.3 split the old #dirt: mycelium (and more) is only in
        // #substrate_overworld and #turns_into_dirt_path now, and fell through to its purple map colour, read as rock
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SUBSTRATE_OVERWORLD) || state.is(BlockTags.TURNS_INTO_DIRT_PATH)
            || state.is(BlockTags.GRASS_BLOCKS) || state.is(BlockTags.MOSS_BLOCKS)) return green(colour) ? GRASS : DIRT;
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
