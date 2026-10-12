"""P2.5 FEM: pretensioned design / check, laced built-up members, back-stay cables.

Member state (per member arrays in a dict `st`):
  kind: 0 solid strut, 1 cable, 2 laced built-up strut, 3 back-stay cable (wide wire-rope ladder, never converted)
  ws:   section index (kind 0/2: outer width on WEXT; kind 1: CAB_W; kind 3: BS_W)
  cs:   chord width index on WEXT (kind 2 only)
  pole: member is a pole segment (always solid; pole bending via prob['pw'])
  floor: pretension floor (recorded pretension / back-stay design tension); 0 for struts

Laced member model (kind 2), outer size b, nc chords (3 if b <= 0.7 else 4) of width cw:
  area A = nc cw^2 (axial stiffness E A / L, tension cap SIG_T A);
  compression cap A * SIG_C0 / (1 + lam_c^2 + lam_o^2) with chord panel buckling lam_c = Lp R_REF / (L0 r_c)
  (r_c = cw / sqrt 12, lacing panel Lp = clamp(min(b, 8 cw), 0.25, L/2)) and overall buckling
  lam_o = 1.1 L R_REF / (L0 r_o) (r_o = b/2 for 4 chords, b/sqrt 6 for 3; 1.1 for lacing shear flexibility);
  R_REF = 0.1 maps the solid formula sig_c(L) = SIG_C0 / (1 + (L/L0)^2) onto a radius of gyration (it is the
  formula for r = 0.1; solids keep sig_c(L) regardless of size, as in XBRACE). Self-weight 1.25 A (lacing), drag
  width min(b, 2.5 cw). Capacity is matched to demand, stiffness is not (A is 0.3-0.6 of b^2).

Pretension: lack-of-fit delta_c on every cable, solved by fixed-point iteration delta += (t - N0_c) / k_c on the
taut stiffness factorisation, so that the self-stress N0 reaches the cable targets t. N0 includes the strut and
pole forces the pretension causes. Per case N = N0 + dN_k (linear, all cables active).
"""
import time
import numpy as np
import scipy.linalg as sla
from . import fem as F
from . import p2fem as PF

WEXT = np.round(0.05 * 1.2 ** np.arange(24), 3)        # 0.05 .. 2.76 (laced outer sizes go past 1.92)
assert np.allclose(WEXT[:21], F.WIDTHS)
BS_W = np.array([0.09, 0.12, 0.16, 0.2, 0.25, 0.3, 0.36, 0.43])
LACE_W = 0.40              # solid struts wider than this are drawn / modelled as laced
I_SOLID_MAX = int(np.searchsorted(WEXT, LACE_W)) - 1   # 0.371
R_REF, CH_MAX = 0.1, 0.25  # chord width <= CH_MAX * b (open look; b grows instead of the chords)
PRE_MARGIN = 1.05
TAUT_PRE_MAX = PF.TAUT_PRE_MAX
DIRECT_PRE = True


# ---------------------------------------------------------------- sections
def nchords(b):
    return np.where(b <= 0.7 + 1e-9, 3, 4)


def lacing_panel(b, cw, L):
    return np.clip(np.minimum(b, 8 * cw), 0.25, np.maximum(L / 2, 0.25))


def sig_laced(L, b, cw):
    nc = nchords(b)
    rc = cw / np.sqrt(12.0)
    ro = np.where(nc == 4, b / 2, b / np.sqrt(6.0))
    lc = lacing_panel(b, cw, L) * R_REF / (F.L0 * rc)
    lo = 1.1 * L * R_REF / (F.L0 * ro)
    return F.SIG_C0 / (1 + lc ** 2 + lo ** 2)


