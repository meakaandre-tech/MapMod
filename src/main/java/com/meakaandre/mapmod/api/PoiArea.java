package com.meakaandre.mapmod.api;

/**
 * One map region a {@link PoiSource} is asked about: blocks {@code minX <= x < maxX}, {@code minZ <= z < maxZ}
 * (512 by 512 blocks, 32 by 32 chunks).
 *
 * @param region the region's key (see {@link com.meakaandre.mapmod.map.MapGrid})
 */
public record PoiArea(long region, int minX, int minZ, int maxX, int maxZ) {
    public boolean contains(int x, int z) {
        return x >= minX && x < maxX && z >= minZ && z < maxZ;
    }
}
