package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.api.MapGlyphs;
import com.meakaandre.mapmod.api.MapLock;
import com.meakaandre.mapmod.api.MapMarkers;
import com.meakaandre.mapmod.api.MapOverlay;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
import com.meakaandre.mapmod.client.paint.PaintMap;
import com.meakaandre.mapmod.map.MapMarker;
import com.meakaandre.mapmod.map.MapPayloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The fullscreen map (M), laid out like Satisfactory's: the filter panel down the left, the painted map
 * ({@link PaintMap}) filling the rest, with everything on it drawn over, and a button per overlay layer
 * ({@link MapLayer}) in the top bar. Drag to move (let go while moving and it glides on a moment, framerate-independent,
 * until any click), scroll to zoom round the mouse, C to centre on the player. Left click pins or unpins what is under the mouse (pins
 * show on the compass with their distance); right click on an empty spot places a marker there, and on one of your
 * markers (or a shared one of your group) changes or removes it. A marker gets an icon, a colour and a name, and is
 * private or shared with the group. Mods' overlay layers ({@link MapOverlay}) draw over the painted map, each with a
 * button in the top bar after the map's own; while a mod locks the map ({@link MapLock}) it shows a locked panel instead.
 */
public class MapScreen extends Screen {
    private static final int PANEL = 140, BAR = 16, FOOT = 14, ROW = 12;
    /** The top bar's contours button: its left edge from the right of the screen, and its width. */
    private static final int CONTOURS_RIGHT = 200, CONTOURS_W = 96;
    /** The widest the position under the mouse is expected to get, for laying out the top bar (MM-7). */
    private static final String COORDS_TEMPLATE = "X -0000000   Z -0000000";
    /** Lines of the hint at the bottom at most, and their height. */
    private static final int HINT_LINES = 3, HINT_LINE = 10;
    /** Zoom limits (screen pixels a block): far enough out for a whole explored world, drawn from the mip pyramid. */
    private static final double MIN_SCALE = 1.0 / 1024, MAX_SCALE = 8;
    /** How quickly an animated zoom closes on its target (per second, of the remaining distance in log scale). */
    private static final double ZOOM_RATE = 14;
    /** Zoom factor of one scroll step in and out. */
    private static final double ZOOM_IN = 1.25, ZOOM_OUT = 0.8;
    /** Length of the scale bar (pixels). */
    private static final int SCALE_BAR = 80;
    /** The glide after a drag: how quickly it slows (per second), the speed it stops below (screen pixels a second),
     * the fastest it starts (the same), and how recently the mouse must have moved for a release to glide. */
    private static final double GLIDE_FRICTION = 6, GLIDE_STOP = 4, GLIDE_MAX = 3000;
    private static final long GLIDE_WINDOW_NANOS = 60_000_000L;
    /** The layer buttons in the top bar: their size and the room between them. */
    private static final int LAYER_W = 14, LAYER_STEP = 16;
    /** How near the mouse (pixels) a thing must be to be hovered. */
    private static final int HOVER_RADIUS = 7;
    /** Height a new marker gets where the map has no ground recorded. */
    private static final int DEFAULT_MARKER_Y = 64;
    /** Categories whose kinds are folded out in the panel. */
    private static final Set<String> OPEN = new HashSet<>();
    private static double lastScale = 1;

    private double cx, cz, scale = lastScale, target = lastScale;
    /** Animated zoom: the screen point it zooms round and the world point that stays under it. */
    private boolean anchored;
    private double anchorX, anchorY, anchorWx, anchorWz;
    private long lastFrameNanos;
    private boolean placed;
    private boolean dragging;
    /** The drag's speed (world blocks a second, smoothed) and when the mouse last moved; the glide's after release. */
    private double dragVx, dragVz, glideVx, glideVz;
    private long lastDragNanos;
    private @Nullable Hover hover;

    // the marker form
    private boolean editing;
    private @Nullable MapMarker editingMarker;
    private int editX, editY, editZ, editIcon, editColour;
    private boolean editShared;
    private @Nullable EditBox nameBox;
    private @Nullable Button privacyButton;

    /** What is under the mouse. */
    private record Hover(List<Component> lines, MapClient.@Nullable Pin pin, @Nullable MapMarker marker) {
    }

    public MapScreen() {
        super(Component.translatable("mapmod.map.title"));
    }

    /** Folds out a category's switches in the filter panel (for the smoke run's screenshots). */
    public static void unfold(String category) {
        OPEN.add(category);
    }

