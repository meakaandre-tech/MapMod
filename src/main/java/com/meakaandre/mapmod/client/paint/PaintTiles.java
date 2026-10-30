package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.Capture;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The tiles' data the map needs right now (MM-4): read on demand, the capture's copy first (it may be newer than the
 * file), else the file on a background thread, and let go again once baked. What exists and how new it is lives in the
 * {@link TileIndex}: filled from the folder's listing and the capture's held tiles, then kept up by the capture's
 * change events ({@link Capture#onTileChanged}), with no polling. A tile whose data is older than the index says is read
 * again the next time it is wanted. Render thread only, except the reads, which hand their results back through a queue.
 */
final class PaintTiles {
    /** Tiles' data held at most beyond those wanted this frame (about 115 KB each). */
    private static final int KEEP = 48;

    /** Told when the map's data for a tile changes: read from disk, or taken from the capture. */
    interface DataListener {
        void loaded(int tx, int tz, long revision, boolean fromDisk);
    }

    /** One tile's data: null where there is none; {@code revision} is the one it was read at. */
    static final class Entry {
        final int tx, tz;
        @Nullable SurfaceTile data;
        long revision = -1;
        /** The revision the last read from disk had (see {@link #update}). */
        long lastDisk = Long.MIN_VALUE;
        boolean loading;
        long used;

        Entry(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
        }
    }

    /** A tile's data and the nine revisions it and its neighbours were read at (see {@link TileIndex#key3x3}). */
    record Around(@Nullable SurfaceTile[][] tiles, long key) {
    }

    private sealed interface Arrived permits Data, Listing {
    }

    private record Data(long key, @Nullable SurfaceTile tile, boolean fromDisk) implements Arrived {
    }

    /** Revisions from the listing (disk: only where nothing newer is known) or from the capture (always newest). */
    private record Listing(Long2LongOpenHashMap revisions, boolean fromCapture) implements Arrived {
    }

    final TileIndex index;
    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Arrived> arrived = new ConcurrentLinkedQueue<>();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, MapMod.NAME + " map reader");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    final @Nullable Path dir;
    private final List<DataListener> listeners = new ArrayList<>();
    private volatile boolean closed;
    private long frame;
    /** Tiles read from disk and taken from the capture, and the disk-load events fired. */
    final AtomicInteger read = new AtomicInteger(), fromCapture = new AtomicInteger();
    int diskEvents;

    PaintTiles(TileIndex index, @Nullable Path dir) {
        this.index = index;
        this.dir = dir;
    }

    void onLoaded(DataListener l) {
        listeners.add(l);
    }

    static long key(int tx, int tz) {
        return TileIndex.key(tx, tz);
    }

    @Nullable Entry get(int tx, int tz) {
        return entries.get(key(tx, tz));
    }

    int size() {
        return entries.size();
    }

    /** Lists the tile folder and asks the capture what it holds; both land in the index as they arrive. */
    void scan() {
        Capture.heldRevisions().whenComplete((held, error) -> {
            if (!closed && held != null) arrived.add(new Listing(held, true));
        });
        if (dir == null) return;
        Path d = dir;
        try {
            reader.execute(() -> {
                Long2LongOpenHashMap found = new Long2LongOpenHashMap();
                if (Files.isDirectory(d)) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(d, "*.mmt")) {
                        for (Path f : files) {
                            if (closed) return;
                            String name = f.getFileName().toString();
                            int us = name.indexOf('_');
                            if (us <= 0) continue;
                            try {
                                int tx = Integer.parseInt(name.substring(0, us)), tz = Integer.parseInt(name.substring(us + 1, name.length() - 4));
                                found.put(key(tx, tz), Capture.fileRevision(f));
                            } catch (NumberFormatException e) {
                                // not a tile
                            }
                        }
                    } catch (IOException e) {
                        MapMod.LOGGER.warn("could not list the map tiles in {}", d, e);
                    }
                }
                arrived.add(new Listing(found, false));
            });
        } catch (RejectedExecutionException e) {
            // closed meanwhile
        }
    }

    /** The capture announced a change: the index moves on, and the data held is read again when next wanted. */
    void captureChanged(int tx, int tz, long revision) {
        index.set(tx, tz, revision);
    }

    /** Takes in what has arrived since the last frame. */
    void update(long frame) {
        this.frame = frame;
        Arrived a;
        while ((a = arrived.poll()) != null) {
            if (a instanceof Listing l) {
                for (var e : l.revisions.long2LongEntrySet()) {
                    int tx = (int) (e.getLongKey() >> 32), tz = (int) e.getLongKey();
                    // a file is only as new as what the capture said; the capture's own copy is the newest there is
                    if (l.fromCapture || index.version(tx, tz) == 0) index.set(tx, tz, e.getLongValue());
                }
                continue;
            }
            Data d = (Data) a;
            Entry e = entries.get(d.key);
            if (e == null) continue;
            e.loading = false;
            int tx = e.tx, tz = e.tz;
            if (d.tile == null) {
                // nothing anywhere: whatever the index thought, there is no data to bake
                e.data = null;
                e.revision = index.version(tx, tz);
                continue;
            }
            e.data = d.tile;
            e.revision = d.tile.revision;
            long known = index.version(tx, tz);
            // newer than the index knew: it moves on. Older (a change came while this was read): read again, unless the
            // file came back the same twice, when it is all there is (the capture let the tile go, and the file's time
            // is coarser than the capture's)
            if (d.tile.revision > known || d.fromDisk && d.tile.revision == e.lastDisk) index.set(tx, tz, d.tile.revision);
            if (d.fromDisk) e.lastDisk = d.tile.revision;
            if (d.fromDisk) diskEvents++;
            for (DataListener l : listeners) l.loaded(tx, tz, d.tile.revision, d.fromDisk);
        }
    }

    /**
     * True if the tile's data is in and as new as the index says (or there is no tile); else it is asked for and this
     * is false. Marks it used this frame.
     */
    boolean ready(int tx, int tz) {
        long v = index.version(tx, tz);
        long k = key(tx, tz);
        Entry e = entries.get(k);
        if (v == 0 && e == null) return true;
        if (e == null) entries.put(k, e = new Entry(tx, tz));
        e.used = frame;
        if (e.loading) return false;
        if (e.revision == v) return true;
        load(e);
        return false;
    }

    /** The tile and its eight neighbours if all are ready ({@link #ready}), else null (and the missing ones asked for). */
    @Nullable Around around(int tx, int tz) {
        boolean all = true;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) all &= ready(tx + dx, tz + dz);
        if (!all) return null;
        SurfaceTile[][] tiles = new SurfaceTile[3][3];
        long[] r = new long[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Entry e = entries.get(key(tx + dx, tz + dz));
                SurfaceTile t = e == null ? null : e.data;
                tiles[dz + 1][dx + 1] = t;
                r[(dz + 1) * 3 + dx + 1] = index.version(tx + dx, tz + dz);
            }
        }
        return new Around(tiles, TileIndex.key3x3(r));
    }

    /** Loading now (for the stats). */
    int loading() {
        int n = 0;
        for (Entry e : entries.values()) if (e.loading) n++;
        return n;
    }

    private void load(Entry e) {
        e.loading = true;
        long k = key(e.tx, e.tz);
        int tx = e.tx, tz = e.tz;
        Capture.held(tx, tz, Long.MIN_VALUE).whenComplete((tile, error) -> {
            if (closed) return;
            if (tile != null) {
                fromCapture.incrementAndGet();
                arrived.add(new Data(k, tile, false));
                return;
            }
            try {
                reader.execute(() -> {
                    if (!closed) arrived.add(new Data(k, readFile(tx, tz), true));
                });
            } catch (RejectedExecutionException ex) {
                // closed meanwhile
            }
        });
    }

    private @Nullable SurfaceTile readFile(int tx, int tz) {
        if (dir == null) return null;
        Path f = dir.resolve(tx + "_" + tz + ".mmt");
        if (!Files.isRegularFile(f)) return null;
        try {
            long revision = Capture.fileRevision(f);
            SurfaceTile t = SurfaceTile.unpack(tx, tz, Files.readAllBytes(f));
            t.revision = revision;
            read.incrementAndGet();
            return t;
        } catch (IOException e) {
            // an old format or a file being replaced: the capture's copy or the next look will do
            return null;
        }
    }

    /** Lets go of the data not wanted this frame or the last, oldest first, beyond {@link #KEEP} tiles. */
    void trim() {
        if (entries.size() <= KEEP) return;
        List<Entry> old = new ArrayList<>();
        for (Entry e : entries.values()) if (!e.loading && e.used < frame - 1) old.add(e);
        old.sort((a, b) -> Long.compare(a.used, b.used));
        for (int i = 0; i < old.size() && entries.size() > KEEP; i++) entries.remove(key(old.get(i).tx, old.get(i).tz));
    }

    void close() {
        closed = true;
        reader.shutdownNow();
        entries.clear();
        arrived.clear();
    }
}
