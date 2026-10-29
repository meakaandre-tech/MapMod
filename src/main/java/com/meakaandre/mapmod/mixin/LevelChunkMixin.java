package com.meakaandre.mapmod.mixin;

import com.meakaandre.mapmod.map.ClientBlockHook;
import com.meakaandre.mapmod.map.MapTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BiConsumer;

/**
 * Block changes reach the map: on the server it records the chunk again a little after a block in it changes; on the
 * client the surface capture samples it again if the change was at the surface. A hook on the chunk's data, not on
 * chunk rendering, so renderer rewrites (Sodium, Quicksilver) are not touched.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void mapmod$blockChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() == null) return;
        LevelChunk chunk = (LevelChunk) (Object) this;
        Level level = chunk.getLevel();
        if (level instanceof ServerLevel server) {
            MapTerrain.blockChanged(server, pos);
        } else if (level.isClientSide()) {
            BiConsumer<LevelChunk, BlockPos> listener = ClientBlockHook.listener;
            if (listener != null) listener.accept(chunk, pos);
        }
    }
}
