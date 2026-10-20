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
- `RiftStatic.java`: Static, the suit drainer. It **blinks**: every 16-30 ticks a step of 1.5-2.5 blocks (x (1 + dread);
  about 2.6 blocks/s at dread 0.5, so a walking player can outpace it) towards the nearest survival player within 24 blocks, onto a free spot in stopped land, hovering 0.6 over
  ground within 3 blocks below; idle, a 1.5-block blink every 40-100 ticks. No gravity, no pathfinding (walking it stuck in
  riftstone pits, flying it drifted off: AdventurAgent). A blink is a cut or a slide, a coin flip per blink on each client
  (Baughn likes both): its client interpolation handler skips the stepped slide half the time. Client look: 10 dust specks a tick, mostly black and white, size 0.9-1.5
  plus full-bright end-rod glints for rift darkness (AdventurAgent: sparse grey specks were invisible, violet washes out
  in the grey); clicks every ~12 ticks. `feeding(level, p)`: each Static within `REACH` (4) blocks, at most 3;
  `Freezing.tick` multiplies the suit's draw factor by 1 + that, in stopped land only (a field is safety), so a rift
  capacitor takes the surcharge like the weather's. It never hits. Sounds: sculk clicking (a Geiger-ish crackle). No model: `NoopRenderer`, and the client draws a knot
  of static pixels (`DustParticleOptions` black / white / violet; in stopped land they hang and fade, `ParticleGroupMixin`).
