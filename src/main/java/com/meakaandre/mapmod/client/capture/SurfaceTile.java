package com.meakaandre.mapmod.client.capture;

import com.meakaandre.mapmod.MapMod;
import io.airlift.compress.v3.zstd.ZstdCompressor;
import io.airlift.compress.v3.zstd.ZstdDecompressor;
import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import io.airlift.compress.v3.zstd.ZstdJavaDecompressor;
import io.airlift.compress.v3.zstd.ZstdNativeCompressor;
import io.airlift.compress.v3.zstd.ZstdNativeDecompressor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * One tile of the captured surface: 512x512 blocks as 256x256 samples, one per 2x2 block cell (MM-8; 4x4 before), each
 * with a height, a water depth, a surface class ({@link SurfaceClass}), the block colour seen from above (RGB), a biome
 * tint (RGB565) and a canopy density, and a bit saying whether it has been explored (sampled) at all.
 * <p>
 * File layout ({@code .mmt}), little-endian:
 * <pre>
 *   header, uncompressed:  u32 magic "MMT1" · u16 format version · u8 sample spacing (blocks) · u16 samples per side
 *                          · u32 body length
 *   body, one zstd frame:  height plane   i16 x 65536   top motion-blocking non-leaf block (water floor under water)
 *                          water plane    u8  x 65536   water depth in blocks (0 on land)
 *                          class plane    u8  x 65536   {@link SurfaceClass} 0-7: only for effects since MM-8 (water,
 *                                                       forest, snow) and for a palette's class colour overrides
 *                          tint plane     u16 x 65536   biome grass colour (foliage colour on forest floor), RGB565
 *                          canopy plane   u8  x 65536   share of the cell's columns under leaves or logs, 0-255
 *                          colour planes  u8  x 65536 x 3 (red, then green, then blue): the cell's colour from above,
 *                                                       {@link BlockColours}: the top face's average texture colour
 *                                                       times its tint (MM-8)
 *                          explored mask  u64 x 1024    bit i = sample i has been sampled
 * </pre>
 * Sample i is at (i % 256, i / 256) in the tile: x then z, so planes upload as textures row by row. Format 1 (MM-1 to
 * MM-7: 4-block samples, no colour) is not read: such tiles are dropped and the land captured again.
 */
public final class SurfaceTile {
    public static final int MAGIC = 0x31544D4D; // "MMT1" read little-endian
    public static final int FORMAT = 2;
    /** Blocks per sample along each axis. */
    public static final int SPACING = 2;
    /** Samples per tile side, and the tile's size in blocks and chunks. */
    public static final int SIDE = 256, BLOCKS = SIDE * SPACING, CHUNKS = BLOCKS / 16;
    /** Samples along a chunk. */
    public static final int PER_CHUNK = 16 / SPACING;
    public static final int AREA = SIDE * SIDE;
    private static final int HEADER = 4 + 2 + 1 + 2 + 4;
    private static final int BODY = AREA * 2 + AREA + AREA + AREA * 2 + AREA + AREA * 3 + AREA / 8;

    public final int tileX, tileZ;
    public final short[] height = new short[AREA];
    public final byte[] water = new byte[AREA], surface = new byte[AREA], canopy = new byte[AREA];
    public final short[] tint = new short[AREA];
    /** The colour from above, 0xRRGGBB (MM-8). */
    public final int[] colour = new int[AREA];
    public final long[] explored = new long[AREA / 64];
    /**
     * When the data last changed, in milliseconds since the epoch, and never smaller than before: a renderer can tell a
     * copy it holds is out of date, and of two copies the larger revision is the newer. A tile read from disk takes its
     * file's modification time, so the same file read twice has the same revision.
     */
    public long revision;
    boolean dirty;

    public SurfaceTile(int tileX, int tileZ) {
        this.tileX = tileX;
        this.tileZ = tileZ;
    }

    public static int index(int sx, int sz) {
        return sx + sz * SIDE;
    }

    public boolean explored(int i) {
        return (explored[i >>> 6] & 1L << i) != 0;
    }

    public int exploredCount() {
        int n = 0;
        for (long l : explored) n += Long.bitCount(l);
        return n;
    }

    /** The sample index of a block position inside this tile. */
    public static int indexOfBlock(int x, int z) {
        return index(Math.floorMod(x, BLOCKS) / SPACING, Math.floorMod(z, BLOCKS) / SPACING);
    }

    public static int tileOfBlock(int b) {
        return Math.floorDiv(b, BLOCKS);
    }

    public static int tileOfChunk(int c) {
        return Math.floorDiv(c, CHUNKS);
    }

    /** Writes a chunk's samples in. Returns true if anything changed. */
    boolean put(SurfaceSampler.Samples s) {
        int baseX = Math.floorMod(s.cx, CHUNKS) * PER_CHUNK, baseZ = Math.floorMod(s.cz, CHUNKS) * PER_CHUNK;
        boolean changed = false;
        for (int sz = 0; sz < PER_CHUNK; sz++) {
            for (int sx = 0; sx < PER_CHUNK; sx++) {
                int n = sx + sz * PER_CHUNK, i = index(baseX + sx, baseZ + sz);
                if (!explored(i) || height[i] != s.height[n] || water[i] != s.water[n] || surface[i] != s.surface[n]
                    || tint[i] != s.tint[n] || canopy[i] != s.canopy[n] || colour[i] != s.colour[n]) {
                    height[i] = s.height[n];
                    water[i] = s.water[n];
                    surface[i] = s.surface[n];
                    tint[i] = s.tint[n];
                    canopy[i] = s.canopy[n];
                    colour[i] = s.colour[n];
                    explored[i >>> 6] |= 1L << i;
                    changed = true;
                }
            }
        }
        if (changed) {
            revision = Math.max(revision + 1, System.currentTimeMillis());
            dirty = true;
        }
        return changed;
    }

