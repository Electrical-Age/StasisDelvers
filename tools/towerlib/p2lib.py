"""TOWERLIB P2: keyed library of bay and boom modules, seam-aware bay loads, base flare, canonical booms, q variants,
stitching and the runtime widening pass.

Frames: world = rot^r(canonical), rot(x, y, z) = (-z, y, x) (a quarter turn E -> S). Wind case k (1..8, at 45(k-1) deg)
maps to case 1 + (k - 1 + 2r) mod 8 under r quarter turns. Boom entries are solved pointing +x (E).
"""
import hashlib, json, math, os, pickle, sys, time
import numpy as np
import fem as F
import spine as S1
import p2spine as S
import p2fem as PF

sys.path.insert(0, '/var/lib/mclab/agent/timestop-proto/grow/ground_structure')
import gs as G

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, os.environ.get('P2CACHE', 'p2cache_v2'))
os.makedirs(CACHE, exist_ok=True)
TOL = 1e-6
DIRS = {'E': 0, 'S': 1, 'W': 2, 'N': 3}
L_LADDER = [2, 3, 4, 6, 8, 11, 16, 22, 32, 45, 64, 90, 128]
RHO_LADDER = [0.35, 0.5, 0.7, 1.0, 1.4, 2.0, 2.8]
Q_LOW = 0.3


def rkey(p):
    return tuple(int(round(v * 1000)) for v in p)


def rot(p, q):
    p = np.array(p, float)
    for _ in range(q % 4):
        p = np.array([-p[..., 2], p[..., 1], p[..., 0]]).T if p.ndim > 1 else np.array([-p[2], p[1], p[0]])
    return p


def perm(k, q):
    return k if (k == 0 or k > 8) else 1 + (k - 1 + 2 * q) % 8


def rot_cases(arr, q):
    """arr (K, ..., 3) in frame A -> frame rot^q(A): rotate vectors and permute cases."""
    out = np.zeros_like(arr)
    for k in range(F.K):
        v = arr[k]
        out[perm(k, q)] = rot(v.reshape(-1, 3), q).reshape(v.shape)
    return out


def khash(keystr):
    return hashlib.sha1(keystr.encode()).hexdigest()[:16]


# ================================================================ booms
def boom_key(rings, H, yb, L):
    sr = round(S.ring_s_at(rings, yb) * 16) / 16
    ys = [y for y in sorted(rings) if y >= yb + max(S.BAY, 0.7 * L) - 1e-9]
    ya = ys[0] if ys else H
    sa = rings[ya]
    R = ya - yb
    Lc = next(x for x in L_LADDER if x >= L - 1e-9)
    rho = max([r for r in RHO_LADDER if r <= R / L + 1e-9] or [RHO_LADDER[0]])
    return dict(t='boom', Lc=Lc, rho=rho, sr=sr, sa=sa), ya


def boom_problem(key):
    Lc, rho, sr, sa = key['Lc'], key['rho'], key['sr'], key['sa']
    Rc = rho * Lc
    pts, fixed = [], set()
    roots = [len(pts), len(pts) + 1]; pts += [[sr, 0, sr], [sr, 0, -sr]]
    if sa > 0:
        anchors = [2, 3]; pts += [[sa, Rc, sa], [sa, Rc, -sa]]
    else:
        anchors = [2]; pts += [[0, Rc, 0]]
    fixed = set(roots + anchors)
    span = Lc - sr
    n = max(1, int(math.ceil(span / 3.6)))
    longb = Lc > 12
    inner = []
    for i in range(1, n + 1):
        t = i / n
        x = sr + span * t
        if i == n:
            tip = len(pts); pts.append([x, 0, 0]); inner.append(tip)
            continue
        b = max(0.3, sr * (1 - t) * 0.85 + 0.3 * t)
        inner += [len(pts), len(pts) + 1]; pts += [[x, 0, b], [x, 0, -b]]
        if longb:
            h = 0.35 + 0.9 * (1 - t)
            inner.append(len(pts)); pts.append([x, h, 0])
    P = np.array(pts, float)
    sel = roots + inner
    sub = P[sel]
    pairs = G.candidates(sub + [0, 10, 0], 7.6)          # y offset: gs drops pairs with both nodes at y == 0
    sb, cb = [], []
    for a, b in pairs.tolist():
        i, j = sel[a], sel[b]
        if i in fixed and j in fixed:
            continue
        L = np.linalg.norm(P[i] - P[j])
        sb.append((i, j))
        if L <= 12:
            cb.append((i, j))
    for a in anchors:
        for j in inner:
            cb.append((a, j))
    bars = np.array(sb + cb, int)
    cab = np.array([False] * len(sb) + [True] * len(cb))
    em = [tip]
    ext = F.emitter_loads(P, em)
    m = len(bars)
    return dict(P=P, fixed=fixed, pole=[], pw=np.zeros(0), bars=bars, cab=cab, mand=np.zeros(m, bool),
                amin=np.zeros(m), ext=ext, ghost=set(), em=em, roots=roots, anchors=anchors, inner=inner, tip=tip,
                sym=False)


