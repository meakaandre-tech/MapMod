package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import org.jspecify.annotations.Nullable;

import java.util.OptionalLong;

/**
 * GPU time of the map's own passes each frame (MM-4): a pair of the game's timestamp queries around them (they work on
 * any of its backends), read back a few frames later so nothing waits on the GPU. Where a pool cannot be made, the
 * queries fail, or no value ever comes back, it says it is unsupported and {@code /mapmod stats} shows "n/a". Render
 * thread only.
 */
public final class GpuTimer {
    /** Frames in flight: a pair is read back this many frames after it was written, at the earliest. */
    private static final int RING = 4;
    /** Frames a pair may stay unanswered before timings are taken to be unsupported. */
    private static final int GIVE_UP = 240;

    private @Nullable GpuQueryPool pool;
    private final boolean[] pending = new boolean[RING];
    private int slot, unanswered;
    /** null: not tried yet. */
    private @Nullable Boolean supported;
    private boolean forcedOff;

    /** True if timings can be had (a pool is made on first use). */
    public boolean supported() {
        if (forcedOff) return false;
        if (supported == null) {
            try {
                pool = RenderSystem.getDevice().createTimestampQueryPool(RING * 2);
                supported = true;
            } catch (RuntimeException | LinkageError e) {
                MapMod.LOGGER.info("no GPU timestamp queries ({}); the map's GPU time shows as n/a", e.toString());
                supported = false;
            }
        }
        return supported;
    }

    /** Switches timings off as if unsupported, or back on (for the selftests). */
    public void forceUnsupported(boolean off) {
        forcedOff = off;
    }

    void begin() {
        write(slot * 2);
    }

    void end() {
        if (write(slot * 2 + 1)) {
            pending[slot] = true;
            slot = (slot + 1) % RING;
        }
    }

    private boolean write(int index) {
        if (!supported() || pending[slot] || pool == null) return false;
        try {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, index);
            return true;
        } catch (RuntimeException e) {
            fail(e.toString());
            return false;
        }
    }

    /** The oldest finished pair's time in nanoseconds, or -1 if none has finished (or timings are unsupported). */
    long poll() {
        if (!supported() || pool == null) return -1;
        try {
            // oldest first: the slot to be written next holds the oldest pair
            for (int i = 0; i < RING; i++) {
                int s = (slot + i) % RING;
                if (!pending[s]) continue;
                OptionalLong t0 = pool.getValue(s * 2), t1 = pool.getValue(s * 2 + 1);
                if (t0.isEmpty() || t1.isEmpty()) {
                    if (++unanswered > GIVE_UP) fail("no answer in " + GIVE_UP + " frames");
                    return -1;
                }
                unanswered = 0;
                pending[s] = false;
                return Math.max(0, t1.getAsLong() - t0.getAsLong());
            }
        } catch (RuntimeException e) {
            fail(e.toString());
        }
        return -1;
    }

    private void fail(String why) {
        MapMod.LOGGER.warn("GPU timestamp queries failed ({}); the map's GPU time shows as n/a", why);
        supported = false;
        if (pool != null) pool.close();
        pool = null;
    }
}
