"""P3a coverage: python3 p3acov.py booms|bays|seams|test|validate
booms : pass-1 spine on the training layouts -> unique boom keys -> solve (8 procs)
bays  : full spine (boom entries from the library, nearest-key fallback if a boom failed) -> plain bay keys -> solve
seams : seam keys of the training layouts -> solve
test  : held-out layouts: exact-hit / fallback-tier rates (bays, seams, booms, whole layouts) -> p3acov_test.json
validate N : stitch + chosen runtime pass (fixf f=1.5) on N held-out layouts that hit fallbacks -> p3acov_val.json
"""
import os, sys
os.environ["OMP_NUM_THREADS"] = "1"; os.environ["OPENBLAS_NUM_THREADS"] = "1"
import copy, glob, json, pickle, time
import numpy as np
from multiprocessing import Pool
import p3alib as A
HERE = os.path.dirname(os.path.abspath(__file__))
LAY = json.load(open(os.path.join(HERE, 'p3acov_layouts.json')))
LOG = open(os.path.join(HERE, 'p3acov.log'), 'a')


def log(*a):
    print(*a, flush=True); print(*a, file=LOG, flush=True)


class Pass1(Exception):
    pass


def pass1_keys(layout):
    got = []

    def sb(keys):
        got.extend(keys); raise Pass1()
    try:
        A.spine3a(layout, sb)
    except Pass1:
        pass
    except A.Refused as ex:                       # stay rule (p3alib.anchor_levels): the game refuses the layout
        log(f'  refused layout {layout}: {ex}')
    return got


def run_pool(jobs, tag, procs=int(os.environ.get('P3A_PROCS', '8'))):
    t0 = time.time(); n_ok = n_err = 0; cpu = 0.0
    with Pool(procs) as pool:
        for k, e in enumerate(pool.imap_unordered(A.solve_job, jobs, chunksize=1)):
            if 'error' in e:
                n_err += 1; log(f'  {tag} ERROR {e["keystr"][:80]}: {e["error"][:200]}')
            else:
                n_ok += 1; cpu += e.get('solve_s', 0) + e.get('low_s', 0)
            if (k + 1) % 50 == 0:
                log(f'  {tag}: {k + 1}/{len(jobs)} done, {time.time() - t0:.0f} s wall')
    log(f'{tag}: {n_ok} solved, {n_err} errors, wall {time.time() - t0:.0f} s, solve cpu {cpu:.0f} s')
    return dict(ok=n_ok, err=n_err, wall=time.time() - t0, cpu=cpu)


# ---------------------------------------------------------------- library index + fallback
def lib_index():
    """All solved entries in the cache: booms {ikey: keystr}, plain bays {ikey: keystr}, seams {ikey: keystr}."""
    idx = dict(boom={}, bay={}, seam={}, boom_failed=set())
    for fn in glob.glob(os.path.join(A.CACHE, '*.pkl')):
        e = pickle.load(open(fn, 'rb'))
        if e.get('keystr', '').startswith('p3aboom') and A.boom_failed(e):
            idx['boom_failed'].add(tuple(json.loads(e['keystr'][7:])))
            continue
        if 'error' in e:
            continue
        ks = e['keystr']
        if ks.startswith('p3aboom'):
            idx['boom'][tuple(json.loads(ks[7:]))] = ks
        elif ks.startswith('p3abay'):
            k = tuple(json.loads(ks[6:]))
            (idx['seam'] if len(k) > 12 + 6 * k[11] else idx['bay'])[k] = ks
    return idx


def under(n, c, w=1.0):
    return w * (2 * (n - c) if c < n else (c - n))


def boom_dist(n, c):
    if (n[3] == 0) != (c[3] == 0):
        return None
    return under(n[0], c[0], 4) + 2 * abs(n[1] - c[1]) + abs(n[2] - c[2]) + abs(n[3] - c[3])


