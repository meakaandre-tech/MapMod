package com.meakaandre.mapmod.map;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.ChunkScanner;
import com.meakaandre.mapmod.api.FoundPoi;
import com.meakaandre.mapmod.api.MapModApi;
import com.meakaandre.mapmod.api.MapPoi;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Overworld's map terrain on the server, kept low-resolution and on disk ({@code data/mapmod/terrain}). A chunk is
 * recorded (a pixel a block: the top block, its biome and height) when it loads and again a few seconds after a block
 * in it changes, and nowhere else: there is no scanning of the world. A reveal with a guess fills land nobody has loaded
 * with the world generator's guess, which real recording replaces.
 * <p>
 * Recording a chunk also runs every {@link ChunkScanner}: what each finds is kept per chunk (on disk next to the tile),
 * with the group that sees it.
 * <p>
 * Everything here runs on the server thread except the generator guesses, which run on the map's worker and are put
 * in on the server thread.
 */
public final class MapTerrain {
    /** Tiles not used for this long are written out and dropped from memory. */
    private static final long IDLE_MS = 5 * 60_000;
    /** Chunks recorded per server tick at most; the rest wait. */
    private static final int PER_TICK = 48;
    /** Ticks a changed chunk waits before it is recorded again, so a busy build is recorded once, not every block. */
    private static final int CHANGE_DELAY = 100;
    /** Ticks between saving tiles and dropping idle ones (a minute). */
    private static final int SAVE_TICKS = 1200;
    /** Points a chunk's guess samples (2 by 2). */
    private static final int GUESS_POINTS = 4;
    /** Blocks between a guess's sample points, and from the chunk's corner to the first one. */
    private static final int GUESS_SPACING = 8, GUESS_INSET = 4;
    /** Format of the found-POI files. */
    private static final int FOUND_FORMAT = 1;
    /** Longest string read from a found-POI file. */
    private static final int MAX_FOUND_STRING = 256;

    /** A found POI, which scanner found it and who sees it ("" for everyone). */
    public record Found(String source, MapPoi poi, String owner) {
    }

    private static final Long2ObjectOpenHashMap<MapTile> TILES = new Long2ObjectOpenHashMap<>();
    private static final Long2LongOpenHashMap USED = new Long2LongOpenHashMap();
    private static final LongOpenHashSet DIRTY = new LongOpenHashSet();
    /** Chunks waiting to be recorded, oldest first. */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    /** Changed chunks and the tick they may be recorded again. */
    private static final Long2LongOpenHashMap CHANGED = new Long2LongOpenHashMap();
    /** Per region: chunk -> what was found in it. */
    private static final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<List<Found>>> FOUND = new Long2ObjectOpenHashMap<>();
    private static final LongOpenHashSet FOUND_LOADED = new LongOpenHashSet();
    /** Regions whose found POIs changed since the last {@link #takeFoundChanges}. */
    private static final LongOpenHashSet FOUND_CHANGED = new LongOpenHashSet();
    private static @Nullable Path root;
    private static int ticks;

    private MapTerrain() {
    }

    public static void start(MinecraftServer server) {
        root = server.getWorldPath(LevelResource.DATA).resolve(MapMod.ID);
        try {
            Files.createDirectories(root.resolve("terrain"));
        } catch (IOException e) {
            MapMod.LOGGER.error("could not make the map folder {}", root, e);
        }
    }

    public static void stop() {
        saveAll();
        TILES.clear();
        USED.clear();
        DIRTY.clear();
        QUEUE.clear();
        CHANGED.clear();
        FOUND.clear();
        FOUND_LOADED.clear();
        FOUND_CHANGED.clear();
        root = null;
    }

    /** The map's save folder ({@code data/mapmod} in the world), or null with no world open. */
    public static @Nullable Path root() {
        return root;
    }

    // ------------------------------------------------------------------------------------------------ tiles

    private static Path tileFile(long region) {
        return root.resolve("terrain").resolve("r." + MapGrid.keyX(region) + "." + MapGrid.keyZ(region) + ".bin");
    }

    private static Path foundFile(long region) {
        return root.resolve("terrain").resolve("f." + MapGrid.keyX(region) + "." + MapGrid.keyZ(region) + ".bin");
    }

    /** The tile, from memory or disk; a blank one if there is none yet. Null only with no world open. */
    public static @Nullable MapTile tile(long region) {
        if (root == null) return null;
        USED.put(region, System.currentTimeMillis());
        MapTile tile = TILES.get(region);
        if (tile != null) return tile;
        Path file = tileFile(region);
        if (Files.exists(file)) {
            try {
                tile = MapTile.unpack(Files.readAllBytes(file));
            } catch (IOException e) {
                MapMod.LOGGER.warn("unreadable map tile {}, starting it again", file, e);
            }
        }
        if (tile == null) tile = new MapTile();
        TILES.put(region, tile);
        return tile;
    }

