package com.meakaandre.mapmod.api;

/** What an {@link UnlockProvider} can hold back from a player. The compass strip is always there. */
public enum MapFeature {
    /** Terrain tiles: without it the map shows only fog and what stands on it. */
    TERRAIN,
    /** The minimap. MapMod no longer draws one; kept so unlock providers that name it still work. */
    MINIMAP,
    /** The fullscreen map (M). */
    FULLSCREEN;

    public int bit() {
        return 1 << ordinal();
    }

    /** Every feature: what the default provider gives. */
    public static final int ALL = (1 << values().length) - 1;
}
