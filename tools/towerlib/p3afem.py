"""P3a runtime-pass variants (pretension solve inside the widening loop), for picking the Java spec.

mode 'pinv'  : P2.5 reference. Influence matrix S (n_c x n_c) from an n_c-column solve, SVD pseudo-inverse,
               target-raise loop.
mode 'xpair' : (a) crossing cable pairs share one unknown (delta_c = z_g / k_c for c in group g). Galerkin system
               (Z^T S Z) z = Z^T tau, dense Cholesky n_g x n_g (no SVD), same target-raise loop. Still needs S.
mode 'nnls'  : (b) Lawson-Hanson NNLS  min ||S delta - tau||, delta >= 0, same target-raise loop. Still needs S.
mode 'fixf'  : (c) delta_c = f * floor_c / k_c (recorded pretension x factor), one extra right-hand side, then a
               raise-only loop on cables below PRE_MARGIN * need: delta_c += omega * (target_c - N0_c) / k_c, one
               extra right-hand side per iteration (no S). Cables still short after the loop are 'unreach'
               (-> struts in resize_pass).
Every mode yields an exactly self-equilibrated N0 (any lack of fit does), so the util / taut checks of each mode are
physically valid on their own.
"""
import time
import numpy as np
import scipy.linalg as sla
from scipy.optimize import nnls as _nnls
from . import fem as F
from . import p2fem as PF
from . import p25fem as Q

PRE_MARGIN = Q.PRE_MARGIN
STATS = {}


def xgroups(P, bars, ci, tol=0.05):
    """Greedy pairing of crossing cables (interiors intersect, no shared node). Returns group id per cable index."""
    n = len(ci)
    gid = np.arange(n)
    if n < 2:
        return gid, 0
    A = P[bars[ci, 0]]; R = P[bars[ci, 1]] - A
    i, j = np.triu_indices(n, 1)
    sh = (bars[ci][i][:, :, None] == bars[ci][j][:, None, :]).any((1, 2))
    i, j = i[~sh], j[~sh]
    r, s = R[i], R[j]; w = A[j] - A[i]
    a = (r * r).sum(1); b = (r * s).sum(1); c = (s * s).sum(1); d = (w * r).sum(1); e = (w * s).sum(1)
    den = a * c - b * b
    ok = np.abs(den) > 1e-9 * a * c
    t = np.where(ok, (d * c - b * e) / np.where(ok, den, 1), -1)
    u = np.where(ok, (b * d - a * e) / np.where(ok, den, 1), -1)
    dist = np.linalg.norm(A[i] + t[:, None] * r - A[j] - u[:, None] * s, axis=1)
    m = ok & (t > 0.05) & (t < 0.95) & (u > 0.05) & (u < 0.95) & (dist < tol)
    order = np.argsort(dist[m])
    used = np.zeros(n, bool); npair = 0
    for a_, b_ in zip(i[m][order], j[m][order]):
        if used[a_] or used[b_]:
            continue
        used[a_] = used[b_] = True; gid[b_] = gid[a_]; npair += 1
    _, gid = np.unique(gid, return_inverse=True)
    return gid, npair


