package com.meakaandre.mapmod.map;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapPoi;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** What the map's server and clients tell each other. */
public final class MapPayloads {
    /** Longest map id read from the network. */
    private static final int MAX_MAP_ID = 64;
    /** Largest packed tile read from the network (bytes). */
    private static final int MAX_TILE_BYTES = 1 << 20;
    /** Longest player name read from the network. */
    private static final int MAX_PLAYER_NAME = 32;
    /** Longest kind id or translation key read from the network. */
    private static final int MAX_KEY = 128;

    private MapPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String id) {
        return new CustomPacketPayload.Type<>(MapMod.id(id));
    }

    /**
     * Server to client on joining: which map this is, so the client's disk cache keeps each world's tiles apart, and the
     * group provider's word for a shared marker.
     */
    public record Hello(String mapId, String sharedKey) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = typeOf("hello");
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeUtf(p.mapId);
            buf.writeUtf(p.sharedKey);
        }, buf -> new Hello(buf.readUtf(MAX_MAP_ID), buf.readUtf(MAX_KEY)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: what the player has unlocked ({@code MapFeature} bits) and what to say about the rest. */
    public record Unlocks(int mask, String lockedKey) implements CustomPacketPayload {
        public static final Type<Unlocks> TYPE = typeOf("unlocks");
        public static final StreamCodec<RegistryFriendlyByteBuf, Unlocks> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.mask);
            buf.writeUtf(p.lockedKey);
        }, buf -> new Unlocks(buf.readVarInt(), buf.readUtf(MAX_KEY)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One region's fog bits. */
    public record FogRegion(long region, long[] bits) {
    }

    /** Server to client: the group's fog for whole regions; {@code replace} drops all fog the client held first. */
    public record Fog(boolean replace, List<FogRegion> regions) implements CustomPacketPayload {
        public static final Type<Fog> TYPE = typeOf("fog");
        public static final StreamCodec<RegistryFriendlyByteBuf, Fog> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeBoolean(p.replace);
            buf.writeVarInt(p.regions.size());
            for (FogRegion r : p.regions) {
                buf.writeLong(r.region);
                for (long l : r.bits) buf.writeLong(l);
            }
        }, buf -> {
            boolean replace = buf.readBoolean();
            int n = buf.readVarInt();
            List<FogRegion> regions = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long region = buf.readLong();
                long[] bits = new long[MapGrid.FOG_LONGS];
                for (int j = 0; j < bits.length; j++) bits[j] = buf.readLong();
                regions.add(new FogRegion(region, bits));
            }
            return new Fog(replace, regions);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: chunks someone of the group has just explored. */
    public record FogDelta(long[] chunks) implements CustomPacketPayload {
        public static final Type<FogDelta> TYPE = typeOf("fog_delta");
        public static final StreamCodec<RegistryFriendlyByteBuf, FogDelta> CODEC = StreamCodec.of((buf, p) -> buf.writeLongArray(p.chunks),
            buf -> new FogDelta(buf.readLongArray()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** What the map shows in one region. */
    public record RegionPois(long region, List<MapPoi> pois) {
    }

    /** Server to client: the points of interest of some regions, each replacing what the client held for it. */
    public record Pois(List<RegionPois> regions) implements CustomPacketPayload {
        public static final Type<Pois> TYPE = typeOf("pois");
        public static final StreamCodec<RegistryFriendlyByteBuf, Pois> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.regions.size());
            for (RegionPois r : p.regions) {
                buf.writeLong(r.region);
                buf.writeVarInt(r.pois.size());
                for (MapPoi poi : r.pois) poi.write(buf);
            }
        }, buf -> {
            int n = buf.readVarInt();
            List<RegionPois> regions = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long region = buf.readLong();
                int m = buf.readVarInt();
                List<MapPoi> pois = new ArrayList<>(m);
                for (int j = 0; j < m; j++) pois.add(MapPoi.read(buf));
                regions.add(new RegionPois(region, pois));
            }
            return new Pois(regions);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: a region's terrain, packed as {@link MapTile#pack()} (its version is inside). */
    public record Tile(long region, byte[] data) implements CustomPacketPayload {
        public static final Type<Tile> TYPE = typeOf("tile");
        public static final StreamCodec<RegistryFriendlyByteBuf, Tile> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeLong(p.region);
            buf.writeByteArray(p.data);
        }, buf -> new Tile(buf.readLong(), buf.readByteArray(MAX_TILE_BYTES)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: regions the client wants, each with the version it already holds (0 for none). */
    public record TileRequest(long[] regions, long[] versions) implements CustomPacketPayload {
        public static final Type<TileRequest> TYPE = typeOf("tile_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, TileRequest> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeLongArray(p.regions);
            buf.writeLongArray(p.versions);
        }, buf -> new TileRequest(buf.readLongArray(), buf.readLongArray()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: every marker the player sees. */
    public record Markers(List<MapMarker> markers) implements CustomPacketPayload {
        public static final Type<Markers> TYPE = typeOf("markers");
        public static final StreamCodec<RegistryFriendlyByteBuf, Markers> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.markers.size());
            for (MapMarker m : p.markers) m.write(buf);
        }, buf -> {
            int n = buf.readVarInt();
            List<MapMarker> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(MapMarker.read(buf));
            return new Markers(list);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static final int ADD = 0, UPDATE = 1, DELETE = 2;

    /** Client to server: place, change or remove a marker. The server checks who may. */
    public record MarkerEdit(int op, MapMarker marker) implements CustomPacketPayload {
        public static final Type<MarkerEdit> TYPE = typeOf("marker_edit");
        public static final StreamCodec<RegistryFriendlyByteBuf, MarkerEdit> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.op);
            p.marker.write(buf);
        }, buf -> new MarkerEdit(buf.readVarInt(), MapMarker.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Another player of the group online, as the compass and the map show them. */
    public record Mate(String name, double x, double y, double z, float yaw) {
        void write(FriendlyByteBuf buf) {
            buf.writeUtf(name);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeFloat(yaw);
        }

        static Mate read(FriendlyByteBuf buf) {
            return new Mate(buf.readUtf(MAX_PLAYER_NAME), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat());
        }
    }

    /** Server to client, twice a second: the group's other players in the player's dimension. */
    public record Mates(List<Mate> mates) implements CustomPacketPayload {
        public static final Type<Mates> TYPE = typeOf("mates");
        public static final StreamCodec<RegistryFriendlyByteBuf, Mates> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.mates.size());
            for (Mate m : p.mates) m.write(buf);
        }, buf -> {
            int n = buf.readVarInt();
            List<Mate> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(Mate.read(buf));
            return new Mates(list);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client, once a second: the moving things the player's map shows (see {@code MovingSource}). */
    public record Moving(List<com.meakaandre.mapmod.api.MovingPoi> moving) implements CustomPacketPayload {
        public static final Type<Moving> TYPE = typeOf("moving");
        public static final StreamCodec<RegistryFriendlyByteBuf, Moving> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.moving.size());
            for (var m : p.moving) {
                buf.writeUtf(m.kind());
                buf.writeDouble(m.x());
                buf.writeDouble(m.z());
                buf.writeFloat(m.yaw());
                buf.writeBoolean(m.dimmed());
                buf.writeUtf(m.note());
            }
        }, buf -> {
            int n = buf.readVarInt();
            List<com.meakaandre.mapmod.api.MovingPoi> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(new com.meakaandre.mapmod.api.MovingPoi(buf.readUtf(MAX_KEY), buf.readDouble(), buf.readDouble(), buf.readFloat(),
                    buf.readBoolean(), buf.readUtf(MAX_KEY)));
            }
            return new Moving(list);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
