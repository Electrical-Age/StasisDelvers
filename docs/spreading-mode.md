# Spreading mode

Paths are relative to `src/main/java/org/erisia/stasis/`. Design: `notes/timestop/spread-DESIGN.md` (with Argon's
review and Baughn's decisions of 2026-10-19). Step 1 is in: the server core (1a) and the client sync, shader and
drifting deeps over running land (1b); front warnings, the stronghold desk and hard mode's effects come later.

## What it is
- Config `mode = "spreading"` (default `"stopped"`, the base mod). The world starts running; the stillness spreads
  from the rifts over `spreadHours` of online time to `spreadCap` (80 %) of the land within `spreadRadius` of spawn.
  Hard mode (for now only `/stasis spread hardmode`) takes it on to 100 % over `hardSpreadHours`.
- At 100 % the spread is **complete**: everything behaves as the stopped mode, spawn included (Baughn: on purpose).
- First join: one stability compass and nothing else (`StarterKit`, persisted flag `stasisdelvers_starter_compass`).
- Off in spreading mode: the spawn cottage (not marked done), the starter suit and flashlight, storms
  (`StasisConfig.storms` is false: storms come with hard mode, after the storm clock's origin moves, or tau jumps),
  and the stronghold portal room's gate rift (the room stays intact; hard mode will need it).
- The Nether and the End run until the spread is complete (`Stasis.stops` is false there), then follow the base rules.
- Drifting deeps (Baughn, 2026-10-19): a drifting deep stops time on running land it passes over wherever no field
  (or the spawn area) holds it, on both sides: `rateAt` and `rateAtSteady` are 0 there (block ticks, entities, EA
  nodes stop), at the threshold Freezing uses (`Deeps.at > Deeps.THRESHOLD` = 0.5). `rateAtLand` (flame light,
  meshing, map overlays) ignores them. Static deeps' outer zones don't stop running land; their rifts do.
- Players in such a deep: pending Baughn's answer, `Freezing.SPREAD_DEEP_HOLDS` (true) gives an unsuited player the
  Delver hold (no statue, released when the deep passes, at most 2400 ticks, no suit messages); false gives the
  normal freeze to a statue. Suited players drain at the deep rate (x2) as in stilled land.

## Classes
- `SpreadMap.java`: the map, pure (no Minecraft state). `build(seed, spawnX, spawnZ, radius, connectedUntil)`,
  algorithm 2, shape v2 "rising water" (Baughn 2026-10-19: v1's stilled regions were too scattered for bases):
  - 32-block cells (cell i covers blocks 32i..32i+31, global). Why 32: open corridors are at least 32 blocks wide
    (Argon), a quarter of the cells of a chunk grid, and every chunk lies in one bilinear patch per axis.
  - S per cell (`stability`): a smooth stability terrain. 2-octave fbm hills on a 1400-block lattice over a warped
    domain (features of 1-2 km), small detail for ragged shores, minus a dip round each static deep's rift (0.35 at its
    edge, gone 200 blocks out: the stillness starts at the rifts), plus a Gaussian raise round spawn (0.25, 800 blocks).
  - split = floor(`spreadConnectedUntil` x N) (config, default 0.5). The N - split cells of highest S take ranks
    split..N-1 in S order: the water rises over the terrain, the islands shrink to their high points, which still last.
  - The other cells take ranks 0..split-1 in the order of a 4-connected priority flood from spawn's cell over all
    cells (the frontier cell of highest S first, v1's growth), keeping only the low cells' order (the later reached, the
    lower). Every low cell was reached from a cell taken before it (high, or low of higher rank), so below the split
    every running low cell is connected to spawn through running cells; high ground may be a running island.
    Above the split nothing is promised (Baughn: EA teleporters, cheap suits). Spawn's cell runs until the split.
  - Sort keys are S exactly (float bits made sortable), then the cell index: deterministic, no ties.
  - Cells outside the radius are -1 (stilled from the start).
  - `nearestAtLeast(map, x, z, minRank, rings)`: the nearest cell centre at or above a rank, ring by ring (the compass).
  - `rank1024(SpreadRanks, x, z)`: **the** lookup, bilinear between cell centres (32i+16) in integer arithmetic.
    A column is stilled iff it is below `P * SCALE` (`stilled`). `chunkMin1024`/`chunkMax1024` are exact.
  - `stilledSplit`: `stilled` in 32-bit integers (high and low 16 bits of r - P), the shader's arithmetic.
- `SpreadRanks.java`: cell ranks by global cell index. The server's is the `SpreadMap`; the client's a `SpreadTiles`.
- `SpreadTiles.java`: the client's map, 32x32-cell tiles (1024 blocks square) in a `ConcurrentHashMap` (mesh workers
  read it). A cell no tile covers is `MISSING` (`Integer.MAX_VALUE`): it runs at every P. `cut` (server),
  `tilesFor(x0, x1)`: the tiles block columns x0..x1 read (cell floor((x - 16) / 32) and the next).
- `SpreadSync.java` (server, main thread): the state to every player when it changes (P at most once a second),
  with `reset` on login, respawn, dimension change and a new map; the tiles round each overworld player out to
  `range` = (view distance + 1) x 16 + 32 blocks, nearest first, 4 a tick; `SpreadForget` for tiles more than one
  tile beyond. Payloads in networking.md.
- `Spread.java`: run-time state in static volatiles (`server`, `client`: map, N, P, hard, complete).
  - `active(level)`: spreading, overworld, not complete. `runs(level, x, z)`, `chunk(level, cx, cz)` (1/0/2),
    `holdsOff(level)` (the Nether and the End).
  - Clock: `tick` adds real seconds while a player is on, at most 1 s per tick (`tickSeconds`). `coverage`:
    `min(cap, cap t / H)`; in hard mode `cap + (1 - cap) min(1, (t - tHard) / H_hard)`. `progress` = floor(c N).
  - `update`: on a change of P relights flames in loaded chunks the front crossed and tells `SpreadSync`; on
    completion invalidates every level and gives safe players grace (`Freezing.graceIfStopped`). It doesn't bump
    `Stasis.version`: nothing on the server caches a P-dependent answer (the field index is fields only,
    `StaticDeeps`' section cache is geometry, and the version is read by client meshing only).
  - Client: `clientState`, `clientTile`, `clientForget` (StasisNet handlers) fill `client` (map: `clientTiles`) and
    bump `Stasis.version` (the mesh workers' per-section cache); `clientListener` (`FrozenSprites.SPREAD`) hears
    which rank range P crossed and which tiles came; `clientClear` on disconnect.
  - Life cycle: `levelLoad` (overworld) loads a saved map before chunks ask; `serverStarted` builds it on the
    world's first start in spreading mode (spawn pinned then; ~40 ms at 8 km, 0.6 s at the 30 km maximum);
    until then the overworld runs. `serverStopped` clears the state.
  - Test hooks (ship in the jar): `testInstall(map, p)`, `testReset()`.
- `Stasis.rateAt`: right after "not a stopped dimension", a running column returns 1 whatever the fields (0 in a
  rift, 0 in a drifting deep no field holds); stilled columns go on to the base rules. `sectionState` and
  `mayRunInColumn` have the same branch (by the land: no deeps). `Stasis.spreadRuns(level, x, z)` asks the column.
  Everything else (Freezing, NodeFreezer, Flames, spawns, crystals, sleep, liquids) goes through these.
- Client consumers that looked at fields rather than the rate now ask `spreadRuns` first: the sky clock
  (`LocalTime.Client.time`: the world's clock on running land, the grey's in a deep), beds (`FieldSleep`: vanilla's
  night, and no field clock skip, on running land), `AntiSound` (no failing-field sag there). Weather, particles,
  sounds, frozen entities and flames go through `rateAt` and so follow the client's tiles. A field's shield still
  keeps rain out on running land (`underShield` is the dome's shape, not a time test).
- `StasisCommands`: `/stasis spread status | set <coverage> | hardmode` (op).
- `StabilityCompassItem.java` (`stasisdelvers:stability_compass`): points at the nearest place of temporal stability
  (rank / N, the coverage at which it stills) >= 90 %; past 90 % (hard mode) at rank >= P, so never into stilled land.
  The server searches (`nearestAtLeast`, cached per player until the player's cell or the threshold changes, asked
  every 20 ticks from `inventoryTick`) and stores an untracked `LODESTONE_TRACKER` target: the vanilla needle draws it,
  as the rift compass's. It spins outside spreading mode, off the overworld, when complete, or with no target. Use:
  "Temporal stability here: NN %" on the action bar. Recipe: a compass with amethyst shards N, E, S, W. Model: the
  vanilla compass models with a constant pale-amethyst tint (`items/stability_compass.json`).

## Persistent state
- Overworld SavedData `stasisdelvers:spread_map` (`algorithm`, `spawn_x`, `spawn_z`, `radius`, `ranks` int array),
  written once. A saved map is never rebuilt, whatever its algorithm: a world made with v1 keeps its v1 map (logged at
  load). A missing `algorithm` reads as 1 (DFU drops fields equal to their default, so v1 saves lack it). Delete
  `spread_map.dat` to rebuild with the current algorithm (the front then moves under existing bases); `stasisdelvers:spread_clock` (`seconds`, `hard`, `hard_at`).

## Client / server split
- The client answers every column within its tiles exactly as the server (the same `rank1024` on the same integers),
  so stopped-water collision, frozen textures and entity freezing agree. Before the state arrives, while the server
  has no map, and for cells no tile covers, the client says "running".
- Drifting deeps are computed on both sides from the deep seed and game time (as in stopped mode); the client's game
  time can be a tick or so off, so at a moving deep's edge the two may disagree for a moment.
- Re-meshing: `FrozenSprites.spreadMoved` (at most once a second) re-meshes and relights the loaded chunks whose
  [`chunkMin1024`, `chunkMax1024`] the new P crossed, and those a new tile covers that aren't wholly running. Near a
  player that is a handful of chunks a minute.
- The post shader: a 64x64-cell rank window and P in data slots 196-199, the same test in 32-bit integers
  (`stilledSplit`), and the drifting-deep test at block centres (client-rendering.md).
- Xaero's overlays use `rateAtLand`; the world map refreshes as P moves by 1/2000 of the map.

## Invariants and tests
- `SpreadMapTest`: ranks are a permutation of 0..N-1 (any split); build is deterministic; the ranks from the split up
  follow S and every low cell is at most as stable as the least high one; below the split, BFS from spawn's cell
  reaches every running low cell, and block by block (the bilinear) every running low cell's centre column; the compass
  search equals brute force; chunk extremes are exact; coverage formula, monotone, capped tick.
  `coherenceReport` logs, at 20/50/80/90/95 %, the running cells' components, those of >= 100 cells (~320 x 320
  blocks) and their share, the largest, and the shore share (running cells next to stilled land); it dumps the lab
  seed's map to `build/spread-lab-v2.ranks` for `tools/spread-render.py` (renders: `notes/spread-renders/v2-*.png`).
  Lab seed 3201319, 8 km: at 50 % 38 components, 11 big holding 99.6 %; at 80 % 38, 20 big holding 98.9 %, the
  largest 29 %; at 95 % 32, 17 big holding 97 %.
- `SpreadTilesTest`: tile lookup = map lookup on random maps (through the codec), uncovered cells run,
  `stilledSplit` = the exact sum at every offset and at P on each boundary. `NetCodecTest`: the three payloads.
- GameTests (isolated): `spread_rates` (at P = N/3, below the split); `stability_compass_target` (the target is the
  nearest cell >= 90 %, it spins in stopped mode); `stability_compass_first_join` (one compass, nothing else, not again); `spread_deep_stops_running_land` (rate 0 and no block tick in a drifting
  deep over running land, the land's rate 1, a field holds it off, an unsuited player is held, not a statue);
  `spread_tiles_follow_player` (the server's tile list follows the player).

## Gotchas
- A running column's rift still stops (Baughn). Below the split a corridor could pass a rift's column; rifts still early
  (S dips there), and they are 3D, so there is usually a way round, but nothing guarantees it.
- Above the split (default 50 %) running regions can be cut off from spawn and from each other, on purpose.
- Spawn's own region can still at the split: the spawn raise is mild (on the lab seed spawn sits in a low area).
- Entering hard mode before the coverage reached the cap jumps it to the cap at once (the formula as designed).
- Changing `spreadRadius` or `spreadConnectedUntil` after the map is built changes nothing; delete `spread_map` to
  rebuild.
- `Deeps.at` is cached per tick by 256-block region (from `within`, a superset of `near`): it is asked per block in
  running land now. Off the server's main thread the deep test is skipped (it reads a heightmap); off-thread callers
  use `rateAtLand` anyway.
