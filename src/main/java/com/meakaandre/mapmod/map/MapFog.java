package com.meakaandre.mapmod.map;

import com.meakaandre.mapmod.MapMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Each group's fog of war, shared by its players: one bit per chunk of the Overworld, set once one of them has been
 * near it or a reveal has swept it. Kept per group in {@code data/mapmod/fog/<group>.bin}.
 */
public final class MapFog {
    private static final Map<String, Long2ObjectOpenHashMap<long[]>> FOG = new HashMap<>();
    private static final Set<String> DIRTY = new HashSet<>();

    private MapFog() {
    }

    public static void stop() {
        saveAll();
        FOG.clear();
        DIRTY.clear();
    }

    private static @Nullable Path file(String group) {
        Path root = MapTerrain.root();
        if (root == null) return null;
        // group ids are free text: anything but letters, digits, _ and - is kept out of the path
        String safe = group.replaceAll("[^A-Za-z0-9_-]", "_");
        return root.resolve("fog").resolve(safe + ".bin");
    }

    /** The group's fog, by region (loaded the first time). */
    public static Long2ObjectOpenHashMap<long[]> of(String group) {
        Long2ObjectOpenHashMap<long[]> fog = FOG.get(group);
        if (fog != null) return fog;
        fog = new Long2ObjectOpenHashMap<>();
        FOG.put(group, fog);
        Path file = file(group);
        if (file != null && Files.exists(file)) {
            try (DataInputStream in = new DataInputStream(new InflaterInputStream(Files.newInputStream(file)))) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    long region = in.readLong();
                    long[] bits = new long[MapGrid.FOG_LONGS];
                    for (int j = 0; j < bits.length; j++) bits[j] = in.readLong();
                    fog.put(region, bits);
                }
            } catch (IOException e) {
                MapMod.LOGGER.warn("unreadable map fog {}", file, e);
            }
        }
        return fog;
    }

    public static boolean explored(String group, int chunkX, int chunkZ) {
        long[] bits = of(group).get(MapGrid.regionOfChunk(chunkX, chunkZ));
        return bits != null && MapGrid.get(bits, MapGrid.fogBit(chunkX, chunkZ));
    }

    /**
     * Clears the fog over a disc of chunks. Returns the chunks newly cleared, and adds the regions that had nothing
     * explored before to {@code newRegions}.
     */
    public static LongArrayList reveal(String group, int centreChunkX, int centreChunkZ, int radiusChunks, LongOpenHashSet newRegions) {
        Long2ObjectOpenHashMap<long[]> fog = of(group);
        LongArrayList cleared = new LongArrayList();
        int r2 = radiusChunks * radiusChunks;
        for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                if (dx * dx + dz * dz > r2) continue;
                int cx = centreChunkX + dx, cz = centreChunkZ + dz;
                long region = MapGrid.regionOfChunk(cx, cz);
                long[] bits = fog.get(region);
                if (bits == null) {
                    bits = new long[MapGrid.FOG_LONGS];
                    fog.put(region, bits);
                    newRegions.add(region);
                }
                if (MapGrid.set(bits, MapGrid.fogBit(cx, cz))) cleared.add(MapGrid.key(cx, cz));
            }
        }
        if (!cleared.isEmpty()) DIRTY.add(group);
        return cleared;
    }

    /** Folds one group's fog into another's (groups merging). */
    public static void fold(String from, String into) {
        Long2ObjectOpenHashMap<long[]> source = of(from), target = of(into);
        for (var e : source.long2ObjectEntrySet()) {
            long[] bits = target.computeIfAbsent(e.getLongKey(), k -> new long[MapGrid.FOG_LONGS]);
            for (int i = 0; i < bits.length; i++) bits[i] |= e.getValue()[i];
        }
        DIRTY.add(into);
    }

    public static void saveAll() {
        for (String group : DIRTY) {
            Path file = file(group);
            Long2ObjectOpenHashMap<long[]> fog = FOG.get(group);
            if (file == null || fog == null) continue;
            try {
                Files.createDirectories(file.getParent());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
                    out.writeInt(fog.size());
                    for (var e : fog.long2ObjectEntrySet()) {
                        out.writeLong(e.getLongKey());
                        for (long l : e.getValue()) out.writeLong(l);
                    }
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, bytes.toByteArray());
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                MapMod.LOGGER.error("could not write the map fog {}", file, e);
            }
        }
        DIRTY.clear();
    }
}
