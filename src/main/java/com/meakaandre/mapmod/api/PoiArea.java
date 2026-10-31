package com.meakaandre.mapmod.api;

/**
 * One map region a {@link PoiSource} is asked about: blocks {@code minX <= x < maxX}, {@code minZ <= z < maxZ}
 * (512 by 512 blocks, 32 by 32 chunks).
 *
 * @param region the region's key: its x (block x floor-divided by 512) in the high 32 bits, its z in the low 32
 */
public record PoiArea(long region, int minX, int minZ, int maxX, int maxZ) {
    public boolean contains(int x, int z) {
        return x >= minX && x < maxX && z >= minZ && z < maxZ;
    }
}
