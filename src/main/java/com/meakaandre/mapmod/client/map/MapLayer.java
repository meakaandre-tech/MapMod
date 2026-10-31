package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.api.MapGlyphs;
import com.meakaandre.mapmod.api.MapLock;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The fullscreen map's overlay layers (MM-5), each switched on or off from its button in the top bar and kept in
 * {@code mapmod/settings.json} under {@code layers}: the players (you and your group), markers (waypoints placed and
 * death markers), pins of POIs, POIs and moving things, and the mob radar. A layer that is off hides its things on the
 * fullscreen map only; the filter panel's switches still apply inside a layer that is on. A layer a mod locks
 * ({@link MapLock}) is hidden on the map and the compass strip whatever its switch says, and its switch leaves the top bar.
 */
public enum MapLayer {
    PLAYERS("players", MapGlyphs.ARROW, MapLock.PLAYERS),
    MARKERS("markers", MapGlyphs.FLAG, MapLock.MARKERS),
    PINS("pins", MapGlyphs.PIN, MapLock.PINS),
    POIS("pois", MapGlyphs.NODE, MapLock.POIS),
    MOBS("mobs", MapGlyphs.EYE, MapLock.MOB_RADAR);

    /** Its name in settings.json and its lang key. */
    public final String id;
    /** The glyph on its button. */
    public final int glyph;
    /** Its id for {@link MapLock}. */
    public final Identifier lockId;

    MapLayer(String id, int glyph, Identifier lockId) {
        this.id = id;
        this.glyph = glyph;
        this.lockId = lockId;
    }

    public Component label() {
        return Component.translatable("mapmod.map.layer." + id);
    }

    /** True if the player has its switch on. */
    public boolean on() {
        return !MapClient.LAYERS_OFF.contains(this);
    }

    /** True if a mod locks it. */
    public boolean locked() {
        return MapLock.layerLocked(lockId);
    }

    /** True if it is drawn: switched on and not locked. */
    public boolean shown() {
        return on() && !locked();
    }
}
