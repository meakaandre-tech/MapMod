package com.meakaandre.mapmod.api;

import com.meakaandre.mapmod.client.capture.SurfaceClass;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * The surface classes the map sorts the ground into, and overrides of which class a block is. The client captures the
 * ground every 2 blocks and gives each sample one of eight classes (fixed by the tile format), from block tags and then
 * the block's map colour, so any mod's blocks land in a sensible class with no code; an override puts a block, or every
 * block in a tag, in the class you choose instead. Since MM-8 a sample is painted in its block's real colour, and its
 * class only decides effects (water, forest stipple, snow) and which palette class colour override applies to it. A block override wins over a tag override, and of two tag overrides
 * the one set first wins.
 * <p>
 * Overrides apply to the ground block only (leaves, logs, plants and water are still looked past as before) and to
 * samples taken from then on: land already captured keeps its class until the client captures it again (when it loads
 * the chunk again, or a block at the surface changes). A class's colour override is {@link MapPalette}'s
 * {@code classes.<id>} (none by default).
 * Safe from any thread (the capture classifies off-thread).
 */
public final class MapSurface {
    /** The classes: grass, forest floor, sand, rock, snow, dirt or mud, ice, lava. */
    public static final int GRASS = 0, FOREST = 1, SAND = 2, ROCK = 3, SNOW = 4, DIRT = 5, ICE = 6, LAVA = 7;
    /** How many classes there are. */
    public static final int CLASSES = 8;

    private MapSurface() {
    }

    /** Puts a block in a class (in all its states). */
    public static void override(Block block, int classId) {
        SurfaceClass.override(Objects.requireNonNull(block), (byte) check(classId));
    }

    /** Puts every block in a tag in a class. */
    public static void override(TagKey<Block> tag, int classId) {
        SurfaceClass.override(Objects.requireNonNull(tag), (byte) check(classId));
    }

    /** Takes a block's override away. */
    public static void clear(Block block) {
        SurfaceClass.override(block, (byte) -1);
    }

    /** Takes a tag's override away. */
    public static void clear(TagKey<Block> tag) {
        SurfaceClass.override(tag, (byte) -1);
    }

    /** The class a block gets as the ground now, overrides included. */
    public static int classOf(BlockState state) {
        return SurfaceClass.classify(state);
    }

    /** A class's name, for logs ("grass", "forest floor"...). */
    public static String name(int classId) {
        return SurfaceClass.name(classId);
    }

    static int check(int classId) {
        if (classId < 0 || classId >= CLASSES) throw new IllegalArgumentException("surface class " + classId + " is not 0-" + (CLASSES - 1));
        return classId;
    }
}