def props(P, bars, st):
    """-> dict(width (drawn outer), chord, area, wa (weight area), dw (drag width), capT, capC, L)."""
    _, L = F.geometry(P, bars)
    k, ws, cs = st['kind'], st['ws'], st['cs']
    w = np.where(k == 1, F.CAB_W[np.clip(ws, 0, len(F.CAB_W) - 1)],
                 np.where(k == 3, BS_W[np.clip(ws, 0, len(BS_W) - 1)], WEXT[np.clip(ws, 0, len(WEXT) - 1)]))
    cw = np.where(k == 2, WEXT[np.clip(cs, 0, len(WEXT) - 1)], w)
    nc = nchords(w)
    area = np.where(k == 2, nc * cw ** 2, w ** 2)
    wa = np.where(k == 2, 1.25 * area, area)
    dw = np.where(k == 2, np.minimum(w, 2.5 * cw), w)
    cabl = (k == 1) | (k == 3)
    capT = np.where(cabl, F.SIG_CAB, F.SIG_T) * area
    capC = np.where(cabl, 0.0, np.where(k == 2, sig_laced(L, w, cw), F.sig_c(L)) * area)
    return dict(width=w, chord=cw, area=area, wa=wa, dw=dw, capT=capT, capC=capC, L=L, cab=cabl)


def new_state(bars, cab, sec, prob, floor=None):
    m = len(bars)
    pole = prob['pole']
    pset = {tuple(sorted((pole[i], pole[i + 1]))) for i in range(len(pole) - 1)}
    isp = np.array([tuple(sorted(b)) in pset and not c for b, c in zip(bars.tolist(), cab)], bool) if m else np.zeros(0, bool)
    return dict(kind=np.where(cab, 1, 0).astype(int), ws=np.asarray(sec, int).copy(), cs=np.zeros(m, int), pole=isp,
                floor=np.zeros(m) if floor is None else np.asarray(floor, float).copy())


def st_copy(st):
    return {k: v.copy() for k, v in st.items()}


def st_take(st, keep):
    return {k: v[keep] for k, v in st.items()}


def st_cat(a, b):
    return {k: np.concatenate([a[k], b[k]]) for k in a}


def st_extend(st, n, kind=0, ws=0):
    return st_cat(st, dict(kind=np.full(n, kind, int), ws=np.full(n, ws, int), cs=np.zeros(n, int),
                           pole=np.zeros(n, bool), floor=np.zeros(n)))


def size_member(st, i, Nt, Nc, L, allow_shrink=False):
    """Make member i carry tension Nt and compression Nc (>= 0) with margin; returns True if changed."""
    k0, w0, c0 = int(st['kind'][i]), int(st['ws'][i]), int(st['cs'][i])
    Nt, Nc = Nt * 1.04, Nc * 1.04
    if k0 == 1:
        need = Nt / F.SIG_CAB
        j = int(np.searchsorted(F.CAB_A, need - 1e-12))
        if j < len(F.CAB_A):
            st['ws'][i] = max(j, w0) if not allow_shrink else j
        else:                                   # cable too weak: strut
            st['kind'][i] = 0; st['ws'][i] = 0; st['floor'][i] = 0
            return size_member(st, i, Nt / 1.04, Nc / 1.04, L) or True
    elif k0 == 3:
        need = Nt / F.SIG_CAB
        j = min(int(np.searchsorted(BS_W ** 2, need - 1e-12)), len(BS_W) - 1)
        st['ws'][i] = max(j, w0)
    elif k0 == 0:
        sc = float(F.sig_c(np.array([L]))[0])
        need = max(Nt / F.SIG_T, Nc / sc)
        lim = len(F.WIDTHS) - 1 if st['pole'][i] else I_SOLID_MAX
        j = int(np.searchsorted(WEXT ** 2, need - 1e-12))
        j = j if allow_shrink else max(j, w0)
        if j <= lim:
            st['ws'][i] = j
        elif st['pole'][i]:
            st['ws'][i] = lim
        else:                                   # laced, outer size = the required solid section
            st['kind'][i] = 2; st['ws'][i] = min(j, len(WEXT) - 1); st['cs'][i] = 0
            _size_laced(st, i, Nt, Nc, L)
    else:
        _size_laced(st, i, Nt, Nc, L, keep=not allow_shrink)
    return (int(st['kind'][i]), int(st['ws'][i]), int(st['cs'][i])) != (k0, w0, c0)


