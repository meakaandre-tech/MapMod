# MapMod

A Satisfactory-style map for Fabric 26.3, for any modpack (working name; the real one comes later and lives in
`gradle.properties` as `mod_name`, read back in code as `MapMod.NAME`):

- a **compass strip** across the top of the screen, with pins and their distance, group players, markers and nearby POIs;
- a **painted fullscreen map** (M): smooth shaded terrain at any zoom, no blocks and no pixels, unexplored land fading
  into soft, cloudy fog, with a **filter panel**, **pins**, **markers** (an icon, a colour and a name, private or shared
  with your group), **death markers** and the mob radar on top, and a switch per overlay layer in its top bar. There is
  no minimap.

Terrain is captured by the client itself (see below), so it works on any server and with any world generator. Only the
Overworld's surface is mapped. Vanilla's locator bar is switched
off (the compass shows your group's players instead). Needs Fabric API; works next to
[Quicksilver](https://github.com/meakaandre-tech/Quicksilver).

On a server that doesn't have MapMod, the client keeps the map by itself: it clears its
own fog and keeps its own markers, death markers and pins, all of it open (there are no POIs, mates or moving things
then, which only a server can supply), in `mapmod/server-<address>/` in the game folder.

Saves: `data/mapmod/` in the world (`fog/`, `id.txt`, `markers.dat`, and `terrain/` for what mods' chunk hooks found;
the server keeps no terrain of its own); client tiles, pins and settings: `mapmod/` in the game folder. Keys are under
**Map** in the controls.

## Surface capture and the painted map (client)

The client captures the surface itself, on any server and with any world generator: every chunk it loads, and every chunk whose surface changes, is sampled every 4 blocks on a
background thread (height, water depth, one of eight surface classes from block tags then map colour, biome tint as
RGB565, canopy density). Samples go into 512x512-block tiles, zstd-compressed, in
`mapmod/<server address or world folder>/<dimension>/<x>_<z>.mmt` in the game folder; the layout is documented in
`SurfaceTile`. zstd is [aircompressor](https://github.com/airlift/aircompressor)'s v3 line, bundled: it calls the zstd
library it carries for Linux (x64, arm64, ppc64le) and macOS (arm64) through `java.lang.foreign`, and its pure-Java codec
on other platforms (Windows among them), which still uses `sun.misc.Unsafe`. Both read and write the same files.

The fullscreen map reads the saved tiles it shows on a background thread (and takes newer ones from the capture), works
out each tile's textures on another (class weights blurred five samples wide, a signed distance to the shore, canopy),
lets them go when it closes, uploads one or two a frame to the GPU and paints them with one fragment shader per tile
(`assets/mapmod/shaders/core/map_paint.*`): bicubic height, a hillshade lit from the northwest, soft class borders that
wander with low-frequency noise in world space (no straight runs, no chunk edges, the same across tiles and at any
zoom), one muted colour per surface class tinted by the biome, darker lowlands and paler peaks, snow above a height,
forests darkened by canopy with a soft brush, water deep to shallow by depth and distance from the shore with a pale
band along it (coasts and rivers as smooth curves), optional contour lines (off by default; the switch is in the map's
top bar and kept in `mapmod/settings.json` as `contours`), and a light paper grain and vignette over it all.

Unexplored land fades into fog: what is shown is everything this client has captured, on any server and in any group
(the group's fog of war gates POIs, never terrain), so nothing you have seen is fogged but for the soft rim of the
fog's edge. It is blurred into a soft mask per cell off the render thread and wanders with the same world-space noise
as region edges, so there are no chunk corners or straight runs at any zoom, with a static cloud texture in it. The fog
goes on as the screen's pixels are written, never into the baked tiles. A change of zoom level cross-fades over about 150 ms; a drag let go while moving glides on briefly (any click
stops it). The top bar has a switch per overlay layer (players, markers, pins, POIs, mob radar), kept in
`mapmod/settings.json` as `layers`. Going to another dimension frees the GPU cache at once.

Resource packs restyle it through `assets/mapmod/map_palette.json` (colours per class 0-7, water,
shore band, paper, height tint, snow line, hillshade share, `edges`, `forest`, `contours`, `finish`, `fog`: colour,
edge width in blocks, cloud strength); every value is optional and falls back on its own; F3+T reloads it.

`/mapmod stats` shows tiles cached, dirty tiles, the worker queue, samples per second, and with the map open its render
time, uploads per frame and the off-thread preparation time per tile, the fog masks made and held, mips made again from
their parent after an eviction, and frames cross-faded.

## For other mods: `com.meakaandre.mapmod.api`

Everything another mod may use is in `com.meakaandre.mapmod.api`, with Javadoc (built by `./gradlew apiJavadoc` into
`build/docs/api`). **Everything outside that package is internal** and may change in any release; that includes the
tile format, `TilePrep`'s texture layout and every class under `client`, `map` and `mixin`.

With no other mod the map works on its own: every player is a group of their own, everything is unlocked, there are no
POIs, nothing is locked.

### Server side (and the registry both sides share): `MapModApi`

Register in your mod's initializer (categories, kinds and glyphs on both sides, identically).

| What | API |
| --- | --- |
| **Sharing groups**: who shares fog, shared markers and compass players | `setGroups(GroupProvider)` — `groupOf(server, uuid)`, optional `same`, `ignored`, `sharedLabel`; `groupChanged(player)`, `mergeGroups(server, from, into)` |
| **Unlocks**: per-player gates the server sends | `setUnlocks(UnlockProvider)` — `unlocked(player, MapFeature)` for `TERRAIN` and `FULLSCREEN`, optional `lockedMessage`; `unlocksChanged(player)` |
| **POI categories and kinds** | `category(id, label)`; `kind(id, category)` → `PoiKind.Builder` (`label`, `labeller`, `glyph`, `item`, `purity`, `filter`, `subFilters`, `sub(PoiSubType)`, `compassRange`, `compassNearest`, `dimColour`) `.register()` |
| **Glyphs** | `MapGlyphs` stock set, `MapGlyphs.register(rows...)` |
| **POIs worked out per group and region** | `addSource(id, PoiSource<S>)` — `prepare(level, group)` on the server thread, `pois(level, PoiArea, prepared)` on the worker, optional `stamp`, `ready`, `executor`; `refreshPois(server[, group])` |
| **Chunk hook**: things found in loaded chunks | `addChunkScanner(id, ChunkScanner)` returning `FoundPoi(poi, owner)`; `setChunkPois(level, scanner, cx, cz, list)`; `foundNear(kind, x, z, blocks)`, `foundIn(region, group)` |
| **Moving things** | `addMovingSource(MovingSource)` returning `MovingPoi` |
| **Reveal** | `reveal(level, group, centre, radiusBlocks)` clears a group's fog (POIs there show); `explored(group, cx, cz)` |
| **Death markers** | `deathMarker(player, level, pos, kept)`, `removeMarker(server, id)`, `skipDeathMarkers(predicate)` |

### Client side

Call these on the client thread; register overlays and listeners in your client initializer. MapMod adds no networking
for them: a mod that bases a lock, marker or overlay on server state syncs that state itself.

| What | API |
| --- | --- |
| **Overlay layers** drawn over the painted map, each with a top-bar switch (kept in `settings.json`'s `layers`); never baked, no grain or vignette | `MapOverlay.register(id, label, glyph, (graphics, MapView view) -> ...)`; `enabled`, `setEnabled`, `shown`; `warpX`/`warpZ` and `WARP_PERIOD` (8192 blocks) to follow the painted borders |
| **Markers** (glyph, colour, optional label, per dimension; hover and pin like a player's) | `MapMarkers.add(new Marker(id, dimension, x, z, glyph, argb, label))`, `move(id, [dimension,] x, z)`, `remove(id)`, `removeAll(namespace)`, `get`, `in(dimension)`; cleared on leaving a world |
| **Palette overrides** over the resource packs' `map_palette.json`, kept across F3+T | `MapPalette.classColour(classId, rgb)`, `classTint`, `colour(key, rgb)`, `number(key, value)`, `edit()...apply()`, `clear(key)`, `clearAll()`; `keys()`, `version()` |
| **Locks**: the only way to hide things on purpose | `MapLock.lockMap(owner, reason)` / `unlockMap(owner)`: a "map locked" panel instead of the map; `lockLayer(owner, layer)` / `unlockLayer` for `CONTOURS`, `PLAYERS`, `MARKERS`, `PINS`, `POIS`, `MOB_RADAR` or an overlay's id (hidden on the map and the compass); `unlockAll(owner)`. The fog rule does not change |
| **Surface classes** (ids 0-7 as `MapSurface.GRASS`...`LAVA`, fixed by the tile format) | `MapSurface.override(block or tag, classId)`, `clear`, `classOf(state)`: thread-safe, new samples only |
| **Tile events** | `MapTiles.addListener((tileX, tileZ, revision) -> ...)`: client thread, merged per tile, `long` revisions (ms, never going down); tiles are `MapTiles.BLOCKS` (512) blocks |

A layer of your own, following the painted coasts:

```java
MapOverlay.register(Identifier.fromNamespaceAndPath("mymod", "territory"), Component.literal("Territory"), MapGlyphs.RING,
    (g, view) -> {
        for (BlockPos p : myClaims()) {
            double x = p.getX() - MapOverlay.warpX(p.getX(), p.getZ()), z = p.getZ() - MapOverlay.warpZ(p.getX(), p.getZ());
            int sx = (int) view.screenX(x), sy = (int) view.screenY(z), r = (int) Math.max(2, 16 * view.scale());
            g.fill(sx - r, sy - r, sx + r, sy + r, 0x302080FF);
        }
    });
```

A vehicle, moved as your own packets say:

```java
Identifier truck = Identifier.fromNamespaceAndPath("mymod", "truck/" + id);
MapMarkers.add(new MapMarkers.Marker(truck, Level.OVERWORLD, x, z, MapGlyphs.TRUCK, 0xFFFFC060, Component.literal("Truck 1")));
MapMarkers.move(truck, newX, newZ);
MapMarkers.remove(truck);
```

Locked until a research is done, then unlocked, with the contours only later:

```java
Identifier me = Identifier.fromNamespaceAndPath("mymod", "research");
MapLock.lockMap(me, Component.literal("Research the map in the HUB"));
MapLock.lockLayer(me, MapLock.CONTOURS);
// ... once the research is done:
MapLock.unlockMap(me);
```

Restyle from code, and put a mod's blocks in a class:

```java
MapPalette.edit().classColour(MapSurface.GRASS, 0x9AB37E).colour("water.deep", 0x2E5F7E).number("edges.warp", 3).apply();
MapSurface.override(MyBlocks.ASH, MapSurface.DIRT);
MapSurface.override(MyTags.SALT_FLATS, MapSurface.SNOW);
```

Group ids are free text and are used in file names with anything but `A-Za-z0-9_-` replaced.

## Building and CI

`./gradlew build` makes `build/libs/mapmod-<version>+26.3.jar`, the API's Javadoc, and the CI-only
`mapmod-selftest-*.jar` (the `/mapmod selftest` and `/mapmod worldtest` suites, a test POI provider and a client test
mod, `ApiClientTests`, that both use only the public API; the build fails if the latter imports anything else). Every push to
`main` builds, runs the server and client selftests (with screenshots of the compass, the fullscreen map at three zooms,
filters and a marker), restarts the server on the saved world, pushes the logs to the `ci-logs` branch, and publishes
`mapmod-26.3.jar` as the `latest` release.

## Licence

LGPL-3.0-only ([LICENSE](LICENSE), with the GPL-3.0 text it builds on in [COPYING](COPYING)).
