package com.meakaandre.mapmod.api;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Moving things a player's map shows, asked on the server thread once a second for every online player in a mapped dimension. */
@FunctionalInterface
public interface MovingSource {
    List<MovingPoi> moving(ServerPlayer player, String group);
}
