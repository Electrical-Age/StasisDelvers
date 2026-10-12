"""Spine statics: the mast as a cantilever of point loads, booms as stayed cantilevers. Written to port to Java:
plain loops over point loads, one 3x3 solve per boom tip, no matrices beyond that.

Coordinates: offsets from the core centre (c, 21, c), y up. Directions: E = +x, W = -x, S = +z, N = -z.
Interfaces ("rings") every 4 blocks at y = 0, 4, ..., H. Ring half-width s in {0 (pole), 0.75, 1.0, 1.25, 1.5}.
"""
import math
import numpy as np
from . import fem as F

DIRS = {'E': (1, 0), 'W': (-1, 0), 'S': (0, 1), 'N': (0, -1)}
S_LADDER = (0.0, 0.75, 1.0, 1.25, 1.5)
BAY = 4
SIG_LEG = F.sig_c(4.0)          # compression allowable of a 4-long leg
F_LEG_TARGET = 6.0              # leg force (estimate) above which a wider ring is used
M_POLE_MAX = 2.5                # moment a bare pole (width <= 0.47) takes at an interface
POLE_A = 0.09                   # pole 0.30 x 0.30
STAY_RISE = 0.7                 # stays anchor at the first ring >= yb + STAY_RISE * L (about 35-45 deg)


def leg_force(M, N, s):
    return 0.354 * M / s + N / 4 if s > 0 else 1e9


def pick_s(M, N, s_above, need_lattice):
    for s in S_LADDER:
        if s < s_above:
            continue
        if s == 0:
            if M <= M_POLE_MAX and not need_lattice:
                return 0.0
            continue
        if leg_force(M, N, s) <= F_LEG_TARGET:
            return s
    return S_LADDER[-1]


