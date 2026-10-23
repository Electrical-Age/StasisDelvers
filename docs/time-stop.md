# Time-stop core: fields, rates, tick gates

Paths are relative to `src/main/java/org/erisia/stasis/`.

## Classes
- `Stasis.java`: the rule. It answers "how fast does time run at this block?"
  - `rateAt(level, x, y, z)` with eddies; `rateAtSteady` without them (EA nodes, towers, Freezing's eddy test);
    `rateAtLand` without eddies and without drifting deeps over running spread land (flame light, chunk meshing,
    Xaero: caches nobody refreshes as weather passes; safe off the main thread). `spawnsHere` ignores the
    new-chunk grace. In stopped mode `rateAtLand` = `rateAtSteady`.
  - Order inside `rateAt(level,x,y,z,eddies,grace,deeps)`:
    1. Not a stopped dimension (`stops`; in spreading mode the Nether and the End until complete): 1.
       Spreading mode, overworld: a column the stillness hasn't reached (`spreadRuns`): 1; 0 in a rift, and 0 in a
       drifting deep (`Deeps.stopsRunningLand`, server main thread or client) unless a field or the spawn area
       holds the block. See spreading-mode.md.
    2. Spawn area or new-chunk grace: 1.
    3. No field in the chunk column: 0.
    4. Otherwise the fastest field with `containsBlock`, skipping eddy pockets.
    5. Finally, inside a rift (`StaticDeeps.inRift`): 0.
  - Per-level column index (`Index`: chunk key to the fields touching that column). It is rebuilt lazily in
    `indexLocked` (synchronized), dropped by `invalidate`, and versioned by `version()`. The client index
    (`clientIndex`) is read without the lock.
  - Tick gate: `milli`, `runs(rate, gameTime)`, `dueTick`, `ticksNow`, `mayRunInColumn`.
  - `sectionState`: STOPPED / RUNNING / MIXED for a 16³ section by `rateAtLand` (chunk meshing and the GameTests
    use it).
  - `exemptBlockEntity`: tower master, End gateway, EA `LightBlockEntity`.
  - `nearTower` (the `TOWER_YARD` of 6 blocks); `isFrozen(Entity)` (never players, never `#stasisdelvers:unbound`).
    Unbound types (endermen, the dragon, the warden): never frozen on the client either (`FrozenEntities`: they never get
    a FROZEN sync, so the client mustn't guess from the block); they skip the stopped-land spawn vetoes, up to `stoppedLandEndermen` (2) unbound mobs within 64 blocks
    (`StasisEvents.unboundRoom`, counted per 32-block cell every 40 ticks; else they filled the monster cap); endermen
    don't take or place blocks in stopped land (`StasisEvents.griefing`). Test: `enderman_spawns_and_doesnt_grief_in_stopped_land`.
- `Field.java`: immutable record (id, centre, radius, failing, collapse, column, rate, seed, formedAt, amber,
  dropout, side, `Stretch`).
  - Geometry: `norm`, `normSpan`, `containsBlock` (the whole block must be inside: the far face on each axis),
    `containsPoint`, `toward`, `reach`, `minX..maxZ`.
  - Collapse: `collapsed` / `collapseScore`, mirrored in `stasis.fsh`.
  - `clampRate` keeps rates in [0.001, 1]. `changedShapes` diffs two field lists (used for relighting and re-meshing).
  - `CODEC` loads old saves with defaults.
- `FieldData.java`: `SavedData` `stasisdelvers:fields`, one per level.
  - Mutations: `add`, `update(id, UnaryOperator)`, `remove`, `clear`. Each ends in `changed()`.
  - `LOADED` + `peek` / `peekDeepSeed` serve off-thread readers (light engine, worldgen).
  - `get` is main-thread only.
- `ClientFields.java`: the client copy. `set` invalidates, relights flames and fires `onChange`, which re-meshes
  through `FrozenSprites`. `underShield` is a looser test, for weather.
- `NewChunkGrace.java`: a newly generated chunk runs for `TICKS` = 70.
  - Server only: `active` is false on the client.
  - `exempt` turns it off for gate rooms and time-scar chunks.
- `Eddies.java`: stopped pockets that drift in weak fields (failing > 0.7) and during a collapse. They only
  affect `rateAt(..., eddies = true)`.
- `LocalTime.java` + `FieldSleep.java`: visual per-field clocks, skewed 0.5-2x toward world time. Sleeping
  advances only that field's clock. Clocks are saved in `FieldData.clocks`, and sync every 40 ticks (`FieldClocks`).
- `Flames.java`: flames tagged `#stasisdelvers:flames` are dark in stopped land and gutter where time runs.
  Light comes from `BlockMixin` merging `getLightEmission(state, getter, pos)`. Gutters are saved
  (`stasisdelvers:gutters`) and synced (`Gutters`).
- `StilledLiquids.java`: stopped water and lava are solid. A shovel takes them as stilled blocks.
- `StasisEvents.java`: the event-driven part:
  - entity tick cancel and the `FROZEN` attachment
  - spawn vetoes, pickup delay, leash drag
  - `deathLog`, a diagnostic that is kept on purpose
- Mixins that gate ticks (details in mixins.md):
  - `ServerLevelMixin`: scheduled, random and precipitation ticks
  - `LevelChunkMixin`: block entities
  - `LiquidBlockMixin`, `EntityPushMixin`, `BucketItemMixin`
  - `WalkNodeEvaluatorMixin` + `PathfindingContextMixin`

## Acceleration fields (rate above 1; λ-Nero, notes/timestop/ACCEL-*.md)
- `Field.clampRate` allows (0, `Field.MAX_RATE` = 10]. For the rule a field counts as `min(1, rate)`: `rateAt`, `rateAtSteady`
  and `rateAtLand` stay in [0, 1] for all their callers (PlayerRates, EA's NodeFreezer, meshing).
- `Stasis.speedAt(level, x, y, z)` (>= 1): the achieved rate of the fastest acceleration field holding the whole block, 1 in
  a rift. It ignores `stops()`: it works in running land and in levels that don't stop. Per-level `Index.fast` by column;
  `anyFast` (set by the `Field` constructor) makes it one read until an acceleration field exists. The server's built
  indexes are also in the copy-on-write `serverIndex`, read without the lock.
- `Stasis.ticksThisTurn(speed, t)`: how many ticks a block at that speed gets in game tick t (exact thousandths, like `runs`).
- Extra ticks (server only): scheduled delays become `max(1, ceil(delay / speed))` (`dueTick`); random ticks
  (`ServerLevelMixin`), block entities (`BoundTickingBlockEntityMixin`; not `multiTickExempt`) and entities
  (`ServerLevelEntityMixin`; not players or passengers) run `ticksThisTurn` times.
- `Speeds`: once a second each acceleration field counts its block entities and entities and achieves
  `1 + min(rate - 1, accelBudget / count)`, all together within `accelServerBudget`. Kept outside `FieldData` (a change
  there re-syncs and re-meshes). The client has no copy yet: it uses the field's rate.
- Not done yet: client-side extra ticks (entities rubber-band in a fast field), players (3x cap), the EA hook, the
  tower block and the riftstone room.

## Riftstone rooms (`Rooms`, `ChronalAcceleratorBlock(Entity)`; λ-Nero's variant B)
- A chronal accelerator set into a wall fills from each of its sides (6-connected) through everything not tagged
  `stasisdelvers:chronal_casing` (riftstone, refined riftstone, accelerators, riftstone glass; riftstone doors only while
  shut), within a 16-block span and at most 1728
  blocks; the side that stays sealed is the room. Max volume 512 with plain walls, up to 1728 with all refined (by the
  refined share of its wall blocks). Every 2 s and on every click.
- It runs while its FE store pays `2 W x volume x rate² x (1 - 0.5 x refined share)`: at its setting (`STEP` 0..18:
  1x..10x), else the highest half step the store carries, else not at all. Power comes from outside: an EA exporter
  against the accelerator (it counts as wall).
- A running room is in `Rooms` (per level, copy-on-write, not saved: its accelerator registers it again after a load):
  Riftstone glass: 3 sand + 1 riftstone dust (EA macerator, `ElnMachineRecipes.EXTRA`) make 4 glass mix, smelted.
  `rateAt` floors it at 1 (stopped land too; a rift still wins), `speedAt` takes its rate, `heldOff` (deeps pass over it),
  `mayRunInColumn`, `baseSectionState` (MIXED). Synced whole (`RoomsSync`); the client re-meshes and relights round a
  change (`FrozenSprites.roomsChanged`). The accelerator is `exemptBlockEntity` and `multiTickExempt`.
- The post shader takes the rooms' boxes, walls included (slots 32+, `StasisPost.ROOM_BASE`): a room and its walls are
  in colour in stopped land. Test: `chronal_room`.

## Data flow
1. A tower (`TowerBlockEntity.start`) or a command (`StasisCommands`) calls `FieldData.add` or `update`.
2. `FieldData.changed()` does, in order:
   - takes a new immutable snapshot
   - `setDirty`
   - `Stasis.invalidate(level)`
   - `StasisNet.sendFields(level)`
   - `Flames.fieldsChanged`
3. The client gets `StasisNet.Fields` (fields + deep seed), then `ClientFields.set`, which invalidates the
   client index and re-meshes the sections whose shape changed.
4. Readers call `Stasis.rateAt`. The index rebuilds on first use.

## The tick gate (exact integer arithmetic)
- A rate is counted in thousandths (`milli`). Tick t runs iff `floor(t*m/1000)` steps up at t. This is stateless,
  the same for every block at that rate, and stable across restarts.
- Scheduled ticks: `ServerLevelMixin.createTick` (two merged overrides of `LevelAccessor` defaults) sets the
  due tick to `Stasis.dueTick`, which is `delay` *running* ticks from now.
  - `tickBlock` / `tickFluid` give a tick that lands on a non-running tick back as delay 1.
  - Wholly stopped columns keep their ticks queued (`isPositionTickingWithEntitiesLoaded` returns false).
- Random ticks run only when `ticksNow` says so. Snow and ice skip wholly stopped columns (`tickChunk` HEAD cancel).

## Persistent state
- `FieldData` keys: `next_id`, `fields[...]` (`Field.CODEC`), `cottage`, `deep_seed`, `clocks{id: double}`. A new level's
  `deep_seed` is `FieldData.derive(world seed, dimension)` (since 2026-10-20, Baughn: a same-seed reset put the rifts
  elsewhere); levels that have one keep it. Test: `DeepSeedTest`.
- The `FROZEN` attachment (`StasisRegistry`) is synced, not saved.

## Client / server split
- Both sides run `rateAt`: the server from `FieldData`, the client from `ClientFields`. This keeps stopped-water
  collision and movement prediction in agreement.
- Known disagreement: `NewChunkGrace` is server-only, so for 3.5 s in a new chunk the client treats stopped
  water as solid while the server does not.

## Invariants
- Stopped means rate 0, and `rateAt` is in [0, 1] (field rates in [0.001, 10]; above 1 only `speedAt` sees it). Where fields overlap, the fastest wins.
  Guarded by GameTests `rate_overlap_max`, `rate_math`, `TickGateTest`.
- Whole-block rule: a block the boundary cuts is outside, for stretched fields too.
  Guarded by `FieldTest.containsBlockIsAllEightCorners` and `bugA_westCornerOfACentreBlock`, and GameTests
  `rule_whole_block` and `stretched_field_shape`.
- `sectionState` never says RUNNING or STOPPED for a section with a block that disagrees.
  Guarded by `section_state_matches_blocks` and `section_state_matches_stretched`.
- `dueTick` lands on the delay-th running tick, and equals vanilla `now + delay` at rate 1.
  Guarded by `TickGateTest`.
- Spawn area and new-chunk grace are checked before fields and rifts. A rift beats any field.
- Anything that must not churn as eddies drift past uses `rateAtSteady`: EA nodes, towers. Flame light and
  meshing use `rateAtLand`, which also ignores drifting deeps over running spread land.
- "Which field am I in?" has four answers, deliberately different:
  - `rateAt`: the fastest field that holds the whole block
  - `FieldSleep.fieldAt`: the first field holding the point
  - `LocalTime.Client.time`: the deepest edge weight
  - `ClientFields.underShield`: any sphere

## Gotchas
- `FieldData.get` off the main thread can deadlock or crash. Off-thread code must use `peek`, `peekDeepSeed`
  and `Deeps.seed`, which already branch on `isSameThread`.
- Shrinking or removing a field calls `Freezing.graceIfStopped` for players who were safe, so a shrink is
  never an instant statue.
- `PlayerRates` floors the client tick rate at 1 TPS, while fields allow 0.001 (see players-suits.md).
