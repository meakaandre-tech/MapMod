package com.meakaandre.mapmod.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * A sub-type of a {@link PoiKind} (an ore of a resource node, say), with its own label and, if the kind says so, its
 * own filter switch.
 *
 * @param item   an item drawn in the kind's purity ring instead of the glyph, or null
 * @param glyph  a {@link MapGlyphs} glyph instead of the kind's, or -1
 * @param colour the glyph's tint (ARGB) instead of the kind's, or 0
 */
public record PoiSubType(String id, Component label, @Nullable Identifier item, int glyph, int colour) {
    public static PoiSubType of(String id, Component label) {
        return new PoiSubType(id, label, null, -1, 0);
    }
}
