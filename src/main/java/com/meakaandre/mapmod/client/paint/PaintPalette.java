package com.meakaandre.mapmod.client.paint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.SurfaceClass;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.Optional;

/**
 * The painted map's colours, from {@code assets/mapmod/map_palette.json} (resource packs can replace it; F3+T reloads
 * it): one colour and tint strength per surface class, water by depth, the paper of unexplored land, the height tint
 * and the snow line, and the hillshade's share. Anything missing or unreadable keeps the built-in value. Colours are
 * 0xRRGGBB.
 */
public record PaintPalette(int[] classColour, float[] classTint, int waterShallow, int waterDeep, float deepAt, int paper,
                           float lowHeight, float highHeight, float lowShade, float highLight, float snowAbove,
                           float snowBlend, int snow, float hillshade) {
    public static final Identifier FILE = MapMod.id("map_palette.json");
    /** The built-in palette: the same values as the mod's own map_palette.json. */
    public static final PaintPalette DEFAULT = new PaintPalette(
        new int[]{0xB2BD8C, 0x8A9A6C, 0xDCCB9E, 0x9C978D, 0xECEEEC, 0x93805F, 0xBCD3DE, 0xC8642C},
        new float[]{0.6f, 0.6f, 0, 0, 0, 0.15f, 0, 0},
        0x8DB6C2, 0x3E6F8E, 20, 0xE6DCC6, 50, 220, 0.88f, 1.12f, 190, 20, 0xF0F1EF, 0.4f);

    private static volatile PaintPalette current = DEFAULT;
    /** Bumped on every load, so the renderer knows to upload the palette again. */
    private static volatile int version;
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
        PaintPalette loaded = DEFAULT;
        String error = "";
        Optional<Resource> resource = resources.getResource(FILE);
        if (resource.isEmpty()) {
            error = "no " + FILE;
        } else {
            try (Reader reader = resource.get().openAsReader()) {
                loaded = parse(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (Exception e) {
                error = e.toString();
            }
        }
        if (!error.isEmpty()) MapMod.LOGGER.warn("map palette: {}; using the built-in one", error);
        current = loaded;
        lastError = error;
        loads++;
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
        return new PaintPalette(colours, tints,
            colour(water, "shallow", d.waterShallow), colour(water, "deep", d.waterDeep), Math.max(1, number(water, "deep_at", d.deepAt)),
            colour(o, "paper", d.paper),
            number(tint, "low", d.lowHeight), number(tint, "high", d.highHeight), number(tint, "low_shade", d.lowShade), number(tint, "high_light", d.highLight),
            number(snow, "above", d.snowAbove), Math.max(0.5f, number(snow, "blend", d.snowBlend)), colour(snow, "colour", d.snow),
            Math.clamp(number(o, "hillshade", d.hillshade), 0f, 1f));
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