    private static void changed(long region, MapTile tile) {
        // strictly increasing, so a client that holds an older copy always asks for the new one
        tile.version = Math.max(tile.version + 1, System.currentTimeMillis());
        DIRTY.add(region);
    }

    public static void saveAll() {
        if (root == null) return;
        for (long region : DIRTY) {
            MapTile tile = TILES.get(region);
            if (tile != null) write(tileFile(region), tile.pack());
        }
        DIRTY.clear();
        for (long region : FOUND_LOADED) saveFound(region);
    }

    private static void write(Path file, byte[] data) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            MapMod.LOGGER.error("could not write the map file {}", file, e);
        }
    }

    // ------------------------------------------------------------------------------------------------ recording

    /** A chunk loaded: record it soon. */
    public static void loaded(ServerLevel level, LevelChunk chunk) {
        if (root == null || !MapServer.mapped(level)) return;
        QUEUE.add(MapGrid.key(chunk.getPos().x(), chunk.getPos().z()));
    }

    /** A block changed in a chunk: record it again after a while. */
    public static void blockChanged(ServerLevel level, BlockPos pos) {
        if (root == null || !MapServer.mapped(level)) return;
        long key = MapGrid.key(pos.getX() >> 4, pos.getZ() >> 4);
        if (!CHANGED.containsKey(key)) CHANGED.put(key, level.getServer().getTickCount() + CHANGE_DELAY);
    }

    public static void tick(ServerLevel level) {
        if (root == null) return;
        int now = level.getServer().getTickCount();
        if (!CHANGED.isEmpty()) {
            var it = CHANGED.long2LongEntrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getLongValue() > now) continue;
                QUEUE.add(e.getLongKey());
                it.remove();
            }
        }
        for (int n = 0; n < PER_TICK && !QUEUE.isEmpty(); n++) {
            long key = QUEUE.removeFirstLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(MapGrid.keyX(key), MapGrid.keyZ(key));
            if (chunk != null) record(level, chunk);
        }
        if (++ticks % SAVE_TICKS == 0) {
            saveAll();
            long cutoff = System.currentTimeMillis() - IDLE_MS;
            List<Long> idle = new ArrayList<>();
            USED.long2LongEntrySet().forEach(e -> {
                if (e.getLongValue() < cutoff) idle.add(e.getLongKey());
            });
            for (long region : idle) {
                USED.remove(region);
                TILES.remove(region);
                if (FOUND_LOADED.remove(region)) FOUND.remove(region);
            }
        }
    }

    /** Records a loaded chunk's 16 pixels and asks the scanners what stands in it. */
    public static void record(ServerLevel level, LevelChunk chunk) {
        long region = MapGrid.regionOfChunk(chunk.getPos().x(), chunk.getPos().z());
        MapTile tile = tile(region);
        if (tile == null) return;
        if (MapRecorder.record(level, chunk, tile, true)) changed(region, tile);
        scan(level, chunk, region);
    }

    private static void scan(ServerLevel level, LevelChunk chunk, long region) {
        var scanners = MapModApi.scanners();
        if (scanners.isEmpty()) return;
        List<Found> found = new ArrayList<>();
        for (var s : scanners) {
            List<FoundPoi> list;
            try {
                list = s.value().scan(level, chunk);
            } catch (RuntimeException e) {
                MapMod.LOGGER.warn("map chunk scanner {} failed on chunk {}", s.id(), chunk.getPos(), e);
                continue;
            }
            String source = s.id().toString();
            if (list != null) for (FoundPoi f : list) found.add(new Found(source, f.poi(), f.owner() == null ? "" : f.owner()));
        }
        put(region, MapGrid.key(chunk.getPos().x(), chunk.getPos().z()), found, null);
    }

    /** Replaces a chunk's found POIs: all of them, or only one scanner's when {@code only} is given. */
    private static void put(long region, long chunk, List<Found> found, @Nullable String only) {
        Long2ObjectOpenHashMap<List<Found>> byChunk = found(region);
        List<Found> before = byChunk.get(chunk);
        List<Found> now = new ArrayList<>();
        if (only != null && before != null) for (Found f : before) if (!f.source().equals(only)) now.add(f);
        now.addAll(found);
        if (now.equals(before == null ? List.of() : before)) return;
        if (now.isEmpty()) byChunk.remove(chunk);
        else byChunk.put(chunk, now);
        FOUND_CHANGED.add(region);
    }

    /**
     * What one scanner found in a chunk that is not loaded (a search had it generated): replaces that scanner's POIs on
     * record for the chunk; the others' stay. When the chunk is loaded for real it is recorded as usual.
     */
    public static void setFound(ServerLevel level, Identifier scanner, int cx, int cz, List<FoundPoi> pois) {
        if (root == null || !MapServer.mapped(level)) return;
        String source = scanner.toString();
        List<Found> found = new ArrayList<>();
        for (FoundPoi f : pois) found.add(new Found(source, f.poi(), f.owner() == null ? "" : f.owner()));
        put(MapGrid.regionOfChunk(cx, cz), MapGrid.key(cx, cz), found, source);
    }

    /** Every found POI of a kind within this many blocks of a spot, nearest first. */
    public static List<MapPoi> foundNear(String kind, int x, int z, int blocks) {
        List<MapPoi> out = new ArrayList<>();
        if (root == null) return out;
        int r = Math.max(0, Math.floorDiv(blocks, MapGrid.REGION_BLOCKS)) + 1;
        long centre = MapGrid.regionOfChunk(x >> 4, z >> 4);
        int rx = MapGrid.keyX(centre), rz = MapGrid.keyZ(centre);
        long max = (long) blocks * blocks;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (List<Found> list : found(MapGrid.key(rx + dx, rz + dz)).values()) {
                    for (Found f : list) {
                        if (!f.poi().kind().equals(kind)) continue;
                        long ddx = f.poi().x() - x, ddz = f.poi().z() - z;
                        if (ddx * ddx + ddz * ddz <= max) out.add(f.poi());
                    }
                }
            }
        }
        out.sort(Comparator.comparingLong(p -> (long) (p.x() - x) * (p.x() - x) + (long) (p.z() - z) * (p.z() - z)));
        return out;
    }

    /** What was found in a region, by chunk (loaded from disk the first time). */
    private static Long2ObjectOpenHashMap<List<Found>> found(long region) {
        Long2ObjectOpenHashMap<List<Found>> byChunk = FOUND.get(region);
        if (byChunk != null) return byChunk;
        byChunk = new Long2ObjectOpenHashMap<>();
        FOUND.put(region, byChunk);
        FOUND_LOADED.add(region);
        USED.put(region, System.currentTimeMillis());
        Path file = root == null ? null : foundFile(region);
        if (file != null && Files.exists(file)) {
            try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
                int format = in.readByte();
                if (format != FOUND_FORMAT) throw new IOException("unknown found-POI format " + format);
                int chunks = in.readInt();
                for (int c = 0; c < chunks; c++) {
                    long key = in.readLong();
                    int n = in.readInt();
                    List<Found> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        String source = readString(in), kind = readString(in), sub = readString(in);
                        int purity = in.readByte();
                        MapPoi poi = new MapPoi(kind, sub, purity, in.readInt(), in.readInt(), in.readInt());
                        list.add(new Found(source, poi, readString(in)));
                    }
                    byChunk.put(key, list);
                }
            } catch (IOException e) {
                MapMod.LOGGER.warn("unreadable map file {}", file, e);
            }
        }
        return byChunk;
    }

    private static String readString(DataInputStream in) throws IOException {
        String s = in.readUTF();
        if (s.length() > MAX_FOUND_STRING) throw new IOException("string too long in a found-POI file");
        return s;
    }

    private static void saveFound(long region) {
        Long2ObjectOpenHashMap<List<Found>> byChunk = FOUND.get(region);
        if (byChunk == null || root == null) return;
        Path file = foundFile(region);
        if (byChunk.isEmpty() && !Files.exists(file)) return;
        try (var bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(FOUND_FORMAT);
            out.writeInt(byChunk.size());
            for (var e : byChunk.long2ObjectEntrySet()) {
                out.writeLong(e.getLongKey());
                out.writeInt(e.getValue().size());
                for (Found f : e.getValue()) {
                    out.writeUTF(f.source());
                    out.writeUTF(f.poi().kind());
                    out.writeUTF(f.poi().sub());
                    out.writeByte(f.poi().purity());
                    out.writeInt(f.poi().x());
                    out.writeInt(f.poi().y());
                    out.writeInt(f.poi().z());
                    out.writeUTF(f.owner());
                }
            }
            out.flush();
            write(file, bytes.toByteArray());
        } catch (IOException e) {
            MapMod.LOGGER.error("could not write the map file {}", file, e);
        }
    }

    /** What a group sees of what was found in a region: what everyone sees, and what the group owns. */
    public static List<MapPoi> foundFor(long region, String group) {
        List<MapPoi> out = new ArrayList<>();
        if (root == null) return out;
        var groups = MapModApi.groups();
        for (List<Found> list : found(region).values()) {
            for (Found f : list) {
                if (f.owner().isEmpty() || groups.same(f.owner(), group)) out.add(f.poi());
            }
        }
        return out;
    }

    /** Regions whose found POIs changed since last asked. */
    public static long[] takeFoundChanges() {
        long[] out = FOUND_CHANGED.toLongArray();
        FOUND_CHANGED.clear();
        return out;
    }

    // ------------------------------------------------------------------------------------------------ guesses

    /**
     * A chunk of generator guesses: 2 by 2 samples, each covering 8 by 8 pixels: the block the ground likely shows, its
     * map colour, the biome, flags, water depth and height.
     */
    public record Guess(long chunk, String[] block, int[] colour, String[] biome, int[] flags, int[] depth, short[] height) {
    }

    /** True if the chunk has nothing recorded or only a guess (so a guess may fill it). */
    public static boolean needsGuess(int cx, int cz) {
        MapTile tile = tile(MapGrid.regionOfChunk(cx, cz));
        if (tile == null) return false;
        int i = MapTile.index(Math.floorMod(cx, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX, Math.floorMod(cz, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX);
        return !tile.known(i);
    }

    /** Off the server thread: the generator's ground for a chunk, at 2 by 2 points. */
    public static Guess guess(ServerLevel level, int cx, int cz, int seaLevel) {
        int n = GUESS_POINTS;
        String[] block = new String[n], biome = new String[n];
        int[] colour = new int[n], flags = new int[n], depth = new int[n];
        short[] height = new short[n];
        for (int s = 0; s < n; s++) {
            int x = (cx << 4) + (s & 1) * GUESS_SPACING + GUESS_INSET, z = (cz << 4) + (s >> 1) * GUESS_SPACING + GUESS_INSET;
            MapGuess.Column col = MapGuess.column(level, x, z);
            flags[s] = MapTile.KNOWN | MapTile.GUESSED;
            biome[s] = "";
            if (col == null) {
                block[s] = "";
                colour[s] = MapRecorder.VOID_COLOUR;
                height[s] = (short) level.getMinY();
                continue;
            }
            biome[s] = col.biome().unwrapKey().map(k -> k.identifier().toString()).orElse("");
            boolean underwater = col.y() < seaLevel - 1;
            block[s] = MapPalette.guessBlock(col.biome(), underwater);
            colour[s] = MapPalette.guess(col.biome());
            if (underwater) {
                flags[s] |= MapTile.WATER;
                depth[s] = seaLevel - 1 - col.y();
                colour[s] = MapPalette.water(depth[s]);
                height[s] = (short) (seaLevel - 1);
            } else {
                height[s] = (short) col.y();
            }
        }
        return new Guess(MapGrid.key(cx, cz), block, colour, biome, flags, depth, height);
    }

    /** On the server thread: puts guesses in where nothing real has been recorded. Returns the regions changed. */
    public static LongOpenHashSet apply(List<Guess> guesses) {
        LongOpenHashSet changed = new LongOpenHashSet();
        for (Guess g : guesses) {
            int cx = MapGrid.keyX(g.chunk()), cz = MapGrid.keyZ(g.chunk());
            long region = MapGrid.regionOfChunk(cx, cz);
            MapTile tile = tile(region);
            if (tile == null) continue;
            int baseX = Math.floorMod(cx, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX, baseZ = Math.floorMod(cz, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX;
            boolean any = false;
            for (int pz = 0; pz < MapGrid.CHUNK_PX; pz++) {
                for (int px = 0; px < MapGrid.CHUNK_PX; px++) {
                    int i = MapTile.index(baseX + px, baseZ + pz);
                    if (tile.known(i) && !tile.guessed(i)) continue;
                    int s = (pz >= MapGrid.CHUNK_PX / 2 ? 2 : 0) + (px >= MapGrid.CHUNK_PX / 2 ? 1 : 0);
                    tile.cell[i] = MapTile.cell(tile.block(g.block()[s], g.colour()[s]), tile.biome(g.biome()[s]), g.flags()[s], g.depth()[s]);
                    tile.height[i] = g.height()[s];
                    any = true;
                }
            }
            if (any) {
                changed(region, tile);
                changed.add(region);
            }
        }
        return changed;
    }
}
