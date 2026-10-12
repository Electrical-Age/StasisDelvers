"""TOWERLIB P2.5: P2's keyed library with pretensioned design (p25fem), laced built-up members and boom back-stays.
Reuses p2lib's keys, bay / boom problems and seam logic. Cache: p25cache/ (never touches p2cache_v2)."""
import os
os.environ['P2CACHE'] = os.environ.get('P2CACHE_P25', 'p25cache')     # p2lib creates its cache dir on import
import hashlib, json, math, pickle, sys, time
import numpy as np
import fem as F
import spine as S1
import p2spine as S
import p2lib as L
import p25fem as Q

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, 'p25cache')
os.makedirs(CACHE, exist_ok=True)
assert L.CACHE == CACHE, L.CACHE
TOL = 1e-6
DIRS = L.DIRS
rot, rkey, khash = L.rot, L.rkey, L.khash
BS_MIN_L = 14            # booms at least this long get back-stays
NO_POLE_CABLES = True
LOW_VOL_MAX = 1.6        # a q = 0.3 variant may weigh at most this much more than its q = 1 entry
SEAM_PRE = True          # seam loads include the bay above's pretension reactions
BS_PEN = 1.0             # per-wire cost (volume units) in the back-stay choice


# ================================================================ back-stays
def backstays(bl, d, yb, ya, sa, H, rings):
    """bl: gravity-case (pos, force) loads the boom puts on the tower. Back-stays from the far-side corners of the
    anchor ring (or the pole top) to two ground anchors behind the tower. The common tension T is chosen to minimise
    the peak gravity-case moment the boom leaves in the mast over 0..ya. Wire count: the fewest wires (>= 2) whose
    tension fits the back-stay ladder; the per-wire cost BS_PEN makes extra pairs lose unless capacity forces them.
    Returns (wires [(attach, ground, T)], info)."""
    q = DIRS[d]
    ax = rot(np.array([0, 0, 1.0]), q)
    D = 0.75 * ya + sa
    best = None
    for npair in (1, 2):
        lev = [ya] + ([y for y in sorted(rings) if y > ya][:1] if npair == 2 else [])
        if len(lev) < npair:
            continue
        att, gnd = [], []
        for y in lev:
            s = rings[y]
            for sg in (1, -1):
                att.append(rot(np.array([-s, 0, sg * s]), q) + [0, y, 0] if s > 0 else np.array([0.0, y, 0]))
                gnd.append(rot(np.array([-D, 0, sg * (sa + 0.35 * D)]), q))
        att, gnd = np.array(att), np.array(gnd)
        uv = (gnd - att) / np.linalg.norm(gnd - att, axis=1)[:, None]
        ys = np.arange(0, ya + 1, 0.5)
        Mb = np.array([sum(np.cross(p - [0, y, 0], f) @ ax for p, f in bl if p[1] > y - 1e-9) for y in ys])
        mw = np.array([sum(np.cross(a - [0, y, 0], u) @ ax for a, u in zip(att, uv) if a[1] > y - 1e-9) for y in ys])
        Ts = np.linspace(0, 4 * np.abs(Mb).max() / max(1.0, np.abs(mw).max()), 801)
        peak = np.array([np.abs(Mb + T * mw).max() for T in Ts])
        i = int(np.argmin(peak)); T = Ts[i]
        cap = F.SIG_CAB * Q.BS_W[-1] ** 2 / 1.6           # leave room for the load-induced change
        vol = float(np.sum(np.linalg.norm(gnd - att, axis=1) * T / (F.SIG_CAB / 1.6)))
        feas = T <= cap
        cost = vol + BS_PEN * len(att) + (0 if feas else 1e6)
        cand = dict(npair=npair, T=float(T), att=att, gnd=gnd, peak=float(peak[i]), peak0=float(np.abs(Mb).max()),
                    cost=cost, feasible=feas, D=D)
        if best is None or cand['cost'] < best['cost']:
            best = cand
    wires = [(a, g, best['T']) for a, g in zip(best['att'], best['gnd'])] if best['T'] > 0.3 else []
    return wires, dict(T=best['T'], n=len(wires), peak=best['peak'], peak0=best['peak0'], feasible=best['feasible'],
                       D=best['D'])


def bs_loads(wires, d, yb):
    return [(a, T * (g - a) / np.linalg.norm(g - a), ('anchor', 'bs', d, yb, i)) for i, (a, g, T) in enumerate(wires)]


