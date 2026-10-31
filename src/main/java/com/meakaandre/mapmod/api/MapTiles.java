package com.meakaandre.mapmod.api;

import com.meakaandre.mapmod.client.capture.Capture;

/**
 * Hears when the client's map changes: the capture takes the ground every 4 blocks into tiles of {@link #BLOCKS} by
 * {@link #BLOCKS} blocks (tile x is {@code floorDiv(blockX, BLOCKS)}), and a listener is told each time a tile it holds
 * changed, in the dimension the player is in. Useful to work something out again where the land changed.
 */
public final class MapTiles {
    /** Blocks along a tile's side. */
    public static final int BLOCKS = 512;

    /**
     * Told that a tile changed: its position and its new revision. Called on the client thread, at the end of a client
     * tick (or as the map draws). Changes are merged per tile, so a listener sees each tile once with its latest revision
     * however often it changed in between. A revision is a time in milliseconds since the epoch and never goes down for
     * a tile: of two, the larger is the newer.
     */
    @FunctionalInterface
    public interface Listener {
        void changed(int tileX, int tileZ, long revision);
    }

    private MapTiles() {
    }

    /** Adds a listener; call from your client initializer. */
    public static void addListener(Listener listener) {
        Capture.onTileChanged(listener);
    }

    public static void removeListener(Listener listener) {
        Capture.removeTileListener(listener);
    }
}
