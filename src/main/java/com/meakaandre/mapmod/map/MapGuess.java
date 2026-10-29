package com.meakaandre.mapmod.map;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * What the world generator says about a column without generating anything: whether there is land at all, where its
 * ground is and which biome that ground has. Works on ordinary worlds and on worlds of floating islands over void.
 * It only asks the biome source and the generator's noise, which the game itself does from its world generation
 * threads, so it may run off the server thread.
 */
public final class MapGuess {
    /** The height a column's biome is first sampled at: an ordinary world's surface. */
    private static final int SAMPLE_Y = 64;
    /** The second sample stands this far above the generator's sea level. */
    private static final int SEA_SAMPLE_ABOVE = 8;
    /** Samples spread from the bottom to the top of the world before a column counts as void. */
    private static final int VOID_SAMPLES = 6;

    /** The top solid block of a column and the biome just above it. */
    public record Column(int y, Holder<Biome> biome) {
    }

    private MapGuess() {
    }

    /** A void biome: {@code minecraft:the_void}, or any other mod's {@code *:void}. */
    public static boolean isVoid(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> {
            String path = key.identifier().getPath();
            return path.equals("void") || path.equals("the_void") || path.endsWith("/void");
        }).orElse(false);
    }

    private static Holder<Biome> biome(ServerLevel level, int x, int y, int z) {
        return level.getUncachedNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
    }

    /** True if the biome is void everywhere in the column; ordinary worlds never have one, so this costs them one sample. */
    private static boolean voidColumn(ServerLevel level, int x, int z) {
        int min = level.getMinY(), max = level.getMaxY();
        if (!isVoid(biome(level, x, Math.clamp(SAMPLE_Y, min, max), z))) return false;
        if (!isVoid(biome(level, x, Math.clamp(level.getSeaLevel() + SEA_SAMPLE_ABOVE, min, max), z))) return false;
        for (int i = 0; i < VOID_SAMPLES; i++) {
            int y = min + (int) ((max - min) * (i + 0.5) / VOID_SAMPLES);
            if (!isVoid(biome(level, x, y, z))) return false;
        }
        return true;
    }

    /** The generator's ground at a column, or null where there is none (void). */
    public static @Nullable Column column(ServerLevel level, int x, int z) {
        if (voidColumn(level, x, z)) return null;
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        int top = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random) - 1;
        if (top < level.getMinY()) return null;
        Holder<Biome> biome = biome(level, x, top + 1, z);
        if (isVoid(biome)) return null;
        return new Column(top, biome);
    }
}
