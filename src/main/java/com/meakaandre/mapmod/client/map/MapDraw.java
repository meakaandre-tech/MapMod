package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.api.MapGlyphs;
import com.meakaandre.mapmod.api.MapModApi;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
import com.meakaandre.mapmod.api.PoiKind;
import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapMarker;
import com.meakaandre.mapmod.map.MapPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drawing shared by the minimap and the fullscreen map: a view (a centre in the world, a scale and a rectangle on
 * screen), the terrain tiles under it, and the icons of everything on it.
 */
public final class MapDraw {
    private static final Map<Identifier, ItemStack> ITEMS = new HashMap<>();
    public static final int PLAYER = 0xFFFFFFFF, MATE = 0xFF5FD4FF, FOG_BACK = 0xFF2A3440;
    /** Size an item is drawn at in a purity ring (pixels; items are 16). */
    private static final int ITEM_PX = 9;
    /** Blocks in a kilometre: distances from this on are given in km. */
    private static final int BLOCKS_PER_KM = 1000;

    private MapDraw() {
    }

    /** A view of the map: world (cx, cz) at the centre of the screen rectangle, {@code scale} screen pixels a block. */
    public record View(double cx, double cz, double scale, int left, int top, int width, int height) {
        public double sx(double x) {
            return left + width / 2.0 + (x - cx) * scale;
        }

        public double sy(double z) {
            return top + height / 2.0 + (z - cz) * scale;
        }

        public double wx(double sx) {
            return cx + (sx - left - width / 2.0) / scale;
        }

        public double wz(double sy) {
            return cz + (sy - top - height / 2.0) / scale;
        }

        public boolean inside(double sx, double sy, int margin) {
            return sx >= left - margin && sx < left + width + margin && sy >= top - margin && sy < top + height + margin;
        }

        /** The regions the view shows. */
        public List<Long> regions() {
            List<Long> out = new ArrayList<>();
            int x0 = Math.floorDiv((int) Math.floor(wx(left)), MapGrid.REGION_BLOCKS), x1 = Math.floorDiv((int) Math.ceil(wx(left + width)), MapGrid.REGION_BLOCKS);
            int z0 = Math.floorDiv((int) Math.floor(wz(top)), MapGrid.REGION_BLOCKS), z1 = Math.floorDiv((int) Math.ceil(wz(top + height)), MapGrid.REGION_BLOCKS);
            for (int rz = z0; rz <= z1; rz++) for (int rx = x0; rx <= x1; rx++) out.add(MapGrid.key(rx, rz));
            return out;
        }
    }

