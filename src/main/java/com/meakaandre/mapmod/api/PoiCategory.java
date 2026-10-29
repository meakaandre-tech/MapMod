package com.meakaandre.mapmod.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A switch in the fullscreen map's filter panel, with its kinds under it. The map's own two (players, markers) come
 * first; registered ones follow in the order they were registered ({@link MapModApi#category}).
 */
public record PoiCategory(Identifier id, Component label) {
    /** The filter key the client keeps while the switch is off. */
    public String filterKey() {
        return "cat:" + id;
    }
}
