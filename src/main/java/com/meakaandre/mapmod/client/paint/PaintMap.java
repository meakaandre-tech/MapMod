package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.client.capture.Capture;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.map.MapDraw;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The painted map (MM-2, shader v1): while the fullscreen map is open, the tiles it shows are held ({@link PaintTiles}),
 * uploaded to the GPU a tile or two a frame ({@link TileTextures}) and painted live every frame by one fragment shader
 * per tile ({@link PaintRenderer}) into a texture the size of the map on screen in real pixels, which the screen then
 * draws as one picture with its icons on top. Tiles not uploaded yet show as paper. When the map closes, everything is
 * let go.
 * <p>
 * Baking tiles once and a mip pyramid for far zoom come in MM-4; until then every visible tile is painted each frame.
 */
public final class PaintMap {
    /** Tiles uploaded to the GPU in one frame at most, so opening the map never stalls a frame. */
    public static final int UPLOADS_PER_FRAME = 2;
    /** Tiles held beyond the visible ones on each side before they are let go. */
    private static final int KEEP_MARGIN = 2;

    private static @Nullable PaintTiles tiles;
    private static final Long2ObjectOpenHashMap<TileTextures> GPU = new Long2ObjectOpenHashMap<>();
    private static @Nullable PaintRenderer renderer;
    private static final Stats STATS = new Stats();

    /** Render-thread numbers for {@code /mapmod stats}: per frame, averaged over the last second, and the worst. */
    private static final class Stats {
        long frames, uploads;
        long windowStart, windowFrames, windowMicros, windowUploads, windowMaxMicros, windowMaxUploads;
        double avgMicros, avgUploads;
        long maxMicros, maxUploads, lastMaxMicros, lastMaxUploads;
        int tilesShown, tilesPainted;
    }

    private PaintMap() {
    }

