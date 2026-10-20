# Testing

There are three layers. All of them run through Gradle; the wrapper script below sets the JDK and the Gradle home.

## Running
- Wrapper: `/var/lib/mclab/agent/tools/stasis-build.sh [gradle task...]` (default `build`).
  - It sets `JAVA_HOME` (JDK 25 from the nix store) and `GRADLE_USER_HOME=/var/lib/mclab/agent/mods/gradle-home`.
  - It writes the whole log to `/var/lib/mclab/agent/stasis-build.log` and prints only `exit N; log: ...`.
    Read the log for failures.
- `stasis-build.sh build`: compiles, runs the unit tests (`test`) and `:towergen:test`, and builds the jar in
  `build/libs/`.
- `stasis-build.sh test`: unit and property tests of the mod only.
- `stasis-build.sh :towergen:test`, and `./gradlew :towergen:slowTest [-Dtowergen.random=N -Dtowergen.seed=S]`.
- `stasis-build.sh runGameTestServer`: every GameTest, headless. It exits non-zero if a required test fails.
  The results are at the end of `run/logs/latest.log` ("All N required tests passed"; 151 at HEAD).
- One GameTest by hand: `./gradlew runServer` (or `runClient`), then `/test run stasisdelvers:<name>`.
  `neoforge.enabledGameTestNamespaces` is set for every run config.

## 1. Unit and property tests (`src/test/java/org/erisia/stasis/`)
- JUnit 5 + jqwik 1.9.3. `build.gradle` `neoForge { unitTest { enable(); testedMod = ... } }` runs them with
  the game on the classpath and the mod loaded, so codecs and `BlockPos` work. The EA jar is on the test
  classpath because the mod requires `eln`.
- `src/test/resources/junit-platform.properties`: `jqwik.reporting.onlyfailures = true`. The jqwik failure
  database is `build/jqwik-database` (absolute path; the unit-test game runs in its own directory).
- `FieldTest.java`: containment over random stretched fields (`containsBlockIsAllEightCorners`,
  `containsBlockStraddlingTheCentre`, `bugA_westCornerOfACentreBlock`), `containsPointMatchesNorm`,
  `towardLandsOnTheScaledSurface`, `normSpanBoundsTheBlocksOfABox`, codec round trips, old-save defaults.
- `TickGateTest.java`: the `runs` count matches the rate; `dueTick` lands on the delay-th running tick;
  storm `tau` is monotone and continuous (also with overrides); `integral` is the integral of intensity; a
  storm stays in its block; the time-of-day wrap; the bed span.
