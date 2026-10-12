"""Generalised XBRACE solver (struts + tension-only cables, LP path), parametrised for bay / boom / whole-tower use.

Kept as close to grow/xbrace/xb.py as possible. Differences:
- explicit problem dict: nodes P, fixed set, pole chain with per-node pole width (bending cap + stiffness), explicit
  per-case external loads ext (K, 3n), candidate pairs, mandatory members (pole, legs) with min areas;
- a fixed "ghost" pole node below the bottom pole node clamps the pole base (so a pole pinned at one node is not a
  mechanism); the ghost has no member, only takes part in the pole shear-transfer;
- strut catalogue extended to width 1.92 (tall towers);
- utilisation from the strict tension-only elastic solution (|N|/cap per member, pole kink force / cap).
"""
import math, os, sys, time
import numpy as np
import scipy.sparse as sp
from scipy.optimize import linprog
from . import gsx as G

SIG_T, SIG_C0, L0, E = 300.0, 150.0, 3.0, G.E
RHO = 1.0
SIG_CAB = 600.0
WIDTHS = np.round(0.05 * 1.2 ** np.arange(21), 3)     # 0.05 .. 1.92
AREAS = WIDTHS ** 2
CAB_W = np.array([0.04, 0.06, 0.09, 0.12])
CAB_A = CAB_W ** 2
SIG_B = SIG_C0
EMIT_W, EMIT_WIND0 = 3.0, 0.5
CD0 = 0.03
TORQ0 = 0.4
RMAX, RMAX_CAB = 7.6, 12.0
LAM_S, LAM_C = 2.0, 0.03
EPS_S, EPS_C = 5e-4, 1e-4
ROUNDS = 8
KILL_FROM = 3
W_POLE_MIN = 0.30


def sig_c(L):
    return SIG_C0 / (1 + (L / L0) ** 2)


def pole_cap(w, span):
    """Shear-transfer cap at an interior pole node (same as xb.bend_matrix)."""
    return 4 * SIG_B * w ** 3 / 6 / span


def cases(ws=1.0):
    out = [('gravity', None, 0)]
    for k in range(8):
        a = math.radians(45 * k)
        out.append((f'wind {45 * k}', np.array([math.cos(a), 0, math.sin(a)]), 0))
    out += [('torsion +', None, 1), ('torsion -', None, -1)]
    return out


CASES = cases()
K = len(CASES)


def emitter_loads(P, em):
    """(K, 3n) emitter loads, exactly as xb.emitter_loads."""
    n = len(P)
    out = np.zeros((K, n, 3))
    for k, (_nm, wv, ts) in enumerate(CASES):
        for e in em:
            out[k, e, 1] -= EMIT_W
            if wv is not None:
                out[k, e] += EMIT_WIND0 * wv
            r = P[e] * [1, 0, 1]
            if ts and np.linalg.norm(r) > 0.5:
                t = np.cross([0, 1.0, 0], r / np.linalg.norm(r))
                out[k, e] += ts * TORQ0 * t
    return out.reshape(K, -1)


def geometry(P, bars):
    d = P[bars[:, 1]] - P[bars[:, 0]]
    return d, np.linalg.norm(d, axis=1)


def body(P, bars, wv, coef_wind, coef_sw=RHO):
    n, m = len(P), len(bars)
    d, L = geometry(P, bars)
    rows, cols, vals = [], [], []
    for end in (0, 1):
        rows.append(3 * bars[:, end] + 1); cols.append(np.arange(m)); vals.append(-coef_sw * L / 2)
        if wv is not None:
            Lp = np.linalg.norm(np.cross(d, wv), axis=1)
            for k in (0, 2):
                if wv[k]:
                    rows.append(3 * bars[:, end] + k); cols.append(np.arange(m)); vals.append(coef_wind * Lp / 2 * wv[k])
    return sp.csr_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))), shape=(3 * n, m))


def bend_matrix(P, pole, pw, free_mask):
    n = len(P)
    cols, caps = [], []
    for k in range(1, len(pole) - 1):
        span = np.linalg.norm(P[pole[k + 1]] - P[pole[k - 1]])
        for d in (0, 2):
            v = np.zeros(3 * n)
            v[3 * pole[k] + d] = 1.0
            v[3 * pole[k - 1] + d] = -0.5
            v[3 * pole[k + 1] + d] = -0.5
            cols.append(v)
            caps.append(pole_cap(pw[k], span))
    Bv = np.array(cols).T if cols else np.zeros((3 * n, 0))
    return sp.csr_matrix(Bv[free_mask]), np.array(caps)


