package com.meakaandre.mapmod.client.paint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.SurfaceClass;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The map's colours (MM-9), from {@code assets/mapmod/map_palette.json} (resource packs can replace it; F3+T reloads it).
 * The land shows each block's real colour ({@code SurfaceTile.colour}: its top texture's average times its biome tint);
 * a surface class's colour, when the palette sets one ({@code classes.<id>.colour}, none by default), replaces the real
 * colour of that class's ground, tinted by the biome as much as its {@code tint} says. Then the height shading's
 * strength ({@code shading}), the water ({@code water.opacity}: how much of the water's own colour lies over the floor at
 * the shallowest; {@code water.depth}: the depth at which the floor no longer shows; {@code water.darken}: how much
 * darker the water is there) and the flat background of unexplored land ({@code background}). Each value that is missing
 * or unreadable keeps its built-in one on its own. Colours are 0xRRGGBB; depths are in blocks. Mods override single values
 * over the resource packs' file through {@code api.MapPalette} (MM-6), kept across F3+T reloads.
 * <p>
 * The painted look's keys (MM-3 to MM-8: paper, hillshade, edges, forest, contours, finish, fog, colour pull and brush,
 * the old water and snow keys) mean nothing since MM-9: {@link #DEPRECATED} lists them; an override of one is accepted and
 * ignored, and a palette file that still has them reads the same as one without.
 */
public record PaintPalette(int[] classColour, float[] classTint, int background, float shading, float waterOpacity,
                           float waterDepth, float waterDarken) {
    /** A class colour that is not overridden. */
    public static final int NONE = -1;
    public static final Identifier FILE = MapMod.id("map_palette.json");
    /** The built-in palette: the same values as the mod's own map_palette.json. */
    public static final PaintPalette DEFAULT = new PaintPalette(
        new int[]{NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE},
        new float[]{0.4f, 0.4f, 0, 0, 0, 0.15f, 0, 0},
        0x16181D, 1f, 0.55f, 16, 0.45f);
    /**
     * The height shading's steps (MM-9), for a block against its north and west neighbours together: two or more blocks
     * lower, one lower, level, one higher, two or more higher. Negative darkens (times 1 + k), positive lightens
     * (toward white by k); scaled by {@code shading}.
     */
    static final float[] SHADE_STEPS = {-0.22f, -0.11f, 0f, 0.10f, 0.20f};

    private static volatile PaintPalette current = DEFAULT;
    /** Bumped on every load and every change of the overrides, so the map bakes its tiles again. */
    private static volatile int version;
    /** The resource packs' palette as read (null: none read yet, or none there), and the overrides over it (MM-6). */
    private static @Nullable JsonObject read;
    private static Map<String, JsonPrimitive> overrides = Map.of();
    private static volatile int loads;
    private static volatile String lastError = "";

    public static PaintPalette current() {
        return current;
    }

    public static int version() {
        return version;
    }

    /** How many times the palette has been read from the resources (for the selftests). */
    public static int loads() {
        return loads;
    }

    /** What went wrong on the last load ("" if nothing). */
    public static String lastError() {
        return lastError;
    }

    /** Reads the palette from the resources (the top pack's file wins); the built-in one if there is none. */
    public static void load(ResourceManager resources) {
        JsonObject json = null;
        String error = "";
        Optional<Resource> resource = resources.getResource(FILE);
        if (resource.isEmpty()) {
            error = "no " + FILE;
        } else {
            try (Reader reader = resource.get().openAsReader()) {
                json = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (Exception e) {
                error = e.toString();
            }
        }
        if (!error.isEmpty()) MapMod.LOGGER.warn("map palette: {}; using the built-in one", error);
        synchronized (PaintPalette.class) {
            read = json;
            lastError = error;
            loads++;
            apply();
        }
    }

    /** The JSON keys an override can set, by dotted path ("classes.3.colour", "water.opacity", "background"...), with true for colours. */
    public static final Map<String, Boolean> KEYS = keys();
    /**
     * The painted look's keys (MM-3 to MM-8), with true for colours: no longer used since MM-9. An override of one is
     * accepted (so a mod that sets one keeps working) and has no effect; they are not in {@link #KEYS}.
     */
    public static final Map<String, Boolean> DEPRECATED = deprecated();

    private static Map<String, Boolean> keys() {
        Map<String, Boolean> k = new LinkedHashMap<>();
        for (int c = 0; c < SurfaceClass.COUNT; c++) {
            k.put("classes." + c + ".colour", true);
            k.put("classes." + c + ".tint", false);
        }
        k.put("background", true);
        for (String n : new String[]{"shading", "water.opacity", "water.depth", "water.darken"}) k.put(n, false);
        return Collections.unmodifiableMap(k);
    }

    private static Map<String, Boolean> deprecated() {
        Map<String, Boolean> k = new LinkedHashMap<>();
        for (String n : new String[]{"water.shallow", "water.deep", "water.shore", "paper", "snow.colour", "fog.colour"}) k.put(n, true);
        for (String n : new String[]{"water.deep_at", "water.deep_dist", "water.shore_width", "height_tint.low", "height_tint.high",
            "height_tint.low_shade", "height_tint.high_light", "snow.above", "snow.blend", "hillshade", "edges.softness", "edges.warp",
            "forest.darken", "forest.stipple", "contours.interval", "contours.strength", "finish.grain", "finish.vignette", "fog.edge",
            "fog.clouds", "colour.saturation", "colour.value", "colour.brush_size", "water.tint", "colour.merge"}) k.put(n, false);
        return Collections.unmodifiableMap(k);
    }

    /**
     * Replaces every override (a dotted key from {@link #KEYS} to a colour "#rrggbb" or a number) and lays them
     * over the resource packs' palette: one version bump, so the baked tiles go once.
     */
    public static synchronized void override(Map<String, JsonPrimitive> next) {
        overrides = Map.copyOf(next);
        apply();
    }

    public static synchronized Map<String, JsonPrimitive> overrides() {
        return overrides;
    }

    /** The resource packs' palette with the overrides over it, as the current one. Locked on the class. */
    private static void apply() {
        JsonObject o = read == null ? new JsonObject() : read.deepCopy();
        for (Map.Entry<String, JsonPrimitive> e : overrides.entrySet()) {
            String[] path = e.getKey().split("\\.");
            JsonObject at = o;
            for (int i = 0; i < path.length - 1; i++) {
                JsonElement next = at.get(path[i]);
                if (next == null || !next.isJsonObject()) {
                    next = new JsonObject();
                    at.add(path[i], next);
                }
                at = next.getAsJsonObject();
            }
            at.add(path[path.length - 1], e.getValue());
        }
        current = read == null && overrides.isEmpty() ? DEFAULT : parse(o);
        version++;
    }

    /** A palette from its JSON; every value that is missing or broken keeps the built-in one. */
    public static PaintPalette parse(JsonObject o) {
        PaintPalette d = DEFAULT;
        int[] colours = d.classColour.clone();
        float[] tints = d.classTint.clone();
        JsonObject classes = object(o, "classes");
        for (int c = 0; c < SurfaceClass.COUNT; c++) {
            JsonObject entry = object(classes, Integer.toString(c));
            colours[c] = colour(entry, "colour", colours[c]);
            tints[c] = Math.clamp(number(entry, "tint", tints[c]), 0f, 1f);
        }
        JsonObject water = object(o, "water");
        return new PaintPalette(colours, tints, colour(o, "background", d.background),
            Math.clamp(number(o, "shading", d.shading), 0f, 2f),
            Math.clamp(number(water, "opacity", d.waterOpacity), 0f, 1f), Math.clamp(number(water, "depth", d.waterDepth), 1f, 255f),
            Math.clamp(number(water, "darken", d.waterDarken), 0f, 0.9f));
    }

    /**
     * The colour a land block starts from (MM-8): its real colour, or, where the palette overrides its class's colour,
     * that colour tinted by the biome as much as the class's tint says. Colours are 0xRRGGBB.
     */
    public int landBase(int cls, int colour, int tint) {
        int c = Math.clamp(cls, 0, SurfaceClass.COUNT - 1);
        if (classColour[c] == NONE) return colour & 0xFFFFFF;
        float[] base = rgb(classColour[c]), t = rgb(tint);
        float k = classTint[c];
        float max = Math.max(Math.max(t[0], t[1]), Math.max(t[2], 0.001f));
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = base[i] * (1 + (t[i] / max - 1) * k);
        return pack(out);
    }

    /**
     * The colour the map gives a block of one class with a stored colour and tint, before the height shading and the
     * water (for {@code /mapmod probe} and the selftests): {@link #landBase}. 0xRRGGBB.
     */
    public int landColour(int cls, int colour, int tint) {
        return landBase(cls, colour, tint);
    }

    /**
     * The height shading (MM-9), as Xaero's World Map has it: {@code rise} is how much higher a block is than its north
     * neighbour plus how much higher than its west one; it falls in one of {@link #SHADE_STEPS}' five steps, so slopes
     * read as a few clear bands of light and shadow, not a smooth gradient. 0xRRGGBB.
     */
    public int shade(int rgb, int rise) {
        float k = SHADE_STEPS[Math.clamp(rise, -2, 2) + 2] * shading;
        if (k == 0) return rgb & 0xFFFFFF;
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            float c = rgb >> shift & 255;
            c = k > 0 ? c + (255 - c) * k : c * (1 + k);
            out |= Math.clamp(Math.round(c), 0, 255) << shift;
        }
        return out;
    }

    /**
     * Water over its floor (MM-9): the floor's colour (shaded) shows through, less and darker the deeper the water: a
     * block deep, a share {@code water.opacity} of the water's own colour lies over it, growing to all of it at
     * {@code water.depth}, and the whole darkens by up to {@code water.darken}. 0xRRGGBB.
     */
    public int water(int floor, int water, int depth) {
        float t = Math.clamp((depth - 1) / Math.max(1f, waterDepth - 1), 0f, 1f);
        float a = waterOpacity + (1 - waterOpacity) * t, dark = 1 - waterDarken * t;
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            float f = floor >> shift & 255, w = water >> shift & 255;
            out |= Math.clamp(Math.round((f + (w - f) * a) * dark), 0, 255) << shift;
        }
        return out;
    }

    static float[] rgb(int c) {
        return new float[]{(c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f};
    }

    static int pack(float[] c) {
        int r = Math.clamp(Math.round(c[0] * 255), 0, 255), g = Math.clamp(Math.round(c[1] * 255), 0, 255), b = Math.clamp(Math.round(c[2] * 255), 0, 255);
        return r << 16 | g << 8 | b;
    }

    private static JsonObject object(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static float number(JsonObject o, String key, float fallback) {
        try {
            JsonElement e = o == null ? null : o.get(key);
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    /** A colour as "#rrggbb". */
    private static int colour(JsonObject o, String key, int fallback) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        String s = e.getAsString().trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return fallback;
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
