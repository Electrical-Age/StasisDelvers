"""Root-pattern coverage: for every (sb4, st4, h) ring signature seen with roots in the training specs (>= MIN_SEEN
times) and every canonical root (y, d) pattern with <= MAXR roots that the library lacks, solve one synthetic plain
entry (template demand classes from a real training spec with that signature, representative boom keys).
python3 p3apat.py count|solve"""
import os, sys
os.environ["OMP_NUM_THREADS"] = "1"
import itertools, json, time, pickle
from collections import Counter, defaultdict
import p3alib as A
import p3acov as C
MAXR = int(os.environ.get('P3A_MAXR', '3'))
MIN_SEEN = int(os.environ.get('P3A_MIN_SEEN', '3'))


def canon_pat(pat):
    return min(tuple(sorted((y, (d - r) % 4) for y, d in pat)) for r in range(4))


def collect():
    idx = C.lib_index()
    sig_specs = defaultdict(list); boomc = defaultdict(Counter)
    for l in C.LAY['train']:
        try:
            sp, specs = C.spine_with_lookup(l, idx)
        except RuntimeError:
            continue
        bents = {A.boom_keystr(b['ikey']): b['entry'] for b in sp['booms']}
        for s in specs:
            if s['sb'] >= 0.75 and s['st'] >= 0.75:
                k = s['ikey']; sig = (k[0], k[1], k[10])
                sig_specs[sig].append((s, bents))
                for rt in C.split_key(k)[1]:
                    boomc[sig][tuple(rt[2:])] += 1
    have = defaultdict(set)
    for k in idx['bay']:
        have[(k[0], k[1], k[10])].add(canon_pat([(r[0], r[1]) for r in C.split_key(k)[1]]))
    return idx, sig_specs, boomc, have


def jobs_for(idx, sig_specs, boomc, have):
    jobs = []
    for sig, lst in sig_specs.items():
        if len(lst) < MIN_SEEN:
            continue
        h = sig[2]
        pos = [(y, d) for y in range(1, h + 1) for d in range(4)]
        pats = set()
        for nr in range(1, (MAXR if h == 4 or not os.environ.get('P3A_H4ONLY') else 2) + 1):
            for comb in itertools.combinations(pos, nr):
                pats.add(canon_pat(comb))
        missing = sorted(pats - have[sig])
        # template: the training spec with this signature and the most roots (its demand classes / mg)
        tmpl, tb = max(lst, key=lambda x: (len(x[0]['key']['roots']), x[0]['env']['Mw']))
        bk = boomc[sig].most_common(1)[0][0] if boomc[sig] else None
        if bk is None or A.boom_keystr(bk) not in {A.boom_keystr(k) for k in idx['boom']}:
            bk = max(idx['boom'], key=lambda k: (k[2] == sig[0], -abs(k[0] - 5), k))
        be = A.load(A.boom_keystr(bk)); be['ikey'] = bk
        for pat in missing:
            hd = list(tmpl['ikey'][:12])
            kmg, mga = hd[6], (hd[7] if hd[6] >= 0 else None)
            roots = [(y, d, tuple(bk)) for y, d in pat]
            r = A.canon_rot(roots, mga)
            croots = sorted((y, (d - r) % 4) + tuple(bk) for y, d, _ in roots)
            mgc = (mga - 2 * r) % 8 if mga is not None else -1
            hd[7] = mgc; hd[11] = len(croots)
            ikey = tuple(hd) + tuple(x for rt in croots for x in rt)
            spec = dict(tmpl); key = dict(tmpl['key'])
            key['mg'] = [kmg, mgc]
            key['roots'] = [[rt[0], rt[1], A.boom_keystr(rt[2:])] for rt in croots]
            spec['key'] = key; spec['ikey'] = ikey; spec['above'] = None
            ks = 'p3abay' + json.dumps(list(ikey))
            if A.cached(ks):
                continue
            jobs.append(dict(kind='bay', key=key, ikey=ikey, keystr=ks, spec=spec, boom_entries={A.boom_keystr(bk): be}))
    return jobs


if __name__ == '__main__':
    idx, sig_specs, boomc, have = collect()
    jobs = jobs_for(idx, sig_specs, boomc, have)
    sigs = {s: len(v) for s, v in sig_specs.items()}
    C.log(f'pattern coverage: {len(sigs)} ring signatures with roots-capable rings ({sum(v >= MIN_SEEN for v in sigs.values())} seen >= {MIN_SEEN}x), {len(jobs)} synthetic jobs (<= {MAXR} roots)')
    if sys.argv[1] == 'solve':
        C.run_pool(jobs, 'patterns')
