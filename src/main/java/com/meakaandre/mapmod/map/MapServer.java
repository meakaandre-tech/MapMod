package com.meakaandre.mapmod.map;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.GroupProvider;
import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.api.MapModApi;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
import com.meakaandre.mapmod.api.MovingSource;
import com.meakaandre.mapmod.api.PoiArea;
import com.meakaandre.mapmod.api.PoiSource;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The map's server side: fog of war shared by each group, the Overworld's terrain tiles, the POIs the providers supply,
 * markers and death markers, and the group's players for the compass. It sends each client only what its group has
 * explored, and terrain only once the player has it unlocked.
 */
public final class MapServer {
    /** Chunks round a player that count as visited. */
    public static final int VISIT_RADIUS = 6;
    /** Regions of POIs or fog sent in one packet at most. */
    private static final int BATCH = 64;
    /** Tiles sent for one request at most. */
    private static final int TILES_PER_REQUEST = 12;
    /** Chunks a reveal's guess works out in one go on the worker. */
    private static final int GUESS_BATCH = 48;
    /** A player's tile requests are answered at most this often (ticks); the client asks once a second. */
    private static final int TILE_REQUEST_TICKS = 10;
    /** Regions of a tile request looked at, at most. */
    private static final int REQUEST_REGIONS = 64;
    /** Regions of one source's POIs kept per group; past this the cache is emptied and worked out again as needed. */
    private static final int SOURCE_REGIONS_KEPT = 4096;
    /** Ticks in a second: fog clearing round players, group, unlock and stamp checks, and moving things run once a second. */
    private static final int TICKS_PER_SECOND = 20;
    /** Ticks between group-mate updates (twice a second). */
    private static final int MATES_TICKS = 10;
    /** Ticks between fog saves (a minute). */
    private static final int FOG_SAVE_TICKS = 1200;
    /** Packed tiles cached at most; past this the cache is emptied. */
    private static final int PACKED_KEPT = 256;

    /** One source's POIs for one group. */
    private record Key(String group, int source) {
    }

    private static @Nullable String mapId;
    /** Per group and source: the POIs worked out for each region. */
    private static final Map<Key, Long2ObjectOpenHashMap<List<MapPoi>>> COMPUTED = new HashMap<>();
    /** Per group and source: the stamp its POIs were worked out for. */
    private static final Map<Key, Integer> STAMP = new HashMap<>();
    /** Per group and source: regions waiting for their POIs. */
    private static final Map<Key, LongOpenHashSet> PENDING = new HashMap<>();
    private static final Set<Key> RUNNING = new HashSet<>();
    /** Which group each online player was last sent the map of. */
    private static final Map<UUID, String> SYNCED = new HashMap<>();
    /**
     * Players whose client has had the map: a client says which payloads it takes only after it has joined, so what was
     * sent at the join may never have arrived, and the map is sent again until it does.
     */
    private static final Set<UUID> GREETED = new HashSet<>();
    /** What each online player was last told they have unlocked. */
    private static final Map<UUID, Integer> UNLOCKS = new HashMap<>();
    /** When each player's last tile request was answered (server ticks). */
    private static final Map<UUID, Integer> TILE_ASKED = new HashMap<>();
    /** Players last sent an empty list of mates or of moving things: an empty list is sent once, not every time. */
    private static final Set<UUID> NO_MATES = new HashSet<>(), NO_MOVING = new HashSet<>();
    /** Packed tiles, kept while their version is current. */
    private static final Long2ObjectOpenHashMap<byte[]> PACKED = new Long2ObjectOpenHashMap<>();
    private static final Long2LongOpenHashMap PACKED_AT = new Long2LongOpenHashMap();

    private MapServer() {
    }

    /** Only the Overworld's surface is mapped. */
    public static boolean mapped(Level level) {
        return level.dimension() == Level.OVERWORLD;
    }

    private static GroupProvider groups() {
        return MapModApi.groups();
    }

    private static boolean ignored(ServerPlayer player) {
        return groups().ignored(player);
    }

