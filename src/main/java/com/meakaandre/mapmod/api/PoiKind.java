package com.meakaandre.mapmod.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.LinkedHashMap;

/**
 * A kind of POI: how the map draws it, where its switch sits in the filter panel and how the compass treats it. Made
 * with {@link MapModApi#kind} and registered on both sides (common code), before a world loads.
 * <p>
 * Drawing: a kind with a purity ring is a dark disc with a ring coloured by purity (impure red, normal amber, pure
 * green) and, inside it, the sub-type's or the kind's item if it has one, else its glyph; any other kind is its glyph,
 * tinted.
 */
public final class PoiKind {
    /** Purity ring colours: impure, normal, pure. */
    public static final int[] PURITY = {0xFFD9534F, 0xFFE8B43C, 0xFF5CB85C};

    /** Makes a POI's label from its sub-type and purity. */
    @FunctionalInterface
    public interface Labeller {
        Component label(PoiKind kind, String sub, int purity);
    }

    private final Identifier id;
    private final String key;
    private final @Nullable PoiCategory category;
    private final Component label;
    private final int glyph;
    private final int colour;
    private final int dimColour;
    private final @Nullable Identifier item;
    private final boolean purity;
    private final boolean filter;
    private final boolean subFilters;
    private final Map<String, PoiSubType> subTypes;
    private final int compassRange;
    private final boolean compassNearest;
    private final boolean detail;
    private final @Nullable Labeller labeller;

    private PoiKind(Builder b) {
        id = b.id;
        key = b.id.toString();
        category = b.category;
        label = b.label == null ? Component.literal(key) : b.label;
        glyph = b.glyph;
        colour = b.colour;
        dimColour = b.dimColour != 0 ? b.dimColour : dim(b.colour);
        item = b.item;
        purity = b.purity;
        subFilters = b.subFilters && !b.subTypes.isEmpty();
        filter = b.filter == null ? !subFilters : b.filter;
        subTypes = Collections.unmodifiableMap(new LinkedHashMap<>(b.subTypes));
        compassRange = b.compassRange;
        compassNearest = b.compassNearest;
        detail = b.detail;
        labeller = b.labeller;
    }

    private static int dim(int argb) {
        int r = argb >> 16 & 255, g = argb >> 8 & 255, bl = argb & 255;
        r = (r * 13 + 160 * 7) / 20;
        g = (g * 13 + 160 * 7) / 20;
        bl = (bl * 13 + 160 * 7) / 20;
        return 0xC0000000 | r << 16 | g << 8 | bl;
    }

    public Identifier id() {
        return id;
    }

    /** The id as POIs carry it. */
    public String key() {
        return key;
    }

    /** The filter category, or null for a kind with no switch (moving things the map always shows). */
    public @Nullable PoiCategory category() {
        return category;
    }

    public Component label() {
        return label;
    }

    public int glyph() {
        return glyph;
    }

    /** ARGB tint of the glyph. */
    public int colour() {
        return colour;
    }

    /** ARGB tint of a dimmed moving POI. */
    public int dimColour() {
        return dimColour;
    }

    public @Nullable Identifier item() {
        return item;
    }

    public boolean purity() {
        return purity;
    }

    /** True if the kind has its own switch under its category. */
    public boolean filter() {
        return filter;
    }

    /** True if each sub-type has its own switch under the category. */
    public boolean subFilters() {
        return subFilters;
    }

    public Map<String, PoiSubType> subTypes() {
        return subTypes;
    }

    public @Nullable PoiSubType subType(String sub) {
        return subTypes.get(sub);
    }

    /** Blocks within which the compass shows it (explored ones only); 0 for never. */
    public int compassRange() {
        return compassRange;
    }

    /** True if the compass always shows the nearest one, however far. */
    public boolean compassNearest() {
        return compassNearest;
    }

    /** True if it is a small detail the minimap leaves off when zoomed far out. */
    public boolean detail() {
        return detail;
    }

    /** The filter key of the kind's own switch. */
    public String filterKey() {
        return "kind:" + key;
    }

    /** The filter key of a sub-type's switch. */
    public String filterKey(String sub) {
        return "sub:" + key + "/" + sub;
    }

