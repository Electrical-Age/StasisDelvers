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
  `run.sh info LAYOUT.json` prints keys, tiers, util, timings and a density line (members by kind, silhouette, triangles,
  ladder top); `run.sh obj LAYOUT.json out.obj`;
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
- Measured 2026-10-17 (x1.4 library, `maxStaysPerBoom` 2, xPanels keep; `Report.readdFlat / readdMech / readdForce`
  count why each stay was put back, printed by `SolverTest.fewerWiresStillStand`): kept stays = free boom nodes, or
  a few more where a node had a stay from each anchor. Per boom, stays before -> after / free boom nodes: H24b E L8
  6 -> 4 / 3, N L6 3 -> 3 / 3; H40r3 L16 14 -> 12 / 9, L10 8 -> 6 / 5, L6 3 -> 3 / 3; H40b4 E L20 18 -> 15 / 11
  (q0.3: 11), S L12 14 -> 11 / 10, W L6 4 -> 4 / 3, N L10 5 -> 5 / 5; H64max E L40 38 -> 24 / 27 (the 24 reach 23
  free nodes; the flat test only fires on nodes the reduction made flatter). Triggers: H24b 3/3 flat; H40r3 60/60 flat (q0.3: 54 flat, 4 mechanism, 2 force); H40b4
  21 flat, 3 mechanism, 3 force; H64max 18 flat, 4 mechanism. So the flat-node test (`FLAT`, smallest eigenvalue of
  sum e e^T at a free boom node) does nearly all of it: every boom node lies in the boom plane, and a stay is its only
  member out of that plane. Without the put-back the boom is a mechanism, so the target cannot simply be enforced.
  A real cut needs boom geometry with fewer free nodes (longer chord panels) or out-of-plane depth (a top chord /
  3D section), i.e. new boom designs and a library rebuild. No cheap runtime alternative: the warning already says
  why stays came back; `maxStaysPerBoom` stays a target (polish.md).
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
  repair gave up, util 1.12, reactions 12x its neighbours) is refused instead of approximated by the nearest entry. (Empty since the x1.4 strength library, 2026-10-17.)
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

## Stays between rings: `stayAtBoomAbove` (2026-10-17, polish.md "Generator"; default OFF)
- Wanted: booms stacked 2-3 blocks apart on one face are refused (the lower boom's stays may only anchor at a ring
  level at or below the boom above, and rings are 4 blocks apart).
- Done (Java only): `stayAtBoomAbove` (SolverParams, spine group; `Consts.STAY_AT_BOOM`). If the ring level under
  the cap gives too small a rise and the cap is a same-direction boom, the stays anchor at the cap level itself:
  `Spine.anchorLevels` returns ya = that boom's y, and the anchors (s = ring_s_at(ya), the same expression as the
  upper boom's root s) land exactly on the upper boom's root nodes, which the stitch merges (mast + upper boom +
  stays meet there). `sa` is now `ringSAt(ys, s, ya)` everywhere (bit-identical at ring levels); back-stays of such a
  boom attach at the next ring up (booms this short are below `backstayMinBoom` 14 anyway). Same direction only:
  under `stayCap "any"` a perpendicular cap's roots are on another face, so that stays refused. One block apart stays
  refused (rise 1 < `stayMinRise` 2).
- Effect on refusals (StayRuleTest, 4000 random layouts): 16.7% -> 11.1% under "same".
- Why it is off: the library is not trained for it. 150 random layouts that the knob newly accepts (seed 7, q 1 and
  0.3): 284 of 300 builds pass, 16 over the catalogue (baseline, 150 two-plus-boom layouts accepted by both: 293 /
  7). Several failures are near-mechanisms, not just overload: H15 S y3 L6 under S y6 L2 (util 14, |Nt| = |Nc|
  ~ 4000 in the lower boom), and `{"H": 40, "booms": [[2, "S", 8], [5, "S", 6]]}` is a mechanism (pivot at the
  lower boom's tip; a plain `build(l, q)` throws MechanismException, which the game shows as FAILED rather than
  REFUSED). Cause: the hang node is a mid-leg root node of the bay entry, braced only for the upper boom's root
  reactions (no lateral bracing in the face plane), and a mid-bay ANCHOR load is credited to no bay key
  (`baySpecs` adds TOP / ANCHOR / BS loads at the top ring only); the new (rho, sr4, sa4) boom keys also go to
  `near` entries more often. Pinned in `StayRuleTest.stackedBoomsHangFromTheBoomAbove` (the y10 / y13 E pair
  builds at util 0.99; the two bad layouts fail) so a library rebuild shows them flipping.
