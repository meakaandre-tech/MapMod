# MapMod

A Satisfactory-style map for Fabric 26.3, for any modpack (working name; the real one comes later and lives in
`gradle.properties` as `mod_name`, read back in code as `MapMod.NAME`):

- a **compass strip** across the top of the screen, with pins and their distance, group players, markers and nearby POIs;
- a **fullscreen map** (M) in the style of Xaero's World Map: one pixel per block in each block's real colour, shaded
  by height, water showing the ground under it, crisp blocks close up and clean averages far out, unexplored land a
  flat dark background, with a **filter panel**, **pins**, **markers** (an icon, a colour and a name, private or shared
  with your group), **death markers** and the mob radar on top, and a switch per overlay layer in its top bar. There is
  no minimap;
- **waypoints** (B): one key opens "New waypoint", filled in with where you stand in whatever dimension you are in (on
  the open map: where the mouse points, Y the captured ground's there): a name ("Waypoint 1", "Waypoint 2"..., selected
  so typing replaces it), X, Y and Z, a row of colours (the next in the cycle picked), "Pin to compass" (on), Save and
  Cancel (Enter and Esc). Saved, it is a private marker like any other, pinned to the compass strip if ticked, with a
  short note in the top right corner ("Waypoint added (Waypoint N)"). Right click one on the map to rename it, change
  its colour, unpin or remove it. While a mod locks the map or its markers, B opens nothing and says "Map locked".

