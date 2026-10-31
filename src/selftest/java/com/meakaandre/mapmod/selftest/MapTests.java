package com.meakaandre.mapmod.selftest;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.api.FoundPoi;
import com.meakaandre.mapmod.api.GroupProvider;
import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.api.MapModApi;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
import com.meakaandre.mapmod.api.PoiCategory;
import com.meakaandre.mapmod.api.UnlockProvider;
import com.meakaandre.mapmod.map.MapFog;
import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapMarker;
import com.meakaandre.mapmod.map.MapMarkers;
import com.meakaandre.mapmod.map.MapPayloads;
import com.meakaandre.mapmod.map.MapServer;
import com.meakaandre.mapmod.api.PoiKind;
import com.meakaandre.mapmod.map.MapFiles;
import com.meakaandre.mapmod.map.MapFound;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /mapmod selftest map: the defaults (every player alone, everything unlocked) and each part of the API through the
 * test provider ({@link TestPois}): fog kept per group; the chunk hook finding things, for everyone or for their owner
 * only; POIs set for unloaded chunks and looked up nearest first; a POI source worked out per group, and again when its
 * stamp moves; a reveal clearing a disc; markers private or shared, a second player of the group changing and removing a shared one;
 * death markers (the last three kept, a kept one until removed, none for a skipped player); unlock gates; moving POIs;
 * groups merging; and MM-6's cleanup (no server terrain, no tile payloads, no minimap feature, no POI detail flag).
 * /mapmod selftest restart checks that what was saved loads again.
 * <p>
 * {@code /mapmod worldtest ...} drives the smoke run's client for its screenshots (see {@link Control}). Every check
 * prints "SELFTEST map" (or "SELFTEST restart"); failures say FAIL.
 */
public final class MapTests {
    private static final String A = "selftest_group_a", B = "selftest_group_b", FROM = "selftest_group_from";
    private static final UUID MEMBER_1 = UUID.fromString("00000000-0000-4000-8000-0000000000a1"), MEMBER_2 = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    /** Well away from spawn, where the smoke run's player stands. */
    private static final int X = -9000, Z = 7000;
    /** A second player of the smoke run's group (never online), who places a shared marker for the real player. */
    private static final UUID MATE = UUID.fromString("00000000-0000-4000-8000-00000000beef");
    private static final String SMOKE = "selftest_smoke";
    private static final int REVEAL = 1056;

    /** What the map's remote control does on the client. */
    public enum Action {
        OPEN, CLOSE, HIDE, SHOW, UNFOLD, PIN_MARKER, EDIT, CHECK
    }