def eq_matrix(P, bars, fixed):
    n, m = len(P), len(bars)
    d, L = geometry(P, bars)
    e = d / L[:, None]
    rows, cols, vals = [], [], []
    for k in range(3):
        rows += [3 * bars[:, 0] + k, 3 * bars[:, 1] + k]
        cols += [np.arange(m), np.arange(m)]
        vals += [-e[:, k], e[:, k]]
    B = sp.csr_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))), shape=(3 * n, m))
    free = np.array([k for nd in range(n) if nd not in fixed for k in range(3 * nd, 3 * nd + 3)])
    mask = np.zeros(3 * n, bool); mask[free] = True
    return B[free], free, mask, L


# ---------------------------------------------------------------- candidates
def candidates(P, fixed, pole, ghost, nodes_ok=None, rmax_s=RMAX, rmax_c=RMAX_CAB, extra_skip=None):
    """Strut + cable candidate pairs, xb-style: pairs within R, not passing through other nodes, not both fixed,
    not along / through the pole. Pole segments (consecutive chain, excluding the ghost) are mandatory struts."""
    polem = [p for p in pole if p not in ghost]
    poleset = {tuple(sorted((polem[k], polem[k + 1]))) for k in range(len(polem) - 1)}
    pn = set(polem)
    M = P[polem]

    def plist(R):
        Pc = P.copy()
        for g in ghost:          # keep the ghost out of the way
            Pc[g] = [1e4, -1e4, 1e4]
        pl = {tuple(sorted(b)) for b in G.candidates(Pc, R).tolist()}
        out = set()
        for b in pl:
            i, j = b
            if i in ghost or j in ghost or (i in fixed and j in fixed):
                continue
            if nodes_ok is not None and not (nodes_ok(i) and nodes_ok(j)):
                continue
            if i in pn and j in pn:
                continue
            if extra_skip is not None and extra_skip(i, j):
                continue
            hit = False
            if i not in pn and j not in pn:
                for t in np.linspace(0.05, 0.95, 19):
                    x = P[i] + t * (P[j] - P[i])
                    for k in range(len(M) - 1):
                        a_, d_ = M[k], M[k + 1] - M[k]
                        u = np.clip(np.dot(x - a_, d_) / np.dot(d_, d_), 0, 1)
                        if np.linalg.norm(x - a_ - u * d_) < 0.35:
                            hit = True; break
                    if hit:
                        break
            if not hit:
                out.add(b)
        return out
    ps = plist(rmax_s) | poleset
    pc = plist(rmax_c)
    bars = sorted(ps) + sorted(pc)
    cab = np.array([False] * len(ps) + [True] * len(pc))
    bars = np.array(bars, int)
    is_pole = np.array([tuple(b) in poleset and not c for b, c in zip(bars.tolist(), cab)])
    return bars, cab, is_pole


# ---------------------------------------------------------------- design LP
def body_all(prob, bars, cw):
    return [body(prob['P'], bars, cs[1], cw) for cs in CASES]


