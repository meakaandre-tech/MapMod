package com.meakaandre.mapmod.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A sub-type of a {@link PoiKind} (an ore of a resource node, say), with its own label and, if the kind says so, its
 * own filter switch.
 *
 * @param items  items to draw in the kind's purity ring instead of the glyph: the first one the game has is drawn (so
 *               an item of a mod that may be missing can come first, with a vanilla one after it); empty for none
 * @param glyph  a {@link MapGlyphs} glyph instead of the kind's, or -1
 * @param colour the glyph's tint (ARGB) instead of the kind's, or 0
 */
public record PoiSubType(String id, Component label, List<Identifier> items, int glyph, int colour) {
    public PoiSubType {
        items = List.copyOf(items);
    }

    /** A sub-type with one item (or none, for null). */
    public PoiSubType(String id, Component label, @Nullable Identifier item, int glyph, int colour) {
        this(id, label, item == null ? List.of() : List.of(item), glyph, colour);
    }

    public static PoiSubType of(String id, Component label) {
        return new PoiSubType(id, label, List.<Identifier>of(), -1, 0);
    }
}
