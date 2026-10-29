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

    private MapPalette() {
    }

    public static int biome(Holder<Biome> biome) {
        String path = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        if (path.contains("frozen") || path.contains("snowy") || path.contains("ice") || path.contains("grove") || path.contains("jagged")
            || path.contains("frozen_peaks")) return 0xD5D9DA;
        if (biome.is(BiomeTags.IS_BADLANDS)) return 0x9C604A;
        if (path.contains("desert") || path.contains("dune")) return 0xDDB792;
        if (biome.is(BiomeTags.IS_BEACH)) return 0xE4D6BA;
        if (path.contains("stony") || path.contains("peaks") || path.contains("windswept_gravelly")) return 0x6B6A6C;
        if (biome.is(BiomeTags.IS_JUNGLE) || path.contains("jungle")) return 0x3D5C26;
        if (path.contains("swamp")) return 0x4C5C3A;
        if (path.contains("mangrove")) return 0x3E5230;
        if (biome.is(BiomeTags.IS_SAVANNA) || path.contains("savanna")) return 0xC9A47C;
        if (biome.is(BiomeTags.IS_TAIGA) || path.contains("taiga")) return 0x34483A;
        if (path.contains("dark_forest") || path.contains("pale_garden")) return 0x2C3A22;
        if (biome.is(BiomeTags.IS_FOREST) || path.contains("forest") || path.contains("wood")) return 0x36482A;
        if (path.contains("mushroom")) return 0x7A6474;
        if (path.contains("cherry")) return 0xB8919C;
        if (path.contains("meadow")) return 0x5C8C40;
        if (path.contains("plains") || path.contains("field") || path.contains("grass")) return 0x4F8638;
        // a biome nobody named here (a mod's): told apart by its climate, which every biome has
        float t = biome.value().getBaseTemperature();
        if (t < 0.15f) return 0xD5D9DA;
        if (!biome.value().hasPrecipitation() && t > 1.0f) return 0xDDB792;
        if (t > 0.9f) return 0x5A7A34;
        return 0x46713A;
    }

    /**
     * The block a biome's ground most likely shows from above, for a guess over land nobody has loaded (a forest shows
     * its leaves, a desert its sand, a snowy biome snow). Any biome works: a mod's is told apart by its climate.
     */
    public static String guessBlock(Holder<Biome> biome, boolean underwater) {
        String path = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        if (underwater) return path.contains("deep") || path.contains("cold") || path.contains("frozen") ? "minecraft:gravel" : "minecraft:sand";
        if (path.contains("frozen") || path.contains("snowy") || path.contains("ice") || path.contains("grove") || path.contains("jagged")) {
            return path.contains("taiga") ? "minecraft:spruce_leaves" : "minecraft:snow_block";
        }
        if (biome.is(BiomeTags.IS_BADLANDS)) return "minecraft:red_sand";
        if (path.contains("desert") || path.contains("dune") || biome.is(BiomeTags.IS_BEACH)) return "minecraft:sand";
        if (path.contains("stony") || path.contains("peaks") || path.contains("windswept_gravelly")) return "minecraft:stone";
        if (path.contains("mushroom")) return "minecraft:mycelium";
        if (biome.is(BiomeTags.IS_JUNGLE) || path.contains("jungle")) return "minecraft:jungle_leaves";
        if (path.contains("mangrove")) return "minecraft:mangrove_leaves";
        if (biome.is(BiomeTags.IS_TAIGA) || path.contains("taiga")) return "minecraft:spruce_leaves";
        if (path.contains("dark_forest")) return "minecraft:dark_oak_leaves";
        if (path.contains("pale_garden")) return "minecraft:pale_oak_leaves";
        if (path.contains("cherry")) return "minecraft:cherry_leaves";
        if (path.contains("birch")) return "minecraft:birch_leaves";
        if (biome.is(BiomeTags.IS_FOREST) || path.contains("forest") || path.contains("wood")) return "minecraft:oak_leaves";
        float t = biome.value().getBaseTemperature();
        if (t < 0.15f) return "minecraft:snow_block";
        if (!biome.value().hasPrecipitation() && t > 1.0f) return "minecraft:sand";
        return "minecraft:grass_block";
    }

    /** A water pixel by depth (blocks of water over the floor). */
    public static int water(int depth) {
        float t = Math.min(1f, Math.max(0, depth) / DEEPEST_WATER);
        return mix(0x5DC0D2, 0x4A757B, t);
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