def design_lp(prob, sel, weight, aref, tie=None):
    P = prob['P']
    bars, cab = prob['bars'][sel], prob['cab'][sel]
    amin = prob['amin'][sel]
    B, free, mask, L = eq_matrix(P, bars, prob['fixed'])
    Bv, vcap = bend_matrix(P, prob['pole'], prob['pw'], mask)
    nv = Bv.shape[1]
    m = len(bars)
    cw = CD0 / np.sqrt(np.maximum(aref, np.where(cab, CAB_A[0], AREAS[0])))
    nb = B.shape[0]
    rows, beq = [], []
    nvar = m + K * (m + nv)
    for k, cs in enumerate(CASES):
        W = body(P, bars, cs[1], cw)[free]
        row = [-W] + [sp.csr_matrix((nb, m + nv))] * K
        row[1 + k] = sp.hstack([B, Bv])
        rows.append(sp.hstack(row))
        beq.append(prob['ext'][k][free])
    Aeq = sp.vstack(rows).tocsc()
    sigt = np.where(cab, SIG_CAB, SIG_T)
    sigc = np.where(cab, 1.0, sig_c(L))
    I = sp.identity(m, format='csr')
    Ub = []
    for k in range(K):
        for s_ in (1, -1):
            row = [-I] + [sp.csr_matrix((m, m + nv))] * K
            Nm = sp.diags(1 / sigt) if s_ > 0 else -sp.diags(1 / sigc)
            row[1 + k] = sp.hstack([Nm, sp.csr_matrix((m, nv))])
            Ub.append(sp.hstack(row))
    Aub = sp.vstack(Ub).tocsc()
    c = np.concatenate([L * weight, np.zeros(K * (m + nv))])
    bnds = [(lo, AREAS[-1] / 1.5) for lo in amin]
    for k in range(K):
        bnds += [((0 if cb else None), None) for cb in cab] + [(-vc, vc) for vc in vcap]
    beq = np.concatenate(beq)
    if tie is not None:
        loc = -np.ones(len(sel), int); loc[np.nonzero(sel)[0]] = np.arange(m)
        rr, cc, vv = [], [], []
        nr = 0
        for gi in np.nonzero(sel)[0]:
            gj = tie[gi]
            if gj >= 0 and gj != gi and loc[gj] >= 0:
                rr += [nr, nr]; cc += [loc[gi], loc[gj]]; vv += [1.0, -1.0]; nr += 1
        if nr:
            T = sp.csr_matrix((vv, (rr, cc)), shape=(nr, nvar))
            Aeq = sp.vstack([Aeq, T]).tocsc(); beq = np.concatenate([beq, np.zeros(nr)])
    res = linprog(c, A_ub=Aub, b_ub=np.zeros(Aub.shape[0]), A_eq=Aeq, b_eq=beq, bounds=bnds, method='highs-ipm')
    if res.status != 0:
        raise RuntimeError('design LP: ' + res.message)
    return res.x[:m]


def rot_map(P, bars, cab, skip=()):
    rot = lambda p: np.array([-p[:, 2], p[:, 1], p[:, 0]]).T
    A, Bp = P[bars[:, 0]], P[bars[:, 1]]
    RA, RB = rot(A), rot(Bp)
    skip = set(skip)
    out = np.full(len(bars), -1)
    for i in range(len(bars)):
        if bars[i, 0] in skip or bars[i, 1] in skip:
            out[i] = -2; continue
        d1 = np.maximum(np.linalg.norm(A - RA[i], axis=1), np.linalg.norm(Bp - RB[i], axis=1))
        d2 = np.maximum(np.linalg.norm(A - RB[i], axis=1), np.linalg.norm(Bp - RA[i], axis=1))
        d = np.minimum(d1, d2) + 10 * (cab != cab[i])
        j = int(np.argmin(d))
        if d[j] < 0.05:
            out[i] = j
    return out


def reduce(prob, closure, log):
    m = len(prob['bars'])
    cab, mand = prob['cab'], prob['mand']
    _, Lall = geometry(prob['P'], prob['bars'])
    alive = np.ones(m, bool)
    aref = np.where(cab, CAB_A[0], 0.15 ** 2)
    a = None
    lam = np.where(cab, LAM_C, LAM_S); eps = np.where(cab, EPS_C, EPS_S)
    tie = rot_map(prob['P'], prob['bars'], cab) if closure else None
    for r in range(ROUNDS + 1):
        if a is not None:
            alive &= (a > 1e-5) | mand | cab | (r < KILL_FROM)
            if tie is not None:
                for _ in range(4):
                    for i in np.nonzero(~alive)[0]:
                        if tie[i] >= 0 and alive[tie[i]]:
                            alive[tie[i]] = False
                alive |= mand
            w = 1 + lam[alive] / (Lall[alive] * (a[alive] + eps[alive]))
        else:
            w = np.ones(alive.sum())
        a_s = design_lp(prob, alive, w, aref[alive], tie)
        a = np.zeros(m); a[alive] = a_s
        aref = np.where(a > 0, np.maximum(a, np.where(cab, CAB_A[0], AREAS[0])), aref)
    log(f'  LP done: struts {((a > 0.2 * AREAS[0]) & ~cab).sum()} cables {((a > 0.2 * CAB_A[0]) & cab).sum()} vol {float(Lall @ a):.3f}')
    return a, tie


