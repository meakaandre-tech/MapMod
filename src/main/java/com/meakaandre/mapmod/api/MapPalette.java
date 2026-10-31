package com.meakaandre.mapmod.api;

import com.google.gson.JsonPrimitive;
import com.meakaandre.mapmod.client.paint.PaintPalette;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Restyles the painted map from code: overrides of single values of {@code assets/mapmod/map_palette.json}, laid over
 * whatever the resource packs give, kept until cleared and laid over again after every resource reload (F3+T). Any
 * change makes the map paint its tiles again. Values are clamped as the JSON's are.
 * <p>
 * Keys are the JSON's paths, dotted ({@link #keys()}): {@code classes.<id>.colour} and {@code classes.<id>.tint} for the
 * surface classes ({@link MapSurface}'s ids 0-7), and {@code water.shallow}, {@code water.deep}, {@code water.shore},
 * {@code paper}, {@code snow.colour}, {@code fog.colour} (colours) and the numbers ({@code hillshade}, {@code edges.warp},
 * {@code fog.edge}, {@code colour.saturation}, {@code colour.value}, {@code colour.brush_size}, {@code water.tint}...).
 * One change at a time with {@link #colour}/{@link #number}, or several at once with {@link #edit()}. Client thread.
 * <p>
 * Since MM-8 the map paints the land in each block's real colour (the average of its top texture, as the resource packs
 * give it, times its biome tint), pulled toward a muted painted palette by {@code colour.saturation} and
 * {@code colour.value} and brushed into patches {@code colour.brush_size} blocks across. A class colour
 * ({@link #classColour}, {@code classes.<id>.colour}) is now an optional override on top of that: none is set by
 * default, and one that is set replaces the real colour of every sample of that class (tinted by its biome as much as
 * {@link #classTint} says), until it is cleared. {@code classes.<id>.tint} only matters with a class colour set. Nothing
 * in this API changed its signature.
 */
public final class MapPalette {
    private MapPalette() {
    }

    /** Every key an override can set. */
    public static Set<String> keys() {
        return PaintPalette.KEYS.keySet();
    }

    /** True if the key is a colour (0xRRGGBB), false if a number. */
    public static boolean isColour(String key) {
        Boolean c = PaintPalette.KEYS.get(key);
        if (c == null) throw new IllegalArgumentException("no map palette key " + key + "; the keys are " + keys());
        return c;
    }

    /** Overrides a colour, 0xRRGGBB. */
    public static void colour(String key, int rgb) {
        edit().colour(key, rgb).apply();
    }

    /** Overrides a number. */
    public static void number(String key, float value) {
        edit().number(key, value).apply();
    }

    /**
     * Overrides a surface class's colour (class ids as {@link MapSurface}'s): that class's ground is painted in it
     * instead of its blocks' real colours (MM-8; {@link #clear} with {@code "classes.<id>.colour"} brings them back).
     */
    public static void classColour(int classId, int rgb) {
        edit().classColour(classId, rgb).apply();
    }

    /** How much the biome's colour tints a surface class's override colour, 0 none to 1 fully (only with {@link #classColour} set). */
    public static void classTint(int classId, float tint) {
        edit().classTint(classId, tint).apply();
    }

    /** Takes one override away (the resource packs' value is back). */
    public static void clear(String key) {
        edit().clear(key).apply();
    }

    /** Takes every override away. */
    public static void clearAll() {
        PaintPalette.override(Map.of());
    }

    /** The overrides in force: key to "#rrggbb" or a number. */
    public static Map<String, String> overrides() {
        Map<String, String> out = new LinkedHashMap<>();
        PaintPalette.overrides().forEach((k, v) -> out.put(k, v.getAsString()));
        return out;
    }

    /** Starts a batch of changes to the overrides in force, applied together by {@link Builder#apply()}. */
    public static Builder edit() {
        return new Builder(PaintPalette.overrides());
    }

    /**
     * A palette's version: it changes every time the palette does (a resource reload, or overrides applied), as the
     * map paints its tiles again.
     */
    public static int version() {
        return PaintPalette.version();
    }

    /** Changes to the overrides, made together: what {@link #apply()} lays over the resource packs' palette. */
    public static final class Builder {
        private final Map<String, JsonPrimitive> next;

        private Builder(Map<String, JsonPrimitive> from) {
            next = new LinkedHashMap<>(from);
        }

        public Builder colour(String key, int rgb) {
            if (!isColour(key)) throw new IllegalArgumentException(key + " is a number, not a colour");
            next.put(key, new JsonPrimitive(String.format("#%06x", rgb & 0xFFFFFF)));
            return this;
        }

        public Builder number(String key, float value) {
            if (isColour(key)) throw new IllegalArgumentException(key + " is a colour, not a number");
            if (!Float.isFinite(value)) throw new IllegalArgumentException(key + " must be a finite number");
            next.put(key, new JsonPrimitive(value));
            return this;
        }

        public Builder classColour(int classId, int rgb) {
            return colour("classes." + MapSurface.check(classId) + ".colour", rgb);
        }

        public Builder classTint(int classId, float tint) {
            return number("classes." + MapSurface.check(classId) + ".tint", tint);
        }

        public Builder clear(String key) {
            isColour(key);
            next.remove(key);
            return this;
        }

        public Builder clearAll() {
            next.clear();
            return this;
        }

        /** Applies the overrides: the palette changes and the map paints its tiles again. */
        public void apply() {
            PaintPalette.override(next);
        }
    }
}
