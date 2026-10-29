package com.meakaandre.mapmod.api;

/**
 * Something that moves (a vehicle, a drone), sent to a player's client once a second.
 *
 * @param kind   the {@link PoiKind}'s id
 * @param dimmed drawn dimmer: where it would be rather than where it is (its land is not loaded)
 * @param note   a translation key shown under its name when hovered, or empty
 */
public record MovingPoi(String kind, double x, double z, float yaw, boolean dimmed, String note) {
    public static MovingPoi of(PoiKind kind, double x, double z, float yaw, boolean dimmed, String note) {
        return new MovingPoi(kind.key(), x, z, yaw, dimmed, note);
    }
}
