package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapTile;
import com.mojang.blaze3d.platform.NativeImage;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Paints a region of the map as pixel art, a pixel a block, in the manner of Xaero's map: each block in its own colour
 * (its texture's, tinted by the biome), a block higher than the one north of it a little lighter and a lower one a
 * little darker so hills and cliffs stand out, water see-through over its floor and darker the deeper it is, and the
 * fog of war over what the group has not explored.
 */
final class MapPainter {
    /** Fog: a dark blue-grey with a faint diagonal weave (the colours the selftests look for). */
    static final int FOG = 0x141A26, FOG_LINE = 0x19202D;
    /** Explored but not recorded yet. */
    static final int UNKNOWN = 0x3A424C;
    /** Blocks between the fog weave's diagonal lines. */
    private static final int FOG_WEAVE = 6;
    /** Pixels read from the neighbouring regions round a region (the shading looks one block north). */
    static final int PAD = 1;
    /** Light on a block higher and lower than the one north of it, and more for each further block of difference. */
    private static final float UP_LIGHT = 1.10f, DOWN_LIGHT = 0.86f, STEP_LIGHT = 0.035f;
    /** How much the water covers its floor: at the surface, more per block of depth, and at most. */
    private static final float WATER_ALPHA = 0.45f, WATER_ALPHA_PER_BLOCK = 0.045f, WATER_ALPHA_MAX = 0.88f;
    /** A generator guess (land nobody loaded yet) is drawn a little dimmer than recorded land. */
    private static final float GUESS_LIGHT = 0.85f;

    private MapPainter() {
    }

    /**
     * Paints the region. {@code near} holds the 3 by 3 tiles round it (it in the middle; missing ones null),
     * {@code fog} their explored chunks.
     */
    static void paint(NativeImage image, @Nullable MapTile tile, @Nullable MapTile[] near, long[] @Nullable [] fog) {
        int n = MapGrid.REGION_PX;
        long[] own = fog[4];
        Map<Integer, Integer> colours = new HashMap<>();
        MapTile north = near[1];
        for (int pz = 0; pz < n; pz++) {
            for (int px = 0; px < n; px++) {
                int chunkBit = (pz / MapGrid.CHUNK_PX) * MapGrid.REGION_CHUNKS + px / MapGrid.CHUNK_PX;
                int i = MapTile.index(px, pz);
                int rgb;
                if (own == null || !MapGrid.get(own, chunkBit)) {
                    rgb = ((px + pz) % FOG_WEAVE == 0) ? FOG_LINE : FOG;
                } else if (tile == null || !tile.known(i)) {
                    rgb = UNKNOWN;
                } else {
                    int cell = tile.cell[i];
                    int key = MapTile.blockOf(cell) << 8 | MapTile.biomeOf(cell);
                    Integer base = colours.get(key);
                    if (base == null) {
                        base = colourOf(tile, cell);
                        colours.put(key, base);
                    }
                    rgb = base;
                    boolean guess = MapTile.blockOf(cell) < tile.blocks.size() && tile.blocks.get(MapTile.blockOf(cell)).isEmpty();
                    if (MapTile.water(cell)) {
                        if (!guess) {
                            // the floor seen through the water
                            int water = BlockColours.colour("minecraft:water", biome(tile, cell), 0x3F76E4);
                            float alpha = Math.min(WATER_ALPHA_MAX, WATER_ALPHA + MapTile.depth(cell) * WATER_ALPHA_PER_BLOCK);
                            rgb = mix(rgb, water, alpha);
                        }
                    } else {
                        // lighter where higher than the block north of it, darker where lower
                        int above = MapTile.index(px, pz > 0 ? pz - 1 : n - 1);
                        MapTile t = pz > 0 ? tile : north;
                        if (t != null && t.known(above) && !MapTile.water(t.cell[above])) {
                            int d = tile.height[i] - t.height[above];
                            float light = 1f;
                            if (d > 0) light = Math.min(UP_LIGHT + (d - 1) * STEP_LIGHT, 1.25f);
                            else if (d < 0) light = Math.max(DOWN_LIGHT - (-d - 1) * STEP_LIGHT, 0.65f);
                            rgb = scale(rgb, light);
                        }
                    }
                    if (tile.guessed(i)) rgb = scale(rgb, GUESS_LIGHT);
                }
                image.setPixel(px, pz, 0xFF000000 | rgb);
            }
        }
    }

    private static @Nullable String biome(MapTile tile, int cell) {
        int b = MapTile.biomeOf(cell);
        return b < tile.biomes.size() ? tile.biomes.get(b) : null;
    }

    /** A pixel's block colour: from its texture and biome, or the map colour the server sent. */
    private static int colourOf(MapTile tile, int cell) {
        int b = MapTile.blockOf(cell);
        if (b >= tile.blocks.size()) return UNKNOWN;
        String name = tile.blocks.get(b);
        int fallback = tile.blockColours.get(b) & 0xFFFFFF;
        if (name.isEmpty()) return fallback;
        return BlockColours.colour(name, biome(tile, cell), fallback);
    }

    static int mix(int a, int b, float t) {
        t = Math.clamp(t, 0f, 1f);
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t + 0.5f);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t + 0.5f);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t + 0.5f);
        return r << 16 | g << 8 | bl;
    }

    private static int scale(int c, float light) {
        int r = Math.clamp((int) ((c >> 16 & 255) * light), 0, 255), g = Math.clamp((int) ((c >> 8 & 255) * light), 0, 255);
        int b = Math.clamp((int) ((c & 255) * light), 0, 255);
        return r << 16 | g << 8 | b;
    }
}
