"""python3 p3arun.py NAME [seam] [q ...]: build (P3a keys / back-stays) + stitch + P2.5 validate.
Writes p3araw_<tag>_q<q>.pkl (raw stitch, for the runtime-variant comparison) and p3a_<tag>_q<q>.pkl."""
import os, sys
os.environ["OMP_NUM_THREADS"] = "1"; os.environ["OPENBLAS_NUM_THREADS"] = "1"
import copy, json, pickle, time
from multiprocessing import Pool
from . import p3alib as A
HERE = os.path.dirname(os.path.abspath(__file__))
from .paths import OUT
if __name__ == '__main__':
    name = sys.argv[1]; seam = 'seam' in sys.argv[2:]
    qs = [float(a) for a in sys.argv[2:] if a[0].isdigit()] or [1.0]
    tag = name + ('_seam' if seam else '') + os.environ.get('P3A_TAG', '')
    lg = open(os.path.join(OUT, f'p3alog_{tag}.log'), 'a')
    def log(*a):
        print(*a, flush=True); print(*a, file=lg, flush=True)
    with Pool(int(os.environ.get('P3A_PROCS', '8'))) as pool:
        b = A.build(A.LAYOUTS[name], pool, log, seam=seam)
    log(f'== {tag}: rings {b["sp"]["rings"]}')
    log(f'  spine+specs {b["t_spine"]*1000:.1f} ms, library {b["t_lib"]:.1f} s, {b["solve_stats"]}')
    for s, e in zip(b['specs'], b['entries']):
        log(f"  bay {s['y0']:3d} r {s['r']} ikey {s['ikey']} | taut_util {e['taut_util']:.2f} X {e['xpairs']} laced {e['laced']}")
    for bo in b['sp']['booms']:
        log(f"  boom {bo['d']} y{bo['yb']} L{bo['L']} ikey {bo['ikey']} ya {bo['ya']} | bs {json.dumps(bo['bsinfo'], default=float)}")
    os.environ['OMP_NUM_THREADS'] = '4'
    for q in qs:
        st = A.stitch(b, q=q)
        st['layout'] = b['layout']; st['name'] = name; st['q'] = q; st['t_spine'] = b['t_spine']
        st['booms'] = [(bo['yb'], bo['d'], bo['L'], bo['ya']) for bo in b['sp']['booms']]
        st['bsinfo'] = [bo['bsinfo'] for bo in b['sp']['booms']]
        st['rings'] = b['sp']['rings']
        st['keys'] = [list(s['seam_ikey'] if seam else s['ikey']) for s in b['specs']]
        st['rots'] = [s['r'] for s in b['specs']]
        st['boom_keys'] = [list(bo['ikey']) for bo in b['sp']['booms']]
        pickle.dump(st, open(os.path.join(OUT, f'p3araw_{tag}_q{q}.pkl'), 'wb'))
        log(f'-- {tag} q {q}: stitched {len(st["bars"])} members, {len(st["prob"]["P"])} nodes, variants {st["variants"]}')
        st = A.validate(st, log)
        pickle.dump(st, open(os.path.join(OUT, f'p3a_{tag}_q{q}.pkl'), 'wb'))
