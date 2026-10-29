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
 * there at once on the next visit and only changed tiles are sent again, and turned into textures for the screen in
 * the manner of Satisfactory's map: soft ground colours, light from the north-west, contour lines every 16 blocks
 * (darker every 64), and the fog of war over what the group has not explored.
 * <p>
 * Cached tiles are read from disk on a background thread, never while drawing; only regions the group has explored
 * are read at all; and only the {@link #KEPT} regions used most recently keep their tile and texture.
 */
public final class ClientTiles {
    /** Fog: a dark blue-grey with a faint diagonal weave. */
    private static final int FOG = 0x2A3440, FOG_LINE = 0x313C4A;
    /** Explored but not recorded yet. */
    private static final int UNKNOWN = 0x5A626A;
    /** Pixels between the fog weave's diagonal lines. */
    private static final int FOG_WEAVE = 6;
    /** Hill shading: light per block of rise toward the north-west, and its most either way. */
    private static final float SLOPE_LIGHT = 0.07f, MAX_SLOPE_LIGHT = 0.3f;
    /** Blocks between contour lines, and between the darker major ones. */
    private static final int CONTOUR_STEP = 16, MAJOR_CONTOUR_STEP = 64;
    /** Light on a major contour, a contour and the shore. */
    private static final float MAJOR_CONTOUR_LIGHT = 0.72f, CONTOUR_LIGHT = 0.86f, SHORE_LIGHT = 0.8f;

    /** Regions whose tile and texture are kept in memory; the least recently used beyond this are let go. */
    private static final int KEPT = 192;

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
    public static void edited(long region) {
        Slot e = TILES.get(region);
        if (e == null || e.tile == null) return;
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
        write(region, data);
    }

    /** The region's fog changed: drawn again next time it is shown. */
    public static void fogChanged(long region) {
        Slot e = TILES.get(region);
        if (e != null) e.stale = true;
    }

    /** The region's texture, drawn or redrawn as needed; null when nothing is known there. */
    public static @Nullable Identifier texture(long region) {
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
        if (e.stale) {
            NativeImage image = e.texture.getPixels();
            if (image != null) {
                paint(image, e.tile, fog);
                e.texture.upload();
            }
            e.stale = false;
        }
        return e.id;
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
        saveDirty();
        for (Slot e : TILES.values()) release(e);
        TILES.clear();
        generation++;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static void paint(NativeImage image, @Nullable MapTile tile, long[] fog) {
        int n = MapGrid.REGION_PX;
        for (int pz = 0; pz < n; pz++) {
            for (int px = 0; px < n; px++) {
                int chunkBit = (pz / MapGrid.CHUNK_PX) * MapGrid.REGION_CHUNKS + px / MapGrid.CHUNK_PX;
                int rgb;
                if (!MapGrid.get(fog, chunkBit)) {
                    rgb = ((px + pz) % FOG_WEAVE == 0) ? FOG_LINE : FOG;
                } else if (tile == null || !tile.known(MapTile.index(px, pz))) {
                    rgb = UNKNOWN;
                } else {
                    rgb = shade(tile, px, pz);
                }
                image.setPixel(px, pz, 0xFF000000 | rgb);
            }
        }
    }

    private static int h(MapTile tile, int px, int pz) {
        px = Math.clamp(px, 0, MapGrid.REGION_PX - 1);
        pz = Math.clamp(pz, 0, MapGrid.REGION_PX - 1);
        int i = MapTile.index(px, pz);
        return tile.known(i) ? tile.height[i] : Integer.MIN_VALUE;
    }

    private static boolean water(MapTile tile, int px, int pz) {
        px = Math.clamp(px, 0, MapGrid.REGION_PX - 1);
        pz = Math.clamp(pz, 0, MapGrid.REGION_PX - 1);
        return (tile.colour[MapTile.index(px, pz)] & MapTile.WATER) != 0;
    }

    /** One pixel: its colour, lit from the north-west, with contour lines and shore lines. */
    private static int shade(MapTile tile, int px, int pz) {
        int i = MapTile.index(px, pz);
        int c = tile.colour[i] & 0xFFFFFF;
        int here = tile.height[i];
        boolean wet = (tile.colour[i] & MapTile.WATER) != 0;
        float light = 1f;
        if (!wet) {
            int nw = h(tile, px - 1, pz - 1);
            if (nw != Integer.MIN_VALUE) light += Math.clamp((here - nw) * SLOPE_LIGHT, -MAX_SLOPE_LIGHT, MAX_SLOPE_LIGHT);
            int east = h(tile, px + 1, pz), south = h(tile, px, pz + 1);
            boolean contour = false, major = false;
            for (int o : new int[]{east, south}) {
                if (o == Integer.MIN_VALUE) continue;
                if (Math.floorDiv(o, CONTOUR_STEP) != Math.floorDiv(here, CONTOUR_STEP)) contour = true;
                if (Math.floorDiv(o, MAJOR_CONTOUR_STEP) != Math.floorDiv(here, MAJOR_CONTOUR_STEP)) major = true;
            }
            if (major) light *= MAJOR_CONTOUR_LIGHT;
            else if (contour) light *= CONTOUR_LIGHT;
        } else if (!water(tile, px + 1, pz) || !water(tile, px, pz + 1) || !water(tile, px - 1, pz) || !water(tile, px, pz - 1)) {
            // the shore
            light = SHORE_LIGHT;
        }
        int r = Math.clamp((int) ((c >> 16 & 255) * light), 0, 255);
        int g = Math.clamp((int) ((c >> 8 & 255) * light), 0, 255);
        int b = Math.clamp((int) ((c & 255) * light), 0, 255);
        return r << 16 | g << 8 | b;
    }
}
