package com.meakaandre.mapmod.map;

import com.meakaandre.mapmod.api.MapGlyphs;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;

/**
 * A marker placed on the map, as Satisfactory's stamps and beacons: an icon, a colour and a name, private to the
 * player who placed it or shared with their group. A death marker is placed by the game where its player died and is
 * always private.
 */
public final class MapMarker {
    /** The icons a marker can have (the first glyphs, in this order). */
    public static final int ICONS = MapGlyphs.MARKER_ICONS;
    /** The colours a marker can have. */
    public static final int[] COLOURS = {0xF2A33A, 0xE5533D, 0xF2D64B, 0x6BCB5A, 0x4FB8E8, 0x3F6FE0, 0xB06BE0, 0xF0F0F0};
    public static final int MAX_NAME = 32;
    /** Longest marker id read from the network. */
    private static final int MAX_ID = 64;
    /** Longest dimension id read from the network. */
    private static final int MAX_DIMENSION = 128;
    /** Longest owner (player uuid) read from the network. */
    private static final int MAX_OWNER = 64;
    /** Bytes per character at most in a UTF-8 name. */
    private static final int UTF8_BYTES_PER_CHAR = 4;

    public String id = "";
    public String dimension = "minecraft:overworld";
    public int x, y, z;
    public String name = "";
    public int icon;
    public int colour;
    /** Shared with the group; false for private. */
    public boolean shared;
    /** The player who placed it (uuid). */
    public String owner = "";
    /** The owner's group when placed (for shared markers). */
    public String group = "";
    public boolean death;
    /**
     * A death marker kept until a mod takes it away ({@code MapModApi.removeMarker}), whatever the number of deaths
     * since: one that shows a grave or a death crate. Only the server needs it, so it is not sent.
     */
    public boolean kept;
    /** Real time (ms) it was placed. */
    public long at;

    /** How markers are saved (the world's {@code data/mapmod/markers.dat}). */
    public static final Codec<MapMarker> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.optionalFieldOf("id", "").forGetter(m -> m.id),
        Codec.STRING.optionalFieldOf("dimension", "minecraft:overworld").forGetter(m -> m.dimension),
        Codec.INT.optionalFieldOf("x", 0).forGetter(m -> m.x),
        Codec.INT.optionalFieldOf("y", 0).forGetter(m -> m.y),
        Codec.INT.optionalFieldOf("z", 0).forGetter(m -> m.z),
        Codec.STRING.optionalFieldOf("name", "").forGetter(m -> m.name),
        Codec.INT.optionalFieldOf("icon", 0).forGetter(m -> m.icon),
        Codec.INT.optionalFieldOf("colour", 0).forGetter(m -> m.colour),
        Codec.BOOL.optionalFieldOf("shared", false).forGetter(m -> m.shared),
        Codec.STRING.optionalFieldOf("owner", "").forGetter(m -> m.owner),
        Codec.STRING.optionalFieldOf("group", "").forGetter(m -> m.group),
        Codec.BOOL.optionalFieldOf("death", false).forGetter(m -> m.death),
        Codec.BOOL.optionalFieldOf("kept", false).forGetter(m -> m.kept),
        Codec.LONG.optionalFieldOf("at", 0L).forGetter(m -> m.at)
    ).apply(i, MapMarker::of));

    private static MapMarker of(String id, String dimension, int x, int y, int z, String name, int icon, int colour, boolean shared,
                                String owner, String group, boolean death, boolean kept, long at) {
        MapMarker m = new MapMarker();
        m.id = id;
        m.dimension = dimension;
        m.x = x;
        m.y = y;
        m.z = z;
        m.name = name;
        m.icon = icon;
        m.colour = colour;
        m.shared = shared;
        m.owner = owner;
        m.group = group;
        m.death = death;
        m.kept = kept;
        m.at = at;
        return m;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(dimension);
        buf.writeVarInt(x);
        buf.writeVarInt(y);
        buf.writeVarInt(z);
        buf.writeUtf(name);
        buf.writeVarInt(icon);
        buf.writeVarInt(colour);
        buf.writeBoolean(shared);
        buf.writeUtf(owner);
        buf.writeBoolean(death);
        buf.writeLong(at);
    }

    public static MapMarker read(FriendlyByteBuf buf) {
        MapMarker m = new MapMarker();
        m.id = buf.readUtf(MAX_ID);
        m.dimension = buf.readUtf(MAX_DIMENSION);
        m.x = buf.readVarInt();
        m.y = buf.readVarInt();
        m.z = buf.readVarInt();
        m.name = buf.readUtf(MAX_NAME * UTF8_BYTES_PER_CHAR);
        m.icon = buf.readVarInt();
        m.colour = buf.readVarInt();
        m.shared = buf.readBoolean();
        m.owner = buf.readUtf(MAX_OWNER);
        m.death = buf.readBoolean();
        m.at = buf.readLong();
        return m;
    }

    public String pinKey() {
        return "marker:" + id;
    }

    /** Keeps icon, colour and name inside what the map can show. */
    public void clamp() {
        icon = Math.floorMod(icon, ICONS);
        colour = Math.floorMod(colour, COLOURS.length);
        name = name.strip();
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
    }
}
