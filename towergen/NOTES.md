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
  - `TowerGen`: facade, `new TowerGen(Library.shared()).build(layout, q)`.
- `towergen/layouts/`: the 7 golden layouts. `towergen/renders/`: headless renders (only the contact sheet is in git).
- `towerview/`: the viewer (`Main` CLI, `Raster` software renderer, `Interactive` LWJGL window), `shell.nix`, `run.sh`.

## Running
- Tests: `tools/stasis-build.sh :towergen:test` (goldens, mesh determinism, last-resort layouts; ~5 s, part of the
  root `build`). Slow tests: `./gradlew :towergen:slowTest [-Dtowergen.random=400 -Dtowergen.seed=7]` (timings, random
  layouts).
- Viewer: `towerview/run.sh render ../towergen/layouts/H64max.json --q 1.0 --out /tmp/tv [--shots a215,close]
  [--orbit 72] [--size 1280x720] [--ss 2] [--params visual.json]`; `run.sh params > visual.json` writes the defaults;
  `run.sh info LAYOUT.json` prints keys, tiers, util and timings; `run.sh obj LAYOUT.json out.obj`;
  `run.sh contact sheet.png --cols 3 --width 1920 a.png b.png ...`.
- Interactive (NixOS): `cd towerview && nix-shell --run './run.sh view ../towergen/layouts/H40r3.json --params visual.json'`.
  Left drag orbit, right drag target height, wheel zoom, Q/A q +/- 0.1, 1 / 3 q = 1 / 0.3, R rebuild, S save a PNG
  (software renderer), Esc quit. The layout and parameter files are polled every 0.5 s and rebuilt off-thread.

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
- The library is 7.8 MB gz in the jar; a compact binary export (1/16-block int nodes, no q0.3 duplicates) is not done.
- The interactive window was tested only under Xvfb + Mesa llvmpipe (renders, `--snap` hook); mouse, keys and the
  file-reload loop need a desktop (saya).