- Options (designer's decision):
  (a) the real fix: mirror the rule in tools/towerlib (`p3alib.anchor_levels`; `boom_key_int` uses `rings[ya]`, a
  KeyError for a non-ring ya, so it needs ring_s_at), let `bay_specs` see the hang load at the root node (a key
  change), train booms / bays on the newly accepted layouts and rebuild the library (incremental from the s140
  cache: only the new keys solve). Then default it on.
  (b) runtime bracing at the hang node (face diagonals to the bracketing ring nodes of both face-adjacent legs, as
  the `patch` tier does), sized by the pass; moderate, unproven, a second fix-up layer.
  (c) default on now with a mechanism turned into a refusal: accepts ~5% more layouts, with ~5% of those refused
  at build time instead of at check time (`TowerGen.check` would no longer predict them).
- Renders (q1, the y10 / y13 E pair, shots sideE, bjoint): /var/lib/mclab/agent/scratch/colourtie/hang/.

## Strut colour: ties are steel (2026-10-17, polish.md "Generator")
- A strut is rust (compression) when its largest compression over the load cases beats its largest tension, else
  steel. Members loaded only by the mirrored wind / torsion cases (H40 top cap struts, H100 platform struts; |N| ~
  0.03-0.12 either way) tie up to 1e-14 relative, so the old argmax(|N|) picked whichever the last bits favoured:
  JVM and TeaVM differed on 4 presets (H40, H40b4, H64max, H100 with the x1.4 library), not just H40b4.
- `MeshBuilder.forceColour`: rust only if max compression > max tension x (1 + 1e-6) + 1e-9, else steel (ties and
  unloaded struts). The JVM colours of tie members change (a few thin struts rust -> steel; geometry unchanged);
  no golden stores mesh hashes. Browser check (scratch/twtest/hull.py, headless Chromium): all 7 presets now have
  identical mesh FNV (was 3 of 7). `MeshTest.strutColourStable`: unit ties / near-zero forces, and every golden strut
  (3672, q 1 and 0.3) keeps its colour under a 1e-9 relative perturbation of its forces.
- Render: /var/lib/mclab/agent/scratch/colourtie/crop_H40_close.png (H40 close, before | after: one inner diagonal
  rust -> steel).

## Material strength x1.4 (2026-10-17, Baughn: the most ridiculous towers feel dense)
- `tools/towerlib/fem.py`: `STRENGTH` (env `P3A_STRENGTH`, default now 1.4) multiplies every allowable stress:
  SIG_T 300 -> 420, SIG_C0 150 -> 210 (so sig_c(L), laced buckling and the pole bending cap SIG_B), SIG_CAB 600 ->
  840 (so BS_CAP). E (240000) and L0 unchanged. Java has no copy: `Consts` reads them from the library header (the
  spine's leg / P1 sizing uses them too, so ring half-widths shift in ~2/3 of layouts, by up to 1.5 blocks; the
  ladder top never moved in 600 random layouts, nor in the goldens).
- Library rebuilt from an empty cache (`full`, 12 procs, ~20 min): 7797 entries (199 booms; was 7845 / 214),
  `boom_failed` now empty (L45 rho 0.5 [9, 1, 6, 6] is in the cache without error), 6.0 MB gz JSON, TGLB1 1.71 MB gz.
  Infeasible (LP) keys, not cached: bays 3, seams 1, patterns 0 (were 27 / 16 / 214). Both golden sets
  regenerated (P3a-rule recipe now in the towerlib README); `StayRuleTest` asserts an empty `boom_failed`.
- Tried x1.25 first (cache /var/lib/mclab/agent/scratch/towerlib-strength/s125/cache): H100 / H40 / H24b got
  lighter but H64max did not (q1 vol 433 -> 420, laced silhouette 1163 -> 1399: its E-y24 L40 boom came out with a
  larger back-stay tension, 39 -> 48, which raised the mast's demand classes one step). x1.4 halves it.
- Numbers (towerview `info`, new `density:` line; q1 / q0.3; silhouette = sum of length x outer width of solid +
  laced members, blocks^2; "before" is x1.0):

| layout | vol x1.0 -> x1.25 -> x1.4 | members (laced) x1.0 -> x1.4 | silhouette x1.0 -> x1.4 | triangles x1.0 -> x1.4 |
|---|---|---|---|---|
| H16 | 7.6 -> 6.8 -> 5.9 / 7.8 -> 8.3 -> 7.5 | 59 (0) -> 55 (0) / 51 (8) -> 46 (3) | 21 -> 18 / 36 -> 29 | 1250 -> 1218 / 3858 -> 2318 |
| H24b | 35.3 -> 30.3 -> 25.3 / 43.1 -> 34.5 -> 32.1 | 196 (34) -> 209 (25) / 175 (51) -> 182 (41) | 133 -> 104 / 231 -> 159 | 11278 -> 9642 / 16118 -> 12950 |
| H40 | 32.4 -> 26.2 -> 23.1 / 39.3 -> 36.8 -> 30.2 | 235 (48) -> 227 (44) / 200 (54) -> 194 (46) | 134 -> 107 / 246 -> 178 | 15142 -> 14436 / 16342 -> 14074 |
| H40r3 | 167 -> 150 -> 131 / 205 -> 199 -> 153 | 536 (101) -> 514 (96) / 508 (136) -> 480 (110) | 631 -> 559 / 932 -> 759 | 43030 -> 41360 / 55282 -> 44156 |
| H40b4 | 105 -> 93 -> 90 / 125 -> 118 -> 108 | 399 (106) -> 410 (95) / 373 (132) -> 375 (113) | 439 -> 407 / 685 -> 613 | 38732 -> 35170 / 46490 -> 39836 |
| H64max | 433 -> 420 -> 208 / 522 -> 531 -> 265 | 555 (252) -> 560 (178) / 513 (267) -> 505 (198) | 1270 -> 768 / 1962 -> 1170 | 83602 -> 58160 / 87180 -> 63814 |
| H100 | 287 -> 195 -> 178 / 403 -> 300 -> 236 | 715 (212) -> 707 (188) / 601 (248) -> 591 (203) | 872 -> 644 / 1433 -> 1090 | 67136 -> 58410 / 75092 -> 61760 |

  Member counts barely move (the topology is the library's; mostly laced -> solid and thinner members), so the
  look is thinner and more open rather than fewer members. Cable -> strut conversions rise on H64max (6 -> 36 at q1)
  and H40b4 (3 -> 17), i.e. a few more thin struts where cables were. All 14 golden runs pass (util <= 1.0).
- Random slowTest: default 60 x 2 q: 94 pass / 2 over / 24 refused -> 96 / 0 / 24. 400 x 2 q seed 7: 642 / 22 / 136
  -> 657 / 7 / 136 (17 over -> pass, 2 pass -> over: the H55 eight-boom layout #299 with an N L45 boom on a near
  boom key, util 1.15 / 1.34 at the sweep's seed; it passes at seed 0, util 0.99). Volume of towers passing both: median x0.77 (p10 0.59, p90 0.96; 33 of 640 heavier).
  Hull sweep: 0 failures. Old vs new library, 600 random layouts at q1, seed 0 (scratch Cmp.java): 3 OK ->
  OVER_CATALOGUE, 10 OVER_CATALOGUE -> OK (the stitch seed matters near the limit; the game uses a per-tower seed).
- Towers already standing in a world: the server stores layout, status, seed and `ladderTop` and does not rebuild
  until a pole changes; the client rebuilds the mesh from the layout with the jar's library. So every standing tower
  is redrawn with the new (thinner) geometry on the next client load; the only blocks placed from the geometry are
  the pole parts (LADDER up to `ladderTop`, HIDDEN above), and the ladder top is unchanged, so no block changes. A
  stored OK tower whose layout is now over the catalogue keeps standing (the client draws refused results too) until
  its poles are touched, then it is refused.
- Renders: `renders/contact_strength_x14.png` (1920 x 3240, before | after: H64max a215, close, sideW, ljoint; H100
  close; H40r3 close). x1.25 renders and the comparison sheets are in /var/lib/mclab/agent/scratch/towerlib-strength.
- Solve cache: /var/lib/mclab/agent/scratch/towerlib-strength/s140/cache (7797 pickles).

## Gusset seams and brace hubs inside laced legs (2026-10-17, polish.md "Tower mesh")
- Gusset seams (Baughn: slivers / faint z-fighting where chords meet a gusset): the chords' open ends lay exactly on
  the gusset face, the chords' outer faces were the envelope's sides (the gusset's faces ran on from them at a
  shallow angle) and the end batten was centred on the face (half buried). Now: the envelope (gusset) is
  `gussetLip` 0.2 x the chord width wider on every side than the chords' outer faces (the chords stand on the face;
  no near-coplanar side faces); each chord's open end sinks into the hull by up to `chordSink` 0.5 x its width, as
  far as all four edges stay strictly inside (Hull3.clip with a negative tolerance; where the hull reaches past the
  cut along the chords, e.g. a wide strut inside a leg base, the end sinks forward into it instead); the end batten
  and the lacing start just off the gusset (batten centre 0.35 x its width past the face where the last chord edge
  leaves the hull: sunk 0.15 x, no face coplanar with the gusset's). Splice ends are unchanged.
- Brace hubs inside laced legs (small dark knuckle blocks seen through the lacing): at a node where a laced leg
  continues (chord splices), the brace hub is drawn in its widest member's part (rust / steel) instead of
  colKnuckle, so it reads as the braces meeting rather than a floating dark block. Geometry unchanged. A first
  version cut the braces at the leg's lacing planes with no hub; reverted the same day: wide or shallow braces then
  ended in mid-air (H16 W4 branchClose, H40 q0.3 node 49), i.e. visibly detached members.
- Slow random sweep (100 layouts x 2 q, seed 7): no failures, no exceptions.
- Known: where the hull covers a chord past 0.49 x its length (very short laced members), the end batten may sit
  inside the hull (as before). Hubs of braces inside laced legs are still drawn (recoloured only).
- Not changed: the 4 -> 3 chord step. Tried giving the end batten ring to the 3-chord member (the wider member's
  square ring is the step): small changes (0.77 -> 0.45) look cleaner, but large ones (1.92 -> 0.64, H40 q0.3 node
  52) leave the unmatched 4th chord as a lone post and the wide member's lacing ends poke out of its chord cuts, so
  the ring stays.
- Test `MeshTest.gussetChordEndsSunk` (goldens + H16 W4 / W5, q 1 and 0.3): every chord's open end at a gusset is
  strictly inside the hull; 5311 ends, 5245 sunk by >= 0.1 x the chord width, the rest by 0.008-0.1 x. Hull test:
  0 spiky. No test stores mesh hashes (`portableHashes` only writes build/mesh-fnv.json for the browser check), so
  there is no golden to update; the mesh FNVs of the golden layouts change.
- VisualParams: `gussetLip` 0.2, `chordSink` 0.5 (joints group; web sliders added).
- towerview shots: `branchClose` (the branch node from 2 blocks), `nodeN@DIST[:DEG[:UP]]` (close-up of node N at
  DIST blocks, DEG around from the outward radial, UP above; e.g. `node13@1.3:20:-1.3` looks up at H16 W4's gusset).
- Renders: `renders/contact_gusset_sink.png` (1920 x 3240, before | after: H16 W4 q1 node 13 from below (gusset lip,
  chords sunk, batten off the face), H16 W4 branchClose (brace hubs in member colour), H40 q0.3 node 49 from inside,
  H64max and H100 `ljoint`, H64max `close`).

## Laced members at branch nodes: envelope (gusset) joints (2026-10-13, owner report 2)
- Report: H16 W4@2 / W5@2, lower west leg: a laced member ending at a node (Y: the laced leg ends where a solid leg
  and a strut carry on) made a spiky clump: its chords went into the node hull one by one, so the hub was the hull
  of scattered chord corners.
- Fix (general, not a Y special case): every laced end that is not a chord-pair splice joins the node joint by its
  **outer envelope** (a square in the axis frame holding all its chords' sections, i.e. b x b for 4 chords), as an
  ordinary solid end: setbacks on the envelope against all neighbours (solid and laced envelopes), hull of the
  envelope cut faces (a gusset block, in the widest member's colour rather than knuckle), and all its chords are cut
  flat at the envelope's setback (straight, no bend; lacing and end battens move to the cut). Splices stay only for
  the continuing pair when no wider laced member arrives at the node: a leg with laced bracing keeps its chord
  splices, and the bracing joins the node's inner hull by its envelope, as solid bracing does (making every leg node
  with laced bracing one envelope block turned H64max / H100 legs into solid slabs, so that variant was dropped).
- Tests: `hullJointsClosedAndContained` now runs the goldens, both owner layouts and 25 random layouts at q 1 and
  0.3, samples hull vertices, edge midpoints and face points, and asserts 0 "spiky" points (farther than 0.35 x the
  widest member + 0.06 blocks outside every member envelope, laced envelopes included): 3.14 M points, 0 spiky. The
  threshold went from 0.3 to 0.35 because K-node gussets fill the crotch between two wide laced diagonals (worst
  0.64 blocks at a 1.92-wide H64max leg node). `lacedChordsStraightAndSpliced` accepts an unspliced collinear pair
  only at a node with more than two laced ends. Random sweep (slowTest): 400 towers, 106683 hulls, all consistent.
- Mesh time fell (fewer chord-level joints): H64max 14 -> 6 ms, H100 10 -> 5 ms (JVM); browser H64max 33 ms.
- Renders: `renders/contact_gusset_joints.png` (1920 x 1800, before | after: H16 W4 node 18 outside / inside (the
  Y), node 19 inside at q 0.3, H16 W4 west / east, H16 W5 west, H40r3 and H64max `branch`, H64max and H100 `close`).
  towerview: shots `branch` / `branchIn` (a node where laced members branch, nearest the first boom) and `nodeN` /
  `nodeNin` (close-up of node N from outside / inside).

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
  member whose dominant force differs between JS and JVM arithmetic (runtime pass, not the mesh). (Fixed
  2026-10-17: ties are steel, all 7 identical; see "Strut colour: ties are steel".)
- Renders: `renders/contact_hull_joints.png` (1920 x 1440; pairs before | after: H40r3 bjoint, ljoint, splice;
  H64max bjoint, ljoint, splice; H100 ljoint, splice). New towerview shot `splice` (a node where two laced members
  continue, nearest 0.3 H). Singles in renders/before_hull, renders/after_hull (git-ignored).
- Known: the hull fills the crotch between members at small angles (a gusset look, bounded as above); near-parallel
  members beyond the setback cap overlap inside each other; hubs inside laced legs (bracing only) are still drawn
  (2026-10-17: drawn in the member colour instead of colKnuckle, see "Gusset seams").

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
  refused even when short. `stayAtBoomAbove` (off) hangs the lower boom from the upper one's roots; it needs a
  library rebuild to be safe (see "Stays between rings").
- LP-infeasible boom solves are not cached, so they are not in `boom_failed` (only repair-gave-up ones are).
- Joint look: section changes on laced legs show as chord-width splice cubes; big hubs where a laced boom truss
  tapers into a solid chord; hubs of braces sit inside laced legs (2026-10-17: in the member colour, no longer dark); hubs read as a dark band in colKnuckle on solid
  legs (`hubMemberColour` / colKnuckle tune it); ground hubs reach h below ground.
- Meshes are heavy: H64max is ~110k triangles (lacing dominates). LOD (skip lacing at distance) is needed in the mod.
- The library ships in the jar as `LibraryBin` (TGLB1, lossless, 2.0 MB gz, reads in 20 ms; the JSON one was 7.8 MB
  gz); the mod and towerweb both load it (`Library.shared` -> `LibraryBin.read`).
- The interactive window was tested only under Xvfb + Mesa llvmpipe (renders, `--snap` hook); mouse, keys and the
  file-reload loop need a desktop (saya).

## Ladder well (2026-10-13, Baughn: option A)
- No central pole inside the lattice. Lattice bays (sb, st > 0) have no pole nodes. Transition bays (sb > 0,
  st = 0) carry a pole socket from HB/2 to the apex. The pole bay above clamps into it: its ghost lands on the
  socket foot. Pole bays are unchanged.
- No member but the legs enters the plan square |x|, |z| < WELL = 0.62 (shrunk to 0.8 s(y) where the transition
  faces close in). In the transition bay this applies only below HB/2. tools/towerlib p2lib.well_skip;
  TOWERLIB_WELL=0 restores the old model.
- The ladder top is the transition bay's bottom ring. Platforms go at or below it (Baughn).
- Stitch: poleChain starts with the ghost only if the pole reaches y = 0 (it no longer does), so there's no fake
  clamp at the socket foot. MeshBuilder skips the ghost only when it's there.
- Library rebuilt from an empty cache (`full`, 12 procs, ~25 min): 7845 entries (214 booms), boom_failed
  unchanged [[9, 1, 6, 6]]. Infeasible keys (now refused or looked up as near matches): bays 27, seams 16,
  patterns 214. Both golden sets regenerated (the main one under the stay rule, p3arule under stayCap none). All
  14 pass with 0 members in the well below the ladder top. Steel volume changes by -25% to +30%.
  Random slowTest: 94/120 pass, 2 over, 24 refused, the same as the old library.
- Solve cache: /var/lib/mclab/agent/scratch/wellfull2/cache on tsugumi (rebuilt 2026-10-13 with the npole fix and the lattice-top cap; 7843 entries); strength x1.0, superseded 2026-10-17 by the x1.4 cache, see "Material strength x1.4").
