package com.meakaandre.mapmod.client;

import com.meakaandre.mapmod.client.map.MapClient;
import net.fabricmc.api.ClientModInitializer;

/** The map on the client: the compass strip, the minimap, the fullscreen map and the keys (M and N, under "Map"). */
public class MapModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MapClient.register();
    }
}