def spine25(layout, solve_booms):
    """P2 spine with back-stay pull added (constant over cases, = its design pretension) in both passes."""
    H = layout['H']
    booms = layout.get('booms', [])
    rings_tmp = {y: 1.5 for y in range(0, H + 1, S.BAY)}
    if booms:
        l1, b1 = S1.point_loads(layout, rings_tmp)
        loads1 = [[(p, f, t) for p, f, t in pl] for pl in l1]
        for bi, ((yb, d, Lb), bb) in enumerate(zip(booms, b1)):
            if Lb < BS_MIN_L:
                continue
            g = [(p, f) for p, f, t in l1[0] if t != 'top' and t[1] == bi]
            w, _ = backstays(g, d, yb, bb['ya'], rings_tmp[bb['ya']], H, rings_tmp)
            for k in range(F.K):
                loads1[k] += bs_loads(w, d, yb)
    else:
        loads1 = S.top_loads(H)
    p1 = S.sweep(layout, None, loads1)
    rings = p1['rings']
    bk = [L.boom_key(rings, H, yb, Lb) for (yb, d, Lb) in booms]
    bents = solve_booms([k for k, _ in bk]) if booms else []
    loads2 = S.top_loads(H)
    binfo = []
    for (yb, d, Lb), (key, ya), be in zip(booms, bk, bents):
        sr = S.ring_s_at(rings, yb); sa = rings[ya]
        bl = L.boom_loads_world(be, yb, d, sr, ya, sa)
        for k in range(F.K):
            loads2[k] += bl[k]
        wires, wi = [], dict(n=0)
        if Lb >= BS_MIN_L:
            wires, wi = backstays([(p, f) for p, f, t in bl[0]], d, yb, ya, sa, H, rings)
            for k in range(F.K):
                loads2[k] += bs_loads(wires, d, yb)
        binfo.append(dict(yb=yb, d=d, L=Lb, ya=ya, sr=sr, sa=sa, key=key, entry=be, wires=wires, bsinfo=wi))
    p2 = S.sweep(layout, rings, loads2)
    p2['booms'] = binfo
    return p2


