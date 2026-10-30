package com.meakaandre.mapmod.map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * Where {@code LevelChunkMixin} tells the client's map capture that a block changed in a client chunk. Kept in common
 * code with no client classes in it, so the mixin loads on a dedicated server too (where nothing is set here).
 */
public final class ClientBlockHook {
    public static volatile @Nullable BiConsumer<LevelChunk, BlockPos> listener;

    private ClientBlockHook() {
    }
}
