package com.meakaandre.mapmod.map;

/**
 * The map's grid. The map is kept at low resolution: one pixel for every 4 by 4 blocks, so a chunk is 4 by 4 pixels.
 * A region (tile) is 32 by 32 chunks, 512 by 512 blocks, 128 by 128 pixels. Fog of war is kept per chunk.
 */
public final class MapGrid {
    /** Blocks along one side of a pixel. */
    public static final int PX_BLOCKS = 4;
    /** Pixels along one side of a chunk. */
    public static final int CHUNK_PX = 16 / PX_BLOCKS;
    /** Chunks along one side of a region. */
    public static final int REGION_CHUNKS = 32;
    /** Pixels along one side of a region. */
    public static final int REGION_PX = REGION_CHUNKS * CHUNK_PX;
    /** Blocks along one side of a region. */
    public static final int REGION_BLOCKS = REGION_CHUNKS * 16;
    /** Longs in a region's fog bits: one bit a chunk. */
    public static final int FOG_LONGS = REGION_CHUNKS * REGION_CHUNKS / 64;

    private MapGrid() {
    }

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    /** The region a chunk is in. */
    public static long regionOfChunk(int chunkX, int chunkZ) {
        return key(Math.floorDiv(chunkX, REGION_CHUNKS), Math.floorDiv(chunkZ, REGION_CHUNKS));
    }

    /** The region a block is in. */
    public static long regionOfBlock(int x, int z) {
        return key(Math.floorDiv(x, REGION_BLOCKS), Math.floorDiv(z, REGION_BLOCKS));
    }

    /** The chunk's bit in its region's fog. */
    public static int fogBit(int chunkX, int chunkZ) {
        return Math.floorMod(chunkZ, REGION_CHUNKS) * REGION_CHUNKS + Math.floorMod(chunkX, REGION_CHUNKS);
    }

    public static boolean get(long[] bits, int bit) {
        return (bits[bit >>> 6] & (1L << (bit & 63))) != 0;
    }

    /** Sets a bit; true if it was not set before. */
    public static boolean set(long[] bits, int bit) {
        long mask = 1L << (bit & 63);
        if ((bits[bit >>> 6] & mask) != 0) return false;
        bits[bit >>> 6] |= mask;
        return true;
    }
}