# ---------------------------------------------------------------- fixed-section checks
def exact_loads(prob, bars, cab, width):
    """(K, 3n): external + self-weight + drag with the drawn widths (xb.exact_loads)."""
    P = prob['P']
    d, L = geometry(P, bars)
    area = width ** 2
    out = prob['ext'].copy()
    for k, cs in enumerate(CASES):
        f = out[k]
        for b, (i, j) in enumerate(bars):
            f[3 * i + 1] -= RHO * L[b] * area[b] / 2; f[3 * j + 1] -= RHO * L[b] * area[b] / 2
            if cs[1] is not None:
                drag = CD0 * np.linalg.norm(np.cross(d[b], cs[1])) * width[b]
                f[3 * i:3 * i + 3] += drag / 2 * cs[1]; f[3 * j:3 * j + 3] += drag / 2 * cs[1]
    return out


def sec_width(sec, cab):
    return np.where(cab, CAB_W[np.clip(sec, 0, len(CAB_W) - 1)], WIDTHS[np.clip(sec, 0, len(WIDTHS) - 1)])


def force_lp(prob, bars, cab, width, soft=False):
    P = prob['P']
    fixed = set(prob['fixed']) | (set(range(len(P))) - set(bars.ravel()) - set(prob['pole']))
    B, free, mask, L = eq_matrix(P, bars, fixed)
    Bv, vcap = bend_matrix(P, prob['pole'], prob['pw'], mask)
    nv = Bv.shape[1]
    m = len(bars)
    A = width ** 2
    capT = np.where(cab, SIG_CAB, SIG_T) * A
    capC = np.where(cab, 0.0, sig_c(L) * A)
    BB = sp.hstack([B, Bv])
    Aeq = sp.block_diag([BB] * K)
    ex = exact_loads(prob, bars, cab, width)
    beq = np.concatenate([ex[k][free] for k in range(K)])
    nf = K * (m + nv)
    if not soft:
        bnds = []
        for k in range(K):
            bnds += list(zip(-capC, capT)) + [(-v, v) for v in vcap]
        res = linprog(np.zeros(nf), A_eq=Aeq.tocsc(), b_eq=beq, bounds=bnds, method='highs')
        if res.status != 0:
            return None
        return np.array([res.x[k * (m + nv): k * (m + nv) + m] for k in range(K)]).T, None, None
    Aeq2 = sp.hstack([Aeq, sp.csr_matrix((Aeq.shape[0], 2 * m))]).tocsc()
    selN = sp.block_diag([sp.hstack([sp.identity(m), sp.csr_matrix((m, nv))])] * K).tocsr()
    S1 = sp.vstack([sp.identity(m)] * K)
    Z = sp.csr_matrix((K * m, m))
    Cc = sp.diags(cab.astype(float))
    Sst = sp.vstack([sp.diags((~cab).astype(float))] * K)
    Aub = sp.vstack([sp.hstack([selN, -S1, Z]), sp.hstack([-selN, -Sst, -sp.vstack([Cc] * K)])]).tocsc()
    bub = np.concatenate([np.tile(capT, K), np.tile(capC, K)])
    c = np.concatenate([np.zeros(nf), np.ones(m), 100 * np.ones(m)])
    bnds = []
    for k in range(K):
        bnds += [(None, None)] * m + [(-v, v) for v in vcap]
    bnds += [(0, None)] * m + [(0, None) if cb else (0, 0) for cb in cab]
    res = linprog(c, A_ub=Aub, b_ub=bub, A_eq=Aeq2, b_eq=beq, bounds=bnds, method='highs')
    if res.status != 0:
        return None
    return None, res.x[nf:nf + m], res.x[nf + m:]