# ================================================================ spine with booms
def boom_loads_world(bentry, yb, d, sr_act, ya, sa_act):
    """Per case point loads (pos, f, tag) the boom puts on the tower, from the boom entry's reactions, rotated to
    direction d and placed at the actual root / anchor nodes."""
    q = DIRS[d]
    Rr = rot_cases(bentry['reac_roots'], q)
    Ra = rot_cases(bentry['reac_anchors'], q)
    rp = [rot([sr_act, 0, s * sr_act], q) + [0, yb, 0] for s in (1, -1)]
    ap = [rot([sa_act, 0, s * sa_act], q) + [0, ya, 0] for s in (1, -1)] if sa_act > 0 else [np.array([0.0, ya, 0])]
    out = [[] for _ in range(F.K)]
    for k in range(F.K):
        for j, p in enumerate(rp):
            out[k].append((p, Rr[k, j], ('root', d, yb, j)))
        if len(ap) == Ra.shape[1]:
            for j, p in enumerate(ap):
                out[k].append((p, Ra[k, j], ('anchor', d, yb, j)))
        else:                                             # anchor count mismatch: lump
            for j, p in enumerate(ap):
                out[k].append((p, Ra[k].sum(0) / len(ap), ('anchor', d, yb, j)))
    return out


def spine(layout, boom_lib=None, solve_booms=None):
    """Pass 1 (P1 min-norm boom estimate) chooses rings; booms are keyed and solved (solve_booms(keys) -> entries);
    pass 2 recomputes the resultants with the boom entries' reactions."""
    H = layout['H']
    booms = layout.get('booms', [])
    rings_tmp = {y: 1.5 for y in range(0, H + 1, S.BAY)}
    if booms:
        l1, _ = S1.point_loads(layout, rings_tmp)
        loads1 = [[(p, f, t) for p, f, t in pl] for pl in l1]
    else:
        loads1 = S.top_loads(H)
    p1 = S.sweep(layout, None, loads1)
    rings = p1['rings']
    bk = []
    for (yb, d, L) in booms:
        key, ya = boom_key(rings, H, yb, L)
        bk.append((key, ya))
    bents = solve_booms([k for k, _ in bk]) if booms else []
    loads2 = S.top_loads(H)
    binfo = []
    for (yb, d, L), (key, ya), be in zip(booms, bk, bents):
        sr = S.ring_s_at(rings, yb); sa = rings[ya]
        bl = boom_loads_world(be, yb, d, sr, ya, sa)
        for k in range(F.K):
            loads2[k] += bl[k]
        binfo.append(dict(yb=yb, d=d, L=L, ya=ya, sr=sr, sa=sa, key=key, entry=be))
    p2 = S.sweep(layout, rings, loads2)
    p2['booms'] = binfo
    return p2


# ================================================================ bay specs and keys
def canon_rot(roots, mga):
    """Pick r (0..3) minimising the canonical (roots, moment axis)."""
    best = None
    for r in range(4):
        rr = tuple(sorted((y, (d - r) % 4, bk) for y, d, bk in roots))
        ma = (mga - 2 * r) % 8 if mga is not None else -1
        cand = (rr, ma)
        if best is None or cand < best[0]:
            best = (cand, r)
    return best[1]


