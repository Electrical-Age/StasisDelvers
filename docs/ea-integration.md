# Electrical Age integration

Paths are relative to `src/main/java/org/erisia/stasis/`. EA is the `ports/26.3` port of
[Electrical-Age/ElectricalAge](https://github.com/Electrical-Age/ElectricalAge/tree/ports/26.3), checked out
locally at `/var/lib/mclab/agent/eln/port26`.

## Build dependency
- `build.gradle`: `def elnJar = file(project.findProperty('eln_jar') ?: '/var/lib/mclab/agent/eln/port26/build/libs/eln-26.3-0.1.0-port.jar')`.
  - `compileOnly files(elnJar)` and `testImplementation files(elnJar)`: the unit-test game loads the mod,
    which requires `eln`.
  - The `copyElnToRun` task copies the jar into `run/mods`. Every `run*` task depends on it, so dev runs and
    GameTests load EA as a real mod jar, with its jarJar'd Kotlin and commons-math.
  - To use another EA build: `./gradlew build -Peln_jar=/path/to/eln.jar`.
- `src/main/templates/META-INF/neoforge.mods.toml`: dependency `eln`, required, `[26.3-0.1.0-port,)`,
  ordering AFTER.
- The mod links directly against EA classes; there is no API layer or reflection. When EA's API changes,
  rebuild EA first, then Stasis, and ship both together.

## EA hooks that exist for Stasis (in EA's code)
- `mods.eln.node.NodeBase.simulationVeto`: a predicate EA asks before a node joins the simulation.
  `NodeFreezer.install()` sets it to `n -> !runs(n)`, so a frozen node never slips back in.
- `mods.eln.node.NodeBase.timeScale` (EA 01102ad9): how fast time runs at a node. `NodeFreezer.install()` sets it to
  `Stasis.speedAt` at the node, so EA's processing machines (`ElectricalMachineElement`) in an acceleration field draw
  that much more power and finish that much sooner (λ-Nero). EA's block entities are never multi-ticked
  (`Stasis.multiTickExempt`). Test: `ea_time_scale_hook` (and EA's own macerator test).
- `mods.eln.item.electricalitem.LampItem.clientBeam`: set in `StasisDelvers` so that EA lights only the
  holder's block. `client/FlashlightPost.java` draws the beam as a post effect, turned off under Iris.

## NodeFreezer (`NodeFreezer.java`)
- Every 10 server ticks (`ServerTickEvent.Post`), `pass()` walks `NodeManager.instance.getNodes()`:
  - When `runs(n)` is false and the node is added: `disconnect()` + `visualConnect()`. Frozen cables still
    *look* connected.
  - When `runs(n)` is true and the node is not added: `connect()`.
  - It works from EA's own `isAdded()`, not from a list of its own, because EA reconnects nodes from many places.
- `runs(n)` is `Stasis.isRunningSteady(pos) || (Stasis.nearTower(pos) && infrastructure(n))`.
  - It uses the steady rate: eddies never make networks flicker.
- `infrastructure(n)` defines the **yard**: within `Stasis.TOWER_YARD` = 6 blocks per axis of a lance core, or of a
  formed tower core while config `towerYard` is on (default off since 2026-10-21, Baughn: nothing pointed to it and it
  was awkward; the rift quern's field starts machinery instead, docs/quern.md; a test can set
  `TowerBlockEntity.testYard`), these keep running in stopped land:
  - `TowerSignalNode`
  - `TransparentNode` batteries (`BatteryElement`) and EA-to-FE exporters (`EnergyConverterElnToOtherElement`)
  - `VoxelNode`s made only of `CablePart`
  - `SixNode`s made only of wiring: cables, switches, relays, fuses, diodes, ground
  - Machinery never counts as infrastructure, by design (not even a heat turbine).
  - A rift lance's core (`LanceBlockEntity`, 1x1x1) has the same yard (lance.md): its exporter and cables run while it
    breaches or holds a gate in stopped land. It is exempt from stopping too (`Stasis.exemptBlockEntity`) and takes FE
    like a tower (energy capability registered in `StasisRegistry`).
- A cable that crosses into stopped land is cut there, so a field's power must come from inside it.
- `FROZEN` is memory only; `isFrozen(n)` is for bug reports and tests.

## Other EA touch points
- `Stasis.exemptBlockEntity`: EA `LightBlockEntity` keeps ticking, so a flashlight's light times out.
- `TowerBlockEntity`: the store is a NeoForge energy handler (`SimpleEnergyHandler`). EA's exporter pushes FE
  into it inside EA's simulation (`FE_PER_J` = 4/3, EA's ratio).
- `TowerSignalNode.java`: a bare `NodeBase` at the core's master, with one gate input (left of the console) and one
  gate output (right).
  - Input 0-50 V sets the target radius; the output gives the charge share as 0-50 V.
  - Not saved: `TowerBlockEntity` recreates it.
- Suits:
  - `DelverSuitItem` and `CapacitorItem` implement `IItemEnergyBattery`, so EA chargers fill them.
  - `StasisArmorItem` extends EA's `ElectricalArmor`.
  - `Suits` drains carried EA batteries.
- `Freezing.onBreakSpeed` and `StilledLiquids`: the EA `ElectricalPickaxe` (mining drill) ignores Mining
  Fatigue and digs stilled liquids.
- `StarterKit`: an EA small flashlight (`FLASHLIGHT` = 7744, a six-node item id).
- `SpawnCottage.placeNodes`: recreates EA nodes from `data/stasisdelvers/spawn/spawn_cottage_eln.nbt`,
  because EA keeps node data outside the chunks.
- `SpawnUse.java` (+ `SpawnUseMixin`): EA blocks stay usable under spawn protection.
- Bug reports: `client/report/ElnReport.java` (client GUI and look target) and `ReportResponder` (nodes near
  the player).

## Invariants and tests
- Nodes outside fields stop; inside, they run: `ea_nodes_stop_outside`.
- Frozen cables keep their visual connections: `ea_frozen_cables_look_connected`.
- An EA lamp lights inside a field: `ea_lamp_lights_in_a_field`.
- The tower yard keeps cables and batteries running with the field down: `ea_tower_yard_keeps_cables`.
- The GameTest server runs only `stasisdelvers:*` tests (`--tests` in `build.gradle`). EA's own GameTests
  are registered too, but can't run in stopped time.

## Gotchas
- Stasis is compiled against the jar at `eln_jar`. If a server or client runs an older EA than that, the
  first use of a missing hook throws `NoSuchMethodError` / `NoSuchFieldError` at runtime. The version range in
  `neoforge.mods.toml` won't catch this, because the port's version string doesn't change.
- `nearTower` only looks at loaded chunks: a core in an unloaded chunk gives no yard. Towers keep their own
  chunks loaded with tickets while they run (towers.md), but a dead tower's chunk may be unloaded.
- `TowerBlockEntity.setRemoved` wraps `node.onBreakBlock()` in `catch (LinkageError)` for runs without EA.
