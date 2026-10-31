package com.meakaandre.mapmod.map;

import com.meakaandre.mapmod.MapMod;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The map's worker thread: POI sources run here, off the server thread. */
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
}