    /**
     * Which zstd codec packs and reads tiles on this machine: "native" where aircompressor carries the zstd library for
     * the platform (Linux x64, arm64 and ppc64le, macOS arm64; called through java.lang.foreign, no sun.misc.Unsafe), else
     * "java", its pure-Java codec (which still reads memory through sun.misc.Unsafe). Both read and write the same frames.
     */
    public static String codec() {
        return NATIVE ? "native" : "java";
    }

    private static final boolean NATIVE = nativeZstd();

    /** True if the native zstd loads here; anything else (no library for the platform, native access refused) is Java. */
    private static boolean nativeZstd() {
        try {
            return ZstdNativeCompressor.isEnabled() && ZstdNativeDecompressor.isEnabled();
        } catch (Throwable e) {
            MapMod.LOGGER.warn("the native zstd could not be loaded, map tiles use the pure-Java one: {}", e.toString());
            return false;
        }
    }

    private static ZstdCompressor compressor() {
        return NATIVE ? new ZstdNativeCompressor() : new ZstdJavaCompressor();
    }

    private static ZstdDecompressor decompressor() {
        return NATIVE ? new ZstdNativeDecompressor() : new ZstdJavaDecompressor();
    }

    public SurfaceTile copy() {
        SurfaceTile t = new SurfaceTile(tileX, tileZ);
        System.arraycopy(height, 0, t.height, 0, AREA);
        System.arraycopy(water, 0, t.water, 0, AREA);
        System.arraycopy(surface, 0, t.surface, 0, AREA);
        System.arraycopy(tint, 0, t.tint, 0, AREA);
        System.arraycopy(canopy, 0, t.canopy, 0, AREA);
        System.arraycopy(colour, 0, t.colour, 0, AREA);
        System.arraycopy(explored, 0, t.explored, 0, explored.length);
        t.revision = revision;
        return t;
    }

    public boolean sameData(SurfaceTile o) {
        return Arrays.equals(height, o.height) && Arrays.equals(water, o.water) && Arrays.equals(surface, o.surface)
            && Arrays.equals(tint, o.tint) && Arrays.equals(canopy, o.canopy) && Arrays.equals(colour, o.colour)
            && Arrays.equals(explored, o.explored);
    }

    public byte[] pack() {
        ByteBuffer body = ByteBuffer.allocate(BODY).order(ByteOrder.LITTLE_ENDIAN);
        for (short h : height) body.putShort(h);
        body.put(water).put(surface);
        for (short t : tint) body.putShort(t);
        body.put(canopy);
        for (int shift = 16; shift >= 0; shift -= 8) for (int c : colour) body.put((byte) (c >> shift));
        for (long l : explored) body.putLong(l);
        ZstdCompressor zstd = compressor();
        byte[] packed = new byte[zstd.maxCompressedLength(BODY)];
        int n = zstd.compress(body.array(), 0, BODY, packed, 0, packed.length);
        ByteBuffer out = ByteBuffer.allocate(HEADER + n).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MAGIC).putShort((short) FORMAT).put((byte) SPACING).putShort((short) SIDE).putInt(BODY);
        out.put(packed, 0, n);
        return out.array();
    }

    /** A stored biome tint (RGB565) as 0xRRGGBB. */
    public static int tintRgb(short tint) {
        return SurfaceSampler.rgb888(tint);
    }

    /** True if a file's first bytes (magic, format, spacing) are this release's tile format. */
    static boolean currentFormat(byte[] head) {
        if (head.length < 7) return false;
        ByteBuffer in = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        return in.getInt() == MAGIC && (in.getShort() & 0xFFFF) == FORMAT && (in.get() & 0xFF) == SPACING;
    }

    /** Reads a tile back; an unknown format or a broken file is an IOException (the caller drops the tile). */
    public static SurfaceTile unpack(int tileX, int tileZ, byte[] data) throws IOException {
        if (data.length < HEADER) throw new IOException("map tile too short");
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getInt() != MAGIC) throw new IOException("not a map tile");
        int format = in.getShort() & 0xFFFF, spacing = in.get() & 0xFF, side = in.getShort() & 0xFFFF, length = in.getInt();
        if (format != FORMAT || spacing != SPACING || side != SIDE || length != BODY) {
            throw new IOException("map tile format " + format + " (spacing " + spacing + ", side " + side + ") is not " + FORMAT);
        }
        byte[] raw = new byte[BODY];
        int n;
        try {
            n = decompressor().decompress(data, HEADER, data.length - HEADER, raw, 0, BODY);
        } catch (RuntimeException e) {
            throw new IOException("broken map tile", e);
        }
        if (n != BODY) throw new IOException("map tile body is " + n + " bytes, not " + BODY);
        ByteBuffer body = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        SurfaceTile t = new SurfaceTile(tileX, tileZ);
        for (int i = 0; i < AREA; i++) t.height[i] = body.getShort();
        body.get(t.water).get(t.surface);
        for (int i = 0; i < AREA; i++) t.tint[i] = body.getShort();
        body.get(t.canopy);
        for (int shift = 16; shift >= 0; shift -= 8) for (int i = 0; i < AREA; i++) t.colour[i] |= (body.get() & 0xFF) << shift;
        for (int i = 0; i < t.explored.length; i++) t.explored[i] = body.getLong();
        return t;
    }
}
