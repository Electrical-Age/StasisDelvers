# towergen / towerview (P3b, P3c)

Java port of the TOWERLIB P3a runtime (notes/timestop/TOWERLIB-PLAN.md, timestop-proto/towerlib/NOTES-P3a.md).
Python stays the offline library builder (now `tools/towerlib/`, see its README: `tools/towerlib/build_library.sh
export|goldens|full`; the library header carries `provenance`); Java owns statics, lookup, stitch, the pretensioned widening pass and the
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

## Stay rule and refusals (2026-10-13)
- **Rule** (`Spine.anchorLevels`, mirrored in p3alib.anchor_levels): a boom's stays anchor at the old preferred ring
  level (first ring >= yb + max(BAY, `stayRise` 0.7 x L), else H) unless that is above the **next boom level above
  in the same direction**; then at the highest ring level at or below that boom. Same direction only (`stayCap`
  "same", default): stays run from the boom up to its own mast face, so only a boom above on the same face can be
  crossed; perpendicular / opposite booms leave from other faces (checked geometrically for the E-y8 / S-y14 case
  of H40b4: the stay passes 2+ blocks outside the S boom's root corner). `"any"` (next boom above in any direction)
  is available but refuses H40b4 and 32% of random layouts; `"none"` is the P3a rule.
- **Refusal**: if the capped rise < max(`stayMinRise` 2, `stayMinRatio` 0.35 x L) (0.35 = RHO_LADDER[0], the
  flattest stay the boom library is solved for, ~19 deg), `build` throws `RefusedException` (Refusal.Reason
  STAY_RISE, message names the boom, the capping boom, the rise and a max length). Layout-only, so
  `TowerGen.check(layout, sp)` gives the same answer before building. 17% of random layouts (`LayoutGen`, mostly
  booms stacked 1-4 blocks apart on one face) are refused; `LayoutGen.randomBuildable` redraws (web Random button).
- `NO_BOOM_DESIGN`: a boom key whose offline solve failed (header `boom_failed`, now [9, 1, 6, 6]: L45 at rho 0.5,
  repair gave up, util 1.12, reactions 12x its neighbours) is refused instead of approximated by the nearest entry.
- `OVER_CATALOGUE`: `Result.refusal` (was a warning) when util > utilLimit after all fallbacks; the tower is kept
  for previews. The game must not build if `refusal != null` or a `RefusedException` was thrown.
- SolverParams: `stayCap`, `stayRise`, `stayMinRatio`, `stayMinRise` (spine group; defaults from the library header
  constants STAY_*).
- Library: +22 booms, +90 plain, +90 seam bays (p3acov booms/bays/seams on the 400 training layouts under the
  rule; 73 of them are refused and skipped) -> 7747 entries (214 booms), 8.05 MB gz JSON, TGLB1 2.05 MB gz.
- Goldens: only H40r3 changes (y10 booms anchor at y20 instead of y24; boom key [6, 1, 6, 6]); the other 12 runs
  are byte-identical. The pre-rule goldens are kept (`p3a_goldens_p3arule.json.gz`) and must still match stage by
  stage under stayCap "none" (`GoldenTest.oldStayRuleMatchesP3aGoldens`). `StayRuleTest`: caps, refusals, check(),
  failed boom keys, random refusal rate.
- Random (slowTest, 400 x 2 q, seed 7): 645 pass, 19 over the catalogue (now refused), 136 refused by the stay rule
  (was 769 pass / 31 over); no exceptions.

## Laced members: straight chords, splices at every continuing node (2026-10-13, owner report)
- Report: `{"H": 16, "booms": [[2, "W", 4]]}` (and W5) drew the west laced legs as spindles: chords converging to a
  point at every bay node and bowing out between, with kinked joints; the joint keys had no effect there.
- Cause: a laced end counted as continuing only if the collinear partner had the **same chord count** (and a
  one-to-one nearest-chord map). Where the leg section changes 3 <-> 4 chords (outer width across 0.7, e.g. around
  the boom-root bay), both ends were treated as free ends, so `lacedEndTaper` pulled the chords together at both
  ends of the member (a spindle), and the converged chord ends went into the node hull instead of a splice.
- Fix: continuation = the mutually most collinear laced partner within 30 degrees (`CONT_DOT`, so base-flare kinks
  splice too), any section / chord count. Chords are matched one-to-one by increasing end distance; each pair is a
  two-member hull joint (flush / frustum / wedge at a kink: the hull bridges, chords stay straight); an unmatched
  chord (4 -> 3) just ends capped at the node. Chord ends are no longer moved to a common midpoint (no kink inside a
  member). `lacedEndTaper` now defaults to 0: at a genuine free end the chords run straight into the node hull like
  solid members (the old taper is still available as an optional key). Every laced joint is a hull joint, so the
  joint* keys apply.
- Test `MeshTest.lacedChordsStraightAndSpliced` (7 goldens + H16 W4@2 + H16 W5@2 + 40 random, q 1 and 0.3): every
  chord segment within 1 degree of its member axis, every chord point inside the member envelope (square of side b,
  triangle of side b for 3 chords), and of any two laced members meeting within 5 degrees of collinear at least one
  is spliced. 8479 members, 10430 spliced ends; the largest member bend at a splice is 28.6 degrees (chords stay
  straight; the hull bridges). Hull test still passes (laced envelopes now count for splice and free-end joints);
  slow random sweep: 400 towers, 109149 hulls, 0 inconsistent.
- Triangles q1 drop (no taper breakpoints, fewer chord segments): H24b 12886 -> 10802, H40 18148 -> 17306, H40r3
  58042 -> 45588, H40b4 47572 -> 39094, H64max 113966 -> 86284, H100 95356 -> 80036. Mesh time about the same.
- Renders: `renders/contact_laced_splices.png` (1920 x 1800, pairs before | after: H16 W4 / W5 from west and east
  (new towerview shots `sideW`, `sideE`), then the `close` shot of H24b, H40r3, H40b4, H64max, H100). Browser: 6/7
  mesh hashes identical (H40b4 strut colour as before), the W4 layout builds straight legs
  (`towerweb/screenshot-laced.png`; script /var/lib/mclab/agent/scratch/twtest/hull.py).
- Known: at a 4 -> 3 chord section change the end battens of the wider member show as a step; members meeting
  at 11-30 degrees now splice with a wedge-shaped hull at each chord instead of a node knuckle.

## Mesh: convex-hull joints (2026-10-13, replaces the cube hubs below)
- Scheme (MeshBuilder javadoc, `Hull3`): per node, every solid member end (strut, pole, laced chord at a free laced
  end) is cut back by its setback = the largest distance along it inside any other member's prism there (exact
  prism-prism intersection: max of s over the vertices of the intersection polytope), + `jointMargin` x its width,
  capped at `jointMaxSetbackFrac` x its length and `jointMaxSetback` x the widest member at the joint (nearly parallel
  members overlap past the cap instead of making a long hub). The hub is the convex hull of all cut-face corners;
  members run hull face to hull face (no end caps). If the hull still covers a member past its cut (a wide neighbour
  reaching further along it), the cut moves to where the member's edges leave the hull and the hull is rebuilt (no
  coplanar faces). Cables do not size hubs: they end where all four edges have left the hull, or (end outside it,
  crossing offset) their end face is added to the hull. Two or more cables with no solid form a hull of their own; a
  single solid end stays capped. Continuing laced legs: each chord pair is a two-member hull joint (thin prism when
  collinear, frustum when the chord size changes; no splice cubes); bracing at that node forms its own hull inside
  the leg. Lacing and battens unchanged. Crossing offsets are applied before (member end points include them).
