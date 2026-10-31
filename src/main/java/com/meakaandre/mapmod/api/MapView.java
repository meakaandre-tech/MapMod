package com.meakaandre.mapmod.api;

/**
 * What the fullscreen map shows this frame, for a {@link MapOverlay} to draw over: the world rectangle under the map's
 * area of the screen, in GUI pixels (the units {@code GuiGraphicsExtractor} draws in). World x runs right and world z
 * down, as on the map. Only valid during the draw call it was given to.
 */
public interface MapView {
    /** Screen x (GUI pixels) of a world x. */
    double screenX(double worldX);

    /** Screen y (GUI pixels) of a world z. */
    double screenY(double worldZ);

    /** World x under a screen x. */
    double worldX(double screenX);

    /** World z under a screen y. */
    double worldZ(double screenY);

    /** GUI pixels a block. */
    double scale();

    /** The world x and z at the middle of the map's area. */
    double centreX();

    double centreZ();

    /** The map's area on screen, in GUI pixels; drawing is clipped to it. */
    int left();

    int top();

    int width();

    int height();

    /** True if a screen point lies inside the map's area, or within {@code margin} pixels of it. */
    default boolean visible(double screenX, double screenY, int margin) {
        return screenX >= left() - margin && screenX < left() + width() + margin && screenY >= top() - margin && screenY < top() + height() + margin;
    }
}
