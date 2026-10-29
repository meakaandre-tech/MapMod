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
            || path.contains("frozen_peaks")) return 0xE4EAEC;
        if (biome.is(BiomeTags.IS_BADLANDS)) return 0xC27C52;
        if (path.contains("desert")) return 0xDCCB98;
        if (biome.is(BiomeTags.IS_BEACH)) return 0xDDD2A2;
        if (path.contains("stony") || path.contains("peaks") || path.contains("windswept_gravelly")) return 0x9C9B93;
        if (biome.is(BiomeTags.IS_JUNGLE)) return 0x5E9442;
        if (path.contains("swamp")) return 0x627A55;
        if (path.contains("mangrove")) return 0x5B7A4C;
        if (biome.is(BiomeTags.IS_SAVANNA)) return 0xB3A962;
        if (biome.is(BiomeTags.IS_TAIGA)) return 0x627F62;
        if (path.contains("dark_forest") || path.contains("pale_garden")) return 0x55704A;
        if (biome.is(BiomeTags.IS_FOREST)) return 0x6D9853;
        if (path.contains("mushroom")) return 0x8C7A8C;
        if (path.contains("cherry")) return 0xC8A0B0;
        if (path.contains("meadow")) return 0x86AE66;
        return 0x8AAE62;
    }

    /** A water pixel by depth (blocks of water over the floor). */
    public static int water(int depth) {
        float t = Math.min(1f, Math.max(0, depth) / DEEPEST_WATER);
        return mix(0x79BED0, 0x2D5F88, t);
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
        // a fifth of the way to grey, then a little lighter
        r = Math.min(255, (r * 4 + grey) / 5 + 8);
        g = Math.min(255, (g * 4 + grey) / 5 + 8);
        b = Math.min(255, (b * 4 + grey) / 5 + 8);
        return r << 16 | g << 8 | b;
    }

    public static int mix(int a, int b, float t) {
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }
}
