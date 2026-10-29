package com.meakaandre.mapmod.map;

import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;

/**
 * The map's soft colours, in the manner of Satisfactory's map: each biome has a muted ground colour, lightly mixed
 * with the colour of the block on top so paths, sand and snow still show; water goes from a light shallow blue to a
 * deep one.
 */
public final class MapPalette {
    /** Water this deep (blocks) or deeper is drawn in the deepest blue. */
    private static final float DEEPEST_WATER = 20f;
    /** How much of the top block's map colour is mixed into the biome's ground colour. */
    private static final float BLOCK_MIX = 0.25f;

    private MapPalette() {
    }

    public static int biome(Holder<Biome> biome) {
        String path = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        if (path.contains("frozen") || path.contains("snowy") || path.contains("ice") || path.contains("grove") || path.contains("jagged")
            || path.contains("frozen_peaks")) return 0xC6CCCE;
        if (biome.is(BiomeTags.IS_BADLANDS)) return 0xB57A5E;
        if (path.contains("desert")) return 0xCFA28A;
        if (biome.is(BiomeTags.IS_BEACH)) return 0xD2B49A;
        if (path.contains("stony") || path.contains("peaks") || path.contains("windswept_gravelly")) return 0x8A8986;
        if (biome.is(BiomeTags.IS_JUNGLE)) return 0x46703A;
        if (path.contains("swamp")) return 0x4F6248;
        if (path.contains("mangrove")) return 0x4A6240;
        if (biome.is(BiomeTags.IS_SAVANNA)) return 0x9E9460;
        if (biome.is(BiomeTags.IS_TAIGA)) return 0x48634A;
        if (path.contains("dark_forest") || path.contains("pale_garden")) return 0x3E5638;
        if (biome.is(BiomeTags.IS_FOREST)) return 0x4E7041;
        if (path.contains("mushroom")) return 0x7E6E7E;
        if (path.contains("cherry")) return 0xB89AA4;
        if (path.contains("meadow")) return 0x6E8E55;
        return 0x6A8A52;
    }

    /** A water pixel by depth (blocks of water over the floor). */
    public static int water(int depth) {
        float t = Math.min(1f, Math.max(0, depth) / DEEPEST_WATER);
        return mix(0x74AEBB, 0x24496C, t);
    }

    /** Ground: the biome's colour with a little of the top block's map colour, softened. */
    public static int ground(int biomeColour, int blockColour) {
        int c = blockColour == 0 ? biomeColour : mix(biomeColour, blockColour, BLOCK_MIX);
        return soften(c);
    }

    /** Land as the map shows it: just its biome's soft colour, whatever stands on it (the same as a guess). */
    public static int land(Holder<Biome> biome) {
        return soften(biome(biome));
    }

    /** A guess from the world generator alone: just the biome's colour. */
    public static int guess(Holder<Biome> biome) {
        return land(biome);
    }

    private static int soften(int c) {
        int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
        int grey = (r * 30 + g * 59 + b * 11) / 100;
        // a tenth of the way to grey: muted, like a painted survey map
        r = (r * 9 + grey) / 10;
        g = (g * 9 + grey) / 10;
        b = (b * 9 + grey) / 10;
        return r << 16 | g << 8 | b;
    }

    public static int mix(int a, int b, float t) {
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }
}
