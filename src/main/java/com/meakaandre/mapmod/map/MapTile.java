package com.meakaandre.mapmod.map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * One region of the map's terrain, at one pixel for 4 by 4 blocks: for each pixel its ground colour (already in the
 * map's soft palette) and its height. The same packed bytes are kept on the server's disk, sent to clients and kept
 * in their disk cache.
 * <p>
 * A pixel's {@code colour} is 0x00RRGGBB with flags in the top byte: {@link #KNOWN} once anything is recorded,
 * {@link #GUESSED} while it only comes from the world generator (a reveal over land nobody loaded),
 * {@link #WATER} for water, whose height is its surface.
 */
public final class MapTile {
    public static final int KNOWN = 1 << 24, GUESSED = 1 << 25, WATER = 1 << 26;
    /** A water pixel's depth (blocks to the floor, at most {@link #MAX_DEPTH}) is kept in the colour's top 5 bits. */
    private static final int DEPTH_SHIFT = 27;
    public static final int MAX_DEPTH = 31;
    /** 4: water keeps its depth (older tiles are recorded again). */
    private static final int FORMAT = 4;
    /** Starting size of the buffer a tile is packed into (bytes). */
    private static final int PACK_BUFFER = 16384;
    /** Deflate level used to pack a tile. */
    private static final int PACK_LEVEL = 6;
    public static final int AREA = MapGrid.REGION_PX * MapGrid.REGION_PX;

    public final int[] colour = new int[AREA];
    public final short[] height = new short[AREA];
    /** When it last changed (server time in ms); clients ask again with the version they hold. */
    public long version;

    public static int index(int px, int pz) {
        return pz * MapGrid.REGION_PX + px;
    }

    /** The depth bits of a water pixel's colour. */
    public static int withDepth(int depth) {
        return Math.clamp(depth, 0, MAX_DEPTH) << DEPTH_SHIFT;
    }

    /** A water pixel's depth, from its colour. */
    public static int depth(int colour) {
        return colour >>> DEPTH_SHIFT;
    }

    public boolean known(int i) {
        return (colour[i] & KNOWN) != 0;
    }

    public boolean guessed(int i) {
        return (colour[i] & GUESSED) != 0;
    }

    public byte[] pack() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(PACK_BUFFER);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, new Deflater(PACK_LEVEL)))) {
            out.writeByte(FORMAT);
            out.writeLong(version);
            // the colours of a run of pixels are mostly alike, so runs of equal values pack small
            for (int c : colour) out.writeInt(c);
            for (short h : height) out.writeShort(h);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    public static MapTile unpack(byte[] data) throws IOException {
        MapTile tile = new MapTile();
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            int format = in.readByte();
            if (format != FORMAT) throw new IOException("unknown map tile format " + format);
            tile.version = in.readLong();
            for (int i = 0; i < AREA; i++) tile.colour[i] = in.readInt();
            for (int i = 0; i < AREA; i++) tile.height[i] = in.readShort();
        }
        return tile;
    }
}
