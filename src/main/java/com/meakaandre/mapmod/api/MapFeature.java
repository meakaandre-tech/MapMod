package com.meakaandre.mapmod.api;

/**
 * What an {@link UnlockProvider} can hold back from a player, server-driven and sent to their client. The compass strip
 * is always there. (A lock a client-side mod drives itself, with no server involved, is {@link MapLock}.)
 */
public enum MapFeature {
    /** The painted terrain: without it the fullscreen map shows only fog and what stands on it. */
    TERRAIN,
    /** The fullscreen map (M). */
    FULLSCREEN;

    /** This feature's bit in a mask of features. */
    public int bit() {
        return 1 << ordinal();
    }

    /** Every feature: what the default provider gives. */
    public static final int ALL = (1 << values().length) - 1;
}