def nearest_boom(bk, booms):
    best = None
    for c in booms:
        d = boom_dist(bk, c)
        if d is not None and (best is None or (d, c) < best[0]):
            best = ((d, c), c)
    return best[1] if best else None


def split_key(k):
    nr = k[11]
    head = k[:12]
    roots = [tuple(k[12 + 6 * i: 18 + 6 * i]) for i in range(nr)]
    rest = k[12 + 6 * nr:]
    return head, roots, rest


def rot_roots(roots, kk):
    return sorted((r[0], (r[1] + kk) % 4) + tuple(r[2:]) for r in roots)


def bay_dist(n, c, kk):
    """n, c: plain integer keys; candidate rotated by kk quarter turns. Roots matched by (y, d) after rotation."""
    hn, rn, _ = split_key(n); hc, rc, _ = split_key(c)
    d = sum(under(hn[i], hc[i]) for i in (3, 4, 5, 6, 8))            # kd kn kv kmg kt
    if hn[6] >= 0 and hc[6] >= 0:
        a = abs(((hc[7] + 2 * kk) % 8) - hn[7]); d += min(a, 8 - a)
    elif (hn[6] >= 0) != (hc[6] >= 0):
        d += 2
    d += abs(hn[9] - hc[9])
    cm = {(r[0], r[1]): r[2:] for r in rot_roots(rc, kk)}
    for r in rn:
        bd = boom_dist(r[2:], cm[(r[0], r[1])])
        d += bd if bd is not None else 6
    return d


def nearest_bay(n, bays, superset=False, relax_mid=False):
    """Tier 'same pattern' (superset=False) or 'superset pattern'. Returns (candidate key, kk) or None."""
    hn, rn, _ = split_key(n)
    pn = {(r[0], r[1]) for r in rn}
    best = None
    for c in bays:
        if c[:2] != n[:2] or c[10] != n[10] or (c[2] != n[2] and not relax_mid):
            continue
        _, rc, _ = split_key(c)
        for kk in range(4):
            pc = {(r[0], r[1]) for r in rot_roots(rc, kk)}
            if (pc == pn) if not superset else (pc > pn):
                d = bay_dist(n, c, kk) + (len(pc) - len(pn)) * 3 + 3 * abs(c[2] - n[2])
                if best is None or (d, kk, c) < best[0]:
                    best = ((d, kk, c), (c, kk))
    return best[1] if best else None


def lookup_bay(spec, idx, seam=True):
    """-> (tier, keystr, kk). tiers: seam_exact, seam_near (own key exact, nearest above), plain_exact,
    plain_near (same rings/mid/root pattern), plain_super (root pattern superset), none."""
    n = tuple(spec['ikey'])
    if seam and spec['above'] is not None:
        sk = tuple(spec['seam_ikey'])
        if sk in idx['seam']:
            return 'seam_exact', idx['seam'][sk], 0
        own = [k for k in idx['seam'] if k[:len(n)] == n]
        if own:
            ab = tuple(spec['above']['ikey'])
            def dab(k):
                c = k[len(n) + 2:]
                if c[:3] != ab[:3] or c[10] != ab[10] or len(split_key(c)[1]) != len(split_key(ab)[1]):
                    return 1e9
                try:
                    return bay_dist(ab, c, 0)
                except KeyError:
                    return 1e9
            bestk = min(own, key=lambda k: (dab(k), k))
            if dab(bestk) < 1e9:
                return 'seam_near', idx['seam'][bestk], 0
    if n in idx['bay']:
        return 'plain_exact', idx['bay'][n], 0
    r = nearest_bay(n, idx['bay'])
    if r:
        return 'plain_near', idx['bay'][r[0]], r[1]
    r = nearest_bay(n, idx['bay'], relax_mid=True)
    if r:
        return 'plain_near_mid', idx['bay'][r[0]], r[1]
    r = nearest_bay(n, idx['bay'], superset=True, relax_mid=True)
    if r:
        return 'plain_super', idx['bay'][r[0]], r[1]
    return 'none', None, 0


