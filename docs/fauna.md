# Rift fauna

Things time doesn't stop. They spawn near weather, rifts and storms, and the world stays quiet elsewhere (Baughn).
Plan and decisions: `/var/lib/mclab/agent/notes/fauna/` (PLAN.md, OPTIONS.md). Paths relative to `src/main/java/org/erisia/stasis/`.

## Classes
- `Dread.java`: `at(level, x, y, z)` in 0..1.
  - 0 wherever time runs (fields, the spawn area, running spread land); a drifting deep over running land stops it, and counts.
  - Otherwise the max of: static deep outer zone 0.5 / rift 1; strata pockets CLEAR 0.15, MIST 0.35, VIOLET 0.7, RIFT 1;
    a drifting deep 0.2 + 0.3 x intensity; storm mist 0.6 / storm rift 0.9 x the storm's intensity.
  - `storm(...)`: a storm over the place (looser caps and cooldowns).
- `RiftFauna.java`: the base class (a `Monster`). Tag `#stasisdelvers:rift_fauna`, inside `#stasisdelvers:unbound`.
  - **The wall:** a fauna that finds itself where time runs is put back where it last stood stopped. A failing field
    (failing > 0.5) may be entered (λ-Nero). With nowhere to go back to (a field grew over it), it has a 50 % chance to
    escape to a stopped standable spot within 12 blocks, else it dissolves (Baughn).
  - Its paths treat running land as blocked (`StoppedOnly` node evaluator; the global `WalkNodeEvaluatorMixin` is for
    the other direction).
  - Speed x (1 + dread), so at most 2x (λ-Nero). Updated every 10 ticks.
  - Dread 0 at and around it for `FADE_TICKS` (200) and it dissolves (static particles, no drop).
- `RiftStatic.java`: Static, the suit drainer. It **blinks**: every 10-25 ticks (/(1 + dread)) a step of 2.5-3.5 blocks
  (x (1 + dread)) towards the nearest survival player within 24 blocks, onto a free spot in stopped land, hovering 0.6 over
  ground within 3 blocks below; idle, a 1.5-block blink every 40-100 ticks. No gravity, no pathfinding (walking it stuck in
  riftstone pits, flying it drifted off: AdventurAgent). Client look: 10 dust specks a tick, mostly black and white, size 0.9-1.5
  plus full-bright end-rod glints for rift darkness (AdventurAgent: sparse grey specks were invisible, violet washes out
  in the grey); clicks every ~12 ticks. `feeding(level, p)`: each Static within `REACH` (4) blocks, at most 3;
  `Freezing.tick` multiplies the suit's draw factor by 1 + that, in stopped land only (a field is safety), so a rift
  capacitor takes the surcharge like the weather's. It never hits. Sounds: sculk clicking (a Geiger-ish crackle). No model: `NoopRenderer`, and the client draws a knot
  of static pixels (`DustParticleOptions` black / white / violet; in stopped land they hang and fade, `ParticleGroupMixin`).
- `FaunaSpawner.java`: its own spawn pass (vanilla vetoes natural spawns in stopped land, and its caps are per category).
  - Every 40 ticks per player in a stopping dimension, at most once per roll (45-90 s): 4 standable spots 16-48 blocks
    out (from up to 32 samples), each spawns with `faunaChance` x dread. In a rift's outer zone (dread 0.5) that's about
    40 % per roll.
  - `/stasis fauna PLAYER`: dread there, the roll and quiet timers, fauna within 64 blocks, and how many of 40 sample spots
    are standable and in dread.
  - Caps `faunaPerPlayer` (2) within 64 blocks, `faunaPerPlayerStorm` (3).
  - After an encounter (a fauna within 8 blocks) nothing new for `faunaQuietMinutes` (12.5, ±15 %), `faunaStormQuietMinutes` (2.5)
    in a storm. `Freezing.freeze` calls `froze`: the fauna within 48 blocks dissolve and the quiet starts (no camping statues).
  - Memory only (cooldowns); a fresh player's first roll waits 45 s. Spawn reason `MOB_SUMMONED`, outside the stopped-land veto.
- `TowerBlockEntity.faunaPressure`: fauna within 4 blocks outside a field add 0.5 each (at most 2) to `weatherFactor`,
  computed every 20 ticks with the weather (λ-Nero: they raise the draw like a storm, and can make it fail).

## Config (synced)
`fauna`, `faunaChance`, `faunaPerPlayer`, `faunaPerPlayerStorm`, `faunaQuietMinutes`, `faunaStormQuietMinutes`;
`stoppedLandEndermen` (time-stop.md).

## Tests
`fauna_dread`, `fauna_failing_field_lets_in`, `fauna_field_grows_over_them`,
`fauna_fades_without_dread`, `fauna_spawner_caps` (isolated; `FaunaSpawner.testSpot` puts the tries on the test floor),
`static_drains_suits`, `fauna_field_is_a_wall` (isolated: a freeze in a neighbouring test dissolves fauna within 48 blocks), `fauna_press_on_the_shield`, `fauna_leave_a_fresh_statue`.
Dread in tests comes from `Pockets.TEST` / `Storms.TEST` boxes (the test deep seed is 0).
