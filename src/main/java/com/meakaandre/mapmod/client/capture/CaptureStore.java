package com.meakaandre.mapmod.client.capture;

import com.meakaandre.mapmod.MapMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The tiles of one world and dimension ({@code mapmod/<server or world>/<dimension>/<x>_<z>.mmt}), held by the capture
 * worker: only the worker thread touches a store, so it needs no locks. Tiles are read from disk the first time a
 * sample or the player comes near them, written back only when dirty, and let go when the player is far away.
 */
final class CaptureStore {
    final Path dir;
    private final Long2ObjectOpenHashMap<SurfaceTile> tiles = new Long2ObjectOpenHashMap<>();
    boolean closed;

    CaptureStore(Path dir) {
        this.dir = dir;
    }

    static long key(int tx, int tz) {
        return (long) tx << 32 | tz & 0xFFFFFFFFL;
    }

    static int keyX(long key) {
        return (int) (key >> 32);
    }

    static int keyZ(long key) {
        return (int) key;
    }

    private Path file(int tx, int tz) {
        return dir.resolve(tx + "_" + tz + ".mmt");
    }

    int cached() {
        return tiles.size();
    }

    int dirty() {
        int n = 0;
        for (SurfaceTile t : tiles.values()) if (t.dirty) n++;
        return n;
    }

    @Nullable SurfaceTile peek(int tx, int tz) {
        return tiles.get(key(tx, tz));
    }

    /** The tile, read from disk if it is there, else a new empty one. */
    SurfaceTile tile(int tx, int tz) {
        long k = key(tx, tz);
        SurfaceTile t = tiles.get(k);
        if (t != null) return t;
        t = read(tx, tz);
        if (t == null) t = new SurfaceTile(tx, tz);
        tiles.put(k, t);
        return t;
    }

    /** Reads a tile that is on disk but not held yet (nothing if there is no file). */
    void loadIfSaved(int tx, int tz) {
        long k = key(tx, tz);
        if (tiles.containsKey(k)) return;
        SurfaceTile t = read(tx, tz);
        if (t != null) tiles.put(k, t);
    }

    private @Nullable SurfaceTile read(int tx, int tz) {
        Path f = file(tx, tz);
        if (!Files.isRegularFile(f)) return null;
        try {
            SurfaceTile t = SurfaceTile.unpack(tx, tz, Files.readAllBytes(f));
            Capture.STATS.loaded.incrementAndGet();
            return t;
        } catch (IOException e) {
            // an old format or a broken file: dropped, and captured again as the player explores
            MapMod.LOGGER.warn("dropping unreadable map tile {}: {}", f, e.getMessage());
            return null;
        }
    }

    void put(SurfaceSampler.Samples s) {
        tile(SurfaceTile.tileOfChunk(s.cx), SurfaceTile.tileOfChunk(s.cz)).put(s);
    }

    /** Writes every dirty tile out. */
    void saveDirty() {
        for (SurfaceTile t : tiles.values()) if (t.dirty) save(t);
    }

    private void save(SurfaceTile t) {
        Path f = file(t.tileX, t.tileZ);
        try {
            Files.createDirectories(dir);
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.write(tmp, t.pack());
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            t.dirty = false;
            Capture.STATS.saved.incrementAndGet();
        } catch (IOException e) {
            MapMod.LOGGER.warn("could not save map tile {}", f, e);
        }
    }

    /** Keeps the tiles within {@code keep} tiles of the player's (saving dirty ones before letting go), and reads in
     * the saved ones within {@code load}. */
    void around(int ptx, int ptz, int load, int keep) {
        LongArrayList far = new LongArrayList();
        for (var e : tiles.long2ObjectEntrySet()) {
            int tx = keyX(e.getLongKey()), tz = keyZ(e.getLongKey());
            if (Math.max(Math.abs(tx - ptx), Math.abs(tz - ptz)) > keep) far.add(e.getLongKey());
        }
        for (long k : far) {
            SurfaceTile t = tiles.remove(k);
            if (t != null && t.dirty) save(t);
        }
        for (int dz = -load; dz <= load; dz++) for (int dx = -load; dx <= load; dx++) loadIfSaved(ptx + dx, ptz + dz);
    }

    void close() {
        if (closed) return;
        saveDirty();
        tiles.clear();
        closed = true;
    }
}
