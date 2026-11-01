package com.meakaandre.mapmod.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A short note in the top right corner of the screen (MM-8c: "Waypoint added (Waypoint 3)", "Map locked"), over the game
 * and over the fullscreen map alike. It shows for a couple of seconds and fades; a new one replaces it.
 */
public final class MapToast {
    /** How long a note shows, and how long of that it takes to fade at the end. */
    private static final long SHOW_NANOS = 2_500_000_000L, FADE_NANOS = 500_000_000L;
    private static final int PAD = 5, MARGIN = 6, LINE = 9;

    private static @Nullable Component text;
    private static long shownAt;
    /** Notes shown since the game started (for the selftests). */
    public static long shown;

    private MapToast() {
    }

    /** Shows a note, replacing the one showing. */
    public static void show(Component note) {
        text = note;
        shownAt = System.nanoTime();
        shown++;
    }

    /** The note showing now, or null (for the selftests). */
    public static @Nullable Component current() {
        Component t = text;
        if (t != null && System.nanoTime() - shownAt > SHOW_NANOS) text = t = null;
        return t;
    }

    /** Draws the note showing, if any, in the top right corner of a screen this many GUI pixels wide. */
    public static void draw(GuiGraphicsExtractor g, int screenWidth) {
        Component t = current();
        if (t == null) return;
        long age = System.nanoTime() - shownAt;
        float alpha = age < SHOW_NANOS - FADE_NANOS ? 1f : Math.max(0f, (SHOW_NANOS - age) / (float) FADE_NANOS);
        int a = Math.round(alpha * 255);
        if (a < 8) return;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.font.width(t) + 2 * PAD, h = LINE + 2 * PAD - 2;
        int x = screenWidth - w - MARGIN, y = MARGIN + 14;
        g.fill(x, y, x + w, y + h, (Math.round(alpha * 0xE0) << 24) | 0x101418);
        g.fill(x, y, x + 2, y + h, (a << 24) | 0xFFA530);
        g.text(mc.font, t, x + PAD, y + PAD, (a << 24) | 0xE6E6E6, false);
    }
}
