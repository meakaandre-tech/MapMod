package com.meakaandre.mapmod.selftest;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Work a selftest suite puts off: each entry runs at the end of the first server tick at which the overworld's game
 * time has reached the entry's time. A suite keeps one queue and hooks it up where it registers its other listeners,
 * so the listeners keep their order among the mod's own: {@link #register} for a plain queue, or its own listener
 * calling {@link #runDue} and {@link #clear} when the suite does more on a tick or on a stop.
 */
public final class TestQueue {
    private record Later(long at, Runnable run) {
    }

    private final List<Later> entries = new ArrayList<>();

    /** Runs {@code run} at the end of the first server tick at which the overworld's game time is {@code at} or later. */
    public void add(long at, Runnable run) {
        entries.add(new Later(at, run));
    }

    /**
     * Waits for a condition instead of a fixed time: from game time {@code now} on, {@code ready} is asked at the end of
     * every server tick, and {@code then} runs at the first one at which it holds, or once {@code timeout} ticks have gone
     * by without it. {@code then} is told which: true if the condition held.
     */
    public void when(long now, BooleanSupplier ready, long timeout, Consumer<Boolean> then) {
        poll(now, now + timeout, ready, then);
    }

    private void poll(long at, long deadline, BooleanSupplier ready, Consumer<Boolean> then) {
        add(at, () -> {
            boolean ok = ready.getAsBoolean();
            if (ok || at >= deadline) then.accept(ok);
            else poll(at + 1, deadline, ready, then);
        });
    }

    /** Registers the plain listeners: due entries run at the end of every server tick, and a stopped server drops all. */
    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> runDue(server.overworld().getGameTime()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    /** Runs every entry due by {@code now}; see {@link #runDue(long, Consumer)}. */
    public void runDue(long now) {
        runDue(now, Runnable::run);
    }

    /**
     * Runs every entry due by {@code now} through {@code runner}, in the order they were added. Each is taken off the
     * queue before it runs; entries added while these run wait for a later tick.
     */
    public void runDue(long now, Consumer<Runnable> runner) {
        for (Later l : new ArrayList<>(entries)) {
            if (l.at > now) continue;
            entries.remove(l);
            runner.accept(l.run);
        }
    }

    /** Drops every entry. */
    public void clear() {
        entries.clear();
    }
}
