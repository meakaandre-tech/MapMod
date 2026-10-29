# MapMod

A Satisfactory-style map for Fabric 26.3, for any modpack (working name; the real one comes later and lives in
`gradle.properties` as `mod_name`, read back in code as `MapMod.NAME`):

- a **compass strip** across the top of the screen, with pins and their distance, group players, markers and nearby POIs;
- a **small square minimap** at the top right (N hides it);
- a **fullscreen map** (M) with soft biome colours, hill shading and contour lines, **fog of war**, a **filter panel**,
  **pins**, **markers** (an icon, a colour and a name, private or shared with your group) and **death markers**.

Terrain is recorded server-side at one pixel per 4 by 4 blocks when chunks load and after blocks change (no world
scanning), and cached on the client per world. Only the Overworld's surface is mapped. Vanilla's locator bar is switched
off (the compass shows your group's players instead). Needs Fabric API; works next to
[Quicksilver](https://github.com/meakaandre-tech/Quicksilver).

Saves: `data/mapmod/` in the world (`terrain/`, `fog/`, `id.txt`, `markers.dat`); client cache and settings: `mapmod/`
in the game folder (`mapmod/<map id>/` per world). Keys are under **Map** in the controls.

## For other mods: `com.meakaandre.mapmod.api`

With no other mod the map works on its own: every player is a group of their own, everything is unlocked, there are no
POIs. `MapModApi` is the one entry point; register in your mod's initializer (categories, kinds and glyphs on both
sides, identically).

| What | API |
| --- | --- |
| **Sharing groups**: who shares fog, shared markers and compass players | `setGroups(GroupProvider)` — `groupOf(server, uuid)`, optional `same`, `ignored`, `sharedLabel`; `groupChanged(player)`, `mergeGroups(server, from, into)` |
| **Unlocks**: per-player gates | `setUnlocks(UnlockProvider)` — `unlocked(player, MapFeature)` for `TERRAIN`, `MINIMAP`, `FULLSCREEN`, optional `lockedMessage`; `unlocksChanged(player)` |
| **POI categories and kinds** | `category(id, label)`; `kind(id, category)` → `PoiKind.Builder` (`label`, `labeller`, `glyph`, `item`, `purity`, `filter`, `subFilters`, `sub(PoiSubType)`, `compassRange`, `compassNearest`, `detail`, `dimColour`) `.register()` |
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
`main` builds, runs the server and client selftests (with screenshots of the compass, minimap, fullscreen map, filters, a
marker and the fog), restarts the server on the saved world, pushes the logs to the `ci-logs` branch, and publishes
`mapmod-26.3.jar` as the `latest` release.

## Licence

LGPL-3.0-only ([LICENSE](LICENSE), with the GPL-3.0 text it builds on in [COPYING](COPYING)).
