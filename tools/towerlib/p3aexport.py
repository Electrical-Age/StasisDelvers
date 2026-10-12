"""P3a library export: p3a_library.json(.gz) with integer keys, section indices and the constants Java needs to
reproduce the keys. python3 p3aexport.py"""
import glob, gzip, json, os, pickle, time
import numpy as np
from . import p3alib as A
from . import p2lib as L
from . import p2spine as S
from . import p25fem as Q
from . import fem as F
HERE = os.path.dirname(os.path.abspath(__file__))
from .paths import OUT
R4 = lambda a: np.round(np.asarray(a, float), 4).tolist()


def ser(P, bars, st, pre, pw):
    return dict(nodes=R4(P), members=[[int(i), int(j), int(k), int(w), int(c), round(float(p), 4)]
                                      for (i, j), k, w, c, p in zip(bars, st['kind'], st['ws'], st['cs'], pre)],
                pole_w=R4(pw))


def header():
    ref = S.reference()
    return dict(
        format='p3a-1',
        key_doc=A.__doc__,
        member_doc='members [i, j, kind, ws, cs, pretension]; kind 0 solid (outer WEXT[ws]), 1 cable (CAB_W[ws]), '
                   '2 laced (outer WEXT[ws], chord WEXT[cs], 3 chords if outer <= 0.7 else 4), 3 back-stay (BS_W[ws], '
                   'runtime only). pretension = recorded design pretension (cables), the runtime floor.',
        WEXT=Q.WEXT.tolist(), CAB_W=F.CAB_W.tolist(), BS_W=Q.BS_W.tolist(), WIDTHS=F.WIDTHS.tolist(),
        L_LADDER=L.L_LADDER, RHO_LADDER=L.RHO_LADDER, S_LADDER=list(S.S_LADDER), DEPTH_LADDER=S.DEPTH_LADDER,
        reference=dict(d=ref['d'].tolist(), N=ref['N'].tolist(), V=ref['V'].tolist(), M=ref['M'].tolist(), REF_H=S.REF_H),
        ladders=dict(N=[4.0, 1.4], V=[0.5, 1.4], Mg=[1.0, S.MG_R], T=[0.5, S.T_R], below=0.05,
                     class_tol=1.02, ceil_eps=1e-9, demand_eps=1e-6),
        constants=dict(BAY=S.BAY, S_CORE=S.S_CORE, W_LEG_TARGET=S.W_LEG_TARGET, MID_F=S.MID_F, M_POLE_MAX=S.M_POLE_MAX,
                       POLE_A=S.POLE_A, W_POLE_MIN=F.W_POLE_MIN, EMIT_W=F.EMIT_W, EMIT_WIND0=F.EMIT_WIND0,
                       RHO=F.RHO, CD0=F.CD0, SIG_CAB=F.SIG_CAB, SIG_T=F.SIG_T, SIG_C0=F.SIG_C0, L0=F.L0, E=F.E,
                       BS_MIN_L=A.BS_MIN_L, D_CAP=A.D_CAP, D_FRAC=A.D_FRAC, BS_T_MIN=A.BS_T_MIN, BS_CAP=A.BS_CAP,
                       Q_LOW=L.Q_LOW, PRE_MARGIN=Q.PRE_MARGIN, TAUT_PRE_MAX=Q.TAUT_PRE_MAX, LACE_W=Q.LACE_W,
                       CH_MAX=Q.CH_MAX, R_REF=Q.R_REF, STAY_CAP=A.STAY_CAP, STAY_RISE=A.STAY_RISE,
                       STAY_MIN_RATIO=A.STAY_MIN_RATIO, STAY_MIN_RISE=A.STAY_MIN_RISE),
        cases=[[c[0], None if c[1] is None else list(map(float, c[1])), c[2]] for c in F.CASES])


