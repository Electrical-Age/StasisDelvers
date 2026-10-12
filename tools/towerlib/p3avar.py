"""python3 p3avar.py RAWPKL... : run the runtime-pass variants on raw P3a stitches -> p3avar_<tag>.json"""
import os, sys
os.environ["OMP_NUM_THREADS"] = "1"; os.environ["OPENBLAS_NUM_THREADS"] = "1"
import copy, json, pickle, time
import numpy as np
from . import p25lib as L25
from . import p25fem as Q
from . import p3afem as V
HERE = os.path.dirname(os.path.abspath(__file__))
from .paths import OUT
MODES = [('pinv', {}), ('xpair', {}), ('nnls', {}), ('fixf', dict(f=1.0)), ('fixf', dict(f=1.5)), ('fixf', dict(f=0.0))]


def run(raw, mode, kw):
    stt = copy.deepcopy(raw)
    prob = stt['prob']; P = prob['P']
    an0 = V.analyze_v(prob, stt['bars'], stt['st'], mode, **kw)
    raw_util = round(float(an0['max_util']), 3) if an0 is not None else None
    t0 = time.perf_counter()
    bars, st, an, info = V.widen_v(prob, stt['bars'], stt['st'], mode, **kw)
    nfix = 0
    for _ in range(3):
        nv, _, nf = L25.thick_above_thin(P, bars, st, stt['legs'], fix=True)
        nfix += nf
        if not nv:
            break
    if nfix:
        bars, st, an, info2 = V.widen_v(prob, bars, st, mode, **kw)
        for k in ('rounds', 'converted', 'mechanisms', 'bumps', 't_analyze'):
            info[k] += info2[k]
        info['per_round'] += info2['per_round']
    t_pass = time.perf_counter() - t0
    an = V.analyze_v(prob, bars, st, mode, **kw)
    pr = an['pr']; cab = pr['cab']
    old = Q.props(P, raw['bars'], raw['st'])
    out = dict(mode=mode + ('' if not kw else '_' + '_'.join(f'{k}{v}' for k, v in kw.items())),
               raw_util=raw_util, rounds=info['rounds'], mechanisms=info['mechanisms'], converted=info['converted'], leg_fix=nfix,
               max_util=round(an['max_util'], 3), pole_util=round(an['pole_util'], 3),
               min_slack=round(float(an['min_slack']), 4), slack_cases_ok=bool(an['min_slack'] >= -1e-6),
               util_per_case=[round(float(x), 3) for x in np.max(np.where(cab, an['Nk'] / np.maximum(pr['capT'], 1e-12),
                              np.maximum(an['Nk'] / pr['capT'], -an['Nk'] / np.maximum(pr['capC'], 1e-12))), axis=1)],
               vol_raw=round(float(np.sum(old['L'] * old['area'])), 2), vol=round(float(np.sum(pr['L'] * pr['area'])), 2),
               n_free=an['info']['n_free'], n_cab=an['info']['n_cab'], n_groups=an['info'].get('n_groups'),
               pre_iters_max=max(r['iters'] for r in info['per_round']) if info['per_round'] else 0,
               rhs_per_round_max=max(r['rhs'] for r in info['per_round']) if info['per_round'] else 0,
               t_pass=round(t_pass, 3), t_analyze=round(info['t_analyze'], 3), eq_err=an['eq_err'],
               pretension_sum=round(float(an['N0'][cab].sum()), 2), max_pre_ratio=round(float((an['N0'] / np.maximum(pr['capT'], 1e-12))[cab].max()), 3) if cab.any() else 0)
    out['pass'] = bool(out['max_util'] <= 1.0 + 1e-6 and out['slack_cases_ok'] and out['mechanisms'] == 0)
    return out, dict(bars=bars, st=st, prob=prob, an=an)


if __name__ == '__main__':
    if os.environ.get('P3A_MODES'):
        MODES = [m for m in MODES if m[0] in os.environ['P3A_MODES'].split(',')]
    for fn in sys.argv[1:]:
        raw = pickle.load(open(fn, 'rb'))
        tag = os.path.basename(fn)[7:-4]
        res = []
        for mode, kw in MODES:
            try:
                r, _ = run(raw, mode, kw)
            except Exception as ex:
                r = dict(mode=mode + ('' if not kw else '_' + '_'.join(f'{k}{v}' for k, v in kw.items())), error=repr(ex)[:300], **{'pass': False})
            res.append(r)
            print(tag, json.dumps({k: v for k, v in r.items() if k != 'util_per_case'}), flush=True)
        ofn = os.path.join(OUT, f'p3avar_{tag}.json')
        if os.path.exists(ofn) and os.environ.get('P3A_MODES'):
            old = [r for r in json.load(open(ofn)) if r['mode'] not in {x['mode'] for x in res}]
            res = old + res
        json.dump(res, open(ofn, 'w'), indent=0)