def _size_laced(st, i, Nt, Nc, L, keep=True):
    c0 = int(st['cs'][i]) if keep else 0
    while True:
        b = WEXT[st['ws'][i]]
        nc = int(nchords(np.array(b)))
        ok = None
        for c in range(c0, len(WEXT)):
            cw = WEXT[c]
            if cw > CH_MAX * b + 1e-9:
                break
            A = nc * cw ** 2
            if A * F.SIG_T >= Nt and A * float(sig_laced(np.array(L), np.array(b), np.array(cw))) >= Nc:
                ok = c; break
        if ok is not None:
            st['cs'][i] = ok; return
        if st['ws'][i] >= len(WEXT) - 1:
            st['cs'][i] = max(c0, int(np.searchsorted(WEXT, CH_MAX * b)) - 1); return
        st['ws'][i] += 1


def tie_state(P, bars, st):
    """4-fold symmetric closure (fem.tie_secs for the member state)."""
    rm = F.rot_map(P, bars, st['kind'] % 2 == 1)
    for _ in range(4):
        for i in range(len(bars)):
            j = rm[i]
            if j < 0:
                continue
            for k in ('kind', 'ws', 'cs'):
                v = max(st[k][i], st[k][j]); st[k][i] = st[k][j] = v
    return st


# ---------------------------------------------------------------- linear taut model with pretension
def assemble(prob, bars, area):
    P = prob['P']; n = len(P)
    d, L = F.geometry(P, bars)
    e = d / L[:, None]
    Km = F.pole_spring(P, prob['pole'], prob['pw'], n)
    kk = F.E * area / L
    blk = kk[:, None, None] * e[:, :, None] * e[:, None, :]
    ii = 3 * bars[:, 0][:, None] + np.arange(3); jj = 3 * bars[:, 1][:, None] + np.arange(3)
    for a, b, s in ((ii, ii, 1), (jj, jj, 1), (ii, jj, -1), (jj, ii, -1)):
        np.add.at(Km, (a[:, :, None], b[:, None, :]), s * blk)
    return Km, e, L, kk


def loads(prob, bars, pr):
    P = prob['P']; n = len(P)
    d, L = F.geometry(P, bars)
    out = prob['ext'].reshape(F.K, n, 3).copy()
    wgt = F.RHO * L * pr['wa'] / 2
    for k, cs in enumerate(F.CASES):
        np.add.at(out[k][:, 1], bars[:, 0], -wgt); np.add.at(out[k][:, 1], bars[:, 1], -wgt)
        if cs[1] is not None:
            drag = F.CD0 * np.linalg.norm(np.cross(d, cs[1]), axis=1) * pr['dw'] / 2
            np.add.at(out[k], bars[:, 0], drag[:, None] * cs[1]); np.add.at(out[k], bars[:, 1], drag[:, None] * cs[1])
    return out.reshape(F.K, -1)


