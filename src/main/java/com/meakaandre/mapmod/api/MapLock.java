package com.meakaandre.mapmod.api;

import com.meakaandre.mapmod.MapMod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one way to hide parts of the map on purpose, on the client: lock the whole fullscreen map until your mod unlocks
 * it (a research unlock, say), or lock single layers. A client-side check your mod drives: MapMod sends nothing for it,
 * so a mod that bases a lock on server state syncs that state itself. Nothing is locked by default.
 * <p>
 * Every lock has an owner, the id of whoever locked it (your mod's), so mods never undo each other's locks: something
 * stays locked while any owner still locks it. Locks last until they are lifted, across worlds and servers.
 * <ul>
 * <li>A locked <b>map</b> still opens with M, and shows a "map locked" panel with the reason given instead of the map:
 * no terrain, no layers, no markers. The compass strip stays; lock layers too to keep their things off it.</li>
 * <li>A locked <b>layer</b> is hidden on the fullscreen map and on the compass strip, its switch leaves the top bar,
 * and the player's own setting for it is kept for when it unlocks. Layers are {@link #CONTOURS} (the painted map's
 * contour lines), {@link #PLAYERS}, {@link #MARKERS} (placed and death markers, and {@link MapMarkers}' markers),
 * {@link #PINS}, {@link #POIS} (POIs and moving things) and {@link #MOB_RADAR}, and each {@link MapOverlay} by its
 * id.</li>
 * </ul>
 * What the fog shows does not change: the painted terrain is everything this client has captured itself, whatever the
 * group's fog says (that gates POIs only). Client thread.
 */
public final class MapLock {
    /** The painted map's contour lines. */
    public static final Identifier CONTOURS = MapMod.id("contours");
    /** You and your group's players. */
    public static final Identifier PLAYERS = MapMod.id("players");
    /** Placed markers, death markers and {@link MapMarkers}' markers. */
    public static final Identifier MARKERS = MapMod.id("markers");
    /** The player's pins over POIs. */
    public static final Identifier PINS = MapMod.id("pins");
    /** POIs and moving things. */
    public static final Identifier POIS = MapMod.id("pois");
    /** The mob radar. */
    public static final Identifier MOB_RADAR = MapMod.id("mobs");

    /** Who locks the map, and why. */
    private static final Map<Identifier, Component> MAP = new LinkedHashMap<>();
    /** Per layer, who locks it. */
    private static final Map<Identifier, Set<Identifier>> LAYERS = new HashMap<>();
    private static volatile boolean mapLocked;
    private static volatile Set<Identifier> lockedLayers = Set.of();
    private static volatile @Nullable Component reason;

    private MapLock() {
    }

    /**
     * Locks the whole fullscreen map for {@code owner} (your mod's id), with what the locked panel says under "Map
     * locked" (what unlocks it, say). Locking again changes the reason.
     */
    public static synchronized void lockMap(Identifier owner, Component reason) {
        MAP.put(Objects.requireNonNull(owner), Objects.requireNonNull(reason));
        update();
    }

    /** Lifts {@code owner}'s lock on the whole map; the map opens again once no owner locks it. */
    public static synchronized void unlockMap(Identifier owner) {
        MAP.remove(owner);
        update();
    }

    /** True while any owner locks the whole map. */
    public static boolean mapLocked() {
        return mapLocked;
    }

    /** What the locked panel says: the reason of the first lock still in place, or null while the map is open. */
    public static @Nullable Component reason() {
        return reason;
    }

    /** Locks one layer for {@code owner}: one of this class's layer ids, or a {@link MapOverlay}'s. */
    public static synchronized void lockLayer(Identifier owner, Identifier layer) {
        LAYERS.computeIfAbsent(Objects.requireNonNull(layer), k -> new java.util.HashSet<>()).add(Objects.requireNonNull(owner));
        update();
    }

    /** Lifts {@code owner}'s lock on one layer. */
    public static synchronized void unlockLayer(Identifier owner, Identifier layer) {
        Set<Identifier> by = LAYERS.get(layer);
        if (by != null && by.remove(owner) && by.isEmpty()) LAYERS.remove(layer);
        update();
    }

    /** True while any owner locks this layer. */
    public static boolean layerLocked(Identifier layer) {
        return lockedLayers.contains(layer);
    }

    /** Lifts every lock {@code owner} holds, on the map and on layers. */
    public static synchronized void unlockAll(Identifier owner) {
        MAP.remove(owner);
        LAYERS.values().forEach(by -> by.remove(owner));
        LAYERS.values().removeIf(Set::isEmpty);
        update();
    }

    private static void update() {
        lockedLayers = Set.copyOf(LAYERS.keySet());
        reason = MAP.isEmpty() ? null : MAP.values().iterator().next();
        mapLocked = !MAP.isEmpty();
    }
}
