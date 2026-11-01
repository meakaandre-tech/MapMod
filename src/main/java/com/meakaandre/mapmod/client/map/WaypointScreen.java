package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.map.MapMarker;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * "New waypoint" (MM-8c): what the waypoint key opens, in game or on the map. A name ("Waypoint N", all of it selected so
 * typing replaces it), X, Y and Z (the player's block, or where the mouse points on the map), a row of colours (the next
 * in the cycle picked), "Pin to compass" (on), and Save and Cancel. Enter saves, Esc cancels; Save is off while a number
 * is not one. It goes back to the screen it was opened from (the map, or none) either way.
 */
public class WaypointScreen extends Screen {
    private static final int W = 232, H = 158, ROW = 20, SWATCH = 16, SWATCH_STEP = 20;
    /** How far out a coordinate may be (the world border's reach), and the heights it may have. */
    private static final int MAX_XZ = 30_000_000, MIN_Y = -2048, MAX_Y = 4096;

    private final @Nullable Screen parent;
    private final String dimension;
    private final String defaultName;
    private String name, xs, ys, zs;
    private int colour;
    private boolean pin = true;
    private @Nullable EditBox nameBox, xBox, yBox, zBox;
    private @Nullable Button saveButton;
    /** Saved, or cancelled: set once it closed (for the selftests). */
    private boolean saved, cancelled;

