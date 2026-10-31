package com.meakaandre.mapmod.map;

import com.meakaandre.mapmod.MapMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The map's save folder on the server, {@code data/mapmod} in the world: {@code id.txt}, {@code fog/},
 * {@code terrain/} (what the chunk hook found, {@code f.<x>.<z>.bin}; the folder keeps its old name so saves from before
 * MM-6 still load) and {@code markers.dat}. Server thread.
 */
public final class MapFiles {
    private static @Nullable Path root;

    private MapFiles() {
    }

    public static void start(MinecraftServer server) {
        root = server.getWorldPath(LevelResource.DATA).resolve(MapMod.ID);
        try {
            Files.createDirectories(root.resolve("terrain"));
        } catch (IOException e) {
            MapMod.LOGGER.error("could not make the map folder {}", root, e);
        }
    }

    public static void stop() {
        root = null;
    }

    /** The map's save folder, or null with no world open. */
    public static @Nullable Path root() {
        return root;
    }

    /** Writes a file whole or not at all (through a temporary file next to it). */
    public static void write(Path file, byte[] data) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            MapMod.LOGGER.error("could not write the map file {}", file, e);
        }
    }
}