# ================================================================ solving, q variants
def degrade25(prob, sol, q, seed, log, is_boom=False, amp_f=1.0, pdrop=0.85):
    """Low-q variant: wonky interior nodes and a kinked pole, single cables instead of X, randomly fatter struts and
    one visibly mismatched leg, then taut stabilise + pretensioned repair (never shrinks)."""
    rng = np.random.default_rng(seed)
    P = prob['P'].copy()
    keepn = set(prob['fixed']) | set(prob.get('top', [])) | set(prob.get('em', [])) | set(prob.get('keep', []))
    if is_boom:
        keepn |= {prob['tip']}
    pole = set(prob['pole'])
    pole_bay = (not is_boom) and len(prob.get('legs') or []) == 0
    amp = 0.55 * (1 - q) * amp_f
    for i in range(len(P)):
        if i in keepn:
            continue
        if i in pole:
            # kinked pole only where the pole is the structure (pole bays); a kinked pole inside a loaded lattice
            # bay picks up bending, gets bumped to the catalogue max and its weight snowballs (P2.5 H64 q0.3)
            if pole_bay:
                P[i] += 1.2 * amp * np.array([rng.uniform(-1, 1), 0, rng.uniform(-1, 1)])
        else:
            P[i] += amp * np.array([rng.uniform(-1, 1), rng.uniform(-0.3, 0.3), rng.uniform(-1, 1)])
    prob2 = dict(prob); prob2['P'] = P; prob2['pw'] = sol['pw'].copy() if len(sol['pw']) else prob['pw'].copy()
    bars = sol['bars'].copy(); st = Q.st_copy(sol['st'])
    cab = st['kind'] == 1
    cb = np.nonzero(cab)[0]
    drop = set()
    for a in range(len(cb)):
        for b in range(a + 1, len(cb)):
            i, j = cb[a], cb[b]
            if i in drop or j in drop or set(bars[i]) & set(bars[j]):
                continue
            p, r = P[bars[i, 0]], P[bars[i, 1]] - P[bars[i, 0]]
            q_, s = P[bars[j, 0]], P[bars[j, 1]] - P[bars[j, 0]]
            A = np.array([[r @ r, -r @ s], [r @ s, -s @ s]])
            if abs(np.linalg.det(A)) < 1e-9:
                continue
            t, u = np.linalg.solve(A, [(q_ - p) @ r, (q_ - p) @ s])
            if 0.05 < t < 0.95 and 0.05 < u < 0.95 and np.linalg.norm(p + t * r - q_ - u * s) < 0.5:
                if rng.uniform() < pdrop:
                    drop.add(int(rng.choice([i, j])))
    keep = np.array([b not in drop for b in range(len(bars))])
    bars = bars[keep]; st = Q.st_take(st, keep)
    for b in range(len(bars)):
        if st['kind'][b] in (0, 2) and not st['pole'][b] and rng.uniform() < 0.3:
            st['ws'][b] = min(st['ws'][b] + int(rng.integers(1, 3)), (len(Q.WEXT) if st['kind'][b] == 2 else Q.I_SOLID_MAX + 1) - 1)
    legs = prob.get('legs') or []
    if legs:
        ch = legs[int(rng.integers(len(legs)))]
        segs = {tuple(sorted((ch[k], ch[k + 1]))) for k in range(len(ch) - 1)}
        for b, (i, j) in enumerate(bars):
            if tuple(sorted((i, j))) in segs and st['kind'][b] in (0, 2):
                st['ws'][b] = min(st['ws'][b] + 3, len(Q.WEXT) - 1)
                if st['kind'][b] == 0 and Q.WEXT[st['ws'][b]] > Q.LACE_W:
                    st['kind'][b] = 2; st['cs'][b] = max(1, st['ws'][b] - 6)
    sec = st['ws'].copy(); cab = st['kind'] % 2 == 1; n0 = len(bars)
    bars, cab2, sec2, _, added = F.stabilise(prob2, bars, cab.copy(), sec, np.zeros(len(bars), int), log)
    st = Q.st_extend(st, len(bars) - n0)
    conv = np.nonzero(cab[:n0] & ~cab2[:n0])[0]
    st['kind'][conv] = 0; st['ws'][conv] = 0; st['floor'][conv] = 0
    st['pole'] = Q.new_state(bars, st['kind'] % 2 == 1, st['ws'], prob2)['pole']
    st, nb, an = Q.repair25(prob2, bars, st, False, log, its=20)
    if an is None:
        raise RuntimeError('degraded variant is a mechanism')
    pr = an['pr']
    return dict(P=P, bars=bars, cab=st['kind'] % 2 == 1, st=st, width=pr['width'], chord=pr['chord'],
                pre=np.where(st['kind'] == 1, an['t'], 0.0), pw=prob2['pw'].copy(), dropped=len(drop),
                taut_util=an['max_util'])