- `RiftPuppeteer.java`: the Puppeteer. A low, wide cloud (draws ~2.4 x 0.8; hitbox 1.4 x 0.6), sparser and darker than
  Static, lower clicks (Baughn: that's how you tell them apart). It creeps in 0.8-block low blinks to the nearest frozen
  mob within 16 blocks (`Possession.canHost`: a frozen `Mob`, not fauna, not a boss, not taken), pours into it from up to 4.5 blocks over 30 ticks
  (synced `POURING` = host id; the client draws a column of specks), and is gone into it. With no host it drifts after
  players, keeping 8 blocks off. Burst out of a host, it's stunned for 60 ticks.
- `Possession.java`: the host (memory only; `POSSESSED` attachment synced, not saved). Its goals' flags are all switched
  off; it runs in **bursts** of 2-8 ticks with 5-40 frozen between (/(1 + dread)), `Stasis.isFrozen` false only in a burst
  (so the FROZEN sync and the client's stillness follow); each burst paths it at the nearest survival player within 32,
  at `burstPace` = (run + hold) / run (at most 8): fast in the burst, so on average it keeps the mob's own pace (Baughn). It hits for 3 at reach, every 30 ticks, without knockback. The fauna wall applies (put back where it last stood
  stopped; if a field grew over that too, half the time out to a stopped spot, else the Puppeteer goes). A player freezing
  within 48 blocks sends hosts' Puppeteers away too (`releaseNear`). Hosts count in a tower's fauna pressure. Damage to the host goes to the grip (6) instead, each swing at least 2 and once per 8 ticks (3 fist hits); at 0 the static bursts out (a Puppeteer) and the host freezes
  where it is, unhurt; no Puppeteer takes that host again for 1200 ticks; /kill and the void go through. No prey for 400 ticks: it pours out; no dread for FADE_TICKS: it dissolves.
  - Client (`client/PossessedLook`): purple eyes (two full-bright quads at eye height, on the head's front by its yaw;
    `RenderLivingEvent.Post`, render data from `RegisterRenderStateModifiersEvent`) and a static haze (EntityTickEvent.Pre at
    HIGHEST, before FrozenEntities cancels the frozen host's client tick).
- `PossessedStatue.java` (F4; design: notes/fauna/F4-DESIGN.md): a statue a Puppeteer took. **It never loses the gear**
  (λ-Nero): every end is `revert`, which adds a `Statue` with exactly the items (list + every equipment slot) and xp first,
  and only then discards itself.
  - Taken (`take`): items copied, armour worn, tools stay in the list; the new entity is added before the statue goes. Only
    a Puppeteer already there notices a statue (`canTake`); the spawner's Puppeteer choice counts mobs only (rarity: Baughn).
  - Hunts its owner in stop-motion bursts (`running`, `Stasis.isFrozen`; scheduled from the server tick, since its own tick
    is cancelled between bursts), at `burstPace`; hits for 3 without knockback; stuck 20 burst ticks with its owner over 3
    blocks off: blinks up to 8 blocks towards them (λ-Nero). The fauna wall: a field grown over it means remains.
  - Damage only from players while in its owner's chunk (λ-Nero); /kill and the void: remains; `die` and `tickDeath` revert.
  - Owner offline, in another dimension, or no 2-block progress in 2400 ticks: teleports home (the statue's spot, its chunk
    forced for the add) and stands again. Owner dies: `testHunt` or a coin: hunt on (to the field at their respawn) or home.
  - Chunks: `TICKETS` (entity-owned, ticking, 3x3, follow it), saved by NeoForge; `Data` (`possessed_statues` per level:
    uuid → chunk) is what the load validator keeps. `sweep` drops the tickets of an entry whose chunk is loaded but whose
    entity hasn't been seen for 5 minutes (and logs an error). `joined` (EntityJoinLevelEvent) registers it and sets
    POSSESSED after a load.
  - Owner notices ("Your statue stands again at ...") wait in the overworld's `Data` for owners who are offline.
  - Client: `PossessedStatueRenderer` (player model, owner skin, armour layer); eyes and haze as any host.
- `FaunaSpawner.java`: its own spawn pass (vanilla vetoes natural spawns in stopped land, and its caps are per category).
  - Every 40 ticks per player in a stopping dimension, at most once per roll (45-90 s): 4 standable spots 16-48 blocks
    out (from up to 32 samples), each spawns with `faunaChance` x dread. In a rift's outer zone (dread 0.5) that's about
    40 % per roll.
  - `/stasis fauna PLAYER`: dread there, the roll and quiet timers, fauna within 64 blocks, and how many of 40 sample spots
    are standable and in dread.
  - Caps `faunaPerPlayer` (2) within 64 blocks, `faunaPerPlayerStorm` (3).
  - After an encounter (a fauna within 8 blocks) nothing new for `faunaQuietMinutes` (12.5, ±15 %), `faunaStormQuietMinutes` (2.5)
    in a storm. `Freezing.freeze` calls `froze`: the fauna within 48 blocks dissolve and the quiet starts (no camping statues).
  - A spawn is a Puppeteer a third of the time when a frozen mob is within 16 blocks of the spot, else Static.
  - Memory only (cooldowns); a fresh player's first roll waits 45 s. Spawn reason `MOB_SUMMONED`, outside the stopped-land veto.
- `TowerBlockEntity.faunaPressure`: fauna within 4 blocks outside a field add 0.5 each (at most 2) to `weatherFactor`,
  computed every 20 ticks with the weather (λ-Nero: they raise the draw like a storm, and can make it fail).

## Config (synced)
`fauna`, `faunaChance`, `faunaPerPlayer`, `faunaPerPlayerStorm`, `faunaQuietMinutes`, `faunaStormQuietMinutes`;
`stoppedLandEndermen` (time-stop.md).

## Tests
`fauna_dread`, `fauna_failing_field_lets_in`, `fauna_field_grows_over_them`,
`fauna_fades_without_dread`, `fauna_spawner_caps` (isolated; `FaunaSpawner.testSpot` puts the tries on the test floor),
`static_drains_suits`, `puppeteer_pours_into_a_frozen_mob`, `possessed_mob_stutters_and_lets_go`,
`possessed_mob_keeps_out_of_fields`, `possessed_mob_field_grows_over_it`, `possessed_mob_leaves_a_fresh_statue`,
`possessed_statue_taken`, `possessed_statue_damage_and_death`, `possessed_statue_owner_leaves`,
`possessed_statue_owner_dies_home|hunt`, `possessed_statue_field_grows_over_it`, `possessed_statue_save_load`,
`possessed_statue_climbs_out`, `possessed_statue_keeps_out_of_fields` (all isolated; every possessed-statue test ends
with `gearAccountedFor`: exactly one holder of the owner's gear, every item and the xp), `fauna_field_is_a_wall` (isolated: a freeze in a neighbouring test dissolves fauna within 48 blocks), `fauna_press_on_the_shield`, `fauna_leave_a_fresh_statue`.
Dread in tests comes from `Pockets.TEST` / `Storms.TEST` boxes (the test deep seed is 0).
