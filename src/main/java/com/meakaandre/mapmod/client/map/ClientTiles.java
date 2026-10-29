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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The client's map tiles: kept on disk per map ({@code mapmod/<map id>/} in the game folder) so a world's map is
 * there at once on the next visit and only changed tiles are sent again, and turned into textures for the screen by
 * {@link MapPainter}, a texel a block.
 * <p>
 * Cached tiles are read from disk on a background thread, never while drawing; only regions the group has explored
 * are read at all; and only the {@link #KEPT} regions used most recently keep their tile and texture.
 */
public final class ClientTiles {
    /** Regions whose tile and texture are kept in memory; the least recently used beyond this are let go. */
    private static final int KEPT = 72;

    private static final class Slot {
        @Nullable MapTile tile;
        @Nullable DynamicTexture texture;
        @Nullable Identifier id;
        boolean stale = true;
        long asked;
        boolean fromDisk;
        /** True while its cached tile is being read from disk: no tile asked for yet, so the disk copy's version counts. */
        boolean loading;
    }

    /** By region, in the order last used (oldest first). */
    private static final LinkedHashMap<Long, Slot> TILES = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Slot> eldest) {
            if (size() <= KEPT) return false;
            // a tile the client recorded itself is written out before it is let go
            if (DIRTY.remove(eldest.getKey()) && eldest.getValue().tile != null) write(eldest.getKey(), eldest.getValue().tile.pack());
            release(eldest.getValue());
            return true;
        }
    };
    /** Regions the client recorded itself (no MapMod on the server) and has not written to its disk cache yet. */
    private static final Set<Long> DIRTY = new HashSet<>();
    /** Bumped whenever every tile is let go (a new map), so a disk read that finishes afterwards is dropped. */
    private static int generation;

    private ClientTiles() {
    }

    private static @Nullable Path dir() {
        String id = MapClient.mapId;
        if (id == null) return null;
        return Minecraft.getInstance().gameDirectory.toPath().resolve(MapMod.ID).resolve(id);
    }

    private static void release(Slot e) {
        if (e.id != null) Minecraft.getInstance().getTextureManager().release(e.id);
        e.texture = null;
        e.id = null;
        e.tile = null;
    }

    private static Slot entry(long region) {
        Slot e = TILES.get(region);
        if (e != null) return e;
        e = new Slot();
        TILES.put(region, e);
        Path dir = dir();
        if (dir != null) {
            // read on a background thread; the tile turns up a frame or two later
            Path file = dir.resolve("r." + MapGrid.keyX(region) + "." + MapGrid.keyZ(region) + ".bin");
            Slot fresh = e;
            fresh.loading = true;
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
                    fresh.loading = false;
                    // the map was cleared, or the server sent a newer tile, while it was read
                    if (gen != generation || TILES.get(region) != fresh || tile == null) return;
                    if (fresh.tile == null || fresh.tile.version < tile.version) {
                        fresh.tile = tile;
                        fresh.fromDisk = true;
                        fresh.stale = true;
                        neighboursChanged(region);
                    }
                });
            });
        }
        return e;
    }

    /** True while the region's cached tile is still being read: it is not asked of the server until it is. */
    public static boolean loading(long region) {
        Slot e = TILES.get(region);
        return e != null && e.loading;
    }

    /** The version held for a region (0 for none). */
    public static long version(long region) {
        Slot e = entry(region);
        return e.tile == null ? 0 : e.tile.version;
    }

    /** True if the region was asked for less than {@code ms} ago; marks it asked otherwise. */
    public static boolean askedWithin(long region, long ms) {
        Slot e = entry(region);
        long now = System.currentTimeMillis();
        if (now - e.asked < ms) return true;
        e.asked = now;
        return false;
    }

    /**
     * For the client's own recording (no MapMod on the server): the region's tile to write into, made if there is none
     * yet; null while its disk copy is still being read, so nothing recorded before is lost.
     */
    public static @Nullable MapTile editable(long region) {
        Slot e = entry(region);
        if (e.loading) return null;
        if (e.tile == null) e.tile = new MapTile();
        return e.tile;
    }

    /** The client recorded into a region's tile: drawn again, and written to the disk cache with the next save. */
    public static void edited(long region, int chunkX, int chunkZ) {
        Slot e = TILES.get(region);
        if (e == null || e.tile == null) return;
        nearEdge(region, chunkX, chunkZ);
        e.tile.version = Math.max(e.tile.version + 1, System.currentTimeMillis());
        e.stale = true;
        DIRTY.add(region);
    }

    /** Writes every tile the client recorded itself since the last save. */
    public static void saveDirty() {
        for (long region : DIRTY) {
            Slot e = TILES.get(region);
            if (e != null && e.tile != null) write(region, e.tile.pack());
        }
        DIRTY.clear();
    }

    private static void write(long region, byte[] data) {
        Path dir = dir();
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("r." + MapGrid.keyX(region) + "." + MapGrid.keyZ(region) + ".bin");
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
        Slot e = entry(region);
        e.tile = tile;
        e.stale = true;
        neighboursChanged(region);
        write(region, data);
    }

    /** The tiles round a changed one are painted again: their edges blend into it. */
    private static void neighboursChanged(long region) {
        int rx = MapGrid.keyX(region), rz = MapGrid.keyZ(region);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Slot n = (dx == 0 && dz == 0) ? null : TILES.get(MapGrid.key(rx + dx, rz + dz));
                if (n != null) n.stale = true;
            }
        }
    }

    /** The region's fog changed: drawn again next time it is shown. */
    public static void fogChanged(long region) {
        Slot e = TILES.get(region);
        if (e != null) e.stale = true;
        // the fog's soft edge runs over into the regions round it
        neighboursChanged(region);
    }

    /** One chunk was explored: its region is drawn again, and its neighbours too where the chunk is by their edge. */
    public static void fogChanged(int chunkX, int chunkZ) {
        long region = MapGrid.regionOfChunk(chunkX, chunkZ);
        Slot e = TILES.get(region);
        if (e != null) e.stale = true;
        nearEdge(region, chunkX, chunkZ);
    }

    /** Marks the neighbouring regions a chunk's change reaches into (it blends over a couple of chunks). */
    private static void nearEdge(long region, int chunkX, int chunkZ) {
        int lx = Math.floorMod(chunkX, MapGrid.REGION_CHUNKS), lz = Math.floorMod(chunkZ, MapGrid.REGION_CHUNKS);
        int reach = MapPainter.PAD / MapGrid.CHUNK_PX + 1;
        int dx = lx < reach ? -1 : lx >= MapGrid.REGION_CHUNKS - reach ? 1 : 0;
        int dz = lz < reach ? -1 : lz >= MapGrid.REGION_CHUNKS - reach ? 1 : 0;
        if (dx == 0 && dz == 0) return;
        int rx = MapGrid.keyX(region), rz = MapGrid.keyZ(region);
        for (int[] o : new int[][]{{dx, 0}, {0, dz}, {dx, dz}}) {
            if (o[0] == 0 && o[1] == 0) continue;
            Slot n = TILES.get(MapGrid.key(rx + o[0], rz + o[1]));
            if (n != null) n.stale = true;
        }
    }

    /** The region's texture, drawn or redrawn as needed; null when nothing is known there. */
    public static @Nullable DynamicTexture texture(long region) {
        // a region the group has not explored at all is plain fog: nothing is read or kept for it
        long[] fog = MapClient.FOG.get(region);
        if (fog == null) return null;
        Slot e = entry(region);
        if (e.texture == null) {
            e.id = MapMod.id("map/tile_" + Long.toHexString(region));
            e.texture = new DynamicTexture(() -> MapMod.ID + " map tile", MapGrid.REGION_PX, MapGrid.REGION_PX, false);
            Minecraft.getInstance().getTextureManager().register(e.id, e.texture);
            e.stale = true;
        }
        if (e.stale && repaintAllowed(1)) {
            NativeImage image = e.texture.getPixels();
            if (image != null) {
                paint(image, region, e.tile);
                e.texture.upload();
            }
            e.stale = false;
        }
        return e.texture;
    }

    /** Overview tiles' worth of painting done in one go at most, and the stretch of time that counts as one go (ns): a frame's worth. */
    private static final int REPAINTS = 2;
    private static final long REPAINT_WINDOW = 16_000_000L;
    private static long windowStart;
    private static int repaints;

    /** True if one more tile may be painted again now; the rest wait for a later frame and show as they were. */
    private static boolean repaintAllowed(int cost) {
        long now = System.nanoTime();
        if (now - windowStart > REPAINT_WINDOW) {
            windowStart = now;
            repaints = 0;
        }
        // one go may always paint one thing, however costly
        if (repaints > 0 && repaints + cost > REPAINTS) return false;
        repaints += cost;
        return true;
    }

    /** The height recorded at a block, or null. */
    public static @Nullable Integer height(int x, int z) {
        long region = MapGrid.regionOfBlock(x, z);
        Slot e = TILES.get(region);
        if (e == null || e.tile == null) return null;
        int px = Math.floorMod(x, MapGrid.REGION_BLOCKS) / MapGrid.PX_BLOCKS, pz = Math.floorMod(z, MapGrid.REGION_BLOCKS) / MapGrid.PX_BLOCKS;
        int i = MapTile.index(px, pz);
        return e.tile.known(i) ? (int) e.tile.height[i] : null;
    }

    public static void clear() {
        BlockColours.clear();
        saveDirty();
        for (Slot e : TILES.values()) release(e);
        TILES.clear();
        generation++;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static void paint(NativeImage image, long region, @Nullable MapTile tile) {
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
                    Slot e = TILES.get(key);
                    near[k] = e == null ? null : e.tile;
                }
            }
        }
        MapPainter.paint(image, tile, near, fog);
    }
}
