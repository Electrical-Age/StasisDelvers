# Overview

Stasis Delvers is a NeoForge mod for Minecraft 26.3, built as a companion to Electrical Age (EA).
In the stopped dimensions (config `stoppedDimensions`; the default is the overworld, the Nether and the End), time stands still.
It runs only inside **fields**, which are kept up by EA-powered **towers**. Players move through stopped land
in battery-powered **suits**. Rifts, deeps, storms and strata provide the danger and the loot.

Design notes live outside the repo, in `/var/lib/mclab/agent/notes/` (`timestop/`, `rifts/`, `bugreport/`, `quality/`).
Javadocs often cite them as `notes/...`.

## Source layout

| Path | What |
|---|---|
| `src/main/java/org/erisia/stasis/` | Common code: rules, saved data, blocks, items, worldgen, networking |
| `src/main/java/org/erisia/stasis/client/` | Client only: post effects, renderers, sounds, console screen |
| `src/main/java/org/erisia/stasis/client/report/` | The F7 bug-report tool |
| `src/main/java/org/erisia/stasis/mixin/` | Required mixins (gameplay); `mixin/client/` holds the optional cosmetic ones; `mixin/xaero/` the Xaero overlay |
| `src/main/java/org/erisia/stasis/compat/xaero/` | Stopped-land highlighters for Xaero's maps |
| `src/main/java/org/erisia/stasis/gametest/StasisGameTests.java` | Every GameTest (one file) |
| `src/test/java/org/erisia/stasis/` | JUnit 5 + jqwik unit and property tests |
| `src/main/resources/assets/stasisdelvers/shaders/post/stasis.fsh` | The main post shader (mist, strata, desaturation, shield) |
| `src/main/resources/META-INF/accesstransformer.cfg` | Access transformers (they replaced the accessor mixins) |
| `src/main/templates/META-INF/neoforge.mods.toml` | Mod metadata, the 3 mixin configs, the AT, the dependency on `eln` |
| `towergen/` | Pure-Java tower generator, compiled into the mod (no MC dependency) |
| `towerview/`, `towerweb/` | Desktop viewer and TeaVM browser build of towergen |
| `tools/towerlib/` | Offline Python builder of the tower library |
| `tools/spawn/`, `tools/structures/` | Scripts for the spawn and ruin templates |
| `script/` | Asset and test-structure generators (`gen_test_structures.py` etc.) |

## Entry points
- `StasisDelvers` (`@Mod`): registers the synced config (`StasisConfig`), every NeoForge event listener,
  `StasisRegistry`, `StasisNet`, `EndSites`, `RuinedCottages`, `NodeFreezer.install()` and the GameTests.
  The listener list in its constructor is the fastest map of which class reacts to what.
- `client/StasisClient` (`@Mod(dist = CLIENT)`): sets every hook that common code calls back into:
  - `StasisNet` sinks, `ClientFields.onChange`, `TowerClientHooks.onUpdate/onRemove`, `Flames.clientResolver`
  - post effects, renderers, `FrozenSprites`, the console and the bug-report key
  - No common class imports client code.
- `StasisCommands`: `/stasis field|at|tower|deeps|rifts|storm|strata|status|spread|savetemplate ...` (op, for testing).

## Subsystems (one page each)
- [time-stop.md](time-stop.md): fields, `rateAt`, the tick gate, scheduled ticks. Everything else builds on it.
- [players-suits.md](players-suits.md): freezing, statues, suits, capacitors, per-player tick rate.
- [ea-integration.md](ea-integration.md): `NodeFreezer`, the tower yard, the build dependency on the EA jar.
- [towers.md](towers.md): `TowerBlockEntity` state machine, power formulas, chunk tickets.
- [towergen.md](towergen.md): the tower generator pipeline, plus towerview and towerweb.
- [rifts-storms.md](rifts-storms.md): drifting and static deeps, rifts, storms, crystals.
- [deeps-strata-worldgen.md](deeps-strata-worldgen.md): `DeepFeature`, strata, gates, ruins, end sites, the deep seed.
- [spawn-cottage.md](spawn-cottage.md): the spawn site placed once per world.
- [spreading-mode.md](spreading-mode.md): the `spreading` game mode: the world starts running and the stillness
  spreads from the rifts over online time (`SpreadMap`, `Spread`).
- [client-rendering.md](client-rendering.md): post chain, data-texture slots, frozen textures, tower meshes.
- [networking.md](networking.md): `StasisNet` payloads, protocol version.
- [mixins.md](mixins.md): the 25 mixins, the required and cosmetic configs, `CosmeticPlugin`.
- [bug-reports.md](bug-reports.md): the F7 pipeline, `Redactor`, `ReportResponder`.
- [testing.md](testing.md): unit, property and GameTests, the known flaky ones, how to run them.

## Cross-cutting facts
- **One rule:** `Stasis.rateAt` gives the rate in [0, 1]. Stopped means rate 0. Nearly every gameplay
  hook asks it, or `rateAtSteady`, which ignores eddies, or `rateAtLand` (meshing, flame light), which also
  ignores drifting deeps over running spread land.
- **Both sides compute the world.** The server sends the fields, the deep seed, gate rifts and the storm
  override. The client recomputes everything else (deeps, rifts, storms, pockets, strata) from the seed, the
  synced config and game time. Java code mirrored in GLSL must stay mirrored: `Noise` against `stasis.fsh`,
  and `Field.collapseScore` against its shader `collapseScore`.
- **Saved data** (per level, `SavedDataType`): `stasisdelvers:fields` (`FieldData`), `gutters` (`Flames`),
  `rift_crystals` (`Crystals`), `storm_crystals` (`StormCrystals`), `rift_gates` (`Gates`),
  `storm_override` (`Storms.Forced`); overworld only: `spread_map`, `spread_clock` (`Spread`). Block-entity NBT: towers (`TowerBlockEntity`, `TowerStructure`).
  Entity NBT: statues (`Statue`). Player persisted NBT: `stasisdelvers_starter_suit`, `stasisdelvers_map_desk`.
- **Memory only, lost on restart:**
  - `Freezing` states and login grace
  - `Capacitors.SHARD_USED`
  - `PlayerRates.SENT`
  - `NewChunkGrace.GRACE`
  - `NodeFreezer.FROZEN`
  - `Tickets.FORCED`, which is rebuilt from NeoForge's saved tickets at level load
- **Config:** `StasisConfig` is SYNCED. Values are cached in static volatiles by `refresh()`.
  Some keys that look like worldgen-only keys are also read at runtime (see deeps-strata-worldgen.md).
  The client-only config is `client/report/ReportConfig` (`stasisdelvers-client.toml`).