def repair(prob, bars, cab, sec, minsec, log):
    sec, cab = sec.copy(), cab.copy()
    for it in range(40):
        width = sec_width(sec, cab)
        r = force_lp(prob, bars, cab, width, soft=True)
        if r is None:
            raise RuntimeError('equilibrium impossible')
        _, s_, sc = r
        bad = s_ > 1e-7
        comp = sc > 1e-7
        if not bad.any() and not comp.any():
            return sec, cab, it
        for i in np.nonzero(comp)[0]:
            cab[i] = False; sec[i] = max(0, minsec[i])
        for i in np.nonzero(bad & ~comp)[0]:
            _, Li = geometry(prob['P'], bars[i:i + 1])
            sig = SIG_CAB if cab[i] else min(SIG_T, float(sig_c(Li)[0]))
            Acur = sec_width(sec[i:i + 1], cab[i:i + 1])[0] ** 2
            tgt = (Acur * sig + s_[i]) * 1.02 / sig
            cat = CAB_A if cab[i] else AREAS
            ns = max(int(np.searchsorted(cat, tgt)), sec[i] + 1)
            if ns >= len(cat):
                if cab[i]:
                    cab[i] = False; ns = int(min(np.searchsorted(AREAS, tgt), len(AREAS) - 1))
                else:
                    ns = len(cat) - 1
            sec[i] = ns
    log('    repair: giving up')
    return sec, cab, it


def pole_spring(P, pole, pw, n):
    Kp = np.zeros((3 * n, 3 * n))
    for k in range(1, len(pole) - 1):
        i, j, l = pole[k - 1], pole[k], pole[k + 1]
        span = np.linalg.norm(P[l] - P[i])
        ax = (P[l] - P[i]) / span
        kb = 48 * E * (pw[k] ** 4 / 12) / span ** 3
        Pp = np.eye(3) - np.outer(ax, ax)
        coef = {i: -0.5, j: 1.0, l: -0.5}
        for p, cp in coef.items():
            for r_, cr in coef.items():
                Kp[3 * p:3 * p + 3, 3 * r_:3 * r_ + 3] += kb * cp * cr * Pp
    return Kp


def assemble(prob, bars, area, active):
    P = prob['P']
    n = len(P)
    d, L = geometry(P, bars)
    e = d / L[:, None]
    Km = pole_spring(P, prob['pole'], prob['pw'], n)
    for b in np.nonzero(active)[0]:
        i, j = bars[b]
        k = E * area[b] / L[b] * np.outer(e[b], e[b])
        Km[3 * i:3 * i + 3, 3 * i:3 * i + 3] += k; Km[3 * j:3 * j + 3, 3 * j:3 * j + 3] += k
        Km[3 * i:3 * i + 3, 3 * j:3 * j + 3] -= k; Km[3 * j:3 * j + 3, 3 * i:3 * i + 3] -= k
    return Km


def freedofs(prob, bars):
    P = prob['P']
    used = set(bars.ravel()) | set(prob['pole'])
    fx = set(prob['fixed']) | (set(range(len(P))) - used)
    return np.array([k for nd in range(len(P)) if nd not in fx for k in range(3 * nd, 3 * nd + 3)])


def is_pd(Kf):
    w = np.linalg.eigvalsh(Kf)
    return w[0] > 1e-9 * w[-1], w


def pole_kinks(prob, U):
    """Pole shear-transfer force / cap per interior pole node, from displacements U (n,3)."""
    P, pole, pw = prob['P'], prob['pole'], prob['pw']
    out = []
    for k in range(1, len(pole) - 1):
        i, j, l = pole[k - 1], pole[k], pole[k + 1]
        span = np.linalg.norm(P[l] - P[i])
        ax = (P[l] - P[i]) / span
        kb = 48 * E * (pw[k] ** 4 / 12) / span ** 3
        dlt = U[j] - 0.5 * (U[i] + U[l]); dlt = dlt - ax * (ax @ dlt)
        out.append(kb * np.linalg.norm(dlt) / pole_cap(pw[k], span))
    return np.array(out)


def tension_only(prob, bars, cab, width):
    P = prob['P']
    area = width ** 2
    fr = freedofs(prob, bars)
    d, L = geometry(P, bars)
    e = d / L[:, None]
    ex = exact_loads(prob, bars, cab, width)
    out = []
    for k, cs in enumerate(CASES):
        f = ex[k]
        active = np.ones(len(bars), bool)
        seen = set()
        res = dict(case=cs[0], pd=False, disp=np.inf, slack=0, iters=0, converged=False)
        for it in range(25):
            Km = assemble(prob, bars, area, active)
            Kf = Km[np.ix_(fr, fr)]
            ok, w = is_pd(Kf)
            if not ok:
                res.update(pd=False, iters=it, slack=int((cab & ~active).sum()), active=active.copy())
                break
            u = np.zeros(3 * len(P)); u[fr] = np.linalg.solve(Kf, f[fr])
            U = u.reshape(-1, 3)
            strain = np.einsum('ij,ij->i', U[bars[:, 1]] - U[bars[:, 0]], e)
            new = ~cab | (strain > 0)
            res.update(pd=True, disp=float(np.max(np.linalg.norm(U, axis=1))), iters=it,
                       slack=int((cab & ~active).sum()), N=E * area * strain / L * active, U=U)
            if (new == active).all():
                res['converged'] = True
                break
            key = new.tobytes()
            if key in seen:
                break
            seen.add(key)
            active = new
        out.append(res)
    return out


