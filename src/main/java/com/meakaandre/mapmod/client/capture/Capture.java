package com.meakaandre.mapmod.client.capture;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.map.ClientBlockHook;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Client-side surface capture: every chunk the client loads, and every chunk whose surface changes, is copied on the
 * client thread (a few sections, within a small per-tick time budget) and sampled, classified and stored into tiles on
 * one background worker, which also reads and writes the tile files. Works on any server, with or without MapMod on
 * it, and with any world generator.
 */
public final class Capture {
    /** Client-thread time per tick spent copying chunks, at most. */
    private static final long BUDGET_NS = 500_000;
    /** Chunks copied per tick at most, and jobs waiting on the worker before copying pauses. */
    private static final int PER_TICK = 8, MAX_QUEUE = 96;
    /** Ticks after a surface change before the chunk is sampled again (changes in between are taken together). */
    private static final int DEBOUNCE_TICKS = 20;
    /** Ticks between keeping the tiles round the player, and between saves of dirty tiles. */
    private static final int AROUND_TICKS = 20, SAVE_TICKS = 600;
    /** Saved tiles read in round the player's tile on joining and as they move. */
    private static final int LOAD_RADIUS = 1;

    static final Stats STATS = new Stats();

    private static final LinkedBlockingQueue<Runnable> QUEUE = new LinkedBlockingQueue<>();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, QUEUE, r -> {
        Thread thread = new Thread(r, MapMod.NAME + " capture");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        return thread;
    });

    /** Chunks to copy, oldest first, and chunks whose surface changed, with the tick they are due. Client thread. */
    private static final LongLinkedOpenHashSet PENDING = new LongLinkedOpenHashSet();
    private static final Long2IntOpenHashMap DUE = new Long2IntOpenHashMap();
    private static @Nullable ClientLevel level;
    private static @Nullable CaptureStore store;
    private static int ticks;
    private static long rateAtMs, rateAtSamples;
    private static long tickMaxNs;
    private static boolean stopped;

    /** Counters the worker and the client thread share. */
    static final class Stats {
        final AtomicLong samples = new AtomicLong(), loaded = new AtomicLong(), saved = new AtomicLong();
        volatile int cached, dirty;
        volatile double samplesPerSecond;
        volatile long tickAvgNs, tickMaxNs;
        volatile int commandRuns;
    }

    /** What {@code /mapmod stats} shows. */
    public record Snapshot(int tilesCached, int dirtyTiles, int workerQueue, int chunksWaiting, double samplesPerSecond,
                           long samples, long tilesLoaded, long tilesSaved, long tickAvgMicros, long tickMaxMicros,
                           @Nullable Path dir) {
    }

    private Capture() {
    }

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register(Capture::loaded);
        ClientChunkEvents.CHUNK_UNLOAD.register((lvl, chunk) -> {
            if (lvl != level) return;
            long k = key(chunk.getPos().x(), chunk.getPos().z());
            PENDING.remove(k);
            DUE.remove(k);
        });
        ClientTickEvents.END_CLIENT_TICK.register(Capture::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(Capture::leave));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stop());
        Runtime.getRuntime().addShutdownHook(new Thread(Capture::stop, MapMod.NAME + " capture shutdown"));
        ClientBlockHook.listener = Capture::blockChanged;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal(MapMod.ID)
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stats").executes(c -> {
                    for (String line : statsLines()) c.getSource().sendFeedback(Component.literal(line));
                    STATS.commandRuns++;
                    return 1;
                }))));
    }

    // ------------------------------------------------------------------------------------------------ client thread

    /** A chunk's key: x in the low 32 bits, z in the high. */
    private static long key(int cx, int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    private static void loaded(ClientLevel lvl, LevelChunk chunk) {
        follow(lvl);
        PENDING.add(key(chunk.getPos().x(), chunk.getPos().z()));
    }

    /** A block changed in a client chunk: if it is at the surface, the chunk is sampled again shortly. */
    private static void blockChanged(LevelChunk chunk, BlockPos pos) {
        if (chunk.getLevel() != level) return;
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX() & 15, pos.getZ() & 15);
        if (pos.getY() < top - 2) return;
        long k = key(chunk.getPos().x(), chunk.getPos().z());
        if (!DUE.containsKey(k)) DUE.put(k, ticks + DEBOUNCE_TICKS);
    }

    /** Starts capturing into the store for this level (closing the last one's) if it is not the one in use. */
    private static void follow(ClientLevel lvl) {
        if (lvl == level || stopped) return;
        closeStore();
        level = lvl;
        Path dir = dirFor(Minecraft.getInstance(), lvl);
        CaptureStore fresh = new CaptureStore(dir);
        store = fresh;
        submit(fresh, s -> SurfaceClass.clearCache());
        MapMod.LOGGER.info("capturing the map into {}", dir);
    }

    private static void leave() {
        closeStore();
        level = null;
    }

    private static void closeStore() {
        CaptureStore old = store;
        store = null;
        PENDING.clear();
        DUE.clear();
        if (old != null) {
            submit(old, s -> {
                s.close();
                STATS.cached = 0;
                STATS.dirty = 0;
            });
        }
    }

    /** Game closing: dirty tiles are written before the worker stops (a few seconds at most). */
    private static synchronized void stop() {
        if (stopped) return;
        stopped = true;
        CaptureStore old = store;
        store = null;
        if (old != null) submit(old, CaptureStore::close);
        WORKER.shutdown();
        try {
            WORKER.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void tick(Minecraft mc) {
        ClientLevel lvl = mc.level;
        if (lvl == null || stopped) return;
        long t0 = System.nanoTime();
        follow(lvl);
        ticks++;
        if (!DUE.isEmpty()) {
            var it = DUE.long2IntEntrySet().iterator();
            while (it.hasNext()) {
                Long2IntMap.Entry e = it.next();
                if (e.getIntValue() <= ticks) {
                    PENDING.add(e.getLongKey());
                    it.remove();
                }
            }
        }
        CaptureStore st = store;
        int copied = 0;
        while (st != null && !PENDING.isEmpty() && copied < PER_TICK && QUEUE.size() < MAX_QUEUE && System.nanoTime() - t0 < BUDGET_NS) {
            long k = PENDING.removeFirstLong();
            int cx = (int) k, cz = (int) (k >> 32);
            if (!lvl.getChunkSource().hasChunk(cx, cz)) continue;
            ChunkSnapshot snap = ChunkSnapshot.of(lvl, lvl.getChunk(cx, cz));
            copied++;
            if (snap == null) continue;
            submit(st, s -> {
                s.put(SurfaceSampler.sample(snap));
                STATS.samples.addAndGet(16);
            });
        }
        if (st != null && mc.player != null && ticks % AROUND_TICKS == 0) {
            int ptx = SurfaceTile.tileOfBlock(mc.player.getBlockX()), ptz = SurfaceTile.tileOfBlock(mc.player.getBlockZ());
            int keep = Math.max(2, mc.options.getEffectiveRenderDistance() / SurfaceTile.CHUNKS + 2);
            submit(st, s -> s.around(ptx, ptz, LOAD_RADIUS, keep));
        }
        if (st != null && ticks % SAVE_TICKS == 0) submit(st, CaptureStore::saveDirty);
        long spent = System.nanoTime() - t0;
        tickMaxNs = Math.max(tickMaxNs, spent);
        STATS.tickAvgNs = STATS.tickAvgNs == 0 ? spent : (STATS.tickAvgNs * 31 + spent) / 32;
        if (ticks % 20 == 0) {
            long now = System.currentTimeMillis(), samples = STATS.samples.get();
            if (rateAtMs != 0) STATS.samplesPerSecond = (samples - rateAtSamples) * 1000.0 / Math.max(1, now - rateAtMs);
            rateAtMs = now;
            rateAtSamples = samples;
            STATS.tickMaxNs = tickMaxNs;
            tickMaxNs = 0;
        }
    }

    // ------------------------------------------------------------------------------------------------ worker

    private static void submit(CaptureStore st, Consumer<CaptureStore> job) {
        try {
            WORKER.execute(() -> {
                if (st.closed) return;
                try {
                    job.accept(st);
                } catch (RuntimeException e) {
                    MapMod.LOGGER.warn("a map capture job failed", e);
                }
                if (!st.closed) {
                    STATS.cached = st.cached();
                    STATS.dirty = st.dirty();
                }
            });
        } catch (RejectedExecutionException e) {
            // the game is closing
        }
    }

    private static <T> CompletableFuture<@Nullable T> ask(Function<CaptureStore, T> job) {
        CompletableFuture<@Nullable T> f = new CompletableFuture<>();
        CaptureStore st = store;
        if (st == null) {
            f.complete(null);
            return f;
        }
        try {
            WORKER.execute(() -> {
                try {
                    f.complete(st.closed ? null : job.apply(st));
                } catch (RuntimeException e) {
                    f.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            f.complete(null);
        }
        return f;
    }

    // ------------------------------------------------------------------------------------------------ for others

    /** Writes every dirty tile now; completes on the worker once they are written. */
    public static CompletableFuture<@Nullable Boolean> flush() {
        return ask(s -> {
            s.saveDirty();
            STATS.dirty = 0;
            return true;
        });
    }

    /**
     * A copy of a tile as the worker holds it (read from disk if needed), or null if it holds nothing there and there
     * is no file. Safe to keep and read on any thread; compare {@link SurfaceTile#revision} to see if it changed.
     */
    public static CompletableFuture<@Nullable SurfaceTile> tile(int tileX, int tileZ) {
        return ask(s -> {
            SurfaceTile t = s.peek(tileX, tileZ);
            if (t == null) {
                s.loadIfSaved(tileX, tileZ);
                t = s.peek(tileX, tileZ);
            }
            return t == null ? null : t.copy();
        });
    }

    /**
     * A copy of a tile the worker holds right now, if its revision is not {@code knownRevision}; null if it holds none
     * there or it has not changed. Never reads the disk and never makes the worker hold a tile it did not, so a map
     * reading saved tiles itself leaves the capture's own radius as it is.
     */
    public static CompletableFuture<@Nullable SurfaceTile> held(int tileX, int tileZ, int knownRevision) {
        return ask(s -> {
            SurfaceTile t = s.peek(tileX, tileZ);
            return t == null || t.revision == knownRevision ? null : t.copy();
        });
    }

    /** Lines other parts of the client add to {@code /mapmod stats}. */
    private static final List<Supplier<List<String>>> MORE_STATS = new ArrayList<>();

    /** Adds lines to {@code /mapmod stats} (client thread). */
    public static void addStats(Supplier<List<String>> lines) {
        MORE_STATS.add(lines);
    }

    /** Where the tiles of the world being played are kept (null when not in one). */
    public static @Nullable Path dir() {
        CaptureStore st = store;
        return st == null ? null : st.dir;
    }

    public static Snapshot stats() {
        return new Snapshot(STATS.cached, STATS.dirty, QUEUE.size(), PENDING.size() + DUE.size(), STATS.samplesPerSecond,
            STATS.samples.get(), STATS.loaded.get(), STATS.saved.get(), STATS.tickAvgNs / 1000, STATS.tickMaxNs / 1000, dir());
    }

    /** How many times {@code /mapmod stats} has run (for the selftests). */
    public static int statsCommandRuns() {
        return STATS.commandRuns;
    }

    public static List<String> statsLines() {
        Snapshot s = stats();
        List<String> lines = new ArrayList<>();
        lines.add(MapMod.NAME + " capture");
        lines.add(String.format("  tiles cached %d, dirty %d (loaded %d, saved %d)", s.tilesCached(), s.dirtyTiles(), s.tilesLoaded(), s.tilesSaved()));
        lines.add(String.format("  worker queue %d, chunks waiting %d", s.workerQueue(), s.chunksWaiting()));
        lines.add(String.format("  samples/s %.0f (%d total)", s.samplesPerSecond(), s.samples()));
        lines.add(String.format("  client thread %d us/tick avg, %d us max", s.tickAvgMicros(), s.tickMaxMicros()));
        if (s.dir() != null) lines.add("  " + Minecraft.getInstance().gameDirectory.toPath().relativize(s.dir()));
        for (Supplier<List<String>> more : MORE_STATS) lines.addAll(more.get());
        return lines;
    }

    // ------------------------------------------------------------------------------------------------ files

    /** {@code mapmod/<server address or world folder>/<dimension namespace>/<dimension path>} in the game folder. */
    static Path dirFor(Minecraft mc, ClientLevel lvl) {
        String world;
        IntegratedServer local = mc.getSingleplayerServer();
        if (local != null) {
            Path root = local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            world = root.getFileName() == null ? "world" : root.getFileName().toString();
        } else {
            ServerData server = mc.getCurrentServer();
            world = server == null ? "unknown" : server.ip;
        }
        Identifier dim = lvl.dimension().identifier();
        Path dir = mc.gameDirectory.toPath().resolve(MapMod.ID).resolve(safe(world)).resolve(safe(dim.getNamespace()));
        for (String part : dim.getPath().split("/")) dir = dir.resolve(safe(part));
        return dir;
    }

    private static String safe(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() || s.equals(".") || s.equals("..") ? "_" : s;
    }
}
