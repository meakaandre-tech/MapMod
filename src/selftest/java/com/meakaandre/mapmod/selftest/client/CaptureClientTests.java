package com.meakaandre.mapmod.selftest.client;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.Capture;
import com.meakaandre.mapmod.client.capture.SurfaceClass;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

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
                + ", " + SurfaceClass.name(tile.surface[i]) + ", canopy " + (tile.canopy[i] & 0xFF) + ", " + tile.exploredCount() + " explored";
            boolean classes = true;
            for (byte c : tile.surface) classes &= c >= 0 && c < SurfaceClass.COUNT;
            out.add(new Check("capture: the sample under the player is explored and near their feet", underPlayer
                && Math.abs(tile.height[i] - (mc.player.getBlockY() - 1)) <= 24, detail));
            out.add(new Check("capture: every surface class is one of the eight", classes, ""));
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
            {Blocks.DIRT, SurfaceClass.DIRT}, {Blocks.MUD, SurfaceClass.DIRT}, {Blocks.PODZOL, SurfaceClass.DIRT},
            {Blocks.ICE, SurfaceClass.ICE}, {Blocks.PACKED_ICE, SurfaceClass.ICE}, {Blocks.LAVA, SurfaceClass.LAVA},
        };
        List<String> wrong = new ArrayList<>();
        for (Object[] e : expect) {
            byte got = SurfaceClass.classify(((Block) e[0]).defaultBlockState());
            if (got != (byte) e[1]) wrong.add(e[0] + " is " + SurfaceClass.name(got));
        }
        return new Check("capture: blocks land in their class by tag, then map colour", wrong.isEmpty(), wrong.toString());
    }
}