    /** Where the map opens: a world position and a scale (screen pixels a block; 0 keeps the last). */
    public void centre(int x, int z, float zoom) {
        cx = x;
        cz = z;
        if (zoom > 0) scale = target = Math.clamp(zoom, MIN_SCALE, MAX_SCALE);
        anchored = false;
        placed = true;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        if (!placed && mc.player != null) {
            cx = mc.player.getX();
            cz = mc.player.getZ();
            placed = true;
        }
        if (editing) {
            buildForm();
        } else if (!MapLock.mapLocked() && !MapLock.layerLocked(MapLock.CONTOURS)) {
            addRenderableWidget(Button.builder(contoursText(), b -> {
                toggleContours();
                b.setMessage(contoursText());
            }).bounds(width - CONTOURS_RIGHT, 1, CONTOURS_W, 14).build());
        }
    }

    /** Switches contour lines on or off (the top bar's button; also used by the selftests). */
    public static void toggleContours() {
        MapClient.setContours(!MapClient.contours);
    }

    private static Component contoursText() {
        return Component.translatable(MapClient.contours ? "mapmod.map.contours_on" : "mapmod.map.contours_off");
    }

    @Override
    public void removed() {
        // the map's tiles and their textures are let go as soon as it closes
        PaintMap.close();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (!MapClient.mappedHere() || !MapClient.unlocked(MapFeature.FULLSCREEN)) {
            onClose();
            return;
        }
        // a lock set or lifted while the map is open: the top bar's buttons follow, and an open marker form closes
        boolean lockNow = MapLock.mapLocked() || MapLock.layerLocked(MapLock.CONTOURS);
        if (lockNow != lockedBar) {
            lockedBar = lockNow;
            if (MapLock.mapLocked() && editing) closeForm();
            else rebuildWidgets();
        }
    }

    /** Whether the top bar was last built with the map or its contours locked. */
    private boolean lockedBar = MapLock.mapLocked() || MapLock.layerLocked(MapLock.CONTOURS);

    /** True while a mod locks the map and this screen shows the locked panel (for the selftests). */
    public boolean locked() {
        return MapLock.mapLocked();
    }

    private MapDraw.View view() {
        return new MapDraw.View(cx, cz, scale, PANEL, BAR, width - PANEL, height - BAR - foot());
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        animate();
        g.fill(0, 0, width, height, 0xFF151A20);
        MapDraw.View v = view();
        Minecraft mc = Minecraft.getInstance();
        boolean locked = MapLock.mapLocked();
        g.enableScissor(v.left(), v.top(), v.left() + v.width(), v.top() + v.height());
        if (locked) {
            lockedPanel(g, v);
        } else {
            PaintMap.draw(g, v);
            overlays(g, v);
            MapHud.things(g, v);
            MobRadar.draw(g, v);
            if (mc.player != null && MapLayer.PLAYERS.shown()) MapDraw.self(g, (float) v.sx(mc.player.getX()), (float) v.sy(mc.player.getZ()));
        }
        g.disableScissor();
        panel(g, mouseX, mouseY);
        // the top bar: title, the scale, and the world position under the mouse
        g.fill(0, 0, width, BAR, 0xF0101418);
        g.text(font, title, 6, 4, 0xFFFFA530, false);
        if (!locked) {
            String scaleText = MapDraw.distance(SCALE_BAR / scale);
            g.fill(width - 96, 10, width - 16, 11, 0xFFE6E6E6);
            g.text(font, scaleText, width - 96, 1, 0xFFE6E6E6, false);
        }
        if (!locked) layerButtons(g, mouseX, mouseY);
        Bar bar = bar();
        int foot = bar.foot();
        g.fill(0, height - foot, width, height, 0xF0101418);
        for (int i = 0; i < bar.hint().size(); i++) g.text(font, bar.hint().get(i), 6, height - foot + 3 + i * HINT_LINE, 0xFF8A949E, false);
        if (!locked && inMap(mouseX, mouseY)) {
            String at = String.format(Locale.ROOT, "X %d   Z %d", (int) Math.floor(v.wx(mouseX)), (int) Math.floor(v.wz(mouseY)));
            if (bar.coordsInFoot()) g.text(font, at, width - 6 - font.width(at), height - foot + 3, 0xFFB0B8C0, false);
            else g.text(font, at, bar.coordsX(), 4, 0xFFB0B8C0, false);
        }
    }

    // ------------------------------------------------------------------------------------------------ bar layout (MM-7)

    /** A box in the top bar: x and width (they are all a row high). */
    public record Box(String what, int x, int w) {
        public boolean overlaps(Box o) {
            return x < o.x + o.w && o.x < x + w;
        }
    }

