package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.map.MapGrid;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The fullscreen map as a 3D model: the recorded terrain as a mesh, seen from above at an angle, with the painted map
 * draped over it and lit by a low sun from the north-west. It is drawn on the CPU on a background thread into a texture
 * (so it needs nothing from the game's renderer and runs alike everywhere), and drawn again whenever the camera moves or
 * the map underneath changes. Each pixel also remembers the world position it shows, so clicks land where they point.
 */
public final class Map3D {
    /** The height of ground nothing is known of (and of the fog). */
    private static final float SEA = 62f;
    /** Pixels of the texture across at most (it is drawn smoothly scaled to the view). */
    private static final int MAX_WIDTH = 1280;
    /** Least time between two drawings for a change in the map, not the camera (ms). */
    private static final long DATA_REDRAW_MS = 250;

    /** A camera for a view: {@code scale} screen pixels a block at the target. */
    public static MapModel.Cam cam(double cx, double cz, double yaw, double pitch, double scale, int left, int top, int width, int height) {
        float h = ClientTiles.surface(cx, cz);
        double focal = height / (2 * Math.tan(Math.toRadians(MapModel.FOV / 2)));
        return new MapModel.Cam(cx, Float.isNaN(h) ? SEA : Math.max(h, SEA), cz, yaw, pitch, focal / scale, focal, left, top, width, height);
    }

    // ------------------------------------------------------------------------------------------------ drawing state

    private static @Nullable DynamicTexture texture;
    private static @Nullable Identifier textureId;
    private static @Nullable Frame shown;
    private static MapModel.@Nullable Cam drawing;
    private static boolean busy;
    private static long lastDraw;
    /** True if the last drawing was made before every region under it was painted. */
    private static boolean incomplete;

    private Map3D() {
    }

    /** Draws the model for a view; starts a new drawing when the camera moved or the map changed. */
    public static void draw(GuiGraphicsExtractor g, MapDraw.View v) {
        MapModel.Cam cam = v.cam();
        if (cam == null) return;
        g.fill(v.left(), v.top(), v.left() + v.width(), v.top() + v.height(), 0xFF000000 | MapModel.BACK);
        double reach = cam.reach();
        // the regions under the model are painted as they are needed
        boolean detailed = MapModel.resFor(reach) <= 1;
        for (long region : regions(cam)) ClientTiles.texture(region, detailed);
        long now = System.currentTimeMillis();
        if (!busy) {
            boolean moved = (shown == null || !cam.same(shown.cam)) && (drawing == null || !cam.same(drawing));
            boolean stale = (dataChanged || incomplete) && now - lastDraw > DATA_REDRAW_MS;
            boolean sharpen = !moved && shown != null && !shown.full() && now - lastMove > SETTLE_MS;
            if (moved) lastMove = now;
            if (moved || stale || sharpen) start(cam, stale, !moved || now - lastMove > SETTLE_MS);
        }
        Frame f = shown;
        if (f == null || texture == null || textureId == null) return;
        g.blit(texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            v.left(), v.top(), v.left() + v.width(), v.top() + v.height(), 0f, (float) f.frame().width() / texWidth, 0f, (float) f.frame().height() / texHeight);
    }

    /** A drawing, and whether it was made at full size (while the camera moves, it is drawn at half size, faster). */
    private record Frame(MapModel.Frame frame, MapModel.Cam cam, boolean full) {
    }

    /** Time the camera must stand still before the model is drawn at full size (ms). */
    private static final long SETTLE_MS = 150;
    private static long lastMove;
    private static MapModel.@Nullable Field field;

    private static boolean dataChanged;
    private static int texWidth, texHeight;

    /** The map underneath changed (a tile arrived or was painted again): the model is drawn again soon. */
    public static void changed() {
        dataChanged = true;
    }

    /** The regions the model covers. */
    public static List<Long> regions(MapModel.Cam cam) {
        double r = cam.reach() * 1.3;
        List<Long> out = new ArrayList<>();
        int x0 = Math.floorDiv((int) Math.floor(cam.cx() - r), MapGrid.REGION_BLOCKS), x1 = Math.floorDiv((int) Math.ceil(cam.cx() + r), MapGrid.REGION_BLOCKS);
        int z0 = Math.floorDiv((int) Math.floor(cam.cz() - r), MapGrid.REGION_BLOCKS), z1 = Math.floorDiv((int) Math.ceil(cam.cz() + r), MapGrid.REGION_BLOCKS);
        for (int rz = z0; rz <= z1; rz++) for (int rx = x0; rx <= x1; rx++) out.add(MapGrid.key(rx, rz));
        return out;
    }

    /** The world position under a screen point: off the last drawing, or the target's level where it shows nothing. */
    public static double[] world(MapModel.Cam cam, double sx, double sy) {
        Frame shownFrame = shown;
        if (shownFrame != null && shownFrame.cam.same(cam)) {
            MapModel.Frame f = shownFrame.frame();
            int px = (int) ((sx - cam.left()) * f.width() / cam.width()), py = (int) ((sy - cam.top()) * f.height() / cam.height());
            if (px >= 0 && py >= 0 && px < f.width() && py < f.height()) {
                int i = py * f.width() + px;
                if (!Float.isNaN(f.wx()[i])) return new double[]{f.wx()[i], f.wz()[i]};
            }
        }
        return cam.ground(sx, sy);
    }

    /** True once a drawing for exactly this camera is shown (for the selftests' screenshots). */
    public static boolean ready(MapModel.Cam cam) {
        return shown != null && shown.cam.same(cam) && !busy && !incomplete;
    }

    // ------------------------------------------------------------------------------------------------ the field

    /** Reads the map round the camera: heights and painted colours, wider than the view so it can turn and pan over it. */
    private static MapModel.Field read(MapModel.Cam cam) {
        double reach = cam.reach(), res = MapModel.resFor(reach), half = reach * 1.3;
        double x0 = Math.floor((cam.cx() - half) / res) * res, z0 = Math.floor((cam.cz() - half) / res) * res;
        int side = Math.min(MapModel.FIELD_SIDE, (int) Math.ceil(2 * half / res) + 2);
        float[] h = new float[side * side];
        int[] c = new int[side * side];
        boolean complete = true;
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                double x = x0 + i * res, z = z0 + j * res;
                float y = ClientTiles.surface(x, z);
                int col = ClientTiles.painted(x, z);
                if (col == 0) {
                    col = MapPainter.FOG;
                    if (complete && MapClient.FOG.containsKey(MapGrid.regionOfBlock((int) Math.floor(x), (int) Math.floor(z)))) complete = false;
                }
                h[j * side + i] = Float.isNaN(y) ? SEA : y;
                c[j * side + i] = col & 0xFFFFFF;
            }
        }
        incomplete = !complete;
        return new MapModel.Field(x0, z0, res, side, h, c);
    }

    private static void start(MapModel.Cam cam, boolean reread, boolean full) {
        if (field == null || reread || !field.covers(cam)) {
            field = read(cam);
            dataChanged = false;
            lastDraw = System.currentTimeMillis();
        }
        MapModel.Field f = field;
        busy = true;
        drawing = cam;
        int scaleUp = Math.max(1, (int) Minecraft.getInstance().getWindow().getGuiScale());
        int w = Math.min(MAX_WIDTH, cam.width() * scaleUp) / (full ? 1 : 2);
        int h = Math.max(1, (int) Math.round((double) w * cam.height() / cam.width()));
        Util.backgroundExecutor().execute(() -> {
            MapModel.Frame frame;
            try {
                frame = MapModel.raster(cam, f, w, h);
            } catch (RuntimeException e) {
                MapMod.LOGGER.warn("could not draw the 3D map", e);
                frame = null;
            }
            MapModel.Frame done = frame;
            Minecraft.getInstance().execute(() -> {
                busy = false;
                if (done != null) upload(new Frame(done, cam, full));
            });
        });
    }

    private static void upload(Frame shownFrame) {
        MapModel.Frame f = shownFrame.frame();
        if (texture == null || texWidth < f.width() || texHeight < f.height()) {
            if (textureId != null) Minecraft.getInstance().getTextureManager().release(textureId);
            texWidth = Math.max(f.width(), texWidth);
            texHeight = Math.max(f.height(), texHeight);
            textureId = MapMod.id("map/model");
            texture = new DynamicTexture(() -> MapMod.ID + " 3D map", texWidth, texHeight, false);
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
        }
        NativeImage image = texture.getPixels();
        if (image == null) return;
        for (int y = 0; y < f.height(); y++) {
            for (int x = 0; x < f.width(); x++) image.setPixel(x, y, 0xFF000000 | f.rgb()[y * f.width() + x]);
        }
        texture.upload();
        shown = shownFrame;
    }

    /** Lets go of the texture (the map closed). */
    public static void release() {
        if (textureId != null) Minecraft.getInstance().getTextureManager().release(textureId);
        texture = null;
        textureId = null;
        shown = null;
        drawing = null;
        field = null;
    }

}
