# tools/towerlib: the offline tower library builder (Python)

Builds `towergen/src/main/resources/towergen/p3a_library.json.gz` (the bay / boom library the Java generator looks
up), the TGLB1 binary for towerweb (via `:towergen:libraryBin`) and the goldens
`towergen/src/test/resources/p3a_goldens.json.gz`. The Java side (towergen) owns everything at runtime; this is only
needed to change the library (new keys, other loads or catalogue, new rules).

## Origin
Moved from `timestop-proto/towerlib` at commit **e060c08** ("Tower library: stay rule ...", 2026-10-13). The first
stasis commit adding this directory is a verbatim copy of those files; the next one makes them a package, so
`git log -p tools/towerlib` shows every change. Design notes stay in timestop-proto: `towerlib/NOTES-P2.md`,
`NOTES-P25.md`, `NOTES-P3a.md` (sections 1-7 the P3a library, section 10 the stay rule) and
`notes/timestop/TOWERLIB-PLAN.md`. Java port: `towergen/NOTES.md`.

| file | role |
|---|---|
| `p3alib.py` | integer keys, boom key quantisation, arbitrary H, net-moment back-stays, stay rule; patches p2spine / p2lib for H % 4 != 0 |
| `p3acov.py` | coverage phases on the training layouts (`p3acov_layouts.json`: 400 training, 150 held-out), lookup + fallback, held-out test, validation |
| `p3apat.py` | root-pattern coverage (synthetic entries) |
| `p3arun.py` | one test layout end to end (raw stitch for the goldens) |
| `p3aexport.py` | cache -> library JSON (header: key doc, ladders, constants, cases, `boom_failed`, `provenance`) |
| `p3agold.py`, `p3avar.py`, `p3afem.py` | goldens with the runtime pass (fixf f = 1.5) |
| `p3agen.py` | the random layout generator that made `p3acov_layouts.json` (seed 20261012; writes to OUT) |
| `p25lib.py`, `p25fem.py` | P2.5 bay / boom solves (pretensioned design, laced members, back-stays), stitch, validate |
| `p2lib.py`, `p2fem.py`, `p2spine.py` | P2 keys, bay / boom problems, seams, spine sweep |
| `fem.py`, `spine.py` | the LP / elastic core (HiGHS via `scipy.optimize.linprog`), P1 spine |
| `gsx.py` | the four functions used from `grow/ground_structure/gs.py` and `grow/xbrace/xb.py`, verbatim |
| `paths.py` | cache and output directories |
| `__main__.py`, `build_library.sh` | entry points |

Changes from the origin (behaviour-neutral, verified below): relative imports in one package, no `sys.path` edits
(the old `grow/ground_structure` path insert is what made `shots` resolve to the wrong module; nothing here imports
renderer code any more); the p2 / p2.5 / p3a cache juggling replaced by one cache (`paths.CACHE`; p3alib already
pointed all three at `p3acache`); outputs go to `paths.OUT` instead of the source directory; `p2run.LAYOUTS` inlined
into p3alib; BLAS threads pinned to 1 in `__init__`; the export header gains `provenance`. Not copied: renderers
(`*shots*.py`, `*sheet*.py`, `render`), diagnostics (`p3adiag_*`, `p3alearn`, `p3aseam`, `p3acovsum`, `p3at_h`),
P1 / P2 drivers and the earlier experiments.

## Running
Python 3 with numpy and scipy (HiGHS LP, scipy >= 1.6); on NixOS
`nix-shell -p 'python3.withPackages(p: [p.numpy p.scipy])'` (the script does this).

```
tools/towerlib/build_library.sh export    # cache -> library, install, libraryBin, :towergen:test  (seconds)
tools/towerlib/build_library.sh goldens   # 7 test layouts (solves missing keys) -> goldens, install, test (~1 min)
tools/towerlib/build_library.sh full      # everything: ~30 min wall on 8 processes from an empty cache
```
Env: `TOWERLIB_CACHE` (default `tools/towerlib/cache`, git-ignored), `TOWERLIB_OUT` (default `tools/towerlib/out`),
`P3A_PROCS` (8), `GRADLE` (default `./gradlew`; lab box: `/var/lib/mclab/agent/tools/stasis-build.sh`),
`NO_INSTALL=1`, `TOWERLIB_DATE` (fixed provenance date). The complete solve cache of the shipped library is
`timestop-proto/towerlib/p3acache` (7748 pickles, 56 MB): `TOWERLIB_CACHE=.../p3acache build_library.sh export`
re-exports the shipped library without solving. Single steps: `cd tools && python3 -m towerlib --help`
(`run NAME [seam] [q..]`, `cov booms|bays|seams|test|validate`, `pat count|solve`, `export`, `gold`, ...).

**The library is the union of the cache**, and the shipped one was built in two regimes, so `full` replays both:
1. P3a rule (`P3A_STAYCAP=none`): the 7 test layouts (plain and seam), `cov booms`, `cov bays`, `cov seams`,
   `pat solve` with `P3A_MAXR=2 P3A_MIN_SEEN=3`, then with `P3A_MAXR=3 P3A_MIN_SEEN=10 P3A_H4ONLY=1`;
2. stay rule (`same`): `cov booms`, `cov bays`, `cov seams`;
3. `export`, the 7 test layouts (seam) under the rule, `gold`.
A run under the stay rule alone would give a smaller library (the P3a-rule keys are still looked up as near
matches). Keys already in the cache are not re-solved, so an interrupted `full` resumes.

Known order dependence: `p3acov.lib_index` and `p3apat` read the cache in `glob` order, and the pattern phase only
solves patterns the cache lacks at that point; that is why `full` keeps the historical phase order.

## Provenance
`header.provenance` in the library: builder (stasis) commit and dirty flag, origin, UTC date, cache name and size,
solve parameters (D_CAP, BACKSTAY_MODE, the stay rule constants, any `P3A_*` overrides), Python / numpy / scipy
versions. It survives into TGLB1 (the header is stored as JSON) and is ignored by the Java loader.

## Verification (2026-10-13)
- Export from `timestop-proto/towerlib/p3acache` with this package: entries and header byte-identical to the
  shipped library (apart from the added `provenance`).
- Fresh cache, `goldens` (7 test layouts, seam, under the stay rule; 135 entries solved in ~40 s): every entry
  byte-identical (as exported JSON) to the shipped library's entry of the same key, and `p3a_goldens.json`
  byte-identical to the shipped goldens.
- Full rebuild from an empty cache: see the result line below.
