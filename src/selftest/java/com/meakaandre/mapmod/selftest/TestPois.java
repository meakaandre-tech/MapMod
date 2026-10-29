package com.meakaandre.mapmod.selftest;

import com.meakaandre.mapmod.api.FoundPoi;
import com.meakaandre.mapmod.api.GroupProvider;
import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.api.MapGlyphs;
import com.meakaandre.mapmod.api.MapModApi;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
import com.meakaandre.mapmod.api.PoiArea;
import com.meakaandre.mapmod.api.PoiCategory;
import com.meakaandre.mapmod.api.PoiKind;
import com.meakaandre.mapmod.api.PoiSource;
import com.meakaandre.mapmod.api.PoiSubType;
import com.meakaandre.mapmod.api.UnlockProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A test mod's use of the map's public API, the way any other mod would plug in (and nothing but the API): a filter
 * category with four kinds, a POI source worked out per group and region, a chunk scanner, a moving source, a custom
 * glyph, and group and unlock providers the selftests can steer. With nothing steered, the providers behave exactly
 * as the map's defaults (every player alone, everything unlocked).
 */
public final class TestPois {
    public static final String NS = "mapmod_selftest";
    /** Blocks between the source's ore spots. */
    public static final int CELL = 128;

    public static PoiCategory FINDS;
    public static PoiKind ORE, CAMP, BEACON, CACHE, CART;
    public static int TENT;
    public static final Identifier SCANNER = id("scanner"), SOURCE = id("seed");

    /** Players moved into a group by a test; everyone else is alone (the default). */
    public static final Map<UUID, String> GROUPS = new ConcurrentHashMap<>();
    /** Players with only some features unlocked; everyone else has all. */
    public static final Map<UUID, Integer> UNLOCKS = new ConcurrentHashMap<>();
    /** Ore sub-types a group may not see yet (its stamp changes with this). */
    public static final Map<String, Set<String>> LOCKED = new ConcurrentHashMap<>();
    /** Enchanting tables placed by a test, and the group that owns each; others belong to everyone. */
    public static final Map<BlockPos, String> OWNERS = new ConcurrentHashMap<>();
    /** Players whose death leaves no plain death marker (a test of skipDeathMarkers). */
    public static final Set<UUID> NO_DEATH_MARKER = ConcurrentHashMap.newKeySet();