def solve_entry25(job):
    os.environ['OMP_NUM_THREADS'] = '1'
    fn = os.path.join(CACHE, khash(job['keystr']) + '.pkl')
    if os.path.exists(fn):
        return pickle.load(open(fn, 'rb'))
    t0 = time.time()
    logs = []
    try:
        if job['kind'] == 'boom':
            prob = L.boom_problem(job['key'])
        else:
            ab = job.get('above_entry')
            if ab is not None and SEAM_PRE:
                ab = dict(ab); ab['reac'] = ab['reac'] + ab['reac_pre'][None]
            prob = L.bay_problem(job['spec'], job['boom_entries'], ab)
            if NO_POLE_CABLES and job['key']['sb'] > 0 and job['key']['st'] > 0:
                # lattice bays: no cables to the pole. A pretensioned cable anchored on the pole closes its
                # self-stress through the pole and the base, so the pole compression of every bay above
                # accumulates down the tower (H40: pole N0 -37 at the base). Face / ring cables close locally.
                pn = set(prob['pole'])
                ok = np.array([not (c and (i in pn or j in pn)) for (i, j), c in zip(prob['bars'].tolist(), prob['cab'])])
                for k in ('bars', 'cab', 'mand', 'amin'):
                    prob[k] = prob[k][ok]
        sol = Q.design25(prob, closure=prob['sym'], log=logs.append)
        t1 = time.time()
        st = sol['st']; st['floor'] = sol['pre'].copy()
        ent = dict(P=prob['P'], bars=sol['bars'], cab=sol['cab'], st=st, width=sol['width'], chord=sol['chord'],
                   pre=sol['pre'], pw=sol['pw'], pole=list(prob['pole']), ghost=sorted(prob['ghost']),
                   sup=list(prob.get('sup', [])), top=list(prob.get('top', [])), sym=prob.get('sym', False),
                   key=job['key'], keystr=job['keystr'], kind=job['kind'], solve_s=t1 - t0,
                   taut_util=sol['taut_util'], pre_ratio=sol['pre_ratio'], laced=sol['laced'], eq_err=sol['eq_err'],
                   pole_util=sol['pole_util'])
        ent['xpairs'] = L.count_x(prob['P'], sol['bars'], sol['cab'])
        if job['kind'] == 'boom':
            R = Q.reactions25(prob, sol['bars'], st, prob['roots'] + prob['anchors'])
            ent['reac_roots'] = R[:, :2]; ent['reac_anchors'] = R[:, 2:]
            ent.update(roots=prob['roots'], anchors=prob['anchors'], tip=prob['tip'], inner=prob['inner'])
        else:
            prob['pw'] = sol['pw'].copy()
            ent['reac'] = Q.reactions25(prob, sol['bars'], st, prob['sup'])
            ent['reac_pre'] = Q.reactions_pre(prob, sol['bars'], st, sol['an'], prob['sup'])
            ent['legs'] = prob['legs']
        seed = int(khash(job['keystr'])[:8], 16)
        ent['low'] = None
        p1 = Q.props(prob['P'], sol['bars'], st); v1 = float(np.sum(p1['L'] * p1['area']))
        for amp, pdrop in ((1.0, 0.85), (0.5, 0.6), (0.25, 0.5), (0.0, 0.5), (0.0, 0.0)):
            try:
                if job['kind'] == 'boom':
                    amp = amp * 0.3          # jittered truss chords bend: keep booms nearly straight
                lo = degrade25(prob, sol, L.Q_LOW, seed, logs.append, job['kind'] == 'boom', amp, pdrop)
                if lo['taut_util'] > 1.05:
                    raise RuntimeError(f"low variant util {lo['taut_util']:.2f}")
                p2_ = Q.props(lo['P'], lo['bars'], lo['st']); v2 = float(np.sum(p2_['L'] * p2_['area']))
                if v2 > LOW_VOL_MAX * v1 and (amp, pdrop) != (0.0, 0.0):
                    raise RuntimeError(f"low variant volume {v2 / v1:.2f}x")
                lo['vol_ratio'] = v2 / v1
                lo['st']['floor'] = lo['pre'].copy()
                ent['low'] = lo; lo['amp'] = amp
                break
            except Exception as ex:
                logs.append(f'degrade failed ({amp}, {pdrop}): {ex!r}')
        ent['low_s'] = time.time() - t1
        ent['log'] = logs
    except Exception as ex:
        import traceback
        return dict(error=repr(ex) + traceback.format_exc()[-600:], keystr=job['keystr'], log=logs)
    pickle.dump(ent, open(fn + f'.tmp{os.getpid()}', 'wb')); os.replace(fn + f'.tmp{os.getpid()}', fn)
    return ent


def cached(keystr):
    return os.path.exists(os.path.join(CACHE, khash(keystr) + '.pkl'))


