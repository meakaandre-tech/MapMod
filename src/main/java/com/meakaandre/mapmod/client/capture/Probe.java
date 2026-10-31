package com.meakaandre.mapmod.client.capture;

import com.meakaandre.mapmod.client.paint.PaintPalette;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /mapmod probe} (MM-7): what the map makes of the ground at a column. The live part is read now, on the client
 * thread, exactly as the capture reads it (the ground block, its class, the cell's tint and biome); the stored part is
 * the sample the capture holds for that cell (class, tint, height, the tile's revision), and the colour the painted map
 * gives it. A stored sample that differs from the live one means the tile was not captured again after a change.
 */
public final class Probe {
    /**
     * @param liveClass  the ground block's class now; -1 if the chunk is not loaded
     * @param cellClass  the class the capture would store for the cell now (its middle column, forest and snow applied)
     * @param liveTint   the tint the capture would store for the cell now (0xRRGGBB)
     * @param storedClass -1 if the cell was never captured
     */
    public record Result(int x, int y, int z, String block, int liveClass, int cellClass, int liveTint, String biome, int biomeGrass,
                         int storedClass, int storedTint, int storedHeight, long revision, int colour, int liveColour) {
        public boolean captured() {
            return storedClass >= 0;
        }

        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add(String.format(Locale.ROOT, "probe %d %d %d: %s, class %s (%d), biome %s (grass #%06X)", x, y, z, block,
                SurfaceClass.name(liveClass), liveClass, biome, biomeGrass));
            out.add(String.format(Locale.ROOT, "  live cell: class %s (%d), tint #%06X -> colour #%06X", SurfaceClass.name(cellClass), cellClass, liveTint, liveColour));
            if (!captured()) {
                out.add("  stored: not captured");
            } else {
                out.add(String.format(Locale.ROOT, "  stored: class %s (%d), tint #%06X, height %d, tile revision %d", SurfaceClass.name(storedClass),
                    storedClass, storedTint, storedHeight, revision));
                out.add(String.format(Locale.ROOT, "  palette colour #%06X%s", colour,
                    storedClass != cellClass || storedTint != liveTint ? " (stored sample differs from the ground now)" : ""));
            }
            return out;
        }
    }

    private Probe() {
    }

    /**
     * Probes the column at x, z: copies the chunk now (client thread), then reads the column and the stored sample on the
     * capture worker (the classifier's cache is the worker's), and completes there.
     */
    public static CompletableFuture<Result> at(ClientLevel level, int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        ChunkSnapshot snap = level.getChunkSource().hasChunk(cx, cz) ? ChunkSnapshot.of(level, level.getChunk(cx, cz)) : null;
        PaintPalette p = PaintPalette.current();
        int minY = level.getMinY();
        return Capture.onWorker(store -> {
            int y = minY, liveClass = -1, cellClass = -1, liveTint = 0, grass = 0;
            String block = "unloaded", biome = "?";
            if (snap != null) {
                int[] col = SurfaceSampler.ground(snap, x & 15, z & 15);
                y = col[0];
                liveClass = col[1];
                BlockState state = snap.get(x & 15, y, z & 15);
                block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                SurfaceSampler.Samples s = SurfaceSampler.sample(snap);
                int n = ((x & 15) >> 2) + ((z & 15) >> 2) * 4;
                cellClass = s.surface[n];
                liveTint = SurfaceSampler.rgb888(s.tint[n]);
                Holder<Biome> b = snap.biomes[n];
                if (b != null) {
                    biome = b.unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                    grass = b.value().getGrassColor(x, z) & 0xFFFFFF;
                }
            }
            int liveColour = cellClass < 0 ? 0 : p.landColour(cellClass, liveTint, y);
            SurfaceTile t = store.peek(SurfaceTile.tileOfBlock(x), SurfaceTile.tileOfBlock(z));
            if (t == null) {
                store.loadIfSaved(SurfaceTile.tileOfBlock(x), SurfaceTile.tileOfBlock(z));
                t = store.peek(SurfaceTile.tileOfBlock(x), SurfaceTile.tileOfBlock(z));
            }
            int i = SurfaceTile.indexOfBlock(x, z);
            if (t == null || !t.explored(i)) return new Result(x, y, z, block, liveClass, cellClass, liveTint, biome, grass, -1, 0, 0, 0, 0, liveColour);
            int cls = t.surface[i], tint = SurfaceSampler.rgb888(t.tint[i]), h = t.height[i];
            return new Result(x, y, z, block, liveClass, cellClass, liveTint, biome, grass, cls, tint, h, t.revision, p.landColour(cls, tint, h), liveColour);
        });
    }
}
