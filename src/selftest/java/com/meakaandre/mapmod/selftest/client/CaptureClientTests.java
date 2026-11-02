package com.meakaandre.mapmod.selftest.client;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.Capture;
import com.meakaandre.mapmod.client.capture.SurfaceClass;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Checks of the client-side surface capture (MM-1): chunks around the player are sampled into tiles, tiles save to
 * {@code mapmod/<server>/<dimension>/} and read back the same, {@code /mapmod stats} answers, and the client thread's
 * share stays small. On the second session (a plain server at the same address) tiles saved by the first are read in.
 */
final class CaptureClientTests {
    record Check(String what, boolean ok, String detail) {
    }

    private CaptureClientTests() {
    }

    static List<Check> run(boolean secondSession) {
        List<Check> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            out.add(new Check("capture: a player to check round", false, ""));
            return out;
        }
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        int tx = SurfaceTile.tileOfBlock(px), tz = SurfaceTile.tileOfBlock(pz);
        Capture.Snapshot before = Capture.stats();
        Path dir = Capture.dir();
        out.add(new Check("capture: tiles go to mapmod/<server>/<dimension>/", dir != null
            && dir.endsWith(Path.of("localhost", "minecraft", "overworld")), String.valueOf(dir)));
        if (secondSession) {
            out.add(new Check("capture: tiles saved last session are read back in on joining", before.tilesLoaded() > 0,
                before.tilesLoaded() + " loaded"));
        }
        out.add(new Check("capture: loaded chunks are sampled", before.samples() >= 16 * 9 && before.tilesCached() > 0,
            before.samples() + " samples, " + before.tilesCached() + " tiles"));
        SurfaceTile tile = null;
        try {
            tile = Capture.tile(tx, tz).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            MapMod.LOGGER.warn("capture test: no tile", e);
        }
        boolean underPlayer = false;
        String detail = "no tile";
        if (tile != null) {
            int i = SurfaceTile.indexOfBlock(px, pz);
            underPlayer = tile.explored(i);
            detail = "height " + tile.height[i] + " under a player at y " + mc.player.getBlockY() + ", water " + (tile.water[i] & 0xFF)
                + ", " + SurfaceClass.name(tile.surface[i]) + ", top " + (tile.above[i] & 0xFF) + " above it, " + tile.exploredCount() + " explored";
            boolean classes = true;
            for (byte c : tile.surface) classes &= c >= 0 && c < SurfaceClass.COUNT;
            // MM-9: a sample a block; its height is the column's top block, or below it where leaves, logs, plants or
            // water were looked past, and the top that shows from above is the column's top block on land
            int top = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, px, pz) - 1;
            detail += ", top block of its column at y " + top;
            out.add(new Check("capture: the sample under the player is explored and at its column's surface", underPlayer
                && tile.height[i] <= top && tile.height[i] >= top - 40, detail));
            out.add(new Check("capture: every surface class is one of the eight", classes, ""));
            // one sample per block (MM-9): the tile is 512 samples a side, and the player's chunk has all 256 explored
            int cx0 = Math.floorMod(px >> 4, SurfaceTile.CHUNKS) * 16, cz0 = Math.floorMod(pz >> 4, SurfaceTile.CHUNKS) * 16, inChunk = 0;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (tile.explored(SurfaceTile.index(cx0 + x, cz0 + z))) inChunk++;
            sizes(tile);
            out.add(new Check("capture: one sample per block, every block of the player's chunk explored",
                SurfaceTile.SPACING == 1 && SurfaceTile.SIDE == 512 && SurfaceTile.FORMAT == 3 && inChunk == 256, inChunk + " of 256"));
        } else {
            out.add(new Check("capture: the tile the player is in is held", false, detail));
        }
        try {
            Capture.flush().get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            MapMod.LOGGER.warn("capture test: flush failed", e);
        }
        Path file = dir == null ? null : dir.resolve(tx + "_" + tz + ".mmt");
        boolean same = false;
        String fileDetail = String.valueOf(file);
        try {
            if (file != null && Files.exists(file) && tile != null) {
                byte[] bytes = Files.readAllBytes(file);
                SurfaceTile back = SurfaceTile.unpack(tx, tz, bytes);
                SurfaceTile now = Capture.tile(tx, tz).get(20, TimeUnit.SECONDS);
                same = now != null && back.sameData(now) && back.exploredCount() > 0;
                fileDetail = bytes.length + " bytes, " + back.exploredCount() + " explored";
            }
        } catch (Exception e) {
            fileDetail = e.toString();
        }
        out.add(new Check("capture: a tile saves as zstd and reads back the same", same, fileDetail));
        Capture.Snapshot after = Capture.stats();
        out.add(new Check("capture: dirty tiles are saved", after.tilesSaved() > 0, after.tilesSaved() + " saved, " + after.dirtyTiles() + " dirty"));
        int runs = Capture.statsCommandRuns();
        if (mc.player.connection != null) mc.player.connection.sendCommand("mapmod stats");
        for (String line : Capture.statsLines()) MapMod.LOGGER.info("capture stats: {}", line);
        out.add(new Check("capture: /mapmod stats runs on the client", Capture.statsCommandRuns() == runs + 1, ""));
        out.add(new Check("capture: the client thread's share stays small", after.tickAvgMicros() < 1500,
            after.tickAvgMicros() + " us/tick avg, " + after.tickMaxMicros() + " us max"));
        out.add(classes());
        return out;
    }

    private static Check classes() {
        Object[][] expect = {
            {Blocks.GRASS_BLOCK, SurfaceClass.GRASS}, {Blocks.MOSS_BLOCK, SurfaceClass.GRASS}, {Blocks.OAK_LOG, SurfaceClass.FOREST},
            {Blocks.SAND, SurfaceClass.SAND}, {Blocks.RED_SAND, SurfaceClass.SAND}, {Blocks.STONE, SurfaceClass.ROCK},
            {Blocks.DEEPSLATE, SurfaceClass.ROCK}, {Blocks.TERRACOTTA, SurfaceClass.ROCK}, {Blocks.SNOW_BLOCK, SurfaceClass.SNOW},
            {Blocks.DIRT, SurfaceClass.DIRT}, {Blocks.MUD, SurfaceClass.DIRT}, {Blocks.MYCELIUM, SurfaceClass.DIRT}, {Blocks.PODZOL, SurfaceClass.DIRT},
            {Blocks.ICE, SurfaceClass.ICE}, {Blocks.PACKED_ICE, SurfaceClass.ICE}, {Blocks.LAVA, SurfaceClass.LAVA},
        };
        List<String> wrong = new ArrayList<>();
        for (Object[] e : expect) {
            byte got = SurfaceClass.classify(((Block) e[0]).defaultBlockState());
            if (got != (byte) e[1]) wrong.add(e[0] + " is " + SurfaceClass.name(got));
        }
        return new Check("capture: blocks land in their class by tag, then map colour", wrong.isEmpty(), wrong.toString());
    }

    /**
     * MM-9: the player's tile packed now, against the same land laid out as MM-8 packed it (2-block samples, each its
     * cell's middle column), with the same zstd, and both in memory: for the report on what one pixel per block costs.
     */
    static void sizes(SurfaceTile t) {
        int side = 256, area = side * side;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(area * 2 + area + area + area * 2 + area + area * 3 + area / 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] mid = new int[area];
        for (int i = 0; i < area; i++) mid[i] = SurfaceTile.index((i % side) * 2 + 1, (i / side) * 2 + 1);
        for (int i = 0; i < area; i++) b.putShort(t.height[mid[i]]);
        for (int i = 0; i < area; i++) b.put(t.water[mid[i]]);
        for (int i = 0; i < area; i++) b.put(t.surface[mid[i]]);
        for (int i = 0; i < area; i++) b.putShort(t.tint[mid[i]]);
        for (int i = 0; i < area; i++) b.put((byte) (t.above[mid[i]] != 0 ? 255 : 0));
        for (int shift = 16; shift >= 0; shift -= 8) for (int i = 0; i < area; i++) b.put((byte) (t.colour[mid[i]] >> shift));
        long[] explored = new long[area / 64];
        for (int i = 0; i < area; i++) if (t.explored(mid[i])) explored[i >>> 6] |= 1L << i;
        for (long l : explored) b.putLong(l);
        io.airlift.compress.v3.zstd.ZstdCompressor z = SurfaceTile.codec().equals("native")
            ? new io.airlift.compress.v3.zstd.ZstdNativeCompressor() : new io.airlift.compress.v3.zstd.ZstdJavaCompressor();
        byte[] out = new byte[z.maxCompressedLength(b.capacity())];
        int mm8 = z.compress(b.array(), 0, b.capacity(), out, 0, out.length) + 13, mm9 = t.pack().length;
        long mem8 = (long) area * (2 + 1 + 1 + 2 + 1 + 4) + area / 8;
        MapMod.LOGGER.info(String.format(java.util.Locale.ROOT,
            "SELFTEST sizes: the player's tile (%d of %d blocks explored) is %.1f KB on disk (MM-9), %.1f KB in MM-8's layout; %.2f MB in memory (MM-9), %.2f MB (MM-8)",
            t.exploredCount(), SurfaceTile.AREA, mm9 / 1024.0, mm8 / 1024.0, SurfaceTile.memoryBytes() / 1048576.0, mem8 / 1048576.0));
    }
}