def bay_specs(layout, sp):
    H = layout['H']
    rings = sp['rings']
    specs = []
    roots_all = []
    for bi, b in enumerate(sp['booms']):
        roots_all.append((b['yb'], DIRS[b['d']], json.dumps(b['key'], sort_keys=True), bi))
    for y0 in range(0, H, S.BAY):
        y1 = y0 + S.BAY
        sb, st = rings[y0], rings[y1]
        ifc = sp['ifc'][y1]
        Rt = ifc['R'].copy(); Mt = ifc['M'].copy()
        for c in range(F.K):
            for p, f, tag in ifc['conc'][c]:
                if tag == 'top' or tag[0] == 'anchor':
                    Rt[c] += f; Mt[c] += np.cross(p - [0, y1, 0], f)
        roots = [(round(yb - y0, 3), d, bk) for yb, d, bk, bi in roots_all if y0 + TOL < yb <= y1 + TOL]
        rootb = [bi for yb, d, bk, bi in roots_all if y0 + TOL < yb <= y1 + TOL]
        N = float(max(0.0, (-Rt[:, 1]).max()))
        Mg = Mt[0] * [1, 0, 1]
        Vw = float(np.hypot(Rt[:, 0], Rt[:, 2]).max())
        Mw = float(max(np.linalg.norm((Mt[c] - Mt[0]) * [1, 0, 1]) for c in range(F.K)))
        T = float(np.abs(Mt[:, 1]).max())
        kd = S.demand_class(0.0, 0.0, Mw)
        Nr, Vr, Mr = S.demand_loads(kd)
        kn, nv = (-1, Nr) if N <= 1.02 * Nr else S.ladder(N, 4.0, 1.4)
        kv, vv = (-1, Vr) if Vw <= 1.02 * Vr else S.ladder(Vw, 0.5, 1.4)
        kmg, mgv = S.ladder(float(np.linalg.norm(Mg)), 1.0, S.MG_R)
        mga = None
        if kmg >= 0:
            mga = int(round(math.degrees(math.atan2(Mg[2], Mg[0])) / 45)) % 8
        kt, tv = S.ladder(T, 0.5, S.T_R)
        r = canon_rot([(y, d, bk) for y, d, bk in roots], mga)
        mid = bool(sb > 0 and st > 0 and S.leg_force(Mw + np.linalg.norm(Mg), N, sb) > S.MID_F)
        pw = F.W_POLE_MIN
        Nc, Vc, Mc = max(nv, Nr), max(vv, Vr), Mr
        if st == 0:
            need = Mc + mgv + (4 if sb == 0 else 2) * Vc
            pw = max(F.W_POLE_MIN, (1.15 * need / 25.0) ** (1 / 3))
            pw = float(F.WIDTHS[np.searchsorted(F.WIDTHS, pw - 1e-9)])
        key = dict(t='bay', sb=sb, st=st, mid=mid, kd=kd, kn=kn, kv=kv, mg=[kmg, (mga - 2 * r) % 8 if mga is not None else -1],
                   kt=kt, pw=pw, roots=sorted([[y, (d - r) % 4, bk] for y, d, bk in roots]))
        specs.append(dict(y0=y0, y1=y1, sb=sb, st=st, r=r, key=key, rootb=rootb,
                          env=dict(N=N, Vw=Vw, Mw=Mw, Mg=float(np.linalg.norm(Mg)), T=T),
                          cls=dict(N=Nc, V=Vc, M=Mc, Mg=mgv, T=tv)))
    for i, s in enumerate(specs):
        s['plainkey'] = json.dumps(dict(s['key'], above=''), sort_keys=True)
    for i, s in enumerate(specs):
        if i + 1 < len(specs):
            ab = specs[i + 1]
            s['above'] = dict(keystr=ab['plainkey'], r=ab['r'])
            s['seamkey'] = json.dumps(dict(s['key'], above=ab['plainkey'], dr=(ab['r'] - s['r']) % 4), sort_keys=True)
        else:
            s['above'] = None
            s['seamkey'] = s['plainkey']
    return specs


# ================================================================ bay problem (canonical frame)
def corners(s, y):
    return [np.array([s * sx, y, s * sz]) for sx, sz in ((1, 1), (-1, 1), (-1, -1), (1, -1))]


def mg_vec(key, mgv):
    k, a = key['mg']
    if k < 0:
        return np.zeros(3)
    return mgv * np.array([math.cos(math.radians(45 * a)), 0, math.sin(math.radians(45 * a))])


