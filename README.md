# Stasis Delvers

A Minecraft 26.3 (NeoForge) mod by Baughn's design: **time stands still** everywhere outside fields kept running
by [Electrical Age](https://github.com/Electrical-Age/ElectricalAge/tree/ports/26.3) power.

- Outside a field nothing ticks: entities, block entities, redstone, fluids, crops, mob spawning. Endermen, the
  dragon and the warden are unaffected. The spawn area (3x3 chunks) always runs.
- Fields are spheres. A block counts as inside only if all of it is.
- A player in stopped land without a charged suit gets a short warning, then freezes into a statue that keeps their
  inventory, and wakes at their bed.
- Suits: the Delver suit (cheap, no armour, battery powered) and stasis-proof E-Coal armour (EA's E-Coal plus time
  protection). Both charge in EA battery chargers. Suited players dig with Mining Fatigue I in stopped land (EA's mining
  drill ignores it).
- Stopped water and lava are solid; stopped lava hurts like magma.

Work in progress (V1): EA-powered field towers, the shield and desaturation visuals, collapse on power loss.

Build: `./gradlew build` (Java 25); the EA jar path is the `eln_jar` property. Tests: `./gradlew runGameTestServer`.
