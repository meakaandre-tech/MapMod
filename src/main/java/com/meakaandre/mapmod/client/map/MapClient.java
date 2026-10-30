package com.meakaandre.mapmod.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.api.MapGlyphs;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
import com.meakaandre.mapmod.client.paint.PaintMap;
import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapMarker;
import com.meakaandre.mapmod.map.MapPayloads;
import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The map on the client: what the server has sent (unlocks, fog, POIs, markers, the group's players, moving things),
 * what the player chose (pins and filters, kept in the game folder's {@code mapmod} folder) and the key: M opens the
 * map (changeable under "Map"). The terrain is the client's own surface capture, painted by {@code client.paint}.
 */
public final class MapClient {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(MapMod.id("map"));
    public static final KeyMapping OPEN = new KeyMapping("key.mapmod.open", InputConstants.KEY_M, CATEGORY);

    public static @Nullable String mapId;
    public static final Long2ObjectOpenHashMap<long[]> FOG = new Long2ObjectOpenHashMap<>();
    public static final Long2ObjectOpenHashMap<List<MapPoi>> POIS = new Long2ObjectOpenHashMap<>();
    public static List<MapMarker> markers = List.of();
    public static List<MapPayloads.Mate> mates = List.of();
    /** Moving things (where they are, or would be where their land does not tick). */
    public static List<MovingPoi> moving = List.of();
    /** What the server says the player has unlocked ({@link MapFeature} bits); nothing until it says. */
    public static int unlocks;
    /** What to say when a locked part is opened, and the group provider's word for a shared marker. */
    public static String lockedKey = "mapmod.map.locked", sharedKey = "mapmod.map.shared";
    /** Pinned things, by pin key, in the order pinned. */
    public static final Map<String, Pin> PINS = new LinkedHashMap<>();
    /** Filter switches that are off (see {@link MapFilters}). */
    public static final Set<String> HIDDEN = new HashSet<>();
    /** Contour lines on the painted map: off by default, switched in the map screen, kept in settings.json. */
    public static boolean contours;
    /** GPU memory for the painted map's baked tiles and mips (MB), kept in settings.json as {@code cache_mb}. */
    public static int cacheMB = PaintMap.DEFAULT_CACHE_MB;
    /** Markers seen since joining, so a new death marker can be pinned at once. */
    private static final Set<String> SEEN = new HashSet<>();
    private static boolean firstMarkers = true;

    /** A pinned thing: shown on the compass with its distance. */
    public record Pin(String key, String label, int x, int y, int z, int icon, int argb) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MapClient() {
    }

    public static void register() {
        KeyMappingHelper.registerKeyMapping(OPEN);
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Hello.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> hello(p.mapId(), p.sharedKey())));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Unlocks.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> {
            unlocks = p.mask();
            lockedKey = p.lockedKey();
        }));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Fog.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> fog(p)));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.FogDelta.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> fogDelta(p.chunks())));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Pois.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> {
            for (MapPayloads.RegionPois r : p.regions()) POIS.put(r.region(), r.pois());
        }));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Markers.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> markers(p.markers())));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Mates.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> mates = p.mates()));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Moving.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> moving = p.moving()));
        ClientTickEvents.END_CLIENT_TICK.register(MapClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(MapClient::reset));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(LocalMap::joined));
        loadSettings();
        MapHud.register();
    }

    /** True if the server says the player has this part of the map. */
    public static boolean unlocked(MapFeature feature) {
        // with no MapMod on the server the client keeps its own map, and all of it is open
        return LocalMap.active() || (unlocks & feature.bit()) != 0;
    }

    /** True while the player is somewhere the map covers (the Overworld). */
    public static boolean mappedHere() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == Level.OVERWORLD;
    }

    public static boolean explored(int x, int z) {
        long[] bits = FOG.get(MapGrid.regionOfBlock(x, z));
        return bits != null && MapGrid.get(bits, MapGrid.fogBit(x >> 4, z >> 4));
    }

    private static void reset() {
        LocalMap.stop();
        mapId = null;
        FOG.clear();
        POIS.clear();
        markers = List.of();
        mates = List.of();
        moving = List.of();
        unlocks = 0;
        lockedKey = "mapmod.map.locked";
        sharedKey = "mapmod.map.shared";
        PINS.clear();
        SEEN.clear();
        firstMarkers = true;
    }

    static void hello(String id, String shared) {
        sharedKey = shared;
        if (!id.equals(mapId)) PINS.clear();
        mapId = id;
        loadPins();
    }

    private static void fog(MapPayloads.Fog p) {
        if (p.replace()) {
            FOG.clear();
            POIS.clear();
        }
        for (MapPayloads.FogRegion r : p.regions()) FOG.put(r.region(), r.bits());
    }

    static void fogDelta(long[] chunks) {
        for (long c : chunks) {
            int cx = MapGrid.keyX(c), cz = MapGrid.keyZ(c);
            long region = MapGrid.regionOfChunk(cx, cz);
            long[] bits = FOG.computeIfAbsent(region, k -> new long[MapGrid.FOG_LONGS]);
            MapGrid.set(bits, MapGrid.fogBit(cx, cz));
        }
    }

    static void markers(List<MapMarker> list) {
        Minecraft mc = Minecraft.getInstance();
        String me = mc.player == null ? "" : mc.player.getUUID().toString();
        for (MapMarker m : list) {
            // a death marker that is new since joining is pinned at once, so the way back shows on the compass
            if (SEEN.add(m.id) && !firstMarkers && m.death && m.owner.equals(me)) pin(markerPin(m));
        }
        firstMarkers = false;
        markers = list;
        // pins of markers that are gone go too
        Set<String> live = new HashSet<>();
        for (MapMarker m : list) live.add(m.pinKey());
        boolean changed = PINS.keySet().removeIf(k -> k.startsWith("marker:") && !live.contains(k));
        if (changed) savePins();
    }

    // ------------------------------------------------------------------------------------------------ pins

    public static Pin markerPin(MapMarker m) {
        return new Pin(m.pinKey(), m.death ? "" : m.name, m.x, m.y, m.z, m.death ? MapGlyphs.SKULL : m.icon, MapDraw.markerColour(m));
    }

    public static boolean pinned(String key) {
        return PINS.containsKey(key);
    }

    public static void pin(Pin pin) {
        PINS.put(pin.key(), pin);
        savePins();
    }

    public static void togglePin(Pin pin) {
        if (PINS.remove(pin.key()) == null) PINS.put(pin.key(), pin);
        savePins();
    }

    /** A pin's live position: a marker's from the marker list (it may have been moved or renamed). */
    public static Pin live(Pin pin) {
        if (!pin.key().startsWith("marker:")) return pin;
        for (MapMarker m : markers) if (m.pinKey().equals(pin.key())) return markerPin(m);
        return pin;
    }

    private static @Nullable Path mapDir() {
        if (mapId == null) return null;
        return Minecraft.getInstance().gameDirectory.toPath().resolve(MapMod.ID).resolve(mapId);
    }

    private static void loadPins() {
        PINS.clear();
        Path dir = mapDir();
        if (dir == null) return;
        Path file = dir.resolve("pins.json");
        if (!Files.exists(file)) return;
        try {
            JsonArray array = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                Pin pin = new Pin(o.get("key").getAsString(), o.get("label").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(),
                    o.get("z").getAsInt(), o.get("icon").getAsInt(), o.get("argb").getAsInt());
                PINS.put(pin.key(), pin);
            }
        } catch (IOException | RuntimeException e) {
            MapMod.LOGGER.warn("unreadable map file {}", file, e);
        }
    }

    private static void savePins() {
        Path dir = mapDir();
        if (dir == null) return;
        JsonArray array = new JsonArray();
        for (Pin pin : PINS.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", pin.key());
            o.addProperty("label", pin.label());
            o.addProperty("x", pin.x());
            o.addProperty("y", pin.y());
            o.addProperty("z", pin.z());
            o.addProperty("icon", pin.icon());
            o.addProperty("argb", pin.argb());
            array.add(o);
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("pins.json"), GSON.toJson(array));
        } catch (IOException e) {
            MapMod.LOGGER.warn("could not save the map pins", e);
        }
    }

    // ------------------------------------------------------------------------------------------------ settings

    private static Path settingsFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(MapMod.ID).resolve("settings.json");
    }

    private static void loadSettings() {
        Path file = settingsFile();
        if (!Files.exists(file)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (o.has("hidden")) for (JsonElement e : o.getAsJsonArray("hidden")) HIDDEN.add(e.getAsString());
            if (o.has("contours")) contours = o.get("contours").getAsBoolean();
            if (o.has("cache_mb")) cacheMB = Math.clamp(o.get("cache_mb").getAsInt(), 16, 4096);
        } catch (IOException | RuntimeException e) {
            MapMod.LOGGER.warn("unreadable map file {}", file, e);
        }
    }

    /** Switches contour lines on or off and saves the setting. */
    public static void setContours(boolean on) {
        contours = on;
        saveSettings();
    }

    /** Where the map's settings are kept (for the selftests). */
    public static Path settingsPath() {
        return settingsFile();
    }

    public static void saveSettings() {
        JsonObject o = new JsonObject();
        JsonArray hidden = new JsonArray();
        for (String h : HIDDEN) hidden.add(h);
        o.add("hidden", hidden);
        o.addProperty("contours", contours);
        o.addProperty("cache_mb", cacheMB);
        try {
            Path file = settingsFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(o));
        } catch (IOException e) {
            MapMod.LOGGER.warn("could not save the map settings", e);
        }
    }

    // ------------------------------------------------------------------------------------------------ ticking

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        LocalMap.tick(mc);
        if (mc.gui.screen() == null) while (OPEN.consumeClick()) open();
    }

    /** Opens the fullscreen map, if the player has it and is in the Overworld. */
    public static void open() {
        show(new MapScreen());
    }

    /** Shows this map screen, if the player has the fullscreen map and is in the Overworld. */
    public static void show(MapScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!unlocked(MapFeature.FULLSCREEN)) {
            mc.player.sendOverlayMessage(Component.translatable(lockedKey));
            return;
        }
        if (!mappedHere()) {
            mc.player.sendOverlayMessage(Component.translatable("mapmod.map.surface_only"));
            return;
        }
        mc.gui.setScreen(screen);
    }

    /** Every POI the map holds in the regions given. */
    public static List<MapPoi> poisIn(Iterable<Long> regions) {
        List<MapPoi> out = new ArrayList<>();
        for (long region : regions) {
            List<MapPoi> list = POIS.get(region);
            if (list != null) out.addAll(list);
        }
        return out;
    }
}
