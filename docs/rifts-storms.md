# Rifts, deeps, storms, strata weather, crystals

Paths are relative to `src/main/java/org/erisia/stasis/`. Design notes: `/var/lib/mclab/agent/notes/rifts/`
(STAGE1/2/3-PLAN.md, CAPACITOR-SPEC.md).

## Classes
- `Noise.java`: `hash32`, value noise and `fbm32`, written identically in `stasis.fsh`. Ints wrap the same
  way. Seeds are 24-bit, exact as floats in the shader's data texture.
- `Deeps.java`: **drifting deeps**, which act as surface weather.
  - A 256-block grid (`CELL`), 80% occupied, radius 20-42, carried by a per-world wind (`drift`, `windAngle`).
  - Each deep has a 20-60 min form/fade envelope. Puffs (`puffs`, `nextPuffIn`) are breathed out by static deeps.
  - `near`, `within`, `horizontal`, `at`, `inBand`, `overlap(level, field)`.
  - `seed(level)` is **the** way to get a level's deep seed; it branches on `isSameThread`. GameTests get 0
    unless `testDeeps` is set.
  - Clocks: `time(level)` = `Storms.tau(realTime)` (warped by storms); `realTime` = gameTime / 20. Both are
    doubles that never wrap.
- `StaticDeeps.java`: **static deeps**. They never move, and each bottoms out in a **rift**.
  - A jittered 3D grid (`CX`/`CY`/`CZ` = 512/192/512) with 25% occupancy, radius 24-64; the rift is the inner 35%.
  - `cell`, `near`, `core`, `shape`, `zone` (0 none, 1 outer, 2 rift), `inRift`.
  - Gate rifts: `setGates`, `isGate`, `gateCount`.
  - Lance return rifts: `returnRift` (radius `RETURN_RADIUS` 3), `setReturns`, `isReturn` (`isGate` too: no strata, no
    puffs); per deep seed from `Lance`'s arrivals, synced in `GateRifts`, listed by `near` after the gate rifts.
  - The End rift (spreading mode, hard mode): `endRift(seed)` at (0.5, 64.5, 0.5), radius 40, added per deep seed by
    `addEndRift` and listed first by `near`; `isGate` is true for it (no strata, no puffs). See spreading-mode.md.
  - `sectionMayHave`: a concurrent cache per deep seed, used by `Stasis.sectionState` from mesh threads.
- `Storms.java`: storms, roughly one per `stormEveryHours`, lasting `stormMinutes`.
  - Time is cut into blocks of the mean gap, one storm per block at a hashed start (`scheduled`); lookup is O(1).
  - The intensity I ramps in and out over 2 min (`RAMP`). `integral` has a closed form.
  - **Warped time** `tau(t) = t + 9·∫I + override offset`: deeps and puffs move up to 10x faster in a storm.
  - **The origin** (`origin(level)`, `clientOrigin()`; every function takes it): the schedule's blocks count from it,
    and before it `tau(t) = t` with no storms. 0 in the base mode (the schedule as always). In spreading mode NaN (no
    storms) until hard mode, then the game time of the hard-mode instant (`Spread.Clock.hard_game`, synced in
    `SpreadState`): storms switch on there and tau goes on from t without a jump (Baughn: storms come with hard mode).
    The 3-argument `tau`/`intensity`/`warning` are origin 0, for tests.
  - `warning` drives the violet horizon glow. `field`/`kind`/`at`: storm mist (1) and storm rift (2) by noise.
  - `overlap`/`side`: for towers (draw, collapse side).
  - `Forced` is the `/stasis storm start|stop` override (SavedData `storm_override`: active, start, end, folded).
- `Pockets.java`: strata weather. World-aligned 12-block cubes under each rift, with states `CLEAR`, `MIST`,
  `VIOLET` (a 20 s warning) and `RIFT`.
  - Epochs of 10-30 min, from `Deeps.realTime`. Storms don't speed them up.
  - `weather`, `exposure` (closed form, for crystals), `masked` (4-block strata mask cells), `at(level,...)`.
- `Crystals.java`: analytic rift-crystal growth. Nothing ticks in stopped land, so each bud accumulates exposure
  seconds (`Pockets.exposure`) while no field covers it.
  - `sweep` runs on chunk load and every 30 s. `stageFor(e)` uses `crystalStageSeconds`.
  - SavedData `rift_crystals` (`buds[pos, dir, t, e, stage]`, `swept`).
