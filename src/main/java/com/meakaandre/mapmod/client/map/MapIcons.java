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

    /** A shadow for an upright glyph centred on x, y: its own shape a pixel down and right, faint (MM-5). */
    public static void shadow(GuiGraphicsExtractor g, int icon, int x, int y) {
        centred(g, icon, x + 1, y + 1, 0x70000000);
    }

    /**
     * A soft outline round a turned glyph (MM-5): its own shape eight times round it, a pixel and a half out and faint,
     * so it reads on any ground and still stays pixel-sharp.
     */
    public static void softOutline(GuiGraphicsExtractor g, int icon, float x, float y, float radians) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            turned(g, icon, x + 1.5f * (float) Math.cos(a), y + 1.5f * (float) Math.sin(a), radians, 0x38101418);
        }
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