def ring_s_at(rings, y):
    """Leg half-width at height y (linear between rings)."""
    y0 = int(y // BAY) * BAY
    if y0 >= max(rings):
        return rings[max(rings)]
    t = (y - y0) / BAY
    return (1 - t) * rings[y0] + t * rings[y0 + BAY]


def boom_nodes(rings, H, yb, d, L):
    """Root, anchor and tip positions of a boom (see boom_members)."""
    dx, dz = DIRS[d]
    dv = np.array([dx, 0.0, dz]); pv = np.array([-dz, 0.0, dx])
    tip = np.array([L * dx, yb, L * dz], float)
    sr = ring_s_at(rings, yb)
    roots = [np.array([0, yb, 0.0]) + sr * dv + k * sr * pv for k in (1, -1)]
    ys = [y for y in sorted(rings) if y >= yb + max(BAY, STAY_RISE * L) - 1e-9]
    ya = ys[0] if ys else H
    sa = rings[ya]
    if sa > 0:
        anchors = [np.array([0, ya, 0.0]) + sa * dv + k * sa * pv for k in (1, -1)]
    else:
        anchors = [np.array([0, ya, 0.0])]
    return tip, roots, anchors, ya


def boom_members(tip, roots, anchors, case, area=None):
    """Statics of the boom tip: members tip->roots (struts) and tip->anchors (stays). Tip loads: emitter + half the
    members' weight and drag. Member tensions t by min-norm solve of the 3 tip equations (A A^T x = F, t = A^T x).
    Returns (tensions, member list [(end point, unit vector from tip, length, is_cable)], tip load, end loads)."""
    ends = [(r, False) for r in roots] + [(a, True) for a in anchors]
    _nm, wv, ts = case
    f_tip = np.array([0.0, -F.EMIT_W, 0.0])
    if wv is not None:
        f_tip += F.EMIT_WIND0 * wv
    r = tip * [1, 0, 1]
    if ts:
        t = np.cross([0, 1.0, 0], r / np.linalg.norm(r))
        f_tip += ts * F.TORQ0 * t
    mem = []
    end_loads = []
    for k, (p, cb) in enumerate(ends):
        dvec = p - tip; L = np.linalg.norm(dvec); u = dvec / L
        a = area[k] if area is not None else (F.CAB_A[0] if cb else 0.04)
        w = math.sqrt(a)
        half = np.array([0.0, -F.RHO * a * L / 2, 0.0])
        if wv is not None:
            half += F.CD0 * np.linalg.norm(np.cross(dvec, wv)) * w / 2 * wv
        f_tip = f_tip + half
        end_loads.append(half)
        mem.append((p, u, L, cb))
    A = np.array([m[1] for m in mem]).T          # 3 x m
    # tip equilibrium: sum t_j u_j + f_tip = 0
    x = np.linalg.solve(A @ A.T, -f_tip)
    tens = A.T @ x
    return tens, mem, f_tip, end_loads


def size_boom(tip, roots, anchors):
    """Areas of the boom members from the 11-case min-norm forces (2 passes for self-weight)."""
    area = None
    for _ in range(8):
        tmax = None
        for cs in F.CASES:
            t, mem, _, _ = boom_members(tip, roots, anchors, cs, area)
            tmax = t if tmax is None else np.vstack([tmax, t])
        new = []
        for j, (p, u, L, cb) in enumerate(mem):
            tj = tmax[:, j]
            if cb:
                a = max(F.CAB_A[0], tj.max() / F.SIG_CAB)
            else:
                a = max(F.AREAS[0], tj.max() / F.SIG_T, -tj.min() / F.sig_c(L if L <= 12 else 4.0))  # long: truss
            new.append(a)
        area = np.array(new)
    return area, tmax


def point_loads(layout, rings):
    """Per case: list of (position, force) on the mast/lattice from the top emitter and the booms (not the mast's own
    weight). Booms: reactions at their roots and anchors. Also returns boom geometry for the assembler."""
    H = layout['H']
    booms = []
    for (yb, d, L) in layout.get('booms', []):
        tip, roots, anchors, ya = boom_nodes(rings, H, yb, d, L)
        area, tmax = size_boom(tip, roots, anchors)
        booms.append(dict(yb=yb, d=d, L=L, tip=tip, roots=roots, anchors=anchors, ya=ya, area=area, tmax=tmax))
    loads = []
    for cs in F.CASES:
        pl = []
        f = np.array([0.0, -F.EMIT_W, 0.0])
        if cs[1] is not None:
            f += F.EMIT_WIND0 * cs[1]
        pl.append((np.array([0.0, H, 0.0]), f, 'top'))
        for bi, b in enumerate(booms):
            t, mem, _, end_loads = boom_members(b['tip'], b['roots'], b['anchors'], cs, b['area'])
            for j, (p, u, L, cb) in enumerate(mem):
                pl.append((p, -t[j] * u + end_loads[j], ('anchor' if cb else 'root', bi, j)))
        loads.append(pl)
    return loads, booms


def sweep(layout, rings=None):
    """Top-down sweep in 1-block steps. If rings is None, choose ring sizes on the way down (pass 1, booms on the
    axis). Returns dict with per-interface per-case resultants and the envelopes."""
    H = layout['H']
    ring_ys = list(range(0, H + 1, BAY))
    choose = rings is None
    if choose:
        rings = {y: 0.0 for y in ring_ys}
        need = set()
        for (yb, d, L) in layout.get('booms', []):
            need.add(int(yb // BAY) * BAY); need.add(int(math.ceil(yb / BAY)) * BAY)
            need.add(int(yb // BAY) * BAY + BAY)
        rings_tmp = {y: 1.5 for y in ring_ys}
        loads, booms = point_loads(layout, rings_tmp)
    else:
        need = set()
        loads, booms = point_loads(layout, rings)
    nC = F.K
    # running resultants per case: force R and the moment about the origin Mo; M about (0,y,0) = Mo - y_hat*y x R
    R = np.zeros((nC, 3)); Mo = np.zeros((nC, 3))
    mast_lumps = [[] for _ in range(nC)]
    out = {}
    s_above = 0.0
    # sort point loads by height (descending) per case
    pls = [sorted(pl, key=lambda t: -t[0][1]) for pl in loads]
    idx = [0] * nC
    w_leg_above = 0.0
    for y in range(H, -1, -1):
        # add point loads strictly above y (and record those exactly at y as concentrated)
        conc = [[] for _ in range(nC)]
        for c in range(nC):
            while idx[c] < len(pls[c]) and pls[c][idx[c]][0][1] > y + 1e-6:
                p, f, tag = pls[c][idx[c]]
                R[c] += f; Mo[c] += np.cross(p, f); idx[c] += 1
            j = idx[c]
            while j < len(pls[c]) and abs(pls[c][j][0][1] - y) < 1e-6:
                conc[c].append(pls[c][j]); j += 1
        Mres = Mo - np.cross(np.array([0.0, y, 0.0]), R)        # moments about (0, y, 0)
        # include concentrated loads at y for the envelope used for sizing at/below y
        Rc, Mc = R.copy(), Mres.copy()
        for c in range(nC):
            for p, f, tag in conc[c]:
                Rc[c] += f; Mc[c] += np.cross(p - [0, y, 0], f)
        env = dict(N=float(max(0.0, (-Rc[:, 1]).max())), V=float(np.hypot(Rc[:, 0], Rc[:, 2]).max()),
                   M=float(np.hypot(Mc[:, 0], Mc[:, 2]).max()), T=float(np.abs(Mc[:, 1]).max()))
        if y % BAY == 0:
            if choose:
                s = 1.5 if y == 0 else (0.0 if y == H else pick_s(env['M'], env['N'], s_above, y in need))
                if y in need and s < 0.75:
                    s = 0.75
                rings[y] = s; s_above = s
            out[y] = dict(R=R.copy(), M=Mres.copy(), conc=conc, env=env, Rc=Rc, Mc=Mc)
        # mast block [y-1, y]: weight + drag lumped at y - 0.5, from a leg estimate at this section
        if y > 0:
            s_here = rings.get(int(math.ceil(y / BAY)) * BAY, 0.0) if choose else ring_s_at(rings, y - 0.5)
            if choose:
                s_here = s_above
            A_leg = (leg_force(env['M'], env['N'], s_here) / SIG_LEG) if s_here > 0 else 0.0
            A_leg = max(A_leg, F.AREAS[0]) if s_here > 0 else 0.0
            vol = POLE_A + 4 * A_leg + (0.06 if s_here > 0 else 0.0)
            wdt = 0.3 + 4 * math.sqrt(A_leg) + (0.25 if s_here > 0 else 0.0)
            p = np.array([0.0, y - 0.5, 0.0])
            for c, cs in enumerate(F.CASES):
                f = np.array([0.0, -F.RHO * vol, 0.0])
                if cs[1] is not None:
                    f += F.CD0 * wdt * cs[1]
                R[c] += f; Mo[c] += np.cross(p, f)
    return dict(rings=rings, ifc=out, booms=booms, loads=loads)


def run(layout):
    """Two passes: choose rings (booms assumed at s=1.5), then recompute with the actual boom geometry."""
    p1 = sweep(layout)
    rings = p1['rings']
    p2 = sweep(layout, rings)
    return p2


# ---------------------------------------------------------------- force-class ladder
LADDER_R = 1.6


def classify(v, floor):
    """Round v up onto the ladder floor * LADDER_R^k (k >= 0). Returns (k, value)."""
    if v <= 1e-9:
        return -1, 0.0
    if v <= floor:
        return 0, floor
    k = int(math.ceil(math.log(v / floor) / math.log(LADDER_R) - 1e-9))
    return k, floor * LADDER_R ** k


FLOORS = dict(N=4.0, V=0.5, M=1.0, T=0.5)


if __name__ == '__main__':
    import time, json
    lays = {
        'H100 bare': dict(H=100, booms=[]),
        'H100 4 booms @50 (L50)': dict(H=100, booms=[(50, d, 50) for d in 'ENSW']),
        'H100 4 booms @10 (L90)': dict(H=100, booms=[(10, d, 90) for d in 'ENSW']),
        'H100 lopsided E@30 L70': dict(H=100, booms=[(30, 'E', 70)]),
        'A H40 bare': dict(H=40, booms=[]),
        'B H24 2 booms': dict(H=24, booms=[(10, 'E', 8), (18, 'N', 6)]),
    }
    for name, lay in lays.items():
        t0 = time.perf_counter()
        sp_ = run(lay)
        dt = (time.perf_counter() - t0) * 1000
        print(f'== {name}: {dt:.1f} ms (python)')
        print('   rings', {y: s for y, s in sp_['rings'].items()})
        for y in sorted(sp_['ifc']):
            e = sp_['ifc'][y]['env']
            print(f"   y {y:3d} s {sp_['rings'][y]:.2f}  N {e['N']:7.2f} V {e['V']:6.2f} M {e['M']:8.2f} T {e['T']:6.2f}")
        for b in sp_['booms']:
            print(f"   boom {b['d']} y{b['yb']} L{b['L']} anchor y{b['ya']} areas {np.round(b['area'], 4)} tmin {b['tmax'].min(0).round(2)} tmax {b['tmax'].max(0).round(2)}")