def distribute(ext, P, ct, pt, pmid_top, st, Fv, Mv):
    """Apply a top-interface resultant (force Fv, moment Mv about the top centre) to the top ring / pole top."""
    if st > 0:
        for i in ct:
            x, _, z = P[i] - P[pt]
            f = Fv / 4.0
            f = f + np.array([0, Mv[2] * x / (4 * st ** 2) - Mv[0] * z / (4 * st ** 2), 0])
            f = f + Mv[1] / (8 * st ** 2) * np.array([z, 0, -x])
            ext[i] += f
    else:
        ext[pt] += Fv
        Fc = np.array([-Mv[2] / 2, 0, Mv[0] / 2])
        ext[pt] += Fc
        ext[pmid_top] -= Fc


def canonical_resultants(spec):
    """Per case (F, M) at the bay top (canonical frame) from the class values."""
    c = spec['cls']
    mg = mg_vec(spec['key'], c['Mg'])
    out = []
    for k, (nm, wv, ts) in enumerate(F.CASES):
        Fv = np.array([0, -c['N'], 0.0]); Mv = mg.copy()
        if wv is not None:
            Fv = Fv + c['V'] * wv
            Mv = Mv + c['M'] * np.array([wv[2], 0, -wv[0]])
        if ts:
            Mv = Mv + np.array([0, ts * c['T'], 0])
        out.append((Fv, Mv))
    return out


def bay_problem(spec, boom_entries, above_entry=None):
    key = spec['key']
    sb, st, pw = key['sb'], key['st'], key['pw']
    pts, fixed = [], set()

    def add(p, fx=False):
        k = rkey(p)
        for i, q in enumerate(pts):
            if rkey(q) == k:
                return i
        pts.append(np.array(p, float))
        if fx:
            fixed.add(len(pts) - 1)
        return len(pts) - 1
    ghost = add([0, -2.0, 0], True)
    pb = add([0, 0.0, 0], True)
    cb = [add(p, True) for p in corners(sb, 0.0)] if sb > 0 else []
    roots_y = sorted({r[0] for r in key['roots'] if r[0] < 4 - TOL})
    pole_y = sorted({2.0} | set(roots_y))
    pmid = [add([0, y, 0]) for y in pole_y]
    pt = add([0, 4.0, 0])
    ct = [add(p) for p in corners(st, 4.0)] if st > 0 else []
    legs = []
    leg_y = sorted(set(roots_y) | ({2.0} if key['mid'] else set()))
    for k in range(4):
        if sb <= 0:
            break
        a = pts[cb[k]]
        b = pts[ct[k]] if st > 0 else pts[pt]
        chain = [cb[k]] + [add(a + y / 4.0 * (b - a)) for y in leg_y] + [ct[k] if st > 0 else pt]
        legs.append(chain)
    P = np.array(pts)
    pole = [ghost, pb] + pmid + [pt]
    wide = max(sb, st) >= 2.5
    bars, cab, is_pole = F.candidates(P, fixed, pole, {ghost}, rmax_s=9.5 if wide else F.RMAX,
                                      rmax_c=14.0 if wide else F.RMAX_CAB)
    legset = {tuple(sorted((ch[i], ch[i + 1]))) for ch in legs for i in range(len(ch) - 1)}
    have = {(tuple(b), c) for b, c in zip(bars.tolist(), cab)}
    extra = [b for b in legset if (b, False) not in have]
    if extra:
        bars = np.vstack([bars, extra]); cab = np.concatenate([cab, [False] * len(extra)])
        is_pole = np.concatenate([is_pole, [False] * len(extra)])
    is_leg = np.array([tuple(sorted(b)) in legset and not c for b, c in zip(bars.tolist(), cab)])
    mand = is_pole | is_leg
    amin = np.where(is_pole, pw ** 2, np.where(is_leg, F.AREAS[0], 0.0))
    n = len(P)
    ext = np.zeros((F.K, n, 3))
    can = canonical_resultants(spec)
    ptop_mid = pmid[-1] if pmid else pb
    if above_entry is not None:
        dr = (spec['above']['r'] - spec['r']) % 4
        R = rot_cases(above_entry['reac'], dr)            # (K, nsup, 3)
        sup = rot(above_entry['P'][above_entry['sup']], dr) + [0, 4.0, 0]
        for j, p in enumerate(sup):
            i = None
            for ii, q in enumerate(P):
                if rkey(q) == rkey(p):
                    i = ii
            if i is None:
                raise RuntimeError(f'seam node {p} not in bay')
            ext[:, i] += R[:, j]
        c0 = np.array([0, 4.0, 0])
        for k in range(F.K):
            Fr = R[k].sum(0); Mr = np.sum(np.cross(sup - c0, R[k]), axis=0)
            distribute(ext[k], P, ct, pt, ptop_mid, st, can[k][0] - Fr, can[k][1] - Mr)
    else:
        for k in range(F.K):
            Fv, Mv = can[k]
            if st > 0:
                npole = min(-Fv[1], F.EMIT_W)
                ext[k, pt, 1] -= npole
                Fv = Fv + [0, npole, 0]
            distribute(ext[k], P, ct, pt, ptop_mid, st, Fv, Mv)
    # boom roots: entry reactions rotated into the bay frame
    for y, dc, bk in key['roots']:
        be = boom_entries[bk]
        Rr = rot_cases(be['reac_roots'], dc)
        sr = be['key']['sr']
        for j, s in enumerate((1, -1)):
            p = rot([sr, 0, s * sr], dc) + [0, y, 0]
            i = None
            for ii, q in enumerate(P):
                if rkey(q) == rkey(p):
                    i = ii
            if i is None:
                raise RuntimeError(f'root node {p} not in bay {key}')
            ext[:, i] += Rr[:, j]
    keepn = set()
    for y, dc, bk in key['roots']:
        for ii, q in enumerate(P):
            if abs(q[1] - y) < 1e-6 and np.hypot(q[0], q[2]) > 1e-6:
                keepn.add(ii)
    pwv = np.full(len(pole), F.W_POLE_MIN); pwv[:] = max(pw, F.W_POLE_MIN)
    sym = (not key['roots']) and key['mg'][0] < 0 and (above_entry is None or above_entry.get('sym', False))
    return dict(P=P, fixed=fixed, pole=pole, pw=pwv, bars=bars, cab=cab, mand=mand, amin=amin,
                ext=ext.reshape(F.K, -1), ghost={ghost}, sym=sym, sup=[ghost, pb] + cb, top=[pt] + ct,
                legs=legs, em=[], keep=sorted(keepn))


