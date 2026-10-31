/**
 * MapMod's public API: everything another mod may use, and nothing else. Every class outside this package is internal
 * and may change in any release.
 * <h2>Server side, and the registry both sides share</h2>
 * {@link com.meakaandre.mapmod.api.MapModApi}: sharing groups ({@link com.meakaandre.mapmod.api.GroupProvider}),
 * server-driven unlocks ({@link com.meakaandre.mapmod.api.UnlockProvider}, {@link com.meakaandre.mapmod.api.MapFeature}),
 * POI categories and kinds ({@link com.meakaandre.mapmod.api.PoiKind}, {@link com.meakaandre.mapmod.api.MapGlyphs}), POI
 * sources, chunk scanners, moving things, reveals, death markers.
 * <h2>Client side</h2>
 * <ul>
 * <li>{@link com.meakaandre.mapmod.api.MapOverlay}: layers of your own drawn over the painted map, through a
 * {@link com.meakaandre.mapmod.api.MapView}, each with a switch in the top bar.</li>
 * <li>{@link com.meakaandre.mapmod.api.MapMarkers}: markers your mod places, moves and removes.</li>
 * <li>{@link com.meakaandre.mapmod.api.MapPalette}: the painted map's colours, overridden from code.</li>
 * <li>{@link com.meakaandre.mapmod.api.MapLock}: hiding the map, or layers of it, until your mod says.</li>
 * <li>{@link com.meakaandre.mapmod.api.MapSurface}: the surface classes, and which class a block or a tag is.</li>
 * <li>{@link com.meakaandre.mapmod.api.MapTiles}: hearing when the captured map changes.</li>
 * </ul>
 * MapMod adds no networking for the client side: a mod that bases a lock, a marker or an overlay on server state syncs
 * that state itself.
 */
package com.meakaandre.mapmod.api;