def build(layout, pool, log=print, seam=True):
    t0 = time.perf_counter()
    boom_ents = {}
    stats = dict(solved=0, hits=0)

    def solve_many(jobs):
        todo = [j for j in jobs if not cached(j['keystr'])]
        stats['hits'] += len(jobs) - len(todo); stats['solved'] += len(todo)
        res = pool.map(solve_entry25, todo, chunksize=1) if todo else []
        for r in res:
            if 'error' in r:
                raise RuntimeError(f"{r['keystr']}: {r['error']} {r['log'][-3:]}")
        return [solve_entry25(j) for j in jobs]

    def solve_booms(keys):
        jobs = [dict(kind='boom', key=k, keystr=json.dumps(k, sort_keys=True)) for k in keys]
        ents = solve_many(jobs)
        for j, e in zip(jobs, ents):
            boom_ents[j['keystr']] = e
        return ents
    sp = spine25(layout, solve_booms=solve_booms)
    specs = L.bay_specs(layout, sp)
    t_spine = time.perf_counter() - t0
    jobs = {}
    for s in specs:
        jobs.setdefault(s['plainkey'], dict(kind='bay', key=json.loads(s['plainkey']), keystr=s['plainkey'], spec=s,
                                            boom_entries={k: boom_ents[k] for k in {r[2] for r in s['key']['roots']}}))
    ents = dict(zip(jobs, solve_many(list(jobs.values()))))
    if seam:
        jobs2 = {}
        for s in specs:
            if s['above'] is None:
                continue
            jobs2.setdefault(s['seamkey'], dict(kind='bay', key=json.loads(s['seamkey']), keystr=s['seamkey'], spec=s,
                                                above_entry=ents[s['above']['keystr']],
                                                boom_entries={k: boom_ents[k] for k in {r[2] for r in s['key']['roots']}}))
        ents.update(zip(jobs2, solve_many(list(jobs2.values()))))
    t_lib = time.perf_counter() - t0 - t_spine
    use = [ents[s['seamkey'] if seam else s['plainkey']] for s in specs]
    return dict(layout=layout, sp=sp, specs=specs, entries=use, boom_entries=boom_ents, t_spine=t_spine,
                t_lib=t_lib, solve_stats=stats, all_entries=ents)


# ================================================================ stitch
def variant(ent, q, seed, tag):
    if q >= L.module_threshold(seed, tag) or ent.get('low') is None:
        return ent['P'], ent['bars'], ent['st'], ent['pw'], 'q1'
    lo = ent['low']
    return lo['P'], lo['bars'], lo['st'], lo['pw'], 'low'


def stitch(b, q=1.0, seed=1):
    t0 = time.perf_counter()
    layout = b['layout']; H = layout['H']
    pts, idx = [], {}

    def node(p):
        k = rkey(p)
        if k not in idx:
            idx[k] = len(pts); pts.append(np.array(p, float))
        return idx[k]
    ghost = node([0, -2.0, 0])
    bars, sts, owner = [], [], []
    pole_nodes, pole_w = {}, {}
    vlist, legs = [], []
    for bi, (spec, ent) in enumerate(zip(b['specs'], b['entries'])):
        Pl, bl, stl, pwl, v = variant(ent, q, seed, f'bay{spec["y0"]}')
        vlist.append(v)
        Pw = rot(Pl, spec['r']) + [0, spec['y0'], 0]
        gset = set(ent['ghost'])
        loc = {i: node(p) for i, p in enumerate(Pw) if i not in gset}
        for k, pi in enumerate(ent['pole']):
            if pi in gset:
                continue
            gi = loc[pi]
            pole_nodes[gi] = Pw[pi][1]
            pole_w[gi] = max(pole_w.get(gi, 0), float(pwl[k]) if k < len(pwl) else F.W_POLE_MIN)
        for ch in ent.get('legs') or []:
            legs.append([loc[i] for i in ch])
        for m, (i, j) in enumerate(bl):
            bars.append((loc[i], loc[j])); owner.append(('bay', bi))
        sts.append(stl)
    em = [node([0, H, 0])]
    ground = []
    for k, bo in enumerate(b['sp']['booms']):
        be = bo['entry']
        Pl, bl, stl, _, v = variant(be, q, seed, f'boom{k}')
        vlist.append(v)
        q4 = DIRS[bo['d']]
        Lc, sr = be['key']['Lc'], be['key']['sr']
        loc = {}
        for i, p in enumerate(Pl):
            if i in be['roots']:
                pw_ = rot(np.array([bo['sr'], 0, np.sign(p[2]) * bo['sr']]), q4) + [0, bo['yb'], 0]
            elif i in be['anchors']:
                if bo['sa'] > 0:
                    pw_ = rot(np.array([bo['sa'], 0, np.sign(p[2]) * bo['sa']]), q4) + [0, bo['ya'], 0]
                else:
                    pw_ = np.array([0.0, bo['ya'], 0])
            else:
                x = bo['sr'] + (p[0] - sr) * (bo['L'] - bo['sr']) / (Lc - sr)
                pw_ = rot(np.array([x, p[1], p[2]]), q4) + [0, bo['yb'], 0]
            loc[i] = node(pw_)
        for (i, j) in bl:
            bars.append((loc[i], loc[j])); owner.append(('boom', k))
        sts.append(stl)
        em.append(loc[be['tip']])
        # back-stays: kind 3 cables to ground anchors (visual only, fixed nodes at y = 0)
        for wi, (a, g, T) in enumerate(bo.get('wires', [])):
            ia, ig = node(a), node(g)
            ground.append(ig)
            bars.append((ia, ig)); owner.append(('bs', k))
            ws = int(min(np.searchsorted(Q.BS_W ** 2, 1.6 * T / F.SIG_CAB), len(Q.BS_W) - 1))
            sts.append(dict(kind=np.array([3]), ws=np.array([ws]), cs=np.array([0]), pole=np.array([False]),
                            floor=np.array([T])))
    st = {k: np.concatenate([s[k] for s in sts]) for k in sts[0]}
    P = np.array(pts)
    pole = [ghost] + sorted(pole_nodes, key=lambda i: pole_nodes[i])
    pw = np.array([F.W_POLE_MIN] + [max(pole_w.get(i, F.W_POLE_MIN), F.W_POLE_MIN) for i in pole[1:]])
    fixed = {ghost} | {i for i, p in enumerate(P) if abs(p[1]) < TOL}
    bars = np.array(bars, int)
    area = Q.props(P, bars, st)['area']
    seen = {}
    keep = np.ones(len(bars), bool)
    for bb, (i, j) in enumerate(bars):
        kk = tuple(sorted((i, j)))
        if i == j:
            keep[bb] = False; continue
        if kk in seen:
            o = seen[kk]
            fl = max(st['floor'][o], st['floor'][bb])
            if area[bb] > area[o] or (st['kind'][o] % 2 == 1 and st['kind'][bb] % 2 == 0):
                keep[o] = False; seen[kk] = bb; st['floor'][bb] = fl if st['kind'][bb] % 2 == 1 else 0
            else:
                keep[bb] = False; st['floor'][o] = fl if st['kind'][o] % 2 == 1 else 0
        else:
            seen[kk] = bb
    bars = bars[keep]; st = Q.st_take(st, keep)
    ext = F.emitter_loads(P, em)
    cand = L.G.candidates(P, 4.6)
    cand = cand[[not (a in fixed and b_ in fixed) for a, b_ in cand.tolist()]]
    prob = dict(P=P, fixed=fixed, pole=pole, pw=pw, ext=ext, em=em, ghost={ghost}, bars=np.vstack([bars, cand]),
                ground=ground)
    st['pole'] = Q.new_state(bars, st['kind'] % 2 == 1, st['ws'], prob)['pole']
    owner = [o for o, k in zip(owner, keep) if k]
    return dict(prob=prob, bars=bars, st=st, owner=owner, variants=vlist, legs=legs, t_stitch=time.perf_counter() - t0)