def utilisation(prob, bars, cab, width, to):
    """Per member max over cases of |N|/cap from the tension-only elastic solution; pole kink max."""
    _, L = geometry(prob['P'], bars)
    A = width ** 2
    capT = np.where(cab, SIG_CAB, SIG_T) * A
    capC = np.where(cab, np.inf, sig_c(L) * A)
    u = np.zeros(len(bars)); pk = 0.0
    for r in to:
        if not r['pd']:
            continue
        N = r['N']
        u = np.maximum(u, np.where(N > 0, N / capT, -N / capC))
        if len(prob['pole']) > 2:
            pk = max(pk, float(pole_kinks(prob, r['U']).max()))
    return u, pk


def pretension_check(prob, bars, cab, width):
    P = prob['P']
    area = width ** 2
    fr = freedofs(prob, bars)
    d, L = geometry(P, bars)
    e = d / L[:, None]
    Km = assemble(prob, bars, area, np.ones(len(bars), bool))
    Kf = Km[np.ix_(fr, fr)]
    ok, _ = is_pd(Kf)
    if not ok:
        return np.inf, np.inf, np.inf, False
    ex = exact_loads(prob, bars, cab, width)
    dN, disp = [], 0.0
    for k in range(K):
        u = np.zeros(3 * len(P)); u[fr] = np.linalg.solve(Kf, ex[k][fr]); U = u.reshape(-1, 3)
        disp = max(disp, float(np.max(np.linalg.norm(U, axis=1))))
        dN.append(E * area / L * np.einsum('ij,ij->i', U[bars[:, 1]] - U[bars[:, 0]], e))
    dN = np.array(dN)
    cap = SIG_CAB * area
    pre = np.maximum(0, -dN.min(0))
    if not cab.any():
        return 0.0, 0.0, disp, True
    return float((pre / cap)[cab].max()), float(((pre + np.maximum(dN.max(0), 0)) / cap)[cab].max()), disp, True


def stabilise(prob, bars, cab, sec, minsec, log, active=None):
    P = prob['P']
    allpairs = {tuple(sorted(b)) for b in prob['bars'].tolist()}
    added = 0
    for it in range(20):
        width = sec_width(sec, cab)
        fr = freedofs(prob, bars)
        act = np.ones(len(bars), bool) if active is None else np.concatenate([active, np.ones(len(bars) - len(active), bool)])
        Km = assemble(prob, bars, width ** 2, act)
        Kf = Km[np.ix_(fr, fr)]
        w, V = np.linalg.eigh(Kf)
        bad = np.nonzero(w < 1e-9 * w[-1])[0]
        if not len(bad):
            return bars, cab, sec, minsec, added
        existing = {tuple(sorted(b)) for b, ac in zip(bars.tolist(), act) if ac}
        slackpos = {tuple(sorted(b)): i for i, (b, ac) in enumerate(zip(bars.tolist(), act)) if not ac}
        used = set(bars.ravel()) | set(prob['fixed'])
        add = []
        for k in bad:
            md = np.zeros(3 * len(P)); md[fr] = V[:, k]; md = md.reshape(-1, 3)
            best, bs = None, 1e-9
            for (i, j) in allpairs:
                if (i, j) in existing or (i not in used or j not in used):
                    continue
                dd = P[j] - P[i]; Lb = np.linalg.norm(dd)
                sc = abs(np.dot(dd / Lb, md[j] - md[i])) / Lb
                if sc > bs:
                    best, bs = (i, j), sc
            if best:
                existing.add(best); add.append(best)
        if not add:
            raise RuntimeError('cannot stabilise')
        conv = [p for p in add if p in slackpos]       # a slack cable needed as a brace becomes a strut
        for p in conv:
            i = slackpos[p]; cab[i] = False; sec[i] = 0
            if active is not None and i < len(active):
                active = active.copy(); active[i] = True
        add = [p for p in add if p not in slackpos]
        if conv:
            log(f'  stabilise: slack cables -> struts {conv}')
        if not add:
            added += len(conv)
            continue
        bars = np.vstack([bars, add]); cab = np.concatenate([cab, [False] * len(add)])
        sec = np.concatenate([sec, [0] * len(add)]); minsec = np.concatenate([minsec, [0] * len(add)]); added += len(add)
        log(f'  stabilise: {len(bad)} modes, added struts {add}')
    raise RuntimeError('stabilise did not converge')