# ================================================================ solving, q variants
def degrade(prob, sol, q, seed, log, is_boom=False, amp_f=1.0, pdrop=0.75):
    """Low-q variant from the q = 1 solution: wonky interior nodes, single cables instead of X, mismatched widths."""
    rng = np.random.default_rng(seed)
    P = prob['P'].copy()
    keepn = set(prob['fixed']) | set(prob.get('top', [])) | set(prob.get('em', [])) | set(prob.get('keep', []))
    if is_boom:
        keepn |= {prob['tip']}
    amp = 0.35 * (1 - q) * amp_f
    for i in range(len(P)):
        if i in keepn:
            continue
        P[i] += amp * np.array([rng.uniform(-1, 1), rng.uniform(-0.3, 0.3), rng.uniform(-1, 1)])
    prob2 = dict(prob); prob2['P'] = P; prob2['pw'] = sol['pw'].copy() if len(sol['pw']) else prob['pw'].copy()
    bars, cab, width = sol['bars'].copy(), sol['cab'].copy(), sol['width'].copy()
    # single cables instead of X
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
    bars, cab, width = bars[keep], cab[keep], width[keep]
    sec = np.where(cab, np.abs(F.CAB_W[None] - width[:, None]).argmin(1), np.abs(F.WIDTHS[None] - width[:, None]).argmin(1))
    # mismatched widths (only up)
    for b in range(len(bars)):
        if not cab[b] and rng.uniform() < 0.3:
            sec[b] = min(sec[b] + int(rng.integers(1, 3)), len(F.WIDTHS) - 1)
    minsec = sec.copy()
    sec, cab, _ = F.repair(prob2, bars, cab, sec, np.where(cab, 0, minsec), log)
    bars, cab, sec, minsec2, added = F.stabilise(prob2, bars, cab, sec, np.where(cab, 0, sec), log)
    sec, cab, nb = PF.taut_repair(prob2, bars, cab, sec, False, log)
    for it in range(6):
        w = F.sec_width(sec, cab)
        ok, dN, disp, Us = PF.taut(prob2, bars, cab, w)
        if not ok:
            break
        _, pre = PF.pre_util(prob2, bars, cab, w, dN)
        r = np.where(cab, pre / (F.SIG_CAB * w ** 2), 0)
        if r.max() <= PF.TAUT_PRE_MAX:
            break
        worst = np.nonzero(r > PF.TAUT_PRE_MAX)[0]
        cab[worst] = False; sec[worst] = np.maximum(sec[worst], 1)
        sec, cab, _ = PF.taut_repair(prob2, bars, cab, sec, False, log)
    w = F.sec_width(sec, cab)
    ok, dN, disp, Us = PF.taut(prob2, bars, cab, w)
    _, pre = PF.pre_util(prob2, bars, cab, w, dN) if ok else (None, np.zeros(len(bars)))
    return dict(P=P, bars=bars, cab=cab, width=w, pre=pre, pw=prob2['pw'].copy(), dropped=len(drop))


