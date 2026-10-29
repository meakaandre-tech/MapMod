package com.meakaandre.mapmod.client;

import com.meakaandre.mapmod.client.capture.Capture;
import com.meakaandre.mapmod.client.map.MapClient;
import net.fabricmc.api.ClientModInitializer;

/** The map on the client: surface capture into tiles, the compass strip, the fullscreen map and its keys. */
public class MapModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Capture.register();
        MapClient.register();
    }
}
