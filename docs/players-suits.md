# Players: freezing, statues, suits, player tick rate

Paths are relative to `src/main/java/org/erisia/stasis/`.

## Classes
- `Freezing.java`: the per-player state machine, run from `PlayerTickEvent.Post` (`tick(ServerPlayer)`, public
  so tests can drive mock players). Each tick it:
  1. Works out `stopped` (`Stasis.isRunning` at the feet), `eddy` (stopped, but `rateAtSteady` > 0), the
     static-deep `zone` (0 / 1 outer / 2 rift), and the strata pocket weather (`Pockets.at`). It also reads
     storms (`Storms.at`) and drifting deeps (`Deeps.at`).
  2. In a rift (zone 2): +2 freeze ticks per tick. Touching the eye (`touchesCore`) makes a statue at once.
     Nothing protects.
  3. A hold (`heldUntil`, at most `HOLD_TICKS` = 2400) keeps a Delver-suited player at freezeTicks-1 while passing
     weather (drifting deep, storm mist) is over them. No statue. In spreading mode a drifting deep over running
     land stops it (`Stasis.rateAt`); while Baughn hasn't decided, `SPREAD_DEEP_HOLDS` (true) gives an unsuited
     player caught there the same hold, without suit messages (`quietHold`); false gives the normal freeze.
  4. Otherwise `isProtected(p, factor, capacitors)` decides. The drain factor is 3 in the outer zone and 2 in
     deeps and mist, times (1 + missing pieces), times (1 + Static feeding on the suit: `RiftStatic.feeding`, fauna.md). Unprotected: +1 per tick (capped below a statue in an eddy).
  5. Suited but in deep weather with a Delver suit or missing pieces: the freeze "creeps" at 0.25 per missing
     piece; `Capacitors.absorb` can take it.
  6. Out of danger, the freeze recovers 3 per tick.
  7. Side effects: the movement slow (`SLOW` modifier), Mining Fatigue for partial suits, action-bar messages
     (none for deeps and rifts, by design), `StasisNet.sendFreeze`.
  8. At `freezeTicks` (default 100) it calls `freeze`, which makes a `Statue`.
- Grace: `grace(PlayerEvent)` (login, respawn, dimension change; `graceTicks`, default 200; login grace in
  stopped land at most once per 30 min, `LOGIN_GRACE_EVERY`), `graceIfStopped`
  (when a field shrinks or goes, on the action bar; when the spreading front passes over, in chat, since the grace
  countdown takes the bar), `LOGIN_GRACE`. In running spread land `FrontWarnings` has the action bar before the
  front arrives (spreading-mode.md); Freezing's messages win wherever time doesn't run. `respawnPosition`: an unprotected player whose bed is in stopped
  land respawns by `SafeRespawn.choose` (Baughn 2026-10-21): world spawn if time runs there, else the nearest working
  field above ground (radius 8+, not failing or collapsing: a tower's; the highest standable spot under the open sky,
  nearest its centre), else any field with room to stand, else the nearest lull's core (Lulls, rifts-storms.md) within
  4 km; the note after the respawn says which. Tests: `respawn_into_a_field`, `lull_runs_time`.
- `Statue.java`: an entity holding the inventory and XP. Unhurtable, never despawns. The owner (or a creative
  player) right-clicks to get everything back. Rendered by `client/StatueRenderer.java`.
