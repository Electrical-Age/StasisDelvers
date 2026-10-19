# Stasis Delvers

Stasis Delvers is a NeoForge mod for Minecraft 26.3 and a companion to Electrical Age (EA).
- In the stopped dimensions, time stands still everywhere except inside fields.
- Fields are kept up by EA-powered towers; their mesh comes from a generated lattice (`towergen`).
- Players cross stopped land in battery suits.
- Rifts, deeps, storms and strata are the hazards and the loot.
- The design is Baughn's. Design notes live outside the repo, in `/var/lib/mclab/agent/notes/`
  (`timestop/`, `rifts/`, `bugreport/`, `quality/`).

## Build and test
- Use the wrapper: `/var/lib/mclab/agent/tools/stasis-build.sh [task...]`.
  - It sets JDK 25 and `GRADLE_USER_HOME=/var/lib/mclab/agent/mods/gradle-home`.
  - It logs to `/var/lib/mclab/agent/stasis-build.log` and prints only the exit code.
- Common tasks:
  - `build`: compile, unit tests, `:towergen:test`, jar in `build/libs/`
  - `test`: JUnit 5 + jqwik
  - `runGameTestServer`: every GameTest, headless. The result is at the end of `run/logs/latest.log`.
  - `:towergen:test`, `:towergen:slowTest`
  - `:towerweb:dist`
- Before pushing, run `build` and `runGameTestServer` (there is no CI). Details: `docs/testing.md`.

## The EA jar dependency
- Stasis compiles against a locally built EA jar and links to EA classes directly. The path is the Gradle
  property `eln_jar`; the default is `/var/lib/mclab/agent/eln/port26/build/libs/eln-26.3-0.1.0-port.jar`
  (EA `ports/26.3`, checked out at `/var/lib/mclab/agent/eln/port26`).
  Override it with `-Peln_jar=/path/to.jar`.
- `copyElnToRun` copies the jar into `run/mods` before every `run*` task.
- When EA's API or its Stasis hooks change (`NodeBase.simulationVeto`, `LampItem.clientBeam`), build EA first,
  then Stasis, and ship both together. See `docs/ea-integration.md`.

## Commits and pushes
- Commit as `git -c user.name=tsugumi-lab -c user.email=tsugumi-lab@erisia.local commit`.
- End every message with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Subject: a plain-English sentence saying what changed, with no type prefix (for example "Cache rift
  sections per deep seed"). Body: why, and what was wrong before. Credit who asked or reported
  (Baughn, λ-Nero, AdventurAgent).
- Push with `git push origin HEAD:main` (origin: github.com/Electrical-Age/StasisDelvers), and only when asked to.

## Conventions
- One rule for time: `Stasis.rateAt` / `rateAtSteady`. Stopped means rate 0. Don't add new "is it stopped"
  tests elsewhere.
- Both sides compute the world from synced state (fields, deep seed, gates, storm override, config). Java mirrored
  in GLSL (`Noise`, `Field.collapseScore`, the data-texture slots, `MAX_FIELDS` = 13) must change on both sides.
- Prefer, in this order: a NeoForge event, an access transformer (`src/main/resources/META-INF/accesstransformer.cfg`),
  then a mixin. Gameplay mixins go in the required config; look-only ones in the cosmetic config.
  See `docs/mixins.md`.
- Common code never imports client classes. Client hooks are set in `client/StasisClient`.
- A payload change needs a protocol bump (`StasisNet.register`, `registrar("10")`) and a `NetCodecTest` case.
- Saved state needs a codec default for older saves (`optionalFieldOf`, `getXOr`).
- Off the main thread (light engine, worldgen, chunk meshing) never call `FieldData.get`. Use `peek`,
  `peekDeepSeed`, `Deeps.seed`.
- Behaviour changes get a GameTest in `StasisGameTests` (or a unit or property test when there's no world).
- Comments and javadocs say why and cite who asked (Baughn, λ-Nero, ...). Keep them in line with the code.

## docs/ index
- `docs/overview.md`: what the mod is, the source layout, entry points, saved and memory-only state.
- `docs/time-stop.md`: fields, `Stasis.rateAt`, `FieldData`, the tick gate, scheduled and random ticks.
- `docs/players-suits.md`: freezing, statues, suits, capacitors, the per-player tick rate.
- `docs/ea-integration.md`: `NodeFreezer`, the tower yard, EA hooks, the `eln_jar` build dependency.
- `docs/towers.md`: the `TowerBlockEntity` state machine, power and store formulas, chunk tickets, the console.
- `docs/towergen.md`: the generator pipeline, towerview, towerweb, and why server, client and web build the same tower.
- `docs/rifts-storms.md`: drifting and static deeps, rifts, storms (tau never jumps), pockets, crystals.
- `docs/deeps-strata-worldgen.md`: the per-level random deep seed, `DeepFeature`, strata, gates, ruins, end sites.
- `docs/spawn-cottage.md`: spawn site placement, the EA node data, spawn protection use.
- `docs/client-rendering.md`: the post chain, data-texture slot layout, `MAX_FIELDS`, frozen textures, tower meshes.
- `docs/networking.md`: the `StasisNet` payloads both ways, the protocol version, size limits.
- `docs/mixins.md`: each of the 25 mixins (target, why no event), the required and cosmetic configs, `CosmeticPlugin`.
- `docs/bug-reports.md`: the F7 pipeline, `Redactor`, `ReportResponder`, retention.
- `docs/testing.md`: unit, property and GameTests, flaky and soft tests, gaps, how to run them.
