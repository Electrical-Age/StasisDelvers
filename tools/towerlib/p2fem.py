"""P2 additions to fem.py:
- fast strict tension-only check (Cholesky instead of eigvalsh; eigh only when a mechanism needs its mode);
- pretensioned design check: taut linear model, per-cable pretension pre_i = max(0, -min_case dN_i), cable util
  (pre_i + max(dN_i, 0)) / cap; struts from the taut model (pretension self-stress in struts is ignored);
- design_pre: fem.design without the strict tension-only stabilisation loop, elastic repair on the taut model;
- per-node pole sizing in the repairs (fem bumped the whole pole);
- runtime widening pass (strict, no pretension) with stitched-tower stabilisation.
"""
import time
import numpy as np
import scipy.linalg as sla
import fem as F

TAUT_PRE_MAX = 0.5          # max pretension / cable capacity accepted in a bay design


def _factor(Kf):
    dg = np.diag(Kf)
    if dg.min() <= 1e-9 * dg.max():
        return None
    try:
        return sla.cho_factor(Kf, check_finite=False)
    except np.linalg.LinAlgError:
        return None


def tension_only(prob, bars, cab, width, cases=None):
    P = prob['P']
    area = width ** 2
    fr = F.freedofs(prob, bars)
    d, L = F.geometry(P, bars)
    e = d / L[:, None]
    ex = F.exact_loads(prob, bars, cab, width)
    out = []
    for k, cs in enumerate(F.CASES):
        if cases is not None and k not in cases:
            continue
        f = ex[k]
        active = np.ones(len(bars), bool)
        seen = set()
        res = dict(case=cs[0], k=k, pd=False, disp=np.inf, slack=0, iters=0, converged=False)
        for it in range(25):
            Km = F.assemble(prob, bars, area, active)
            Kf = Km[np.ix_(fr, fr)]
            sc = np.sqrt(np.maximum(np.diag(Kf), 1e-30))
            fac = _factor(Kf / sc[:, None] / sc[None, :] + 0 * np.eye(len(fr)))
            if fac is None or np.min(np.abs(np.diag(fac[0]))) < 1e-6:
                res.update(pd=False, iters=it, slack=int((cab & ~active).sum()), active=active.copy())
                break
            u = np.zeros(3 * len(P)); u[fr] = sla.cho_solve(fac, f[fr] / sc) / sc
            U = u.reshape(-1, 3)
            strain = np.einsum('ij,ij->i', U[bars[:, 1]] - U[bars[:, 0]], e)
            new = ~cab | (strain > 0)
            res.update(pd=True, disp=float(np.max(np.linalg.norm(U, axis=1))), iters=it,
                       slack=int((cab & ~active).sum()), N=F.E * area * strain / L * active, U=U, active=active.copy())
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


def taut(prob, bars, cab, width):
    """Linear all-taut response. Returns (ok, dN (K, m), disp, U list)."""
    P = prob['P']
    area = width ** 2
    fr = F.freedofs(prob, bars)
    d, L = F.geometry(P, bars)
    e = d / L[:, None]
    Km = F.assemble(prob, bars, area, np.ones(len(bars), bool))
    Kf = Km[np.ix_(fr, fr)]
    sc = np.sqrt(np.maximum(np.diag(Kf), 1e-30))
    fac = _factor(Kf / sc[:, None] / sc[None, :])
    if fac is None or np.min(np.abs(np.diag(fac[0]))) < 1e-6:
        return False, None, np.inf, None
    ex = F.exact_loads(prob, bars, cab, width)
    dN, Us, disp = [], [], 0.0
    for k in range(F.K):
        u = np.zeros(3 * len(P)); u[fr] = sla.cho_solve(fac, ex[k][fr] / sc) / sc; U = u.reshape(-1, 3)
        disp = max(disp, float(np.max(np.linalg.norm(U, axis=1))))
        dN.append(F.E * area / L * np.einsum('ij,ij->i', U[bars[:, 1]] - U[bars[:, 0]], e))
        Us.append(U)
    return True, np.array(dN), disp, Us


def pre_util(prob, bars, cab, width, dN):
    """Per member util under the pretensioned model, and per cable pretension (force units)."""
    _, L = F.geometry(prob['P'], bars)
    A = width ** 2
    pre = np.where(cab, np.maximum(0, -dN.min(0)), 0.0)
    capT = np.where(cab, F.SIG_CAB, F.SIG_T) * A
    capC = F.sig_c(L) * A
    mx, mn = dN.max(0), dN.min(0)
    u = np.where(cab, (pre + np.maximum(mx, 0)) / capT, np.maximum(np.maximum(mx, 0) / capT, np.maximum(-mn, 0) / capC))
    return u, pre


