# towergen / towerview (P3b, P3c)

Java port of the TOWERLIB P3a runtime (notes/timestop/TOWERLIB-PLAN.md, timestop-proto/towerlib/NOTES-P3a.md).
Python stays the offline library builder; Java owns statics, lookup, stitch, the pretensioned widening pass and the
mesh. `towergen` has no Minecraft or NeoForge dependency (and no third-party dependency at all).

## Layout
- `towergen/src/main/java/org/erisia/stasis/towergen/`
  - `Library`, `Consts`, `Json`: streaming loader for `p3a_library.json.gz` (classpath resource
    `/towergen/p3a_library.json.gz`, 7534 entries, ~0.45 s); bay reactions are skipped (only boom reactions are used).
  - `Layout`, `LayoutGen`: layout model (`{"H": 40, "booms": [[10, "E", 16], ...]}`), game-rule checks, random layouts
    (same distribution as p3agen.py).
  - `Spine`: p3alib.spine3a (pass-1 P1 boom estimate, net-moment back-stays with capped anchors, ring choice by
    p2spine.sweep patched for arbitrary H, boom keys, pass 2 with the boom entries' reactions) and bay_specs (integer
    keys, canonical rotation, seam keys).
  - `Lookup`: fallback order of p3acov.lookup_bay plus last-resort tiers (below). `Patch`: the `patch` tier.
  - `Stitch`: p25lib.stitch (module variants by the sha1 threshold, node merge on the 1e-3 grid, duplicate members,
    back-stay wires).
  - `RuntimePass`: the fixf pass of NOTES-P3a section 5 (f = 1.5, raise loop, resize, pole bumps, leg fix) with a
    hand-written Cholesky (`Dense`; profile/skyline variant, the nodes are numbered bay by bay so K is nearly banded).
  - `Sections`: member sections (p25fem.props / sig_laced).
  - `Mesh`, `MeshBuilder`, `VisualParams`: triangles with flat normals and a part per triangle (part -> material id:
    rust, steel, cable, porcelain, glow, brass, concrete).
  - `LibraryBin`: compact lossless binary library (TGLB1; `:towergen:libraryBin`), used by `towerweb`.
  - `Portable`: `Arrays.compare` / SHA-1 stand-ins (the TeaVM classlib lacks them).
  - `TowerGen`: facade, `new TowerGen(Library.shared()).build(layout, q[, solverParams])`.
  - `SolverParams` (solver.json), `WireReduction`: runtime solver knobs and the wire reduction (see below).
- `towergen/layouts/`: the 7 golden layouts. `towergen/renders/`: headless renders (only the contact sheet is in git).
- `towerweb/`: the browser build (TeaVM) and static site, see towerweb/NOTES.md.
- `towerview/`: the viewer (`Main` CLI, `Raster` software renderer, `Interactive` LWJGL window), `shell.nix`, `run.sh`.

## Running
- Tests: `tools/stasis-build.sh :towergen:test` (goldens, mesh determinism, last-resort layouts; ~5 s, part of the
  root `build`). Slow tests: `./gradlew :towergen:slowTest [-Dtowergen.random=400 -Dtowergen.seed=7]` (timings, random
  layouts).
- Viewer: `towerview/run.sh render ../towergen/layouts/H64max.json --q 1.0 --out /tmp/tv [--shots a215,close]
  [--orbit 72] [--size 1280x720] [--ss 2] [--params visual.json] [--solver solver.json]`; `run.sh params > visual.json` and
  `run.sh solver > solver.json` write the defaults;
  `run.sh info LAYOUT.json` prints keys, tiers, util and timings; `run.sh obj LAYOUT.json out.obj`;
  `run.sh contact sheet.png --cols 3 --width 1920 a.png b.png ...`.
- Interactive (NixOS): `cd towerview && nix-shell --run './run.sh view ../towergen/layouts/H40r3.json --params visual.json'`.
  Left drag orbit, right drag target height, wheel zoom, Q/A q +/- 0.1, 1 / 3 q = 1 / 0.3, R rebuild, S save a PNG
  (software renderer), Esc quit. The layout, visual and solver (`--solver`) files are polled every 0.5 s and rebuilt
  off-thread; the title shows the wire counts.

