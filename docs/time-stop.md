# Time-stop core: fields, rates, tick gates

Paths are relative to `src/main/java/org/erisia/stasis/`.

## Classes
- `Stasis.java`: the rule. It answers "how fast does time run at this block?"
  - `rateAt(level, x, y, z)` with eddies; `rateAtSteady` without them (for flame light, EA nodes and towers).
    `spawnsHere` ignores the new-chunk grace.
  - Order inside `rateAt(level,x,y,z,eddies,grace)`:
    1. Not a stopped dimension (`stops`): 1.
    2. Spawn area or new-chunk grace: 1.
    3. No field in the chunk column: 0.
    4. Otherwise the fastest field with `containsBlock`, skipping eddy pockets.
    5. Finally, inside a rift (`StaticDeeps.inRift`): 0.
  - Per-level column index (`Index`: chunk key to the fields touching that column). It is rebuilt lazily in
    `indexLocked` (synchronized), dropped by `invalidate`, and versioned by `version()`. The client index
    (`clientIndex`) is read without the lock.
  - Tick gate: `milli`, `runs(rate, gameTime)`, `dueTick`, `ticksNow`, `mayRunInColumn`.
  - `sectionState`: STOPPED / RUNNING / MIXED for a 16³ section (chunk meshing and the GameTests use it).
  - `exemptBlockEntity`: tower master, End gateway, EA `LightBlockEntity`.
  - `nearTower` (the `TOWER_YARD` of 6 blocks); `isFrozen(Entity)` (never players, never `#stasisdelvers:unbound`).
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
- `FieldData` keys: `next_id`, `fields[...]` (`Field.CODEC`), `cottage`, `deep_seed`, `clocks{id: double}`.
- The `FROZEN` attachment (`StasisRegistry`) is synced, not saved.

## Client / server split
- Both sides run `rateAt`: the server from `FieldData`, the client from `ClientFields`. This keeps stopped-water
  collision and movement prediction in agreement.
- Known disagreement: `NewChunkGrace` is server-only, so for 3.5 s in a new chunk the client treats stopped
  water as solid while the server does not.

## Invariants
- Stopped means rate 0, and field rates are in [0.001, 1]. Where fields overlap, the fastest wins.
  Guarded by GameTests `rate_overlap_max`, `rate_math`, `TickGateTest`.
- Whole-block rule: a block the boundary cuts is outside, for stretched fields too.
  Guarded by `FieldTest.containsBlockIsAllEightCorners` and `bugA_westCornerOfACentreBlock`, and GameTests
  `rule_whole_block` and `stretched_field_shape`.
- `sectionState` never says RUNNING or STOPPED for a section with a block that disagrees.
  Guarded by `section_state_matches_blocks` and `section_state_matches_stretched`.
- `dueTick` lands on the delay-th running tick, and equals vanilla `now + delay` at rate 1.
  Guarded by `TickGateTest`.
- Spawn area and new-chunk grace are checked before fields and rifts. A rift beats any field.
- Anything that must not churn as eddies drift past uses `rateAtSteady`: EA nodes, flame light, towers.
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