def provenance(n_cache):
    """Builder git commit, date, solve parameters (header['provenance']; Java ignores it)."""
    import datetime, platform, subprocess, sys
    import scipy
    from . import paths

    def git(*a):
        try:
            return subprocess.run(['git', '-C', HERE, *a], capture_output=True, text=True, timeout=10).stdout.strip()
        except Exception:
            return ''
    env = {k: v for k, v in sorted(os.environ.items()) if k.startswith('P3A_')}
    return dict(
        builder='stasis tools/towerlib (python -m towerlib)',
        builder_commit=git('rev-parse', 'HEAD'),
        builder_dirty=bool(git('status', '--porcelain', '--', '.')),
        origin='timestop-proto towerlib @ e060c08 (P3a + stay rule); see tools/towerlib/README.md',
        date=os.environ.get('TOWERLIB_DATE') or datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
        cache=os.path.basename(paths.CACHE.rstrip('/')), cache_files=n_cache,
        params=dict(D_CAP=A.D_CAP, D_FRAC=A.D_FRAC, BACKSTAY_MODE=A.BACKSTAY_MODE, STAY_CAP=A.STAY_CAP,
                    STAY_RISE=A.STAY_RISE, STAY_MIN_RATIO=A.STAY_MIN_RATIO, STAY_MIN_RISE=A.STAY_MIN_RISE,
                    rules='entries = union of the solve cache: P3a rule (stay cap none) runs, then stay rule (same)',
                    env=env),
        python=platform.python_version(), numpy=np.__version__, scipy=scipy.__version__)


def main():
    t0 = time.time()
    ents = []
    n = dict(boom=0, bay=0, seam=0); low = 0; cpu = 0.0; nmem = 0
    failed = []
    for fn in sorted(glob.glob(os.path.join(A.CACHE, '*.pkl'))):
        e = pickle.load(open(fn, 'rb'))
        if e.get('keystr', '').startswith('p3aboom') and A.boom_failed(e):
            failed.append(json.loads(e['keystr'][7:]))
            continue
        if 'error' in e:
            continue
        ks = e['keystr']
        if ks.startswith('p3aboom'):
            kind, key = 'boom', json.loads(ks[7:])
        elif ks.startswith('p3abay'):
            key = json.loads(ks[6:]); kind = 'seam' if len(key) > 12 + 6 * key[11] else 'bay'
        else:
            continue
        n[kind] += 1; cpu += e.get('solve_s', 0) + e.get('low_s', 0); nmem += len(e['bars'])
        d = dict(key=key, kind=kind, pole=e['pole'], ghost=e['ghost'], sup=e['sup'], top=e['top'],
                 q1=ser(e['P'], e['bars'], e['st'], e['pre'], e['pw']))
        if kind == 'boom':
            d.update(roots=e['roots'], anchors=e['anchors'], tip=e['tip'], reac_roots=R4(e['reac_roots']),
                     reac_anchors=R4(e['reac_anchors']))
        else:
            d.update(legs=e['legs'], reactions=R4(e['reac']), reactions_pre=R4(e['reac_pre']))
        if e.get('low'):
            lo = e['low']; low += 1
            d['q03'] = ser(lo['P'], lo['bars'], lo['st'], lo['pre'], lo['pw'])
        ents.append(d)
    hd = header()
    hd['provenance'] = provenance(len(glob.glob(os.path.join(A.CACHE, '*.pkl'))))
    hd['boom_failed'] = sorted(failed)               # solved but no valid design: layouts needing these are refused
    hd['boom_failed_doc'] = 'boom keys whose solve failed (LP infeasible or repair gave up); refuse, never fall back'
    lib = dict(header=hd, entries=ents)
    js = json.dumps(lib, separators=(',', ':')).encode()
    open(os.path.join(OUT, 'p3a_library.json'), 'wb').write(js)
    gz = gzip.compress(js, 9)
    open(os.path.join(OUT, 'p3a_library.json.gz'), 'wb').write(gz)
    st = dict(entries=len(ents), booms=n['boom'], boom_failed=len(failed), bays_plain=n['bay'], bays_seam=n['seam'], with_low_q=low,
              members=nmem, solve_cpu_s=round(cpu, 1), json_bytes=len(js), json_gz_bytes=len(gz),
              export_s=round(time.time() - t0, 1))
    json.dump(st, open(os.path.join(OUT, 'p3a_libstats.json'), 'w'))
    print(json.dumps(st))


if __name__ == '__main__':
    main()