def tie_secs(P, bars, cab, sec):
    rm = rot_map(P, bars, cab)
    for _ in range(4):
        for i in range(len(bars)):
            if rm[i] >= 0 and cab[i] == cab[rm[i]]:
                sec[i] = sec[rm[i]] = max(sec[i], sec[rm[i]])
    return sec


def design(prob, closure=False, log=print):
    """Full xb pipeline on a problem dict. Returns dict(bars, cab, width, N) (indices into prob['P'])."""
    t0 = time.time()
    a, tie = reduce(prob, closure, log)
    pb, pc, mand = prob['bars'], prob['cab'], prob['mand']
    keep = None
    for thr in (0.2, 0.05, 0.01, 1e-3):
        kk = ((a > thr * AREAS[0]) & ~pc) | ((a > thr * CAB_A[0]) & pc) | mand
        if closure:
            for _ in range(3):
                for i in np.nonzero(kk)[0]:
                    if tie[i] >= 0:
                        kk[tie[i]] = True
        pk = {}
        for i in np.nonzero(kk)[0]:
            pk.setdefault(tuple(sorted(pb[i])), []).append(i)
        for _key, ii in pk.items():
            if len(ii) == 2:
                kk[[i for i in ii if pc[i]][0]] = False
        if force_lp(prob, pb[kk], pc[kk], np.ones(kk.sum()), soft=True) is not None:
            keep = kk
            break
        log(f'  threshold {thr}: equilibrium impossible, lowering')
    if keep is None:
        raise RuntimeError('no feasible threshold')
    bars, cab = pb[keep], pc[keep].copy()
    a = a[keep]
    minsec = np.where(cab, 0, np.searchsorted(AREAS, prob['amin'][keep] * 0.999))
    sec = np.where(cab, np.minimum(np.searchsorted(CAB_A, a * 0.999), len(CAB_A) - 1),
                   np.minimum(np.searchsorted(AREAS, a * 0.999), len(AREAS) - 1))
    sec = np.maximum(sec, minsec)
    if closure:
        sec = tie_secs(prob['P'], bars, cab, sec)
    sec, cab, nrep = repair(prob, bars, cab, sec, minsec, log)
    if closure:
        sec = tie_secs(prob['P'], bars, cab, sec)
    bars, cab, sec, minsec, added = stabilise(prob, bars, cab, sec, minsec, log)
    for it in range(15):
        width = sec_width(sec, cab)
        bad = [r for r in tension_only(prob, bars, cab, width) if not r['pd']]
        if not bad:
            break
        log(f"  tension-only mechanism in {[r['case'] for r in bad]}")
        bars, cab, sec, minsec, a2 = stabilise(prob, bars, cab, sec, minsec, log, active=bad[0]['active'])
        added += a2
    if closure and added:     # keep 4-fold symmetry: braces / converted cables over the whole rotation orbit
        for _ in range(4):
            rm = rot_map(prob['P'], bars, np.zeros(len(bars), bool))
            for i in range(len(bars)):
                j = rm[i]
                if j >= 0 and not cab[i] and cab[j]:
                    cab[j] = False; sec[j] = max(sec[j], sec[i], minsec[j])
            Pp = prob['P']; rot = lambda p: np.array([-p[2], p[1], p[0]])
            have = {tuple(sorted(b)) for b in bars.tolist()}
            keyp = {tuple(np.round(p, 3)): k for k, p in enumerate(Pp)}
            new = []
            for i in np.nonzero(~cab)[0]:
                a_, b_ = (keyp.get(tuple(np.round(rot(Pp[x]), 3))) for x in bars[i])
                if a_ is not None and b_ is not None and tuple(sorted((a_, b_))) not in have:
                    new.append((a_, b_)); have.add(tuple(sorted((a_, b_))))
            if new:
                bars = np.vstack([bars, new]); cab = np.concatenate([cab, [False] * len(new)])
                sec = np.concatenate([sec, [0] * len(new)]); minsec = np.concatenate([minsec, [0] * len(new)])
        sec = tie_secs(prob['P'], bars, cab, sec)
    if added:
        sec, cab, _ = repair(prob, bars, cab, sec, minsec, log)
        if closure:
            sec = tie_secs(prob['P'], bars, cab, sec)
    nel = 0
    if ELASTIC_REPAIR:
        sec, cab, nel = elastic_repair(prob, bars, cab, sec, closure, log)
    width = sec_width(sec, cab)
    return dict(bars=bars, cab=cab, width=width, sec=sec, gen_time=time.time() - t0, repairs=nrep, added=added,
                elastic_bumps=nel, pw=prob['pw'].copy())


