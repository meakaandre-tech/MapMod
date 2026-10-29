package com.meakaandre.mapmod.selftest;

import net.fabricmc.api.ModInitializer;

/** The selftest mod: the test POI provider (through the public API only), the selftests and the smoke run's waits. */
public final class SelfTests implements ModInitializer {
    @Override
    public void onInitialize() {
        TestPois.register();
        MapTests.register();
        SmokeWaits.register();
    }
}
