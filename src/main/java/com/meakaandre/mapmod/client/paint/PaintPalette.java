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
 * The painted map's colours, from {@code assets/mapmod/map_palette.json} (resource packs can replace it; F3+T reloads
 * it). MM-8: the land is painted in its real block colours ({@code SurfaceTile.colour}), pulled toward a muted painted
 * palette ({@code colour.saturation}, {@code colour.value}) and brushed into patches ({@code colour.brush_size}); a
 * surface class's colour, when the palette sets one ({@code classes.<id>.colour}, none by default), overrides the real
 * colour of that class's ground, tinted by the biome as much as its {@code tint} says. Then water by depth and distance from the shore with a pale shore
 * band, the paper of unexplored land, the height tint and the snow line, the hillshade's share, how soft and wavy class
 * borders are, the forests' darkening and brush, contour lines, the paper grain and vignette, and the fog over unexplored
 * land (MM-5: its colour, how wide its edge fades, and how strong its clouds are). Each value that is
 * missing or unreadable keeps its built-in one on its own. Colours are 0xRRGGBB; distances are in blocks. Mods override
 * single values over the resource packs' file through {@code api.MapPalette} (MM-6), kept across F3+T reloads.
 */
public record PaintPalette(int[] classColour, float[] classTint, int waterShallow, int waterDeep, float deepAt, int paper,
                           float lowHeight, float highHeight, float lowShade, float highLight, float snowAbove,
                           float snowBlend, int snow, float hillshade, float deepDist, int shore, float shoreWidth,
                           float edgeSoftness, float edgeWarp, float forestDarken, float forestStipple,
                           float contourInterval, float contourStrength, float grain, float vignette,
                           int fogColour, float fogEdge, float fogClouds,
                           float colourSaturation, float colourValue, float brushSize, float waterTint) {
    /** A class colour that is not overridden. */
    public static final int NONE = -1;
    /** The mid tone {@code colour.value} pulls brightness toward. */
    static final float MID = 0.62f;
    public static final Identifier FILE = MapMod.id("map_palette.json");
    /** The built-in palette: the same values as the mod's own map_palette.json. */
    public static final PaintPalette DEFAULT = new PaintPalette(
        new int[]{NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE},
        new float[]{0.4f, 0.4f, 0, 0, 0, 0.15f, 0, 0},
        0x8DB6C2, 0x3E6F8E, 20, 0xE6DCC6, 50, 220, 0.88f, 1.12f, 190, 20, 0xF0F1EF, 0.5f,
        40, 0xB0CCD0, 3, 0.12f, 5, 0.12f, 0.18f, 16, 0.3f, 0.035f, 0.18f,
        0xD8CFBA, 48, 0.6f,
        0.8f, 0.2f, 6, 0.5f);

    private static volatile PaintPalette current = DEFAULT;
    /** Bumped on every load and every change of the overrides, so the renderer bakes its tiles again. */
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

    /** The JSON keys an override can set, by dotted path ("classes.3.colour", "water.shallow", "fog.edge"...), with true for colours. */
    public static final Map<String, Boolean> KEYS = keys();

    private static Map<String, Boolean> keys() {
        Map<String, Boolean> k = new LinkedHashMap<>();
        for (int c = 0; c < SurfaceClass.COUNT; c++) {
            k.put("classes." + c + ".colour", true);
            k.put("classes." + c + ".tint", false);
        }
        for (String n : new String[]{"water.shallow", "water.deep", "water.shore", "paper", "snow.colour", "fog.colour"}) k.put(n, true);
        for (String n : new String[]{"water.deep_at", "water.deep_dist", "water.shore_width", "height_tint.low", "height_tint.high",
            "height_tint.low_shade", "height_tint.high_light", "snow.above", "snow.blend", "hillshade", "edges.softness", "edges.warp",
            "forest.darken", "forest.stipple", "contours.interval", "contours.strength", "finish.grain", "finish.vignette", "fog.edge",
            "fog.clouds", "colour.saturation", "colour.value", "colour.brush_size", "water.tint"}) k.put(n, false);
        return Collections.unmodifiableMap(k);
    }

    /**
     * Replaces every override (a dotted key from {@link #KEYS} to a colour "#rrggbb" or a number) and paints with the
     * resource packs' palette under them: one version bump, so the baked tiles go once.
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
        JsonObject tint = object(o, "height_tint");
        JsonObject snow = object(o, "snow");
        JsonObject edges = object(o, "edges");
        JsonObject forest = object(o, "forest");
        JsonObject contours = object(o, "contours");
        JsonObject finish = object(o, "finish");
        JsonObject fog = object(o, "fog");
        JsonObject colour = object(o, "colour");
        return new PaintPalette(colours, tints,
            colour(water, "shallow", d.waterShallow), colour(water, "deep", d.waterDeep), Math.max(1, number(water, "deep_at", d.deepAt)),
            colour(o, "paper", d.paper),
            number(tint, "low", d.lowHeight), number(tint, "high", d.highHeight), number(tint, "low_shade", d.lowShade), number(tint, "high_light", d.highLight),
            number(snow, "above", d.snowAbove), Math.max(0.5f, number(snow, "blend", d.snowBlend)), colour(snow, "colour", d.snow),
            Math.clamp(number(o, "hillshade", d.hillshade), 0f, 1f),
            Math.max(1, number(water, "deep_dist", d.deepDist)), colour(water, "shore", d.shore),
            Math.clamp(number(water, "shore_width", d.shoreWidth), 0f, 32f),
            Math.clamp(number(edges, "softness", d.edgeSoftness), 0.01f, 1f), Math.clamp(number(edges, "warp", d.edgeWarp), 0f, 8f),
            Math.clamp(number(forest, "darken", d.forestDarken), 0f, 1f), Math.clamp(number(forest, "stipple", d.forestStipple), 0f, 1f),
            Math.max(1, number(contours, "interval", d.contourInterval)), Math.clamp(number(contours, "strength", d.contourStrength), 0f, 1f),
            Math.clamp(number(finish, "grain", d.grain), 0f, 0.5f), Math.clamp(number(finish, "vignette", d.vignette), 0f, 1f),
            colour(fog, "colour", d.fogColour), Math.clamp(number(fog, "edge", d.fogEdge), 8f, 256f),
            Math.clamp(number(fog, "clouds", d.fogClouds), 0f, 1f),
            Math.clamp(number(colour, "saturation", d.colourSaturation), 0f, 2f), Math.clamp(number(colour, "value", d.colourValue), 0f, 1f),
            Math.clamp(number(colour, "brush_size", d.brushSize), 0f, 16f), Math.clamp(number(water, "tint", d.waterTint), 0f, 1f));
    }

    /**
     * The colour a land sample starts from (MM-8): its real colour, or, where the palette overrides its class's colour,
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
     * The light pull toward a muted painted palette (MM-8): saturation scaled by {@code colour.saturation}, brightness
     * pulled toward a mid tone by {@code colour.value}, the hue kept. 0xRRGGBB.
     */
    public int paint(int rgb) {
        float[] c = rgb(rgb);
        float max = Math.max(c[0], Math.max(c[1], c[2])), min = Math.min(c[0], Math.min(c[1], c[2]));
        float v = max, s = max > 0 ? (max - min) / max : 0;
        float v2 = v + (MID - v) * colourValue, s2 = Math.clamp(s * colourSaturation, 0f, 1f);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            float k = max > min ? (max - c[i]) / (max - min) : 0;
            out[i] = v2 * (1 - s2 * k);
        }
        return pack(out);
    }

    /** The brush's windows, in samples across (1: no brush). */
    public int brushSamples() {
        return Math.clamp(Math.round(brushSize / com.meakaandre.mapmod.client.capture.SurfaceTile.SPACING), 1, TilePrep.MAX_BRUSH);
    }

    /**
     * The colour the painted map gives open, flat land of one class with a stored colour and tint, as the prep and
     * {@code map_paint.fsh} work it out before the brush, relief, forest, water and the finish (for {@code /mapmod probe}
     * and the selftests). 0xRRGGBB.
     */
    public int landColour(int cls, int colour, int tint, int height) {
        float[] out = rgb(paint(landBase(cls, colour, tint)));
        float h = smooth(lowHeight, highHeight, height), shade = lowShade + (highLight - lowShade) * h;
        float s = smooth(snowAbove - snowBlend, snowAbove + snowBlend, height);
        float[] sn = rgb(snow);
        for (int i = 0; i < 3; i++) out[i] = out[i] * shade + (sn[i] - out[i] * shade) * s;
        return pack(out);
    }

    static float[] rgb(int c) {
        return new float[]{(c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f};
    }

    static int pack(float[] c) {
        int r = Math.clamp(Math.round(c[0] * 255), 0, 255), g = Math.clamp(Math.round(c[1] * 255), 0, 255), b = Math.clamp(Math.round(c[2] * 255), 0, 255);
        return r << 16 | g << 8 | b;
    }

    static float smooth(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3 - 2 * t);
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
