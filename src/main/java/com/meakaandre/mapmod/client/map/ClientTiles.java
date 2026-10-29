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
 * the manner of Satisfactory's map: each biome's soft colour blended into its neighbours', soft light from the
 * north-west on the land's smoothed shape, sparse contour lines every 32 blocks (darker every 128), drawn smoothly, and
 * the fog of war over what the group has not explored.
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
    private static final float SLOPE_LIGHT = 0.04f, MAX_SLOPE_LIGHT = 0.22f;
    /** Blocks between contour lines, and between the darker major ones. */
    private static final int CONTOUR_STEP = 32, MAJOR_CONTOUR_STEP = 128;
    /** Light on a major contour, a contour and the shore. */
    private static final float MAJOR_CONTOUR_LIGHT = 0.84f, CONTOUR_LIGHT = 0.93f, SHORE_LIGHT = 0.86f;

    /** Pixels either way a colour is blended over. */
    private static final int BLUR = 2;

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
        if (e.stale && repaintAllowed()) {
            NativeImage image = e.texture.getPixels();
            if (image != null) {
                paint(image, region, e.tile, fog);
                e.texture.upload();
            }
            e.stale = false;
        }
        return e.texture;
    }

    /** Tiles painted again in one go at most, and the stretch of time that counts as one go (ns): a frame's worth. */
    private static final int REPAINTS = 3;
    private static final long REPAINT_WINDOW = 16_000_000L;
    private static long windowStart;
    private static int repaints;

    /** True if one more tile may be painted again now; the rest wait for a later frame and show as they were. */
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

    /** Pixels of the neighbouring tiles read round a tile while painting it, so blends and shading run on across edges. */
    private static final int PAD = BLUR + 2;

    /**
     * The tile with a border of its neighbours' pixels round it: colours, heights and what is known, in one grid of
     * {@code REGION_PX + 2 * PAD} pixels a side (a neighbour that is not loaded counts as unknown).
     */
    private static final class Grid {
        final int side = MapGrid.REGION_PX + 2 * PAD;
        final int[] colour = new int[side * side];
        final float[] height = new float[side * side];
        final boolean[] known = new boolean[side * side];
        final boolean[] wet = new boolean[side * side];

        Grid(long region, MapTile tile) {
            int rx = MapGrid.keyX(region), rz = MapGrid.keyZ(region), n = MapGrid.REGION_PX;
            MapTile[] near = new MapTile[9];
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) near[4] = tile;
                    else {
                        Slot e = TILES.get(MapGrid.key(rx + dx, rz + dz));
                        near[(dz + 1) * 3 + dx + 1] = e == null ? null : e.tile;
                    }
                }
            }
            for (int gz = 0; gz < side; gz++) {
                for (int gx = 0; gx < side; gx++) {
                    int px = gx - PAD, pz = gz - PAD;
                    int tx = px < 0 ? -1 : px >= n ? 1 : 0, tz = pz < 0 ? -1 : pz >= n ? 1 : 0;
                    MapTile t = near[(tz + 1) * 3 + tx + 1];
                    int g = gz * side + gx;
                    if (t == null) continue;
                    int i = MapTile.index(Math.floorMod(px, n), Math.floorMod(pz, n));
                    if (!t.known(i)) continue;
                    known[g] = true;
                    colour[g] = t.colour[i] & 0xFFFFFF;
                    height[g] = t.height[i];
                    wet[g] = (t.colour[i] & MapTile.WATER) != 0;
                }
            }
        }

        int at(int px, int pz) {
            return (pz + PAD) * side + px + PAD;
        }
    }

    private static void paint(NativeImage image, long region, @Nullable MapTile tile, long[] fog) {
        int n = MapGrid.REGION_PX;
        Grid grid = tile == null ? null : new Grid(region, tile);
        int[] base = grid == null ? null : smoothColours(grid);
        float[] heights = grid == null ? null : smoothHeights(grid);
        for (int pz = 0; pz < n; pz++) {
            for (int px = 0; px < n; px++) {
                int chunkBit = (pz / MapGrid.CHUNK_PX) * MapGrid.REGION_CHUNKS + px / MapGrid.CHUNK_PX;
                int rgb;
                if (!MapGrid.get(fog, chunkBit)) {
                    rgb = ((px + pz) % FOG_WEAVE == 0) ? FOG_LINE : FOG;
                } else if (grid == null || !grid.known[grid.at(px, pz)]) {
                    rgb = UNKNOWN;
                } else {
                    rgb = shade(grid, base, heights, px, pz);
                }
                image.setPixel(px, pz, 0xFF000000 | rgb);
            }
        }
    }

    /** A weighted blend over the pixels round each one, land with land and water with water. */
    private static int[] smoothColours(Grid g) {
        int[] out = new int[g.side * g.side];
        for (int z = BLUR; z < g.side - BLUR; z++) {
            for (int x = BLUR; x < g.side - BLUR; x++) {
                int i = z * g.side + x;
                if (!g.known[i]) continue;
                int r = 0, gr = 0, b = 0, w = 0;
                for (int dz = -BLUR; dz <= BLUR; dz++) {
                    for (int dx = -BLUR; dx <= BLUR; dx++) {
                        int j = (z + dz) * g.side + x + dx;
                        if (!g.known[j] || g.wet[j] != g.wet[i]) continue;
                        // nearer pixels count more
                        int weight = BLUR + 1 - Math.max(Math.abs(dx), Math.abs(dz));
                        int c = g.colour[j];
                        r += (c >> 16 & 255) * weight;
                        gr += (c >> 8 & 255) * weight;
                        b += (c & 255) * weight;
                        w += weight;
                    }
                }
                out[i] = (r / w) << 16 | (gr / w) << 8 | (b / w);
            }
        }
        return out;
    }

    /** Heights blended the same way, so the shading and contour lines follow the land's shape, not its blocks. */
    private static float[] smoothHeights(Grid g) {
        float[] out = new float[g.side * g.side];
        for (int z = 0; z < g.side; z++) {
            for (int x = 0; x < g.side; x++) {
                int i = z * g.side + x;
                if (!g.known[i]) continue;
                float sum = 0, w = 0;
                for (int dz = -BLUR; dz <= BLUR; dz++) {
                    for (int dx = -BLUR; dx <= BLUR; dx++) {
                        int xx = x + dx, zz = z + dz;
                        if (xx < 0 || zz < 0 || xx >= g.side || zz >= g.side) continue;
                        int j = zz * g.side + xx;
                        if (!g.known[j] || g.wet[j] != g.wet[i]) continue;
                        float weight = BLUR + 1 - Math.max(Math.abs(dx), Math.abs(dz));
                        sum += g.height[j] * weight;
                        w += weight;
                    }
                }
                out[i] = sum / w;
            }
        }
        return out;
    }

    /** One pixel: its blended colour, lit softly from the north-west, with sparse contour lines and shore lines. */
    private static int shade(Grid g, int[] base, float[] h, int px, int pz) {
        int i = g.at(px, pz);
        int c = base[i];
        float light = 1f;
        if (!g.wet[i]) {
            int nw = g.at(px - 1, pz - 1), se = g.at(px + 1, pz + 1);
            if (g.known[nw] && g.known[se] && !g.wet[nw] && !g.wet[se]) {
                light += Math.clamp((h[se] - h[nw]) * -SLOPE_LIGHT, -MAX_SLOPE_LIGHT, MAX_SLOPE_LIGHT);
            }
            boolean contour = false, major = false;
            for (int o : new int[]{g.at(px + 1, pz), g.at(px, pz + 1)}) {
                if (!g.known[o] || g.wet[o]) continue;
                if (Math.floor(h[o] / CONTOUR_STEP) != Math.floor(h[i] / CONTOUR_STEP)) contour = true;
                if (Math.floor(h[o] / MAJOR_CONTOUR_STEP) != Math.floor(h[i] / MAJOR_CONTOUR_STEP)) major = true;
            }
            if (major) light *= MAJOR_CONTOUR_LIGHT;
            else if (contour) light *= CONTOUR_LIGHT;
        } else {
            for (int o : new int[]{g.at(px + 1, pz), g.at(px, pz + 1), g.at(px - 1, pz), g.at(px, pz - 1)}) {
                if (g.known[o] && !g.wet[o]) light = SHORE_LIGHT;
            }
        }
        int r = Math.clamp((int) ((c >> 16 & 255) * light), 0, 255);
        int gr = Math.clamp((int) ((c >> 8 & 255) * light), 0, 255);
        int b = Math.clamp((int) ((c & 255) * light), 0, 255);
        return r << 16 | gr << 8 | b;
    }
}
