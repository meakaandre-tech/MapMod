package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.api.MapGlyphs;
import net.minecraft.network.chat.Component;

/**
 * The fullscreen map's overlay layers (MM-5), each switched on or off from its button in the top bar and kept in
 * {@code mapmod/settings.json} under {@code layers}: the players (you and your group), markers (waypoints placed and
 * death markers), pins of POIs, POIs and moving things, and the mob radar. A layer that is off hides its things on the
 * fullscreen map only; the filter panel's switches still apply inside a layer that is on.
 */
public enum MapLayer {
    PLAYERS("players", MapGlyphs.ARROW),
    MARKERS("markers", MapGlyphs.FLAG),
    PINS("pins", MapGlyphs.PIN),
    POIS("pois", MapGlyphs.NODE),
    MOBS("mobs", MapGlyphs.EYE);

    /** Its name in settings.json and its lang key. */
    public final String id;
    /** The glyph on its button. */
    public final int glyph;

    MapLayer(String id, int glyph) {
        this.id = id;
        this.glyph = glyph;
    }

    public Component label() {
        return Component.translatable("mapmod.map.layer." + id);
    }

    public boolean on() {
        return !MapClient.LAYERS_OFF.contains(this);
    }
}
