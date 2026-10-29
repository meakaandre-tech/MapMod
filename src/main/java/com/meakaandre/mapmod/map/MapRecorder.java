package com.meakaandre.mapmod.map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Records a loaded chunk into its tile, a pixel a block, as Xaero's map does: the top block a player would see from
 * above (plants like grass and flowers are looked past, snow is not), its biome and height; for water its depth and the
 * floor's block. On the server for everyone, and on a client by itself when the server has no MapMod.
 */
public final class MapRecorder {
    /** Colour of a column with nothing in it (the void). */
    public static final int VOID_COLOUR = 0x202020;
    /** Blocks looked down through water for the floor at most. */
    private static final int MAX_WATER_DEPTH = MapTile.MAX_DEPTH;
    /** Blocks looked down past see-through blocks and plants at most. */
    private static final int MAX_LOOK = 32;

    private MapRecorder() {
    }

    /** True for blocks a map looks past: nothing to see (air, glass), or small plants (grass, flowers, saplings). */
    private static boolean seeThrough(BlockState state, LevelChunk chunk, BlockPos pos) {
        if (state.isAir()) return true;
        if (state.is(Blocks.SNOW)) return false;
        if (!state.getFluidState().isEmpty()) return false;
        MapColor colour = state.getMapColor(chunk, pos);
        return colour == MapColor.NONE || state.canBeReplaced();
    }

    public static String name(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** Writes the chunk's pixels into the tile of its region. Returns true if a pixel changed. */
    public static boolean record(Level level, LevelChunk chunk, MapTile tile, boolean server) {
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        boolean any = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = Math.floorMod(cx, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX, baseZ = Math.floorMod(cz, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX;
        int min = level.getMinY();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = (cx << 4) + lx, z = (cz << 4) + lz;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                int cell, height;
                if (top <= min) {
                    cell = MapTile.cell(tile.block("", VOID_COLOUR), 0, MapTile.KNOWN, 0);
                    height = min;
                } else {
                    int y = top;
                    BlockState state = chunk.getBlockState(pos.set(x, y, z));
                    // down past air, glass and small plants to what is seen from above
                    while (y > min && top - y < MAX_LOOK && seeThrough(state, chunk, pos)) state = chunk.getBlockState(pos.set(x, --y, z));
                    height = y;
                    int flags = MapTile.KNOWN, depth = 0;
                    if (state.getFluidState().is(FluidTags.WATER)) {
                        // through the water (and the weed in it) to the floor
                        flags |= MapTile.WATER;
                        int floor = y;
                        while (floor > min && y - floor < MAX_WATER_DEPTH && !state.getFluidState().isEmpty()) {
                            state = chunk.getBlockState(pos.set(x, --floor, z));
                        }
                        depth = y - floor;
                        if (!state.getFluidState().isEmpty()) state = Blocks.WATER.defaultBlockState();
                    }
                    pos.set(x, height, z);
                    int colour = state.getMapColor(chunk, pos).col;
                    int block = tile.block(name(state), colour);
                    int biome = tile.biome(level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse(""));
                    cell = MapTile.cell(block, biome, flags, depth);
                }
                int i = MapTile.index(baseX + lx, baseZ + lz);
                if (tile.cell[i] != cell || tile.height[i] != height) {
                    tile.cell[i] = cell;
                    tile.height[i] = (short) height;
                    any = true;
                }
            }
        }
        return any;
    }
}
