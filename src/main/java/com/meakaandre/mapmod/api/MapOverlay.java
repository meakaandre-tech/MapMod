package com.meakaandre.mapmod.api;

import com.meakaandre.mapmod.client.map.MapClient;
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
 * open, over the terrain and under the map's own icons, through a {@link MapView} that turns world positions
 * into screen ones. Each layer gets a switch in the map's top bar (its glyph, its label as the tooltip), on by default
 * and kept with the map's other layer switches in {@code mapmod/settings.json} under {@code layers}, by the layer's id.
 * <p>
 * Overlays are drawn straight to the screen, never baked into the map's tiles. Since MM-9 the map shows every block
 * where it is (one pixel per block), so a world position needs no offset to line up with the terrain under it. A layer
 * can be locked with {@link MapLock#lockLayer} by its id. Register in your client initializer; client thread.
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

    /**
     * The painted map's warp noise repeated every this many blocks along x and z.
     *
     * @deprecated since MM-9 the map has no warp: {@link #warpX} and {@link #warpZ} are always 0.
     */
    @Deprecated
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
     * How far, in blocks along x, the painted map moved what is at a world position (the noise that bent its coasts and
     * region borders).
     *
     * @deprecated since MM-9 the map shows every block where it is: always 0, so code that offsets by it still lines up.
     */
    @Deprecated
    public static double warpX(double x, double z) {
        return 0;
    }

    /**
     * The same along z.
     *
     * @deprecated since MM-9: always 0, as {@link #warpX}.
     */
    @Deprecated
    public static double warpZ(double x, double z) {
        return 0;
    }
}
