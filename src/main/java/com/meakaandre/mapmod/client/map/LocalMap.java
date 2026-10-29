package com.meakaandre.mapmod.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapMarker;
import com.meakaandre.mapmod.map.MapMarkers;
import com.meakaandre.mapmod.map.MapPayloads;
import com.meakaandre.mapmod.map.MapRecorder;
import com.meakaandre.mapmod.map.MapServer;
import com.meakaandre.mapmod.map.MapTile;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * The map on a server that has no MapMod: the client keeps it by itself. It records the chunks it has loaded into its
 * tiles, clears its own fog round the player, keeps its own markers and death markers, and opens the whole map (there
 * are no unlocks to wait for, and no POIs, mates or moving things, which only a server can supply). Everything is kept
 * per server in the game folder ({@code mapmod/server-<address>/}).
 */
public final class LocalMap {
    /** Ticks after joining before a server that has not shown MapMod is taken as one without it. */
    private static final int GRACE_TICKS = 40;
    /** Chunks recorded per tick at most. */
    private static final int PER_TICK = 6;
    /** Ticks between looking for loaded chunks to record, and between fog updates. */
    private static final int LOOK_TICKS = 10, FOG_TICKS = 20;
    /** A chunk is recorded again after this long (ticks); near the player sooner, so building shows. */
    private static final int AGAIN_TICKS = 1200, AGAIN_NEAR_TICKS = 100;
    /** Chunks round the player that count as near, and the farthest it looks. */
    private static final int NEAR = 2, FARTHEST = 16;
    /** Ticks between saves (half a minute). */
    private static final int SAVE_TICKS = 600;
    /** Fog file format. */
    private static final int FOG_FORMAT = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean active;
    private static int joinedTicks = -1;
    private static int ticks;
    private static boolean wasDead;
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    /** When each chunk was last recorded (ticks since this map started). */
    private static final Long2LongOpenHashMap RECORDED = new Long2LongOpenHashMap();
    private static final List<MapMarker> MARKERS = new ArrayList<>();

    private LocalMap() {
    }

    /** True while the client keeps the map by itself. */
    public static boolean active() {
        return active;
    }

    /** How long (ticks) the client has kept its own map this time; 0 when it does not. */
    public static int ticks() {
        return active ? ticks : 0;
    }

    static void joined() {
        joinedTicks = 0;
    }

    private static boolean serverHasMap() {
        return ClientPlayNetworking.canSend(MapPayloads.TileRequest.TYPE) || MapClient.mapId != null && !MapClient.mapId.startsWith("server-");
    }

    static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return;
        if (!active) {
            if (joinedTicks < 0 || serverHasMap()) return;
            if (++joinedTicks < GRACE_TICKS) return;
            start(mc);
        }
        ticks++;
        // a death leaves a death marker, as on a server with the map
        boolean dead = player.isDeadOrDying();
        if (dead && !wasDead && MapClient.mappedHere()) death(player);
        wasDead = dead;
        if (MapClient.mappedHere()) {
            if (ticks % FOG_TICKS == 0) fog(player);
            if (ticks % LOOK_TICKS == 0) look(mc, player);
            for (int n = 0; n < PER_TICK && !QUEUE.isEmpty(); n++) {
                long key = QUEUE.removeFirstLong();
                int cx = MapGrid.keyX(key), cz = MapGrid.keyZ(key);
                if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                long region = MapGrid.regionOfChunk(cx, cz);
                MapTile tile = ClientTiles.editable(region);
                if (tile == null) {
                    // its disk copy is still being read: later
                    QUEUE.add(key);
                    break;
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                if (MapRecorder.record(level, chunk, tile, false)) ClientTiles.edited(region, chunk.getPos().x(), chunk.getPos().z());
                RECORDED.put(key, ticks);
            }
        }
        if (ticks % SAVE_TICKS == 0) save();
    }

