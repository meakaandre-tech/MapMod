package com.meakaandre.mapmod.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Markers your mod places on the client's map (vehicles, a base, a mission target), each with a glyph, a colour and an
 * optional label, in a dimension. They show on the fullscreen map in the markers layer, under the markers filter, with
 * their label and position when hovered, and can be pinned like a player's markers. Markers are this client's only:
 * MapMod sends nothing for them, so a mod that shares markers syncs them itself and calls these on each client. They are
 * all removed when the client leaves a world or server. Client thread.
 */
public final class MapMarkers {
    /**
     * One marker.
     *
     * @param id        yours, unique (your mod's namespace); adding a marker with an id already used replaces it
     * @param dimension where it is; it shows only while the player is in that dimension (the fullscreen map covers the
     *                  Overworld only)
     * @param glyph     a {@link MapGlyphs} glyph
     * @param colour    the glyph's tint, ARGB
     * @param label     what hovering it shows, or null for no label (the position still shows)
     */
    public record Marker(Identifier id, ResourceKey<Level> dimension, double x, double z, int glyph, int colour, @Nullable Component label) {
        public Marker {
            Objects.requireNonNull(id);
            Objects.requireNonNull(dimension);
        }

        /** The same marker somewhere else. */
        public Marker at(ResourceKey<Level> dimension, double x, double z) {
            return new Marker(id, dimension, x, z, glyph, colour, label);
        }
    }

    private static final Map<Identifier, Marker> MARKERS = new LinkedHashMap<>();
    private static volatile List<Marker> all = List.of();

    private MapMarkers() {
    }

    /** Adds a marker, or replaces the one with its id. */
    public static synchronized void add(Marker marker) {
        MARKERS.put(marker.id(), marker);
        all = List.copyOf(MARKERS.values());
    }

    /** Moves a marker (in its own dimension); false if there is none with this id. */
    public static boolean move(Identifier id, double x, double z) {
        Marker m = get(id);
        return m != null && move(id, m.dimension(), x, z);
    }

    /** Moves a marker, to another dimension too; false if there is none with this id. */
    public static synchronized boolean move(Identifier id, ResourceKey<Level> dimension, double x, double z) {
        Marker m = MARKERS.get(id);
        if (m == null) return false;
        MARKERS.put(id, m.at(dimension, x, z));
        all = List.copyOf(MARKERS.values());
        return true;
    }

    /** Removes a marker; false if there was none with this id. */
    public static synchronized boolean remove(Identifier id) {
        if (MARKERS.remove(id) == null) return false;
        all = List.copyOf(MARKERS.values());
        return true;
    }

    /** Removes every marker whose id is in this namespace (a mod's own). */
    public static synchronized void removeAll(String namespace) {
        if (MARKERS.keySet().removeIf(id -> id.getNamespace().equals(namespace))) all = List.copyOf(MARKERS.values());
    }

    public static @Nullable Marker get(Identifier id) {
        for (Marker m : all) if (m.id().equals(id)) return m;
        return null;
    }

    /** Every marker in a dimension, in the order added. */
    public static List<Marker> in(ResourceKey<Level> dimension) {
        List<Marker> out = new ArrayList<>();
        for (Marker m : all) if (m.dimension().equals(dimension)) out.add(m);
        return out;
    }

    /** Every marker, in the order added. */
    public static List<Marker> all() {
        return all;
    }

    /** Leaving a world: every marker goes (internal). */
    public static synchronized void clear() {
        MARKERS.clear();
        all = List.of();
    }
}
