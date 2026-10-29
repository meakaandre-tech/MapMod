package com.meakaandre.mapmod.client.capture;

import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * What the sampler needs of a chunk, copied on the client thread so the sampling can run on the capture worker: the
 * block sections from the top of the chunk down to a little below its lowest surface (copies, so later edits to the
 * live chunk do not reach the worker), the surface heightmap, and the biome under each sample. No mixin into chunk
 * rendering is involved, so this works the same with Sodium or Quicksilver.
 */
final class ChunkSnapshot {
    /** Blocks below the lowest surface that are copied, for water depth (deeper reads as this deep). */
    static final int DEPTH_COPY = 64;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    final int cx, cz;
    /** Lowest block y of the copied range and its first section's index in the chunk. */
    final int bottomY;
    private final int firstSection, minY;
    private final @Nullable PalettedContainer<BlockState>[] sections;
    /** y of the first air above the top block, per column (x + z * 16); {@code bottomY} for an empty column. */
    final int[] top = new int[256];
    /** The biome at the surface of each sample (sx + sz * 4). */
    final Holder<Biome>[] biomes;

    @SuppressWarnings("unchecked")
    private ChunkSnapshot(int cx, int cz, int minY, int firstSection, int count) {
        this.cx = cx;
        this.cz = cz;
        this.minY = minY;
        this.firstSection = firstSection;
        this.bottomY = minY + firstSection * 16;
        this.sections = new PalettedContainer[count];
        this.biomes = new Holder[16];
    }

    /** Copies what the sampler needs of a loaded chunk. Client thread. Returns null for a chunk with no blocks. */
    static @Nullable ChunkSnapshot of(Level level, LevelChunk chunk) {
        LevelChunkSection[] all = chunk.getSections();
        int minY = level.getMinY();
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        int[] heights = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                heights[x + z * 16] = h;
                lowest = Math.min(lowest, h);
                highest = Math.max(highest, h);
            }
        }
        if (highest <= minY) return null;
        int lo = Math.clamp((Math.max(minY, lowest - DEPTH_COPY) - minY) >> 4, 0, all.length - 1);
        int hi = Math.clamp((highest - 1 - minY) >> 4, lo, all.length - 1);
        ChunkSnapshot s = new ChunkSnapshot(chunk.getPos().x(), chunk.getPos().z(), minY, lo, hi - lo + 1);
        for (int i = lo; i <= hi; i++) {
            LevelChunkSection section = all[i];
            if (section != null && !section.hasOnlyAir()) s.sections[i - lo] = section.getStates().copy();
        }
        for (int i = 0; i < 256; i++) s.top[i] = Math.max(heights[i], s.bottomY);
        int qx0 = s.cx << 2, qz0 = s.cz << 2;
        for (int sz = 0; sz < 4; sz++) {
            for (int sx = 0; sx < 4; sx++) {
                // the quart (4x4x4 biome cell) just above the sample's middle column
                int y = heights[(sx * 4 + 2) + (sz * 4 + 2) * 16];
                s.biomes[sx + sz * 4] = chunk.getNoiseBiome(qx0 + sx, y >> 2, qz0 + sz);
            }
        }
        return s;
    }

    /** The block at a column (0-15) and a world y in the copied range (air outside it). Worker-safe. */
    BlockState get(int x, int y, int z) {
        int i = ((y - minY) >> 4) - firstSection;
        if (i < 0 || i >= sections.length) return AIR;
        PalettedContainer<BlockState> section = sections[i];
        return section == null ? AIR : section.get(x, y & 15, z);
    }
}