def spine_with_lookup(layout, idx, stats=None):
    """Full spine; boom keys looked up exactly, else nearest boom (stats records the tiers)."""
    def sb(keys):
        out = []
        for k in keys:
            k = tuple(k)
            if k in idx.get('boom_failed', ()):
                raise A.Refused(f'no valid boom design for key {k}')
            if k in idx['boom']:
                ks = idx['boom'][k]; t = 'exact'
            else:
                c = nearest_boom(k, idx['boom'])
                ks = idx['boom'][c] if c else None; t = 'near' if c else 'none'
            if stats is not None:
                stats.append(t)
            if ks is None:
                raise RuntimeError(f'no boom for {k}')
            e = A.load(ks); e['ikey'] = k; out.append(e)
        return out
    sp = A.spine3a(layout, sb)
    return sp, A.bay_specs(layout, sp)


# ---------------------------------------------------------------- phases
def phase_booms():
    keys = set()
    t0 = time.time()
    for l in LAY['train']:
        keys.update(tuple(k) for k in pass1_keys(l))
    log(f'booms: {len(keys)} unique keys from {len(LAY["train"])} training layouts (pass 1 {time.time() - t0:.1f} s)')
    jobs = [A.boom_job(k) for k in sorted(keys, key=lambda k: -k[0]) if not A.cached(A.boom_keystr(k))]
    return run_pool(jobs, 'booms')


def phase_bays(seam=False):
    idx = lib_index()
    jobs = {}; t0 = time.time(); nspecs = 0; bt = []
    for l in LAY['train']:
        try:
            sp, specs = spine_with_lookup(l, idx, bt)
        except (RuntimeError, A.Refused) as ex:
            log(f'  skip layout {l}: {ex}'); continue
        bents = {b['entry']['keystr']: b['entry'] for b in sp['booms']}
        bents = {A.boom_keystr(b['ikey']): b['entry'] for b in sp['booms']}
        for s in specs:
            nspecs += 1
            be = {k: bents[k] for k in {r[2] for r in s['key']['roots']}}
            if not seam:
                if s['plainkey'] not in jobs and not A.cached(s['plainkey']):
                    jobs[s['plainkey']] = dict(kind='bay', key=s['key'], ikey=s['ikey'], keystr=s['plainkey'], spec=s, boom_entries=be)
            elif s['above'] is not None and s['seamkey'] not in jobs and not A.cached(s['seamkey']):
                ab = A.load(s['above']['keystr'])
                if ab is None or 'error' in ab:
                    continue
                jobs[s['seamkey']] = dict(kind='bay', key=s['key'], ikey=s['seam_ikey'], keystr=s['seamkey'], spec=s,
                                          boom_entries=be, above_entry=ab)
    log(f'{"seams" if seam else "bays"}: {nspecs} bay specs, {len(jobs)} new unique keys (spine {time.time() - t0:.1f} s); boom tiers {dict((t, bt.count(t)) for t in set(bt))}')
    return run_pool(list(jobs.values()), 'seams' if seam else 'bays')


def phase_test():
    idx = lib_index()
    log(f'library: {len(idx["boom"])} booms, {len(idx["bay"])} plain bays, {len(idx["seam"])} seam bays')
    res = []
    for li, l in enumerate(LAY['test']):
        bt = []
        try:
            sp, specs = spine_with_lookup(l, idx, bt)
        except RuntimeError as ex:
            res.append(dict(i=li, layout=l, error=str(ex))); continue
        tiers_seam = [lookup_bay(s, idx, True)[0] for s in specs]
        tiers_plain = [lookup_bay(s, idx, False)[0] for s in specs]
        res.append(dict(i=li, layout=l, boom_tiers=bt, seam_tiers=tiers_seam, plain_tiers=tiers_plain))
    json.dump(res, open(os.path.join(HERE, 'p3acov_test.json'), 'w'))
    summarize(res)


