package com.meakaandre.mapmod.selftest;

import com.meakaandre.mapmod.MapMod;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * A selftest suite's output: every line goes to the log and to whoever ran the command, behind the suite's prefix
 * (such as {@code "SELFTEST map "}), and the checks are counted.
 */
public final class TestReport {
    private final String prefix;
    public int passed, failed;

    public TestReport(String prefix) {
        this.prefix = prefix;
    }

    /** Logs the prefixed line and sends it to {@code source} as command feedback. */
    public void report(CommandSourceStack source, String text) {
        String line = prefix + text;
        MapMod.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    /** One check: counts it and reports "PASS what" or "FAIL what", with the detail in brackets when there is one. */
    public void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    /**
     * Runs one part of a suite, now or from its queue: a part that throws is logged and counted as a failed check
     * ("FAIL part runs to the end"), so the rest of the suite still runs and its totals still print.
     */
    public void attempt(CommandSourceStack source, String part, Runnable checks) {
        try {
            checks.run();
        } catch (RuntimeException e) {
            MapMod.LOGGER.error("{}{} threw", prefix, part, e);
            expect(source, part + " runs to the end", false, e.toString());
        }
    }

    /** Starts the count again. */
    public void reset() {
        passed = failed = 0;
    }

    /** The count so far, as "N passed, M failed". */
    public String totals() {
        return passed + " passed, " + failed + " failed";
    }
}
