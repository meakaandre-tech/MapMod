package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.Capture;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The tiles the open map shows, held for as long as it is open: saved ones are read from disk on a background thread,
 * the ones the capture holds right now are taken from it (newer than their file), and both are asked again now and then
 * so the map follows what the player changes. Everything here is touched on the render thread only, except the reads,
 * which hand their tiles back through a queue. {@link #close()} lets everything go.
 */
final class PaintTiles {
    /** Milliseconds between asking the capture whether a shown tile changed. */
    private static final long REFRESH_MS = 1000;

    /** One tile: its data (null where nothing was ever captured), and a stamp that changes whenever the data does. */
    static final class Entry {
        final int tx, tz;
        @Nullable SurfaceTile data;
        int revision = -1;
        /** A number no other data of any tile had: the GPU copy is redone when its own or a neighbour's stamp moves. */
        long stamp;
        /** Asked and not answered yet; resolved once the first answer (data or none) is in. */
        boolean loading, resolved;
        long askedAt;

        Entry(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
        }
    }

    private record Arrived(long key, @Nullable SurfaceTile tile) {
    }

    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Arrived> arrived = new ConcurrentLinkedQueue<>();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, MapMod.NAME + " map reader");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final @Nullable Path dir = Capture.dir();
    private long stamps;
    private volatile boolean closed;
    /** Tiles read from disk and taken from the capture since the map opened. */
    final AtomicInteger read = new AtomicInteger(), fromCapture = new AtomicInteger();

    static long key(int tx, int tz) {
        return (long) tx << 32 | tz & 0xFFFFFFFFL;
    }

    @Nullable Entry get(int tx, int tz) {
        return entries.get(key(tx, tz));
    }

    int size() {
        return entries.size();
    }

    /**
     * Takes in what has arrived, starts reading the tiles in [x0, x1] x [z0, z1] that are not held yet, asks the
     * capture again about the held ones now and then, and lets go of those well outside it. Returns true if any tile's
     * data changed.
     */
    boolean update(int x0, int z0, int x1, int z1, int keepMargin) {
        boolean changed = false;
        Arrived a;
        while ((a = arrived.poll()) != null) {
            Entry e = entries.get(a.key);
            if (e == null) continue;
            e.loading = false;
            e.resolved = true;
            // no file, or the capture's copy has not changed
            if (a.tile == null) continue;
            e.data = a.tile;
            e.revision = a.tile.revision;
            e.stamp = ++stamps;
            changed = true;
        }
        long now = System.currentTimeMillis();
        for (int tz = z0; tz <= z1; tz++) {
            for (int tx = x0; tx <= x1; tx++) {
                long k = key(tx, tz);
                Entry e = entries.get(k);
                if (e == null) {
                    e = new Entry(tx, tz);
                    entries.put(k, e);
                    load(e);
                } else if (!e.loading && now - e.askedAt >= REFRESH_MS) {
                    refresh(e);
                }
            }
        }
        LongArrayList far = new LongArrayList();
        for (Entry e : entries.values()) {
            if (e.tx < x0 - keepMargin || e.tx > x1 + keepMargin || e.tz < z0 - keepMargin || e.tz > z1 + keepMargin) far.add(key(e.tx, e.tz));
        }
        for (long k : far) entries.remove(k);
        return changed;
    }

    /** First the capture's copy (it may be newer than the file), else the file. */
    private void load(Entry e) {
        e.loading = true;
        e.askedAt = System.currentTimeMillis();
        long k = key(e.tx, e.tz);
        Capture.held(e.tx, e.tz, -1).whenComplete((tile, error) -> {
            if (closed) return;
            if (tile != null) {
                fromCapture.incrementAndGet();
                arrived.add(new Arrived(k, tile));
                return;
            }
            try {
                reader.execute(() -> {
                    if (!closed) arrived.add(new Arrived(k, readFile(e.tx, e.tz)));
                });
            } catch (RejectedExecutionException ex) {
                // the map closed meanwhile
            }
        });
    }

    private void refresh(Entry e) {
        e.loading = true;
        e.askedAt = System.currentTimeMillis();
        long k = key(e.tx, e.tz);
        Capture.held(e.tx, e.tz, e.revision).whenComplete((tile, error) -> {
            if (closed) return;
            if (tile != null) fromCapture.incrementAndGet();
            arrived.add(new Arrived(k, tile));
        });
    }

    private @Nullable SurfaceTile readFile(int tx, int tz) {
        if (dir == null) return null;
        Path f = dir.resolve(tx + "_" + tz + ".mmt");
        if (!Files.isRegularFile(f)) return null;
        try {
            SurfaceTile t = SurfaceTile.unpack(tx, tz, Files.readAllBytes(f));
            read.incrementAndGet();
            return t;
        } catch (IOException e) {
            // an old format or a file being replaced: the capture's copy or the next look will do
            return null;
        }
    }

    /** True while the tile or one of its eight neighbours is still being read for the first time. */
    boolean settling(int tx, int tz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Entry e = entries.get(key(tx + dx, tz + dz));
                if (e != null && !e.resolved) return true;
            }
        }
        return false;
    }

    void close() {
        closed = true;
        reader.shutdownNow();
        entries.clear();
        arrived.clear();
    }
}
