package com.meakaandre.mapmod.selftest.client;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.selftest.SmokeWaits;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The client's half of {@code /mapmod worldtest ready} ({@link SmokeWaits}): once the chunks around the player have
 * arrived and every section in view is drawn, for {@link #STEADY_TICKS} ticks running, it logs "SELFTEST client ready n."
 * for the smoke run's script to wait on.
 */
public final class ReadyClientTests {
    /** Chunks in each direction around the player that must have arrived. */
    private static final int AROUND = 2;
    /** Ticks in a row the world must look ready, so a moment between two batches of chunks does not count. */
    private static final int STEADY_TICKS = 20;

    private static @Nullable Integer token;
    private static int steady;

    private ReadyClientTests() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(SmokeWaits.Ready.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> {
            token = payload.token();
            steady = 0;
        }));
        ClientTickEvents.END_CLIENT_TICK.register(ReadyClientTests::tick);
    }

    private static void tick(Minecraft mc) {
        if (token == null) return;
        steady = ready(mc) ? steady + 1 : 0;
        if (steady < STEADY_TICKS) return;
        MapMod.LOGGER.info("SELFTEST client ready {}.", token);
        token = null;
    }

    private static boolean ready(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.player.isRemoved()) return false;
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        for (int dx = -AROUND; dx <= AROUND; dx++)
            for (int dz = -AROUND; dz <= AROUND; dz++) if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) return false;
        return mc.levelRenderer.hasRenderedAllSections();
    }
}
