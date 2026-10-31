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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the chunk hooks ({@link ChunkScanner}) found in the Overworld, kept per chunk with the group that sees it, on
 * disk per region ({@code data/mapmod/terrain/f.<x>.<z>.bin}). A chunk is scanned when it loads and again a few seconds
 * after a block in it changes, and nowhere else: there is no scanning of the world. The server keeps no terrain of its
 * own (the client captures and paints what it sees). Server thread.
 */
public final class MapFound {
    /** Regions not used for this long are written out and dropped from memory. */
    private static final long IDLE_MS = 5 * 60_000;
    /** Chunks scanned per server tick at most; the rest wait. */
    private static final int PER_TICK = 48;
    /** Ticks a changed chunk waits before it is scanned again, so a busy build is scanned once, not every block. */
    private static final int CHANGE_DELAY = 100;
    /** Ticks between saving and dropping idle regions (a minute). */
    private static final int SAVE_TICKS = 1200;
    /** Format of the found-POI files. */
    private static final int FOUND_FORMAT = 1;
    /** Longest string read from a found-POI file. */
    private static final int MAX_FOUND_STRING = 256;

    /** A found POI, which scanner found it and who sees it ("" for everyone). */
    public record Found(String source, MapPoi poi, String owner) {
    }

    private static final Long2LongOpenHashMap USED = new Long2LongOpenHashMap();
    /** Chunks waiting to be scanned, oldest first. */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    /** Changed chunks and the tick they may be scanned again. */
    private static final Long2LongOpenHashMap CHANGED = new Long2LongOpenHashMap();
    /** Per region: chunk -> what was found in it. */
    private static final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<List<Found>>> FOUND = new Long2ObjectOpenHashMap<>();
    /** Regions whose found POIs changed since the last {@link #takeChanges}. */
    private static final LongOpenHashSet FOUND_CHANGED = new LongOpenHashSet();
    private static int ticks;

    private MapFound() {
    }

    public static void stop() {
        saveAll();
        USED.clear();
        QUEUE.clear();
        CHANGED.clear();
        FOUND.clear();
        FOUND_CHANGED.clear();
    }

    private static boolean active(ServerLevel level) {
        return MapFiles.root() != null && MapServer.mapped(level) && !MapModApi.scanners().isEmpty();
    }

    private static Path file(Path root, long region) {
        return root.resolve("terrain").resolve("f." + MapGrid.keyX(region) + "." + MapGrid.keyZ(region) + ".bin");
    }

    // ------------------------------------------------------------------------------------------------ scanning

    /** A chunk loaded: scan it soon. */
    public static void loaded(ServerLevel level, LevelChunk chunk) {
        if (active(level)) QUEUE.add(MapGrid.key(chunk.getPos().x(), chunk.getPos().z()));
    }

    /** A block changed in a chunk: scan it again after a while. */
    public static void blockChanged(ServerLevel level, BlockPos pos) {
        if (!active(level)) return;
        long key = MapGrid.key(pos.getX() >> 4, pos.getZ() >> 4);
        if (!CHANGED.containsKey(key)) CHANGED.put(key, level.getServer().getTickCount() + CHANGE_DELAY);
    }

    public static void tick(ServerLevel level) {
        if (MapFiles.root() == null) return;
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
            if (chunk != null) scan(level, chunk);
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
                FOUND.remove(region);
            }
        }
    }

    /** Asks every scanner what stands in a loaded chunk; what each finds replaces what it found there before. */
    public static void scan(ServerLevel level, LevelChunk chunk) {
        if (MapFiles.root() == null || !MapServer.mapped(level)) return;
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
        long region = MapGrid.regionOfChunk(chunk.getPos().x(), chunk.getPos().z());
        put(region, MapGrid.key(chunk.getPos().x(), chunk.getPos().z()), found, null);
    }

    /** Replaces a chunk's found POIs: all of them, or only one scanner's when {@code only} is given. */
    private static void put(long region, long chunk, List<Found> found, String only) {
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
     * record for the chunk; the others' stay. When the chunk is loaded for real it is scanned as usual.
     */
    public static void set(ServerLevel level, Identifier scanner, int cx, int cz, List<FoundPoi> pois) {
        if (MapFiles.root() == null || !MapServer.mapped(level)) return;
        String source = scanner.toString();
        List<Found> found = new ArrayList<>();
        for (FoundPoi f : pois) found.add(new Found(source, f.poi(), f.owner() == null ? "" : f.owner()));
        put(MapGrid.regionOfChunk(cx, cz), MapGrid.key(cx, cz), found, source);
    }

    /** Every found POI of a kind within this many blocks of a spot, nearest first. */
    public static List<MapPoi> near(String kind, int x, int z, int blocks) {
        List<MapPoi> out = new ArrayList<>();
        if (MapFiles.root() == null) return out;
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

    /** What a group sees of what was found in a region: what everyone sees, and what the group owns. */
    public static List<MapPoi> foundFor(long region, String group) {
        List<MapPoi> out = new ArrayList<>();
        if (MapFiles.root() == null) return out;
        var groups = MapModApi.groups();
        for (List<Found> list : found(region).values()) {
            for (Found f : list) {
                if (f.owner().isEmpty() || groups.same(f.owner(), group)) out.add(f.poi());
            }
        }
        return out;
    }

    /** Regions whose found POIs changed since last asked. */
    public static long[] takeChanges() {
        long[] out = FOUND_CHANGED.toLongArray();
        FOUND_CHANGED.clear();
        return out;
    }

    // ------------------------------------------------------------------------------------------------ files

    /** What was found in a region, by chunk (read from disk the first time). */
    private static Long2ObjectOpenHashMap<List<Found>> found(long region) {
        USED.put(region, System.currentTimeMillis());
        Long2ObjectOpenHashMap<List<Found>> byChunk = FOUND.get(region);
        if (byChunk != null) return byChunk;
        byChunk = new Long2ObjectOpenHashMap<>();
        FOUND.put(region, byChunk);
        Path root = MapFiles.root();
        Path file = root == null ? null : file(root, region);
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

    public static void saveAll() {
        Path root = MapFiles.root();
        if (root == null) return;
        for (var e : FOUND.long2ObjectEntrySet()) save(root, e.getLongKey(), e.getValue());
    }

    private static void save(Path root, long region, Long2ObjectOpenHashMap<List<Found>> byChunk) {
        Path file = file(root, region);
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
            MapFiles.write(file, bytes.toByteArray());
        } catch (IOException e) {
            MapMod.LOGGER.error("could not write the map file {}", file, e);
        }
    }
}