def pole_kinks_nodes(prob, Us):
    pk = None
    for U in Us:
        v = F.pole_kinks(prob, U)
        pk = v if pk is None else np.maximum(pk, v)
    return pk if pk is not None else np.zeros(0)


def bump_pole(prob, bars, cab, sec, pk, target):
    """Per-node pole widening: interior pole node k (index into prob['pole']) over target gets a wider section;
    pole segments adjacent to bumped nodes get at least that width."""
    changed = False
    pole = prob['pole']
    for k, v in enumerate(pk):
        if v > target:
            node_k = k + 1
            w = prob['pw'][node_k] * v ** (1 / 3) * 1.03
            i = int(np.searchsorted(F.WIDTHS, w - 1e-9))
            prob['pw'][node_k] = F.WIDTHS[min(i, len(F.WIDTHS) - 1)]
            changed = True
    if changed:
        pidx = {n: k for k, n in enumerate(pole)}
        for b, (i, j) in enumerate(bars):
            if i in pidx and j in pidx and not cab[b]:
                w = max(prob['pw'][pidx[i]], prob['pw'][pidx[j]])
                sec[b] = max(sec[b], int(np.searchsorted(F.WIDTHS, w - 1e-9)))
    return changed


def _bump(sec, cab, width, i, u):
    A = width[i] ** 2
    tgt = A * u * 1.08
    cat = F.CAB_A if cab[i] else F.AREAS
    ns = max(int(np.searchsorted(cat, tgt)), sec[i] + 1)
    if ns >= len(cat):
        if cab[i]:
            cab[i] = False; ns = int(min(np.searchsorted(F.AREAS, tgt), len(F.AREAS) - 1))
        else:
            ns = len(cat) - 1
    sec[i] = ns


def taut_repair(prob, bars, cab, sec, closure, log, target=1.0, its=14):
    sec, cab = sec.copy(), cab.copy()
    nb = 0
    for it in range(its):
        width = F.sec_width(sec, cab)
        ok, dN, disp, Us = taut(prob, bars, cab, width)
        if not ok:
            log('  taut repair: mechanism'); break
        u, pre = pre_util(prob, bars, cab, width, dN)
        pk = pole_kinks_nodes(prob, Us)
        bad = np.nonzero(u > target)[0]
        if not len(bad) and (not len(pk) or pk.max() <= target):
            break
        for i in bad:
            _bump(sec, cab, width, i, u[i])
        if len(pk):
            bump_pole(prob, bars, cab, sec, pk, target)
        if closure:
            sec = F.tie_secs(prob['P'], bars, cab, sec)
        nb += len(bad)
    return sec, cab, nb


def design_pre(prob, closure=False, log=print):
    """fem.design with pretensioned-X acceptance: no strict tension-only stabilisation; the taut structure must be
    stable and the pretension need <= TAUT_PRE_MAX of capacity (else the worst cables become struts)."""
    t0 = time.time()
    a, tie = F.reduce(prob, closure, log)
    pb, pc, mand = prob['bars'], prob['cab'], prob['mand']
    done = None
    for thr in (0.2, 0.05, 0.01, 1e-3, 0.0):
        kk = ((a > thr * F.AREAS[0]) & ~pc) | ((a > thr * F.CAB_A[0]) & pc) | mand
        if thr == 0.0:
            kk = ~pc | mand | (a > 0)
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
        bars, cab = pb[kk], pc[kk].copy()
        ak = a[kk]
        minsec = np.where(cab, 0, np.searchsorted(F.AREAS, prob['amin'][kk] * 0.999))
        sec = np.where(cab, np.minimum(np.searchsorted(F.CAB_A, ak * 0.999), len(F.CAB_A) - 1),
                       np.minimum(np.searchsorted(F.AREAS, ak * 0.999), len(F.AREAS) - 1))
        sec = np.maximum(sec, minsec)
        if closure:
            sec = F.tie_secs(prob['P'], bars, cab, sec)
        try:
            sec, cab, nrep = F.repair(prob, bars, cab, sec, minsec, log)
            done = thr
            break
        except RuntimeError:
            log(f'  threshold {thr}: equilibrium impossible at drawn widths, lowering')
    if done is None:
        raise RuntimeError('no feasible threshold')
    if closure:
        sec = F.tie_secs(prob['P'], bars, cab, sec)
    bars, cab, sec, minsec, added = F.stabilise(prob, bars, cab, sec, minsec, log)   # taut stability only
    bars, cab, sec, minsec, ns = strict_stabilise(prob, bars, cab, sec, minsec, closure, log)
    added += ns
    sec, cab, nel = taut_repair(prob, bars, cab, sec, closure, log)
    # pretension cap: cables needing more than TAUT_PRE_MAX of capacity as pretension become struts
    for it in range(6):
        width = F.sec_width(sec, cab)
        ok, dN, disp, Us = taut(prob, bars, cab, width)
        _, pre = pre_util(prob, bars, cab, width, dN)
        cap = F.SIG_CAB * width ** 2
        r = np.where(cab, pre / cap, 0)
        if r.max() <= TAUT_PRE_MAX:
            break
        worst = np.nonzero(r > TAUT_PRE_MAX)[0]
        log(f'  pretension > {TAUT_PRE_MAX}: {len(worst)} cables -> struts')
        cab[worst] = False; sec[worst] = 0
        sec, cab, n2 = taut_repair(prob, bars, cab, sec, closure, log)
    width = F.sec_width(sec, cab)
    ok, dN, disp, Us = taut(prob, bars, cab, width)
    u, pre = pre_util(prob, bars, cab, width, dN)
    return dict(bars=bars, cab=cab, width=width, sec=sec, pre=pre, gen_time=time.time() - t0, repairs=nrep,
                added=added, bumps=nel, pw=prob['pw'].copy(), taut_util=float(u.max()), taut_disp=disp,
                pre_ratio=float((pre / (F.SIG_CAB * width ** 2))[cab].max()) if cab.any() else 0.0)


