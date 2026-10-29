package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapGlyphs;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Draws the map's glyphs ({@link MapGlyphs}), from one texture made when first needed (no image files): white shapes
 * with a dark outline, tinted when drawn. Each glyph is 9 by 9 in a cell of 11 by 11. Glyphs registered later remake it.
 */
public final class MapIcons {
    public static final int CELL = 11, SIZE = MapGlyphs.SIZE;
    private static final int COLUMNS = 16;
    /** Width of the round minimap's ring (pixels). */
    private static final int RING_WIDTH = 3;
    private static final Identifier ID = MapMod.id("map/icons");
    /** Glyphs in the texture as made; -1 before it is made. */
    private static int made = -1;
    private static int rows = 1;

    private MapIcons() {
    }

    private static void make() {
        int count = MapGlyphs.count();
        if (made == count) return;
        if (made >= 0) Minecraft.getInstance().getTextureManager().release(ID);
        made = count;
        rows = (count + COLUMNS - 1) / COLUMNS;
        NativeImage image = new NativeImage(COLUMNS * CELL, rows * CELL, true);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setPixel(x, y, 0);
        for (int i = 0; i < count; i++) {
            int ox = (i % COLUMNS) * CELL, oy = (i / COLUMNS) * CELL;
            // the outline first: every clear pixel next to a set one
            for (int y = -1; y <= SIZE; y++) {
                for (int x = -1; x <= SIZE; x++) {
                    if (MapGlyphs.set(i, x, y)) continue;
                    boolean edge = false;
                    for (int dy = -1; dy <= 1 && !edge; dy++) for (int dx = -1; dx <= 1 && !edge; dx++) edge = MapGlyphs.set(i, x + dx, y + dy);
                    if (edge) image.setPixel(ox + 1 + x, oy + 1 + y, 0xB0101010);
                }
            }
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) if (MapGlyphs.set(i, x, y)) image.setPixel(ox + 1 + x, oy + 1 + y, 0xFFFFFFFF);
        }
        Minecraft.getInstance().getTextureManager().register(ID, new DynamicTexture(() -> MapMod.ID + " map icons", image));
    }

    private static final Identifier RING = MapMod.id("map/ring");
    private static int ringMade = -1;

    /**
     * The round minimap's ring round a disc of diameter {@code d} whose box starts at x, y: a dark band with a faint
     * light edge inside it, smoothed at its rims.
     */
    public static void ring(GuiGraphicsExtractor g, int x, int y, int d) {
        int size = d + 2 * RING_WIDTH;
        if (ringMade != d) {
            if (ringMade >= 0) Minecraft.getInstance().getTextureManager().release(RING);
            ringMade = d;
            NativeImage image = new NativeImage(size, size, true);
            double c = size / 2.0, inner = d / 2.0, outer = inner + RING_WIDTH;
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    double r = Math.hypot(px + 0.5 - c, py + 0.5 - c);
                    int argb = 0;
                    if (r >= inner - 1 && r <= outer) {
                        // soft rims: the alpha fades over the outermost half pixel and the innermost
                        double a = Math.min(1, Math.min(outer - r + 0.5, r - inner + 1.5));
                        int rgb = r < inner ? 0x9AA4AE : 0x0E1216;
                        int alpha = (int) Math.round(Math.clamp(a, 0, 1) * (r < inner ? 0x70 : 0xF0));
                        argb = alpha << 24 | rgb;
                    }
                    image.setPixel(px, py, argb);
                }
            }
            Minecraft.getInstance().getTextureManager().register(RING, new DynamicTexture(() -> MapMod.ID + " minimap ring", image));
        }
        g.blit(RenderPipelines.GUI_TEXTURED, RING, x - RING_WIDTH, y - RING_WIDTH, 0, 0, size, size, size, size);
    }

    /** Draws a glyph with its top-left corner at x, y (the outline reaches a pixel further), tinted ARGB. */
    public static void draw(GuiGraphicsExtractor g, int icon, int x, int y, int argb) {
        make();
        if (icon < 0 || icon >= made) icon = MapGlyphs.DOT;
        int u = (icon % COLUMNS) * CELL, v = (icon / COLUMNS) * CELL;
        int texW = COLUMNS * CELL, texH = rows * CELL;
        g.blit(RenderPipelines.GUI_TEXTURED, ID, x - 1, y - 1, u, v, CELL, CELL, texW, texH, argb);
    }

    /** Draws a glyph centred on x, y. */
    public static void centred(GuiGraphicsExtractor g, int icon, int x, int y, int argb) {
        draw(g, icon, x - SIZE / 2, y - SIZE / 2, argb);
    }

    /** Draws a glyph centred on x, y, turned by an angle (radians, clockwise on screen). */
    public static void turned(GuiGraphicsExtractor g, int icon, float x, float y, float radians, int argb) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().rotate(radians);
        draw(g, icon, -SIZE / 2, -SIZE / 2, argb);
        g.pose().popMatrix();
    }
}