def analyze(prob, bars, st, pre_targets=None, pre_iters=60):
    """Taut linear analysis with pretension. Cable targets t = max(floor, PRE_MARGIN * need) where
    need = max(0, -min_k dN). Returns dict or None if the taut structure is a mechanism."""
    P = prob['P']; n = len(P)
    pr = props(P, bars, st)
    fr = F.freedofs(prob, bars)
    Km, e, L, kk = assemble(prob, bars, pr['area'])
    Kf = Km[np.ix_(fr, fr)]
    sc = np.sqrt(np.maximum(np.diag(Kf), 1e-30))
    fac = PF._factor(Kf / sc[:, None] / sc[None, :])
    if fac is None or np.min(np.abs(np.diag(fac[0]))) < 1e-6:
        return None
    solve = lambda f: sla.cho_solve(fac, f / sc[:, None]) / sc[:, None]
    ex = loads(prob, bars, pr)
    u = np.zeros((3 * n, F.K)); u[fr] = solve(ex[:, fr].T)
    U = u.T.reshape(F.K, n, 3)
    dN = kk[None] * np.einsum('kmj,mj->km', U[:, bars[:, 1]] - U[:, bars[:, 0]], e)
    cab = pr['cab']
    need = np.where(cab, np.maximum(0, -dN.min(0)), 0)
    t = np.where(cab, np.maximum(st['floor'], PRE_MARGIN * need), 0.0) if pre_targets is None else pre_targets
    # lack-of-fit: direct solve of the cable influence matrix S (N0_c = S delta), then fixed-point polish
    delta = np.where(cab, t / kk, 0.0)
    ci = np.nonzero(cab)[0]
    N0 = np.zeros(len(bars)); U0 = np.zeros((n, 3)); it = 0
    if len(ci) and DIRECT_PRE:
        Fu = np.zeros((n, 3, len(ci)))
        ec = e[ci] * kk[ci][:, None]
        Fu[bars[ci, 0], :, np.arange(len(ci))] += ec; Fu[bars[ci, 1], :, np.arange(len(ci))] -= ec
        Fu = Fu.reshape(3 * n, len(ci))
        Uc = np.zeros((3 * n, len(ci))); Uc[fr] = solve(Fu[fr])
        Uc = Uc.reshape(n, 3, len(ci))
        Bu = np.einsum('cj,jc->c' if False else 'mj,mjc->mc', e[ci], Uc[bars[ci, 1]] - Uc[bars[ci, 0]])
        Smat = kk[ci][:, None] * Bu + np.diag(kk[ci])
        # S is singular where cables can only be tensioned together (an X on a frame that is otherwise stiff:
        # both diagonals take the same self-stress). Use the pseudo-inverse and raise the targets of cables left
        # below target until every cable reaches at least its target (N0 >= t).
        Sp = np.linalg.pinv(Smat, rcond=1e-9)
        tau = t[ci].copy()
        for it in range(pre_iters):
            delta[ci] = Sp @ tau
            def_ = t[ci] - Smat @ delta[ci]
            if def_.max() <= 1e-4 * max(1.0, t[ci].max()):
                break
            tau += np.maximum(def_, 0)
        pre_iters = 1
    for it2 in range(pre_iters):
        f0 = np.zeros((n, 3))
        fk = (kk * delta)[:, None] * e
        np.add.at(f0, bars[:, 0], fk); np.add.at(f0, bars[:, 1], -fk)
        u0 = np.zeros(3 * n); u0[fr] = solve(f0.ravel()[fr][:, None])[:, 0]
        U0 = u0.reshape(n, 3)
        N0 = kk * (np.einsum('mj,mj->m', U0[bars[:, 1]] - U0[bars[:, 0]], e) + delta)
        err = np.abs(t[ci] - N0[ci])
        if not len(ci) or err.max() <= 1e-4 * max(1.0, t[ci].max()):
            break
        delta[ci] += (t[ci] - N0[ci]) / kk[ci]
    # equilibrium of the self-stress at free DOFs (pole spring included)
    fi = np.zeros((n, 3))
    np.add.at(fi, bars[:, 0], N0[:, None] * e); np.add.at(fi, bars[:, 1], -N0[:, None] * e)
    Kp = F.pole_spring(P, prob['pole'], prob['pw'], n)
    res = (fi.ravel() - Kp @ U0.ravel())[fr]
    eq_err = float(np.abs(res).max() / max(1e-9, np.abs(N0).max()))
    Nk = N0[None] + dN
    Nt = np.maximum(0, Nk.max(0)); Nc = np.maximum(0, -Nk.min(0))
    util = np.where(cab, Nt / np.maximum(pr['capT'], 1e-12),
                    np.maximum(Nt / pr['capT'], Nc / np.maximum(pr['capC'], 1e-12)))
    unreach = np.zeros(len(bars), bool); unreach[ci] = N0[ci] < 0.98 * t[ci] - 1e-3
    pre_unreached = int(unreach.sum())
    t = np.where(cab, np.maximum(t, N0), 0.0)        # actual pretension (>= target where the self-stress forces it)
    slack_margin = np.where(cab, (N0 + dN.min(0)) / np.maximum(pr['capT'], 1e-12), np.inf)
    Ut = U0[None] + U
    pk = None
    for k in range(F.K):
        v = F.pole_kinks(prob, Ut[k])
        pk = v if pk is None else np.maximum(pk, v)
    disp = float(np.max(np.linalg.norm(Ut, axis=2)))
    disp_load = float(np.max(np.linalg.norm(U, axis=2)))
    return dict(pr=pr, dN=dN, N0=N0, Nk=Nk, Nt=Nt, Nc=Nc, util=util, t=t, need=need, U=U, U0=U0, pk=pk,
                disp=disp, disp_load=disp_load, eq_err=eq_err, unreach=unreach, pre_iters=it + 1, pre_unreached=pre_unreached,
                slack_margin=slack_margin, max_util=float(util.max()) if len(util) else 0.0,
                pole_util=float(pk.max()) if pk is not None and len(pk) else 0.0,
                pre_ratio=float((t / np.maximum(pr['capT'], 1e-12))[(st['kind'] == 1)].max()) if (st['kind'] == 1).any() else 0.0,
                min_slack=float(slack_margin[cab].min()) if cab.any() else np.inf)


