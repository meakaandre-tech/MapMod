package com.meakaandre.mapmod.client;

import com.meakaandre.mapmod.client.map.MapClient;
import net.fabricmc.api.ClientModInitializer;

/** The map on the client: the compass strip, waypoints in the world, the fullscreen map and its key (M, under "Map"). */
public class MapModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MapClient.register();
    }
}
