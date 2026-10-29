package com.meakaandre.mapmod.map;

import com.meakaandre.mapmod.MapMod;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** The map's worker thread: POI sources and the world generator's guesses run here, off the server thread. */
public final class MapWorker {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, MapMod.NAME + " worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    public static final Executor EXECUTOR = WORKER;

    private MapWorker() {
    }

    /** Runs a job on an executor, answering on the server thread (null if it failed). */
    public static <T> void async(MinecraftServer server, Executor executor, Supplier<T> job, Consumer<@Nullable T> then) {
        executor.execute(() -> {
            T result;
            try {
                result = job.get();
            } catch (RuntimeException e) {
                MapMod.LOGGER.warn("a background map job failed", e);
                result = null;
            }
            T done = result;
            server.execute(() -> then.accept(done));
        });
    }

    /**
     * Runs a long job in small pieces on the worker: each piece of {@code size} items is its own task, and the next is
     * queued only once it is done, so other jobs waiting for the worker run in between. {@code each} gets every piece's
     * answer on the server thread (null if it failed), and {@code done} runs there after the last.
     */
    public static <I, R> void batched(MinecraftServer server, List<I> items, int size, Function<List<I>, R> piece,
                                      Consumer<@Nullable R> each, Runnable done) {
        List<I> all = List.copyOf(items);
        step(server, all, 0, Math.max(1, size), piece, each, done);
    }

    private static <I, R> void step(MinecraftServer server, List<I> all, int from, int size, Function<List<I>, R> piece,
                                    Consumer<@Nullable R> each, Runnable done) {
        if (from >= all.size()) {
            server.execute(done);
            return;
        }
        WORKER.execute(() -> {
            R result;
            try {
                result = piece.apply(all.subList(from, Math.min(all.size(), from + size)));
            } catch (RuntimeException e) {
                MapMod.LOGGER.warn("a background map job failed", e);
                result = null;
            }
            R answer = result;
            server.execute(() -> each.accept(answer));
            step(server, all, from + size, size, piece, each, done);
        });
    }
}