def resize_pass(prob, bars, st, an, target=1.0, allow_shrink=False, conv_pre=True):
    """One sizing step from an analysis. Returns (#changed, #cables->struts)."""
    nch, conv = 0, 0
    pr = an['pr']
    if conv_pre:
        r = np.where(st['kind'] == 1, an['t'] / np.maximum(pr['capT'], 1e-12), 0)
        # pretension above TAUT_PRE_MAX of the widest cable's capacity -> strut
        capmax = F.SIG_CAB * F.CAB_A[-1]
        over = np.nonzero((st['kind'] == 1) & ((an['t'] > TAUT_PRE_MAX * capmax) | an['unreach']))[0]   # or not pretensionable
        for i in over:
            st['kind'][i] = 0; st['ws'][i] = 0; st['floor'][i] = 0; conv += 1
            size_member(st, i, an['Nt'][i], an['Nc'][i], pr['L'][i]); nch += 1
        # cables whose pretension exceeds TAUT_PRE_MAX of their own capacity: wider cable
        for i in np.nonzero((r > TAUT_PRE_MAX) & (st['kind'] == 1))[0]:
            if i in over:
                continue
            nch += size_member(st, i, max(an['Nt'][i], an['t'][i] / TAUT_PRE_MAX), 0, pr['L'][i])
    sel = np.nonzero(an['util'] > target)[0] if not allow_shrink else np.arange(len(bars))
    for i in sel:
        if allow_shrink and st['kind'][i] != 2:
            continue
        nch += size_member(st, i, an['Nt'][i], an['Nc'][i], pr['L'][i], allow_shrink=allow_shrink)
    if an['pk'] is not None and len(an['pk']) and an['pk'].max() > target:
        sec = st['ws'].copy()
        PF.bump_pole(prob, bars, st['kind'] % 2 == 1, sec, an['pk'], target)
        ch = np.nonzero(sec != st['ws'])[0]
        st['ws'][ch] = sec[ch]; nch += len(ch) + 1
    return nch, conv


def lace(st, an):
    """Convert solid non-pole struts wider than LACE_W to laced members sized for their current demand."""
    pr = an['pr']; n = 0
    for i in np.nonzero((st['kind'] == 0) & ~st['pole'] & (pr['width'] > LACE_W))[0]:
        st['kind'][i] = 2; st['cs'][i] = 0
        _size_laced(st, i, an['Nt'][i] * 1.04, an['Nc'][i] * 1.04, pr['L'][i], keep=False)
        n += 1
    return n


