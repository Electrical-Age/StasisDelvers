"""TOWERLIB P3a: integer keys, quantised boom keys, net-moment back-stays with a capped anchor distance.
Builds on p25lib (bay / boom solves, stitch, validate). Cache: p3acache/ (never writes p25cache).

Integer keys (canonical form, reproduced by Java):
  boom key  BK = (Lc_i, rho_i, sr4, sa4)
     Lc_i  = index of the first L_LADDER value >= L
     rho_i = index of the largest RHO_LADDER value <= (ya - yb) / L (0 if none)
     sr4   = floor(4 * ring_s_at(rings, yb) + 0.5)        (root ring half-width, quarter blocks)
     sa4   = floor(4 * rings[ya] + 0.5)                   (anchor ring half-width, quarter blocks; 0 = pole)
  bay key   = (sb4, st4, mid, kd, kn, kv, kmg, mga, kt, pw_i, h, nroots, (y, d, *BK) * nroots)
     sb4/st4 ring half-widths in quarter blocks; mid 0/1; kd demand class; kn/kv/kmg/kt ladder classes (-1 = below
     floor); mga gravity-moment axis in 45 deg steps after the canonical rotation (-1 if kmg < 0); pw_i index of the
     pole width on F.WIDTHS; h bay height in blocks (4, top bay 4..7); roots sorted lexicographically as int tuples (y offset 1..4 within the bay, canonical
     direction 0..3 (E,S,W,N after rotation), boom key).
  canonical rotation r in 0..3: the r minimising (sorted roots with d -> (d - r) mod 4, (mga_world - 2 r) mod 8 or -1)
     lexicographically; ties -> smallest r.
"""
import os, sys
import p25lib as L25
import p2lib as L
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, os.environ.get('P3A_CACHE', 'p3acache'))
os.makedirs(CACHE, exist_ok=True)
L25.CACHE = CACHE; L.CACHE = CACHE
import hashlib, json, math, pickle, time
import numpy as np
import fem as F
import spine as S1
import p2spine as S
import p25fem as Q

TOL = 1e-6
DIRS = L.DIRS
# ---------------------------------------------------------------- arbitrary H: ring levels 0, 4, ..., 4(m-1), H
# (m = max(1, H // 4)); every bay is 4 high except the top bay, which is 4..7 high (H mod 4 extra). The bay height h
# is part of the bay key. p2spine.sweep / ring_s_at and p2lib.bay_problem are patched (identical for H % 4 == 0).
import bisect, inspect


