package com.meakaandre.mapmod.api;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Who shares a map. Players of one group share their fog of war and their shared markers, and see each other on the
 * compass and the map. Group ids are free text: a team id, a company id, anything stable. The default puts every player
 * in a group of their own (the group id is their UUID), so nothing is shared.
 * <p>
 * Asked on the server thread. A player's group is looked at once a second; a player whose group changed gets the new
 * group's map then (or at once after {@link MapModApi#groupChanged}).
 */
public interface GroupProvider {
    GroupProvider ALONE = (server, player) -> player.toString();

    /** The player's group; never null. An empty id means no group: the player keeps no fog and shares nothing. */
    String groupOf(MinecraftServer server, UUID player);

    /** True if two group ids are the same group (an empty id is no group, and the same as nothing). */
    default boolean same(String a, String b) {
        return !a.isEmpty() && a.equals(b);
    }

    /** True if the map should leave this player out entirely (fake players of automation mods). */
    default boolean ignored(ServerPlayer player) {
        return player instanceof FakePlayer;
    }

    /** The translation key the marker form uses for "shared with the group" (a team, a company...). */
    default String sharedLabel() {
        return "mapmod.map.shared";
    }
}