def reactions(prob, bars, cab, width, sup):
    """Per case support reactions (force the structure exerts ON the support = - support reaction) at nodes sup,
    from the taut linear model. Returns (K, len(sup), 3)."""
    P = prob['P']
    ok, dN, disp, Us = taut(prob, bars, cab, width)
    d, L = F.geometry(P, bars)
    e = d / L[:, None]
    ex = F.exact_loads(prob, bars, cab, width).reshape(F.K, -1, 3)
    n = len(P)
    out = np.zeros((F.K, len(sup), 3))
    idx = {s: k for k, s in enumerate(sup)}
    for k in range(F.K):
        # internal force on node i from bar (i,j): +N e  (tension pulls i toward j)
        fn = np.zeros((n, 3))
        np.add.at(fn, bars[:, 0], dN[k][:, None] * e)
        np.add.at(fn, bars[:, 1], -dN[k][:, None] * e)
        # pole shear transfer: K_pole U
        Kp = F.pole_spring(P, prob['pole'], prob['pw'], n)
        fp = (Kp @ Us[k].ravel()).reshape(-1, 3)
        # equilibrium at a support: R + ext + fn - fp = 0 ; load the structure puts on the support = -R
        for s, j in idx.items():
            out[k, j] = ex[k, s] + fn[s] - fp[s]
    return out


def widen(prob, bars, cab, width, log=print, target=1.0, its=32, stabilise=True):
    """Runtime pass on the stitched tower: strict tension-only (no pretension). Whenever a case is a mechanism, the
    slack cables its mode needs become struts (or a strut is added); otherwise members (and pole nodes) over the
    target utilisation are widened. Repeats until stable in all cases and util <= target."""
    sec = np.where(cab, np.abs(F.CAB_W[None] - width[:, None]).argmin(1), np.abs(F.WIDTHS[None] - width[:, None]).argmin(1))
    cab = cab.copy()
    conv, added, nb = 0, 0, 0
    minsec = np.zeros(len(bars), int)
    sec0 = sec.copy(); cab0 = cab.copy(); pw0 = prob['pw'].copy(); nb_0 = len(bars)
    t_stab = 0.0
    for it in range(its):
        w = F.sec_width(sec, cab)
        to = tension_only(prob, bars, cab, w)
        bad = [r for r in to if not r['pd']]
        if bad:
            t1 = time.perf_counter()
            n0, c0 = len(bars), int(cab.sum())
            bars, cab, sec, minsec, a2 = F.stabilise(prob, bars, cab, sec, minsec, lambda *a: None, active=bad[0]['active'])
            conv += c0 - int(cab[:n0].sum()); added += len(bars) - n0
            t_stab += time.perf_counter() - t1
            continue
        _, L = F.geometry(prob['P'], bars)
        capT = np.where(cab, F.SIG_CAB, F.SIG_T) * w ** 2
        capC = np.where(cab, np.inf, F.sig_c(L) * w ** 2)
        u = np.zeros(len(bars)); Us = []
        for r in to:
            u = np.maximum(u, np.where(r['N'] > 0, r['N'] / capT, -r['N'] / capC)); Us.append(r['U'])
        pk = pole_kinks_nodes(prob, Us)
        badm = np.nonzero(u > target)[0]
        if not len(badm) and (not len(pk) or pk.max() <= target):
            break
        for i in badm:
            _bump(sec, cab, w, i, u[i])
        if len(pk):
            bump_pole(prob, bars, cab, sec, pk, target)
        nb += len(badm)
    width = F.sec_width(sec, cab)
    w0 = F.sec_width(sec0, cab0)
    widened = int(np.sum(width[:nb_0] > w0 + 1e-9))
    return bars, cab, width, dict(converted=conv, added=added, widened=widened,
                                  pole_nodes_widened=int((prob['pw'] > pw0 + 1e-9).sum()), iters=it, bumps=nb,
                                  t_stabilise=t_stab)


