package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapGlyphs;
import com.meakaandre.mapmod.api.MapMarkers;
import com.meakaandre.mapmod.api.MapModApi;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
import com.meakaandre.mapmod.api.PoiKind;
import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapMarker;
import com.meakaandre.mapmod.map.MapPayloads;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The HUD: Satisfactory's compass strip across the top. The boss bars move down under it. (There is no minimap: the
 * map is the fullscreen one only.)
 */
public final class MapHud {
    private static final int BAND = 13, TOP = 3;
    private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    /** The compass strip's width: this share of the screen, within these bounds (pixels). */
    private static final double COMPASS_SHARE = 0.45;
    private static final int COMPASS_MIN_W = 180, COMPASS_MAX_W = 320;
    /** Degrees the strip spans, and how far either side of the middle a mark still shows. */
    private static final double COMPASS_SPAN = 180.0;
    private static final int COMPASS_HALF_VIEW = 88;
    /** Degrees between the strip's ticks, and between the points of the compass. */
    private static final int TICK_STEP = 15, POINT_STEP = 45;
    /** Scale of the small text under the strip. */
    private static final float SMALL_TEXT = 0.75f;
    /** The same scale as a double, for measuring the small text's width. */
    private static final double SMALL_TEXT_WIDTH = SMALL_TEXT;
    /** Pixels outside a view an icon may stand and still be drawn (its half size). */
    private static final int ICON_MARGIN = 8;

