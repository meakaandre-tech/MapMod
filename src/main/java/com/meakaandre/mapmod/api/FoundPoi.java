package com.meakaandre.mapmod.api;

/**
 * A POI found in the world (by a {@link ChunkScanner} or {@link MapModApi#setChunkPois}), with who sees it.
 *
 * @param owner the group that sees it; empty for everyone
 */
public record FoundPoi(MapPoi poi, String owner) {
    public static FoundPoi everyone(MapPoi poi) {
        return new FoundPoi(poi, "");
    }
}
