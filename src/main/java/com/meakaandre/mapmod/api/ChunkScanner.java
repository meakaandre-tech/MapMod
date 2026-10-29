package com.meakaandre.mapmod.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;

/**
 * The chunk hook: called on the server thread each time the map records a chunk (when it loads, and a few seconds after
 * a block in it changes), so a mod can find its own things in it. What it returns replaces everything this scanner had
 * found in that chunk; the map keeps it on disk and sends it to the groups that see it, in the chunks they explored.
 */
@FunctionalInterface
public interface ChunkScanner {
    List<FoundPoi> scan(ServerLevel level, LevelChunk chunk);
}