    private MapHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(MapMod.id("compass"), MapHud::compass);
        HudElementRegistry.replaceElement(VanillaHudElements.BOSS_BAR, vanilla -> (g, delta) -> shifted(g, delta, vanilla, BAND + 12));
    }

    private static void shifted(GuiGraphicsExtractor g, DeltaTracker delta, HudElement vanilla, int dy) {
        g.pose().pushMatrix();
        g.pose().translate(0, dy);
        vanilla.extractRenderState(g, delta);
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------------------------------------ compass

    /** The compass bearing of the way the player looks: 0 north, 90 east. */
    public static double heading(LocalPlayer player) {
        return wrap360(player.getYRot() + 180);
    }

    private static double wrap360(double d) {
        d %= 360;
        return d < 0 ? d + 360 : d;
    }

    private static double wrap180(double d) {
        d = wrap360(d);
        return d > 180 ? d - 360 : d;
    }

    /** The bearing from the player to a point. */
    private static double bearing(LocalPlayer p, double x, double z) {
        return wrap360(Math.toDegrees(Math.atan2(x - p.getX(), -(z - p.getZ()))));
    }

    private record Mark(double bearing, int kind, Object what, double distance, boolean pinned) {
    }

    public static void compass(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        int sw = g.guiWidth();
        int width = Math.clamp((int) (sw * COMPASS_SHARE), COMPASS_MIN_W, COMPASS_MAX_W);
        int cx = sw / 2, left = cx - width / 2, right = cx + width / 2;
        double perDeg = width / COMPASS_SPAN;
        double heading = heading(player);
        g.fill(left, TOP, right, TOP + BAND, 0x90101418);
        g.fill(left, TOP, right, TOP + 1, 0x50FFFFFF);
        g.fill(left, TOP + BAND - 1, right, TOP + BAND, 0x50FFFFFF);
        // the scale: a tick every 15 degrees, the points of the compass every 45
        for (int d = 0; d < 360; d += TICK_STEP) {
            double rel = wrap180(d - heading);
            if (Math.abs(rel) > COMPASS_HALF_VIEW) continue;
            int x = (int) Math.round(cx + rel * perDeg);
            if (d % POINT_STEP == 0) {
                String label = POINTS[d / POINT_STEP];
                int colour = d == 0 ? 0xFFFF6A4D : 0xFFE6E6E6;
                g.centeredText(mc.font, label, x, TOP + 3, colour);
            } else {
                g.fill(x, TOP + BAND - 4, x + 1, TOP + BAND - 1, 0x90FFFFFF);
            }
        }
        // what it shows: pins first so they draw on top, then the rest under them
        List<Mark> marks = new ArrayList<>();
        boolean overworld = MapClient.mappedHere();
        String dim = mc.level == null ? "" : mc.level.dimension().identifier().toString();
        // a layer a mod locks stays off the strip too (MM-6)
        boolean pinsOn = !MapLayer.PINS.locked(), markersOn = !MapLayer.MARKERS.locked(), poisOn = !MapLayer.POIS.locked();
        for (MapClient.Pin pin : pinsOn ? MapClient.PINS.values() : List.<MapClient.Pin>of()) {
            MapClient.Pin live = MapClient.live(pin);
            if (pin.key().startsWith("poi:") && (!overworld || !poisOn)) continue;
            if (pin.key().startsWith("marker:")) {
                MapMarker m = marker(pin.key());
                if (m == null || !m.dimension.equals(dim) || !markersOn) continue;
            }
            if (pin.key().startsWith(MapClient.API_PIN)) {
                MapMarkers.Marker m = MapClient.apiMarker(pin.key());
                if (m == null || !m.dimension().identifier().toString().equals(dim) || !markersOn) continue;
            }
            marks.add(new Mark(bearing(player, live.x() + 0.5, live.z() + 0.5), 0, live, Math.hypot(live.x() + 0.5 - player.getX(), live.z() + 0.5 - player.getZ()), true));
        }
        for (MapMarker m : markersOn ? MapClient.markers : List.<MapMarker>of()) {
            if (!m.dimension.equals(dim) || !MapFilters.shows(m) || MapClient.pinned(m.pinKey())) continue;
            marks.add(new Mark(bearing(player, m.x + 0.5, m.z + 0.5), 1, m, 0, false));
        }
        if (MapFilters.showsPlayers() && !MapLayer.PLAYERS.locked()) {
            for (MapPayloads.Mate mate : MapClient.mates) marks.add(new Mark(bearing(player, mate.x(), mate.z()), 2, mate, 0, false));
        }
        if (overworld && poisOn) {
            int px = player.getBlockX(), pz = player.getBlockZ();
            List<Long> near = new ArrayList<>();
            long here = MapGrid.regionOfBlock(px, pz);
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) near.add(MapGrid.key(MapGrid.keyX(here) + dx, MapGrid.keyZ(here) + dz));
            // the nearest of each kind that always shows (a base, say), however far
            Map<String, MapPoi> nearest = new HashMap<>();
            Map<String, Double> nearestD = new HashMap<>();
            for (List<MapPoi> list : MapClient.POIS.values()) {
                for (MapPoi poi : list) {
                    PoiKind kind = MapModApi.kind(poi.kind());
                    if (kind == null || !kind.compassNearest()) continue;
                    double d = Math.hypot(poi.x() - player.getX(), poi.z() - player.getZ());
                    if (d < nearestD.getOrDefault(poi.kind(), Double.MAX_VALUE)) {
                        nearestD.put(poi.kind(), d);
                        nearest.put(poi.kind(), poi);
                    }
                }
            }
            for (MapPoi poi : nearest.values()) {
                if (MapFilters.shows(poi) && !MapClient.pinned(poi.pinKey())) marks.add(new Mark(bearing(player, poi.x(), poi.z()), 4, poi, nearestD.get(poi.kind()), false));
            }
            // the rest within their kind's range, where explored
            for (MapPoi poi : MapClient.poisIn(near)) {
                PoiKind kind = MapModApi.kind(poi.kind());
                if (kind == null || kind.compassNearest() || kind.compassRange() <= 0 || !MapFilters.shows(poi) || MapClient.pinned(poi.pinKey())) continue;
                double d = Math.hypot(poi.x() - player.getX(), poi.z() - player.getZ());
                if (d <= kind.compassRange() && MapClient.explored(poi.x(), poi.z())) marks.add(new Mark(bearing(player, poi.x(), poi.z()), 3, poi, d, false));
            }
        }
        marks.sort((a, b) -> Boolean.compare(a.pinned, b.pinned));
        int iy = TOP + BAND / 2;
        // the distance labels under the strip: one too close to another (markers at nearly the same bearing) goes down a
        // row, so they never run into each other; the bearing under the middle keeps its place
        List<List<int[]>> labelRows = new ArrayList<>();
        String bearingText = String.format(Locale.ROOT, "%03d", Math.round(heading) % 360);
        int bearingHalf = (int) Math.ceil(mc.font.width(bearingText) * SMALL_TEXT_WIDTH / 2) + 1;
        labelRows.add(new ArrayList<>(List.of(new int[]{cx + 1 - bearingHalf, cx + 1 + bearingHalf})));
        // pins at (nearly) the same bearing are spread apart a little, so each icon stays readable
        Map<Mark, Integer> spread = spreadPins(marks, heading, cx, perDeg);
        for (Mark mark : marks) {
            double rel = wrap180(mark.bearing - heading);
            boolean edge = Math.abs(rel) > COMPASS_HALF_VIEW;
            if (edge && !mark.pinned) continue;
            // a pin behind the player waits at the end of the strip it is nearest
            int x = spread.getOrDefault(mark, (int) Math.round(cx + Math.clamp(rel, -COMPASS_HALF_VIEW, COMPASS_HALF_VIEW) * perDeg));
            switch (mark.kind) {
                case 0 -> {
                    MapClient.Pin pin = (MapClient.Pin) mark.what;
                    MapIcons.centred(g, MapGlyphs.DISC, x, iy, 0xC0101418);
                    MapIcons.centred(g, pin.icon(), x, iy, pin.argb());
                    String label = MapDraw.distance(mark.distance);
                    int row = labelRow(labelRows, x, (int) Math.ceil(mc.font.width(label) * SMALL_TEXT_WIDTH / 2) + 2);
                    small(g, label, x, TOP + BAND + 2 + row * LABEL_ROW, 0xFFFFFFFF);
                }
                case 1 -> MapDraw.marker(g, (MapMarker) mark.what, x, iy);
                case 2 -> MapIcons.centred(g, MapGlyphs.ARROW, x, iy, MapDraw.MATE);
                case 4 -> MapDraw.poi(g, (MapPoi) mark.what, x, iy);
                default -> {
                    MapPoi poi = (MapPoi) mark.what;
                    MapIcons.centred(g, MapDraw.icon(poi), x, iy, (MapDraw.colour(poi) & 0xFFFFFF) | 0xB0000000);
                }
            }
        }
        // the middle, and the bearing under it
        g.fill(cx, TOP - 1, cx + 1, TOP + BAND + 1, 0xFFFFA530);
        small(g, bearingText, cx + 1, TOP + BAND + 2, 0xFFFFA530);
    }

    /** The least distance between two pin icons on the strip. */
    private static final int PIN_GAP = 10;

    /**
     * Where each pin's icon goes: at its bearing, except that pins closer than {@link #PIN_GAP} to each other are spread
     * out evenly around where the group stands.
     */
    private static Map<Mark, Integer> spreadPins(List<Mark> marks, double heading, int cx, double perDeg) {
        List<Mark> pins = new ArrayList<>();
        for (Mark m : marks) if (m.pinned) pins.add(m);
        Map<Mark, Integer> out = new HashMap<>();
        if (pins.isEmpty()) return out;
        List<int[]> xs = new ArrayList<>();
        for (int i = 0; i < pins.size(); i++) {
            double rel = wrap180(pins.get(i).bearing - heading);
            xs.add(new int[]{(int) Math.round(cx + Math.clamp(rel, -COMPASS_HALF_VIEW, COMPASS_HALF_VIEW) * perDeg), i});
        }
        xs.sort((a, b) -> Integer.compare(a[0], b[0]));
        int start = 0;
        while (start < xs.size()) {
            int end = start;
            while (end + 1 < xs.size() && xs.get(end + 1)[0] - xs.get(end)[0] < PIN_GAP) end++;
            int n = end - start + 1;
            double middle = 0;
            for (int i = start; i <= end; i++) middle += xs.get(i)[0];
            middle /= n;
            for (int i = start; i <= end; i++) {
                int x = n == 1 ? xs.get(i)[0] : (int) Math.round(middle + (i - start - (n - 1) / 2.0) * PIN_GAP);
                out.put(pins.get(xs.get(i)[1]), x);
            }
            start = end + 1;
        }
        return out;
    }

    /** Mods' markers drawn on the fullscreen map since the game started (for the selftests). */
    public static long apiMarkersDrawn;

    /** Height of a row of distance labels under the strip. */
    private static final int LABEL_ROW = 7;
    /** Rows of distance labels at most (a crowd past that shares the last row). */
    private static final int LABEL_ROWS = 4;

    /** The first row where a label of half width {@code half} centred on x overlaps no other; it is taken there. */
    private static int labelRow(List<List<int[]>> rows, int x, int half) {
        int from = x - half, to = x + half;
        for (int r = 0; r < LABEL_ROWS; r++) {
            if (r == rows.size()) rows.add(new ArrayList<>());
            boolean free = true;
            for (int[] span : rows.get(r)) {
                if (from < span[1] && to > span[0]) {
                    free = false;
                    break;
                }
            }
            if (free || r == LABEL_ROWS - 1) {
                rows.get(r).add(new int[]{from, to});
                return r;
            }
        }
        return LABEL_ROWS - 1;
    }

    private static MapMarker marker(String pinKey) {
        for (MapMarker m : MapClient.markers) if (m.pinKey().equals(pinKey)) return m;
        return null;
    }

    /** Text at three quarters size, centred on x. */
    private static void small(GuiGraphicsExtractor g, String text, int x, int y, int colour) {
        Minecraft mc = Minecraft.getInstance();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(SMALL_TEXT, SMALL_TEXT);
        g.text(mc.font, text, -mc.font.width(text) / 2, 0, colour, true);
        g.pose().popMatrix();
    }

    /**
     * Everything on the map inside a view, as the filters allow: POIs where the group has explored, markers, pins,
     * moving things and the group's players. Returns nothing; the fullscreen map does its own hover on top.
     */
    public static void things(GuiGraphicsExtractor g, MapDraw.View v) {
        if (MapLayer.POIS.shown()) {
            for (MapPoi poi : MapClient.poisIn(v.regions())) {
                if (!MapFilters.shows(poi) && !MapClient.pinned(poi.pinKey())) continue;
                if (!MapClient.explored(poi.x(), poi.z())) continue;
                double sx = v.sx(poi.x() + 0.5), sy = v.sy(poi.z() + 0.5);
                if (!v.inside(sx, sy, ICON_MARGIN)) continue;
                MapDraw.poi(g, poi, (int) Math.round(sx), (int) Math.round(sy));
            }
        }
        if (MapLayer.MARKERS.shown()) {
            for (MapMarker m : MapClient.markers) {
                if (!m.dimension.equals("minecraft:overworld") || (!MapFilters.shows(m) && !MapClient.pinned(m.pinKey()))) continue;
                double sx = v.sx(m.x + 0.5), sy = v.sy(m.z + 0.5);
                if (v.inside(sx, sy, ICON_MARGIN)) MapDraw.marker(g, m, (int) Math.round(sx), (int) Math.round(sy));
            }
            // mods' markers (api.MapMarkers), under the markers filter
            if (MapFilters.on(MapModApi.MARKERS.filterKey())) {
                for (MapMarkers.Marker m : MapMarkers.in(Level.OVERWORLD)) {
                    double sx = v.sx(m.x()), sy = v.sy(m.z());
                    if (!v.inside(sx, sy, ICON_MARGIN)) continue;
                    MapIcons.shadow(g, m.glyph(), (int) Math.round(sx), (int) Math.round(sy));
                    MapIcons.centred(g, m.glyph(), (int) Math.round(sx), (int) Math.round(sy), m.colour());
                    apiMarkersDrawn++;
                }
            }
        }
        if (MapLayer.PINS.shown()) {
            for (MapClient.Pin pin : MapClient.PINS.values()) {
                if (!pin.key().startsWith("poi:")) continue;
                double sx = v.sx(pin.x() + 0.5), sy = v.sy(pin.z() + 0.5);
                if (v.inside(sx, sy, ICON_MARGIN)) MapDraw.pin(g, (int) Math.round(sx), (int) Math.round(sy) - 7);
            }
        }
        if (MapLayer.POIS.shown()) {
            for (MovingPoi m : MapClient.moving) {
                double sx = v.sx(m.x()), sy = v.sy(m.z());
                if (v.inside(sx, sy, ICON_MARGIN)) MapDraw.moving(g, m, (float) sx, (float) sy);
            }
        }
        if (MapLayer.PLAYERS.shown() && MapFilters.showsPlayers()) {
            for (MapPayloads.Mate mate : MapClient.mates) {
                double sx = v.sx(mate.x()), sy = v.sy(mate.z());
                if (v.inside(sx, sy, ICON_MARGIN)) MapDraw.mate(g, mate, (float) sx, (float) sy);
            }
        }
    }
}