    /** The server has no MapMod: the client's own map for it, from its folder. */
    private static void start(Minecraft mc) {
        active = true;
        ticks = 0;
        wasDead = false;
        QUEUE.clear();
        RECORDED.clear();
        ServerData server = mc.getCurrentServer();
        String address = server == null ? "unknown" : server.ip;
        String id = "server-" + address.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        MapClient.hello(id, "mapmod.map.shared");
        MapClient.FOG.clear();
        loadFog();
        loadMarkers();
        MapClient.markers(List.copyOf(MARKERS));
        MapMod.LOGGER.info("the server has no {}: this client keeps its own map ({})", MapMod.NAME, id);
    }

    /** Leaving the server: what was kept is written out. */
    static void stop() {
        if (active) save();
        active = false;
        joinedTicks = -1;
        QUEUE.clear();
        RECORDED.clear();
        MARKERS.clear();
    }

    /** Clears the fog round the player, as a server would for its group. */
    private static void fog(LocalPlayer player) {
        int ccx = player.blockPosition().getX() >> 4, ccz = player.blockPosition().getZ() >> 4;
        int r = MapServer.VISIT_RADIUS, r2 = r * r;
        LongArrayList fresh = new LongArrayList();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r2) continue;
                int cx = ccx + dx, cz = ccz + dz;
                long[] bits = MapClient.FOG.get(MapGrid.regionOfChunk(cx, cz));
                if (bits == null || !MapGrid.get(bits, MapGrid.fogBit(cx, cz))) fresh.add(MapGrid.key(cx, cz));
            }
        }
        if (!fresh.isEmpty()) MapClient.fogDelta(fresh.toLongArray());
    }

    /** Queues the loaded chunks round the player that are due to be recorded. */
    private static void look(Minecraft mc, LocalPlayer player) {
        ClientLevel level = mc.level;
        if (level == null) return;
        int r = Math.min(FARTHEST, mc.options.getEffectiveRenderDistance());
        int ccx = player.blockPosition().getX() >> 4, ccz = player.blockPosition().getZ() >> 4;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int cx = ccx + dx, cz = ccz + dz;
                if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                long key = MapGrid.key(cx, cz);
                long last = RECORDED.getOrDefault(key, Long.MIN_VALUE);
                boolean near = Math.abs(dx) <= NEAR && Math.abs(dz) <= NEAR;
                if (last == Long.MIN_VALUE || ticks - last >= (near ? AGAIN_NEAR_TICKS : AGAIN_TICKS)) QUEUE.add(key);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ markers

    private static String me() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "" : mc.player.getUUID().toString();
    }

    private static @Nullable MapMarker byId(String id) {
        for (MapMarker m : MARKERS) if (m.id.equals(id)) return m;
        return null;
    }

    /** Places, changes or removes one of the client's own markers, as the server would. */
    public static void apply(int op, MapMarker in) {
        in.clamp();
        if (op == MapPayloads.ADD) {
            if (MARKERS.stream().filter(m -> !m.death).count() >= MapMarkers.MAX_PER_PLAYER) return;
            MapMarker m = new MapMarker();
            m.id = UUID.randomUUID().toString();
            m.x = in.x;
            m.y = in.y;
            m.z = in.z;
            m.name = in.name;
            m.icon = in.icon;
            m.colour = in.colour;
            m.owner = me();
            m.at = System.currentTimeMillis();
            MARKERS.add(m);
        } else {
            MapMarker m = byId(in.id);
            if (m == null) return;
            if (op == MapPayloads.DELETE) {
                MARKERS.remove(m);
            } else {
                m.name = in.name;
                m.icon = in.icon;
                m.colour = in.colour;
            }
        }
        changed();
    }

    private static void death(LocalPlayer player) {
        List<MapMarker> deaths = new ArrayList<>();
        for (MapMarker m : MARKERS) if (m.death) deaths.add(m);
        deaths.sort((a, b) -> Long.compare(a.at, b.at));
        while (deaths.size() >= MapMarkers.DEATHS_KEPT) MARKERS.remove(deaths.remove(0));
        MapMarker m = new MapMarker();
        m.id = UUID.randomUUID().toString();
        m.x = player.getBlockX();
        m.y = player.getBlockY();
        m.z = player.getBlockZ();
        m.death = true;
        m.owner = me();
        m.at = System.currentTimeMillis();
        MARKERS.add(m);
        changed();
    }

    private static void changed() {
        MapClient.markers(List.copyOf(MARKERS));
        saveMarkers();
    }

    // ------------------------------------------------------------------------------------------------ saving

    private static @Nullable Path dir() {
        String id = MapClient.mapId;
        return id == null ? null : Minecraft.getInstance().gameDirectory.toPath().resolve(MapMod.ID).resolve(id);
    }

    /** Writes the tiles, the fog and the markers out. */
    public static void save() {
        if (!active) return;
        ClientTiles.saveDirty();
        saveFog();
        saveMarkers();
    }

    private static void saveFog() {
        Path dir = dir();
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
                out.writeByte(FOG_FORMAT);
                out.writeInt(MapClient.FOG.size());
                for (var e : MapClient.FOG.long2ObjectEntrySet()) {
                    out.writeLong(e.getLongKey());
                    for (long l : e.getValue()) out.writeLong(l);
                }
            }
            Files.write(dir.resolve("fog.bin"), bytes.toByteArray());
        } catch (IOException e) {
            MapMod.LOGGER.warn("could not save the map's fog", e);
        }
    }

    private static void loadFog() {
        Path dir = dir();
        if (dir == null || !Files.exists(dir.resolve("fog.bin"))) return;
        Long2ObjectOpenHashMap<long[]> fog = new Long2ObjectOpenHashMap<>();
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(Files.newInputStream(dir.resolve("fog.bin"))))) {
            if (in.readByte() != FOG_FORMAT) throw new IOException("unknown fog format");
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long region = in.readLong();
                long[] bits = new long[MapGrid.FOG_LONGS];
                for (int j = 0; j < bits.length; j++) bits[j] = in.readLong();
                fog.put(region, bits);
            }
        } catch (IOException | RuntimeException e) {
            MapMod.LOGGER.warn("unreadable map fog in {}", dir, e);
            return;
        }
        MapClient.FOG.putAll(fog);
        for (long region : fog.keySet()) ClientTiles.fogChanged(region);
    }

    private static void saveMarkers() {
        Path dir = dir();
        if (dir == null) return;
        JsonArray array = new JsonArray();
        for (MapMarker m : MARKERS) {
            JsonObject o = new JsonObject();
            o.addProperty("id", m.id);
            o.addProperty("x", m.x);
            o.addProperty("y", m.y);
            o.addProperty("z", m.z);
            o.addProperty("name", m.name);
            o.addProperty("icon", m.icon);
            o.addProperty("colour", m.colour);
            o.addProperty("death", m.death);
            o.addProperty("at", m.at);
            array.add(o);
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("markers.json"), GSON.toJson(array));
        } catch (IOException e) {
            MapMod.LOGGER.warn("could not save the map's markers", e);
        }
    }

    private static void loadMarkers() {
        MARKERS.clear();
        Path dir = dir();
        if (dir == null || !Files.exists(dir.resolve("markers.json"))) return;
        try {
            for (JsonElement e : JsonParser.parseString(Files.readString(dir.resolve("markers.json"))).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                MapMarker m = new MapMarker();
                m.id = o.get("id").getAsString();
                m.x = o.get("x").getAsInt();
                m.y = o.get("y").getAsInt();
                m.z = o.get("z").getAsInt();
                m.name = o.get("name").getAsString();
                m.icon = o.get("icon").getAsInt();
                m.colour = o.get("colour").getAsInt();
                m.death = o.get("death").getAsBoolean();
                m.at = o.get("at").getAsLong();
                m.owner = me();
                m.clamp();
                MARKERS.add(m);
            }
        } catch (IOException | RuntimeException e) {
            MapMod.LOGGER.warn("unreadable map markers in {}", dir, e);
        }
    }
}