- `Hull3`: quickhull (maximal tetrahedron, farthest point of the first face with outside points, horizon fan,
  conflict lists), eps = 1e-9 x extent, flat arrays, no hashing (deterministic, same on TeaVM); planar clouds give a
  2D polygon drawn from both sides, collinear ones nothing; an inconsistent result retries with 10x eps.
- VisualParams: `jointMargin` 0.1, `jointMaxSetbackFrac` 0.45, `jointMaxSetback` 1.0, `jointMemberColour` false.
  The old hubScale / hubAdd / hubMaxExtendFrac / hubMemberColour still parse and are ignored
  (`VisualParams.OBSOLETE`; the web page warns "obsolete key").
- Tests (MeshTest): `hullJointsClosedAndContained` (all 7 goldens x 2 q: every hull built consistently, every
  sealed end's cut face inside its hull, and hull points (face centroids, edge midpoints, interior samples) at most
  0.3 x the widest member + 0.06 blocks outside the union of the members' prisms run back through the node by their
  half-width (laced members: their outer envelope). Measured worst: 0.06-0.24 blocks, 0.16-0.37 x the widest member
  at solid joints; up to 0.70 x at two-cable joints, where it is 0.035 blocks (cables pushed apart by crossing
  offsets). `portableHashes` writes `build/mesh-fnv.json` (FNV-1a of the mesh, also per part) for the browser check;
  `stats` prints triangles and mesh time.
  SlowTest `hullJointsRandom` (`-Dtowergen.random=200`): 200 random buildable layouts x 2 q = 400 towers, 104209
  hulls, 0 inconsistent, 0 flat, 0 sealed corners outside, no exceptions.
- Triangles q1 (cube hubs -> hulls): H16 1050 -> 1232, H24b 12024 -> 12886, H40 17456 -> 18148, H40r3 55096 -> 58042,
  H40b4 45718 -> 47572, H64max 109664 -> 113966, H100 92184 -> 95356 (+3-17%).
- Mesh time (JVM warm median): H16 0.09 -> 0.22 ms, H24b 0.9 -> 2.3, H40 1.4 -> 2.2, H40r3 3.8 -> 9.5, H40b4 3.3 -> 6.9,
  H64max 4.9 -> 14.0, H100 4.3 -> 8.8 ms. Browser (headless Chromium, median of 6 rebuilds): H40r3 12 -> 33, H64max
  19 -> 50, H100 18 -> 41 ms (builds 78/68/83 -> 97/99/106 ms). Most of it is the clearance pairs at the big pole
  and boom-root joints (up to 33 ends) and the hulls themselves.
- JVM vs browser: identical mesh FNV for H16, H24b, H40, H40r3, H64max, H100; H40b4 has identical geometry (strut
  triangles hashed without their colour match) but some strut(s) flip rust/steel: the force-sign colouring of a
  member whose dominant force differs between JS and JVM arithmetic (runtime pass, not the mesh).
- Renders: `renders/contact_hull_joints.png` (1920 x 1440; pairs before | after: H40r3 bjoint, ljoint, splice;
  H64max bjoint, ljoint, splice; H100 ljoint, splice). New towerview shot `splice` (a node where two laced members
  continue, nearest 0.3 H). Singles in renders/before_hull, renders/after_hull (git-ignored).
- Known: the hull fills the crotch between members at small angles (a gusset look, bounded as above); near-parallel
  members beyond the setback cap overlap inside each other; hubs inside laced legs (bracing only) are still drawn.

## Mesh: watertight joints, crossing offsets (2026-10-13; the cube hubs, superseded by the hulls above)
- Every node with >= 2 member ends gets a hub: a cube oriented like the widest member, half-size the smallest that
  contains every member's end face (incl. its crossing offset), x `hubScale` + `hubAdd`. Each member is trimmed to
  the largest extension whose end face is still inside the hub (end caps omitted), so joints are closed for any
  angle / width; a straight leg through a node is flush (no step, no coplanar overlap). Pole, anchor and ground
  nodes included. Laced members: canonical section frame, chords taper into the hub at ends, continuing laced legs
  pair chords and splice them in chord-sized cubes, mitred bends; lacing follows the drawn chords.
- Crossings: strut / cable pairs that share no node, cross inside both members at >= `crossMinAngle` and are closer
  than `crossOffsetFrac` x (w1 + w2) along d1 x d2 are pushed apart to that distance (thin member moves more);
  sweep over y-sorted boxes, deterministic order. Removes the coplanar X z-fighting.
- VisualParams: removed knuckle*; added hubScale 1, hubAdd 0, hubMaxExtendFrac 0.45, hubMemberColour false,
  crossOffsetFrac 0.3, crossMinAngle 10. Triangles q1: H16 1344 -> 1050, H24b 13464 -> 12024, H40 19512 -> 17456,
  H40r3 62736 -> 55096, H40b4 49740 -> 45718, H64max 116568 -> 109664, H100 100056 -> 92184; mesh time +0.2-0.8 ms.
- Renders: `renders/contact_fix_a215.png` (before | after, 7 layouts), `contact_fix_joints_H40r3.png`,
  `contact_fix_joints_H40b4.png` (boom joint `bjoint`, leg joint `ljoint`, close / legs), `mesh_crossing_test.png`;
  single shots in renders/before, renders/after (git-ignored).

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
- (Superseded: over-catalogue towers are now refused, see "Stay rule and refusals".) Tall towers with long booms exceed the member catalogue (as in Python): 400 random layouts x 2 q (seed 7): 769 /
  800 pass, 31 have util 1.02-3.2, all with H >= 64 and long booms; no exceptions, no mechanisms. The game needs a
  policy for these (reject / "simplify" hint / bigger laced ladder).
- The stay rule only anchors at ring levels (every 4 blocks), so booms stacked 1-3 blocks apart on one face are
  refused even when short; hanging the lower boom from the upper one (or anchors between rings) would accept them.
- LP-infeasible boom solves are not cached, so they are not in `boom_failed` (only repair-gave-up ones are).
- Joint look: section changes on laced legs show as chord-width splice cubes; big hubs where a laced boom truss
  tapers into a solid chord; hubs of braces sit inside laced legs; hubs read as a dark band in colKnuckle on solid
  legs (`hubMemberColour` / colKnuckle tune it); ground hubs reach h below ground.
- Meshes are heavy: H64max is ~110k triangles (lacing dominates). LOD (skip lacing at distance) is needed in the mod.
- The library is 7.8 MB gz in the jar. `LibraryBin` (TGLB1, lossless, 2.0 MB gz, reads in 20 ms) exists for towerweb;
  the mod could load it too (`LibraryBin.read`), but it doesn't yet.
- The interactive window was tested only under Xvfb + Mesa llvmpipe (renders, `--snap` hook); mouse, keys and the
  file-reload loop need a desktop (saya).
