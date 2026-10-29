package com.meakaandre.mapmod.selftest.client;

import net.fabricmc.api.ClientModInitializer;

/** The selftest mod's client side: the smoke run's remote control and the checks only the client can make. */
public final class SelfTestsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MapClientTests.register();
        ReadyClientTests.register();
    }
}