    /**
     * Server to client, from the smoke run's {@code /mapmod worldtest}: opens or closes the map, centred and zoomed,
     * switches a filter, pins a marker or runs the client's checks (key presses under the CI's virtual display are not
     * reliable enough).
     */
    public record Control(Action action, String arg, int x, int z, float zoom) implements CustomPacketPayload {
        public static final Type<Control> TYPE = new Type<>(TestPois.id("control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.action.ordinal());
            buf.writeUtf(p.arg);
            buf.writeVarInt(p.x);
            buf.writeVarInt(p.z);
            buf.writeFloat(p.zoom);
        }, buf -> new Control(Action.values()[buf.readVarInt()], buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final TestQueue LATER = new TestQueue();
    private static final TestReport REPORT = new TestReport("SELFTEST map ");
    private static final TestReport RESTART = new TestReport("SELFTEST restart ");
    private static final TestReport SMOKE_REPORT = new TestReport("SELFTEST smoke ");
    /** Checks still to come after the command returns. */
    private static int laterChecks;

    private MapTests() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Control.TYPE, Control.CODEC);
        LATER.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal(MapMod.ID).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest")
                    .then(Commands.literal("map").executes(MapTests::run))
                    .then(Commands.literal("restart").executes(MapTests::restart)))
                .then(Commands.literal("worldtest")
                    .then(Commands.literal("setup").executes(MapTests::setup))
                    .then(Commands.literal("stamp").executes(MapTests::stamp))
                    .then(Commands.literal("lock").executes(context -> lock(context, true)))
                    .then(Commands.literal("unlock").executes(context -> lock(context, false)))
                    .then(Commands.literal("open").then(Commands.argument("zoom", FloatArgumentType.floatArg(0.01f, 8))
                        .executes(context -> control(context, Action.OPEN, "", FloatArgumentType.getFloat(context, "zoom")))
                        .then(Commands.argument("marker", StringArgumentType.greedyString())
                            .executes(context -> control(context, Action.OPEN, StringArgumentType.getString(context, "marker"), FloatArgumentType.getFloat(context, "zoom"))))))
                    .then(Commands.literal("close").executes(context -> control(context, Action.CLOSE, "", 0)))
                    .then(Commands.literal("edit").executes(context -> control(context, Action.EDIT, "", 0)))
                    .then(Commands.literal("check").then(Commands.argument("what", StringArgumentType.greedyString())
                        .executes(context -> control(context, Action.CHECK, StringArgumentType.getString(context, "what"), 0))))
                    .then(Commands.literal("pin").then(Commands.argument("marker", StringArgumentType.greedyString())
                        .executes(context -> control(context, Action.PIN_MARKER, StringArgumentType.getString(context, "marker"), 0))))
                    .then(Commands.literal("unfold").then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(context -> control(context, Action.UNFOLD, StringArgumentType.getString(context, "filter"), 0))))
                    .then(Commands.literal("hide").then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(context -> control(context, Action.HIDE, StringArgumentType.getString(context, "filter"), 0))))
                    .then(Commands.literal("show").then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(context -> control(context, Action.SHOW, StringArgumentType.getString(context, "filter"), 0)))))));
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        REPORT.expect(source, what, ok, detail);
    }

    private static MapMarker marker(String name, int x, int z, boolean shared) {
        MapMarker m = new MapMarker();
        m.name = name;
        m.x = x;
        m.y = 70;
        m.z = z;
        m.icon = 1;
        m.colour = 2;
        m.shared = shared;
        return m;
    }

    /** A check that runs once {@code ready} holds (or after the timeout), counted among the later checks. */
    private static void when(CommandSourceStack source, ServerLevel level, java.util.function.BooleanSupplier ready, int ticks, String what, Runnable then) {
        laterChecks++;
        LATER.when(level.getGameTime(), ready, ticks, ok -> {
            REPORT.attempt(source, what, then);
            if (--laterChecks == 0) REPORT.report(source, "done (later checks): " + REPORT.totals());
        });
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        REPORT.reset();
        laterChecks = 0;
        REPORT.attempt(source, "the checks", () -> checks(source, server, level));
        REPORT.report(source, "done: " + REPORT.totals() + " (" + laterChecks + " later checks follow)");
        if (laterChecks == 0) REPORT.report(source, "done (later checks): " + REPORT.totals());
        return REPORT.failed == 0 ? 1 : 0;
    }

    private static void checks(CommandSourceStack source, MinecraftServer server, ServerLevel level) {
        // ---- the defaults and the registry
        UUID someone = UUID.randomUUID();
        expect(source, "by default every player is a group of their own, and nothing is shared",
            GroupProvider.ALONE.groupOf(server, someone).equals(someone.toString()) && MapModApi.groups().groupOf(server, someone).equals(someone.toString())
                && !GroupProvider.ALONE.same("", ""), "");
        FakePlayer fake = FakePlayer.get(level);
        boolean all = true;
        for (MapFeature f : MapFeature.values()) all &= UnlockProvider.ALWAYS.unlocked(fake, f) && MapModApi.unlocked(fake, f);
        expect(source, "by default terrain and the fullscreen map are unlocked", all, "");
        List<PoiCategory> cats = MapModApi.categories();
        expect(source, "the filter panel lists players, markers, then the registered categories in order",
            cats.size() >= 3 && cats.get(0) == MapModApi.PLAYERS && cats.get(1) == MapModApi.MARKERS && cats.get(2) == TestPois.FINDS, cats.size() + " categories");
        expect(source, "kinds keep their category, sub-types and filter switches", MapModApi.kind(TestPois.ORE.key()) == TestPois.ORE
            && TestPois.ORE.subFilters() && !TestPois.ORE.filter() && TestPois.ORE.subTypes().size() == 4 && TestPois.CAMP.filter() && TestPois.CART.category() == null, "");
        expect(source, "vanilla's player locator bar is switched off", !server.getGameRules().get(GameRules.LOCATOR_BAR), "");
        expect(source, "the map saves into data/mapmod", MapFiles.root() != null && MapFiles.root().endsWith(MapMod.ID)
            && Files.exists(MapFiles.root().resolve("id.txt")), String.valueOf(MapFiles.root()));
        cleanup(source);

        // ---- groups: fog is per group, and shared inside it
        TestPois.GROUPS.put(MEMBER_1, A);
        TestPois.GROUPS.put(MEMBER_2, A);
        expect(source, "a group provider moves players into a group", MapModApi.groups().groupOf(server, MEMBER_1).equals(A)
            && MapModApi.groups().same(MapModApi.groups().groupOf(server, MEMBER_2), A), "");
        LongOpenHashSet fresh = new LongOpenHashSet();
        var cleared = MapFog.reveal(A, X >> 4, Z >> 4, 10, fresh);
        expect(source, "a visit clears a disc of chunks for the group",
            !cleared.isEmpty() && MapModApi.explored(A, X >> 4, Z >> 4) && MapModApi.explored(A, (X >> 4) + 9, Z >> 4) && !MapModApi.explored(A, (X >> 4) + 9, (Z >> 4) + 9),
            cleared.size() + " chunks");
        expect(source, "another group's fog is its own", !MapModApi.explored(B, X >> 4, Z >> 4), "");

        // ---- the chunk hook, on a loaded chunk
        LevelChunk chunk = level.getChunk(X >> 4, Z >> 4);
        BlockPos beacon = new BlockPos((X & ~15) + 8, level.getMinY() + 12, (Z & ~15) + 8);
        BlockPos cache = beacon.offset(-4, 0, 3);
        level.setBlock(beacon, Blocks.BEACON.defaultBlockState(), 3);
        level.setBlock(cache, Blocks.ENCHANTING_TABLE.defaultBlockState(), 3);
        TestPois.OWNERS.put(cache, A);
        MapFound.scan(level, chunk);
        long region = MapGrid.regionOfChunk(X >> 4, Z >> 4);
        expect(source, "scanning a chunk writes no terrain on the server", terrainFiles().isEmpty(), terrainFiles().toString());
        List<MapPoi> seenB = MapModApi.foundIn(region, B), seenA = MapModApi.foundIn(region, A);
        expect(source, "the chunk hook finds a beacon in a recorded chunk, for everyone",
            has(seenB, TestPois.BEACON.key(), beacon.getX(), beacon.getZ()) && has(seenA, TestPois.BEACON.key(), beacon.getX(), beacon.getZ()), "");
        expect(source, "what the hook found for one group shows to that group only",
            has(seenA, TestPois.CACHE.key(), cache.getX(), cache.getZ()) && !has(seenB, TestPois.CACHE.key(), cache.getX(), cache.getZ()), "");
        expect(source, "what a group has explored shows on its map, from the hook",
            has(MapServer.poisFor(A, region), TestPois.BEACON.key(), beacon.getX(), beacon.getZ()) && !has(MapServer.poisFor(B, region), TestPois.BEACON.key(), beacon.getX(), beacon.getZ()), "");
        int fx = X + 20_000, fz = Z;
        MapModApi.setChunkPois(level, TestPois.SCANNER, fx >> 4, fz >> 4, List.of(
            FoundPoi.everyone(MapPoi.of(TestPois.BEACON, fx + 12, 40, fz + 2)), FoundPoi.everyone(MapPoi.of(TestPois.BEACON, fx + 1, 40, fz + 1))));
        List<MapPoi> near = MapModApi.foundNear(TestPois.BEACON, fx, fz, 64);
        expect(source, "POIs set for a chunk nobody loaded are found, nearest first", near.size() == 2 && near.get(0).x() == fx + 1 && near.get(1).x() == fx + 12, near.size() + " found");
        MapModApi.setChunkPois(level, TestPois.SCANNER, fx >> 4, fz >> 4, List.of());
        expect(source, "and go again when set to nothing", MapModApi.foundNear(TestPois.BEACON, fx, fz, 64).isEmpty(), "");

        // ---- a POI source: worked out for the group, and again when its stamp moves
        TestPois.LOCKED.put(A, Set.of(TestPois.SUBS));
        // the group goes to the region's first few ore spots, so some are explored whatever the seed's grid puts here
        List<MapPoi> expected = new ArrayList<>();
        for (MapPoi p : TestPois.SEED.pois(level, MapServer.area(region), Set.of())) {
            if (!p.kind().equals(TestPois.ORE.key()) || expected.size() >= 3) continue;
            MapFog.reveal(A, p.x() >> 4, p.z() >> 4, 1, new LongOpenHashSet());
            expected.add(p);
        }
        MapModApi.refreshPois(server, A);
        when(source, level, () -> kinds(MapServer.poisFor(A, region), TestPois.CAMP.key()) > 0, 200, "source POIs arrive", () -> {
            List<MapPoi> now = MapServer.poisFor(A, region);
            expect(source, "a POI source's POIs show where the group explored (a camp)", kinds(now, TestPois.CAMP.key()) == 1, TestPois.counts(now).toString());
            expect(source, "sub-types the group may not see yet stay off its map", kinds(now, TestPois.ORE.key()) == 0, "");
            // nobody of the group is online, so its stamp is not watched (worldtest stamp checks that, with the smoke
            // run's player): a mod says so itself
            TestPois.LOCKED.remove(A);
            MapModApi.refreshPois(server, A);
            when(source, level, () -> kinds(MapServer.poisFor(A, region), TestPois.ORE.key()) >= expected.size(), 200, "a refresh", () -> {
                List<MapPoi> after = MapServer.poisFor(A, region);
                boolean right = !expected.isEmpty();
                for (MapPoi p : expected) right &= after.contains(p);
                expect(source, "refreshPois works the group's POIs out again (ores with their purity)", right,
                    kinds(after, TestPois.ORE.key()) + " ores, " + expected.size() + " expected");
            });
        });

        // ---- a reveal clears its range
        int rx = X + 4000, rz = Z;
        MapModApi.reveal(level, B, new BlockPos(rx, 80, rz), REVEAL);
        boolean in = MapModApi.explored(B, (rx + 1000) >> 4, rz >> 4), beyond = MapModApi.explored(B, (rx + 1200) >> 4, rz >> 4);
        expect(source, "a reveal clears the group's fog over its radius", in && !beyond && MapModApi.explored(B, rx >> 4, (rz - 900) >> 4), "");

        // ---- markers: private, shared, and a second player of the group
        MapMarkers markers = MapMarkers.get(server);
        String m1 = MEMBER_1.toString(), m2 = MEMBER_2.toString();
        markers.all().removeIf(m -> m.owner.equals(m1) || m.owner.equals(m2));
        String r1 = MapServer.apply(server, m1, A, MapPayloads.ADD, marker("Iron here", X + 10, Z, true));
        String r2 = MapServer.apply(server, m1, A, MapPayloads.ADD, marker("My secret", X + 20, Z, false));
        MapMarker shared = markers.visibleTo(m2, A).stream().filter(m -> m.name.equals("Iron here")).findFirst().orElse(null);
        expect(source, "a shared marker reaches the group's other player; a private one does not",
            r1 == null && r2 == null && shared != null && markers.visibleTo(m2, A).stream().noneMatch(m -> m.name.equals("My secret"))
                && markers.visibleTo(m1, A).size() == 2, "");
        expect(source, "no other group sees either", markers.visibleTo("someone-else", B).stream().noneMatch(m -> m.owner.equals(m1)), "");
        if (shared != null) {
            MapMarker rename = marker("Iron, pure", 0, 0, true);
            rename.id = shared.id;
            String r3 = MapServer.apply(server, m2, A, MapPayloads.UPDATE, rename);
            expect(source, "the other player changes the shared marker and the first sees the change",
                r3 == null && markers.visibleTo(m1, A).stream().anyMatch(m -> m.id.equals(shared.id) && m.name.equals("Iron, pure") && m.x == X + 10), "");
            MapMarker secret = markers.visibleTo(m1, A).stream().filter(m -> m.name.equals("My secret")).findFirst().orElse(null);
            MapMarker hit = new MapMarker();
            hit.id = secret == null ? "" : secret.id;
            String r4 = MapServer.apply(server, m2, A, MapPayloads.DELETE, hit);
            String r5 = MapServer.apply(server, "someone-else", B, MapPayloads.DELETE, rename);
            expect(source, "nobody else can remove a private marker, and no other group a shared one",
                "mapmod.map.not_yours".equals(r4) && "mapmod.map.not_yours".equals(r5) && markers.byId(shared.id) != null, r4 + ", " + r5);
            String r6 = MapServer.apply(server, m2, A, MapPayloads.DELETE, rename);
            expect(source, "the other player removes the shared marker for both", r6 == null && markers.byId(shared.id) == null, "");
        }
        // kept for the restart check
        MapServer.apply(server, m1, A, MapPayloads.ADD, marker("Restart check", X + 30, Z + 5, true));

        // ---- death markers: private, the last three kept; a kept one until it is removed; none for a skipped player
        String fakeId = fake.getUUID().toString();
        markers.all().removeIf(m -> m.owner.equals(fakeId));
        fake.setPos(X + 0.5, 90, Z + 0.5);
        String kept = MapModApi.deathMarker(fake, level, new BlockPos(X + 5, 64, Z + 5), true);
        for (int i = 0; i < 4; i++) MapServer.deathMarker(fake);
        long deaths = markers.all().stream().filter(m -> m.death && !m.kept && m.owner.equals(fakeId)).count();
        boolean placedRight = markers.all().stream().anyMatch(m -> m.death && m.owner.equals(fakeId) && m.x == X && m.z == Z && !m.shared);
        expect(source, "dying leaves a private death marker where it happened; the last three are kept", deaths == 3 && placedRight, deaths + " kept");
        expect(source, "a kept death marker outlasts them", markers.byId(kept) != null, "");
        MapModApi.removeMarker(server, kept);
        expect(source, "and goes once a mod removes it", markers.byId(kept) == null, "");
        TestPois.NO_DEATH_MARKER.add(fake.getUUID());
        expect(source, "a mod can say a player's death leaves no plain death marker", MapModApi.skipsDeathMarker(fake), "");
        TestPois.NO_DEATH_MARKER.remove(fake.getUUID());
        expect(source, "and other players still get theirs", !MapModApi.skipsDeathMarker(fake), "");
        markers.all().removeIf(m -> m.owner.equals(fakeId) || m.name.equals("My secret"));
        markers.setDirty();

        // ---- unlocks: per-player gates
        TestPois.UNLOCKS.put(fake.getUUID(), MapFeature.TERRAIN.bit());
        expect(source, "an unlock provider can give a player terrain but not the fullscreen map",
            MapModApi.unlocked(fake, MapFeature.TERRAIN) && !MapModApi.unlocked(fake, MapFeature.FULLSCREEN), "");
        TestPois.UNLOCKS.remove(fake.getUUID());

        // ---- moving POIs
        List<MovingPoi> moving = new ArrayList<>();
        for (var s : MapModApi.movingSources()) moving.addAll(s.moving(fake, MapModApi.groupOf(fake)));
        expect(source, "a moving source supplies what moves round a player", moving.size() == 1 && moving.get(0).kind().equals(TestPois.CART.key())
            && Math.abs(Math.hypot(moving.get(0).x() - fake.getX(), moving.get(0).z() - fake.getZ()) - 40) < 0.5, moving.size() + " moving");

        // ---- groups merging: the fog and the shared markers go along
        MapFog.reveal(FROM, (X + 3000) >> 4, Z >> 4, 2, new LongOpenHashSet());
        MapServer.apply(server, m1, FROM, MapPayloads.ADD, marker("Merged", X + 3000, Z, true));
        MapModApi.mergeGroups(server, FROM, B);
        expect(source, "a group folded into another takes its fog and its shared markers along",
            MapModApi.explored(B, (X + 3000) >> 4, Z >> 4) && markers.visibleTo("someone-else", B).stream().anyMatch(m -> m.name.equals("Merged")), "");
        markers.all().removeIf(m -> m.name.equals("Merged"));
        markers.setDirty();
        MapFog.saveAll();
    }

    // ------------------------------------------------------------------------------------------------ MM-6 cleanup

    /** Server terrain files ({@code r.<x>.<z>.bin}) in data/mapmod/terrain: the server keeps none since MM-6. */
    private static List<String> terrainFiles() {
        List<String> out = new ArrayList<>();
        Path root = MapFiles.root();
        if (root == null) return out;
        try (var files = Files.list(root.resolve("terrain"))) {
            files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("r.")).forEach(out::add);
        } catch (Exception e) {
            out.add(e.toString());
        }
        return out;
    }

    private static boolean gone(String className) {
        try {
            Class.forName(className, false, MapTests.class.getClassLoader());
            return false;
        } catch (ClassNotFoundException e) {
            return true;
        }
    }

    /** MM-6's cleanup, on the server: what went is gone, and nothing on the map changed for it. */
    private static void cleanup(CommandSourceStack source) {
        List<String> left = new ArrayList<>();
        for (String c : List.of("map.MapTerrain", "map.MapRecorder", "map.MapTile", "map.MapGuess", "map.MapPalette", "map.MapPayloads$Tile",
            "map.MapPayloads$TileRequest")) {
            if (!gone("com.meakaandre.mapmod." + c)) left.add(c);
        }
        expect(source, "the server's pixel terrain is gone: MapTerrain, MapRecorder, MapTile, MapGuess, the old map/MapPalette and the tile payloads",
            left.isEmpty(), left.isEmpty() ? "" : "still there: " + left);
        expect(source, "the name MapPalette is free for the API's", !gone("com.meakaandre.mapmod.api.MapPalette"), "");
        expect(source, "the server writes no terrain files", terrainFiles().isEmpty(), terrainFiles().toString());
        expect(source, "MapFeature has no MINIMAP: TERRAIN and FULLSCREEN, all two of them",
            Arrays.stream(MapFeature.values()).map(Enum::name).toList().equals(List.of("TERRAIN", "FULLSCREEN")) && MapFeature.ALL == 3,
            Arrays.toString(MapFeature.values()));
        boolean noDetail = Arrays.stream(PoiKind.class.getMethods()).noneMatch(m -> m.getName().equals("detail"))
            && Arrays.stream(PoiKind.Builder.class.getMethods()).noneMatch(m -> m.getName().equals("detail"));
        expect(source, "PoiKind has no detail() (it was for the minimap)", noDetail, "");
    }

    private static boolean has(List<MapPoi> pois, String kind, int x, int z) {
        for (MapPoi p : pois) if (p.kind().equals(kind) && p.x() == x && p.z() == z) return true;
        return false;
    }

    private static int kinds(List<MapPoi> pois, String kind) {
        int n = 0;
        for (MapPoi p : pois) if (p.kind().equals(kind)) n++;
        return n;
    }

    // ------------------------------------------------------------------------------------------------ after a restart

    private static int restart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        RESTART.reset();
        RESTART.attempt(source, "the restart checks", () -> {
            long region = MapGrid.regionOfChunk(X >> 4, Z >> 4);
            RESTART.expect(source, "the map keeps its id", MapFiles.root() != null && Files.exists(MapFiles.root().resolve("id.txt")), "");
            RESTART.expect(source, "a group's fog loads again", MapModApi.explored(A, X >> 4, Z >> 4) && MapModApi.explored(B, (X + 4000) >> 4, Z >> 4), "");
            RESTART.expect(source, "what the chunk hook found loads again", MapModApi.foundIn(region, B).stream().anyMatch(p -> p.kind().equals(TestPois.BEACON.key())), "");
            RESTART.expect(source, "markers load again", MapMarkers.get(server).all().stream().anyMatch(m -> m.name.equals("Restart check") && m.shared && m.group.equals(A)), "");
            RESTART.expect(source, "still no server terrain after a restart", terrainFiles().isEmpty(), terrainFiles().toString());
            RESTART.expect(source, "the map saves under data/mapmod in the world", Files.isDirectory(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATA).resolve(MapMod.ID).resolve("terrain"))
                && Files.isDirectory(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATA).resolve(MapMod.ID).resolve("fog")), "");
        });
        RESTART.report(source, "done: " + RESTART.totals());
        return RESTART.failed == 0 ? 1 : 0;
    }

    // ------------------------------------------------------------------------------------------------ the smoke run

    /**
     * Puts every online player in the smoke group with a second player who is never online, reveals the land round them,
     * and has that second player place a shared marker near them; adds a marker of their
     * own, a death marker, a beacon and an enchanting table the group owns, for the screenshots.
     */
    private static int setup(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        TestPois.GROUPS.put(MATE, SMOKE);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer) continue;
            TestPois.GROUPS.put(player.getUUID(), SMOKE);
            MapModApi.groupChanged(player);
            BlockPos at = player.blockPosition();
            MapModApi.reveal(level, SMOKE, at, REVEAL);
            MapServer.apply(server, MATE.toString(), SMOKE, MapPayloads.ADD, marker("Placed by a group mate", at.getX() + 60, at.getZ() - 40, true));
            MapMarker mine = marker("My marker", at.getX() - 70, at.getZ() + 30, false);
            mine.icon = 2;
            mine.colour = 4;
            MapServer.apply(server, player.getUUID().toString(), SMOKE, MapPayloads.ADD, mine);
            MapModApi.deathMarker(player, level, at.offset(-25, 0, -35), false);
            BlockPos b = at.offset(30, -3, -50), e = at.offset(-40, -3, 60);
            level.setBlock(b, Blocks.BEACON.defaultBlockState(), 3);
            level.setBlock(e, Blocks.ENCHANTING_TABLE.defaultBlockState(), 3);
            TestPois.OWNERS.put(e.immutable(), SMOKE);
            MapFound.scan(level, level.getChunkAt(b));
            MapFound.scan(level, level.getChunkAt(e));
            SMOKE_REPORT.report(source, "map set up round " + at.toShortString() + " for " + player.getGameProfile().name());
        }
        SMOKE_REPORT.report(source, "setup done.");
        return 1;
    }

    /**
     * The source's stamp, with the smoke group's player online: locking the ores takes them off the group's map within
     * a couple of seconds, and unlocking brings them back, with no call but the stamp moving.
     */
    private static int stamp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        ServerPlayer player = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!(p instanceof FakePlayer)) player = p;
        if (player == null) {
            SMOKE_REPORT.expect(source, "a player is online for the stamp check", false, "");
            SMOKE_REPORT.report(source, "stamp done.");
            return 0;
        }
        long region = MapGrid.regionOfBlock(player.getBlockX(), player.getBlockZ());
        int before = kinds(MapServer.poisFor(SMOKE, region), TestPois.ORE.key());
        TestPois.LOCKED.put(SMOKE, Set.of(TestPois.SUBS));
        LATER.when(level.getGameTime(), () -> kinds(MapServer.poisFor(SMOKE, region), TestPois.ORE.key()) == 0, 300, gone -> {
            SMOKE_REPORT.expect(source, "a source's stamp moving takes what the group may no longer see off its map", before > 0 && gone, before + " ores before");
            TestPois.LOCKED.remove(SMOKE);
            LATER.when(level.getGameTime(), () -> kinds(MapServer.poisFor(SMOKE, region), TestPois.ORE.key()) == before, 300, back -> {
                SMOKE_REPORT.expect(source, "and moving back brings it again", back, kinds(MapServer.poisFor(SMOKE, region), TestPois.ORE.key()) + " ores");
                SMOKE_REPORT.report(source, "stamp done.");
            });
        });
        return 1;
    }

    private static int lock(CommandContext<CommandSourceStack> context, boolean locked) {
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer) continue;
            if (locked) TestPois.UNLOCKS.put(player.getUUID(), MapFeature.TERRAIN.bit());
            else TestPois.UNLOCKS.remove(player.getUUID());
            MapModApi.unlocksChanged(player);
        }
        SMOKE_REPORT.report(context.getSource(), (locked ? "locked" : "unlocked") + ".");
        return 1;
    }

    private static int control(CommandContext<CommandSourceStack> context, Action action, String arg, float zoom) {
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            BlockPos at = player.blockPosition();
            if (!(player instanceof FakePlayer) && ServerPlayNetworking.canSend(player, Control.TYPE)) {
                ServerPlayNetworking.send(player, new Control(action, arg, at.getX(), at.getZ(), zoom));
            }
        }
        return 1;
    }
}