    /** The group the player was last sent the map of (as the provider said then); "" if none yet. */
    public static String groupOf(ServerPlayer player) {
        String g = SYNCED.get(player.getUUID());
        return g != null ? g : MapModApi.groupOf(player);
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Hello.TYPE, MapPayloads.Hello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Unlocks.TYPE, MapPayloads.Unlocks.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Fog.TYPE, MapPayloads.Fog.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.FogDelta.TYPE, MapPayloads.FogDelta.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Pois.TYPE, MapPayloads.Pois.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Tile.TYPE, MapPayloads.Tile.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Markers.TYPE, MapPayloads.Markers.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Mates.TYPE, MapPayloads.Mates.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Moving.TYPE, MapPayloads.Moving.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MapPayloads.TileRequest.TYPE, MapPayloads.TileRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MapPayloads.MarkerEdit.TYPE, MapPayloads.MarkerEdit.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MapPayloads.TileRequest.TYPE, (payload, context) -> context.server().execute(() -> tiles(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(MapPayloads.MarkerEdit.TYPE, (payload, context) -> context.server().execute(() -> edit(context.player(), payload)));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MapTerrain.start(server);
            mapId = readMapId();
            // group players show on the compass; vanilla's locator bar would show everyone, so it is switched off
            server.getGameRules().set(GameRules.LOCATOR_BAR, false, server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MapFog.stop();
            MapTerrain.stop();
            COMPUTED.clear();
            STAMP.clear();
            PENDING.clear();
            RUNNING.clear();
            SYNCED.clear();
            GREETED.clear();
            UNLOCKS.clear();
            TILE_ASKED.clear();
            NO_MATES.clear();
            NO_MOVING.clear();
            PACKED.clear();
            PACKED_AT.clear();
            mapId = null;
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> MapTerrain.loaded(level, chunk));
        ServerPlayerEvents.JOIN.register(MapServer::sync);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            SYNCED.remove(id);
            GREETED.remove(id);
            UNLOCKS.remove(id);
            TILE_ASKED.remove(id);
            NO_MATES.remove(id);
            NO_MOVING.remove(id);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            // a mod that places its own kept death marker (a grave, a crate) says so with skipDeathMarkers
            if (entity instanceof ServerPlayer player && !ignored(player) && !MapModApi.skipsDeathMarker(player)) deathMarker(player);
        });
        ServerTickEvents.END_SERVER_TICK.register(MapServer::tick);
    }

    private static String readMapId() {
        Path root = MapTerrain.root();
        if (root == null) return UUID.randomUUID().toString();
        Path file = root.resolve("id.txt");
        try {
            if (Files.exists(file)) {
                String id = Files.readString(file).strip();
                if (id.matches("[0-9a-f-]{36}")) return id;
            }
            String id = UUID.randomUUID().toString();
            Files.writeString(file, id);
            return id;
        } catch (IOException e) {
            MapMod.LOGGER.error("could not read or write the map file {}", file, e);
            return UUID.randomUUID().toString();
        }
    }

    // ------------------------------------------------------------------------------------------------ ticking

