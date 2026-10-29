package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.api.MapModApi;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.PoiCategory;
import com.meakaandre.mapmod.api.PoiKind;
import com.meakaandre.mapmod.api.PoiSubType;
import com.meakaandre.mapmod.map.MapMarker;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The fullscreen map's filter panel, as Satisfactory's: a switch for each category (the map's own players and markers
 * first, then those mods registered, in order), and inside most of them a switch for each kind or sub-type. A switch
 * that is off hides its things on the map, the minimap and the compass.
 */
public final class MapFilters {
    public static final String PLACED = "markers:placed", DEATHS = "markers:death";
    /** The mob radar's switches: all mobs, and hostile and passive ones (the client's own, not a category of the API). */
    public static final String MOBS = "cat:mapmod:mobs", HOSTILE = "mobs:hostile", PASSIVE = "mobs:passive";

    /**
     * One switch.
     *
     * @param key    what {@link MapClient#HIDDEN} holds while it is off
     * @param parent the category switch it sits under, or null for a category
     */
    public record Filter(String key, Component label, @Nullable String parent) {
    }

    private static List<Filter> all = List.of();
    private static int builtFor = -1;

    private MapFilters() {
    }

    /** Every switch, in panel order (worked out again when more kinds have been registered). */
    public static List<Filter> all() {
        int size = MapModApi.categories().size() * 1000 + MapModApi.kinds().size();
        if (size == builtFor) return all;
        List<Filter> out = new ArrayList<>();
        List<PoiKind> kinds = MapModApi.kinds();
        for (PoiCategory c : MapModApi.categories()) {
            String cat = c.filterKey();
            out.add(new Filter(cat, c.label(), null));
            if (c == MapModApi.MARKERS) {
                out.add(new Filter(PLACED, Component.translatable("mapmod.map.filter.placed"), cat));
                out.add(new Filter(DEATHS, Component.translatable("mapmod.map.filter.deaths"), cat));
                out.add(new Filter(MOBS, Component.translatable("mapmod.map.filter.mobs"), null));
                out.add(new Filter(HOSTILE, Component.translatable("mapmod.map.filter.hostile"), MOBS));
                out.add(new Filter(PASSIVE, Component.translatable("mapmod.map.filter.passive"), MOBS));
            }
            for (PoiKind k : kinds) {
                if (k.category() == null || !k.category().id().equals(c.id())) continue;
                if (k.filter()) out.add(new Filter(k.filterKey(), k.label(), cat));
                if (k.subFilters()) for (PoiSubType s : k.subTypes().values()) out.add(new Filter(k.filterKey(s.id()), s.label(), cat));
            }
        }
        all = List.copyOf(out);
        builtFor = size;
        return all;
    }

    public static boolean on(String key) {
        return !MapClient.HIDDEN.contains(key);
    }

    public static void toggle(String key) {
        if (!MapClient.HIDDEN.remove(key)) MapClient.HIDDEN.add(key);
        MapClient.saveSettings();
    }

    public static boolean shows(MapPoi poi) {
        PoiKind kind = MapModApi.kind(poi.kind());
        if (kind == null) return false;
        if (kind.category() != null && !on(kind.category().filterKey())) return false;
        if (kind.filter() && !on(kind.filterKey())) return false;
        return !kind.subFilters() || on(kind.filterKey(poi.sub()));
    }

    public static boolean shows(MapMarker m) {
        return on(MapModApi.MARKERS.filterKey()) && on(m.death ? DEATHS : PLACED);
    }

    public static boolean showsPlayers() {
        return on(MapModApi.PLAYERS.filterKey());
    }
}
