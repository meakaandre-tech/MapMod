package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The fullscreen map (M), laid out like Satisfactory's: the filter panel down the left, the map filling the rest.
 * Drag to move, scroll to zoom, C to centre on the player. Left click pins or unpins what is under the mouse (pins
 * show on the compass with their distance); right click on an empty spot places a marker there, and on one of your
 * markers (or a shared one of your group) changes or removes it. A marker gets an icon, a colour and a name, and is
 * private or shared with the group.
 */
public class MapScreen extends Screen {
    private static final int PANEL = 140, BAR = 16, FOOT = 14, ROW = 12;
    /** Zoomed out to about a thousand regions on screen at most (each drawn from its small texture when zoomed out). */
    private static final double MIN_SCALE = 0.08, MAX_SCALE = 8;
    /** Zoom factor of one scroll step in and out. */
    private static final double ZOOM_IN = 1.25, ZOOM_OUT = 0.8;
    /** Length of the scale bar (pixels). */
    private static final int SCALE_BAR = 80;
    /** How near the mouse (pixels) a thing must be to be hovered. */
    private static final int HOVER_RADIUS = 7;
    /** Height a new marker gets where the map has no ground recorded. */
    private static final int DEFAULT_MARKER_Y = 64;
    /** Categories whose kinds are folded out in the panel. */
    private static final Set<String> OPEN = new HashSet<>();
    private static double lastScale = 1;

    private double cx, cz, scale = lastScale;
    private boolean placed;
    private boolean dragging;
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
        if (zoom > 0) scale = Math.clamp(zoom, MIN_SCALE, MAX_SCALE);
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
        if (editing) buildForm();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (!MapClient.mappedHere() || !MapClient.unlocked(MapFeature.FULLSCREEN)) onClose();
    }

    private MapDraw.View view() {
        return new MapDraw.View(cx, cz, scale, PANEL, BAR, width - PANEL, height - BAR - FOOT);
    }

    /** The regions on screen, for the tile requests. */
    public List<Long> visibleRegions() {
        return view().regions();
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF151A20);
        MapDraw.View v = view();
        g.enableScissor(v.left(), v.top(), v.left() + v.width(), v.top() + v.height());
        MapDraw.tiles(g, v);
        MapHud.things(g, v, true);
        MobRadar.draw(g, v, true);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) MapDraw.self(g, (float) v.sx(mc.player.getX()), (float) v.sy(mc.player.getZ()));
        g.disableScissor();
        panel(g, mouseX, mouseY);
        // the top bar: title, the scale, and the world position under the mouse
        g.fill(0, 0, width, BAR, 0xF0101418);
        g.text(font, title, 6, 4, 0xFFFFA530, false);
        String scaleText = MapDraw.distance(SCALE_BAR / scale);
        g.fill(width - 96, 10, width - 16, 11, 0xFFE6E6E6);
        g.text(font, scaleText, width - 96, 1, 0xFFE6E6E6, false);
        if (inMap(mouseX, mouseY)) {
            String at = String.format(Locale.ROOT, "X %d   Z %d", (int) Math.floor(v.wx(mouseX)), (int) Math.floor(v.wz(mouseY)));
            g.text(font, at, PANEL + 6, 4, 0xFFB0B8C0, false);
        }
        g.fill(0, height - FOOT, width, height, 0xF0101418);
        g.text(font, Component.translatable("mapmod.map.help"), 6, height - FOOT + 3, 0xFF8A949E, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        hover = editing ? null : findHover(mouseX, mouseY);
        if (editing) formBack(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (editing) {
            formFront(g, mouseX, mouseY);
        } else if (hover != null) {
            g.setComponentTooltipForNextFrame(font, hover.lines, mouseX, mouseY);
        }
    }

    private boolean inMap(double x, double y) {
        return x >= PANEL && x < width && y >= BAR && y < height - FOOT;
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
        g.fill(0, BAR, PANEL, height - FOOT, 0xF0181E25);
        g.fill(PANEL - 1, BAR, PANEL, height - FOOT, 0xFF2C3540);
        g.text(font, Component.translatable("mapmod.map.filters"), 8, BAR + 5, 0xFFFFA530, false);
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
        for (MapMarker m : MapClient.markers) {
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
        for (MapPoi poi : MapClient.poisIn(v.regions())) {
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
        for (MovingPoi m : MapClient.moving) {
            double d = sq(v.sx(m.x()) - mx, v.sy(m.z()) - my);
            if (d >= best) continue;
            best = d;
            List<Component> lines = new ArrayList<>();
            lines.add(MapDraw.movingName(m));
            if (!m.note().isEmpty()) lines.add(Component.translatable(m.note()).withColor(0x8A949E));
            where(lines, (int) Math.floor(m.x()), (int) Math.floor(m.z()));
            found = new Hover(lines, null, null);
        }
        if (MapFilters.showsPlayers()) {
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

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (editing) {
            if (super.mouseClicked(event, doubleClick)) return true;
            formClick(mx, my);
            return true;
        }
        if (mx < PANEL && my >= BAR && my < height - FOOT) return panelClick(mx, my);
        if (!inMap(mx, my)) return super.mouseClicked(event, doubleClick);
        Hover h = findHover((int) mx, (int) my);
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (h != null && h.pin != null) {
                MapClient.togglePin(h.pin);
                return true;
            }
            dragging = true;
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
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging && !editing) {
            cx -= dx / scale;
            cz -= dy / scale;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (editing || !inMap(mx, my) || sy == 0) return super.mouseScrolled(mx, my, sx, sy);
        MapDraw.View v = view();
        double wx = v.wx(mx), wz = v.wz(my);
        scale = Math.clamp(scale * (sy > 0 ? ZOOM_IN : ZOOM_OUT), MIN_SCALE, MAX_SCALE);
        lastScale = scale;
        // keep the spot under the mouse where it is
        MapDraw.View after = view();
        cx += wx - after.wx(mx);
        cz += wz - after.wz(my);
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
        Integer h = ClientTiles.height(x, z);
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