    private static void tick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        MapTerrain.tick(overworld);
        int t = server.getTickCount();
        if (t % TICKS_PER_SECOND == 0) {
            checkPlayers(server);
            explore(server);
        }
        if (t % MATES_TICKS == 5) mates(server);
        if (t % TICKS_PER_SECOND == 15) moving(server);
        if (t % TICKS_PER_SECOND == 10) {
            checkStamps(server);
            long[] changed = MapTerrain.takeFoundChanges();
            if (changed.length > 0) foundChanged(server, changed);
            runPending(server);
        }
        if (t % FOG_SAVE_TICKS == 600) MapFog.saveAll();
    }

    /** Players whose group changed get their new group's map; players whose unlocks changed are told. */
    private static void checkPlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ignored(player)) continue;
            String before = SYNCED.get(player.getUUID());
            if (before == null) continue; // not joined yet: the join sends everything
            if (!GREETED.contains(player.getUUID()) || !before.equals(MapModApi.groupOf(player))) sync(player);
            else sendUnlocks(player, false);
        }
    }

    /** The groups with a player online. */
    private static Set<String> onlineGroups(MinecraftServer server) {
        Set<String> out = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String g = SYNCED.get(player.getUUID());
            if (g != null && !g.isEmpty()) out.add(g);
        }
        return out;
    }

    /** Players clear the fog round them, for their group. */
    private static void explore(MinecraftServer server) {
        Map<String, LongArrayList> cleared = new HashMap<>();
        Map<String, LongOpenHashSet> fresh = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!mapped(player.level()) || ignored(player)) continue;
            String group = SYNCED.get(player.getUUID());
            if (group == null || group.isEmpty()) continue;
            LongOpenHashSet newRegions = fresh.computeIfAbsent(group, k -> new LongOpenHashSet());
            LongArrayList list = MapFog.reveal(group, player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4, VISIT_RADIUS, newRegions);
            if (!list.isEmpty()) cleared.computeIfAbsent(group, k -> new LongArrayList()).addAll(list);
        }
        cleared.forEach((group, chunks) -> {
            send(server, group, new MapPayloads.FogDelta(chunks.toLongArray()));
            uncovered(server, group, chunks);
        });
        fresh.forEach((group, regions) -> {
            if (!regions.isEmpty()) pendAll(group, regions);
        });
    }

    private static void mates(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ignored(player)) continue;
            String group = SYNCED.getOrDefault(player.getUUID(), "");
            List<MapPayloads.Mate> mates = new ArrayList<>();
            if (!group.isEmpty()) {
                for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                    if (other == player || ignored(other) || other.level() != player.level() || other.isSpectator()) continue;
                    if (!groups().same(group, SYNCED.getOrDefault(other.getUUID(), ""))) continue;
                    mates.add(new MapPayloads.Mate(other.getGameProfile().name(), other.getX(), other.getY(), other.getZ(), other.getYRot()));
                }
            }
            // nobody to show: one empty list clears the client's, then nothing until someone turns up
            if (mates.isEmpty() && !NO_MATES.add(player.getUUID())) continue;
            if (!mates.isEmpty()) NO_MATES.remove(player.getUUID());
            send(player, new MapPayloads.Mates(mates));
        }
    }

    /** What moves on each player's map, as the moving sources say. */
    private static void moving(MinecraftServer server) {
        List<MovingSource> sources = MapModApi.movingSources();
        if (sources.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ignored(player)) continue;
            String group = SYNCED.getOrDefault(player.getUUID(), "");
            List<MovingPoi> out = new ArrayList<>();
            if (mapped(player.level())) {
                for (MovingSource s : sources) {
                    try {
                        List<MovingPoi> list = s.moving(player, group);
                        if (list != null) out.addAll(list);
                    } catch (RuntimeException e) {
                        MapMod.LOGGER.warn("a moving map source failed", e);
                    }
                }
            }
            if (out.isEmpty() && !NO_MOVING.add(player.getUUID())) continue;
            if (!out.isEmpty()) NO_MOVING.remove(player.getUUID());
            send(player, new MapPayloads.Moving(out));
        }
    }

    // ------------------------------------------------------------------------------------------------ sending

    /** Sends if the client takes it; true if it was sent. */
    private static boolean send(ServerPlayer player, CustomPacketPayload payload) {
        if (ignored(player) || !ServerPlayNetworking.canSend(player, payload.type())) return false;
        ServerPlayNetworking.send(player, payload);
        return true;
    }

    /** To every online player of the group. */
    public static void send(MinecraftServer server, String group, CustomPacketPayload payload) {
        if (group.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (groups().same(group, SYNCED.getOrDefault(player.getUUID(), ""))) send(player, payload);
        }
    }

    /** Tells the player's client what it has unlocked: always with {@code force}, else only if it changed. */
    public static void sendUnlocks(ServerPlayer player, boolean force) {
        if (ignored(player)) return;
        int mask = 0;
        for (MapFeature f : MapFeature.values()) if (MapModApi.unlocked(player, f)) mask |= f.bit();
        Integer before = UNLOCKS.get(player.getUUID());
        if (!force && before != null && before == mask) return;
        // remembered only once it reached the client, so one sent too early is sent again a second later
        if (send(player, new MapPayloads.Unlocks(mask, MapModApi.unlocks().lockedMessage(player)))) UNLOCKS.put(player.getUUID(), mask);
        else UNLOCKS.remove(player.getUUID());
    }

    /** Everything a player's client needs: the map's id, their unlocks, their group's fog, their markers and the POIs. */
    public static void sync(ServerPlayer player) {
        if (ignored(player)) return;
        MinecraftServer server = player.level().getServer();
        String group = MapModApi.groupOf(player);
        SYNCED.put(player.getUUID(), group);
        if (mapId != null && send(player, new MapPayloads.Hello(mapId, groups().sharedLabel()))) GREETED.add(player.getUUID());
        sendUnlocks(player, true);
        sendMarkers(player);
        if (group.isEmpty()) {
            send(player, new MapPayloads.Fog(true, List.of()));
            return;
        }
        Long2ObjectOpenHashMap<long[]> fog = MapFog.of(group);
        List<MapPayloads.FogRegion> regions = new ArrayList<>();
        boolean first = true;
        for (var e : fog.long2ObjectEntrySet()) {
            regions.add(new MapPayloads.FogRegion(e.getLongKey(), e.getValue().clone()));
            if (regions.size() >= BATCH) {
                send(player, new MapPayloads.Fog(first, regions));
                regions = new ArrayList<>();
                first = false;
            }
        }
        send(player, new MapPayloads.Fog(first, regions));
        // the POIs: sources whose stamp moved are worked out again; the rest come from the cache where it has them
        var sources = MapModApi.sources();
        for (int i = 0; i < sources.size(); i++) {
            Key key = new Key(group, i);
            if (stamp(server, sources.get(i).value(), group) != STAMP.getOrDefault(key, Integer.MIN_VALUE) && !RUNNING.contains(key)) refresh(server, key);
        }
        List<MapPayloads.RegionPois> out = new ArrayList<>();
        for (long region : fog.keySet()) {
            if (!complete(group, region, true)) continue;
            out.add(new MapPayloads.RegionPois(region, merged(region, group)));
            if (out.size() >= BATCH) {
                send(player, new MapPayloads.Pois(out));
                out = new ArrayList<>();
            }
        }
        if (!out.isEmpty()) send(player, new MapPayloads.Pois(out));
        runPending(server);
    }

    /** A player's group may have changed: they get the new group's map at once if so. */
    public static void groupChanged(ServerPlayer player) {
        if (ignored(player)) return;
        String before = SYNCED.get(player.getUUID());
        if (before == null) return; // not joined yet: the join sends everything
        if (!before.equals(MapModApi.groupOf(player))) sync(player);
    }

    // ------------------------------------------------------------------------------------------------ points of interest

    private static int stamp(MinecraftServer server, PoiSource<?> source, String group) {
        try {
            return source.stamp(server, group);
        } catch (RuntimeException e) {
            MapMod.LOGGER.warn("a map POI source's stamp failed", e);
            return 0;
        }
    }

    /** Sources whose stamp moved for a group online are worked out again. */
    private static void checkStamps(MinecraftServer server) {
        var sources = MapModApi.sources();
        if (sources.isEmpty()) return;
        for (String group : onlineGroups(server)) {
            for (int i = 0; i < sources.size(); i++) {
                Key key = new Key(group, i);
                if (RUNNING.contains(key) || !STAMP.containsKey(key)) continue;
                if (stamp(server, sources.get(i).value(), group) != STAMP.get(key)) refresh(server, key);
            }
        }
    }

    /**
     * True if every source has its POIs for the region worked out for the group; with {@code pend} the missing ones are
     * queued (their answer sends the region then).
     */
    private static boolean complete(String group, long region, boolean pend) {
        int n = MapModApi.sources().size();
        boolean all = true;
        for (int i = 0; i < n; i++) {
            Key key = new Key(group, i);
            Long2ObjectOpenHashMap<List<MapPoi>> computed = COMPUTED.get(key);
            if (computed != null && computed.containsKey(region)) continue;
            all = false;
            if (pend) PENDING.computeIfAbsent(key, k -> new LongOpenHashSet()).add(region);
        }
        return all;
    }

    private static void pendAll(String group, LongOpenHashSet regions) {
        int n = MapModApi.sources().size();
        for (int i = 0; i < n; i++) PENDING.computeIfAbsent(new Key(group, i), k -> new LongOpenHashSet()).addAll(regions);
    }

    /** What a region shows the group: every source's POIs and what was found there, in the chunks it has explored only. */
    private static List<MapPoi> merged(long region, String group) {
        List<MapPoi> out = new ArrayList<>();
        int n = MapModApi.sources().size();
        for (int i = 0; i < n; i++) {
            Long2ObjectOpenHashMap<List<MapPoi>> computed = COMPUTED.get(new Key(group, i));
            List<MapPoi> list = computed == null ? null : computed.get(region);
            if (list == null) continue;
            for (MapPoi p : list) if (shows(p, group)) out.add(p);
        }
        for (MapPoi p : MapTerrain.foundFor(region, group)) if (shows(p, group)) out.add(p);
        return out;
    }

    private static boolean shows(MapPoi p, String group) {
        return MapModApi.kind(p.kind()) != null && MapFog.explored(group, p.x() >> 4, p.z() >> 4);
    }

    /** True if a POI of the region stands in one of the chunks. */
    private static boolean anyIn(long region, String group, LongOpenHashSet chunks) {
        int n = MapModApi.sources().size();
        for (int i = 0; i < n; i++) {
            Long2ObjectOpenHashMap<List<MapPoi>> computed = COMPUTED.get(new Key(group, i));
            List<MapPoi> list = computed == null ? null : computed.get(region);
            if (list != null) for (MapPoi p : list) if (chunks.contains(MapGrid.key(p.x() >> 4, p.z() >> 4))) return true;
        }
        for (MapPoi p : MapTerrain.foundFor(region, group)) if (chunks.contains(MapGrid.key(p.x() >> 4, p.z() >> 4))) return true;
        return false;
    }

    /**
     * Chunks the group just explored: the regions they are in are sent again if a POI stands in one of them (POIs are
     * only sent for explored chunks). Regions whose POIs are not all worked out yet are left to the pending work.
     */
    private static void uncovered(MinecraftServer server, String group, LongList chunks) {
        LongOpenHashSet fresh = new LongOpenHashSet(chunks);
        LongOpenHashSet regions = new LongOpenHashSet();
        for (long c : chunks) regions.add(MapGrid.regionOfChunk(MapGrid.keyX(c), MapGrid.keyZ(c)));
        List<MapPayloads.RegionPois> out = new ArrayList<>();
        for (long region : regions) {
            if (!complete(group, region, false) || !anyIn(region, group, fresh)) continue;
            out.add(new MapPayloads.RegionPois(region, merged(region, group)));
            if (out.size() >= BATCH) {
                send(server, group, new MapPayloads.Pois(out));
                out = new ArrayList<>();
            }
        }
        if (!out.isEmpty()) send(server, group, new MapPayloads.Pois(out));
    }

    /** Works one source's POIs out again for every region the group has explored. */
    private static void refresh(MinecraftServer server, Key key) {
        PoiSource<?> source = MapModApi.sources().get(key.source).value();
        COMPUTED.remove(key);
        STAMP.put(key, stamp(server, source, key.group));
        PENDING.computeIfAbsent(key, k -> new LongOpenHashSet()).addAll(MapFog.of(key.group).keySet());
    }

    /** Every source's POIs worked out again for a group. */
    public static void refreshGroup(MinecraftServer server, String group) {
        if (group.isEmpty()) return;
        int n = MapModApi.sources().size();
        for (int i = 0; i < n; i++) refresh(server, new Key(group, i));
        runPending(server);
    }

    /** Every source's POIs worked out again for every group the map has worked anything out for. */
    public static void refreshAll(MinecraftServer server) {
        Set<String> groups = new HashSet<>(onlineGroups(server));
        for (Key k : COMPUTED.keySet()) groups.add(k.group);
        for (String g : groups) refreshGroup(server, g);
    }

    private static void runPending(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        ServerLevel level = server.overworld();
        var sources = MapModApi.sources();
        for (Key key : new ArrayList<>(PENDING.keySet())) {
            if (RUNNING.contains(key)) continue;
            if (key.source >= sources.size()) {
                PENDING.remove(key);
                continue;
            }
            PoiSource<?> source = sources.get(key.source).value();
            if (!source.ready(level)) continue;
            LongOpenHashSet regions = PENDING.remove(key);
            if (regions == null || regions.isEmpty()) continue;
            run(server, level, key, source, regions.toLongArray());
        }
    }

    private static <S> void run(MinecraftServer server, ServerLevel level, Key key, PoiSource<S> source, long[] regions) {
        S prepared;
        try {
            prepared = source.prepare(level, key.group);
        } catch (RuntimeException e) {
            MapMod.LOGGER.warn("a map POI source failed to prepare for group {}", key.group, e);
            return;
        }
        int stamp = STAMP.computeIfAbsent(key, k -> stamp(server, source, key.group));
        RUNNING.add(key);
        MapWorker.async(server, source.executor(), () -> {
            Long2ObjectOpenHashMap<List<MapPoi>> out = new Long2ObjectOpenHashMap<>();
            for (long region : regions) {
                List<MapPoi> list = source.pois(level, area(region), prepared);
                out.put(region, list == null ? List.of() : List.copyOf(list));
            }
            return out;
        }, result -> {
            RUNNING.remove(key);
            if (result == null) return;
            if (STAMP.getOrDefault(key, stamp) != stamp) {
                // it was refreshed while it ran: the new pending work answers instead
                runPending(server);
                return;
            }
            Long2ObjectOpenHashMap<List<MapPoi>> computed = COMPUTED.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());
            // a group that has explored very far: forget what it has not looked at lately rather than keep it all
            if (computed.size() + result.size() > SOURCE_REGIONS_KEPT) computed.clear();
            List<MapPayloads.RegionPois> out = new ArrayList<>();
            for (var e : result.long2ObjectEntrySet()) {
                computed.put(e.getLongKey(), e.getValue());
                if (!complete(key.group, e.getLongKey(), false)) continue;
                out.add(new MapPayloads.RegionPois(e.getLongKey(), merged(e.getLongKey(), key.group)));
                if (out.size() >= BATCH) {
                    send(server, key.group, new MapPayloads.Pois(out));
                    out = new ArrayList<>();
                }
            }
            if (!out.isEmpty()) send(server, key.group, new MapPayloads.Pois(out));
        });
    }

    /** A region's bounds, for the sources. */
    public static PoiArea area(long region) {
        int minX = MapGrid.keyX(region) * MapGrid.REGION_BLOCKS, minZ = MapGrid.keyZ(region) * MapGrid.REGION_BLOCKS;
        return new PoiArea(region, minX, minZ, minX + MapGrid.REGION_BLOCKS, minZ + MapGrid.REGION_BLOCKS);
    }

    /** What the group sees in a region now, as it would be sent (for the selftests); POIs not worked out yet are missing. */
    public static List<MapPoi> poisFor(String group, long region) {
        return merged(region, group);
    }

    /** Found POIs appeared or went: the groups that have those regions explored get them again. */
    private static void foundChanged(MinecraftServer server, long[] regions) {
        for (String group : onlineGroups(server)) {
            Long2ObjectOpenHashMap<long[]> fog = MapFog.of(group);
            List<MapPayloads.RegionPois> out = new ArrayList<>();
            for (long region : regions) {
                if (!fog.containsKey(region) || !complete(group, region, true)) continue;
                out.add(new MapPayloads.RegionPois(region, merged(region, group)));
            }
            if (!out.isEmpty()) send(server, group, new MapPayloads.Pois(out));
        }
    }

    // ------------------------------------------------------------------------------------------------ terrain tiles

    private static void tiles(ServerPlayer player, MapPayloads.TileRequest request) {
        if (ignored(player) || !MapModApi.unlocked(player, MapFeature.TERRAIN) || request.regions().length != request.versions().length) return;
        String group = SYNCED.getOrDefault(player.getUUID(), "");
        if (group.isEmpty()) return;
        // at most one request answered every so often per player, and only so many of its regions looked at
        int now = player.level().getServer().getTickCount();
        Integer last = TILE_ASKED.get(player.getUUID());
        if (last != null && now - last < TILE_REQUEST_TICKS && now >= last) return;
        TILE_ASKED.put(player.getUUID(), now);
        Long2ObjectOpenHashMap<long[]> fog = MapFog.of(group);
        int sent = 0;
        int looked = Math.min(request.regions().length, REQUEST_REGIONS);
        for (int i = 0; i < looked && sent < TILES_PER_REQUEST; i++) {
            long region = request.regions()[i];
            if (!fog.containsKey(region)) continue;
            MapTile tile = MapTerrain.tile(region);
            if (tile == null || tile.version == 0 || tile.version <= request.versions()[i]) continue;
            byte[] packed = PACKED.get(region);
            if (packed == null || PACKED_AT.get(region) != tile.version) {
                packed = tile.pack();
                PACKED.put(region, packed);
                PACKED_AT.put(region, tile.version);
                if (PACKED.size() > PACKED_KEPT) {
                    PACKED.clear();
                    PACKED_AT.clear();
                }
            }
            send(player, new MapPayloads.Tile(region, packed));
            sent++;
        }
    }

    // ------------------------------------------------------------------------------------------------ reveal

    /**
     * Clears the group's fog over a disc round a spot and, with {@code guess}, fills land nobody has loaded there with
     * the world generator's guess, so it shows on the map at once.
     */
    public static void reveal(ServerLevel level, String group, BlockPos at, int radiusBlocks, boolean guess) {
        if (!mapped(level) || group.isEmpty()) return;
        MinecraftServer server = level.getServer();
        int radius = Math.max(1, radiusBlocks >> 4);
        LongOpenHashSet newRegions = new LongOpenHashSet();
        int ccx = at.getX() >> 4, ccz = at.getZ() >> 4;
        LongArrayList cleared = MapFog.reveal(group, ccx, ccz, radius, newRegions);
        if (!cleared.isEmpty()) {
            uncovered(server, group, cleared);
            // the touched regions' whole fog: far fewer bytes than the chunks one by one
            LongOpenHashSet touched = new LongOpenHashSet();
            for (long c : cleared) touched.add(MapGrid.regionOfChunk(MapGrid.keyX(c), MapGrid.keyZ(c)));
            Long2ObjectOpenHashMap<long[]> fog = MapFog.of(group);
            List<MapPayloads.FogRegion> regions = new ArrayList<>();
            for (long r : touched) regions.add(new MapPayloads.FogRegion(r, fog.get(r).clone()));
            for (int i = 0; i < regions.size(); i += BATCH) {
                send(server, group, new MapPayloads.Fog(false, new ArrayList<>(regions.subList(i, Math.min(regions.size(), i + BATCH)))));
            }
        }
        if (!newRegions.isEmpty()) pendAll(group, newRegions);
        if (!guess) return;
        // guesses for every chunk in the disc with nothing recorded
        LongArrayList wanted = new LongArrayList();
        int r2 = radius * radius;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz > r2) continue;
                if (MapTerrain.needsGuess(ccx + dx, ccz + dz)) wanted.add(MapGrid.key(ccx + dx, ccz + dz));
            }
        }
        if (wanted.isEmpty()) return;
        int sea = level.getSeaLevel();
        // thousands of chunks: guessed a small batch at a time, so the worker is never held up for long
        MapWorker.batched(server, wanted, GUESS_BATCH, batch -> {
            List<MapTerrain.Guess> guesses = new ArrayList<>(batch.size());
            for (long c : batch) guesses.add(MapTerrain.guess(level, MapGrid.keyX(c), MapGrid.keyZ(c), sea));
            return guesses;
        }, guesses -> {
            if (guesses != null) MapTerrain.apply(guesses);
        }, () -> {
        });
    }

    // ------------------------------------------------------------------------------------------------ markers

    private static void sendMarkers(ServerPlayer player) {
        String group = SYNCED.getOrDefault(player.getUUID(), "");
        MapMarkers markers = MapMarkers.get(player.level().getServer());
        send(player, new MapPayloads.Markers(markers.visibleTo(player.getUUID().toString(), group)));
    }

    /** Sends the markers again to everyone who sees this one. */
    private static void markersChanged(MinecraftServer server, MapMarker m) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean sees = player.getUUID().toString().equals(m.owner)
                || (m.shared && groups().same(m.group, SYNCED.getOrDefault(player.getUUID(), "")));
            if (sees) sendMarkers(player);
        }
    }

    /** True if the player may change or remove the marker: their own, or a shared marker of their group. */
    public static boolean mayEdit(MapMarker m, String player, String group) {
        return m.owner.equals(player) || (m.shared && !m.death && groups().same(m.group, group));
    }

    private static void edit(ServerPlayer player, MapPayloads.MarkerEdit payload) {
        if (ignored(player)) return;
        String result = apply(player.level().getServer(), player.getUUID().toString(), groupOf(player), payload.op(), payload.marker());
        if (result != null) player.sendOverlayMessage(Component.translatable(result));
    }

    /**
     * Places, changes or removes a marker for a player of a group, as their client asked. Returns null when done, or the
     * message key saying why not. Also used by the selftest, which plays a second player of the group.
     */
    public static @Nullable String apply(MinecraftServer server, String player, String group, int op, MapMarker in) {
        MapMarkers markers = MapMarkers.get(server);
        in.clamp();
        if (op == MapPayloads.ADD) {
            if (markers.countOf(player) >= MapMarkers.MAX_PER_PLAYER) return "mapmod.map.too_many";
            MapMarker m = new MapMarker();
            m.id = UUID.randomUUID().toString();
            m.dimension = Level.OVERWORLD.identifier().toString();
            m.x = in.x;
            m.y = in.y;
            m.z = in.z;
            m.name = in.name;
            m.icon = in.icon;
            m.colour = in.colour;
            m.shared = in.shared && !group.isEmpty();
            m.owner = player;
            m.group = group;
            m.at = System.currentTimeMillis();
            markers.all().add(m);
            markers.setDirty();
            markersChanged(server, m);
            return null;
        }
        MapMarker m = markers.byId(in.id);
        if (m == null) return "mapmod.map.no_marker";
        if (!mayEdit(m, player, group)) return "mapmod.map.not_yours";
        if (op == MapPayloads.DELETE) {
            markers.all().remove(m);
            markers.setDirty();
            markersChanged(server, m);
            return null;
        }
        // an update: a marker going private or shared is sent to the old and the new viewers alike
        MapMarker before = new MapMarker();
        before.owner = m.owner;
        before.shared = m.shared;
        before.group = m.group;
        m.name = in.name;
        m.icon = in.icon;
        m.colour = in.colour;
        if (!m.death) {
            m.shared = in.shared && !group.isEmpty();
            if (m.shared) m.group = group;
        }
        markers.setDirty();
        markersChanged(server, before);
        markersChanged(server, m);
        return null;
    }

    /** A death marker where the player died (or stands, for the tests); only the last few are kept. */
    public static void deathMarker(ServerPlayer player) {
        deathMarker(player, player.level(), player.blockPosition(), false);
    }

    /** Takes a marker away, for everyone who saw it. */
    public static void removeMarker(MinecraftServer server, String id) {
        if (id == null || id.isEmpty()) return;
        MapMarkers markers = MapMarkers.get(server);
        MapMarker m = markers.byId(id);
        if (m == null) return;
        markers.all().remove(m);
        markers.setDirty();
        markersChanged(server, m);
    }

    /** A death marker; a plain one is one of the last few kept, a kept one stays until it is taken away. Returns its id. */
    public static String deathMarker(ServerPlayer player, ServerLevel level, BlockPos at, boolean kept) {
        MinecraftServer server = level.getServer();
        MapMarkers markers = MapMarkers.get(server);
        String id = player.getUUID().toString();
        List<MapMarker> deaths = new ArrayList<>();
        for (MapMarker m : markers.all()) if (m.death && !m.kept && m.owner.equals(id)) deaths.add(m);
        deaths.sort((a, b) -> Long.compare(a.at, b.at));
        while (!kept && deaths.size() >= MapMarkers.DEATHS_KEPT) markers.all().remove(deaths.remove(0));
        MapMarker m = new MapMarker();
        m.id = UUID.randomUUID().toString();
        m.dimension = level.dimension().identifier().toString();
        m.x = at.getX();
        m.y = at.getY();
        m.z = at.getZ();
        m.name = "";
        m.death = true;
        m.kept = kept;
        m.owner = id;
        m.group = groupOf(player);
        m.at = System.currentTimeMillis();
        markers.all().add(m);
        markers.setDirty();
        sendMarkers(player);
        return m.id;
    }

    // ------------------------------------------------------------------------------------------------ groups

    /** One group folded into another: its fog and shared markers go with it, and its players get the merged map. */
    public static void merge(MinecraftServer server, String from, String into) {
        if (from.equals(into) || from.isEmpty() || into.isEmpty()) return;
        MapFog.fold(from, into);
        MapMarkers markers = MapMarkers.get(server);
        for (MapMarker m : markers.all()) if (m.group.equals(from)) m.group = into;
        markers.setDirty();
        COMPUTED.keySet().removeIf(k -> k.group.equals(from) || k.group.equals(into));
        STAMP.keySet().removeIf(k -> k.group.equals(from) || k.group.equals(into));
        PENDING.keySet().removeIf(k -> k.group.equals(from));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String g = SYNCED.get(player.getUUID());
            if (from.equals(g) || into.equals(g)) sync(player);
        }
    }
}