Terrain is captured by the client itself (see below), so it works on any server and with any world generator. Only the
Overworld's surface is mapped. Vanilla's locator bar is switched
off (the compass shows your group's players instead). Needs Fabric API; works next to
[Quicksilver](https://github.com/meakaandre-tech/Quicksilver).

On a server that doesn't have MapMod, the client keeps the map by itself: it clears its
own fog and keeps its own markers, death markers and pins, all of it open (there are no POIs, mates or moving things
then, which only a server can supply), in `mapmod/server-<address>/` in the game folder.

Saves: `data/mapmod/` in the world (`fog/`, `id.txt`, `markers.dat`, and `terrain/` for what mods' chunk hooks found;
the server keeps no terrain of its own); client tiles, pins and settings: `mapmod/` in the game folder. Keys (open the
map, add a waypoint) are under **MapMod** in the controls.

## Surface capture and the map (client)

The client captures the surface itself, on any server and with any world generator: every chunk it loads, and every
chunk whose surface changes, is sampled at every block on a background thread (MM-9; every 2 blocks before): the
ground's height, the water depth over it, the colour seen from above, how far above the ground the top that shows is
(a tree's crown), one of eight surface classes from block tags then map colour, the biome tint as RGB565, and the
water's own colour. The colour is each block's real one: the average colour of its top face's texture, as the loaded
resource packs give it (any mod's blocks too, read off their models), times its tint (the biome's grass, foliage or
water colour); leaves, mushroom caps and snow layers over the ground show, and under water the floor does, with the
water's colour kept apart. It is worked out once per block state and again after every resource reload, when the loaded
chunks are sampled again. Samples go into 512x512-block tiles, zstd-compressed, in
`mapmod/<server address or world folder>/<dimension>/<x>_<z>.mmt` in the game folder; the layout is documented in
`SurfaceTile`. zstd is [aircompressor](https://github.com/airlift/aircompressor)'s v3 line, bundled: it calls the zstd
library it carries for Linux (x64, arm64, ppc64le) and macOS (arm64) through `java.lang.foreign`, and its pure-Java codec
on other platforms (Windows among them), which still uses `sun.misc.Unsafe`. Both read and write the same files.

The fullscreen map (MM-9) looks like Xaero's World Map, recreated from the technique alone (no code, textures or
assets of Xaero's, which are All Rights Reserved). It reads the saved tiles it shows on a background thread (and takes
newer ones from the capture), works out each tile's picture on another, one texel per block, and lets the tiles' data
go when it closes:

- each block in its real colour (or its class's, where the palette overrides it);
- **height shading**: each block against its north and west neighbours (read across tile borders, so tiles meet without
  a seam), higher lighter and lower darker, in five clear steps; on land the height is the top that shows, so trees
  stand up from the ground and coasts are lit against the water's surface;
- **water** shows the ground under it, less and darker the deeper it is, in its biome's water colour;
- **unexplored land** is one flat dark colour, with the hard edges of the chunks captured: what shows is everything this
  client has captured, on any server and in any group (the group's fog of war gates POIs, never terrain).

Pictures are uploaded one or two a frame and copied texel for texel into baked tiles, with a mip pyramid over them
(each level the exact mean of the one below; unexplored land averages in as background), all within a GPU budget
(`cache_mb` in `mapmod/settings.json`). Close up the tiles are drawn nearest, so blocks are crisp squares; zoomed out
each pixel is the average of what it covers, read from the mip level nearest a pixel, so the picture does not shimmer
as it moves. One shader does all of it (`assets/mapmod/shaders/core/map_quad.*`). A change of zoom level cross-fades
over about 150 ms; a drag let go while moving glides on briefly (any click stops it). Icons shrink as the map zooms
out, and mobs of one kind (as mob faces), markers, pins and moving things (boats, vehicles) of one kind that would
overlap are drawn once with a count. The top bar has a switch per overlay layer (players, markers, pins, POIs, mob
radar), kept in `mapmod/settings.json` as `layers`. Going to another dimension frees the GPU cache at once.

Resource packs restyle it through `assets/mapmod/map_palette.json`: optional class colour overrides `classes` 0-7 (none
by default, each replacing the real colours of its class, tinted by the biome as much as its `tint` says), `shading`
(the height shading's strength, 0 to 2), `water` (`opacity`: how much of the water's colour lies over the ground a block
deep; `depth`: the depth at which the ground no longer shows; `darken`: how much darker the water is there) and
`background` (unexplored land). Every value is optional and falls back on its own; F3+T reloads it. A resource pack
that changes a block's texture changes its colour on the map as well. The painted look's keys from before MM-9
(`colour`, `paper`, `height_tint`, `snow`, `hillshade`, `edges`, `forest`, `contours`, `finish`, `fog` and the old
`water` keys) are ignored.

`/mapmod probe` shows what the map makes of the ground under you: the block, its surface class, its top face's texture,
that texture's average colour and its tint, the colour, tint and class the capture would store now, what is stored
(class, colour, tint, height, tile revision) and the colour the map gives it before shading. If the
stored sample differs from the ground now, the tile was not captured again after a change.

`/mapmod stats` shows tiles cached, dirty tiles, the worker queue, samples per second, and with the map open its render
time, uploads per frame, the off-thread preparation time per tile and the bake time per tile (prep, and the bake pass),
a tile's memory and the tile files' size on disk, mips made again from their parent after an eviction, and frames
cross-faded.

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
| **Overlay layers** drawn over the map, each with a top-bar switch (kept in `settings.json`'s `layers`); never baked | `MapOverlay.register(id, label, glyph, (graphics, MapView view) -> ...)`; `enabled`, `setEnabled`, `shown` |
| **Markers** (glyph, colour, optional label, per dimension; hover and pin like a player's) | `MapMarkers.add(new Marker(id, dimension, x, z, glyph, argb, label))`, `move(id, [dimension,] x, z)`, `remove(id)`, `removeAll(namespace)`, `get`, `in(dimension)`; cleared on leaving a world |
| **Palette overrides** over the resource packs' `map_palette.json`, kept across F3+T. Blocks show in their real colours, and a class colour is an optional override on top of them | `MapPalette.classColour(classId, rgb)` (shows that class in it instead of its real colours), `classTint`, `colour(key, rgb)` (`background`), `number(key, value)` (`shading`, `water.opacity`, `water.depth`, `water.darken`), `edit()...apply()`, `clear(key)`, `clearAll()`; `keys()`, `isDeprecated(key)`, `version()` |
| **Locks**: the only way to hide things on purpose | `MapLock.lockMap(owner, reason)` / `unlockMap(owner)`: a "map locked" panel instead of the map; `lockLayer(owner, layer)` / `unlockLayer` for `PLAYERS`, `MARKERS`, `PINS`, `POIS`, `MOB_RADAR` or an overlay's id (hidden on the map and the compass); `unlockAll(owner)`. What the terrain shows does not change |
| **Surface classes** (ids 0-7 as `MapSurface.GRASS`...`LAVA`, fixed by the tile format; they decide which class colour override applies, not the colour) | `MapSurface.override(block or tag, classId)`, `clear`, `classOf(state)`: thread-safe, new samples only |
| **Tile events** | `MapTiles.addListener((tileX, tileZ, revision) -> ...)`: client thread, merged per tile, `long` revisions (ms, never going down); tiles are `MapTiles.BLOCKS` (512) blocks |

A layer of your own:

```java
MapOverlay.register(Identifier.fromNamespaceAndPath("mymod", "territory"), Component.literal("Territory"), MapGlyphs.RING,
    (g, view) -> {
        for (BlockPos p : myClaims()) {
            int sx = (int) view.screenX(p.getX()), sy = (int) view.screenY(p.getZ()), r = (int) Math.max(2, 16 * view.scale());
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

Locked until a research is done, then unlocked, with the POIs only later:

```java
Identifier me = Identifier.fromNamespaceAndPath("mymod", "research");
MapLock.lockMap(me, Component.literal("Research the map in the HUB"));
MapLock.lockLayer(me, MapLock.POIS);
// ... once the research is done:
MapLock.unlockMap(me);
```

Restyle from code, and put a mod's blocks in a class:

```java
MapPalette.edit().number("shading", 1.5f).number("water.depth", 24).colour("background", 0x101010).apply();
MapPalette.classColour(MapSurface.SNOW, 0xE8F0F4); // an override: all snow in one colour instead of its blocks' own
MapSurface.override(MyBlocks.ASH, MapSurface.DIRT);
MapSurface.override(MyTags.SALT_FLATS, MapSurface.SNOW);
```

Group ids are free text and are used in file names with anything but `A-Za-z0-9_-` replaced.

### Deprecated in MM-9 (the painted look is gone)

The map changed from the painted look to one pixel per block (Xaero-style). Nothing in the API changed its signature;
what no longer means anything is deprecated and kept compiling:

- `MapOverlay.warpX` / `warpZ` always return 0 and `WARP_PERIOD` is unused: the map shows every block where it is, so
  overlays need no offset (code that subtracts the warp still lines up).
- `MapLock.CONTOURS`: there are no contour lines; locking it is accepted and does nothing.
- The painted look's palette keys (`paper`, `hillshade`, `edges.*`, `forest.*`, `contours.*`, `finish.*`, `fog.*`,
  `colour.*`, `height_tint.*`, `snow.*`, `water.shallow`, `water.deep`, `water.deep_at`, `water.deep_dist`,
  `water.shore`, `water.shore_width`, `water.tint`) are no longer in `MapPalette.keys()`; setting or clearing one is
  accepted, logged once and has no effect (`MapPalette.isDeprecated(key)`). The new keys are `shading`,
  `water.opacity`, `water.depth`, `water.darken` and `background`; `classes.<id>.colour` and `.tint` are unchanged.
- `MapSurface` overrides still work: a block's class picks which class colour override applies, so they change the map's
  colours together with `MapPalette.classColour`.
- The contours switch is gone from the map's top bar, and `contours` from `settings.json`.
- Tiles are format 3 (a sample a block): tiles saved before MM-9 are dropped and the land captured again.

## Building and CI

`./gradlew build` makes `build/libs/mapmod-<version>+26.3.jar`, the API's Javadoc, and the CI-only
`mapmod-selftest-*.jar` (the `/mapmod selftest` and `/mapmod worldtest` suites, a test POI provider and a client test
mod, `ApiClientTests`, that both use only the public API; the build fails if the latter imports anything else). Every push to
`main` builds, runs the server and client selftests (with screenshots of the compass, the fullscreen map at three zooms,
filters and a marker), restarts the server on the saved world, pushes the logs to the `ci-logs` branch, and publishes
`mapmod-26.3.jar` as the `latest` release.

## Licence

LGPL-3.0-only ([LICENSE](LICENSE), with the GPL-3.0 text it builds on in [COPYING](COPYING)).
