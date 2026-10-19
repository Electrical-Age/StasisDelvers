# Tower generator: towergen, towerview, towerweb

`G/` below means `towergen/src/main/java/org/erisia/stasis/towergen/`. The long-form notes are
`towergen/NOTES.md` (generator, solver knobs, joints, golden results, known issues) and `towerweb/NOTES.md`.

## What it is
- A pure-Java port of the P3a tower runtime, with no Minecraft or third-party dependency. It turns a
  player's survey-pole sketch (`Layout`: height H plus booms (y, dir, length)) into a statically checked
  lattice tower and a triangle mesh.
- The library of pre-solved bay and boom modules comes from the offline Python builder `tools/towerlib/`
  (`build_library.sh export|goldens|full`).
- Three consumers:
  - the mod: the server checks and builds, the client meshes
  - `towerview/`: CLI renderer and LWJGL viewer
  - `towerweb/`: TeaVM build at towers.brage.info

## Build wiring
- `settings.gradle` includes `towergen`, `towerview`, `towerweb`.
- Root `build.gradle` puts `towergen/src/main/java` into the mod's main source set: it is compiled into the mod,
  not a jar dependency.
- `:towergen:libraryBin` (`G/LibraryBin.java` main) writes `towergen/build/libbin/p3a_library.bin.gz` (TGLB1)
  from `towergen/src/main/resources/towergen/p3a_library.json.gz`. The mod's `towerLibraryResource` task copies
  it to `/towergen/p3a_library.bin.gz` in the jar.
- `Library.loadDefault` prefers the binary, falling back to the JSON. `Library.shared()` caches one instance.

## Pipeline (`G/TowerGen.java`, `build(layout, q[, solverParams])`)
1. **Spine** (`G/Spine.java`): statics, ring sweep, boom keys. `anchorLevels` applies the stay rule (refusal
   `STAY_RISE`), then back-stays and canonical rotations.
2. **Lookup** (`G/Lookup.java`, `G/Patch.java`): tiers `seam_exact → seam_near → plain_exact → plain_near →
   plain_near_mid → plain_super → ring_near → ring_near_h → patch`. Booms are exact, else the nearest with
   the same anchor type; failing that, `NO_BOOM_DESIGN`.
3. **Stitch** (`G/Stitch.java`): merges nodes on a 1e-3 grid. The module variant (q1 or low-q) is chosen by
   `moduleThreshold(Q_LOW, seed, tag)`, a SHA-1 of `seed:tag` (`G/Portable.java` provides SHA-1 for TeaVM).
4. **WireReduction** (`G/WireReduction.java`): only with `SolverParams` that reduce wires (towerweb, towerview).
5. **RuntimePass** (`G/RuntimePass.java`): pretension, raise loop, resize, pole bumps, leg fix. `G/Dense.java`
   is the profile Cholesky; `G/Sections.java` gives member sections.
6. **Over-catalogue refusal**: max utilisation above `UTIL_LIMIT` sets `Result.refusal` to `OVER_CATALOGUE`.
   The tower is still returned, for previews.
- Mesh: `G/MeshBuilder.java` (convex-hull joints via `G/Hull3.java`, lacing, gussets) produces `G/Mesh.java`.
  Triangles carry a part id (`VisualParams.Part`: STRUT_C, STRUT_T, POLE, LACING, CABLE, GLOW...); each part maps to a material.
- Refusals: `G/Refusal.java` (`Reason`), `G/RefusedException.java`. Game rules: `Layout.violations()`
  (H 4..100; booms at y 2..H-2, length 2..H-y, one per y and direction). The mod reports a violation as INVALID.
- Other: `G/Json.java` (a hand-rolled parser), `G/LayoutGen.java` (random layouts under the game rules),
  `G/Consts.java` (library constants; `forSpine`/`forPass` return copies when `SolverParams` differ).

## How the mod uses it
- Server: `TowerStructure.build` (static synchronized, single "stasis-towergen" thread):
  `new TowerGen(Library.shared())`, `gen.seed = structure.seed`, `build(layout, TowerStructure.Q)` (Q = 1.0,
  no `SolverParams`). Only the status, the message and `ladderTop` are kept.
- Client: `client/TowerRenderer.build` on the "stasis-towermesh" thread repeats the same call, then runs
  `MeshBuilder` with `VisualParams` `{"drawCore": false}`.
  - It caches by `layout#seed`.
  - It uploads full and LOD (no lacing, beyond `LOD_DIST` = 48) vertex buffers, with the pipeline
    `stasisdelvers:pipeline/tower` (shaders `assets/stasisdelvers/shaders/core/tower.vsh` and `tower.fsh`).
- The tower seed is set in the `TowerBlockEntity` constructor as a hash of the core position. Ruined cottages
  get a fresh one.

## Invariant: server, client and web build the same tower
- Same inputs: layout, seed, Q, library bytes (`Library.shared()`), and `SolverParams` (none in game; the
  defaults on the web are bit-identical to none: `SolverTest.defaultsAreIdentity`).
- Determinism and thread safety: `MeshTest.deterministic`, `MeshTest.threadSafe`; `GoldenTest` (the 14 Python
  golden runs, stage by stage).
- JVM against JS: `MeshTest.portableHashes` writes `towergen/build/mesh-fnv.json`. Comparing it with the
  browser is manual.
- towerweb never sets `TowerGen.seed` (it stays 1). At Q = 1.0 the q1 variant is chosen for any seed in
  practice, so the web's q = 1 tower matches the game's.
- Library format: `LibraryBinTest.roundTrip`, `RoundTripPropertyTest.libraryNumbersAreBitExact`.
- Layout rules: `RoundTripPropertyTest.randomLayoutsKeepTheRules`. Stay rule: `StayRuleTest`.
  Fallback tiers: `LastResortTest`.

## Thread safety
- A `TowerGen` instance is not thread-safe: `seed` and `seamMode` are public mutable fields. The server
  serialises with `synchronized`; the client uses its own instance on a single-thread executor.
- `Consts` has public mutable fields and is shared through `Library.shared()`. Treat it as read-only.

## towerview (`towerview/`)
- `Main` (CLI: `render`, `view`, `info`, `obj`, `params`, `solver`, `contact`), `Raster` (software renderer),
  `Interactive` (LWJGL window). Run it with `towerview/run.sh` (`shell.nix` for the desktop window).

## towerweb (`towerweb/`)
- `towerweb/src/main/java/org/erisia/stasis/towerweb/TowerWeb.java`: the `@JSExport` API (`loadLibrary`,
  `build`, `positions`, `normals`, `parts`, `randomLayout`).
- `towerweb/src/gen/java/org/erisia/stasis/towerweb/GenCodec.java`: runs at build time and generates
  `VisualParamsCodec`/`SolverParamsCodec`, the defaults and the key docs. TeaVM has no record reflection.
- `/var/lib/mclab/agent/tools/stasis-build.sh :towerweb:dist` builds `towerweb/dist/` (git-ignored); copy it to the web root.
  The root `build` compiles towerweb's Java but doesn't run TeaVM.

## Tests and commands
- `/var/lib/mclab/agent/tools/stasis-build.sh :towergen:test`: fast; excludes the `slow` tag; part of the root `build`.
- `./gradlew :towergen:slowTest -Dtowergen.random=N -Dtowergen.seed=S`: timings and random layouts.
- After changing the library or `Consts`, regenerate the goldens with `tools/towerlib` (see towergen/NOTES.md).
