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

Saves: `data/mapmod/` in the world (`terrain/`, `fog/`, `id.txt`, `markers.dat`); client cache and settings: `mapmod/`
in the game folder (`mapmod/<map id>/` per world). Keys are under **Map** in the controls.

## Surface capture and the painted map (client) The client now captures the surface itself, on any server and with
any world generator: every chunk it loads, and every chunk whose surface changes, is sampled every 4 blocks on a
background thread (height, water depth, one of eight surface classes from block tags then map colour, biome tint as
RGB565, canopy density). Samples go into 512x512-block tiles, zstd-compressed, in
`mapmod/<server address or world folder>/<dimension>/<x>_<z>.mmt` in the game folder; the layout is documented in
`SurfaceTile`.

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
goes on as the screen's pixels are written, never into the baked tiles, so a change to what may show (MM-6's map locks)
changes a small mask and bakes nothing. A change of zoom level cross-fades over about 150 ms; a drag let go while moving glides on briefly (any click
stops it). The top bar has a switch per overlay layer (players, markers, pins, POIs, mob radar), kept in
`mapmod/settings.json` as `layers`. Going to another dimension frees the GPU cache at once.

Resource packs restyle it through `assets/mapmod/map_palette.json` (colours per class 0-7, water,
shore band, paper, height tint, snow line, hillshade share, `edges`, `forest`, `contours`, `finish`, `fog`: colour,
edge width in blocks, cloud strength); every value is optional and falls back on its own; F3+T reloads it.

`/mapmod stats` shows tiles cached, dirty tiles, the worker queue, samples per second, and with the map open its render
time, uploads per frame and the off-thread preparation time per tile, the fog masks made and held, mips made again from
their parent after an eviction, and frames cross-faded.

## For other mods: `com.meakaandre.mapmod.api`

With no other mod the map works on its own: every player is a group of their own, everything is unlocked, there are no
POIs. `MapModApi` is the one entry point; register in your mod's initializer (categories, kinds and glyphs on both
sides, identically).

| What | API |
| --- | --- |
| **Sharing groups**: who shares fog, shared markers and compass players | `setGroups(GroupProvider)` — `groupOf(server, uuid)`, optional `same`, `ignored`, `sharedLabel`; `groupChanged(player)`, `mergeGroups(server, from, into)` |
| **Unlocks**: per-player gates | `setUnlocks(UnlockProvider)` — `unlocked(player, MapFeature)` for `TERRAIN` and `FULLSCREEN` (`MINIMAP` is still there but gates nothing), optional `lockedMessage`; `unlocksChanged(player)` |
| **POI categories and kinds** | `category(id, label)`; `kind(id, category)` → `PoiKind.Builder` (`label`, `labeller`, `glyph`, `item`, `purity`, `filter`, `subFilters`, `sub(PoiSubType)` (a label, items to try in order, a glyph, a colour), `compassRange`, `compassNearest`, `detail`, `dimColour`) `.register()` |
| **Glyphs** | `MapGlyphs` stock set, `MapGlyphs.register(rows...)` |
| **POIs worked out per group and region** | `addSource(id, PoiSource<S>)` — `prepare(level, group)` on the server thread, `pois(level, PoiArea, prepared)` on the worker, optional `stamp`, `ready`, `executor`; `refreshPois(server[, group])` |
| **Chunk hook**: things found in recorded chunks | `addChunkScanner(id, ChunkScanner)` returning `FoundPoi(poi, owner)`; `setChunkPois(level, scanner, cx, cz, list)`; `foundNear(kind, x, z, blocks)`, `foundIn(region, group)` |
| **Moving things** | `addMovingSource(MovingSource)` returning `MovingPoi` |
| **Reveal** | `reveal(level, group, centre, radiusBlocks, guess)` (with the world generator's guess for unloaded land); `explored(group, cx, cz)` |
| **Markers** | `deathMarker(player, level, pos, kept)`, `removeMarker(server, id)`, `skipDeathMarkers(predicate)` |

Everything else (fog, tiles, caching, syncing, drawing, filters, pins) is the map's own. Group ids are free text and are
used in file names with anything but `A-Za-z0-9_-` replaced.

## Building and CI

`./gradlew build` makes `build/libs/mapmod-<version>+26.3.jar` and the CI-only `mapmod-selftest-*.jar` (the
`/mapmod selftest` and `/mapmod worldtest` suites and a test POI provider that uses only the public API). Every push to
`main` builds, runs the server and client selftests (with screenshots of the compass, the fullscreen map at three zooms,
filters and a marker), restarts the server on the saved world, pushes the logs to the `ci-logs` branch, and publishes
`mapmod-26.3.jar` as the `latest` release.

## Licence

LGPL-3.0-only ([LICENSE](LICENSE), with the GPL-3.0 text it builds on in [COPYING](COPYING)).