# ================================================================ leg monotonicity
def leg_segments(P, bars, legs):
    idx = {tuple(sorted(b)): m for m, b in enumerate(bars.tolist())}
    segs = []
    for ch in legs:
        for a, b_ in zip(ch[:-1], ch[1:]):
            lo, hi = (a, b_) if P[a][1] < P[b_][1] else (b_, a)
            m = idx.get(tuple(sorted((a, b_))))
            if m is not None:
                segs.append((lo, hi, m))
    return segs


def thick_above_thin(P, bars, st, legs, fix=False):
    """Count leg segments wider (drawn outer width) than the leg segment below them. fix=True copies the upper
    section onto the lower one (top-down), so legs never get thinner downwards; returns (#violations, max ratio,
    #fixed)."""
    segs = leg_segments(P, bars, legs)
    by_top = {}
    for lo, hi, m in segs:
        by_top.setdefault(hi, []).append(m)
    nviol, worst, nfix = 0, 1.0, 0
    for lo, hi, m in sorted(segs, key=lambda s: -P[s[1]][1]):
        for mb in by_top.get(lo, []):
            w = Q.props(P, bars[[m, mb]], Q.st_take(st, [m, mb]))['width']
            if w[0] > w[1] * 1.001:
                nviol += 1; worst = max(worst, float(w[0] / w[1]))
                if fix:
                    if st['kind'][m] == 2 and st['kind'][mb] == 0:
                        st['kind'][mb] = 2; st['cs'][mb] = st['cs'][m]
                    st['ws'][mb] = max(st['ws'][mb], st['ws'][m])
                    if st['kind'][mb] == 2:
                        st['cs'][mb] = max(st['cs'][mb], 0)
                    nfix += 1
    return nviol, worst, nfix


