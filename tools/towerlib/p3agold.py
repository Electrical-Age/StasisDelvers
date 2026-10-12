"""Golden runtime outputs for the 7 test layouts (q 1 and 0.3) with the chosen runtime pass (fixf, f = 1.5).
python3 p3agold.py -> p3a_goldens.json(.gz), p3af_<name>_q<q>.pkl (for rendering)"""
import os, sys
os.environ["OMP_NUM_THREADS"] = "1"
import gzip, json, pickle, time
import numpy as np
from . import p3alib as A
from . import p2lib as L
from . import p25fem as Q
from . import p25lib as L25
from . import p3avar
HERE = os.path.dirname(os.path.abspath(__file__))
from .paths import OUT
R4 = lambda a: np.round(np.asarray(a, float), 4).tolist()
CHOSEN = ('fixf', dict(f=1.5))


def golden(raw):
    prob = raw['prob']; P = prob['P']
    res, fin = p3avar.run(raw, *CHOSEN)
    bars, st, an = fin['bars'], fin['st'], fin['an']
    pr = an['pr']; cab = pr['cab']
    nb0 = len(raw['bars'])
    g = dict(
        name=raw['name'], q=raw['q'], layout=dict(H=raw['layout']['H'], booms=[list(b) for b in raw['layout']['booms']]),
        rings=[[int(y), A.q4(s)] for y, s in sorted(raw['rings'].items())],
        bay_keys=raw['keys'], bay_rot=raw['rots'], boom_keys=raw['boom_keys'], variants=raw['variants'],
        backstays=[dict(T=round(b.get('T', 0.0), 4), n=b.get('n', 0), D=round(b.get('D', 0.0), 3)) for b in raw['bsinfo']],
        stitched=dict(nodes=R4(P), members=[[int(i), int(j), int(k), int(w), int(c), round(float(f), 4)] for (i, j), k, w, c, f in
                                            zip(raw['bars'], raw['st']['kind'], raw['st']['ws'], raw['st']['cs'], raw['st']['floor'])],
                      pole=[int(x) for x in prob['pole']], pole_w=R4(raw['prob_pw0']), fixed=sorted(int(x) for x in prob['fixed']),
                      em=[int(x) for x in prob['em']], ground=[int(x) for x in prob.get('ground', [])], legs=raw['legs']),
        final=dict(members_added=[[int(i), int(j)] for i, j in bars[nb0:]],
                   sections=[[int(k), int(w), int(c)] for k, w, c in zip(st['kind'], st['ws'], st['cs'])],
                   outer=R4(pr['width']), pretension=R4(np.where(cab, an['N0'], 0.0)), pole_w=R4(prob['pw']),
                   util_per_case=res['util_per_case'], max_util=res['max_util'], min_slack=res['min_slack'],
                   vol_raw=res['vol_raw'], vol=res['vol'], rounds=res['rounds'], converted=res['converted'],
                   leg_fix=res['leg_fix'], pass_=res['pass']),
        tolerances=dict(util_max=1.0 + 1e-3, min_slack=-1e-6, vol_rel=0.05, outer_steps=1, topology='exact'))
    return g, res, fin


def main():
    out = []
    for n in A.TEST7:
        for q in (1.0, 0.3):
            fn = os.path.join(OUT, f'p3araw_{n}_seam_q{q}.pkl')
            raw = pickle.load(open(fn, 'rb'))
            raw['prob_pw0'] = raw['prob']['pw'].copy()
            t0 = time.perf_counter()
            g, res, fin = golden(raw)
            out.append(g)
            print(n, q, json.dumps({k: res[k] for k in ('pass', 'max_util', 'min_slack', 'vol_raw', 'vol', 'rounds', 'converted', 't_pass', 'n_free', 'n_cab')}), flush=True)
            # render pickle (p25shots format)
            stt = dict(name=n, q=q, layout=raw['layout'], prob=fin['prob'], bars=fin['bars'], st=fin['st'],
                       an=dict(Nk=fin['an']['Nk'], N0=fin['an']['N0']))
            prv = Q.props(fin['prob']['P'], fin['bars'], fin['st'])
            ns, nc = L.merged_counts(fin['prob']['P'], fin['bars'], fin['st'] % 2 == 1 if False else fin['st']['kind'] % 2 == 1, prv['width'],
                                     set(fin['prob']['fixed']) | set(fin['prob']['em']))
            stt['final'] = dict(struts=ns, cables=nc, laced=int((fin['st']['kind'] == 2).sum()), vol=res['vol'],
                                max_util=res['max_util'], disp=fin['an']['disp'],
                                xpairs=L.count_x(fin['prob']['P'], fin['bars'], fin['st']['kind'] == 1),
                                backstays=int((fin['st']['kind'] == 3).sum()))
            stt['raw'] = dict(max_util=res['raw_util'] or 0.0)
            stt['bsinfo'] = raw['bsinfo']; stt['booms'] = raw['booms']
            pickle.dump(stt, open(os.path.join(OUT, f'p3af_{n}_q{q}.pkl'), 'wb'))
    js = json.dumps(out, separators=(',', ':')).encode()
    open(os.path.join(OUT, 'p3a_goldens.json'), 'wb').write(js)
    open(os.path.join(OUT, 'p3a_goldens.json.gz'), 'wb').write(gzip.compress(js, 9))
    print('goldens', len(out), len(js), 'bytes')


if __name__ == '__main__':
    main()
