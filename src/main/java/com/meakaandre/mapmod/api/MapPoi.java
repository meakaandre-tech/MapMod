package com.meakaandre.mapmod.api;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Something the map shows that no player placed: a resource, a structure, a building. Providers make them; the map
 * keeps, filters and draws them.
 *
 * @param kind   the {@link PoiKind}'s id (its {@code toString()})
 * @param sub    the sub-type's id within the kind, or empty
 * @param purity 0, 1 or 2 for kinds that show a purity ring (impure, normal, pure); 0 otherwise
 */
public record MapPoi(String kind, String sub, int purity, int x, int y, int z) {
    /** Longest kind or sub-type id read from the network. */
    private static final int MAX_ID = 128;

    public static MapPoi of(PoiKind kind, String sub, int purity, int x, int y, int z) {
        return new MapPoi(kind.key(), sub, purity, x, y, z);
    }

    public static MapPoi of(PoiKind kind, int x, int y, int z) {
        return of(kind, "", 0, x, y, z);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(kind);
        buf.writeUtf(sub);
        buf.writeByte(purity);
        buf.writeVarInt(x);
        buf.writeVarInt(y);
        buf.writeVarInt(z);
    }

    public static MapPoi read(FriendlyByteBuf buf) {
        return new MapPoi(buf.readUtf(MAX_ID), buf.readUtf(MAX_ID), buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    /** What identifies it for pins: the same thing at the same place. */
    public String pinKey() {
        return "poi:" + kind + ":" + sub + ":" + x + ":" + z;
    }
}