    private TestPois() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NS, path);
    }

    /** The default every player alone, unless a test moved them. */
    public static final GroupProvider GROUP_PROVIDER = new GroupProvider() {
        @Override
        public String groupOf(MinecraftServer server, UUID player) {
            String g = GROUPS.get(player);
            return g != null ? g : GroupProvider.ALONE.groupOf(server, player);
        }

        @Override
        public String sharedLabel() {
            return "mapmod_selftest.shared";
        }
    };

    public static final UnlockProvider UNLOCK_PROVIDER = new UnlockProvider() {
        @Override
        public boolean unlocked(ServerPlayer player, MapFeature feature) {
            Integer mask = UNLOCKS.get(player.getUUID());
            return mask == null ? UnlockProvider.ALWAYS.unlocked(player, feature) : (mask & feature.bit()) != 0;
        }

        @Override
        public String lockedMessage(ServerPlayer player) {
            return "mapmod_selftest.locked";
        }
    };

    /** POIs the seed puts in a region: an ore spot in some 128-block cells, a camp in the middle. */
    public static final PoiSource<Set<String>> SEED = new PoiSource<>() {
        @Override
        public Set<String> prepare(ServerLevel level, String group) {
            return Set.copyOf(LOCKED.getOrDefault(group, Set.of()));
        }

        @Override
        public List<MapPoi> pois(ServerLevel level, PoiArea area, Set<String> locked) {
            List<MapPoi> out = new ArrayList<>();
            int sea = level.getSeaLevel();
            for (int x = area.minX(); x < area.maxX(); x += CELL) {
                for (int z = area.minZ(); z < area.maxZ(); z += CELL) {
                    long h = hash(x / CELL, z / CELL);
                    if ((h & 1) == 0) continue;
                    String sub = SUBS[(int) Math.floorMod(h >> 3, (long) SUBS.length)];
                    if (locked.contains(sub)) continue;
                    int purity = (int) Math.floorMod(h >> 7, 3L);
                    out.add(MapPoi.of(ORE, sub, purity, x + (int) Math.floorMod(h >> 11, (long) CELL), sea, z + (int) Math.floorMod(h >> 19, (long) CELL)));
                }
            }
            out.add(MapPoi.of(CAMP, area.minX() + 256, sea, area.minZ() + 256));
            return out;
        }

        @Override
        public int stamp(MinecraftServer server, String group) {
            return LOCKED.getOrDefault(group, Set.of()).hashCode();
        }
    };

    public static final String[] SUBS = {"iron", "copper", "gold", "tin"};

    public static long hash(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 32);
    }

    public static void register() {
        TENT = MapGlyphs.register(
            "....#....",
            "...###...",
            "..##.##..",
            "..#...#..",
            ".##...##.",
            ".#.....#.",
            "##.....##",
            "#...#...#",
            "#########");
        FINDS = MapModApi.category(id("finds"), Component.literal("Test finds"));
        ORE = MapModApi.kind(id("ore"), FINDS).label(Component.literal("Test ore")).glyph(MapGlyphs.NODE, 0xFFF0F0F0).purity().subFilters()
            .sub(new PoiSubType("iron", Component.literal("Test iron"), Identifier.fromNamespaceAndPath("minecraft", "raw_iron"), -1, 0))
            .sub(new PoiSubType("copper", Component.literal("Test copper"), Identifier.fromNamespaceAndPath("minecraft", "raw_copper"), -1, 0))
            .sub(new PoiSubType("gold", Component.literal("Test gold"), List.<Identifier>of(), MapGlyphs.DROP, 0xFFF2D64B))
            // a mod's item that may be missing first, a vanilla one after it
            .sub(new PoiSubType("tin", Component.literal("Test tin"), List.of(Identifier.fromNamespaceAndPath("nosuchmod", "raw_tin"),
                Identifier.fromNamespaceAndPath("minecraft", "raw_gold")), -1, 0))
            .compassRange(120).detail().register();
        CAMP = MapModApi.kind(id("camp"), FINDS).label(Component.literal("Test camp")).glyph(TENT, 0xFFFFA530).compassNearest().register();
        BEACON = MapModApi.kind(id("beacon"), FINDS).label(Component.literal("Test beacon")).glyph(MapGlyphs.RADAR, 0xFF4FB8E8).compassRange(300).register();
        CACHE = MapModApi.kind(id("cache"), FINDS).label(Component.literal("Test cache")).glyph(MapGlyphs.SQUARE, 0xFFB06BE0).register();
        CART = MapModApi.kind(id("cart"), null).label(Component.literal("Test cart")).glyph(MapGlyphs.TRUCK, 0xFFFFC060).dimColour(0xC0D8B070).register();

        MapModApi.setGroups(GROUP_PROVIDER);
        MapModApi.setUnlocks(UNLOCK_PROVIDER);
        MapModApi.addSource(SOURCE, SEED);
        MapModApi.addChunkScanner(SCANNER, (level, chunk) -> {
            List<FoundPoi> out = new ArrayList<>();
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                BlockPos p = be.getBlockPos();
                if (be instanceof BeaconBlockEntity) out.add(FoundPoi.everyone(MapPoi.of(BEACON, p.getX(), p.getY(), p.getZ())));
                else if (be instanceof EnchantingTableBlockEntity) out.add(new FoundPoi(MapPoi.of(CACHE, p.getX(), p.getY(), p.getZ()), OWNERS.getOrDefault(p, "")));
            }
            return out;
        });
        // a cart going round every player at 40 blocks, once a minute; dimmed half the way round
        MapModApi.addMovingSource((player, group) -> {
            double t = (player.level().getGameTime() % 1200) / 1200.0 * Math.PI * 2;
            double x = player.getX() + Math.cos(t) * 40, z = player.getZ() + Math.sin(t) * 40;
            boolean far = Math.sin(t) < 0;
            return List.of(MovingPoi.of(CART, x, z, (float) Math.toDegrees(t), far, far ? "mapmod_selftest.by_time" : ""));
        });
        MapModApi.skipDeathMarkers(player -> NO_DEATH_MARKER.contains(player.getUUID()));
    }

    /** A copy of a set, for tests that look before and after. */
    public static Set<String> copy(Set<String> s) {
        return new HashSet<>(s);
    }

    public static Map<String, Integer> counts(List<MapPoi> pois) {
        Map<String, Integer> out = new HashMap<>();
        for (MapPoi p : pois) out.merge(p.kind(), 1, Integer::sum);
        return out;
    }
}
