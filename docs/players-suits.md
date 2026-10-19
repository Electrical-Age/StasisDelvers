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
     deeps and mist, times (1 + missing pieces). Unprotected: +1 per tick (capped below a statue in an eddy).
  5. Suited but in deep weather with a Delver suit or missing pieces: the freeze "creeps" at 0.25 per missing
     piece; `Capacitors.absorb` can take it.
  6. Out of danger, the freeze recovers 3 per tick.
  7. Side effects: the movement slow (`SLOW` modifier), Mining Fatigue for partial suits, action-bar messages
     (none for deeps and rifts, by design), `StasisNet.sendFreeze`.
  8. At `freezeTicks` (default 100) it calls `freeze`, which makes a `Statue`.
- Grace: `grace(PlayerEvent)` (login, respawn, dimension change; `graceTicks`, default 200; login grace in
  stopped land at most once per 30 min, `LOGIN_GRACE_EVERY`), `graceIfStopped`
  (when a field shrinks or goes), `LOGIN_GRACE`. `respawnPosition`: an unprotected player whose bed is in stopped
  land respawns at world spawn if time runs there (no death loop).
- `Statue.java`: an entity holding the inventory and XP. Unhurtable, never despawns. The owner (or a creative
  player) right-clicks to get everything back. Rendered by `client/StatueRenderer.java`.
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
- `StarterKit.java`: a full charged Delver suit + EA flashlight on first join (player NBT `stasisdelvers_starter_suit`). None in spreading mode.
- `PlayerRates.java`: per-player tick rate in a slow field.
  - Sends vanilla's ticking-state packet at 20·rate.
  - `miningClock` (EntityTickEvent.Pre, LOWEST) and `attackClock` (PlayerTickEvent.Pre) hold back
    `ServerPlayerGameMode.gameTicks` and `LivingEntity.attackStrengthTicker` (made public by
    `accesstransformer.cfg`) in skipped ticks.
  - `LivingEntityMixin` does the same for item use.
  - A full suit means rate 1.
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
- A suit protects only when every worn piece has charge. Tests: `empty_suit_freezes`, `delver_suit_protects`,
  `stasis_armor_protects`.
- `Suits.available` never counts batteries that can't discharge (rift capacitors).
  Tests: `SuitsTest.bugB_aCapacitorIsNotPower`, `drainingTakesWhatAvailablePromised`, `drainRespectsLimits`.
- Rifts are never protected (Baughn's rule). Capacitors don't help there.
- The starter suit is given once per player, across deaths: `starter_suit_once`.
- Crafting keeps the charge: `suit_craft_keeps_charge`. Capacitors: `capacitor_takes_mist`.
- Slow-field player clocks: `suit_full_speed_in_slow_field`, `rate_player_eats_slowly`.

## Gotchas
- `Freezing.tick` runs `Suits.tick` itself, which charges on its own once-a-second cadence.
- No messages about deeps, rifts or eddies, on purpose (fear of the unknown). The `debugMessages` config
  turns on diagnostics.
- `Freezing.onBreakSpeed`: the EA mining drill ignores Mining Fatigue in stopped land.
