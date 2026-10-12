"""P2 spine statics: P1's top-down cantilever sweep with
- an extended ring ladder and a base flare (rings below Y_FLARE may exceed the core's s = 1.5, up to S0_CAP(H));
- ring choice by a leg-width target (P1's leg-force target was unreachable at H100);
- mid-height leg nodes in heavily loaded lattice bays (leg panels 2 long: sig_c(2) = 104 instead of 54);
- a bare-tower reference curve for the one-axis demand key (bare towers are self-similar from the top).
"""
import math
import numpy as np
from . import fem as F
from . import spine as S1

BAY = 4
S_LADDER = (0.0, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 3.5, 4.0)
S_CORE = 1.5
W_LEG_TARGET = 0.40           # leg width the ring choice aims for
MID_F = 9.0                   # leg force estimate above which a lattice bay gets mid-height leg nodes
M_POLE_MAX = 2.5
POLE_A = 0.09


def s0_cap(H):
    return float(min(4.0, max(S_CORE, 0.07 * H)))


def y_flare(H):
    return int(min(16, max(4, BAY * round(H / 5 / BAY))))


def s_cap(y, H):
    yf = y_flare(H)
    if y >= yf:
        return S_CORE
    t = 1 - y / yf
    return S_CORE + (s0_cap(H) - S_CORE) * t ** 1.6


def leg_force(M, N, s):
    return 0.354 * M / s + N / 4 if s > 0 else 1e9


def leg_sig(F_):
    return F.sig_c(2.0) if F_ > MID_F else F.sig_c(4.0)


def pick_s(M, N, s_above, need_lattice, cap):
    best = None
    for s in S_LADDER:
        if s < s_above - 1e-9 or s > cap + 1e-9:
            continue
        if s == 0:
            if M <= M_POLE_MAX and not need_lattice:
                return 0.0
            continue
        Fl = leg_force(M, N, s)
        best = s
        if Fl / leg_sig(Fl) <= W_LEG_TARGET ** 2:
            return s
    return best if best is not None else s_above