    /** The palette's reload listener: F3+T (and every resource reload) reads map_palette.json again. */
    public static void register() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(MapMod.id("map_palette"),
            (ResourceManagerReloadListener) PaintPalette::load);
        Capture.addStats(PaintMap::statsLines);
    }

    /** Paints the terrain under a view and draws it there (the screen has clipped to the view). */
    public static void draw(GuiGraphicsExtractor g, MapDraw.View v) {
        long t0 = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        int scale = Math.max(1, mc.getWindow().getGuiScale());
        int w = v.width() * scale, h = v.height() * scale;
        if (w <= 0 || h <= 0) return;
        PaintTiles held = tiles;
        if (held == null) tiles = held = new PaintTiles();
        // the tiles under the view, and one more on each side for their neighbours' aprons
        double wx0 = v.wx(v.left()), wx1 = v.wx(v.left() + v.width()), wz0 = v.wz(v.top()), wz1 = v.wz(v.top() + v.height());
        int tx0 = Math.floorDiv((int) Math.floor(wx0), SurfaceTile.BLOCKS), tx1 = Math.floorDiv((int) Math.floor(wx1), SurfaceTile.BLOCKS);
        int tz0 = Math.floorDiv((int) Math.floor(wz0), SurfaceTile.BLOCKS), tz1 = Math.floorDiv((int) Math.floor(wz1), SurfaceTile.BLOCKS);
        held.update(tx0 - 1, tz0 - 1, tx1 + 1, tz1 + 1, KEEP_MARGIN);
        dropFar(held);
        boolean terrain = MapClient.unlocked(MapFeature.TERRAIN);
        int uploads = terrain ? upload(held, tx0, tz0, tx1, tz1, (tx0 + tx1) / 2.0, (tz0 + tz1) / 2.0) : 0;

        PaintRenderer r = renderer;
        if (r == null) renderer = r = new PaintRenderer();
        List<PaintRenderer.Quad> quads = new ArrayList<>();
        int shown = 0;
        double blocksPerPixel = 1.0 / (v.scale() * scale);
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                shown++;
                TileTextures tex = terrain ? GPU.get(PaintTiles.key(tx, tz)) : null;
                if (tex == null) continue;
                // the tile's corners in the target's pixels (x right, y down from the top-left)
                double x0 = (tx * (double) SurfaceTile.BLOCKS - wx0) / blocksPerPixel, x1 = x0 + SurfaceTile.BLOCKS / blocksPerPixel;
                double y0 = (tz * (double) SurfaceTile.BLOCKS - wz0) / blocksPerPixel, y1 = y0 + SurfaceTile.BLOCKS / blocksPerPixel;
                // sample i stands for its cell's middle (block i * 4 + 2), so the tile's edge is half a sample before
                // the first and after the last; texel i + APRON holds sample i
                float u0 = TileTextures.APRON - 0.5f, u1 = u0 + SurfaceTile.SIDE;
                quads.add(new PaintRenderer.Quad(tex, (float) x0, (float) y0, (float) x1, (float) y1, u0, u0, u1, u1));
            }
        }
        r.paint(w, h, PaintPalette.current(), PaintPalette.version(), quads);
        g.blit(r.view(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            v.left(), v.top(), v.left() + v.width(), v.top() + v.height(), 0f, 1f, r.v0(), r.v1());

        long micros = (System.nanoTime() - t0) / 1000;
        Stats s = STATS;
        s.frames++;
        s.uploads += uploads;
        s.tilesShown = shown;
        s.tilesPainted = quads.size();
        s.windowFrames++;
        s.windowMicros += micros;
        s.windowUploads += uploads;
        s.windowMaxMicros = Math.max(s.windowMaxMicros, micros);
        s.windowMaxUploads = Math.max(s.windowMaxUploads, uploads);
        s.maxUploads = Math.max(s.maxUploads, uploads);
        long now = System.currentTimeMillis();
        if (now - s.windowStart >= 1000) {
            s.avgMicros = s.windowFrames == 0 ? 0 : (double) s.windowMicros / s.windowFrames;
            s.avgUploads = s.windowFrames == 0 ? 0 : (double) s.windowUploads / s.windowFrames;
            s.lastMaxMicros = s.windowMaxMicros;
            s.lastMaxUploads = s.windowMaxUploads;
            s.windowStart = now;
            s.windowFrames = s.windowMicros = s.windowUploads = s.windowMaxMicros = s.windowMaxUploads = 0;
        }
    }

    /**
     * Uploads up to {@link #UPLOADS_PER_FRAME} visible tiles whose GPU copy is missing or older than their data (or a
     * neighbour's), nearest the middle of the view first. A tile waits while a neighbour is still being read, so it is
     * not uploaded twice in a row as the map opens.
     */
    private static int upload(PaintTiles held, int tx0, int tz0, int tx1, int tz1, double mx, double mz) {
        record Want(int tx, int tz, long key, double d) {
        }
        List<Want> wants = new ArrayList<>();
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                PaintTiles.Entry e = held.get(tx, tz);
                if (e == null || e.data == null || held.settling(tx, tz)) continue;
                long key = stamps(held, tx, tz);
                TileTextures tex = GPU.get(PaintTiles.key(tx, tz));
                if (tex != null && tex.key == key) continue;
                wants.add(new Want(tx, tz, key, (tx - mx) * (tx - mx) + (tz - mz) * (tz - mz)));
            }
        }
        wants.sort((a, b) -> Double.compare(a.d, b.d));
        int done = 0;
        for (Want want : wants) {
            if (done >= UPLOADS_PER_FRAME) break;
            long k = PaintTiles.key(want.tx, want.tz);
            TileTextures tex = GPU.get(k);
            if (tex == null) GPU.put(k, tex = TileTextures.create(want.tx, want.tz));
            SurfaceTile[][] around = new SurfaceTile[3][3];
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    PaintTiles.Entry n = held.get(want.tx + dx, want.tz + dz);
                    around[dz + 1][dx + 1] = n == null ? null : n.data;
                }
            }
            tex.upload(around);
            tex.key = want.key;
            done++;
        }
        return done;
    }

    /** One number from the stamps of a tile and its eight neighbours: it changes when any of their data does. */
    private static long stamps(PaintTiles held, int tx, int tz) {
        long h = 1469598103934665603L;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                PaintTiles.Entry n = held.get(tx + dx, tz + dz);
                h = (h ^ (n == null ? 0 : n.stamp)) * 1099511628211L;
            }
        }
        return h;
    }

    /** Lets go of the GPU copies of tiles the holder no longer holds. */
    private static void dropFar(PaintTiles held) {
        LongArrayList gone = new LongArrayList();
        for (var e : GPU.long2ObjectEntrySet()) {
            int tx = (int) (e.getLongKey() >> 32), tz = (int) e.getLongKey();
            if (held.get(tx, tz) == null) gone.add(e.getLongKey());
        }
        for (long k : gone) {
            TileTextures t = GPU.remove(k);
            if (t != null) t.close();
        }
    }

    /** The height the map holds at a block (the top of the ground, or the floor under water), or null if unknown. */
    public static @Nullable Integer height(int x, int z) {
        PaintTiles held = tiles;
        if (held == null) return null;
        PaintTiles.Entry e = held.get(SurfaceTile.tileOfBlock(x), SurfaceTile.tileOfBlock(z));
        if (e == null || e.data == null) return null;
        int i = SurfaceTile.indexOfBlock(x, z);
        return e.data.explored(i) ? (int) e.data.height[i] : null;
    }

    /** The map closed: its tiles, their textures and the painted picture are let go. */
    public static void close() {
        PaintTiles held = tiles;
        tiles = null;
        if (held != null) held.close();
        for (TileTextures t : GPU.values()) t.close();
        GPU.clear();
        PaintRenderer r = renderer;
        renderer = null;
        if (r != null) r.close();
    }

    /** True while the map holds tiles (it is open). */
    public static boolean open() {
        return tiles != null;
    }

    /** Frames painted and tiles uploaded since the game started, the most uploaded in one frame, and the tiles painted
     * in the last frame (for the selftests). */
    public static long frames() {
        return STATS.frames;
    }

    public static long uploads() {
        return STATS.uploads;
    }

    public static long maxUploadsPerFrame() {
        return STATS.maxUploads;
    }

    public static int tilesPainted() {
        return STATS.tilesPainted;
    }

    /** Compiles the paint shader now (for the selftests): null if it compiled, else what went wrong. */
    public static @Nullable String compileCheck() {
        return PaintRenderer.compileCheck();
    }

    private static List<String> statsLines() {
        Stats s = STATS;
        PaintTiles held = tiles;
        List<String> lines = new ArrayList<>();
        lines.add(MapMod.NAME + " painted map" + (held == null ? " (closed)" : " (open)"));
        lines.add(String.format(Locale.ROOT, "  render %.0f us/frame avg, %d us max (last second)", s.avgMicros, s.lastMaxMicros));
        lines.add(String.format(Locale.ROOT, "  uploads %.2f/frame avg, %d max (budget %d); %d total in %d frames",
            s.avgUploads, s.lastMaxUploads, UPLOADS_PER_FRAME, s.uploads, s.frames));
        lines.add(String.format(Locale.ROOT, "  tiles shown %d, painted %d, held %d, on the GPU %d (read %d, from capture %d)",
            s.tilesShown, s.tilesPainted, held == null ? 0 : held.size(), GPU.size(), held == null ? 0 : held.read.get(),
            held == null ? 0 : held.fromCapture.get()));
        return lines;
    }
}