def check(prob, bars, cab, width):
    to = tension_only(prob, bars, cab, width)
    _, L = F.geometry(prob['P'], bars)
    capT = np.where(cab, F.SIG_CAB, F.SIG_T) * width ** 2
    capC = np.where(cab, np.inf, F.sig_c(L) * width ** 2)
    u = np.zeros(len(bars)); Us = []
    for r in to:
        if r['pd']:
            u = np.maximum(u, np.where(r['N'] > 0, r['N'] / capT, -r['N'] / capC)); Us.append(r['U'])
    pk = pole_kinks_nodes(prob, Us)
    ok, dN, tdisp, _ = taut(prob, bars, cab, width)
    pu, pre = pre_util(prob, bars, cab, width, dN) if ok else (None, None)
    Nst = np.array([r['N'] if r['pd'] else np.zeros(len(bars)) for r in to]).T
    return dict(all_pd=all(r['pd'] for r in to), n_pd=sum(r['pd'] for r in to),
                failed=[r['case'] for r in to if not r['pd']],
                max_disp=max(r['disp'] for r in to), util=u, max_util=float(u.max()) if len(u) else 0.0,
                pole_util=float(pk.max()) if len(pk) else 0.0, taut_pd=ok, taut_disp=tdisp,
                taut_util=float(pu.max()) if ok else np.inf,
                pre_need=float((pre / (F.SIG_CAB * width ** 2))[cab].max()) if ok and cab.any() else 0.0,
                vol=float(np.sum(L * width ** 2)), vol_cables=float(np.sum((L * width ** 2)[cab])), N=Nst)


def _orbit_close(P, bars, cab, sec, minsec, new_pairs):
    rot1 = lambda p: np.array([-p[2], p[1], p[0]])
    keyp = {tuple(np.round(p, 3)): k for k, p in enumerate(P)}
    have = {tuple(sorted(b)) for b in bars.tolist()}
    add = []
    for (i, j) in new_pairs:
        a, b = P[i], P[j]
        for _ in range(3):
            a, b = rot1(a), rot1(b)
            ia, ib = keyp.get(tuple(np.round(a, 3))), keyp.get(tuple(np.round(b, 3)))
            if ia is not None and ib is not None and tuple(sorted((ia, ib))) not in have:
                have.add(tuple(sorted((ia, ib)))); add.append((ia, ib))
    if add:
        bars = np.vstack([bars, add]); cab = np.concatenate([cab, [False] * len(add)])
        sec = np.concatenate([sec, [0] * len(add)]); minsec = np.concatenate([minsec, [0] * len(add)])
    return bars, cab, sec, minsec, len(add)


def strict_stabilise(prob, bars, cab, sec, minsec, closure, log):
    """Make the bay stable in the strict tension-only check (no pretension) in all cases WITHOUT touching the X
    cables: add horizontal struts only (ring diagonals, spokes to the pole), which make each ring a rigid
    diaphragm so that a face whose cables are all slack is held by the other faces. Falls back to any pair."""
    P = prob['P']
    hz = np.array([abs(P[i][1] - P[j][1]) < 1e-6 for i, j in prob['bars']])
    probH = dict(prob); probH['bars'] = prob['bars'][hz & ~prob['cab']]
    added = 0
    for it in range(12):
        w = F.sec_width(sec, cab)
        bad = [r for r in tension_only(prob, bars, cab, w) if not r['pd']]
        if not bad:
            break
        nb0 = len(bars)
        act = bad[0]['active']
        try:
            # keep slack cables as cables: give stabilise a view where they count as existing
            bars2, cab2, sec2, minsec2, a2 = F.stabilise(probH, bars, cab.copy(), sec.copy(), minsec, lambda *a: None,
                                                          active=None if False else act)
            if (cab2[:nb0] != cab[:nb0]).any():
                raise RuntimeError('converted')
        except RuntimeError:
            bars2, cab2, sec2, minsec2, a2 = F.stabilise(prob, bars, cab.copy(), sec.copy(), minsec, lambda *a: None,
                                                          active=act)
        new = [tuple(b) for b in bars2[nb0:].tolist()]
        bars, cab, sec, minsec = bars2, cab2, sec2, minsec2
        if closure and new:
            bars, cab, sec, minsec, nn = _orbit_close(P, bars, cab, sec, minsec, new)
            added += nn
        added += len(bars2) - nb0
    if added:
        log(f'  strict stabilise: {added} horizontal braces')
    return bars, cab, sec, minsec, added