def repair25(prob, bars, st, closure, log, its=16, target=1.0):
    nb = 0; an = None
    for it in range(its):
        an = analyze(prob, bars, st)
        if an is None:
            log('  repair25: mechanism'); break
        nch, conv = resize_pass(prob, bars, st, an, target)
        if closure and nch:
            tie_state(prob['P'], bars, st)
        nb += nch
        if not nch:
            break
    return st, nb, an


def design25(prob, closure=False, log=print):
    """XBRACE LP + plastic repair (solid) -> taut stabilise -> laced conversion -> pretensioned elastic repair."""
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
    st = new_state(bars, cab, sec, prob)
    an = analyze(prob, bars, st)
    nl = lace(st, an) if an is not None else 0
    if closure:
        tie_state(prob['P'], bars, st)
    st, nb, an = repair25(prob, bars, st, closure, log)
    # laced chords may shrink once the forces have settled (capacity matched to demand)
    if an is not None and (st['kind'] == 2).any():
        resize_pass(prob, bars, st, an, allow_shrink=True, conv_pre=False)
        if closure:
            tie_state(prob['P'], bars, st)
        st, nb2, an = repair25(prob, bars, st, closure, log); nb += nb2
    an = analyze(prob, bars, st)
    pr = an['pr']
    pre = np.where(st['kind'] == 1, an['t'], 0.0)
    return dict(bars=bars, cab=st['kind'] % 2 == 1, st=st, width=pr['width'], chord=pr['chord'], area=pr['area'],
                pre=pre, gen_time=time.time() - t0, added=added, bumps=nb, laced=int((st['kind'] == 2).sum()),
                pw=prob['pw'].copy(), taut_util=an['max_util'], taut_disp=an['disp'], pre_ratio=an['pre_ratio'],
                pole_util=an['pole_util'], eq_err=an['eq_err'], an=an)


def reactions25(prob, bars, st, sup):
    """Per case loads the structure puts on its supports (load model only, no pretension). (K, len(sup), 3)"""
    P = prob['P']; n = len(P)
    an = analyze(prob, bars, st, pre_targets=np.zeros(len(bars)))
    pr = an['pr']
    d, L = F.geometry(P, bars); e = d / L[:, None]
    ex = loads(prob, bars, pr).reshape(F.K, n, 3)
    Kp = F.pole_spring(P, prob['pole'], prob['pw'], n)
    out = np.zeros((F.K, len(sup), 3))
    for k in range(F.K):
        fn = np.zeros((n, 3))
        np.add.at(fn, bars[:, 0], an['dN'][k][:, None] * e); np.add.at(fn, bars[:, 1], -an['dN'][k][:, None] * e)
        fp = (Kp @ an['U'][k].ravel()).reshape(-1, 3)
        for j, s in enumerate(sup):
            out[k, j] = ex[k, s] + fn[s] - fp[s]
    return out


# ---------------------------------------------------------------- strict diagnostic (no pretension)
def strict_diag(prob, bars, st):
    P = prob['P']; n = len(P)
    pr = props(P, bars, st)
    fr = F.freedofs(prob, bars)
    d, L = F.geometry(P, bars); e = d / L[:, None]
    ex = loads(prob, bars, pr)
    cab = pr['cab']
    u_all = np.zeros(len(bars)); npd = 0; failed = []; disp = 0.0
    for k, cs in enumerate(F.CASES):
        active = np.ones(len(bars), bool); seen = set(); ok = False
        for it in range(25):
            Km, _, _, kk = assemble(prob, bars, pr['area'] * active)
            Kf = Km[np.ix_(fr, fr)]
            sc = np.sqrt(np.maximum(np.diag(Kf), 1e-30))
            fac = PF._factor(Kf / sc[:, None] / sc[None, :])
            if fac is None or np.min(np.abs(np.diag(fac[0]))) < 1e-6:
                ok = False; break
            u = np.zeros(3 * n); u[fr] = sla.cho_solve(fac, ex[k][fr] / sc) / sc
            U = u.reshape(-1, 3)
            strain = np.einsum('ij,ij->i', U[bars[:, 1]] - U[bars[:, 0]], e)
            N = F.E * pr['area'] * strain / L * active
            ok = True
            new = ~cab | (strain > 0)
            if (new == active).all():
                break
            kb = new.tobytes()
            if kb in seen:
                break
            seen.add(kb); active = new
        if ok:
            npd += 1; disp = max(disp, float(np.max(np.linalg.norm(U, axis=1))))
            u_all = np.maximum(u_all, np.where(N > 0, N / pr['capT'], -N / np.where(cab, np.inf, pr['capC'])))
        else:
            failed.append(cs[0])
    return dict(n_pd=npd, failed=failed, max_util=float(u_all.max()) if npd else None, disp=disp)


