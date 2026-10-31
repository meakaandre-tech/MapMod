package com.meakaandre.mapmod.api;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.map.MapFog;
import com.meakaandre.mapmod.map.MapServer;
import com.meakaandre.mapmod.map.MapFound;
import com.meakaandre.mapmod.map.MapWorker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * The server side of the map's API, and the registry both sides share. Everything has a default that works with no
 * other mod at all: every player alone, everything unlocked, no POIs. (The client side of the API is {@link MapOverlay},
 * {@link MapMarkers}, {@link MapPalette}, {@link MapLock}, {@link MapSurface} and {@link MapTiles}; everything outside
 * this package is internal and may change in any release.)
 * <ul>
 * <li><b>Sharing groups</b> ({@link #setGroups}): who shares fog, shared markers and compass players.</li>
 * <li><b>Unlocks</b> ({@link #setUnlocks}): per-player gates for terrain and the fullscreen map, sent to the client by
 * the server. A client-side lock with no server involved is {@link MapLock}.</li>
 * <li><b>POIs</b>: categories and kinds ({@link #category}, {@link #kind}) and what supplies them: worked out per group and
 * region ({@link #addSource}), found in chunks as they are recorded ({@link #addChunkScanner}, {@link #setChunkPois}),
 * or moving ({@link #addMovingSource}).</li>
 * <li><b>Reveal</b> ({@link #reveal}): clears a group's fog over a disc (POIs there then show; terrain shows on a
 * client once that client has seen the land itself).</li>
 * <li><b>Markers</b> ({@link #deathMarker}, {@link #removeMarker}, {@link #skipDeathMarkers}).</li>
 * </ul>
 * Registration (providers, categories, kinds, sources, scanners, glyphs) belongs in mod initialisation; categories,
 * kinds and glyphs must be registered on both sides, identically. The other calls are for the server thread.
 */
public final class MapModApi {
    /** The map's own categories, always first in the filter panel. */
    public static final PoiCategory PLAYERS = new PoiCategory(MapMod.id("players"), Component.translatable("mapmod.map.filter.players"));
    public static final PoiCategory MARKERS = new PoiCategory(MapMod.id("markers"), Component.translatable("mapmod.map.filter.markers"));

    private static GroupProvider groups = GroupProvider.ALONE;
    private static UnlockProvider unlocks = UnlockProvider.ALWAYS;
    private static final Map<Identifier, PoiCategory> CATEGORIES = new LinkedHashMap<>();
    private static final Map<String, PoiKind> KINDS = new LinkedHashMap<>();
    private static final List<Registered<PoiSource<?>>> SOURCES = new ArrayList<>();
    private static final List<Registered<ChunkScanner>> SCANNERS = new ArrayList<>();
    private static final List<MovingSource> MOVING = new ArrayList<>();
    private static Predicate<ServerPlayer> skipDeath = p -> false;

    /** A registered source or scanner and its id. */
    public record Registered<T>(Identifier id, T value) {
    }

    static {
        CATEGORIES.put(PLAYERS.id(), PLAYERS);
        CATEGORIES.put(MARKERS.id(), MARKERS);
    }

    private MapModApi() {
    }

    // ------------------------------------------------------------------------------------------------ groups

    /** Sets who shares a map (see {@link GroupProvider}); there is one provider, the last set wins. */
    public static void setGroups(GroupProvider provider) {
        groups = Objects.requireNonNull(provider);
    }

    public static GroupProvider groups() {
        return groups;
    }

    /** The player's group, as the provider says. */
    public static String groupOf(ServerPlayer player) {
        return groups.groupOf(player.level().getServer(), player.getUUID());
    }

    /** Sends the player their group's map at once if their group changed (otherwise this happens within a second). */
    public static void groupChanged(ServerPlayer player) {
        MapServer.groupChanged(player);
    }

    /** One group folded into another: its fog and its shared markers go with it. */
    public static void mergeGroups(MinecraftServer server, String from, String into) {
        MapServer.merge(server, from, into);
    }

    // ------------------------------------------------------------------------------------------------ unlocks

    /** Sets the per-player gates (see {@link UnlockProvider}); there is one provider, the last set wins. */
    public static void setUnlocks(UnlockProvider provider) {
        unlocks = Objects.requireNonNull(provider);
    }

    public static UnlockProvider unlocks() {
        return unlocks;
    }

    public static boolean unlocked(ServerPlayer player, MapFeature feature) {
        return unlocks.unlocked(player, feature);
    }

    /** Sends the player's unlocks to their client at once (otherwise this happens within a second). */
    public static void unlocksChanged(ServerPlayer player) {
        MapServer.sendUnlocks(player, true);
    }

    // ------------------------------------------------------------------------------------------------ POIs

    /** Registers a filter category; its switch comes after those registered before it. */
    public static synchronized PoiCategory category(Identifier id, Component label) {
        PoiCategory c = new PoiCategory(id, label);
        if (CATEGORIES.putIfAbsent(id, c) != null) throw new IllegalStateException("map category " + id + " is registered twice");
        return c;
    }

    /** Starts a POI kind in a category (null for one with no switch, such as moving things); finish with {@code register()}. */
    public static PoiKind.Builder kind(Identifier id, @Nullable PoiCategory category) {
        return new PoiKind.Builder(id, category, MapModApi::put);
    }

    private static synchronized PoiKind put(PoiKind kind) {
        if (KINDS.putIfAbsent(kind.key(), kind) != null) throw new IllegalStateException("map POI kind " + kind.key() + " is registered twice");
        return kind;
    }

    public static synchronized List<PoiCategory> categories() {
        return List.copyOf(CATEGORIES.values());
    }

    public static synchronized List<PoiKind> kinds() {
        return List.copyOf(KINDS.values());
    }

    public static synchronized @Nullable PoiKind kind(String key) {
        return KINDS.get(key);
    }

    /** Adds a source of POIs worked out per group and region (see {@link PoiSource}). */
    public static synchronized void addSource(Identifier id, PoiSource<?> source) {
        SOURCES.add(new Registered<>(id, source));
    }

    public static synchronized List<Registered<PoiSource<?>>> sources() {
        return Collections.unmodifiableList(new ArrayList<>(SOURCES));
    }

    /** Works out a group's POIs from every source again (what it may see changed). */
    public static void refreshPois(MinecraftServer server, String group) {
        MapServer.refreshGroup(server, group);
    }

    /** Works out every group's POIs from every source again. */
    public static void refreshPois(MinecraftServer server) {
        MapServer.refreshAll(server);
    }

    /** Adds a chunk hook (see {@link ChunkScanner}); its id names what it found, for {@link #setChunkPois}. */
    public static synchronized void addChunkScanner(Identifier id, ChunkScanner scanner) {
        SCANNERS.add(new Registered<>(id, scanner));
    }

    public static synchronized List<Registered<ChunkScanner>> scanners() {
        return Collections.unmodifiableList(new ArrayList<>(SCANNERS));
    }

    /**
     * Replaces what a scanner found in one chunk, without the chunk being loaded (a search that had it generated, say);
     * when the chunk loads for real the scanner is asked again as usual.
     */
    public static void setChunkPois(ServerLevel level, Identifier scanner, int chunkX, int chunkZ, List<FoundPoi> pois) {
        MapFound.set(level, scanner, chunkX, chunkZ, pois);
    }

    /** Every found POI of a kind within this many blocks of a spot, nearest first. */
    public static List<MapPoi> foundNear(PoiKind kind, int x, int z, int blocks) {
        return MapFound.near(kind.key(), x, z, blocks);
    }

    /** What a group sees found in a region (any explored or not). */
    public static List<MapPoi> foundIn(long region, String group) {
        return MapFound.foundFor(region, group);
    }

    /** Adds a source of moving POIs (see {@link MovingSource}). */
    public static synchronized void addMovingSource(MovingSource source) {
        MOVING.add(source);
    }

    public static synchronized List<MovingSource> movingSources() {
        return List.copyOf(MOVING);
    }

    // ------------------------------------------------------------------------------------------------ fog

    /** True for the dimension the map covers: only the Overworld's surface is mapped. */
    public static boolean mapped(Level level) {
        return MapServer.mapped(level);
    }

    /**
     * Clears a group's fog over a disc round a spot (a radar's sweep) and sends it to the group's players: POIs there show
     * on their maps and compasses. The group's fog gates POIs only; the painted terrain is what each client has captured
     * itself. Server thread.
     */
    public static void reveal(ServerLevel level, String group, BlockPos centre, int radiusBlocks) {
        MapServer.reveal(level, group, centre, radiusBlocks);
    }

    /** True if the group has explored the chunk. */
    public static boolean explored(String group, int chunkX, int chunkZ) {
        return MapFog.explored(group, chunkX, chunkZ);
    }

    // ------------------------------------------------------------------------------------------------ markers

    /**
     * Places a death marker for a player. A plain one ({@code kept} false) is one of the last few kept; a kept one stays
     * until {@link #removeMarker} (one that shows a grave or a death crate). Returns its id.
     */
    public static String deathMarker(ServerPlayer player, ServerLevel level, BlockPos at, boolean kept) {
        return MapServer.deathMarker(player, level, at, kept);
    }

    /** Takes a marker away, for everyone who saw it. */
    public static void removeMarker(MinecraftServer server, String markerId) {
        MapServer.removeMarker(server, markerId);
    }

    /** Players for whom the map places no death marker when they die (a mod placing its own, kept one). */
    public static synchronized void skipDeathMarkers(Predicate<ServerPlayer> skip) {
        Predicate<ServerPlayer> before = skipDeath;
        skipDeath = p -> before.test(p) || skip.test(p);
    }

    public static synchronized boolean skipsDeathMarker(ServerPlayer player) {
        return skipDeath.test(player);
    }

    // ------------------------------------------------------------------------------------------------ the worker

    /** The map's worker thread, where POI sources run by default. */
    public static Executor worker() {
        return MapWorker.EXECUTOR;
    }
}