    /** The fog-coloured ground and every known tile in the view, clipped to it. */
    public static void tiles(GuiGraphicsExtractor g, View v) {
        g.fill(v.left, v.top, v.left + v.width, v.top + v.height, FOG_BACK);
        float size = (float) (MapGrid.REGION_BLOCKS * v.scale) / MapGrid.REGION_PX;
        for (long region : v.regions()) {
            Identifier id = ClientTiles.texture(region);
            if (id == null) continue;
            double x = v.sx((double) MapGrid.keyX(region) * MapGrid.REGION_BLOCKS), y = v.sy((double) MapGrid.keyZ(region) * MapGrid.REGION_BLOCKS);
            g.pose().pushMatrix();
            g.pose().translate((float) x, (float) y);
            g.pose().scale(size, size);
            g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, MapGrid.REGION_PX, MapGrid.REGION_PX, MapGrid.REGION_PX, MapGrid.REGION_PX);
            g.pose().popMatrix();
        }
    }

    // ------------------------------------------------------------------------------------------------ things

    public static int markerColour(MapMarker m) {
        return m.death ? 0xFFE8E8E8 : 0xFF000000 | MapMarker.COLOURS[Math.floorMod(m.colour, MapMarker.COLOURS.length)];
    }

    public static Component markerLabel(MapMarker m) {
        if (m.death) return Component.translatable("mapmod.map.death_marker");
        return m.name.isEmpty() ? Component.translatable("mapmod.map.marker") : Component.literal(m.name);
    }

    public static Component label(MapPoi poi) {
        PoiKind kind = MapModApi.kind(poi.kind());
        return kind == null ? Component.literal(poi.kind()) : kind.label(poi.sub(), poi.purity());
    }

    /** The glyph a POI stands for on pins and the compass. */
    public static int icon(MapPoi poi) {
        PoiKind kind = MapModApi.kind(poi.kind());
        return kind == null ? MapGlyphs.DOT : kind.glyph(poi.sub());
    }

    /** The colour a POI stands for on pins and the compass: its purity ring's, or its glyph's tint. */
    public static int colour(MapPoi poi) {
        PoiKind kind = MapModApi.kind(poi.kind());
        return kind == null ? 0xFFF0F0F0 : kind.markColour(poi.sub(), poi.purity());
    }

    private static ItemStack item(Identifier id) {
        return ITEMS.computeIfAbsent(id, k -> {
            Item item = BuiltInRegistries.ITEM.getValue(k);
            return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        });
    }

    /** The item drawn in a POI's purity ring: the first of its kind's or sub-type's items the game has; empty for none. */
    public static ItemStack item(MapPoi poi) {
        PoiKind kind = MapModApi.kind(poi.kind());
        if (kind == null) return ItemStack.EMPTY;
        for (Identifier id : kind.items(poi.sub())) {
            ItemStack stack = item(id);
            if (!stack.isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }

    /** Draws a POI centred on x, y: in a purity ring (an item or a glyph inside) or as its tinted glyph. */
    public static void poi(GuiGraphicsExtractor g, MapPoi poi, int x, int y) {
        PoiKind kind = MapModApi.kind(poi.kind());
        if (kind == null) return;
        if (!kind.purity()) {
            MapIcons.centred(g, kind.glyph(poi.sub()), x, y, kind.colour(poi.sub()));
            return;
        }
        MapIcons.centred(g, MapGlyphs.DISC, x, y, 0xE0202020);
        MapIcons.centred(g, MapGlyphs.RING, x, y, kind.markColour(poi.sub(), poi.purity()));
        ItemStack stack = item(poi);
        if (!stack.isEmpty()) {
            g.pose().pushMatrix();
            g.pose().translate(x - ITEM_PX / 2f, y - ITEM_PX / 2f);
            g.pose().scale(ITEM_PX / 16f, ITEM_PX / 16f);
            g.fakeItem(stack, 0, 0);
            g.pose().popMatrix();
            return;
        }
        MapIcons.centred(g, kind.glyph(poi.sub()), x, y, kind.colour(poi.sub()));
    }

    public static void marker(GuiGraphicsExtractor g, MapMarker m, int x, int y) {
        MapIcons.centred(g, m.death ? MapGlyphs.SKULL : m.icon, x, y, markerColour(m));
    }

    /** Another player of the group: an arrow turned the way they face. */
    public static void mate(GuiGraphicsExtractor g, MapPayloads.Mate mate, float x, float y) {
        MapIcons.turned(g, MapGlyphs.ARROW, x, y, (float) Math.toRadians(mate.yaw() + 180), MATE);
    }

    /** A moving thing: its glyph, dimmer where it is only where it would be. */
    public static void moving(GuiGraphicsExtractor g, MovingPoi m, float x, float y) {
        PoiKind kind = MapModApi.kind(m.kind());
        if (kind == null) return;
        MapIcons.centred(g, kind.glyph(), Math.round(x), Math.round(y), m.dimmed() ? kind.dimColour() : kind.colour());
    }

    public static Component movingName(MovingPoi m) {
        PoiKind kind = MapModApi.kind(m.kind());
        return kind == null ? Component.literal(m.kind()) : kind.label();
    }

    /** The player themself. */
    public static void self(GuiGraphicsExtractor g, float x, float y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        MapIcons.turned(g, MapGlyphs.ARROW, x, y, (float) Math.toRadians(mc.player.getYRot() + 180), PLAYER);
    }

    public static String distance(double blocks) {
        return blocks >= BLOCKS_PER_KM ? String.format(Locale.ROOT, "%.1f km", blocks / BLOCKS_PER_KM) : Math.round(blocks) + " m";
    }
}