- `Remains.java`: every player death leaves a `Statue` (Baughn: he died in running land and turned into items).
  `LivingDropsEvent` at LOWEST puts the drops (Curios and other mods' additions too) into it and clears them;
  `LivingExperienceDropEvent` drops no orbs; the statue takes what vanilla would drop (`Statue.points`: level x 7, at
  most 100; Baughn), and so does a freeze's statue. A freeze's statue is registered in
  `FREEZING` while the death runs, so what the death still drops joins it. keepInventory: no remains. The owner gets the
  coordinates at respawn (memory only).
  - Statues **fall** where time runs (`Statue.tick` runs only there: `fallen`, synced and saved), lie on their back
    (`StatueRenderer`, centred on the entity: a 1.6 x 0.5 hitbox), and stay lying if time stops again. Only the server
    moves them (4-tick updates). Never below the world floor (`onBelowWorld`), no portals,
    fire-immune.
- `Suits.java`:
  - `problem` (why a suit doesn't protect), `share` (each worn piece carries 4/worn of the draw), `missing`
  - `tick` (once a second): tops up the pieces from carried batteries by water-filling; the stasis suit
    repairs itself (`REPAIR_J`)
  - `carried`/`worn`/`available`/`drain`/`takeCarried`: also used by the tower console's feed
  - `protect(p, factor)`, `delverOnly`, `protectsWithoutDraw`
- `TimeSuit.java`: the interface with `stasisDraw` (W). Energy lives in item custom data `stasisdelvers_energy`,
  so EA chargers (`IItemEnergyBattery`) and the suit share one number.
- `DelverSuitItem.java`: a cheap suit with no armour, draw 10 W per piece (`StasisRegistry`).
  `StasisArmorItem.java`: EA E-Coal armour (`ElectricalArmor`) that also protects.
  `SuitUpgradeRecipe.java`: Delver to stasis piece, keeping the charge (recipe type `stasisdelvers:suit_upgrade`).
- `Capacitors.java` / `CapacitorItem.java`: rift capacitors absorb the mist surcharge and the Delver creep
  (`absorb`, `drawFactor`). A raw shard takes half and crumbles after `SHARD_SECONDS`. EA chargers vent them.
- `BatteryBeltItem.java` / `BeltMenu.java` (client `BeltScreen`): the battery belt (Baughn 2026-10-27), a Curios belt with
  four pockets (data component `minecraft:container`) for EA portable batteries/condensators and rift capacitors. Worn
  (`CuriosCompat.belt`), its batteries join `Suits.carried` and its capacitors `Capacitors.absorb`; carried, it does
  nothing for the suit. As an `IItemEnergyBattery` it stands for its contents, so EA chargers charge it. The HUD
  (`SuitGauge`) stacks a green bar for its batteries under the suit bar. GameTests `battery_belt_*` (a test hook,
  `TEST_WORN`, stands in for Curios, which the test server doesn't load).
- `Suits.battery(stack)`: the item battery behind a stack, the item or an EA item's descriptor. EA portable batteries
  are `ElnItem`s whose descriptor is the battery, so carried ones never fed a suit before it (2026-10-27).
- `StarterKit.java`: a full charged Delver suit + EA flashlight on first join (player NBT `stasisdelvers_starter_suit`). None in spreading mode.
- `PlayerRates.java`: per-player tick rate in a slow or acceleration field.
  - Sends vanilla's ticking-state packet at 20·rate.
  - `miningClock` (EntityTickEvent.Pre, LOWEST) and `attackClock` (PlayerTickEvent.Pre) hold back
    `ServerPlayerGameMode.gameTicks` and `LivingEntity.attackStrengthTicker` (made public by
    `accesstransformer.cfg`) in skipped ticks.
  - `LivingEntityMixin` does the same for item use.
  - A full suit means rate 1.
  - Acceleration fields (λ-Nero): rate up to `FAST_CAP` = 3 (`Stasis.speedAt`, so a 10x field runs blocks and mobs at
    10x and players at 3x); a whole time suit or a chronal anchor (`StasisRegistry.CHRONAL_ANCHOR`, a Curios charm or
    the off hand) keeps 1x; a freezing player stays at the freeze's rate. A fast player's client stays at 20 TPS
    (vanilla's `Minecraft.getTickTargetMillis` never ticks a client faster; AdventurAgent saw no speed-up at "60 TPS"):
    `fast()` adds attribute modifiers `stasisdelvers:accelerated` (r - 1, multiplied total) to movement speed, block
    break speed and attack speed, and `LivingEntityMixin` runs item use `ticksThisTurn(p)` times. Test: `rate_player_fast`.
- Client side: `client/SuitGauge.java` (HUD), `client/SuitArm.java` (first-person arm), and the freeze
  warning in `stasis.fsh` (slot 23, fed by `StasisNet.Freeze`).

## Persistent state
- Item custom data `stasisdelvers_energy` (suits, capacitors).
- `Statue` entity NBT: `owner`, `owner_name`, `items`, `xp`.
- Player persisted NBT: `stasisdelvers_starter_suit`.
- Memory only: `Freezing.STATES`, `LOGIN_GRACE`, `REDIRECTED`, `Capacitors.SHARD_USED`, `PlayerRates.SENT`.
  A restart resets freeze progress, which is harmless because login grants grace anyway.

## Client / server split
- The server decides everything. The client only sees freeze progress (`Freeze` payload) and its tick rate.
- `PlayerRates` floors the client at 1 TPS (`Math.max(1.0, tickrate * r)`), while a field can run at 0.001.
  Below 0.05 the player's client and the blocks around them disagree.

## Invariants (and the tests that guard them)
- Ticks are counted, not wall time, so lag can't skip the warning. Tests: `player_freezes_outside`,
  `player_safe_inside`, `player_grace_after_login`, `field_removed_gives_grace`.
- In the plain Grey a chest piece is enough. Each worn piece carries 4/worn of the draw, so a partial suit is
  never cheaper. Tests: `chest_piece_alone_protects`, `chest_piece_alone_in_mist`, `partial_suit_freezes`.
- A suit protects when its chest piece and the other charged pieces can pay the draw (`Suits.working`): an empty piece
  counts as not worn (its share moves to the others, and it counts as missing for the deeps and Mining Fatigue;
  Baughn: an empty piece should not be worse than not wearing it). An empty chest piece: no suit. Tests:
  `empty_suit_freezes`, `empty_piece_counts_as_not_worn`, `delver_suit_protects`, `stasis_armor_protects`.
- `Suits.available` never counts batteries that can't discharge (rift capacitors).
  Tests: `SuitsTest.bugB_aCapacitorIsNotPower`, `drainingTakesWhatAvailablePromised`, `drainRespectsLimits`.
- Rifts are never protected (Baughn's rule). Capacitors don't help there. The exceptions are doors, not protection:
  an open lance gate's disc and the part of its rift outside the 25 % it is held to, and a return rift's body
  (`Lance.spares`: the outer zone's rules there; lance.md). `Lance.step` runs first in `Freezing.tick`, so stepping into
  a disc is a trip, not the eye's statue.
- Remains: `remains_where_time_runs` (items and vanilla's share of experience in the statue, nothing on the ground, it lies on the
  floor), `remains_in_stopped_land_fall_when_time_runs`, `remains_fall_when_support_goes`, `remains_not_with_keep_inventory`.
- The starter suit is given once per player, across deaths: `starter_suit_once`. In spreading mode there is no suit:
  one stability compass instead (`StarterKit.COMPASS_TAG`), `stability_compass_first_join`.
- Crafting keeps the charge: `suit_craft_keeps_charge`. Capacitors: `capacitor_takes_mist`.
- Slow-field player clocks: `suit_full_speed_in_slow_field`, `rate_player_eats_slowly`.

## Gotchas
- `Freezing.tick` runs `Suits.tick` itself, which charges on its own once-a-second cadence.
- No messages about deeps, rifts or eddies, on purpose (fear of the unknown). The `debugMessages` config
  turns on diagnostics.
- `Freezing.onBreakSpeed`: the EA mining drill ignores Mining Fatigue in stopped land.

## Advancements (`GuideAdvancements`, `DeathAdvancements`)
- The "Stasis Delvers" tab (`advancement/guide`, hints by λ-Nero 2026-10-21, puzzle-style on purpose): A Sickness (join),
  The Cure (any Delver piece), Sealed Suit (all four, charged), Fortified Suit (all four Stasis-Proof), Shards of Time
  (a shard or charged capacitor), Safe Harbour (inside a tower's field as it forms), The Last Chance (in a ruined cottage
  or an End tower site, or within 8 of a ruin's tower core: λ-Nero, some ruins are just the tower), Breach (within 128 of a lance gate as it latches). Code grants the impossible-trigger ones.
- The "Stopped Time" deaths tab, hidden: the possessed-animal deaths, Self Made, Low Battery, Saw the end of the
  universe (any full freeze), It's Damp (froze in mist, a deep, a rift's outer zone or a storm with a partial suit),
  Lights Out (inside a tower's field as it collapses), You were then, and now you- (killed your own possessed statue).
- Not yet: Tick Tock (the time-rate bauble from λ-Nero's to-do). Test: `guide_advancements_load`.


## Rift pendants and exemption (2026-10-22)
- `RiftPendantItem` (Curios necklace slot, `CuriosCompat`; Curios is optional): a rift shard or charged rift shard on a
  string. It stands in for the chest piece when none with charge is worn (`Suits.piece`), so alone it is a Delver
  chest piece without armour. It holds 10 or 30 minutes of a lone piece's draw (4 x 10 W), never recharges, and
  crumbles to its string when spent. Right-click equips it.
- `/stasis exempt <player> on|off` (`Exempt`, SavedData `stasisdelvers:exempt`): the mechanic leaves that player
  alone: no freezing anywhere (`Freezing.tick` returns early) and full speed in slow fields (`PlayerRates.rate`).
