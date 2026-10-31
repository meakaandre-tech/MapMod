package com.meakaandre.mapmod.api;

import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.paint.PaintPalette;
import com.meakaandre.mapmod.client.paint.TilePrep;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Layers of your own on the fullscreen map (territory, routes, ranges): each draws in GUI space, every frame the map is
 * open, over the painted terrain and under the map's own icons, through a {@link MapView} that turns world positions
 * into screen ones. Each layer gets a switch in the map's top bar (its glyph, its label as the tooltip), on by default
 * and kept with the map's other layer switches in {@code mapmod/settings.json} under {@code layers}, by the layer's id.
 * <p>
 * Overlays are drawn straight to the screen: never baked into the map's tiles, and never under its paper grain or
 * vignette. To follow the painted map's borders (coasts, region edges), offset positions by {@link #warpX}/{@link #warpZ}.
 * A layer can be locked with {@link MapLock#lockLayer} by its id. Register in your client initializer; client thread.
 */
public final class MapOverlay {
    /** Draws a layer. Called on the render thread while the fullscreen map is open; drawing is clipped to the map's area. */
    @FunctionalInterface
    public interface Layer {
        void draw(GuiGraphicsExtractor graphics, MapView view);
    }

    /** A registered layer. */
    public record Registered(Identifier id, Component label, int glyph, Layer layer) {
    }

    /** The painted map's warp noise repeats every this many blocks along x and z. */
    public static final int WARP_PERIOD = 8192;

    private static final Map<Identifier, Registered> LAYERS = new LinkedHashMap<>();
    private static volatile List<Registered> all = List.of();

    private MapOverlay() {
    }

    /**
     * Adds a layer: its id (also its key in the settings and for {@link MapLock}), the label its switch shows, a
     * {@link MapGlyphs} glyph for its switch, and what draws it. Layers draw in the order they were registered.
     */
    public static synchronized void register(Identifier id, Component label, int glyph, Layer layer) {
        Registered r = new Registered(Objects.requireNonNull(id), Objects.requireNonNull(label), glyph, Objects.requireNonNull(layer));
        if (LAYERS.putIfAbsent(id, r) != null) throw new IllegalStateException("map overlay " + id + " is registered twice");
        all = List.copyOf(LAYERS.values());
    }

    /** Every layer, in the order registered. */
    public static List<Registered> layers() {
        return all;
    }

    public static @Nullable Registered layer(Identifier id) {
        for (Registered r : all) if (r.id().equals(id)) return r;
        return null;
    }

    /** True if the player has the layer's switch on (whether or not it is locked). */
    public static boolean enabled(Identifier id) {
        return !MapClient.OVERLAYS_OFF.contains(id.toString());
    }

    /** Switches a layer on or off, as its button does, and saves the setting. */
    public static void setEnabled(Identifier id, boolean on) {
        MapClient.setOverlay(id.toString(), on);
    }

    /** True if the layer is drawn now: switched on and not locked. */
    public static boolean shown(Identifier id) {
        return enabled(id) && !MapLock.layerLocked(id);
    }

    /**
     * How far, in blocks along x, the painted map moves what is at a world position (the low-frequency noise that bends
     * its coasts and region borders), at the palette's current strength: what the data has at (x, z) is painted at about
     * (x - warpX(x, z), z - warpZ(x, z)). Repeats every {@link #WARP_PERIOD} blocks. Any thread.
     */
    public static double warpX(double x, double z) {
        return warp(x, z, 0);
    }

    /** The same along z. */
    public static double warpZ(double x, double z) {
        return warp(x, z, 1);
    }

    private static double warp(double x, double z, int axis) {
        // the shader warps the sampling position, in samples (4 blocks), by noise at unit strength times warp/4
        return TilePrep.warp(x / 4, z / 4, axis) * PaintPalette.current().edgeWarp();
    }
}