## Solver parameters (solver.json)
`SolverParams.defaults(c)` is the plain generator: `build(layout, q, defaults)` gives the same tower bit for bit
(`SolverTest.defaultsMatchPlainBuild`, all 14 golden runs), because `Consts.forSpine` / `forPass` return the library
`Consts` itself when nothing differs. `towerview solver > solver.json` writes the defaults; keys and docs are in
the record's comments (also generated into the web page's help). Groups:
- Wire reduction (`WireReduction`, on the stitched tower before the runtime pass; ranking forces from one analysis of
  the stitched tower under the runtime loads, max |N| over the cases):
  - `xPanels` keep / single / auto: crossing cable pairs within a mast bay (segment-segment test) become one strut
    diagonal (ws 0, the pass sizes it); the kept diagonal winds one way in even bays and the other in odd bays.
    `auto` only where a smallest strut carries 2x the pair's force.
  - `maxCablesPerBay`: the lowest-force cables of a bay beyond the limit become struts.
  - `maxStaysPerBoom` / `minStaysPerBoom`: stays = boom members with one end at the boom's mast anchor node(s)
    (`Tower.boomAnchors`, recorded by Stitch), cable or strut. The N with the largest force stay. Then per boom
    (boom members alone, mast nodes held): while a free boom node became flat (smallest eigenvalue of sum e e^T of
    its members < 0.01 and < half of before), or the boom is a mechanism (pivot node, `Analysis.mechNode`), or a
    member force exceeds 0.5x the largest laced section and 2x its force before, the removed stay nearest that node
    is put back. Then the same on the whole tower (also puts back X partners).
  - If the runtime pass then ends over `utilLimit`: put back the 4 removed members nearest the worst member, rerun,
    at most twice; then retry with the stay reduction only, then the bay reduction only, then none (each fallback is
    a warning in `Result.warnings`). Mechanisms in the pass no longer throw out of `build(.., sp)`.
- Back-stays (`backstays`, `backstayMinBoom`, `backstayDistFactor`, `backstayDistCap`, `backstayMinTension`,
  `backstaySecondPair`): go to the Spine (they change the boom keys / library choice).
- Pass: `pretensionFactor` (the 1.5), `pretensionMargin`, `raiseIters`, `maxRounds`, `utilLimit` (sizing target;
  demands are divided by it, so 1.0 is exact).
- Loads of the runtime check only (`emitterWeight`, `emitterWind`, `windDrag`, `torsion`, `density`); the spine and
  the library choice keep the library's loads.
- `extraWidthSteps`: WEXT extended by x1.2 steps (3.97, 4.77, ...); use `Result.c` (not `TowerGen.c`) for the mesh.

Results, `maxStaysPerBoom 2, xPanels single`, final towers, JVM warm (SolverTest.fewerWiresStillStand, all pass:
util <= 1.001 in every case, cables taut):

| layout q | cables default -> reduced | stays | util | time default -> reduced |
|---|---|---|---|---|
| H16 q1 / q0.3 | 16 -> 8 / 12 -> 12 | - | 0.94 / 0.89 | 3 -> 5 ms |
| H24b q1 / q0.3 | 99 -> 42 / 68 -> 41 | 9 -> 9 / 9 -> 9 | 0.98 / 0.98 | 15 -> 13 ms |
| H40 q1 / q0.3 | 140 -> 36 / 77 -> 52 | - | 1.00 / 0.98 | 6-12 -> 6-17 ms |
| H40r3 q1 / q0.3 | 140 -> 64 / 87 -> 65 | 108 -> 80 / 108 -> 80 | 0.99 / 0.99 | 40-65 -> 65-90 ms |
| H40b4 q1 / q0.3 | 149 -> 68 / 97 -> 79 | 43 -> 32 / 43 -> 31 | 0.99 / 1.00 | 35-65 -> 39-67 ms |
| H64max q1 / q0.3 | 172 -> 163 / 102 -> 67 | 32 -> 27 / 31 -> 24 | 1.00 / 0.99 | 23 -> 560 ms / 41 -> 71 ms |
| H100 q1 / q0.3 | 328 -> 48 / 179 -> 62 | - | 1.00 / 1.00 | 25-30 -> 25-47 ms |

H64max q1: the full reduction runs away in the mast legs (util 4.1, legs at the top of the ladder), so it falls back
to the stays only (4 members put back by the util loop); that costs ~0.56 s (JVM), ~1.2 s in the browser.

