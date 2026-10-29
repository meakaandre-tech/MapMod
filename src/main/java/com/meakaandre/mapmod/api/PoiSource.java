package com.meakaandre.mapmod.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * POIs worked out rather than found: what the seed puts in a region (resource nodes, crash sites) as far as a group may
 * know it. The map asks for a region once a group has explored any of it, caches the answer per group and sends the
 * group only the POIs in chunks it has explored.
 *
 * @param <S> what {@link #prepare} reads on the server thread for the worker (a group's unlocks, say)
 */
public interface PoiSource<S> {
    /** On the server thread, once per batch of regions: everything about the group the worker needs. */
    S prepare(ServerLevel level, String group);

    /** On {@link #executor()}: the POIs in the area for a group, from what {@link #prepare} read. */
    List<MapPoi> pois(ServerLevel level, PoiArea area, S prepared);

    /**
     * On the server thread, once a second for each group with a player online: a number that changes whenever what the
     * group may see changes (unlocks, things collected). A change works the group's POIs out again.
     * {@link MapModApi#refreshPois} does the same at once.
     */
    default int stamp(MinecraftServer server, String group) {
        return 0;
    }

    /** False while the source cannot answer yet (its own data still loading); the regions wait. */
    default boolean ready(ServerLevel level) {
        return true;
    }

    /** Where {@link #pois} runs; the map's own worker thread unless the source needs its own. */
    default Executor executor() {
        return MapModApi.worker();
    }
}