ELASTIC_REPAIR = True


def elastic_repair(prob, bars, cab, sec, closure, log, target=1.0, its=12):
    """Bump sections until the strict tension-only elastic utilisation is <= target in every case (the plastic LP
    routes force freely; the elastic structure doesn't). Pole kinks bump the pole width of the whole problem."""
    sec, cab = sec.copy(), cab.copy()
    _, L = geometry(prob['P'], bars)
    nb = 0
    for it in range(its):
        width = sec_width(sec, cab)
        to = tension_only(prob, bars, cab, width)
        if not all(r['pd'] for r in to):
            log('  elastic repair: mechanism, stop'); break
        u, pk = utilisation(prob, bars, cab, width, to)
        bad = np.nonzero(u > target)[0]
        if not len(bad) and pk <= target:
            break
        for i in bad:
            sig = SIG_CAB if cab[i] else (SIG_T if True else 0)
            A = width[i] ** 2
            tgt = A * u[i] * 1.08
            cat = CAB_A if cab[i] else AREAS
            ns = max(int(np.searchsorted(cat, tgt)), sec[i] + 1)
            if ns >= len(cat):
                if cab[i]:
                    cab[i] = False; ns = int(min(np.searchsorted(AREAS, tgt), len(AREAS) - 1))
                else:
                    ns = len(cat) - 1
            sec[i] = ns
        if pk > target:
            k = int(np.searchsorted(WIDTHS, prob['pw'].max() * pk ** (1 / 3) * 1.02))
            prob['pw'][:] = WIDTHS[min(k, len(WIDTHS) - 1)]
            pm = np.array([prob['pole'][q] for q in range(len(prob['pole']))])
            for i, (a_, b_) in enumerate(bars):
                if a_ in pm and b_ in pm and not cab[i]:
                    sec[i] = max(sec[i], int(np.searchsorted(WIDTHS, prob['pw'][0] - 1e-9)))
        if closure:
            sec = tie_secs(prob['P'], bars, cab, sec)
        nb += len(bad)
    log(f'  elastic repair: {it} its, {nb} bumps, pole w {prob["pw"].max():.3f}')
    return sec, cab, nb


def check(prob, bars, cab, width):
    """Validation: strict tension-only per case, utilisation, pretension, plastic force-LP feasibility."""
    to = tension_only(prob, bars, cab, width)
    u, pk = utilisation(prob, bars, cab, width, to)
    pre_need, pre_peak, pre_disp, pre_pd = pretension_check(prob, bars, cab, width)
    fl = force_lp(prob, bars, cab, width)
    _, L = geometry(prob['P'], bars)
    return dict(all_pd=all(r['pd'] for r in to), n_pd=sum(r['pd'] for r in to),
                all_conv=all(r['converged'] for r in to),
                max_disp=max(r['disp'] for r in to), worst_case=max(to, key=lambda r: r['disp'])['case'],
                util=u, max_util=float(u.max()) if len(u) else 0.0, pole_util=pk,
                pre_need=pre_need, pre_peak=pre_peak, pre_disp=pre_disp, taut_pd=pre_pd,
                plastic_ok=fl is not None, N=(fl[0] if fl else None),
                vol=float(np.sum(L * width ** 2)), vol_cables=float(np.sum((L * width ** 2)[cab])),
                elastic_N=[r.get('N') for r in to])