    /**
     * Where the top bar's things go and how high the bottom row is: the title, then the position under the mouse (by
     * the map's left edge, else just after the title, else in the bottom row on the right when the bar is too full), and
     * from the right the scale, the contours button and the layer buttons. The hint wraps to the bottom row's width.
     */
    public record Bar(List<Box> boxes, int coordsX, boolean coordsInFoot, List<net.minecraft.util.FormattedCharSequence> hint, int foot,
                      int hintWidth) {
    }

    private @Nullable Bar barCache;
    private int barWidth = -1, barLayers = -1;

    private Bar bar() {
        int layers = MapLock.mapLocked() ? 0 : layerButtons().size();
        if (barCache == null || barWidth != width || barLayers != layers) {
            barCache = layout(font, width, layers, !MapLock.mapLocked() && !MapLock.layerLocked(MapLock.CONTOURS), !MapLock.mapLocked(), title);
            barWidth = width;
            barLayers = layers;
        }
        return barCache;
    }

    private int foot() {
        return bar().foot();
    }

    /** The top bar and bottom row laid out for a screen width (GUI pixels) and a number of layer buttons. */
    public static Bar layout(net.minecraft.client.gui.Font font, int width, int layers, boolean contours, boolean scaleBar, Component title) {
        List<Box> boxes = new ArrayList<>();
        int titleRight = 6 + font.width(title);
        boxes.add(new Box("title", 6, font.width(title)));
        int right = width;
        if (scaleBar) {
            boxes.add(new Box("scale", width - 96, 80));
            right = width - 96;
        }
        if (contours) {
            boxes.add(new Box("contours", width - CONTOURS_RIGHT, CONTOURS_W));
            right = width - CONTOURS_RIGHT;
        }
        for (int i = 0; i < layers; i++) {
            int x = layerX(width, i, layers);
            boxes.add(new Box("layer " + i, x, LAYER_W));
            right = Math.min(right, x);
        }
        int cw = font.width(COORDS_TEMPLATE);
        int coordsX;
        boolean inFoot = false;
        if (PANEL + 6 + cw + 6 <= right) coordsX = PANEL + 6;
        else if (titleRight + 10 + cw + 6 <= right) coordsX = titleRight + 10;
        else {
            coordsX = -1;
            inFoot = true;
        }
        if (!inFoot) boxes.add(new Box("coordinates", coordsX, cw));
        int hintWidth = Math.max(40, width - 12 - (inFoot ? cw + 12 : 0));
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.translatable("mapmod.map.help"), hintWidth);
        if (lines.size() > HINT_LINES) lines = lines.subList(0, HINT_LINES);
        int foot = Math.max(FOOT, 4 + HINT_LINE * Math.max(1, lines.size()));
        return new Bar(boxes, coordsX, inFoot, lines, foot, hintWidth);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        hover = editing || MapLock.mapLocked() ? null : findHover(mouseX, mouseY);
        if (editing) formBack(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (editing) {
            formFront(g, mouseX, mouseY);
        } else if (hover != null) {
            g.setComponentTooltipForNextFrame(font, hover.lines, mouseX, mouseY);
        } else {
            LayerButton l = MapLock.mapLocked() ? null : layerAt(mouseX, mouseY);
            if (l != null) {
                g.setComponentTooltipForNextFrame(font, List.of(l.label(), Component.translatable(l.on() ? "mapmod.map.layer_on" : "mapmod.map.layer_off")
                    .withColor(0x8A949E)), mouseX, mouseY);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ locked, overlays

    /** The map while a mod locks it: no terrain and nothing on it, a padlock, "Map locked" and the mod's reason. */
    private void lockedPanel(GuiGraphicsExtractor g, MapDraw.View v) {
        g.fill(v.left(), v.top(), v.left() + v.width(), v.top() + v.height(), 0xFF1B2128);
        int cx = v.left() + v.width() / 2, cy = v.top() + v.height() / 2;
        Component title = Component.translatable("mapmod.map.locked_title");
        Component reason = MapLock.reason();
        // the frame fits the longer line, at least 140 pixels wide
        int half = Math.max(70, Math.max(font.width(title), reason == null ? 0 : font.width(reason)) / 2 + 10);
        g.fill(cx - half, cy - 34, cx + half, cy + 30, 0xF0181E25);
        g.outline(cx - half, cy - 34, half * 2, 64, 0xFFFFA530);
        MapIcons.centred(g, MapGlyphs.LOCK, cx, cy - 18, 0xFFFFA530);
        g.text(font, title, cx - font.width(title) / 2, cy - 4, 0xFFE6E6E6, false);
        if (reason != null) g.text(font, reason, cx - font.width(reason) / 2, cy + 9, 0xFFB0B8C0, false);
        lockedFrames++;
    }

    /** Frames the locked panel was drawn (for the selftests). */
    public static long lockedFrames;

    /** Mods' overlay layers that are switched on and not locked, in the order registered; one that throws is skipped. */
    private static void overlays(GuiGraphicsExtractor g, MapDraw.View v) {
        for (MapOverlay.Registered r : MapOverlay.layers()) {
            if (!MapOverlay.shown(r.id())) continue;
            g.pose().pushMatrix();
            try {
                r.layer().draw(g, v);
            } catch (RuntimeException e) {
                if (FAILED.add(r.id())) MapMod.LOGGER.warn("map overlay {} failed to draw", r.id(), e);
            } finally {
                g.pose().popMatrix();
            }
        }
    }

    /** Overlays that threw once already (logged once each). */
    private static final Set<Identifier> FAILED = new HashSet<>();

    // ------------------------------------------------------------------------------------------------ layer buttons

    /** A button in the top bar: one of the map's own layers or a mod's overlay. */
    private record LayerButton(int glyph, Component label, boolean on, Runnable toggle) {
    }

    /** How many layer buttons the top bar has now (for the selftests). */
    public static int layerCount() {
        return layerButtons().size();
    }

    /** The top bar's layer buttons: the map's own, then mods' overlays, leaving out locked ones. */
    private static List<LayerButton> layerButtons() {
        List<LayerButton> out = new ArrayList<>();
        for (MapLayer l : MapLayer.values()) if (!l.locked()) out.add(new LayerButton(l.glyph, l.label(), l.on(), () -> toggleLayer(l)));
        for (MapOverlay.Registered r : MapOverlay.layers()) {
            if (MapLock.layerLocked(r.id())) continue;
            boolean on = MapOverlay.enabled(r.id());
            out.add(new LayerButton(r.glyph(), r.label(), on, () -> MapOverlay.setEnabled(r.id(), !on)));
        }
        return out;
    }

    private int layerX(int i, int count) {
        return layerX(width, i, count);
    }

    private static int layerX(int width, int i, int count) {
        return width - CONTOURS_RIGHT - (count - i) * LAYER_STEP - 4;
    }

    private @Nullable LayerButton layerAt(double mx, double my) {
        if (my < 1 || my >= 1 + LAYER_W) return null;
        List<LayerButton> all = layerButtons();
        for (int i = 0; i < all.size(); i++) {
            int x = layerX(i, all.size());
            if (mx >= x && mx < x + LAYER_W) return all.get(i);
        }
        return null;
    }

    /** A button per overlay layer: its glyph, bright and framed while the layer is on, dim while it is off. */
    private void layerButtons(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<LayerButton> all = layerButtons();
        boolean overAny = my(mouseY);
        for (int i = 0; i < all.size(); i++) {
            LayerButton l = all.get(i);
            int x = layerX(i, all.size()), y = 1;
            boolean on = l.on(), over = overAny && mouseX >= x && mouseX < x + LAYER_W;
            g.fill(x, y, x + LAYER_W, y + LAYER_W, on ? 0xFF242C35 : 0xFF14191F);
            g.outline(x, y, LAYER_W, LAYER_W, over ? 0xFFFFFFFF : on ? 0xFFFFA530 : 0xFF3A444F);
            MapIcons.centred(g, l.glyph(), x + LAYER_W / 2, y + LAYER_W / 2, on ? 0xFFE6E6E6 : 0xFF56606A);
        }
    }

    private static boolean my(double y) {
        return y >= 1 && y < 1 + LAYER_W;
    }

    /** Switches a layer as its button does (also used by the selftests). */
    public static void toggleLayer(MapLayer l) {
        MapClient.toggleLayer(l);
    }

    private boolean inMap(double x, double y) {
        return x >= PANEL && x < width && y >= BAR && y < height - foot();
    }

    // ------------------------------------------------------------------------------------------------ filter panel

    private record Row(MapFilters.Filter filter, int y, boolean category) {
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        int y = BAR + 18;
        for (MapFilters.Filter f : MapFilters.all()) {
            if (f.parent() != null && !OPEN.contains(f.parent())) continue;
            rows.add(new Row(f, y, f.parent() == null));
            y += ROW;
        }
        return rows;
    }

    private boolean hasKinds(String category) {
        for (MapFilters.Filter f : MapFilters.all()) if (category.equals(f.parent())) return true;
        return false;
    }

    private void panel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(0, BAR, PANEL, height - foot(), 0xF0181E25);
        g.fill(PANEL - 1, BAR, PANEL, height - foot(), 0xFF2C3540);
        g.text(font, Component.translatable("mapmod.map.filters"), 8, BAR + 5, 0xFFFFA530, false);
        // a locked map shows none of what is on it, its switches included
        if (MapLock.mapLocked()) return;
        for (Row row : rows()) {
            int x = row.category ? 8 : 20;
            boolean on = MapFilters.on(row.filter.key()) && (row.filter.parent() == null || MapFilters.on(row.filter.parent()));
            boolean over = mouseX >= 0 && mouseX < PANEL && mouseY >= row.y && mouseY < row.y + ROW;
            if (over) g.fill(2, row.y, PANEL - 3, row.y + ROW, 0x30FFFFFF);
            g.outline(x, row.y + 2, 8, 8, 0xFFA0A8B0);
            if (on) g.fill(x + 2, row.y + 4, x + 6, row.y + 8, 0xFFFFA530);
            g.text(font, row.filter.label(), x + 12, row.y + 2, row.category ? 0xFFE6E6E6 : 0xFFB8C0C8, false);
            if (row.category && hasKinds(row.filter.key())) {
                g.text(font, OPEN.contains(row.filter.key()) ? "-" : "+", PANEL - 12, row.y + 2, 0xFF8A949E, false);
            }
        }
    }

    private boolean panelClick(double mx, double my) {
        for (Row row : rows()) {
            if (my < row.y || my >= row.y + ROW) continue;
            if (row.category && hasKinds(row.filter.key()) && mx >= PANEL - 18) {
                if (!OPEN.remove(row.filter.key())) OPEN.add(row.filter.key());
            } else {
                MapFilters.toggle(row.filter.key());
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ hover

    private @Nullable Hover findHover(int mx, int my) {
        if (!inMap(mx, my)) return null;
        MapDraw.View v = view();
        Minecraft mc = Minecraft.getInstance();
        double best = HOVER_RADIUS * HOVER_RADIUS;
        Hover found = null;
        for (MapMarker m : MapLayer.MARKERS.shown() ? MapClient.markers : List.<MapMarker>of()) {
            if (!m.dimension.equals("minecraft:overworld") || (!MapFilters.shows(m) && !MapClient.pinned(m.pinKey()))) continue;
            double d = sq(v.sx(m.x + 0.5) - mx, v.sy(m.z + 0.5) - my);
            if (d >= best) continue;
            best = d;
            List<Component> lines = new ArrayList<>();
            lines.add(MapDraw.markerLabel(m));
            lines.add(Component.translatable(m.death || !m.shared ? "mapmod.map.private" : MapClient.sharedKey).withColor(0xA0A8B0));
            where(lines, m.x, m.z);
            lines.add(Component.translatable(MapClient.pinned(m.pinKey()) ? "mapmod.map.click_unpin" : "mapmod.map.click_pin").withColor(0x8A949E));
            String me = mc.player == null ? "" : mc.player.getUUID().toString();
            if (m.owner.equals(me) || (m.shared && !m.death)) {
                lines.add(Component.translatable("mapmod.map.click_edit").withColor(0x8A949E));
            }
            found = new Hover(lines, MapClient.markerPin(m), m);
        }
        boolean modMarkers = MapLayer.MARKERS.shown() && MapFilters.on(com.meakaandre.mapmod.api.MapModApi.MARKERS.filterKey());
        for (MapMarkers.Marker m : modMarkers ? MapMarkers.in(Level.OVERWORLD) : List.<MapMarkers.Marker>of()) {
            double d = sq(v.sx(m.x()) - mx, v.sy(m.z()) - my);
            if (d >= best) continue;
            best = d;
            List<Component> lines = new ArrayList<>();
            if (m.label() != null) lines.add(m.label());
            where(lines, (int) Math.floor(m.x()), (int) Math.floor(m.z()));
            MapClient.Pin pin = MapClient.apiPin(m);
            lines.add(Component.translatable(MapClient.pinned(pin.key()) ? "mapmod.map.click_unpin" : "mapmod.map.click_pin").withColor(0x8A949E));
            found = new Hover(lines, pin, null);
        }
        for (MapPoi poi : MapLayer.POIS.shown() ? MapClient.poisIn(v.regions()) : List.<MapPoi>of()) {
            if ((!MapFilters.shows(poi) && !MapClient.pinned(poi.pinKey())) || !MapClient.explored(poi.x(), poi.z())) continue;
            double d = sq(v.sx(poi.x() + 0.5) - mx, v.sy(poi.z() + 0.5) - my);
            if (d >= best) continue;
            best = d;
            List<Component> lines = new ArrayList<>();
            lines.add(MapDraw.label(poi));
            where(lines, poi.x(), poi.z());
            lines.add(Component.translatable(MapClient.pinned(poi.pinKey()) ? "mapmod.map.click_unpin" : "mapmod.map.click_pin").withColor(0x8A949E));
            found = new Hover(lines, new MapClient.Pin(poi.pinKey(), MapDraw.label(poi).getString(), poi.x(), poi.y(), poi.z(), MapDraw.icon(poi), MapDraw.colour(poi)), null);
        }
        for (MovingPoi m : MapLayer.POIS.shown() ? MapClient.moving : List.<MovingPoi>of()) {
            double d = sq(v.sx(m.x()) - mx, v.sy(m.z()) - my);
            if (d >= best) continue;
            best = d;
            List<Component> lines = new ArrayList<>();
            lines.add(MapDraw.movingName(m));
            if (!m.note().isEmpty()) lines.add(Component.translatable(m.note()).withColor(0x8A949E));
            where(lines, (int) Math.floor(m.x()), (int) Math.floor(m.z()));
            found = new Hover(lines, null, null);
        }
        if (MapLayer.PLAYERS.shown() && MapFilters.showsPlayers()) {
            for (MapPayloads.Mate mate : MapClient.mates) {
                double d = sq(v.sx(mate.x()) - mx, v.sy(mate.z()) - my);
                if (d >= best) continue;
                best = d;
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(mate.name()));
                where(lines, (int) Math.floor(mate.x()), (int) Math.floor(mate.z()));
                found = new Hover(lines, null, null);
            }
        }
        return found;
    }

    private void where(List<Component> lines, int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        double d = mc.player == null ? 0 : Math.hypot(x + 0.5 - mc.player.getX(), z + 0.5 - mc.player.getZ());
        lines.add(Component.literal(String.format(Locale.ROOT, "X %d  Z %d  ·  %s", x, z, MapDraw.distance(d))).withColor(0xA0A8B0));
    }

    private static double sq(double a, double b) {
        return a * a + b * b;
    }

    /**
     * Moves the scale towards its target, smoothly and at the same pace at any frame rate, keeping the world point under
     * the zoom's anchor where it is.
     */
    private void animate() {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        glideStep(dt);
        if (scale == target) {
            anchored = false;
            return;
        }
        double ls = Math.log(scale), lt = Math.log(target);
        double next = ls + (lt - ls) * (1 - Math.exp(-ZOOM_RATE * dt));
        scale = Math.abs(lt - next) < 1e-3 ? target : Math.exp(next);
        if (anchored) {
            MapDraw.View v = view();
            cx += anchorWx - v.wx(anchorX);
            cz += anchorWz - v.wz(anchorY);
        }
    }

    /**
     * One step of the glide: it slows exponentially, and each step moves by the exact integral of that over the step, so
     * it goes the same way at any frame rate. It stops below {@link #GLIDE_STOP} screen pixels a second.
     */
    private void glideStep(double dt) {
        if (dragging || (glideVx == 0 && glideVz == 0) || dt <= 0) return;
        double[] x = glide(glideVx, dt), z = glide(glideVz, dt);
        cx += x[0];
        cz += z[0];
        anchorWx += x[0];
        anchorWz += z[0];
        glideVx = x[1];
        glideVz = z[1];
        if (Math.hypot(glideVx, glideVz) * scale < GLIDE_STOP) glideVx = glideVz = 0;
    }

    /** How far a glide at speed {@code v} goes in {@code dt} seconds, and its speed after: {moved, speed}. */
    public static double[] glide(double v, double dt) {
        double decay = Math.exp(-GLIDE_FRICTION * dt);
        return new double[]{v * (1 - decay) / GLIDE_FRICTION, v * decay};
    }

    /** Starts a glide at a speed in screen pixels a second, as letting go of a drag does (also used by the selftests). */
    public void fling(double pxPerSecondX, double pxPerSecondY) {
        double sp = Math.hypot(pxPerSecondX, pxPerSecondY), k = sp > GLIDE_MAX ? GLIDE_MAX / sp : 1;
        glideVx = -pxPerSecondX * k / scale;
        glideVz = -pxPerSecondY * k / scale;
    }

    /** True while the map glides after a drag. */
    public boolean gliding() {
        return glideVx != 0 || glideVz != 0;
    }

    /** The world position in the middle of the map. */
    public double centreX() {
        return cx;
    }

    public double centreZ() {
        return cz;
    }

    /** Any click stops a glide (the first thing a click does; also used by the selftests). */
    public void pressed() {
        glideVx = glideVz = 0;
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        pressed();
        if (MapLock.mapLocked()) return super.mouseClicked(event, doubleClick);
        if (!editing && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            LayerButton l = layerAt(mx, my);
            if (l != null) {
                l.toggle().run();
                return true;
            }
        }
        if (editing) {
            if (super.mouseClicked(event, doubleClick)) return true;
            formClick(mx, my);
            return true;
        }
        if (mx < PANEL && my >= BAR && my < height - foot()) return panelClick(mx, my);
        if (!inMap(mx, my)) return super.mouseClicked(event, doubleClick);
        Hover h = findHover((int) mx, (int) my);
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (h != null && h.pin != null) {
                MapClient.togglePin(h.pin);
                return true;
            }
            dragging = true;
            dragVx = dragVz = 0;
            lastDragNanos = System.nanoTime();
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (h != null && h.marker != null) {
                editMarker(h.marker);
            } else if (h == null) {
                MapDraw.View v = view();
                newMarker((int) Math.floor(v.wx(mx)), (int) Math.floor(v.wz(my)));
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        // let go while still moving: it glides on
        if (dragging && System.nanoTime() - lastDragNanos < GLIDE_WINDOW_NANOS) fling(-dragVx * scale, -dragVz * scale);
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging && !editing) {
            long now = System.nanoTime();
            double dt = (now - lastDragNanos) / 1e9;
            if (dt > 0) {
                // the speed, smoothed over the last few moves (world blocks a second)
                double k = Math.min(1, dt / 0.05);
                dragVx += (-dx / scale / dt - dragVx) * k;
                dragVz += (-dy / scale / dt - dragVz) * k;
            }
            lastDragNanos = now;
            cx -= dx / scale;
            cz -= dy / scale;
            // a zoom still under way stays round the same spot of the screen
            anchorWx -= dx / scale;
            anchorWz -= dy / scale;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (editing || MapLock.mapLocked() || !inMap(mx, my) || sy == 0) return super.mouseScrolled(mx, my, sx, sy);
        // the zoom animates towards the new target round the spot under the mouse (see animate)
        MapDraw.View v = view();
        anchored = true;
        anchorX = mx;
        anchorY = my;
        anchorWx = v.wx(mx);
        anchorWz = v.wz(my);
        target = Math.clamp(target * Math.pow(sy > 0 ? ZOOM_IN : ZOOM_OUT, Math.abs(sy)), MIN_SCALE, MAX_SCALE);
        lastScale = target;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (editing) {
            if (event.key() == InputConstants.KEY_ESCAPE) {
                closeForm();
                return true;
            }
            if (event.key() == InputConstants.KEY_RETURN && nameBox != null && nameBox.isFocused()) {
                save();
                return true;
            }
            return super.keyPressed(event);
        }
        if (MapClient.OPEN.matches(event)) {
            onClose();
            return true;
        }
        if (event.key() == InputConstants.KEY_C) {
            Minecraft mc = Minecraft.getInstance();
            pressed();
            if (mc.player != null) {
                cx = mc.player.getX();
                cz = mc.player.getZ();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    // ------------------------------------------------------------------------------------------------ the marker form

    private static final int FORM_W = 220, FORM_H = 132;

    private int formLeft() {
        return (width - FORM_W) / 2;
    }

    private int formTop() {
        return (height - FORM_H) / 2;
    }

    /** Opens the form for a new marker at a spot (also used by the smoke run). */
    public void newMarker(int x, int z) {
        editingMarker = null;
        editX = x;
        editZ = z;
        Integer h = PaintMap.height(x, z);
        editY = h == null ? DEFAULT_MARKER_Y : h + 1;
        editIcon = 0;
        editColour = 0;
        editShared = false;
        openForm("");
    }

    private void editMarker(MapMarker m) {
        Minecraft mc = Minecraft.getInstance();
        String me = mc.player == null ? "" : mc.player.getUUID().toString();
        if (!m.owner.equals(me) && !m.shared) return;
        editingMarker = m;
        editX = m.x;
        editY = m.y;
        editZ = m.z;
        editIcon = m.icon;
        editColour = m.colour;
        editShared = m.shared;
        openForm(m.name);
    }

    private void openForm(String name) {
        editing = true;
        dragging = false;
        rebuildWidgets();
        if (nameBox != null) nameBox.setValue(name);
    }

    private void buildForm() {
        int l = formLeft(), t = formTop();
        nameBox = new EditBox(font, l + 8, t + 20, FORM_W - 16, 16, Component.translatable("mapmod.map.name"));
        nameBox.setMaxLength(MapMarker.MAX_NAME);
        nameBox.setHint(Component.translatable("mapmod.map.name"));
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);
        boolean death = editingMarker != null && editingMarker.death;
        privacyButton = Button.builder(privacyText(), b -> {
            editShared = !editShared;
            b.setMessage(privacyText());
        }).bounds(l + 8, t + 84, FORM_W - 16, 16).build();
        privacyButton.active = !death;
        addRenderableWidget(privacyButton);
        int bw = (FORM_W - 16 - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("mapmod.map.save"), b -> save()).bounds(l + 8, t + 106, bw, 18).build());
        Button delete = Button.builder(Component.translatable("mapmod.map.delete"), b -> delete()).bounds(l + 12 + bw, t + 106, bw, 18).build();
        delete.active = editingMarker != null;
        addRenderableWidget(delete);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> closeForm()).bounds(l + 16 + 2 * bw, t + 106, bw, 18).build());
    }

    private Component privacyText() {
        return Component.translatable(editShared ? MapClient.sharedKey : "mapmod.map.private");
    }

    private void formBack(GuiGraphicsExtractor g) {
        g.fill(0, 0, width, height, 0x80000000);
        int l = formLeft(), t = formTop();
        g.fill(l, t, l + FORM_W, t + FORM_H, 0xF0181E25);
        g.outline(l, t, FORM_W, FORM_H, 0xFFFFA530);
        g.text(font, Component.translatable(editingMarker == null ? "mapmod.map.new_marker" : "mapmod.map.edit_marker"), l + 8, t + 6, 0xFFFFA530, false);
    }

    private int iconX(int i) {
        return formLeft() + 8 + i * 20;
    }

    private void formFront(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int t = formTop();
        boolean death = editingMarker != null && editingMarker.death;
        for (int i = 0; i < MapMarker.ICONS; i++) {
            int x = iconX(i), y = t + 42;
            if (i == editIcon && !death) g.outline(x - 1, y - 1, 18, 18, 0xFFFFA530);
            g.fill(x, y, x + 16, y + 16, 0xFF242C35);
            MapIcons.centred(g, i, x + 8, y + 8, 0xFF000000 | MapMarker.COLOURS[editColour]);
        }
        for (int i = 0; i < MapMarker.COLOURS.length; i++) {
            int x = iconX(i), y = t + 63;
            if (i == editColour) g.outline(x - 1, y - 1, 18, 18, 0xFFFFFFFF);
            g.fill(x + 2, y + 2, x + 14, y + 14, 0xFF000000 | MapMarker.COLOURS[i]);
        }
    }

    private void formClick(double mx, double my) {
        int t = formTop();
        for (int i = 0; i < MapMarker.ICONS; i++) {
            if (mx >= iconX(i) && mx < iconX(i) + 16 && my >= t + 42 && my < t + 58) editIcon = i;
        }
        for (int i = 0; i < MapMarker.COLOURS.length; i++) {
            if (mx >= iconX(i) && mx < iconX(i) + 16 && my >= t + 63 && my < t + 79) editColour = i;
        }
    }

    private void save() {
        MapMarker m = new MapMarker();
        m.id = editingMarker == null ? "" : editingMarker.id;
        m.x = editX;
        m.y = editY;
        m.z = editZ;
        m.name = nameBox == null ? "" : nameBox.getValue();
        m.icon = editIcon;
        m.colour = editColour;
        m.shared = editShared;
        int op = editingMarker == null ? MapPayloads.ADD : MapPayloads.UPDATE;
        if (LocalMap.active()) LocalMap.apply(op, m);
        else if (ClientPlayNetworking.canSend(MapPayloads.MarkerEdit.TYPE)) ClientPlayNetworking.send(new MapPayloads.MarkerEdit(op, m));
        closeForm();
    }

    private void delete() {
        if (editingMarker != null && LocalMap.active()) LocalMap.apply(MapPayloads.DELETE, editingMarker);
        else if (editingMarker != null && ClientPlayNetworking.canSend(MapPayloads.MarkerEdit.TYPE)) {
            ClientPlayNetworking.send(new MapPayloads.MarkerEdit(MapPayloads.DELETE, editingMarker));
        }
        closeForm();
    }

    private void closeForm() {
        editing = false;
        editingMarker = null;
        nameBox = null;
        privacyButton = null;
        rebuildWidgets();
    }
}