def entry_from(prob, sol, extra):
    e = dict(P=prob['P'], bars=sol['bars'], cab=sol['cab'], width=sol['width'], pre=sol['pre'], pw=sol['pw'],
             pole=list(prob['pole']), ghost=sorted(prob['ghost']), sup=list(prob.get('sup', [])),
             top=list(prob.get('top', [])), sym=prob.get('sym', False))
    e.update(extra)
    return e


def solve_entry(job):
    """job: dict(kind='boom'|'bay', keystr, key, spec?, above_entry?, boom_entries?). Cached by key hash."""
    os.environ['OMP_NUM_THREADS'] = '1'
    fn = os.path.join(CACHE, khash(job['keystr']) + '.pkl')
    if os.path.exists(fn):
        return pickle.load(open(fn, 'rb'))
    t0 = time.time()
    logs = []
    try:
        if job['kind'] == 'boom':
            prob = boom_problem(job['key'])
        else:
            prob = bay_problem(job['spec'], job['boom_entries'], job.get('above_entry'))
        sol = PF.design_pre(prob, closure=prob['sym'], log=logs.append)
        t1 = time.time()
        ent = entry_from(prob, sol, dict(key=job['key'], keystr=job['keystr'], kind=job['kind'], solve_s=t1 - t0,
                                         taut_util=sol['taut_util'], pre_ratio=sol['pre_ratio']))
        ent['xpairs'] = count_x(prob['P'], sol['bars'], sol['cab'])
        if job['kind'] == 'boom':
            R = PF.reactions(prob, sol['bars'], sol['cab'], sol['width'], prob['roots'] + prob['anchors'])
            ent['reac_roots'] = R[:, :2]; ent['reac_anchors'] = R[:, 2:]
            ent.update(roots=prob['roots'], anchors=prob['anchors'], tip=prob['tip'], inner=prob['inner'])
        else:
            prob['pw'] = sol['pw'].copy()
            ent['reac'] = PF.reactions(prob, sol['bars'], sol['cab'], sol['width'], prob['sup'])
            ent['legs'] = prob['legs']
        seed = int(khash(job['keystr'])[:8], 16)
        ent['low'] = None
        for amp, pdrop in ((1.0, 0.75), (0.5, 0.5), (0.0, 0.5), (0.0, 0.0)):
            try:
                ent['low'] = degrade(prob, sol, Q_LOW, seed, logs.append, job['kind'] == 'boom', amp, pdrop)
                ent['low']['amp'] = amp
                break
            except Exception as ex:
                logs.append(f'degrade failed ({amp}, {pdrop}): {ex!r}')
        ent['low_s'] = time.time() - t1
        ent['log'] = logs
    except Exception as ex:
        return dict(error=repr(ex), keystr=job['keystr'], log=logs)
    pickle.dump(ent, open(fn + f'.tmp{os.getpid()}', 'wb')); os.replace(fn + f'.tmp{os.getpid()}', fn)
    return ent


def count_x(P, bars, cab):
    sys.path.insert(0, '/var/lib/mclab/agent/timestop-proto/grow/xbrace')
    import xb
    return xb.count_x(P, bars, cab)


