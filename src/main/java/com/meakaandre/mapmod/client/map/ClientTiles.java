package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapTile;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The client's map tiles: kept on disk per map ({@code mapmod/<map id>/} in the game folder) so a world's map is
 * there at once on the next visit and only changed tiles are sent again, and turned into textures for the screen by
 * {@link MapPainter}.
 * <p>
 * Each region has two textures: a close-up one, a texel a block, for a map zoomed in, and a small one, a texel for 4 by
 * 4 blocks, for a map zoomed out, so a map showing hundreds of regions stays light. A region's tile (its full data) is
 * only kept while it is needed to paint; the {@link #KEPT_TILES} used last stay in memory, the rest are read again from
 * disk when wanted. Disk reads run on a background thread, a few at a time, never while drawing.
 */
public final class ClientTiles {
    /** Regions whose full data is kept in memory; the least recently used beyond this are let go (and read again). */
    private static final int KEPT_TILES = 48;
    /** Close-up textures kept (a megabyte each), and small ones (64 kB each). */
    private static final int KEPT_CLOSE = 40, KEPT_SMALL = 800;
    /** Texels of the small texture along a side. */
    private static final int SMALL = MapGrid.REGION_PX / 4;
    /** Tiles read from disk at once at most. */
    private static final int READS = 4;

    private static final class Slot {
        @Nullable MapTile tile;
        /** The version of the region's tile held (on disk or in memory); known once the disk copy was looked for. */
        long version;
        boolean versionKnown;
        boolean reading;
        @Nullable DynamicTexture close, small;
        @Nullable Identifier closeId, smallId;
        /** Bumped whenever the region's data or fog changes; a texture painted at an older count is stale. */
        int changes = 1;
        int closePainted, smallPainted;
        long asked;
    }

    private static final Map<Long, Slot> SLOTS = new HashMap<>();
    /** Regions holding their full data, their close-up and their small texture, each in the order last used. */
    private static final LinkedHashMap<Long, Boolean> TILE_USE = new LinkedHashMap<>(64, 0.75f, true);
    private static final LinkedHashMap<Long, Boolean> CLOSE_USE = new LinkedHashMap<>(64, 0.75f, true);
    private static final LinkedHashMap<Long, Boolean> SMALL_USE = new LinkedHashMap<>(256, 0.75f, true);
    /** Regions waiting for their disk copy to be read. */
    private static final ArrayDeque<Long> TO_READ = new ArrayDeque<>();
    private static int reading;
    /** Regions the client recorded itself (no MapMod on the server) and has not written to its disk cache yet. */
    private static final Set<Long> DIRTY = new HashSet<>();
    /** Bumped whenever every tile is let go (a new map), so a disk read that finishes afterwards is dropped. */
    private static int generation;
    /** Pixels painted into, reused. */
    private static final int[] PIXELS = new int[MapGrid.REGION_PX * MapGrid.REGION_PX];

    private ClientTiles() {
    }

    private static @Nullable Path dir() {
        String id = MapClient.mapId;
        if (id == null) return null;
        return Minecraft.getInstance().gameDirectory.toPath().resolve(MapMod.ID).resolve(id);
    }

    private static Path file(Path dir, long region) {
        return dir.resolve("r." + MapGrid.keyX(region) + "." + MapGrid.keyZ(region) + ".bin");
    }

    private static Slot slot(long region) {
        return SLOTS.computeIfAbsent(region, r -> new Slot());
    }

    // ------------------------------------------------------------------------------------------------ holding tiles

    /** Keeps a region's full data, letting go of the one used longest ago when too many are kept. */
    private static void hold(long region, Slot s, MapTile tile) {
        s.tile = tile;
        s.version = tile.version;
        s.versionKnown = true;
        TILE_USE.put(region, true);
        Iterator<Long> it = TILE_USE.keySet().iterator();
        while (TILE_USE.size() > KEPT_TILES && it.hasNext()) {
            long old = it.next();
            if (old == region) continue;
            Slot o = SLOTS.get(old);
            it.remove();
            if (o == null || o.tile == null) continue;
            // a tile the client recorded itself is written out before it is let go
            if (DIRTY.remove(old)) write(old, o.tile.pack());
            o.tile = null;
        }
    }

    /** Asks for the region's disk copy to be read, if there may be one and it is not held. */
    private static void want(long region, Slot s) {
        if (s.tile != null || s.reading || (s.versionKnown && s.version == 0)) return;
        if (dir() == null) {
            s.versionKnown = true;
            return;
        }
        s.reading = true;
        TO_READ.add(region);
        readMore();
    }

    private static void readMore() {
        Path dir = dir();
        while (reading < READS && !TO_READ.isEmpty()) {
            long region = TO_READ.poll();
            Slot s = SLOTS.get(region);
            if (s == null || dir == null) continue;
            reading++;
            Path file = file(dir, region);
            int gen = generation;
            Util.ioPool().execute(() -> {
                MapTile read = null;
                try {
                    if (Files.exists(file)) read = MapTile.unpack(Files.readAllBytes(file));
                } catch (IOException ex) {
                    MapMod.LOGGER.warn("unreadable cached map tile {}", file, ex);
                }
                MapTile tile = read;
                Minecraft.getInstance().execute(() -> {
                    if (gen != generation) return;
                    reading--;
                    s.reading = false;
                    if (SLOTS.get(region) != s) return;
                    // the server may have sent a newer tile while it was read
                    if (tile != null && (s.tile == null || s.tile.version < tile.version)) {
                        boolean newer = tile.version > s.version;
                        hold(region, s, tile);
                        if (newer) s.changes++;
                    } else if (tile == null && s.tile == null) {
                        s.versionKnown = true;
                        s.version = 0;
                    }
                    readMore();
                });
            });
        }
    }

    /** True while the region's cached tile is still being looked for: it is not asked of the server until it is. */
    public static boolean loading(long region) {
        Slot s = SLOTS.get(region);
        if (s == null || !s.versionKnown) {
            want(region, slot(region));
            return slot(region).reading;
        }
        return s.reading;
    }

    /** The version held for a region (0 for none). */
    public static long version(long region) {
        Slot s = slot(region);
        if (!s.versionKnown) want(region, s);
        return s.version;
    }

    /** True if the region was asked for less than {@code ms} ago; marks it asked otherwise. */
    public static boolean askedWithin(long region, long ms) {
        Slot s = slot(region);
        long now = System.currentTimeMillis();
        if (now - s.asked < ms) return true;
        s.asked = now;
        return false;
    }

    /**
     * For the client's own recording (no MapMod on the server): the region's tile to write into, made if there is none
     * yet; null while its disk copy is still being read, so nothing recorded before is lost.
     */
    public static @Nullable MapTile editable(long region) {
        Slot s = slot(region);
        if (s.tile != null) {
            TILE_USE.get(region);
            return s.tile;
        }
        want(region, s);
        if (s.reading || !s.versionKnown || s.version != 0) return null;
        hold(region, s, new MapTile());
        return s.tile;
    }

    /** The client recorded into a region's tile: drawn again, and written to the disk cache with the next save. */
    public static void edited(long region, int chunkX, int chunkZ) {
        Slot s = SLOTS.get(region);
        if (s == null || s.tile == null) return;
        nearEdge(region, chunkX, chunkZ);
        s.tile.version = Math.max(s.tile.version + 1, System.currentTimeMillis());
        s.version = s.tile.version;
        s.changes++;
        DIRTY.add(region);
    }

    /** Writes every tile the client recorded itself since the last save. */
    public static void saveDirty() {
        for (long region : DIRTY) {
            Slot s = SLOTS.get(region);
            if (s != null && s.tile != null) write(region, s.tile.pack());
        }
        DIRTY.clear();
    }

    private static void write(long region, byte[] data) {
        Path dir = dir();
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            Path file = file(dir, region);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            MapMod.LOGGER.warn("could not cache a map tile", ex);
        }
    }

    /** A tile came from the server: kept, written to the disk cache, drawn again. */
    public static void accept(long region, byte[] data) {
        MapTile tile;
        try {
            tile = MapTile.unpack(data);
        } catch (IOException e) {
            MapMod.LOGGER.warn("bad map tile from the server", e);
            return;
        }
        Slot s = slot(region);
        hold(region, s, tile);
        s.changes++;
        neighboursChanged(region);
        write(region, data);
    }

    /** The tiles round a changed one are painted again: their edges are shaded against it. */
    private static void neighboursChanged(long region) {
        int rx = MapGrid.keyX(region), rz = MapGrid.keyZ(region);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Slot n = (dx == 0 && dz == 0) ? null : SLOTS.get(MapGrid.key(rx + dx, rz + dz));
                if (n != null) n.changes++;
            }
        }
    }

    /** The region's fog changed: drawn again next time it is shown. */
    public static void fogChanged(long region) {
        Slot s = SLOTS.get(region);
        if (s != null) s.changes++;
    }

    /** One chunk was explored: its region is drawn again, and its neighbours too where the chunk is by their edge. */
    public static void fogChanged(int chunkX, int chunkZ) {
        long region = MapGrid.regionOfChunk(chunkX, chunkZ);
        fogChanged(region);
        nearEdge(region, chunkX, chunkZ);
    }

    /** Marks the neighbouring region a chunk's change reaches into (the shading looks a block north). */
    private static void nearEdge(long region, int chunkX, int chunkZ) {
        int lz = Math.floorMod(chunkZ, MapGrid.REGION_CHUNKS);
        if (lz != MapGrid.REGION_CHUNKS - 1) return;
        Slot n = SLOTS.get(MapGrid.key(MapGrid.keyX(region), MapGrid.keyZ(region) + 1));
        if (n != null) n.changes++;
    }

    // ------------------------------------------------------------------------------------------------ textures

    /**
     * The region's texture, painted or painted again as needed; null when nothing is explored there. {@code close} asks
     * for the close-up one (a texel a block); otherwise, or until it is ready, the small one.
     */
    public static @Nullable DynamicTexture texture(long region, boolean close) {
        // a region the group has not explored at all is plain fog: nothing is read or kept for it
        if (MapClient.FOG.get(region) == null) return null;
        Slot s = slot(region);
        boolean closeFresh = s.close != null && s.closePainted == s.changes;
        boolean smallFresh = s.small != null && s.smallPainted == s.changes;
        if (close ? closeFresh : smallFresh) return use(region, s, close);
        if (s.tile == null && !(s.versionKnown && s.version == 0)) {
            // its data is read first; until then the region shows as it was
            want(region, s);
            return shown(region, s, close);
        }
        if (!repaintAllowed()) return shown(region, s, close);
        if (s.tile != null) TILE_USE.get(region);
        paint(region, s.tile);
        if (close || s.close != null) {
            if (s.close == null) {
                keepRoom(CLOSE_USE, KEPT_CLOSE, true);
                s.closeId = MapMod.id("map/tile_" + Long.toHexString(region));
                s.close = new DynamicTexture(() -> MapMod.ID + " map tile", MapGrid.REGION_PX, MapGrid.REGION_PX, false);
                Minecraft.getInstance().getTextureManager().register(s.closeId, s.close);
            }
            NativeImage image = s.close.getPixels();
            if (image != null) {
                int n = MapGrid.REGION_PX;
                for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) image.setPixel(x, z, 0xFF000000 | PIXELS[z * n + x]);
                s.close.upload();
            }
            s.closePainted = s.changes;
        }
        if (s.small == null) {
            keepRoom(SMALL_USE, KEPT_SMALL, false);
            s.smallId = MapMod.id("map/tile_" + Long.toHexString(region) + "_small");
            s.small = new DynamicTexture(() -> MapMod.ID + " map tile", SMALL, SMALL, false);
            Minecraft.getInstance().getTextureManager().register(s.smallId, s.small);
        }
        NativeImage image = s.small.getPixels();
        if (image != null) {
            shrink(image);
            s.small.upload();
        }
        s.smallPainted = s.changes;
        return use(region, s, close);
    }

    /** What a region shows while its texture is not up to date: whichever it has. */
    private static @Nullable DynamicTexture shown(long region, Slot s, boolean close) {
        if (close && s.close != null) return use(region, s, true);
        if (s.small != null) return use(region, s, false);
        return s.close;
    }

    private static DynamicTexture use(long region, Slot s, boolean close) {
        if (close && s.close != null) {
            CLOSE_USE.put(region, true);
            return s.close;
        }
        SMALL_USE.put(region, true);
        return s.small;
    }

    /** Lets go of the textures used longest ago while there are too many. */
    private static void keepRoom(LinkedHashMap<Long, Boolean> use, int kept, boolean close) {
        Iterator<Long> it = use.keySet().iterator();
        while (use.size() >= kept && it.hasNext()) {
            Slot o = SLOTS.get(it.next());
            it.remove();
            if (o == null) continue;
            if (close) {
                releaseTexture(o.closeId);
                o.close = null;
                o.closeId = null;
            } else {
                releaseTexture(o.smallId);
                o.small = null;
                o.smallId = null;
            }
        }
    }

    private static void releaseTexture(@Nullable Identifier id) {
        if (id != null) Minecraft.getInstance().getTextureManager().release(id);
    }

    /** The painted pixels, 4 by 4 averaged into the small texture. */
    private static void shrink(NativeImage image) {
        int n = MapGrid.REGION_PX;
        for (int z = 0; z < SMALL; z++) {
            for (int x = 0; x < SMALL; x++) {
                int r = 0, g = 0, b = 0;
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int c = PIXELS[(z * 4 + dz) * n + x * 4 + dx];
                        r += c >> 16 & 255;
                        g += c >> 8 & 255;
                        b += c & 255;
                    }
                }
                image.setPixel(x, z, 0xFF000000 | (r / 16) << 16 | (g / 16) << 8 | (b / 16));
            }
        }
    }

    /** Regions painted in one go at most, and the stretch of time that counts as one go (ns): a frame's worth. */
    private static final int REPAINTS = 2;
    private static final long REPAINT_WINDOW = 16_000_000L;
    private static long windowStart;
    private static int repaints;

    /** True if one more region may be painted now; the rest wait for a later frame and show as they were. */
    private static boolean repaintAllowed() {
        long now = System.nanoTime();
        if (now - windowStart > REPAINT_WINDOW) {
            windowStart = now;
            repaints = 0;
        }
        return repaints++ < REPAINTS;
    }

    /** The height recorded at a block, or null. */
    public static @Nullable Integer height(int x, int z) {
        Slot s = SLOTS.get(MapGrid.regionOfBlock(x, z));
        if (s == null || s.tile == null) return null;
        int px = Math.floorMod(x, MapGrid.REGION_BLOCKS) / MapGrid.PX_BLOCKS, pz = Math.floorMod(z, MapGrid.REGION_BLOCKS) / MapGrid.PX_BLOCKS;
        int i = MapTile.index(px, pz);
        return s.tile.known(i) ? (int) s.tile.height[i] : null;
    }

    public static void clear() {
        BlockColours.clear();
        saveDirty();
        for (Slot s : SLOTS.values()) {
            releaseTexture(s.closeId);
            releaseTexture(s.smallId);
        }
        SLOTS.clear();
        TILE_USE.clear();
        CLOSE_USE.clear();
        SMALL_USE.clear();
        TO_READ.clear();
        reading = 0;
        generation++;
    }

    // ------------------------------------------------------------------------------------------------ painting

    private static void paint(long region, @Nullable MapTile tile) {
        int rx = MapGrid.keyX(region), rz = MapGrid.keyZ(region);
        MapTile[] near = new MapTile[9];
        long[][] fog = new long[9][];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                long key = MapGrid.key(rx + dx, rz + dz);
                int k = (dz + 1) * 3 + dx + 1;
                fog[k] = MapClient.FOG.get(key);
                if (dx == 0 && dz == 0) near[k] = tile;
                else {
                    Slot s = SLOTS.get(key);
                    near[k] = s == null ? null : s.tile;
                }
            }
        }
        MapPainter.paint(PIXELS, tile, near, fog);
    }
}
