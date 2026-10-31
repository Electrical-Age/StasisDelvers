# Towers (mod side)

Paths are relative to `src/main/java/org/erisia/stasis/`. For the generator behind the tower mesh, see towergen.md.

## Classes
- `TowerBlock.java`: the core, 1x1x2 (`HEIGHT` = 2).
  - Blockstate `CELL`: `MASTER` 0 (bottom), `TOP` 1, `LOOSE` 2.
  - Placing the second block calls `form`. Breaking either block calls `unform`, which turns the rest loose
    and removes the field.
  - Every cell has a `TowerBlockEntity`; the energy capability resolves to the master's.
  - `getTicker` exists only on the server master.
- `TowerBlockEntity.java` (≈950 lines): the tower. It holds:
  - the power store, field lifecycle and state machine
  - safety and retreat, the console, feeding, EA signals, bolts, the remnant
  - test hooks: `testCharge`, `testWeather`, `testStorm`, `testSafety`, `testBolt`, `testDrawFactor`
- `TowerStructure.java`: survey poles to layout to generated tower (server side). Status
  `NONE / WAITING / BUILDING / OK / INVALID / REFUSED / FAILED`.
- `TowerPoleBlock.java` + `PoleItem.java`: scaffolding-like survey poles. Blockstate `PART`:
  - `SURVEY`: a plain pole
  - `LADDER`: the mast up to the ladder top (the drawn pole's socket, `TowerStructure.ladderTop`); outline = the box
    ladder model
  - `HIDDEN`: the generated tower is drawn in its place; its outline is what the mesh draws in its cell
    (`TowerPoleBlock.DRAWN`, filled on the client by `TowerRenderer` from `towergen/CellCover`, 1/8-block sub-cells;
    empty where nothing is drawn; the plain pole shape on the server or before the mesh lands)
- `Tickets.java`: forced chunks for the field's reach (NeoForge `TicketController` `stasisdelvers:tower`).
- `TowerSignalNode.java`: EA signal in/out (see ea-integration.md).
- `TowerCellBlockEntity.java`: a deprecated stub, kept only so old saves load.
- `TowerClientHooks.java`: hands block-entity updates and removals to `client/TowerRenderer.java`.
- Client: `client/ConsoleScreen.java` (polls `ConsoleQuery` every 10 ticks; OVERRIDE needs confirming within
  8 s), `client/BoltRenderer.java`, `client/Remnants.java` (frozen collapse remnants), `client/TowerRenderer.java`.

## State machine (`TowerBlockEntity.serverTick`, master only, runs even in stopped land)
- `OFF` → `RUNNING`: once `storedJ() >= 0.5 * storeMaxJ()`, `start` runs:
  - `FieldData.add` at `towerFieldRate` (default 0.6), then the stretch and `formedAt`
  - `Tickets.update`
  - the lock-on bolt and sound 60 ticks later
- `RUNNING` → `OFF`: the field was removed by a command (`FieldData.get(id)` is empty), so `stop` runs.
- `RUNNING` → `COLLAPSING`: the store reaches 0. A random `freezeAt` (0.5 - dropout), speed (10-25 s),
  side (the storm's side, else random) and a 24-bit `collapseSeed` are chosen. The field gets
  `withSeed(collapseSeed)`.
- `COLLAPSING` → `OFF`: progress reaches `freezeAt` (at most 25 s). `freeze` leaves a `Remnant` (radius,
  seed, collapse, dropout, side, 3 bolts, stretch), drawn frozen by the client, then calls `stop`.
- Unform or `setRemoved` → `stop`, except on a chunk unload (`removedByUnload` via `onChunkUnloaded`).
- Once a second (`% 20 == 10`) the radius steps toward `goalRadius()`: down by at least `STEP` = 2, up by
  1 or 1/8 of the gap.
- Safety (`safety`, once a second):
  - A retreat starts when `failing > 0.5`. The retreat radius is `carryRadius()` (the largest radius whose
    draw times weather is at most 0.9·input), or `minRadius()` as a last ditch when the store is under 15%.
  - The retreat ends when the store is at least 80% full.
  - `override` (the console's OVERRIDE) turns the safety off.

## Formulas
- `radiusFor(segments)` = 16·√(segments + 1) (Baughn 2026-10-30; 16, 23, 32, 39, 48, 64 at 0, 1, 3, 5, 8, 15 poles; it was 8·(segments + 2)). `designRadius` uses it.
  - `minRadius` = min(design, max(6, segments + 4)).
  - `maxRadius` = 1.25·design (`MAX_EXPAND`).
- `drawFor(r)` = `towerDrawK`·r^1.5 W.
  `drawAt(r)` = drawFor(r·`volumeScale()`)·`drawPenalty(r)`, where the penalty is (r/design)^6 above design.
- `storeJ()` = `towerStoreBatteries` (2, the recipe's) × one EA Cost Oriented Battery's energy (60 kJ by default, read
  from EA's descriptor), whatever the size (Baughn 2026-10-25: it had too much store, and resizing clipped energy). FE = J·`FE_PER_J` (4/3).
- `weatherFactor()` = 1 + w + 2·staticWeather.
  - In a storm, w = stormMist + 2·stormRift; otherwise w = 0.5·drifting deep overlap.
  - Overlaps are sampled at 27 points at 0.7 r (`Deeps.overlap`, `staticOverlap`, `Storms.overlap`).
- `stretchFor(layout)`: each side reaches 1 + `towerBoomStretch`·(longest boom / (H - y)). Draw follows the
  volume (`volumeScale`).
- Feeding (`toggleFeed`/`feed`): 2 kW from the player's suit and batteries (`Suits.drain`). Feeding a running
  tower leaves 25% in each piece (`FEED_RESERVE`).
- Config: `towerDrawK`, `towerStoreBatteries`, `towerMaxSegments`, `towerBoomStretch`, `towerFieldRate` (`StasisConfig`).

## Temporal accelerator (`TemporalAcceleratorBlock`; λ-Nero, notes/timestop/ACCEL-DESIGN.md)
- A block against either core cell (any side), set by its lever (`AcceleratorLevers`: a right-click raises `STEP` 0..18,
  round from the top; a sneak-right-click lowers it, with anything in hand; `AcceleratorLeverRenderer` draws the
  lever on the face last clicked, swinging with the setting) or by the console's rate slider (`ConsoleRate`,
  `TowerBlockEntity.setAccelStep`: sets every accelerator against the core; greyed out with a tooltip without one):
  rate = 1 + step / 2 (1x..10x; 1x lifts the field's slowness, Baughn 2026-10-30). `want(level, master)`: the
  highest setting against the core, or `towerFieldRate` without one; `step(level, master)`: -1 without one.
- `TowerBlockEntity.accelerate` (once a second while running, after `safety`): the field runs at `accelRate` (saved;
  `towerFieldRate` without an accelerator). `drawAt(r)` includes `(accelRate / towerFieldRate)²` above the tower's own
  rate (Baughn: priced by the speed-up, 0.6 to 1.5 is 2.5²).
- Short of power (`failing > 0.5`), `safety` sets `accelRetreat` and returns before any radius retreat: the rate falls
  to `carryRate` (the highest half step whose draw the input carries at 90%, at least `towerFieldRate`), at least half
  a step a second while the shield fails, and comes back once the store is 80% full. Override turns this off with the
  rest of the safety.
- Console readout values 20-22: accelerator step (-1: none), the rate it asks for, the rate running.
- Tests: `tower_temporal_accelerator`, `tower_accelerator_1x_and_console`.

## Chunk tickets (`Tickets.java`)
- `update(level, tower)` forces the rectangle of chunks covering max(radius, target)·stretch per side, while the
  state is not OFF. It releases chunks no longer needed.
- NeoForge saves forced chunks. The controller's validation callback (`loaded`) reads them back into `FORCED`
  at level load, so a tower that shrinks or stops after a restart releases what it forced before.
  Towers force with natural spawning on (the last `forceChunk` flag) and release the same kind. `loaded` adopts
  only that kind and drops any plain ticket under the controller, which `update` could never release.
- `Tickets.clear()` runs on `ServerStoppedEvent`, because singleplayer worlds share the JVM.

## Persistent state
- `TowerBlockEntity.saveAdditional`:
  - state, field, segments, collapse, energy, failing
  - locked, ruined, coreDrop
  - amber, dropout, side, collapseProgress, freezeAt, collapseSpeed
  - target, override, radius, stretchE/S/W/N
  - retreating, retreatRadius, facing
  - remnant* + bolts
- `TowerStructure` NBT: `layout` (JSON), `tstatus`, `tmsg`, `ladder`, `seed`, `parted`. `BUILDING` loads as
  `WAITING`.
- Flags set by templates, `/data` or ruin placement: `locked` (the spawn tower), `ruined` (not read or drawn
  until it first runs), `coreDrop` (0 for ruins: they drop scrap).

## Client / server split
- The update tag is the full NBT, layout JSON included. The client rebuilds the mesh from layout + seed; the
  server keeps no `TowerGen.Result`, only status and `ladderTop`.
- The console reads 20 values (`CONSOLE_NAMES`) through `ConsoleState`. `StasisNet.consoleTower` gates access:
  within 8 blocks, the master, inside the world border.

## Invariants and tests
- A tower and its field go away together: `core_breaks_into_loose_blocks`, `tower_runs_a_field`.
- The game never builds a refused or invalid layout: `tower_invalid_keeps_survey_poles`, `boom_from_core_top`.
  `TowerStructure.boomRefusal` mirrors `Layout.violations` on blocks.
- Collapse when empty: `tower_collapses_when_empty`. Booms stretch the field: `tower_booms_stretch_field`.
- Range, console, lock and feed: `tower_range`, `tower_console_access`, `tower_lock_and_feed`,
  `tower_shield_shows_drain`, `tower_weather_raises_draw`.
- Other tests: `poles_extend_like_scaffolding`, `tower_reads_poles`, `tower_builds_and_parts_poles`,
  `core_forms_from_2_blocks`, `ruined_core_scrap`.

## Gotchas
- `collapseSeed` is not saved. A restart mid-collapse gives a remnant seeded 0.
- `TowerStructure.build` is `static synchronized`: `TowerGen` has a mutable `seed`, and one generator is
  shared on the "stasis-towergen" thread.
- A build result is applied only if the layout is unchanged since it started. Debounce: 40 quiet ticks
  (`DEBOUNCE`).
- GameTests set `TowerStructure.syncBuild` to build on the server thread.