- `StormCrystals.java`: while a storm runs, clusters form on open ground under storm rift, one per 32x32 cell,
  checked every 5 s within 128 blocks of a player. They are removed after the storm, in chunks loaded later too.
  SavedData `storm_crystals`.
- `Eddies.java`: drifting stopped pockets in weak fields (see time-stop.md). It has its own float clock
  `Eddies.time`, which wraps.
- Items and blocks:
  - `RiftCompassItem.java` points to the nearest rift core within ~1.5 km and spins in storms.
  - `RiftBarometerBlock.java` gives a redstone signal from the storm warning (up to 4) and the storm (up to
    15). Like all redstone, it works only where time runs.
  - `Capacitors`/`CapacitorItem`: see players-suits.md.
- Client: `client/RiftHum.java` (the hum of a pocket about to turn rift), `client/StrataMask.java` (the 64³ strata mask for the
  shader), and the mist, eye, storm glow and puffs in `stasis.fsh` (see client-rendering.md).

## Effects on gameplay
- Rift (`StaticDeeps.inRift`, pocket `RIFT`, storm rift): `Stasis.rateAt` returns 0 even inside a field.
  An unprotected player becomes a statue in about 3 s; touching the eye is instant. Nothing protects. (Open lance
  gates and return rifts are doors: lance.md.)
- Outer zone, mist pockets: suit drain x3 or x2, and the Delver freeze creeps (players-suits.md).
- Drifting deeps and storm mist: drain x2, and the Delver hold instead of a statue.
- Towers: `weatherFactor` (towers.md). Storm mist x2, storm rift x3, drifting deep up to x1.5, static outer
  zone up to x3.

## Persistent state
- `FieldData.deep_seed` (time-stop.md).
- SavedData: `storm_override`, `rift_crystals`, `storm_crystals`, `rift_gates` (`Gates`, see
  deeps-strata-worldgen.md).
- Config: `storms` (in spreading mode only from hard mode's origin, spreading-mode.md), `stormEveryHours`, `stormMinutes`, `stormWarnMinutes`, `crystalStageSeconds`.

## Client / server split
- Synced: the deep seed (in `StasisNet.Fields`), gate rifts (`GateRifts`), the storm override (`StormOverride`), and in
  spreading mode the storms' origin (`SpreadState.stormOrigin`).
- Everything else is recomputed on the client from the seed, the synced config and game time. The GLSL
  mirrors `Noise`, `Pockets` periods (600/900/1200/1800 s in data slots 188-195) and the deep constants
  (`DEEP_BASE`, `MAX_DEEPS`).

## Invariants and tests
- **tau never jumps.** Deeps' positions are a function of tau, so a jump teleports every deep.
  - `Storms.start` folds a finished override into `folded` (9·its integral); `stop` ends at the start of a ramp-out.
  - Tests: `TickGateTest.tauIsMonotoneAndWithoutJumps`, `tauIsContinuousAcrossBlockEdges`,
    `tauWithAnOverrideIsMonotoneToo`, `integralIsTheIntegralOfIntensity`, `tauIsContinuousAcrossTheHardModeOrigin` (t
    before the origin, the shifted schedule after, monotone and jump-free across it, nothing with no origin), GameTests
    `deeps_clock_continuous`, `spread_hard_mode_entry`.
  - Hard mode drops the test overrides (`Storms.clearOverrides`): they were ignored while storms were off, and counted
    at once past the origin they would jump tau. `/stasis storm start` is refused before hard mode in spreading mode.
- Each storm stays inside its block: `TickGateTest.scheduledStormStaysInItsBlock`, GameTest `storm_schedule`.
- Storm coverage and rules: `storm_coverage`, `storm_draw_and_rules`.
- Crystals: `rift_crystals_grow`, `rift_crystals_no_growth_in_field`, `rift_crystal_grades`, `rift_crystal_drops`.
- Upwellings and puffs: `rift_upwellings`. Static deep shape: `static_deeps_shape`.

## Gotchas
- Changing `stormEveryHours` or `stormMinutes` mid-world changes tau for all past time, so every deep jumps.
  Only override changes are guarded.
- Seed 0 means "not loaded yet": storms off, no deeps, `DeepFeature` does nothing. Never use 0 as a real seed.
  `FieldData.deepSeed()` draws from 1..0xFFFFFE.
- Test hooks ship in the production jar: `Pockets.TEST`, `Pockets.TEST_STRATA`, `Storms.TEST`,
  `Deeps.testDeeps`, `Crystals.FACE_SHARE`.