- `NetCodecTest.java`: round trip of every `StasisNet` payload; `sentFieldsAgreeOnBlocks` (networking.md).
- `SpreadMapTest.java`: the spreading mode's map and clock (spreading-mode.md); `coherenceReport` and `buildTimes` only log
  (`coherenceReport` also writes the lab seed's ranks to `build/spread-lab-v2.ranks` for `tools/spread-render.py`).
- `SpreadTilesTest.java`: the client's rank tiles answer like the server's map (through the wire codec), uncovered
  cells run, and `SpreadMap.stilledSplit` (the shader's 32-bit arithmetic) equals the exact sum at every offset.
- `SuitsTest.java`: `drain` takes what `available` promised and respects its limits; `bugB_aCapacitorIsNotPower`.
- `LanceTest.java`: the lance's destinations and return chooser (L1); its costs and times, strength steps, the breach's
  state machine and the dormancy rule (L2; lance.md).
- `client/report/RedactorTest.java`: see bug-reports.md.

## 2. towergen (`towergen/src/test/java/org/erisia/stasis/towergen/`)
- `GoldenTest` (+ `Goldens`): stage-by-stage comparison with the Python goldens
  (`towergen/src/test/resources/p3a_goldens.json.gz`, `p3a_goldens_p3arule.json.gz`).
- `MeshTest`, `SolverTest`, `StayRuleTest`, `LastResortTest`, `LibraryBinTest`, `RoundTripPropertyTest`,
  `Hull3PropertyTest`. `SlowTest` is tagged `slow` and excluded from `test`. Details are in towergen.md.

## 3. GameTests (`src/main/java/org/erisia/stasis/gametest/StasisGameTests.java`)
- Registration: a static `CASES` map (name → `Spec(ticks, body, field, isolated)`), registered through a
  `TEST_FUNCTION` `DeferredRegister` and `RegisterGameTestsEvent` in `register(modBus)`.
  - 148 `CASES.put` calls, three of them in loops: 151 tests.
  - Each test gets the structure `stasisdelvers:floor_16x6x8` (`src/main/resources/data/stasisdelvers/structure/floor_16x6x8.nbt`,
    made by `script/gen_test_structures.py`) and `maxTicks = ticks + 40`.
  - `isolated` tests (towers, big fields) get their own environment `alone_<name>`, so they run in a batch
    of their own.
- Harness: `run()` lays the floor and, unless `field` is false, adds the default field (`R` = 4.2 at local
  3.5, 2.5, 4.5). Local x 0..6 is inside; x ≥ 9 is outside. `Checks` logs PASS/FAIL lines and runs cleanups.
  - Helpers: `survivalPlayer` (a real `ServerPlayer` on an EmbeddedChannel; `send` skips it), `wear`,
    `field`, `halfField`, `tower`, `eaSix`, `settledLight`.
- The `runGameTestServer` config (`build.gradle`):
  - `stasisdelvers.noSpawnArea=true`: no spawn area, and `Deeps.seed` is 0 unless a test sets
    `Deeps.testDeeps`
  - `--tests stasisdelvers:*`, because EA's own GameTests can't run in stopped time
- Test hooks in production code: `Spread.testInstall`/`testReset`/`testClock`/`testClockRestore`/`testServerState`/`testSecondsUntilStill`,
  `HardMode.testReset`/`testFlashed`, `HardRifts.testQueued`/`testReload`/`testChunks`, `PendingGates.testRoom`/`testReload`/`testForget`, `Gates.testForget`/`gateForTest`/`testRegister`, `Lance.testCooldown`/`testForget`/`testPad`/`testSpeed`, `LanceBlockEntity.testSupplyW`/`testSpeed`/`testStorm`/`testTick`, `HardMode.evictsOnLogin`, `Freezing.inGrace`, `EndBarrier.check`, `StasisConfig.spreadHoursForTests`, `FrontWarnings.check`/`describe`, `SpawnMove.testArm`, `Spread.Clock.testSpawnMove`, `TowerStructure.syncBuild`, `Pockets.TEST`/`TEST_STRATA`, `Storms.TEST`,
  `Deeps.testDeeps`, `DeepFeature.oreForTest`, `Crystals.FACE_SHARE`, `FieldData.testDeepSeed`, `NewChunkGrace.testsStarted`, and
  `TowerBlockEntity.test*`.
  - `StasisDelvers` registers the GameTests unconditionally, so they and these hooks ship in the
    production jar.
- Which test guards what: see the "Invariants" section of each subsystem page.

## Flaky or soft tests
These have failed intermittently before. Each was hardened, so a single failure is worth a rerun before
debugging:
- `furnace_cart_pushes_frozen_cart` (9164234: client position sync)
- `ruined_cottage_templates` (23d07eb: far test chunk didn't tick)
- Fauna tests made robust 2026-10-20: the wall now acts in the host's / possessed statue's own tick (a test saw one a tick
  inside a field); `possessed_statue_climbs_out` measures closeness (bouncing in a pit isn't progress) and suits the
  owner (the test outlasts the login grace)
- `eddies_stop_mobs`, `flames_*`, `tower_collapses_when_empty` (a15602d)
- `rift_crystals_grow` (5907f24: cross-chunk keys)
- `eddies_in_a_weak_field` (once over its 60 % pocket share, 3568 of 5457, when test positions moved; passed on rerun)
- `remains_*` (2026-10-20: ran beside `remains_not_with_keep_inventory`, whose world-wide keepInventory left them no drops;
  that test is isolated now)
- `spread_hard_mode_entry` (once, 2026-10-20: "the flash removes a dragon still alive": removed false; passed on 4 reruns)
- `lance_multiblock_binds` (once, 2026-10-20: "a boom away from the rift: unbound"; like `lance_breach_hold_relax`, a
  lance test whose geometry meets the test's random world position; passed on rerun)
- `spread_rates` (fixed 2026-10-20: the spawn column is a bilinear blend of its cell, ranked only >= split - 1, and lower
  neighbours; near a cell edge it stilled before a third. The test now picks P below the column's own value. Live, the spawn
  area holds spawn whatever the map says)
- `time_resumes` (new_chunk_grace's chunk grace reached its sand when the grid put both in one chunk;
  new_chunk_grace is isolated now)
- `spread_hard_mode_gate_rooms` (2026-10-20: about 1 run in 3; waits up to 1200 ticks now, 1 in 20; was: 29 of 30 chunks shattered, the last "loaded, no entities ticking";
  passed on rerun; not looked into)
- Lance tests, fixed 2026-10-20 (they had flaked since the remains work; the bisect pointed at chance, not remains):
  `lance_breach_hold_relax` picked a probe whose shape could lie past the breached quarter (now 0.095..0.24, six
  directions; one failure in ~30 runs since, with diagnostics in the message); `lance_multiblock_binds`' west boom could
  land over a natural rift of the test world (now: not bound to *this* rift); `lance_spreading_rules` found a real bug:
  an End arrival pad could sit under another island's blocks placed later (Lance.spot now clears every planned island)
- `rate_perf` only logs a timing.
- `rate_random_ticks` accepts a wide band.
- `flames_*` accept a random gutter.
- `tower_weather_raises_draw` has an escape for equal draws.
- `ruined_cottage_templates` falls back when no template is found.
- `eddies_stop_mobs` passes if the mob never ran.

## Gaps (no automated test)
- The serverbound handlers (`ConsoleSet`/`ConsoleFeed`/`ConsoleQuery`/`DigStilled` paths, `ReportResponder`).
- Most `StasisCommands` (only `field add` and `field rate`, through `rate_command`).
- `Tickets` across a restart, `TowerSignalNode`, `FieldSleep`, `MapDeskBlock`, `RiftBarometerBlock`,
  `RiftCompassItem`.
- Client rendering and the cosmetic mixins (checked by eye), the End wall's particles (`EndBarrierFx`), the End
  event's fog (`EndEventFog`), and the client's half of `EndBarrierMixin` (the feel at the wall: the server's `move` is
  tested).
- `EnderDragonFightMixin` (no exit portal, gateway or egg after a real dragon kill; no respawn): needs a dragon fight
  with its own dragon, so it is checked in the lab.
- There is no CI. Run `build` and `runGameTestServer` before pushing.
