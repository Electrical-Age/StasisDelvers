# Spreading mode

Paths are relative to `src/main/java/org/erisia/stasis/`. Design: `notes/timestop/spread-DESIGN.md` (with Argon's
review and Baughn's decisions of 2026-10-19). Step 1 (server core) is in; client sync, front warnings, the
stronghold desk and hard mode's effects come later.

## What it is
- Config `mode = "spreading"` (default `"stopped"`, the base mod). The world starts running; the stillness spreads
  from the rifts over `spreadHours` of online time to `spreadCap` (80 %) of the land within `spreadRadius` of spawn.
  Hard mode (for now only `/stasis spread hardmode`) takes it on to 100 % over `hardSpreadHours`.
- At 100 % the spread is **complete**: everything behaves as the stopped mode, spawn included (Baughn: on purpose).
- Off in spreading mode: the spawn cottage (not marked done), the starter suit and flashlight, storms
  (`StasisConfig.storms` is false: storms come with hard mode, after the storm clock's origin moves, or tau jumps),
  and the stronghold portal room's gate rift (the room stays intact; hard mode will need it).
- The Nether and the End run until the spread is complete (`Stasis.stops` is false there), then follow the base rules.

## Classes
- `SpreadMap.java`: the map, pure (no Minecraft state). `build(seed, spawnX, spawnZ, radius)`:
  - 32-block cells (cell i covers blocks 32i..32i+31, global). Why 32: open corridors are at least 32 blocks wide
    (Argon), a quarter of the cells of a chunk grid, and every chunk lies in one bilinear patch per axis.
  - A0 per cell: distance to the nearest static deep's rift (from a point warped by fbm: lobes), a slope up to spawn,
    a little fbm, and a cone within 600 blocks of spawn so the last land is a round patch around spawn.
  - Ranks by 4-connected region growing from the 2x2 cells round spawn, taking the frontier cell with the highest A0.
    Step k gets rank N-1-k. Cells outside the radius are -1 (stilled from the start).
  - `rank1024(SpreadRanks, x, z)`: **the** lookup, bilinear between cell centres (32i+16) in integer arithmetic.
    A column is stilled iff it is below `P * SCALE` (`stilled`). `chunkMin1024`/`chunkMax1024` are exact.
- `SpreadRanks.java`: cell ranks by global cell index. The server's is the `SpreadMap`; the client's will be a tile cache.
- `Spread.java`: run-time state in static volatiles (`server`, `client`: map, N, P, hard, complete).
  - `active(level)`: spreading, overworld, not complete. `runs(level, x, z)`, `chunk(level, cx, cz)` (1/0/2),
    `holdsOff(level)` (the Nether and the End).
  - Clock: `tick` adds real seconds while a player is on, at most 1 s per tick (`tickSeconds`). `coverage`:
    `min(cap, cap t / H)`; in hard mode `cap + (1 - cap) min(1, (t - tHard) / H_hard)`. `progress` = floor(c N).
  - `update`: on a change of P bumps `Stasis.version` and relights flames in loaded chunks the front crossed; on
    completion invalidates every level and gives safe players grace (`Freezing.graceIfStopped`).
  - Life cycle: `levelLoad` (overworld) loads a saved map before chunks ask; `serverStarted` builds it on the
    world's first start in spreading mode (spawn pinned then; ~40 ms at 8 km, 0.6 s at the 30 km maximum);
    until then the overworld runs. `serverStopped` clears the state.
  - Test hooks (ship in the jar): `testInstall(map, p)`, `testReset()`.
- `Stasis.rateAt`: right after "not a stopped dimension", a running column returns 1 (0 in a rift) whatever the
  fields; stilled columns go on to the base rules. `sectionState` and `mayRunInColumn` have the same branch.
  Everything else (Freezing, NodeFreezer, Flames, spawns, crystals, sleep, liquids) goes through these.
- `StasisCommands`: `/stasis spread status | set <coverage> | hardmode` (op).

## Persistent state
- Overworld SavedData `stasisdelvers:spread_map` (`algorithm`, `spawn_x`, `spawn_z`, `radius`, `ranks` int array),
  written once; `stasisdelvers:spread_clock` (`seconds`, `hard`, `hard_at`).

## Client / server split (step 2 to do)
- The client has no map and no P yet: `Spread.client` is empty, so in spreading mode the client treats the overworld
  as running (except rifts) and the Nether and the End as running. The server is right; the client disagrees in
  stilled land (stopped water, frozen textures, mist).
- The client step must send rank tiles + P (+ hard, complete), fill `Spread.client` with a `SpreadRanks` tile cache,
  answer through `SpreadMap.rank1024` exactly, mirror it in `stasis.fsh`, and re-mesh only the chunks whose
  [`chunkMin1024`, `chunkMax1024`] P crossed (today `Stasis.version` bumps on every P change, ~1/s).

## Invariants and tests
- `SpreadMapTest`: ranks are a permutation of 0..N-1; build is deterministic; every cell but the top one has a
  higher 4-neighbour (so the running cells are one piece with spawn at every P); block-sampled bilinear running set
  is one piece from the spawn column; chunk extremes are exact; coverage formula, monotone, capped tick.
  `pinchReport` logs corridor widths (a few roomy regions hang behind 5-8 block pinches; 1-block ones are rare and
  transient: every corridor narrows to nothing just before it stills).
- GameTest `spread_rates` (isolated).

## Gotchas
- A running column's rift still stops (Baughn). A corridor could pass a rift's column; rifts still early (low A0),
  and they are 3D, so there is usually a way round, but nothing guarantees it.
- Entering hard mode before the coverage reached the cap jumps it to the cap at once (the formula as designed).
- Changing `spreadRadius` after the map is built changes nothing; delete `spread_map` to rebuild.
