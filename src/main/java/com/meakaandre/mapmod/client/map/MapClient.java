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
    /** Overlay layers switched off in the fullscreen map's top bar (MM-5), kept in settings.json as {@code layers}. */
    public static final Set<MapLayer> LAYERS_OFF = java.util.EnumSet.noneOf(MapLayer.class);
    /**
     * Mods' overlay layers ({@code api.MapOverlay}) switched off, by id, kept in settings.json's {@code layers} too (MM-6).
     * Kept by id even for a layer no mod registered this time, so its switch is as the player left it when it comes back.
     */
    public static final Set<String> OVERLAYS_OFF = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            reset();
            com.meakaandre.mapmod.api.MapMarkers.clear();
        }));
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

    /** Pin keys of mods' markers ({@code api.MapMarkers}) start with this, then the marker's id. */
    public static final String API_PIN = "mod:";

    /** The pin of a mod's marker. */
    public static Pin apiPin(com.meakaandre.mapmod.api.MapMarkers.Marker m) {
        String label = m.label() == null ? m.id().toString() : m.label().getString();
        return new Pin(API_PIN + m.id(), label, (int) Math.floor(m.x()), DEFAULT_PIN_Y, (int) Math.floor(m.z()), m.glyph(), m.colour());
    }

    /** A height for pins of things that have none (mods' markers): only x and z are shown. */
    private static final int DEFAULT_PIN_Y = 64;

    /** The mod's marker a pin stands for, or null if it is gone. */
    public static com.meakaandre.mapmod.api.MapMarkers.@Nullable Marker apiMarker(String pinKey) {
        for (var m : com.meakaandre.mapmod.api.MapMarkers.all()) if (pinKey.equals(API_PIN + m.id())) return m;
        return null;
    }

    /** A pin's live position: a marker's from the marker list (it may have been moved or renamed). */
    public static Pin live(Pin pin) {
        if (pin.key().startsWith(API_PIN)) {
            var m = apiMarker(pin.key());
            return m == null ? pin : apiPin(m);
        }
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
            if (o.has("layers") && o.get("layers").isJsonObject()) {
                JsonObject layers = o.getAsJsonObject("layers");
                for (MapLayer l : MapLayer.values()) {
                    JsonElement e = layers.get(l.id);
                    if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && !e.getAsBoolean()) LAYERS_OFF.add(l);
                }
                // the rest are mods' overlays, by id (a built-in layer's name has no namespace)
                for (var e : layers.entrySet()) {
                    if (!e.getKey().contains(":")) continue;
                    JsonElement v = e.getValue();
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && !v.getAsBoolean()) OVERLAYS_OFF.add(e.getKey());
                }
            }
        } catch (IOException | RuntimeException e) {
            MapMod.LOGGER.warn("unreadable map file {}", file, e);
        }
    }

    /** Switches an overlay layer on or off and saves the setting. */
    public static void toggleLayer(MapLayer layer) {
        if (!LAYERS_OFF.remove(layer)) LAYERS_OFF.add(layer);
        saveSettings();
    }

    /** Switches a mod's overlay layer on or off, by id, and saves the setting. */
    public static void setOverlay(String id, boolean on) {
        boolean changed = on ? OVERLAYS_OFF.remove(id) : OVERLAYS_OFF.add(id);
        if (changed) saveSettings();
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
        JsonObject layers = new JsonObject();
        for (MapLayer l : MapLayer.values()) layers.addProperty(l.id, !LAYERS_OFF.contains(l));
        for (com.meakaandre.mapmod.api.MapOverlay.Registered r : com.meakaandre.mapmod.api.MapOverlay.layers()) {
            layers.addProperty(r.id().toString(), !OVERLAYS_OFF.contains(r.id().toString()));
        }
        for (String id : OVERLAYS_OFF) if (!layers.has(id)) layers.addProperty(id, false);
        o.add("layers", layers);
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