# ---------------------------------------------------------------- runtime pass on the stitched tower
def widen25(prob, bars, st, log=print, target=1.0, its=30):
    """Pretensioned runtime pass. Cable targets: recorded pretension as the floor, raised where the stitched tower
    needs more to stay taut; cables needing > TAUT_PRE_MAX of the widest cable's capacity become struts; members
    over target are resized up (never down); pole nodes bumped. Mechanism -> fem.stabilise (adds struts)."""
    st = st_copy(st)
    t0 = time.perf_counter()
    ws0, k0, cs0 = st['ws'].copy(), st['kind'].copy(), st['cs'].copy()
    pw0 = prob['pw'].copy(); nb0 = len(bars)
    conv = added = nbump = 0
    it = 0; an = None; t_an = 0.0
    for it in range(its):
        t1 = time.perf_counter()
        an = analyze(prob, bars, st)
        t_an += time.perf_counter() - t1
        if an is None:
            sec = st['ws'].copy(); cab = st['kind'] % 2 == 1; n0 = len(bars)
            bars, cab2, sec2, _, a2 = F.stabilise(prob, bars, cab.copy(), sec, np.zeros(len(bars), int), lambda *a: None)
            st = st_extend(st, len(bars) - n0); added += len(bars) - n0
            continue
        nch, cv = resize_pass(prob, bars, st, an, target)
        conv += cv; nbump += nch
        if not nch:
            break
    pr = an['pr']
    w0 = props(prob['P'], bars[:nb0], dict(kind=k0, ws=ws0, cs=cs0, pole=st['pole'][:nb0], floor=st['floor'][:nb0]))
    changed = (st['kind'][:nb0] != k0) | (st['ws'][:nb0] != ws0) | (st['cs'][:nb0] != cs0)
    vol_add = float(np.sum(pr['L'] * pr['area'])) - float(np.sum(w0['L'] * w0['area']))
    raised = int(np.sum((st['kind'] % 2 == 1) & (an['t'] > st['floor'] * 1.001 + 1e-6)))
    info = dict(rounds=it + 1, converted=conv, added=added, widened=int(changed.sum()), bumps=nbump,
                pole_nodes_widened=int((prob['pw'] > pw0 + 1e-9).sum()), vol_added=vol_add,
                pre_raised=raised, t_widen=time.perf_counter() - t0, t_analyze=t_an)
    return bars, st, an, info


def reactions_pre(prob, bars, st, an, sup):
    """Self-stress (pretension) loads the structure puts on its supports, rigid base. (len(sup), 3); the set is
    self-equilibrated. The bay below adds them to its top loads (the shared ring sees both bays' pretension)."""
    P = prob['P']; n = len(P)
    d, L = F.geometry(P, bars); e = d / L[:, None]
    fn = np.zeros((n, 3))
    np.add.at(fn, bars[:, 0], an['N0'][:, None] * e); np.add.at(fn, bars[:, 1], -an['N0'][:, None] * e)
    fp = (F.pole_spring(P, prob['pole'], prob['pw'], n) @ an['U0'].ravel()).reshape(-1, 3)
    return np.array([fn[s] - fp[s] for s in sup])
