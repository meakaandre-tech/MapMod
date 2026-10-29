package com.meakaandre.mapmod.map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Records a loaded chunk's 16 map pixels (the natural ground's height and the biome's colour) into its tile: on the server for
 * everyone, and on a client by itself when the server has no MapMod.
 */
public final class MapRecorder {
    /** Colour of a column with nothing in it (the void). */
    public static final int VOID_COLOUR = 0x202020;
    /** Blocks a client looks down through water for the floor at most (clients have no ocean-floor heightmap). */
    private static final int MAX_WATER_DEPTH = 40;

    private MapRecorder() {
    }

    /** Blocks under the top one a pixel looks down for natural ground at most (through trees, plants and buildings). */
    private static final int MAX_DEPTH = 48;
    /** A soft orange for lava, which counts as ground. */
    private static final int LAVA_COLOUR = 0xC9733F;

    /**
     * Ground the map shows: what the world is made of, not what stands on it. Trees, plants, buildings and paths are
     * looked through, so the map shows the land's shape and its biome's colour, as Satisfactory's map does.
     */
    public static boolean natural(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.TERRACOTTA)
            || state.is(BlockTags.SNOW) || state.is(BlockTags.ICE) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SANDSTONE)
            || state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.CALCITE) || state.is(Blocks.BEDROCK) || state.is(Blocks.OBSIDIAN)
            || state.is(BlockTags.ORES);
    }

    /**
     * Writes the chunk's pixels into the tile of its region: for each, the height of its natural ground and its biome's
     * soft colour, or for water the depth's blue. {@code oceanFloor} is kept for callers and no longer needed: the floor
     * under water is found by looking down. Returns true if a pixel changed.
     */
    public static boolean record(Level level, LevelChunk chunk, MapTile tile, boolean oceanFloor) {
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        boolean any = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = Math.floorMod(cx, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX, baseZ = Math.floorMod(cz, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX;
        int min = level.getMinY();
        for (int pz = 0; pz < MapGrid.CHUNK_PX; pz++) {
            for (int px = 0; px < MapGrid.CHUNK_PX; px++) {
                int lx = px * MapGrid.PX_BLOCKS + MapGrid.PX_BLOCKS / 2, lz = pz * MapGrid.PX_BLOCKS + MapGrid.PX_BLOCKS / 2;
                int x = (cx << 4) + lx, z = (cz << 4) + lz;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                int colour, height;
                if (top <= min) {
                    colour = VOID_COLOUR | MapTile.KNOWN;
                    height = min;
                } else {
                    // down from the top block to the first natural ground or water
                    int y = top, found = Integer.MIN_VALUE;
                    boolean water = false, lava = false;
                    for (; y > min && top - y <= MAX_DEPTH; y--) {
                        pos.set(x, y, z);
                        BlockState state = chunk.getBlockState(pos);
                        if (state.getFluidState().is(FluidTags.WATER)) {
                            water = true;
                            found = y;
                            break;
                        }
                        if (state.getFluidState().is(FluidTags.LAVA)) {
                            lava = true;
                            found = y;
                            break;
                        }
                        if (natural(state)) {
                            found = y;
                            break;
                        }
                    }
                    if (found == Integer.MIN_VALUE) found = top;
                    pos.set(x, found, z);
                    if (water) {
                        int floor = floor(chunk, pos, found, min);
                        colour = MapPalette.water(found - floor) | MapTile.KNOWN | MapTile.WATER;
                    } else if (lava) {
                        colour = LAVA_COLOUR | MapTile.KNOWN;
                    } else {
                        Holder<Biome> biome = level.getBiome(pos);
                        colour = MapPalette.land(biome) | MapTile.KNOWN;
                    }
                    height = found;
                }
                int i = MapTile.index(baseX + px, baseZ + pz);
                if (tile.colour[i] != colour || tile.height[i] != height) {
                    tile.colour[i] = colour;
                    tile.height[i] = (short) height;
                    any = true;
                }
            }
        }
        return any;
    }

    /** The top of the ground under water, looked for downwards from the surface. */
    private static int floor(LevelChunk chunk, BlockPos.MutableBlockPos pos, int top, int min) {
        int y = top;
        while (y > min && top - y < MAX_WATER_DEPTH) {
            pos.setY(y);
            if (!chunk.getBlockState(pos).getFluidState().is(FluidTags.WATER)) break;
            y--;
        }
        pos.setY(top);
        return y;
    }
}