def levels(H):
    m = max(1, H // S.BAY)
    return [S.BAY * i for i in range(m)] + [H]


def ring_s_at(rings, y):
    ys = sorted(rings)
    if y >= ys[-1]:
        return rings[ys[-1]]
    if y <= ys[0]:
        return rings[ys[0]]
    i = bisect.bisect_right(ys, y) - 1
    y0, y1 = ys[i], ys[i + 1]
    t = (y - y0) / (y1 - y0)
    return (1 - t) * rings[y0] + t * rings[y1]


def _patch(fn, repl, name, ns):
    src = inspect.getsource(fn)
    for a, b in repl:
        assert a in src, (name, a)
        src = src.replace(a, b)
    src = src.replace('def ' + fn.__name__ + '(', 'def ' + name + '(', 1)
    exec(src, ns)
    return ns[name]


_ns_s = vars(S)
sweep3a = _patch(S.sweep, [
    ("ring_ys = list(range(0, H + 1, BAY))", "ring_ys = levels(H); ring_set = set(ring_ys)"),
    ("""        need.add(int(yb // BAY) * BAY); need.add(int(math.ceil(yb / BAY)) * BAY)
        need.add(int(yb // BAY) * BAY + BAY)""",
     """        lo_ = max(v for v in ring_ys if v <= yb)
        need.add(lo_); need.add(ring_ys[min(ring_ys.index(lo_) + 1, len(ring_ys) - 1)])"""),
    ("if y % BAY == 0:", "if y in ring_set:")], 'sweep3a', _ns_s)
_ns_s['levels'] = levels
S.ring_s_at = ring_s_at; S1.ring_s_at = ring_s_at
S.sweep = sweep3a
_ns_l = vars(L)
bay_problem3a = _patch(L.bay_problem, [
    ("sb, st, pw = key['sb'], key['st'], key['pw']", "sb, st, pw = key['sb'], key['st'], key['pw']\n    HB = float(key.get('h', 4))"),
    ("r[0] < 4 - TOL", "r[0] < HB - TOL"),
    ("{2.0} | set(roots_y)", "{HB / 2} | set(roots_y)"),
    ("pt = add([0, 4.0, 0])", "pt = add([0, HB, 0])"),
    ("corners(st, 4.0)", "corners(st, HB)"),
    ("({2.0} if key['mid'] else set())", "({HB / 2} if key['mid'] else set())"),
    ("a + y / 4.0 * (b - a)", "a + y / HB * (b - a)"),
    ("dr) + [0, 4.0, 0]", "dr) + [0, HB, 0]"),
    ("c0 = np.array([0, 4.0, 0])", "c0 = np.array([0, HB, 0])")], 'bay_problem3a', _ns_l)
L.bay_problem = bay_problem3a


DNAME = 'ESWN'
rot = L.rot
BS_MIN_L = 14
D_CAP = float(os.environ.get('P3A_DCAP', '16'))   # ground-anchor distance cap (blocks behind the attachment ring)
D_FRAC = 0.5                                      # ... and at most this fraction of the attachment height
BS_T_MIN = 0.3                                    # wires with less design tension are dropped
BS_CAP = F.SIG_CAB * Q.BS_W[-1] ** 2 / 1.6        # per-wire design tension limit (room for the load change)
BACKSTAY_MODE = os.environ.get('P3A_BS', 'net')   # 'net' (P3a) or 'per' (P2.5 per-boom, for comparison)
LAYOUTS = dict(**{k: v for k, v in __import__('p2run').LAYOUTS.items()})
LAYOUTS['H40r3'] = dict(H=40, booms=[(y, d, Lb) for y, Lb in ((10, 16), (20, 10), (30, 6)) for d in 'ENWS'])
TEST7 = ['H16', 'H40', 'H100', 'H24b', 'H40b4', 'H64max', 'H40r3']


def q4(x):
    return int(math.floor(4 * x + 0.5 + 1e-9))


def khash(s):
    return hashlib.sha1(s.encode()).hexdigest()[:16]


# ================================================================ boom keys
# ---------------------------------------------------------------- stay rule (2026-10-13; Java Spine.anchorLevels)
# Preferred anchor: the first ring level >= yb + max(BAY, STAY_RISE L), else H (the P3a rule). Cap: the stays must
# anchor at or below the next boom level above in the same direction ('same': the only booms a boom's stays can
# cross), in any direction ('any'), or not at all ('none' = the P3a rule). Above the cap -> the highest ring level
# <= cap. Refused (Refused) if the capped rise < max(STAY_MIN_RISE, STAY_MIN_RATIO L).
STAY_CAP = os.environ.get('P3A_STAYCAP', 'same')
STAY_RISE = 0.7
STAY_MIN_RATIO = 0.35                             # = RHO_LADDER[0], the flattest stay the boom library is solved for
STAY_MIN_RISE = 2.0


class Refused(Exception):
    pass


BOOM_UTIL_MAX = 1.001                             # boom solves above this (repair gave up) are failed, not entries


def boom_failed(e):
    """A cached boom solve that is no valid design: LP infeasible (error) or repair gave up (taut util > 1). Its key is
    exported in the library header ('boom_failed'); a layout needing it is refused (Java: NO_BOOM_DESIGN)."""
    return 'error' in e or e.get('taut_util', 0) > BOOM_UTIL_MAX


def anchor_levels(layout, refuse=True):
    H = layout['H']
    ys = levels(H)
    booms = layout.get('booms', [])
    out, bad = [], []
    for (yb, d, Lb) in booms:
        c = [y for y in ys if y >= yb + max(S.BAY, STAY_RISE * Lb) - 1e-9]
        ya = c[0] if c else H
        cap = H
        if STAY_CAP != 'none':
            for (y2, d2, L2) in booms:
                if yb < y2 < cap and (STAY_CAP == 'any' or d2 == d):
                    cap = y2
        if ya > cap:
            ya = max([y for y in ys if y <= cap] or [ys[0]])
        out.append(ya)
        if ya - yb < max(STAY_MIN_RISE, STAY_MIN_RATIO * Lb) - 1e-9:
            bad.append(f'boom {d} y{yb} L{Lb}: stays capped at y{cap}, ring y{ya}, rise {ya - yb}')
    if bad and refuse:
        raise Refused('; '.join(bad))
    return out


def boom_key_int(rings, H, yb, Lb, ya):
    sr = S.ring_s_at(rings, yb)
    sa = rings[ya]
    li = next(i for i, x in enumerate(L.L_LADDER) if x >= Lb - 1e-9)
    ratio = (ya - yb) / Lb
    ri = max([i for i, r in enumerate(L.RHO_LADDER) if r <= ratio + 1e-9] or [0])
    return (li, ri, q4(sr), q4(sa)), ya


def boom_key_dict(bk):
    li, ri, sr4, sa4 = bk
    return dict(t='boom', Lc=L.L_LADDER[li], rho=L.RHO_LADDER[ri], sr=sr4 / 4, sa=sa4 / 4)


def boom_keystr(bk):
    return 'p3aboom' + json.dumps(list(bk))


# ================================================================ back-stays (net moment, capped anchors)
def bs_geometry(d, ya, sa, rings, npair):
    q = DIRS[d]
    lev = [ya] + ([y for y in sorted(rings) if y > ya][:1] if npair == 2 else [])
    if len(lev) < npair:
        return None
    att, gnd = [], []
    for y in lev:
        s = rings[y]
        D = min(D_FRAC * y, D_CAP) + s
        for sg in (1, -1):
            att.append(rot(np.array([-s, 0, sg * s]), q) + [0, y, 0] if s > 0 else np.array([0.0, y, 0]))
            gnd.append(rot(np.array([-D, 0, sg * (s + 0.35 * D)]), q))
    att, gnd = np.array(att), np.array(gnd)
    uv = (gnd - att) / np.linalg.norm(gnd - att, axis=1)[:, None]
    return att, gnd, uv


def mom_curve(pts_forces, ys):
    """Horizontal moment (x, z components) about the mast axis at each y from the forces above y."""
    if not len(pts_forces):
        return np.zeros((len(ys), 2))
    Pp = np.array([np.asarray(p, float) for p, f in pts_forces]); Ff = np.array([np.asarray(f, float) for p, f in pts_forces])
    out = np.zeros((len(ys), 2))
    for k, y in enumerate(ys):
        m = Pp[:, 1] > y - 1e-9
        if m.any():
            out[k] = np.cross(Pp[m] - [0, y, 0], Ff[m]).sum(0)[[0, 2]]
    return out


def backstays_net(grav, bspec, H, rings):
    """grav: gravity-case (pos, force) of all booms (and nothing else). bspec: list of (boom index, d, ya, sa) for
    booms with L >= BS_MIN_L. Wire tensions T_i >= 0 (common to the wires of boom i) minimise the peak net moment
    |M(y)| over 0 <= y <= H by coordinate descent (balanced opposite booms -> T = 0, no wires). One pair per boom; a
    second pair at the ring above if T would exceed BS_CAP. Returns {boom index: (wires, info)}."""
    ys = np.arange(0, H + 1, 0.5)
    Mb = mom_curve(grav, ys)
    geo, mw = {}, {}
    npairs = {bi: 1 for bi, *_ in bspec}
    for rnd in range(2):
        for bi, d, ya, sa in bspec:
            g = bs_geometry(d, ya, sa, rings, npairs[bi]) or bs_geometry(d, ya, sa, rings, 1)
            geo[bi] = g
            mw[bi] = mom_curve(list(zip(g[0], g[2])), ys)
        T = {bi: 0.0 for bi in geo}
        for sweep in range(4):
            for bi in geo:
                rest = Mb + sum(T[j] * mw[j] for j in geo if j != bi)
                tmax = 4 * np.abs(Mb).max() / max(1e-6, np.abs(mw[bi]).max())
                Ts = np.linspace(0, tmax, 401)
                peak = [np.linalg.norm(rest + t * mw[bi], axis=1).max() for t in Ts]
                T[bi] = float(Ts[int(np.argmin(peak))])
        over = [bi for bi in geo if T[bi] > BS_CAP and npairs[bi] == 1 and bs_geometry(*[x for x in next(b for b in bspec if b[0] == bi)[1:]], rings, 2) is not None]
        if not over:
            break
        for bi in over:
            npairs[bi] = 2
    peak0 = float(np.linalg.norm(Mb, axis=1).max())
    tot = Mb + sum(T[j] * mw[j] for j in geo) if geo else Mb
    peak = float(np.linalg.norm(tot, axis=1).max())
    out = {}
    for bi, d, ya, sa in bspec:
        att, gnd, uv = geo[bi]
        wires = [(a, g_, T[bi]) for a, g_ in zip(att, gnd)] if T[bi] > BS_T_MIN else []
        D = float(np.hypot(*(gnd[0] - att[0])[[0, 2]]))
        out[bi] = (wires, dict(T=T[bi], n=len(wires), npair=npairs[bi], peak=peak, peak0=peak0, D=D,
                               feasible=bool(T[bi] <= BS_CAP), att_y=float(att[0][1]),
                               angle=float(math.degrees(math.atan2(att[0][1], D)))))
    return out


def bs_loads(wires, d, yb):
    return [(a, T * (g - a) / np.linalg.norm(g - a), ('anchor', 'bs', d, yb, i)) for i, (a, g, T) in enumerate(wires)]


def apply_backstays(booms, rings, H, grav_by_boom, ya_sa):
    """grav_by_boom[bi]: gravity (pos, force) list; ya_sa[bi] = (ya, sa). Returns {bi: (wires, info)}."""
    bspec = [(bi, d, ya_sa[bi][0], ya_sa[bi][1]) for bi, (yb, d, Lb) in enumerate(booms) if Lb >= BS_MIN_L]
    if not bspec:
        return {}
    if BACKSTAY_MODE == 'per':
        out = {}
        for bi, d, ya, sa in bspec:
            out.update(backstays_net(grav_by_boom[bi], [(bi, d, ya, sa)], H, rings))
        return out
    grav = [pf for bi in sorted(grav_by_boom) for pf in grav_by_boom[bi]]
    return backstays_net(grav, bspec, H, rings)


_YA = {}                                          # (yb, d, L) -> anchor level, read by the patched spine.boom_nodes


def _boom_nodes_capped(rings, H, yb, d, L):
    dx, dz = S1.DIRS[d]
    dv = np.array([dx, 0.0, dz]); pv = np.array([-dz, 0.0, dx])
    tip = np.array([L * dx, yb, L * dz], float)
    sr = S1.ring_s_at(rings, yb)
    roots = [np.array([0, yb, 0.0]) + sr * dv + k * sr * pv for k in (1, -1)]
    ya = _YA.get((yb, d, L))
    if ya is None:                                # older pipelines (p2lib / p25lib): the P3a rule
        return _boom_nodes_p3a(rings, H, yb, d, L)
    sa = rings[ya]
    anchors = [np.array([0, ya, 0.0]) + sa * dv + k * sa * pv for k in (1, -1)] if sa > 0 else [np.array([0, ya, 0.0])]
    return tip, roots, anchors, ya


_boom_nodes_p3a = S1.boom_nodes
S1.boom_nodes = _boom_nodes_capped


def spine3a(layout, solve_booms):
    """P2 spine, back-stays from the net static moment of all booms, integer boom keys."""
    H = layout['H']
    booms = layout.get('booms', [])
    rings_tmp = {y: 1.5 for y in levels(H)}
    yas = anchor_levels(layout)
    _YA.clear(); _YA.update({(yb, d, Lb): ya for (yb, d, Lb), ya in zip(booms, yas)})
    if booms:
        l1, b1 = S1.point_loads(layout, rings_tmp)
        loads1 = [[(p, f, t) for p, f, t in pl] for pl in l1]
        grav = {bi: [(p, f) for p, f, t in l1[0] if t != 'top' and t[1] == bi] for bi in range(len(booms))}
        bs = apply_backstays(booms, rings_tmp, H, grav, {bi: (bb['ya'], rings_tmp[bb['ya']]) for bi, bb in enumerate(b1)})
        for bi, (w, _) in bs.items():
            for k in range(F.K):
                loads1[k] += bs_loads(w, booms[bi][1], booms[bi][0])
    else:
        loads1 = S.top_loads(H)
    p1 = sweep3a(layout, None, loads1)
    rings = p1['rings']
    bk = [boom_key_int(rings, H, yb, Lb, ya) for (yb, d, Lb), ya in zip(booms, yas)]
    bents = solve_booms([k for k, _ in bk]) if booms else []
    loads2 = S.top_loads(H)
    binfo, grav, yasa, bls = [], {}, {}, []
    for bi, ((yb, d, Lb), (key, ya), be) in enumerate(zip(booms, bk, bents)):
        sr = S.ring_s_at(rings, yb); sa = rings[ya]
        bl = L.boom_loads_world(be, yb, d, sr, ya, sa)
        bls.append(bl)
        grav[bi] = [(p, f) for p, f, t in bl[0]]; yasa[bi] = (ya, sa)
        for k in range(F.K):
            loads2[k] += bl[k]
        binfo.append(dict(yb=yb, d=d, L=Lb, ya=ya, sr=sr, sa=sa, key=be['key'], ikey=key, entry=be, wires=[],
                          bsinfo=dict(n=0)))
    bs = apply_backstays(booms, rings, H, grav, yasa) if booms else {}
    for bi, (w, wi) in bs.items():
        binfo[bi]['wires'] = w; binfo[bi]['bsinfo'] = wi
        for k in range(F.K):
            loads2[k] += bs_loads(w, booms[bi][1], booms[bi][0])
    p2 = sweep3a(layout, rings, loads2)
    p2['booms'] = binfo
    return p2


# ================================================================ bay specs, integer bay keys
def canon_rot(roots, mga):
    best = None
    for r in range(4):
        rr = tuple(sorted((y, (d - r) % 4) + tuple(bk) for y, d, bk in roots))
        ma = (mga - 2 * r) % 8 if mga is not None else -1
        cand = (rr, ma)
        if best is None or cand < best[0]:
            best = (cand, r)
    return best[1]


def pw_index(pw):
    return int(np.argmin(np.abs(F.WIDTHS - pw)))


def bay_specs(layout, sp):
    """As p2lib.bay_specs, with integer keys. spec['ikey'] is the plain integer key."""
    H = layout['H']
    rings = sp['rings']
    specs = []
    roots_all = [(b['yb'], DIRS[b['d']], tuple(b['ikey']), bi) for bi, b in enumerate(sp['booms'])]
    lv = sorted(rings)
    for y0, y1 in zip(lv[:-1], lv[1:]):
        hb = y1 - y0
        sb, st = rings[y0], rings[y1]
        ifc = sp['ifc'][y1]
        Rt = ifc['R'].copy(); Mt = ifc['M'].copy()
        for c in range(F.K):
            for p, f, tag in ifc['conc'][c]:
                if tag == 'top' or tag[0] == 'anchor':
                    Rt[c] += f; Mt[c] += np.cross(p - [0, y1, 0], f)
        roots = [(int(round(yb - y0)), d, bk) for yb, d, bk, bi in roots_all if y0 + TOL < yb <= y1 + TOL]
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
        mga = int(round(math.degrees(math.atan2(Mg[2], Mg[0])) / 45)) % 8 if kmg >= 0 else None
        kt, tv = S.ladder(T, 0.5, S.T_R)
        r = canon_rot(roots, mga)
        mid = bool(sb > 0 and st > 0 and S.leg_force(Mw + np.linalg.norm(Mg), N, sb) > S.MID_F)
        pw = F.W_POLE_MIN
        Nc, Vc, Mc = max(nv, Nr), max(vv, Vr), Mr
        if st == 0:
            need = Mc + mgv + (4 if sb == 0 else 2) * Vc
            pw = max(F.W_POLE_MIN, (1.15 * need / 25.0) ** (1 / 3))
            pw = float(F.WIDTHS[np.searchsorted(F.WIDTHS, pw - 1e-9)])
        croots = sorted((y, (d - r) % 4) + tuple(bk) for y, d, bk in roots)
        mgc = (mga - 2 * r) % 8 if mga is not None else -1
        ikey = (q4(sb), q4(st), int(mid), kd, kn, kv, kmg, mgc, kt, pw_index(pw), hb, len(croots)) + \
            tuple(x for rt in croots for x in rt)
        key = dict(t='bay', h=hb, sb=sb, st=st, mid=mid, kd=kd, kn=kn, kv=kv, mg=[kmg, mgc], kt=kt, pw=pw,
                   roots=[[rt[0], rt[1], boom_keystr(rt[2:])] for rt in croots])
        specs.append(dict(y0=y0, y1=y1, sb=sb, st=st, r=r, key=key, ikey=ikey, rootb=rootb,
                          env=dict(N=N, Vw=Vw, Mw=Mw, Mg=float(np.linalg.norm(Mg)), T=T),
                          cls=dict(N=Nc, V=Vc, M=Mc, Mg=mgv, T=tv)))
    for s in specs:
        s['plainkey'] = 'p3abay' + json.dumps(list(s['ikey']))
    for i, s in enumerate(specs):
        if i + 1 < len(specs):
            ab = specs[i + 1]
            s['above'] = dict(keystr=ab['plainkey'], r=ab['r'], ikey=ab['ikey'])
            s['seam_ikey'] = tuple(s['ikey']) + (-9, (ab['r'] - s['r']) % 4) + tuple(ab['ikey'])
            s['seamkey'] = 'p3abay' + json.dumps(list(s['seam_ikey']))
        else:
            s['above'] = None
            s['seam_ikey'] = tuple(s['ikey'])
            s['seamkey'] = s['plainkey']
    return specs


# ================================================================ solving
def leg_boom_entries(spec, boom_ents):
    """Per-root boom entries for the bay problem, with the root half-width set to the bay leg corner at the root
    height (quantised boom keys don't carry the exact ring size). Returns (key with per-root tags, entries)."""
    key = dict(spec['key'])
    sb, st = key['sb'], key['st']
    roots, ents = [], {}
    for y, d, bks in key['roots']:
        s_y = sb + (y / float(key.get('h', 4))) * (st - sb)
        tag = f'{bks}@{y}'
        be = boom_ents[bks]
        ents[tag] = dict(be, key=dict(be['key'], sr=s_y))
        roots.append([y, d, tag])
    key['roots'] = roots
    return key, ents


def solve_job(job):
    """Wraps p25lib.solve_entry25 (P2.5 design, low-q variant, cache) for P3a jobs."""
    os.environ['OMP_NUM_THREADS'] = '1'
    if job['kind'] == 'bay':
        spec = dict(job['spec'])
        key, ents = leg_boom_entries(spec, job['boom_entries'])
        spec['key'] = key
        j2 = dict(kind='bay', key=key, keystr=job['keystr'], spec=spec, boom_entries=ents)
        if job.get('above_entry') is not None:
            j2['above_entry'] = job['above_entry']
        t0 = time.time()
        e = L25.solve_entry25(j2)
    else:
        e = L25.solve_entry25(dict(kind='boom', key=job['key'], keystr=job['keystr']))
    if 'error' not in e:
        e['ikey'] = job['ikey']
    return e


def cached(keystr):
    return os.path.exists(os.path.join(CACHE, khash(keystr) + '.pkl'))


def load(keystr):
    fn = os.path.join(CACHE, khash(keystr) + '.pkl')
    return pickle.load(open(fn, 'rb')) if os.path.exists(fn) else None


def boom_job(bk):
    return dict(kind='boom', key=boom_key_dict(bk), ikey=tuple(bk), keystr=boom_keystr(bk))


def build(layout, pool, log=print, seam=False):
    t0 = time.perf_counter()
    boom_ents = {}
    stats = dict(solved=0, hits=0)

    def solve_many(jobs):
        todo = [j for j in jobs if not cached(j['keystr'])]
        stats['hits'] += len(jobs) - len(todo); stats['solved'] += len(todo)
        res = pool.map(solve_job, todo, chunksize=1) if todo else []
        for r in res:
            if 'error' in r:
                raise RuntimeError(f"{r['keystr']}: {r['error']} {r['log'][-3:]}")
        out = []
        for j in jobs:
            e = load(j['keystr']); e['ikey'] = j['ikey']; out.append(e)
        return out

    def solve_booms(keys):
        jobs = [boom_job(k) for k in keys]
        ents = solve_many(jobs)
        for j, e in zip(jobs, ents):
            boom_ents[j['keystr']] = e
        return ents
    sp = spine3a(layout, solve_booms=solve_booms)
    specs = bay_specs(layout, sp)
    t_spine = time.perf_counter() - t0
    jobs = {}
    for s in specs:
        jobs.setdefault(s['plainkey'], dict(kind='bay', key=s['key'], ikey=s['ikey'], keystr=s['plainkey'], spec=s,
                                            boom_entries={k: boom_ents[k] for k in {r[2] for r in s['key']['roots']}}))
    ents = dict(zip(jobs, solve_many(list(jobs.values()))))
    if seam:
        jobs2 = {}
        for s in specs:
            if s['above'] is None:
                continue
            jobs2.setdefault(s['seamkey'], dict(kind='bay', key=s['key'], ikey=s['seam_ikey'], keystr=s['seamkey'], spec=s,
                                                above_entry=ents[s['above']['keystr']],
                                                boom_entries={k: boom_ents[k] for k in {r[2] for r in s['key']['roots']}}))
        ents.update(zip(jobs2, solve_many(list(jobs2.values()))))
    t_lib = time.perf_counter() - t0 - t_spine
    use = [ents[s['seamkey'] if seam else s['plainkey']] for s in specs]
    return dict(layout=layout, sp=sp, specs=specs, entries=use, boom_entries=boom_ents, t_spine=t_spine,
                t_lib=t_lib, solve_stats=stats, all_entries=ents, seam=seam)


stitch = L25.stitch
validate = L25.validate