def cached(keystr):
    return os.path.exists(os.path.join(CACHE, khash(keystr) + '.pkl'))


# ================================================================ build a layout (needs a pool for solves)
def build(layout, pool, log=print, seam=True):
    t0 = time.perf_counter()
    boom_ents = {}
    stats = dict(solved=0, hits=0)

    def solve_many(jobs):
        todo = [j for j in jobs if not cached(j['keystr'])]
        stats['hits'] += len(jobs) - len(todo); stats['solved'] += len(todo)
        res = pool.map(solve_entry, todo) if todo else []
        for r in res:
            if 'error' in r:
                raise RuntimeError(f"{r['keystr']}: {r['error']} {r['log'][-3:]}")
        return [solve_entry(j) for j in jobs]           # cache hits now

    def solve_booms(keys):
        jobs = [dict(kind='boom', key=k, keystr=json.dumps(k, sort_keys=True)) for k in keys]
        ents = solve_many(jobs)
        for j, e in zip(jobs, ents):
            boom_ents[j['keystr']] = e
        return ents
    sp = spine(layout, solve_booms=solve_booms)
    specs = bay_specs(layout, sp)
    t_spine = time.perf_counter() - t0
    # wave 1: plain entries (also the seam sources)
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
def module_threshold(seed, tag):
    h = int(hashlib.sha1(f'{seed}:{tag}'.encode()).hexdigest()[:8], 16) / 0xffffffff
    return Q_LOW + (1 - Q_LOW) * h + 1e-6


def variant(ent, q, seed, tag):
    if q >= module_threshold(seed, tag) or ent.get('low') is None:
        return ent['P'], ent['bars'], ent['cab'], ent['width'], ent['pw'], 'q1'
    lo = ent['low']
    return lo['P'], lo['bars'], lo['cab'], lo['width'], lo['pw'], 'low'


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
    bars, cab, width, owner = [], [], [], []
    pole_nodes = {}
    pole_w = {}
    vlist = []
    for bi, (spec, ent) in enumerate(zip(b['specs'], b['entries'])):
        Pl, bl, cl, wl, pwl, v = variant(ent, q, seed, f'bay{spec["y0"]}')
        vlist.append(v)
        Pw = rot(Pl, spec['r']) + [0, spec['y0'], 0]
        gset = set(ent['ghost'])
        loc = {}
        for i, p in enumerate(Pw):
            if i in gset:
                continue
            loc[i] = node(p)
        for k, pi in enumerate(ent['pole']):
            if pi in gset:
                continue
            gi = loc[pi]
            pole_nodes[gi] = Pw[pi][1]
            pole_w[gi] = max(pole_w.get(gi, 0), float(pwl[k]) if k < len(pwl) else F.W_POLE_MIN)
        for (i, j), c, w in zip(bl, cl, wl):
            bars.append((loc[i], loc[j])); cab.append(c); width.append(w); owner.append(('bay', bi))
    em = [node([0, H, 0])]
    for k, bo in enumerate(b['sp']['booms']):
        be = bo['entry']
        Pl, bl, cl, wl, _, v = variant(be, q, seed, f'boom{k}')
        vlist.append(v)
        q4 = DIRS[bo['d']]
        Lc, sr = be['key']['Lc'], be['key']['sr']
        Pc = Pl.copy()
        loc = {}
        for i, p in enumerate(Pc):
            if i in be['roots']:
                pw_ = np.array([sr if False else bo['sr'], 0, np.sign(p[2]) * bo['sr']])
                pw_ = rot(pw_, q4) + [0, bo['yb'], 0]
            elif i in be['anchors']:
                if bo['sa'] > 0:
                    pw_ = rot(np.array([bo['sa'], 0, np.sign(p[2]) * bo['sa']]), q4) + [0, bo['ya'], 0]
                else:
                    pw_ = np.array([0.0, bo['ya'], 0])
            else:
                x = bo['sr'] + (p[0] - sr) * (bo['L'] - bo['sr']) / (Lc - sr)
                pw_ = rot(np.array([x, p[1], p[2]]), q4) + [0, bo['yb'], 0]
            loc[i] = node(pw_)
        for (i, j), c, w in zip(bl, cl, wl):
            bars.append((loc[i], loc[j])); cab.append(c); width.append(w); owner.append(('boom', k))
        em.append(loc[be['tip']])
    P = np.array(pts)
    pole = [ghost] + sorted(pole_nodes, key=lambda i: pole_nodes[i])
    pw = np.array([F.W_POLE_MIN] + [max(pole_w.get(i, F.W_POLE_MIN), F.W_POLE_MIN) for i in pole[1:]])
    fixed = {ghost} | {i for i, p in enumerate(P) if abs(p[1]) < TOL}
    bars = np.array(bars, int); cab = np.array(cab); width = np.array(width)
    seen = {}
    keep = np.ones(len(bars), bool)
    for bb, (i, j) in enumerate(bars):
        kk = tuple(sorted((i, j)))
        if i == j:
            keep[bb] = False; continue
        if kk in seen:
            o = seen[kk]
            if width[bb] > width[o] or (cab[o] and not cab[bb]):
                keep[o] = False; seen[kk] = bb
            else:
                keep[bb] = False
        else:
            seen[kk] = bb
    ext = F.emitter_loads(P, em)
    cand = G.candidates(P, 4.6)
    cand = cand[[not (a in fixed and b in fixed) for a, b in cand.tolist()]]
    prob = dict(P=P, fixed=fixed, pole=pole, pw=pw, ext=ext, em=em, ghost={ghost},
                bars=np.vstack([bars[keep], cand]))
    owner = [o for o, k in zip(owner, keep) if k]
    return dict(prob=prob, bars=bars[keep], cab=cab[keep], width=width[keep], owner=owner, variants=vlist,
                t_stitch=time.perf_counter() - t0)


