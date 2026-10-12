"""python -m towerlib COMMAND [args]   (run from tools/, or with tools/ on PYTHONPATH)

Steps (each runs in its own process, because the stay rule etc. are read from the environment at import):
  run NAME [seam] [q ...]    one test layout (H16 H40 H100 H24b H40b4 H64max H40r3): spine, keys, solve the missing
                             entries, stitch, P2.5 validate -> OUT/p3araw_<tag>_q<q>.pkl (the goldens read these)
  layouts                    run all 7 test layouts, plain and seam, q 1.0 and 0.3
  cov booms|bays|seams|test|validate [N]
                             coverage phases on the 400 training layouts (p3acov_layouts.json); test/validate use
                             the 150 held-out layouts
  pat count|solve            root-pattern coverage (env P3A_MAXR, P3A_MIN_SEEN, P3A_H4ONLY)
  export                     every entry in the cache -> OUT/p3a_library.json(.gz), OUT/p3a_libstats.json
  gold                       OUT/p3araw_*_seam_* -> OUT/p3a_goldens.json(.gz)
  goldens                    layouts (seam only) under the current rule, then gold
  full                       the whole library build from an empty (or partial) cache, in the order the shipped
                             library was built: the P3a phase (stay cap none), then the stay-rule phase; then
                             export and goldens. ~30 min wall on 8 processes from scratch; cached keys are skipped.
Environment: TOWERLIB_CACHE, TOWERLIB_OUT (paths.py), P3A_PROCS (default 8), P3A_STAYCAP (same|any|none),
P3A_DCAP, P3A_BS, TOWERLIB_DATE (fixed provenance date)."""
import os, subprocess, sys, time

TEST7 = ['H16', 'H40', 'H100', 'H24b', 'H40b4', 'H64max', 'H40r3']
MODS = dict(run='p3arun', cov='p3acov', pat='p3apat', export='p3aexport', gold='p3agold', gen='p3agen', var='p3avar')


def step(cmd, *args, **env):
    e = dict(os.environ); e.update({k: str(v) for k, v in env.items()})
    t0 = time.time()
    print(f'== {cmd} {" ".join(args)} {" ".join(f"{k}={v}" for k, v in env.items())}', flush=True)
    r = subprocess.run([sys.executable, '-m', 'towerlib.' + MODS[cmd], *args], env=e,
                       cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    print(f'   {cmd} exit {r.returncode}, {time.time() - t0:.0f} s', flush=True)
    if r.returncode:
        sys.exit(r.returncode)


def layouts(seams=('', 'seam'), **env):
    for s in seams:
        for n in TEST7:
            step('run', n, *([s] if s else []), '1.0', '0.3', **env)


def full():
    none = dict(P3A_STAYCAP='none')
    # 1. P3a (2026-10-12): 7 test layouts, coverage on the training layouts, root patterns (NOTES-P3a.md sections 6, 7)
    layouts(**none)
    for ph in ('booms', 'bays', 'seams'):
        step('cov', ph, **none)
    step('pat', 'solve', P3A_MAXR=2, P3A_MIN_SEEN=3, **none)
    step('pat', 'solve', P3A_MAXR=3, P3A_MIN_SEEN=10, P3A_H4ONLY=1, **none)
    # 2. stay rule (2026-10-13, NOTES-P3a.md section 10): new boom / bay / seam keys of the training layouts
    for ph in ('booms', 'bays', 'seams'):
        step('cov', ph, P3A_STAYCAP='same')
    # 3. the test layouts again under the rule (only H40r3 has new keys: +16), then library and goldens
    layouts(seams=('seam',), P3A_STAYCAP='same')
    step('export', P3A_STAYCAP='same')
    step('gold', P3A_STAYCAP='same')


def main(argv):
    if not argv or argv[0] in ('-h', '--help', 'help'):
        print(__doc__); return
    cmd, args = argv[0], argv[1:]
    if cmd == 'full':
        full()
    elif cmd == 'layouts':
        layouts()
    elif cmd == 'goldens':
        layouts(seams=('seam',)); step('gold')
    elif cmd in MODS:
        step(cmd, *args)
    else:
        sys.exit(f'unknown command {cmd!r}; python -m towerlib --help')


if __name__ == '__main__':
    main(sys.argv[1:])