Known limits:
- The library booms are flat trusses: every free boom node needs its own out-of-plane support, which only a stay
  gives, and collinear chord nodes need more. So `maxStaysPerBoom` 2 ends at about one stay per boom node (H40r3: 108
  -> 80; H24b's 8-block booms keep all 9). Fewer would need geometry changes (dropping chord stations and
  lengthening chords), not done.
- Over-limit reduced towers cost several passes (each up to 60 rounds when it diverges).

## Golden results (p3a_goldens.json.gz, 14 runs)
- Stage by stage, all 14 match exactly: rings, boom keys, back-stays (T, n, D), bay keys (seam form) and rotations,
  lookup tiers (seam_exact / plain_exact for the top bay, booms exact), low-q variant choice, stitched topology
  (nodes, members with sections and floors, pole chain and widths, fixed, emitters, ground anchors, legs).
- Runtime pass: util <= 1.001 in all 11 cases and taut cables in all 14; volume, rounds, conversions and leg fixes are
  identical to the Python (e.g. H100 q1 217.65 -> 383.51, 7 rounds, 40 conversions); every member within one width step.

## Lookup tiers
`seam_exact -> seam_near -> plain_exact -> plain_near -> plain_near_mid -> plain_super` as in the Python, then:
- `ring_near` (same h, same pole-ness at both rings, root pattern equal or a superset under some rotation; distance =
  bay distance + 3/extra root + 3 mid + 4 per quarter block of ring difference). The entry's nodes are stretched
  radially from its ring half-widths to the actual ones (linear in y), so ring corners and boom roots land where the
  neighbours and booms expect them.
- `ring_near_h` (bays without booms only): the same with any bay height, nodes also scaled in y.
- `patch`: the closest entry by root pattern (10 per missing root, 3 per extra root, rings, mid, height, classes),
  stretched, q1 geometry; each missing boom root node is split into its leg (or the pole) and braced to the
  bracketing nodes of both face-adjacent legs (face diagonals + a ring strut to a same-height node). The runtime pass
  sizes the new struts.
- `NoEntryException` only if no entry has the same pole-ness at all (never seen).
- Booms: exact, else nearest with the same anchor type (as the Python).

On the 6 held-out layouts the Python could not cover ("none"), the Java tiers give passing towers at q 1 and 0.3
(`LastResortTest`).

## Timings (JDK 25, tsugumi; warm median, first run in brackets)
| layout | n_free | statics + lookup + stitch | runtime pass |
|---|---|---|---|
| H16 q1 | 48 | 0.6 ms (4 ms) | 1.4 ms (7 ms) |
| H40 q1 / q0.3 | 216 | 1.3 / 1.2 ms | 6 / 12 ms |
| H64max q1 / q0.3 | 498 | 4 / 2 ms (12 ms) | 28 / 56 ms |
| H100 q1 / q0.3 | 666 | 1 / 1 ms | 36 / 42 ms |
Library load 0.45 s. Mesh 0.1-3.5 ms. Python's whole pass took 0.27-0.31 s at H100.

## Discrepancies / notes on the Python
- `TORQ0` (0.4, torsion load at an off-axis emitter) is not in the library header; hardcoded in `Consts`.
- Mechanism check: the Python tests the Cholesky factor's diagonal (sqrt of the pivot) of the scaled matrix
  against 1e-6; the spec says "pivot < 1e-6". Java follows the Python.
- Pole bump (d): the Python bumps every non-cable member whose two ends are pole nodes, not only consecutive pole
  segments (the spec says segments). Java follows the Python (no difference on the goldens).
- Python `round()` is half-to-even (y_flare, mga); Java uses `Math.rint`.
- Boom reactions in the library are rounded to 4 decimals; no golden key changed because of it.

## Known issues
- Tall towers with long booms exceed the member catalogue (as in Python): 400 random layouts x 2 q (seed 7): 769 /
  800 pass, 31 have util 1.02-3.2, all with H >= 64 and long booms; no exceptions, no mechanisms. The game needs a
  policy for these (reject / "simplify" hint / bigger laced ladder).
- Meshes are heavy: H64max is ~117k triangles (lacing dominates). LOD (skip lacing at distance) is needed in the mod.
- The library is 7.8 MB gz in the jar. `LibraryBin` (TGLB1, lossless, 2.0 MB gz, reads in 20 ms) exists for towerweb;
  the mod could load it too (`LibraryBin.read`), but it doesn't yet.
- The interactive window was tested only under Xvfb + Mesa llvmpipe (renders, `--snap` hook); mouse, keys and the
  file-reload loop need a desktop (saya).