def ring_s_at(rings, y):
    y0 = int(y // BAY) * BAY
    if y0 >= max(rings):
        return rings[max(rings)]
    t = (y - y0) / BAY
    return (1 - t) * rings[y0] + t * rings[y0 + BAY]


def sweep(layout, rings=None, loads=None):
    """loads: per case list of (pos, force, tag) point loads (top emitter + booms). If rings is None they are chosen
    on the way down. Returns dict(rings, ifc, loads)."""
    H = layout['H']
    ring_ys = list(range(0, H + 1, BAY))
    choose = rings is None
    need = set()
    for (yb, d, L) in layout.get('booms', []):
        need.add(int(yb // BAY) * BAY); need.add(int(math.ceil(yb / BAY)) * BAY)
        need.add(int(yb // BAY) * BAY + BAY)
    if choose:
        rings = {y: 0.0 for y in ring_ys}
    nC = F.K
    R = np.zeros((nC, 3)); Mo = np.zeros((nC, 3))
    out = {}
    s_above = 0.0
    pls = [sorted(pl, key=lambda t: -t[0][1]) for pl in loads]
    idx = [0] * nC
    for y in range(H, -1, -1):
        conc = [[] for _ in range(nC)]
        for c in range(nC):
            while idx[c] < len(pls[c]) and pls[c][idx[c]][0][1] > y + 1e-6:
                p, f, tag = pls[c][idx[c]]
                R[c] += f; Mo[c] += np.cross(p, f); idx[c] += 1
            j = idx[c]
            while j < len(pls[c]) and abs(pls[c][j][0][1] - y) < 1e-6:
                conc[c].append(pls[c][j]); j += 1
        Mres = Mo - np.cross(np.array([0.0, y, 0.0]), R)
        Rc, Mc = R.copy(), Mres.copy()
        for c in range(nC):
            for p, f, tag in conc[c]:
                Rc[c] += f; Mc[c] += np.cross(p - [0, y, 0], f)
        env = dict(N=float(max(0.0, (-Rc[:, 1]).max())), V=float(np.hypot(Rc[:, 0], Rc[:, 2]).max()),
                   M=float(np.hypot(Mc[:, 0], Mc[:, 2]).max()), T=float(np.abs(Mc[:, 1]).max()))
        if y % BAY == 0:
            if choose:
                if y == H:
                    s = 0.0
                else:
                    s = pick_s(env['M'], env['N'], s_above, y in need, s_cap(y, H))
                    if y == 0:
                        s = max(s, S_CORE)
                if y in need and s < 0.75:
                    s = 0.75
                rings[y] = s; s_above = s
            out[y] = dict(R=R.copy(), M=Mres.copy(), conc=conc, env=env, Rc=Rc, Mc=Mc)
        if y > 0:
            s_here = s_above if choose else ring_s_at(rings, y - 0.5)
            if s_here > 0:
                Fl = leg_force(env['M'], env['N'], s_here)
                A_leg = max(Fl / leg_sig(Fl), F.AREAS[0])
            else:
                A_leg = 0.0
            slope = 1.0
            if not choose:
                ds = abs(ring_s_at(rings, y) - ring_s_at(rings, y - 1))
                slope = math.sqrt(1 + 2 * ds * ds)
            vol = POLE_A + 4 * A_leg * slope + (0.08 * max(1.0, s_here / 1.5) if s_here > 0 else 0.0)
            wdt = 0.3 + 4 * math.sqrt(A_leg) + (0.25 if s_here > 0 else 0.0)
            p = np.array([0.0, y - 0.5, 0.0])
            for c, cs in enumerate(F.CASES):
                f = np.array([0.0, -F.RHO * vol, 0.0])
                if cs[1] is not None:
                    f += F.CD0 * wdt * cs[1]
                R[c] += f; Mo[c] += np.cross(p, f)
    return dict(rings=rings, ifc=out, loads=loads)


def top_loads(H):
    out = []
    for cs in F.CASES:
        f = np.array([0.0, -F.EMIT_W, 0.0])
        if cs[1] is not None:
            f += F.EMIT_WIND0 * cs[1]
        out.append([(np.array([0.0, H, 0.0]), f, 'top')])
    return out


# ---------------------------------------------------------------- reference curve (bare tower, by depth)
_REF = None
REF_H = 160


def reference():
    global _REF
    if _REF is None:
        lay = dict(H=REF_H, booms=[])
        sp = sweep(lay, None, top_loads(REF_H))
        sp = sweep(lay, sp['rings'], top_loads(REF_H))
        d = np.array(sorted(REF_H - y for y in sp['ifc']), float)
        ys = [REF_H - int(x) for x in d]
        N = np.array([sp['ifc'][y]['env']['N'] for y in ys])
        V = np.array([sp['ifc'][y]['env']['V'] for y in ys])
        M = np.array([sp['ifc'][y]['env']['M'] for y in ys])
        _REF = dict(d=d, N=np.maximum.accumulate(N), V=np.maximum.accumulate(V), M=np.maximum.accumulate(M))
    return _REF


DEPTH_LADDER = [0, 2, 4, 6, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64, 72, 80, 88, 96, 104, 112,
                120, 136, 152, 176, 208, 256, 320, 400]


def eq_depth(q, v):
    r = reference()
    arr = r[q]
    if v <= arr[0]:
        return 0.0
    if v <= arr[-1]:
        return float(np.interp(v, arr, r['d']))
    # beyond the curve: extrapolate with the last segment's growth rate (exponential)
    g = math.log(arr[-1] / arr[-5]) / (r['d'][-1] - r['d'][-5])
    return float(r['d'][-1] + math.log(v / arr[-1]) / g)


def demand_class(N, V, Mw):
    d = max(eq_depth('N', N), eq_depth('V', V), eq_depth('M', Mw))
    for k, x in enumerate(DEPTH_LADDER):
        if x >= d - 1e-6:
            return k
    return len(DEPTH_LADDER) - 1


def demand_loads(k):
    """Canonical (N, V, Mw) of demand class k."""
    r = reference()
    x = DEPTH_LADDER[k]
    if x <= r['d'][-1]:
        return tuple(float(np.interp(x, r['d'], r[q])) for q in 'NVM')
    out = []
    for q in 'NVM':
        arr = r[q]
        g = math.log(arr[-1] / arr[-5]) / (r['d'][-1] - r['d'][-5])
        out.append(float(arr[-1] * math.exp(g * (x - r['d'][-1]))))
    return tuple(out)


MG_R = 1.4
T_R = 2.0


def ladder(v, floor, r):
    if v <= 0.05 * floor:
        return -1, 0.0
    if v <= floor:
        return 0, floor
    k = int(math.ceil(math.log(v / floor) / math.log(r) - 1e-9))
    return k, floor * r ** k