def summary(prob, bars, st, an):
    pr = an['pr']
    return dict(max_util=round(an['max_util'], 3), pole_util=round(an['pole_util'], 3), disp=round(an['disp'], 3),
                disp_load=round(an['disp_load'], 3), min_slack=round(float(an['min_slack']), 4),
                slack_cases_ok=bool(an['min_slack'] >= -1e-6), pre_unreached=an['pre_unreached'],
                pre_iters=an['pre_iters'], eq_err=float(an['eq_err']),
                vol=float(np.sum(pr['L'] * pr['area'])), vol_cables=float(np.sum((pr['L'] * pr['area'])[pr['cab']])),
                laced=int((st['kind'] == 2).sum()), backstays=int((st['kind'] == 3).sum()),
                bs_util=float(an['util'][st['kind'] == 3].max()) if (st['kind'] == 3).any() else None,
                max_outer=float(pr['width'][~pr['cab']].max()), max_chord=float(pr['chord'][st['kind'] == 2].max()) if (st['kind'] == 2).any() else None,
                cable_util=float(an['util'][pr['cab']].max()) if pr['cab'].any() else 0.0)


def validate(stt, log=print, widen=True):
    prob = stt['prob']; P = prob['P']
    t0 = time.perf_counter()
    bars, st = stt['bars'], stt['st']
    an = Q.analyze(prob, bars, st)
    stt['raw'] = summary(prob, bars, st, an) if an is not None else dict(max_util=np.inf)
    stt['raw']['xpairs'] = L.count_x(P, bars, st['kind'] == 1)
    stt['raw']['tat'] = thick_above_thin(P, bars, st, stt['legs'])[:2]
    log('  raw: ' + json.dumps(stt['raw'], default=str))
    if widen:
        t1 = time.perf_counter()
        prob['pw0'] = prob['pw'].copy()
        bars2, st2, an2, info = Q.widen25(prob, bars, st, log)
        tat0 = thick_above_thin(P, bars2, st2, stt['legs'])
        nfix = 0
        for _ in range(3):
            nv, _, nf = thick_above_thin(P, bars2, st2, stt['legs'], fix=True)
            nfix += nf
            if not nv:
                break
        if nfix:
            bars2, st2, an2, info2 = Q.widen25(prob, bars2, st2, log)
            for k in ('rounds', 'converted', 'added', 'bumps', 'pre_raised'):
                info[k] = info[k] + info2[k] if k != 'pre_raised' else info2[k]
        info['leg_fix'] = nfix; info['tat_after_widen'] = tat0[:2]
        info['t_widen'] = time.perf_counter() - t1
        old = Q.props(P, bars, st); new = Q.props(P, bars2, st2)
        info['vol_added'] = float(np.sum(new['L'] * new['area']) - np.sum(old['L'] * old['area']))
        nb0 = len(bars)
        info['widened'] = int(((st2['kind'][:nb0] != st['kind']) | (st2['ws'][:nb0] != st['ws']) | (st2['cs'][:nb0] != st['cs'])).sum())
        stt['owner'] = stt['owner'] + [('widen', -1)] * (len(bars2) - nb0)
        stt['bars'], stt['st'] = bars2, st2
        stt['widen'] = info
        log('  widen: ' + json.dumps(info, default=str))
        bars, st, an = bars2, st2, an2
    an = Q.analyze(prob, bars, st)
    stt['an'] = dict(N0=an['N0'], Nk=an['Nk'], util=an['util'], t=an['t'])
    fi = summary(prob, bars, st, an)
    fi['xpairs'] = L.count_x(P, bars, st['kind'] == 1)
    fi['tat'] = thick_above_thin(P, bars, st, stt['legs'])[:2]
    t2 = time.perf_counter()
    fi['strict'] = Q.strict_diag(prob, bars, st)
    fi['t_strict'] = time.perf_counter() - t2
    cab = st['kind'] % 2 == 1
    ns, nc = L.merged_counts(P, bars, cab, Q.props(P, bars, st)['width'], set(prob['fixed']) | set(prob['em']))
    fi.update(struts=ns, cables=nc, nodes=len(set(bars.ravel())), raw_members=len(bars))
    stt['final'] = fi
    log('  final: ' + json.dumps(fi, default=str))
    stt['t_validate'] = time.perf_counter() - t0
    return stt