def merged_counts(P, bars, cab, width, keep_nodes):
    import gs as G_
    nb = {False: 0, True: 0}
    for cl in (False, True):
        sel = cab == cl
        if not sel.any():
            continue
        other = set(bars[~sel].ravel())
        mb, mw = G_.merge_chains(P, bars[sel], width[sel], set(keep_nodes) | other)
        nb[cl] = len(mb)
    return nb[False], nb[True]


def validate(st, log=print, widen=True):
    prob = st['prob']
    t0 = time.perf_counter()
    c0 = PF.check(prob, st['bars'], st['cab'], st['width'])
    st['raw'] = {k: v for k, v in c0.items() if k not in ('util', 'N')}
    st['raw']['xpairs'] = count_x(prob['P'], st['bars'], st['cab'])
    st['raw_util_members'] = c0['util']
    log('  raw: ' + json.dumps({k: (round(v, 3) if isinstance(v, float) else v) for k, v in st['raw'].items()}))
    if widen:
        t1 = time.perf_counter()
        w0 = st['width'].copy(); nb0 = len(st['bars'])
        bars, cab, width, info = PF.widen(prob, st['bars'], st['cab'], st['width'], log)
        info['t_widen'] = time.perf_counter() - t1
        old = np.zeros(len(bars), bool); old[:nb0] = True
        info['vol_added'] = float(np.sum(F.geometry(prob['P'], bars)[1] * width ** 2)) - float(np.sum(F.geometry(prob['P'], st['bars'])[1] * w0 ** 2))
        st['owner'] = st['owner'] + [('widen', -1)] * (len(bars) - nb0)
        st['bars'], st['cab'], st['width'] = bars, cab, width
        st['widen'] = info
        log('  widen: ' + json.dumps({k: (round(v, 3) if isinstance(v, float) else v) for k, v in info.items()}))
    c = PF.check(prob, st['bars'], st['cab'], st['width'])
    st['N'] = c['N']
    st['final'] = {k: v for k, v in c.items() if k not in ('util', 'N')}
    st['final']['xpairs'] = count_x(prob['P'], st['bars'], st['cab'])
    ns, nc = merged_counts(prob['P'], st['bars'], st['cab'], st['width'], set(prob['fixed']) | set(prob['em']))
    st['final'].update(struts=ns, cables=nc, nodes=len(set(st['bars'].ravel())), raw_members=len(st['bars']))
    st['util_members'] = c['util']
    log('  final: ' + json.dumps({k: (round(v, 3) if isinstance(v, float) else v) for k, v in st['final'].items()}))
    st['t_validate'] = time.perf_counter() - t0
    return st
