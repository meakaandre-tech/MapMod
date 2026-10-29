package com.meakaandre.mapmod.map;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapModApi;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Every map marker of the world: private ones, shared ones and death markers ({@code data/mapmod/markers.dat}). */
public class MapMarkers extends SavedData {
    /** Death markers kept per player: the oldest goes when there are more. */
    public static final int DEATHS_KEPT = 3;
    /** Markers a player may have placed (private and shared together). */
    public static final int MAX_PER_PLAYER = 200;

    public static final Codec<MapMarkers> CODEC = RecordCodecBuilder.create(i -> i.group(
        MapMarker.CODEC.listOf().optionalFieldOf("markers", List.of()).forGetter(m -> m.markers)
    ).apply(i, MapMarkers::new));
    private static final SavedDataType<MapMarkers> TYPE = new SavedDataType<>(MapMod.id("markers"), MapMarkers::new, CODEC, null);

    private final List<MapMarker> markers;

    public MapMarkers() {
        this(List.of());
    }

    private MapMarkers(List<MapMarker> markers) {
        this.markers = new ArrayList<>(markers);
    }

    public static MapMarkers get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<MapMarker> all() {
        return markers;
    }

    /** True if this player (in this group) sees the marker. */
    public static boolean visible(MapMarker m, String player, String group) {
        return m.owner.equals(player) || (m.shared && MapModApi.groups().same(m.group, group));
    }

    public List<MapMarker> visibleTo(String player, String group) {
        List<MapMarker> out = new ArrayList<>();
        for (MapMarker m : markers) if (visible(m, player, group)) out.add(m);
        return out;
    }

    public @Nullable MapMarker byId(String id) {
        for (MapMarker m : markers) if (m.id.equals(id)) return m;
        return null;
    }

    public int countOf(String player) {
        int n = 0;
        for (MapMarker m : markers) if (m.owner.equals(player) && !m.death) n++;
        return n;
    }
}