    public Component label(String sub, int purity) {
        if (labeller != null) return labeller.label(this, sub, purity);
        PoiSubType s = subTypes.get(sub);
        Component base = s == null ? label : s.label();
        if (!this.purity) return base;
        return Component.translatable("mapmod.map.with_purity", base, Component.translatable("mapmod.map.purity." + Math.clamp(purity, 0, 2)));
    }

    /** The glyph for a POI of this kind (its sub-type's, if it has one). */
    public int glyph(String sub) {
        PoiSubType s = subTypes.get(sub);
        return s != null && s.glyph() >= 0 ? s.glyph() : glyph;
    }

    /** The glyph's tint for a POI of this kind: the sub-type's, the kind's. */
    public int colour(String sub) {
        PoiSubType s = subTypes.get(sub);
        return s != null && s.colour() != 0 ? s.colour() : colour;
    }

    /** The colour a POI stands for on pins and the compass: its purity ring's, or its glyph's. */
    public int markColour(String sub, int purity) {
        return this.purity ? PURITY[Math.clamp(purity, 0, 2)] : colour(sub);
    }

    public @Nullable Identifier item(String sub) {
        PoiSubType s = subTypes.get(sub);
        return s != null && s.item() != null ? s.item() : item;
    }

    @Override
    public String toString() {
        return key;
    }

    /** Builds a kind; {@link #register} puts it in the map's registry. */
    public static final class Builder {
        private final Identifier id;
        private final @Nullable PoiCategory category;
        private @Nullable Component label;
        private int glyph = MapGlyphs.DOT;
        private int colour = 0xFFF0F0F0;
        private int dimColour;
        private @Nullable Identifier item;
        private boolean purity;
        private @Nullable Boolean filter;
        private boolean subFilters;
        private final Map<String, PoiSubType> subTypes = new LinkedHashMap<>();
        private int compassRange;
        private boolean compassNearest;
        private boolean detail;
        private @Nullable Labeller labeller;
        private final Function<PoiKind, PoiKind> register;

        Builder(Identifier id, @Nullable PoiCategory category, Function<PoiKind, PoiKind> register) {
            this.id = id;
            this.category = category;
            this.register = register;
        }

        public Builder label(Component label) {
            this.label = label;
            return this;
        }

        /** Makes each POI's label (sub-type and purity in it); the default is the sub-type's or the kind's label. */
        public Builder labeller(Labeller labeller) {
            this.labeller = labeller;
            return this;
        }

        /** A {@link MapGlyphs} glyph, tinted ARGB. */
        public Builder glyph(int glyph, int argb) {
            this.glyph = glyph;
            this.colour = argb;
            return this;
        }

        /** The tint of a dimmed moving POI (default: the colour faded toward grey). */
        public Builder dimColour(int argb) {
            this.dimColour = argb;
            return this;
        }

        /** An item drawn in the purity ring (sub-types may have their own). */
        public Builder item(Identifier item) {
            this.item = item;
            return this;
        }

        /** Drawn in a purity ring; POIs carry their purity (0 impure, 1 normal, 2 pure). */
        public Builder purity() {
            this.purity = true;
            return this;
        }

        /** Whether the kind has its own switch (default: yes, unless its sub-types have theirs). */
        public Builder filter(boolean filter) {
            this.filter = filter;
            return this;
        }

        /** Gives every sub-type its own switch under the category. */
        public Builder subFilters() {
            this.subFilters = true;
            return this;
        }

        public Builder sub(PoiSubType sub) {
            subTypes.put(sub.id(), sub);
            return this;
        }

        public Builder subs(List<PoiSubType> subs) {
            for (PoiSubType s : new ArrayList<>(subs)) sub(s);
            return this;
        }

        /** Shown on the compass within this many blocks (only where explored). */
        public Builder compassRange(int blocks) {
            this.compassRange = blocks;
            return this;
        }

        /** The nearest one is always on the compass. */
        public Builder compassNearest() {
            this.compassNearest = true;
            return this;
        }

        /** A small detail: left off the minimap when it is zoomed far out. */
        public Builder detail() {
            this.detail = true;
            return this;
        }

        public PoiKind register() {
            return register.apply(new PoiKind(this));
        }
    }
}
