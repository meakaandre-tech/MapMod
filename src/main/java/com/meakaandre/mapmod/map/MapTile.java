package com.meakaandre.mapmod.map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * One region of the map's terrain, a pixel a block: for each pixel which block is on top, its biome, its height, and
 * for water how deep it is (the block then being the floor's). Blocks and biomes are kept by name in the tile's own
 * palettes, so the client colours them itself (from the block's texture, tinted by its biome) and any mod's blocks and
 * biomes work; with each block name goes its map colour, for a client that does not know the block. The same packed
 * bytes are kept on the server's disk, sent to clients and kept in their disk cache.
 * <p>
 * A pixel's {@code cell} holds the block's palette index (bits 0-11), the biome's (bits 12-19) and flags: {@link #KNOWN}
 * once anything is recorded, {@link #GUESSED} while it only comes from the world generator (a reveal over land nobody
 * loaded), {@link #WATER} for water, whose height is its surface, and its depth in the top 5 bits.
 */
public final class MapTile {
    public static final int KNOWN = 1 << 24, GUESSED = 1 << 25, WATER = 1 << 26;
    private static final int BLOCK_MASK = 0xFFF, BIOME_SHIFT = 12, BIOME_MASK = 0xFF;
    /** A water pixel's depth (blocks to the floor, at most {@link #MAX_DEPTH}) is kept in the cell's top 5 bits. */
    private static final int DEPTH_SHIFT = 27;
    public static final int MAX_DEPTH = 31;
    /** 7: a pixel a block, blocks and biomes by name (older tiles are recorded again). */
    private static final int FORMAT = 7;
    /** Starting size of the buffer a tile is packed into (bytes). */
    private static final int PACK_BUFFER = 65536;
    /** Deflate level used to pack a tile. */
    private static final int PACK_LEVEL = 6;
    public static final int AREA = MapGrid.REGION_PX * MapGrid.REGION_PX;

    public final int[] cell = new int[AREA];
    public final short[] height = new short[AREA];
    /** Block names (an empty name: a generator guess, shown in its map colour only) and their map colours. */
    public final List<String> blocks = new ArrayList<>();
    public final List<Integer> blockColours = new ArrayList<>();
    public final List<String> biomes = new ArrayList<>();
    private final Map<String, Integer> blockIndex = new HashMap<>(), biomeIndex = new HashMap<>();
    /** When it last changed (server time in ms); clients ask again with the version they hold. */
    public long version;

    public static int index(int px, int pz) {
        return pz * MapGrid.REGION_PX + px;
    }

    public boolean known(int i) {
        return (cell[i] & KNOWN) != 0;
    }

    public boolean guessed(int i) {
        return (cell[i] & GUESSED) != 0;
    }

    public static boolean water(int cell) {
        return (cell & WATER) != 0;
    }

    public static int blockOf(int cell) {
        return cell & BLOCK_MASK;
    }

    public static int biomeOf(int cell) {
        return cell >>> BIOME_SHIFT & BIOME_MASK;
    }

    public static int depth(int cell) {
        return cell >>> DEPTH_SHIFT;
    }

    /** A cell: a block and a biome of this tile's palettes, the flags, and a water depth (0 for land). */
    public static int cell(int block, int biome, int flags, int depth) {
        return (block & BLOCK_MASK) | (biome & BIOME_MASK) << BIOME_SHIFT | flags | Math.clamp(depth, 0, MAX_DEPTH) << DEPTH_SHIFT;
    }

    /** The palette index of a block (added if new); {@code colour} is its map colour. */
    public int block(String name, int colour) {
        String key = name.isEmpty() ? "#" + Integer.toHexString(colour) : name;
        Integer i = blockIndex.get(key);
        if (i != null) return i;
        // a full palette: the first entry stands in (never in practice: a region has far fewer kinds of block)
        if (blocks.size() > BLOCK_MASK) return 0;
        blocks.add(name);
        blockColours.add(colour);
        blockIndex.put(key, blocks.size() - 1);
        return blocks.size() - 1;
    }

    /** The palette index of a biome (added if new). */
    public int biome(String name) {
        Integer i = biomeIndex.get(name);
        if (i != null) return i;
        if (biomes.size() > BIOME_MASK) return 0;
        biomes.add(name);
        biomeIndex.put(name, biomes.size() - 1);
        return biomes.size() - 1;
    }

    public byte[] pack() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(PACK_BUFFER);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, new Deflater(PACK_LEVEL)))) {
            out.writeByte(FORMAT);
            out.writeLong(version);
            out.writeShort(blocks.size());
            for (int i = 0; i < blocks.size(); i++) {
                out.writeUTF(blocks.get(i));
                out.writeInt(blockColours.get(i));
            }
            out.writeShort(biomes.size());
            for (String b : biomes) out.writeUTF(b);
            // a byte of every pixel at a time: neighbouring pixels are mostly alike, so these pack small
            byte[] plane = new byte[AREA];
            for (int shift = 0; shift < 32; shift += 8) {
                for (int i = 0; i < AREA; i++) plane[i] = (byte) (cell[i] >>> shift);
                out.write(plane);
            }
            for (int shift = 0; shift < 16; shift += 8) {
                for (int i = 0; i < AREA; i++) plane[i] = (byte) (height[i] >>> shift);
                out.write(plane);
            }
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
            int blocks = in.readUnsignedShort();
            for (int i = 0; i < blocks; i++) {
                String name = in.readUTF();
                tile.block(name, in.readInt());
            }
            int biomes = in.readUnsignedShort();
            for (int i = 0; i < biomes; i++) tile.biome(in.readUTF());
            byte[] plane = new byte[AREA];
            for (int shift = 0; shift < 32; shift += 8) {
                in.readFully(plane);
                for (int i = 0; i < AREA; i++) tile.cell[i] |= (plane[i] & 0xFF) << shift;
            }
            for (int shift = 0; shift < 16; shift += 8) {
                in.readFully(plane);
                for (int i = 0; i < AREA; i++) tile.height[i] |= (short) ((plane[i] & 0xFF) << shift);
            }
        }
        return tile;
    }
}