    /** A new waypoint at a block of a dimension, going back to {@code parent} (null: the game) when it closes. */
    public WaypointScreen(@Nullable Screen parent, int x, int y, int z, String dimension) {
        super(Component.translatable("mapmod.waypoint.new"));
        this.parent = parent;
        this.dimension = dimension;
        int n = Waypoints.next();
        defaultName = Waypoints.name(n);
        name = defaultName;
        xs = Integer.toString(x);
        ys = Integer.toString(y);
        zs = Integer.toString(z);
        colour = Waypoints.colour(n);
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - H) / 2;
    }

    @Override
    protected void init() {
        int l = left(), t = top();
        nameBox = new EditBox(font, l + 10, t + 22, W - 20, 16, Component.translatable("mapmod.map.name"));
        nameBox.setMaxLength(MapMarker.MAX_NAME);
        nameBox.setValue(name);
        nameBox.moveCursorToEnd(false);
        nameBox.setHighlightPos(0);
        nameBox.setResponder(v -> name = v);
        addRenderableWidget(nameBox);
        int fw = (W - 20 - 2 * 8 - 3 * 12) / 3;
        xBox = number(l + 10 + 12, t + 22 + ROW + 4, fw, xs, v -> xs = v);
        yBox = number(l + 10 + 12 + fw + 8 + 12, t + 22 + ROW + 4, fw, ys, v -> ys = v);
        zBox = number(l + 10 + 2 * (12 + fw + 8) + 12, t + 22 + ROW + 4, fw, zs, v -> zs = v);
        addRenderableWidget(Checkbox.builder(Component.translatable("mapmod.waypoint.pin"), font).pos(l + 10, t + 96).selected(pin)
            .onValueChange((box, on) -> pin = on).build());
        int bw = (W - 20 - 8) / 2;
        saveButton = Button.builder(Component.translatable("mapmod.map.save"), b -> save()).bounds(l + 10, t + H - 28, bw, 18).build();
        addRenderableWidget(saveButton);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> cancel()).bounds(l + 18 + bw, t + H - 28, bw, 18).build());
        setInitialFocus(nameBox);
        validate();
    }

    private EditBox number(int x, int y, int w, String value, java.util.function.Consumer<String> to) {
        EditBox box = new EditBox(font, x, y, w, 16, Component.literal(""));
        box.setMaxLength(9);
        box.setValue(value);
        box.setResponder(v -> {
            to.accept(v);
            validate();
        });
        addRenderableWidget(box);
        return box;
    }

    private void validate() {
        if (saveButton != null) saveButton.active = valid();
    }

    /** True while every number is one and in reach: Save is on. */
    public boolean valid() {
        Integer x = parse(xs), y = parse(ys), z = parse(zs);
        return x != null && y != null && z != null && Math.abs(x) <= MAX_XZ && Math.abs(z) <= MAX_XZ && y >= MIN_Y && y <= MAX_Y;
    }

    private static @Nullable Integer parse(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int l = left(), t = top();
        g.fill(l, t, l + W, t + H, 0xF0181E25);
        g.outline(l, t, W, H, 0xFFFFA530);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int l = left(), t = top();
        g.text(font, title, l + 10, t + 8, 0xFFFFA530, false);
        int fw = (W - 20 - 2 * 8 - 3 * 12) / 3, y = t + 22 + ROW + 8;
        g.text(font, "X", l + 10, y, 0xFFB0B8C0, false);
        g.text(font, "Y", l + 10 + 12 + fw + 8, y, 0xFFB0B8C0, false);
        g.text(font, "Z", l + 10 + 2 * (12 + fw + 8), y, 0xFFB0B8C0, false);
        for (int i = 0; i < Waypoints.COLOURS.length; i++) {
            int x = swatchX(i), sy = swatchY();
            int c = Waypoints.COLOURS[i];
            if (c == colour) g.outline(x - 2, sy - 2, SWATCH + 4, SWATCH + 4, 0xFFFFFFFF);
            g.fill(x, sy, x + SWATCH, sy + SWATCH, 0xFF000000 | MapMarker.COLOURS[c]);
        }
        MapToast.draw(g, width);
    }

    private int swatchX(int i) {
        return left() + 10 + i * SWATCH_STEP;
    }

    private int swatchY() {
        return top() + 72;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int i = 0; i < Waypoints.COLOURS.length; i++) {
            if (event.x() >= swatchX(i) && event.x() < swatchX(i) + SWATCH && event.y() >= swatchY() && event.y() < swatchY() + SWATCH) {
                colour = Waypoints.COLOURS[i];
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            if (valid()) save();
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            cancel();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Places the waypoint as filled in (a blank name is the default one) and goes back. */
    public void save() {
        Integer x = parse(xs), y = parse(ys), z = parse(zs);
        if (!valid() || x == null || y == null || z == null) return;
        String n = name.strip().isEmpty() ? defaultName : name.strip();
        Waypoints.place(n, x, y, z, dimension, colour, pin);
        saved = true;
        back();
    }

    /** Places nothing and goes back. */
    public void cancel() {
        cancelled = true;
        back();
    }

    private void back() {
        if (minecraft != null) minecraft.gui.setScreen(parent);
    }

    // for the selftests: what the fields hold, and filling them as typing would

    public String nameValue() {
        return name;
    }

    /** The part of the name selected (all of it when it opens, so typing replaces it). */
    public String nameSelected() {
        return nameBox == null ? "" : nameBox.getHighlighted();
    }

    public String xValue() {
        return xs;
    }

    public String yValue() {
        return ys;
    }

    public String zValue() {
        return zs;
    }

    public int colourValue() {
        return colour;
    }

    public boolean pinValue() {
        return pin;
    }

    public String dimension() {
        return dimension;
    }

    public @Nullable Screen parent() {
        return parent;
    }

    /** Whether Save is on now. */
    public boolean saveActive() {
        return saveButton != null && saveButton.active;
    }

    public boolean saved() {
        return saved;
    }

    public boolean cancelled() {
        return cancelled;
    }

    /** Fills in the fields (through the boxes, as typing does; null leaves one as it is). */
    public void fill(@Nullable String name, @Nullable String x, @Nullable String y, @Nullable String z) {
        if (name != null && nameBox != null) nameBox.setValue(name);
        if (x != null && xBox != null) xBox.setValue(x);
        if (y != null && yBox != null) yBox.setValue(y);
        if (z != null && zBox != null) zBox.setValue(z);
    }

    /** Picks a colour as clicking its swatch does (of {@link MapMarker#COLOURS}). */
    public void pick(int colourIndex) {
        colour = Math.floorMod(colourIndex, MapMarker.COLOURS.length);
    }

    /** Ticks or unticks "Pin to compass". */
    public void pin(boolean on) {
        pin = on;
        rebuildWidgets();
    }
}
