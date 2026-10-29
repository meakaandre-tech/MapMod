package com.meakaandre.mapmod.api;

import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player gates for the map's parts. Asked on the server thread, once a second for every online player (and at once
 * after {@link MapModApi#unlocksChanged}); a change is sent to that player's client. The default unlocks everything.
 */
public interface UnlockProvider {
    UnlockProvider ALWAYS = (player, feature) -> true;

    boolean unlocked(ServerPlayer player, MapFeature feature);

    /** The translation key the client shows when the player opens a part that is still locked. */
    default String lockedMessage(ServerPlayer player) {
        return "mapmod.map.locked";
    }
}