def analyze_v(prob, bars, st, mode='pinv', f=1.0, omega=1.5, raise_its=60, pre_iters=60):
    P = prob['P']; n = len(P)
    pr = Q.props(P, bars, st)
    fr = F.freedofs(prob, bars)
    Km, e, L, kk = Q.assemble(prob, bars, pr['area'])
    Kf = Km[np.ix_(fr, fr)]
    sc = np.sqrt(np.maximum(np.diag(Kf), 1e-30))
    fac = PF._factor(Kf / sc[:, None] / sc[None, :])
    if fac is None or np.min(np.abs(np.diag(fac[0]))) < 1e-6:
        return None
    solve = lambda rhs: sla.cho_solve(fac, rhs / sc[:, None]) / sc[:, None]
    ex = Q.loads(prob, bars, pr)
    u = np.zeros((3 * n, F.K)); u[fr] = solve(ex[:, fr].T)
    U = u.T.reshape(F.K, n, 3)
    dN = kk[None] * np.einsum('kmj,mj->km', U[:, bars[:, 1]] - U[:, bars[:, 0]], e)
    cab = pr['cab']
    ci = np.nonzero(cab)[0]
    need = np.where(cab, np.maximum(0, -dN.min(0)), 0)
    t = np.where(cab, np.maximum(st['floor'], PRE_MARGIN * need), 0.0)
    delta = np.zeros(len(bars))
    info = dict(mode=mode, n_free=len(fr), n_cab=len(ci), rhs=F.K, iters=0, n_groups=len(ci))

    def self_stress(dl):
        f0 = np.zeros((n, 3))
        fk = (kk * dl)[:, None] * e
        np.add.at(f0, bars[:, 0], fk); np.add.at(f0, bars[:, 1], -fk)
        u0 = np.zeros(3 * n); u0[fr] = solve(f0.ravel()[fr][:, None])[:, 0]
        U0 = u0.reshape(n, 3)
        return kk * (np.einsum('mj,mj->m', U0[bars[:, 1]] - U0[bars[:, 0]], e) + dl), U0

    if len(ci) and mode in ('pinv', 'xpair', 'nnls'):
        Fu = np.zeros((n, 3, len(ci)))
        ec = e[ci] * kk[ci][:, None]
        Fu[bars[ci, 0], :, np.arange(len(ci))] += ec; Fu[bars[ci, 1], :, np.arange(len(ci))] -= ec
        Fu = Fu.reshape(3 * n, len(ci))
        Uc = np.zeros((3 * n, len(ci))); Uc[fr] = solve(Fu[fr])
        Uc = Uc.reshape(n, 3, len(ci))
        Bu = np.einsum('mj,mjc->mc', e[ci], Uc[bars[ci, 1]] - Uc[bars[ci, 0]])
        Smat = kk[ci][:, None] * Bu + np.diag(kk[ci])
        info['rhs'] += len(ci)
        tau = t[ci].copy()
        if mode == 'pinv':
            Sp = np.linalg.pinv(Smat, rcond=1e-9)
            sol = lambda tau_: Sp @ tau_
        elif mode == 'xpair':
            gid, npair = xgroups(P, bars, ci)
            ng = gid.max() + 1
            Z = np.zeros((len(ci), ng)); Z[np.arange(len(ci)), gid] = 1.0 / kk[ci]
            Zi = (Z > 0).astype(float)
            Sg = Z.T @ Smat @ Z                       # symmetric PSD (S = D - D G^T K^-1 G D)
            Sg = 0.5 * (Sg + Sg.T)
            reg = 1e-9 * np.abs(np.diag(Sg)).max()
            cf = sla.cho_factor(Sg + reg * np.eye(ng))
            info['n_groups'] = int(ng); info['n_pairs'] = int(npair)
            sol = lambda tau_: Z @ sla.cho_solve(cf, Z.T @ tau_)
        else:
            sol = lambda tau_: _nnls(Smat, tau_, maxiter=50 * len(ci))[0]
        for it in range(pre_iters):
            delta[ci] = sol(tau)
            def_ = t[ci] - Smat @ delta[ci]
            info['iters'] = it + 1
            if def_.max() <= 1e-4 * max(1.0, t[ci].max()):
                break
            tau += np.maximum(def_, 0)
        N0, U0 = self_stress(delta); info['rhs'] += 1
    elif len(ci):                          # 'fixf'
        delta[ci] = f * st['floor'][ci] / kk[ci]
        N0, U0 = self_stress(delta); info['rhs'] += 1
        tgt = np.where(cab, PRE_MARGIN * need, 0.0)
        for it in range(raise_its):
            def_ = tgt[ci] - N0[ci]
            # converged when every cable is within 40% of its 5% margin (N0 >= 1.03 need): taut with margin
            if (def_ <= 0.4 * (PRE_MARGIN - 1) * need[ci] + 1e-9).all():
                break
            delta[ci] += omega * np.maximum(def_, 0) / kk[ci]
            N0, U0 = self_stress(delta); info['rhs'] += 1; info['iters'] = it + 1
        t = np.where(cab, np.maximum(tgt, 0.0), 0.0)
    else:
        N0, U0 = np.zeros(len(bars)), np.zeros((n, 3))
    fi = np.zeros((n, 3))
    np.add.at(fi, bars[:, 0], N0[:, None] * e); np.add.at(fi, bars[:, 1], -N0[:, None] * e)
    Kp = F.pole_spring(P, prob['pole'], prob['pw'], n)
    res = (fi.ravel() - Kp @ U0.ravel())[fr]
    eq_err = float(np.abs(res).max() / max(1e-9, np.abs(N0).max()))
    Nk = N0[None] + dN
    Nt = np.maximum(0, Nk.max(0)); Nc = np.maximum(0, -Nk.min(0))
    util = np.where(cab, Nt / np.maximum(pr['capT'], 1e-12),
                    np.maximum(Nt / pr['capT'], Nc / np.maximum(pr['capC'], 1e-12)))
    unreach = np.zeros(len(bars), bool)
    if mode == 'fixf':      # not taut with at least 1% margin over the load-induced slackening -> strut
        unreach[ci] = N0[ci] < 1.01 * need[ci] - 1e-9
    else:
        unreach[ci] = N0[ci] < 0.98 * t[ci] - 1e-3
    t = np.where(cab, np.maximum(t, N0), 0.0)
    slack_margin = np.where(cab, (N0 + dN.min(0)) / np.maximum(pr['capT'], 1e-12), np.inf)
    Ut = U0[None] + U
    pk = None
    for k in range(F.K):
        v = F.pole_kinks(prob, Ut[k])
        pk = v if pk is None else np.maximum(pk, v)
    for k_, v_ in info.items():
        if isinstance(v_, (int, np.integer)) and k_ != 'mode':
            STATS.setdefault(k_, []).append(int(v_))
    return dict(pr=pr, dN=dN, N0=N0, Nk=Nk, Nt=Nt, Nc=Nc, util=util, t=t, need=need, U=U, U0=U0, pk=pk,
                disp=float(np.max(np.linalg.norm(Ut, axis=2))), disp_load=float(np.max(np.linalg.norm(U, axis=2))),
                eq_err=eq_err, unreach=unreach, pre_iters=info['iters'], pre_unreached=int(unreach.sum()),
                slack_margin=slack_margin, max_util=float(util.max()) if len(util) else 0.0,
                pole_util=float(pk.max()) if pk is not None and len(pk) else 0.0,
                pre_ratio=0.0, min_slack=float(slack_margin[cab].min()) if cab.any() else np.inf, info=info)