def summarize(res):
    from collections import Counter
    bt = Counter(t for r in res for t in r.get('boom_tiers', []))
    st = Counter(t for r in res for t in r.get('seam_tiers', []))
    pt = Counter(t for r in res for t in r.get('plain_tiers', []))
    ok = [r for r in res if 'error' not in r]
    lay_exact_seam = sum(all(t == 'seam_exact' or (t == 'plain_exact' and k == len(r['seam_tiers']) - 1) for k, t in enumerate(r['seam_tiers'])) and all(b == 'exact' for b in r['boom_tiers']) for r in ok)
    lay_exact_plain = sum(all(t == 'plain_exact' for t in r['plain_tiers']) and all(b == 'exact' for b in r['boom_tiers']) for r in ok)
    lay_cov = sum(all(t != 'none' for t in r['seam_tiers']) for r in ok)
    nb0 = [r for r in ok if not r['layout']['booms']]
    s = dict(layouts=len(res), errors=len(res) - len(ok), boom_tiers=dict(bt), seam_mode_tiers=dict(st),
             plain_mode_tiers=dict(pt), layouts_all_exact_seam_mode=lay_exact_seam,
             layouts_all_exact_plain_mode=lay_exact_plain, layouts_fully_covered=lay_cov,
             bare_layouts=len(nb0), bare_all_exact=sum(all(t in ('seam_exact', 'plain_exact') for t in r['seam_tiers']) for r in nb0))
    log('test summary: ' + json.dumps(s))
    return s


def phase_validate(N):
    sys.path.insert(0, HERE)
    import p3afem as V, p3avar
    idx = lib_index()
    res = json.load(open(os.path.join(HERE, 'p3acov_test.json')))
    cand = [r for r in res if 'error' not in r and (any(t not in ('seam_exact', 'plain_exact') for t in r['seam_tiers']) or any(b != 'exact' for b in r['boom_tiers']))
            and all(t != 'none' for t in r['seam_tiers'])]
    cand.sort(key=lambda r: r['i'])
    step = max(1, len(cand) // N)
    pick = cand[::step][:N]
    out = []
    for r in pick:
        l = r['layout']
        t0 = time.perf_counter()
        sp, specs = spine_with_lookup(l, idx)
        ents = []
        for s in specs:
            tier, ks, kk = lookup_bay(s, idx, True)
            s['r'] = (s['r'] + kk) % 4
            ents.append(A.load(ks))
        b = dict(layout=l, sp=sp, specs=specs, entries=ents)
        t_lookup = time.perf_counter() - t0
        row = dict(i=r['i'], layout=l, tiers=r['seam_tiers'], boom_tiers=r['boom_tiers'])
        for q in (1.0, 0.3):
            try:
                st = A.stitch(b, q=q)
                vr, _ = p3avar.run(st, 'fixf', dict(f=1.5))
                row[f'q{q}'] = {k: vr[k] for k in ('pass', 'max_util', 'min_slack', 'mechanisms', 'rounds', 'converted', 'vol_raw', 'vol', 't_pass', 'n_free', 'n_cab', 'leg_fix')}
            except Exception as ex:
                import traceback
                row[f'q{q}'] = dict(error=repr(ex)[:300] + traceback.format_exc()[-300:], **{'pass': False})
        row['t_lookup'] = t_lookup
        out.append(row)
        log('validate ' + json.dumps({k: v for k, v in row.items() if k not in ('tiers',)}, default=str))
    json.dump(out, open(os.path.join(HERE, 'p3acov_val.json'), 'w'), default=str)
    for q in ('q1.0', 'q0.3'):
        log(f'validate {q}: {sum(o[q].get("pass", False) for o in out)}/{len(out)} pass')


if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'booms':
        phase_booms()
    elif cmd == 'bays':
        phase_bays(False)
    elif cmd == 'seams':
        phase_bays(True)
    elif cmd == 'test':
        phase_test()
    elif cmd == 'validate':
        phase_validate(int(sys.argv[2]) if len(sys.argv) > 2 else 20)