def widen_v(prob, bars, st, mode='pinv', target=1.0, its=30, **kw):
    """widen25 with analyze_v. A mechanism is reported (fem.stabilise is not part of the Java port)."""
    st = Q.st_copy(st)
    t0 = time.perf_counter()
    nb0 = len(bars); k0, ws0, cs0 = st['kind'].copy(), st['ws'].copy(), st['cs'].copy()
    conv = nbump = mech = 0
    an = None; t_an = 0.0; per_round = []
    for it in range(its):
        t1 = time.perf_counter()
        an = analyze_v(prob, bars, st, mode, **kw)
        t_an += time.perf_counter() - t1
        if an is None:
            mech += 1
            sec = st['ws'].copy(); cab = st['kind'] % 2 == 1; n0 = len(bars)
            bars, cab2, sec2, _, a2 = F.stabilise(prob, bars, cab.copy(), sec, np.zeros(len(bars), int), lambda *a: None)
            st = Q.st_extend(st, len(bars) - n0)
            continue
        per_round.append(dict(an['info'], t=time.perf_counter() - t1))
        nch, cv = Q.resize_pass(prob, bars, st, an, target)
        conv += cv; nbump += nch
        if not nch:
            break
    changed = (st['kind'][:nb0] != k0) | (st['ws'][:nb0] != ws0) | (st['cs'][:nb0] != cs0)
    info = dict(mode=mode, rounds=it + 1, converted=conv, mechanisms=mech, widened=int(changed.sum()), bumps=nbump,
                t_widen=time.perf_counter() - t0, t_analyze=t_an, per_round=per_round)
    return bars, st, an, info
