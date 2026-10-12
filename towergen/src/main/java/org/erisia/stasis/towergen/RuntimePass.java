package org.erisia.stasis.towergen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pretensioned widening pass, the Java spec in NOTES-P3a section 5 ("fixf": recorded pretension x 1.5, then a
 * raise-only loop; one dense Cholesky per round). Port of p3afem.analyze_v / widen_v (mode fixf), p25fem.resize_pass /
 * size_member / _size_laced, p2fem.bump_pole and p25lib.thick_above_thin (leg fix).
 */
public final class RuntimePass {
    public static final class MechanismException extends RuntimeException {
        public MechanismException(String m) { super(m); }
    }

    /** Result of one analysis. */
    public static final class Analysis {
        public double[][] dN;          // [K][m]
        public double[] N0, Nt, Nc, util, t, need, capT, capC, L, width;
        public boolean[] unreach, cab;
        public double[] pk;            // pole kink util per interior pole node
        public double maxUtil, minSlack = Double.POSITIVE_INFINITY, poleUtil;
        public double[] utilPerCase;   // K
        public int raiseIters, nFree, nCab;
    }

    public static final class Info {
        public int rounds, converted, bumps, legFix;
        public double rawUtil;
        public double volRaw, vol;
        public long tPassNs;
        public boolean pass;
    }

    private final Consts c;
    private final Sections sec;
    public int maxRounds = 30, raiseIts = 60;

    public RuntimePass(Consts c) { this.c = c; this.sec = new Sections(c); }

    // ------------------------------------------------------------------ analysis (analyze_v, mode fixf)
    public Analysis analyze(Stitch.Tower t) {
        int n = t.n, m = t.m, K = c.K;
        double[] P = t.P;
        // free DOFs: used (member end or pole) and not fixed
        boolean[] used = new boolean[n];
        for (int b = 0; b < m; b++) { used[t.bi[b]] = true; used[t.bj[b]] = true; }
        for (int p : t.poleChain) used[p] = true;
        int[] dof = new int[n];
        int nf = 0;
        for (int i = 0; i < n; i++) dof[i] = (used[i] && !t.fixed[i]) ? 3 * (nf++) : -1;
        nf *= 3;
        Analysis an = new Analysis();
        an.nFree = nf;
        double[] L = new double[m], kk = new double[m], ex = new double[3 * m], area = new double[m];
        an.capT = new double[m]; an.capC = new double[m]; an.cab = new boolean[m]; an.width = new double[m];
        double[] wa = new double[m], dw = new double[m];
        for (int b = 0; b < m; b++) {
            int i = t.bi[b], j = t.bj[b];
            double dx = P[3 * j] - P[3 * i], dy = P[3 * j + 1] - P[3 * i + 1], dz = P[3 * j + 2] - P[3 * i + 2];
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            L[b] = l; ex[3 * b] = dx / l; ex[3 * b + 1] = dy / l; ex[3 * b + 2] = dz / l;
            int k = t.kind[b], w = t.ws[b], cs = t.cs[b];
            area[b] = sec.area(k, w, cs);
            wa[b] = sec.weightArea(k, w, cs);
            dw[b] = sec.dragWidth(k, w, cs);
            an.capT[b] = sec.capT(k, w, cs);
            an.capC[b] = sec.capC(k, w, cs, l);
            an.cab[b] = Sections.isCable(k);
            an.width[b] = sec.width(k, w);
            kk[b] = c.E * area[b] / l;
        }
        an.L = L;
        // stiffness
        double[] Kf = new double[nf * nf];
        for (int b = 0; b < m; b++) {
            int di = dof[t.bi[b]], dj = dof[t.bj[b]];
            double k = kk[b];
            for (int a = 0; a < 3; a++) {
                for (int q = 0; q < 3; q++) {
                    double v = k * ex[3 * b + a] * ex[3 * b + q];
                    if (di >= 0) Kf[(di + a) * nf + di + q] += v;
                    if (dj >= 0) Kf[(dj + a) * nf + dj + q] += v;
                    if (di >= 0 && dj >= 0) { Kf[(di + a) * nf + dj + q] -= v; Kf[(dj + a) * nf + di + q] -= v; }
                }
            }
        }
        int[] pc = t.poleChain;
        double[] kbArr = new double[Math.max(0, pc.length)], spanArr = new double[pc.length];
        double[][] axArr = new double[pc.length][];
        for (int k = 1; k + 1 < pc.length; k++) {
            int i = pc[k - 1], j = pc[k], l = pc[k + 1];
            double[] d = {P[3 * l] - P[3 * i], P[3 * l + 1] - P[3 * i + 1], P[3 * l + 2] - P[3 * i + 2]};
            double span = V.norm(d);
            double[] ax = V.mul(d, 1 / span);
            double w = t.pw[k];
            double kb = 48 * c.E * (w * w * w * w / 12) / (span * span * span);
            kbArr[k] = kb; spanArr[k] = span; axArr[k] = ax;
            int[] nd = {i, j, l};
            double[] cf = {-0.5, 1.0, -0.5};
            for (int a = 0; a < 3; a++) {
                int da = dof[nd[a]];
                if (da < 0) continue;
                for (int bb = 0; bb < 3; bb++) {
                    int db = dof[nd[bb]];
                    if (db < 0) continue;
                    double s = kb * cf[a] * cf[bb];
                    for (int r = 0; r < 3; r++)
                        for (int q = 0; q < 3; q++)
                            Kf[(da + r) * nf + db + q] += s * ((r == q ? 1 : 0) - ax[r] * ax[q]);
                }
            }
        }
        // scaling and factorisation (p2fem._factor + the pivot check)
        double[] sc = new double[nf];
        double dmax = 0, dmin = Double.POSITIVE_INFINITY;
        for (int i = 0; i < nf; i++) sc[i] = Math.sqrt(Math.max(Kf[i * nf + i], 1e-30));
        for (int i = 0; i < nf; i++) {
            double si = sc[i];
            int ri = i * nf;
            for (int j = 0; j < nf; j++) Kf[ri + j] /= si * sc[j];
            dmax = Math.max(dmax, Kf[ri + i]); dmin = Math.min(dmin, Kf[ri + i]);
        }
        if (nf > 0 && dmin <= 1e-9 * dmax) return null;
        if (!Dense.cholesky(Kf, nf)) return null;
        for (int i = 0; i < nf; i++) if (Math.abs(Kf[i * nf + i]) < 1e-6) return null;
        // loads
        double[][] F = new double[K][nf];
        for (int k = 0; k < K; k++) {
            double[] wv = c.caseWind[k];
            int ts = c.caseTors[k];
            for (int e : t.em) {
                int d = dof[e];
                if (d < 0) continue;
                F[k][d + 1] -= c.EMIT_W;
                if (wv != null) for (int a = 0; a < 3; a++) F[k][d + a] += c.EMIT_WIND0 * wv[a];
                double rx = P[3 * e], rz = P[3 * e + 2];
                double rn = Math.sqrt(rx * rx + rz * rz);
                if (ts != 0 && rn > 0.5) {
                    // cross((0,1,0), r/|r|) = (rz, 0, -rx) / |r|
                    F[k][d] += ts * c.TORQ0 * (rz / rn);
                    F[k][d + 2] += ts * c.TORQ0 * (-rx / rn);
                }
            }
            for (int b = 0; b < m; b++) {
                double wgt = c.RHO * L[b] * wa[b] / 2;
                int di = dof[t.bi[b]], dj = dof[t.bj[b]];
                if (di >= 0) F[k][di + 1] -= wgt;
                if (dj >= 0) F[k][dj + 1] -= wgt;
                if (wv != null) {
                    double[] d = {ex[3 * b] * L[b], ex[3 * b + 1] * L[b], ex[3 * b + 2] * L[b]};
                    double drag = c.CD0 * V.crossNorm(d, wv) * dw[b] / 2;
                    for (int a = 0; a < 3; a++) {
                        if (di >= 0) F[k][di + a] += drag * wv[a];
                        if (dj >= 0) F[k][dj + a] += drag * wv[a];
                    }
                }
            }
        }
        double[][] U = new double[K][];
        for (int k = 0; k < K; k++) U[k] = solve(Kf, sc, nf, F[k]);
        an.dN = new double[K][m];
        for (int k = 0; k < K; k++) for (int b = 0; b < m; b++) an.dN[k][b] = kk[b] * relDisp(U[k], dof, t, ex, b);
        // pretension: recorded floor x f, then raise-only
        an.need = new double[m];
        int ncab = 0;
        for (int b = 0; b < m; b++) {
            if (!an.cab[b]) continue;
            ncab++;
            double mn = Double.POSITIVE_INFINITY;
            for (int k = 0; k < K; k++) mn = Math.min(mn, an.dN[k][b]);
            an.need[b] = Math.max(0, -mn);
        }
        an.nCab = ncab;
        double[] delta = new double[m];
        double[] N0 = new double[m];
        double[] U0 = new double[nf];
        double[] tt = new double[m];
        if (ncab > 0) {
            for (int b = 0; b < m; b++) if (an.cab[b]) delta[b] = c.FIXF * t.floor[b] / kk[b];
            U0 = selfStress(Kf, sc, nf, dof, t, ex, kk, delta, N0);
            double[] tgt = new double[m];
            for (int b = 0; b < m; b++) if (an.cab[b]) tgt[b] = c.PRE_MARGIN * an.need[b];
            for (int it = 0; it < raiseIts; it++) {
                boolean done = true;
                for (int b = 0; b < m; b++) {
                    if (!an.cab[b]) continue;
                    if (!(tgt[b] - N0[b] <= 0.4 * (c.PRE_MARGIN - 1) * an.need[b] + 1e-9)) { done = false; break; }
                }
                if (done) break;
                for (int b = 0; b < m; b++) if (an.cab[b]) delta[b] += 1.5 * Math.max(tgt[b] - N0[b], 0) / kk[b];
                U0 = selfStress(Kf, sc, nf, dof, t, ex, kk, delta, N0);
                an.raiseIters = it + 1;
            }
            for (int b = 0; b < m; b++) tt[b] = an.cab[b] ? Math.max(tgt[b], 0.0) : 0.0;
        }
        an.N0 = N0;
        an.Nt = new double[m]; an.Nc = new double[m]; an.util = new double[m]; an.unreach = new boolean[m];
        an.utilPerCase = new double[K];
        Arrays.fill(an.utilPerCase, Double.NEGATIVE_INFINITY);
        for (int b = 0; b < m; b++) {
            double mx = Double.NEGATIVE_INFINITY, mn = Double.POSITIVE_INFINITY, dmn = Double.POSITIVE_INFINITY;
            for (int k = 0; k < K; k++) {
                double Nk = N0[b] + an.dN[k][b];
                mx = Math.max(mx, Nk); mn = Math.min(mn, Nk); dmn = Math.min(dmn, an.dN[k][b]);
                double uc = an.cab[b] ? Nk / Math.max(an.capT[b], 1e-12)
                        : Math.max(Nk / an.capT[b], -Nk / Math.max(an.capC[b], 1e-12));
                an.utilPerCase[k] = Math.max(an.utilPerCase[k], uc);
            }
            an.Nt[b] = Math.max(0, mx); an.Nc[b] = Math.max(0, -mn);
            an.util[b] = an.cab[b] ? an.Nt[b] / Math.max(an.capT[b], 1e-12)
                    : Math.max(an.Nt[b] / an.capT[b], an.Nc[b] / Math.max(an.capC[b], 1e-12));
            an.maxUtil = Math.max(an.maxUtil, an.util[b]);
            if (an.cab[b]) {
                an.unreach[b] = N0[b] < 1.01 * an.need[b] - 1e-9;
                tt[b] = Math.max(tt[b], N0[b]);
                an.minSlack = Math.min(an.minSlack, (N0[b] + dmn) / Math.max(an.capT[b], 1e-12));
            }
        }
        an.t = tt;
        // pole kinks over cases (U0 + U_k)
        int np = Math.max(0, pc.length - 2);
        an.pk = new double[np];
        double[] Ut = new double[nf];
        for (int k = 0; k < K; k++) {
            for (int i = 0; i < nf; i++) Ut[i] = U0[i] + U[k][i];
            for (int q = 1; q + 1 < pc.length; q++) {
                double[] ui = disp(Ut, dof, pc[q - 1]), uj = disp(Ut, dof, pc[q]), ul = disp(Ut, dof, pc[q + 1]);
                double[] dl = {uj[0] - 0.5 * (ui[0] + ul[0]), uj[1] - 0.5 * (ui[1] + ul[1]), uj[2] - 0.5 * (ui[2] + ul[2])};
                double[] ax = axArr[q];
                double pr = V.dot(ax, dl);
                for (int a = 0; a < 3; a++) dl[a] -= ax[a] * pr;
                double w = t.pw[q];
                double cap = 4 * c.SIG_C0 * w * w * w / 6 / spanArr[q];
                double v = kbArr[q] * V.norm(dl) / cap;
                an.pk[q - 1] = Math.max(an.pk[q - 1], v);
            }
        }
        for (double v : an.pk) an.poleUtil = Math.max(an.poleUtil, v);
        return an;
    }

    private static double[] disp(double[] u, int[] dof, int node) {
        int d = dof[node];
        return d < 0 ? new double[3] : new double[]{u[d], u[d + 1], u[d + 2]};
    }

    private static double relDisp(double[] u, int[] dof, Stitch.Tower t, double[] ex, int b) {
        int di = dof[t.bi[b]], dj = dof[t.bj[b]];
        double s = 0;
        for (int a = 0; a < 3; a++) {
            double uj = dj >= 0 ? u[dj + a] : 0, ui = di >= 0 ? u[di + a] : 0;
            s += (uj - ui) * ex[3 * b + a];
        }
        return s;
    }

    private static double[] solve(double[] Lf, double[] sc, int nf, double[] f) {
        double[] x = new double[nf];
        for (int i = 0; i < nf; i++) x[i] = f[i] / sc[i];
        Dense.cholSolve(Lf, nf, x);
        for (int i = 0; i < nf; i++) x[i] /= sc[i];
        return x;
    }

    /** SELF(delta): lack-of-fit forces on the cables, one solve; fills N0, returns U0. */
    private double[] selfStress(double[] Lf, double[] sc, int nf, int[] dof, Stitch.Tower t, double[] ex, double[] kk,
                                double[] delta, double[] N0) {
        double[] f0 = new double[nf];
        for (int b = 0; b < t.m; b++) {
            if (delta[b] == 0) continue;
            double fk = kk[b] * delta[b];
            int di = dof[t.bi[b]], dj = dof[t.bj[b]];
            for (int a = 0; a < 3; a++) {
                if (di >= 0) f0[di + a] += fk * ex[3 * b + a];
                if (dj >= 0) f0[dj + a] -= fk * ex[3 * b + a];
            }
        }
        double[] U0 = solve(Lf, sc, nf, f0);
        for (int b = 0; b < t.m; b++) N0[b] = kk[b] * (relDisp(U0, dof, t, ex, b) + delta[b]);
        return U0;
    }

    // ------------------------------------------------------------------ sizing (p25fem)
    private int firstGE(double[] lad, double x) { return Consts.searchLeft(lad, x - 1e-12); }

    private double[] sq(double[] a) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] * a[i];
        return r;
    }

    /** size_member (never shrinks); returns true if (kind, ws, cs) changed. */
    boolean sizeMember(Stitch.Tower t, int i, double Nt, double Nc, double L) {
        int k0 = t.kind[i], w0 = t.ws[i], c0 = t.cs[i];
        double nt = Nt * 1.04, nc = Nc * 1.04;
        if (k0 == 1) {
            int j = firstGE(c.CAB_A, nt / c.SIG_CAB);
            if (j < c.CAB_A.length) t.ws[i] = Math.max(j, w0);
            else {
                t.kind[i] = 0; t.ws[i] = 0; t.floor[i] = 0;
                sizeMember(t, i, Nt, Nc, L);
                return true;
            }
        } else if (k0 == 3) {
            int j = Math.min(firstGE(sq(c.BS_W), nt / c.SIG_CAB), c.BS_W.length - 1);
            t.ws[i] = Math.max(j, w0);
        } else if (k0 == 0) {
            double need = Math.max(nt / c.SIG_T, nc / c.sigC(L));
            int lim = t.pole[i] ? c.WIDTHS.length - 1 : c.I_SOLID_MAX;
            int j = Math.max(firstGE(sq(c.WEXT), need), w0);
            if (j <= lim) t.ws[i] = j;
            else if (t.pole[i]) t.ws[i] = lim;
            else {
                t.kind[i] = 2; t.ws[i] = Math.min(j, c.WEXT.length - 1); t.cs[i] = 0;
                sizeLaced(t, i, nt, nc, L, true);
            }
        } else sizeLaced(t, i, nt, nc, L, true);
        return t.kind[i] != k0 || t.ws[i] != w0 || t.cs[i] != c0;
    }

    void sizeLaced(Stitch.Tower t, int i, double Nt, double Nc, double L, boolean keep) {
        int c0 = keep ? t.cs[i] : 0;
        while (true) {
            double b = c.WEXT[t.ws[i]];
            int nc = Sections.nchords(b);
            for (int cI = c0; cI < c.WEXT.length; cI++) {
                double cw = c.WEXT[cI];
                if (cw > c.CH_MAX * b + 1e-9) break;
                double A = nc * cw * cw;
                if (A * c.SIG_T >= Nt && A * sec.sigLaced(L, b, cw) >= Nc) { t.cs[i] = cI; return; }
            }
            if (t.ws[i] >= c.WEXT.length - 1) {
                t.cs[i] = Math.max(c0, Consts.searchLeft(c.WEXT, c.CH_MAX * b) - 1);
                return;
            }
            t.ws[i]++;
        }
    }

    /** resize_pass: returns {changed, converted}. Mutates t (sections, floors, pw). */
    int[] resizePass(Stitch.Tower t, Analysis an) {
        int nch = 0, conv = 0;
        double capmax = c.SIG_CAB * c.CAB_A[c.CAB_A.length - 1];
        double[] r = new double[t.m];
        for (int b = 0; b < t.m; b++) r[b] = t.kind[b] == 1 ? an.t[b] / Math.max(an.capT[b], 1e-12) : 0;
        boolean[] over = new boolean[t.m];
        for (int b = 0; b < t.m; b++) {
            if (t.kind[b] == 1 && (an.t[b] > c.TAUT_PRE_MAX * capmax || an.unreach[b])) {
                over[b] = true;
                t.kind[b] = 0; t.ws[b] = 0; t.floor[b] = 0; conv++;
                sizeMember(t, b, an.Nt[b], an.Nc[b], an.L[b]);
                nch++;
            }
        }
        for (int b = 0; b < t.m; b++) {
            if (r[b] > c.TAUT_PRE_MAX && t.kind[b] == 1 && !over[b])
                nch += sizeMember(t, b, Math.max(an.Nt[b], an.t[b] / c.TAUT_PRE_MAX), 0, an.L[b]) ? 1 : 0;
        }
        for (int b = 0; b < t.m; b++)
            if (an.util[b] > 1.0) nch += sizeMember(t, b, an.Nt[b], an.Nc[b], an.L[b]) ? 1 : 0;
        double pkMax = 0;
        for (double v : an.pk) pkMax = Math.max(pkMax, v);
        if (an.pk.length > 0 && pkMax > 1.0) {
            int[] pc = t.poleChain;
            for (int k = 0; k < an.pk.length; k++) {
                double v = an.pk[k];
                if (v > 1.0) {
                    int nk = k + 1;
                    double w = t.pw[nk] * Math.cbrt(v) * 1.03;
                    int i = Consts.searchLeft(c.WIDTHS, w - 1e-9);
                    t.pw[nk] = c.WIDTHS[Math.min(i, c.WIDTHS.length - 1)];
                }
            }
            Map<Integer, Integer> pidx = new HashMap<>();
            for (int k = 0; k < pc.length; k++) pidx.put(pc[k], k);
            int ch = 0;
            for (int b = 0; b < t.m; b++) {
                Integer a = pidx.get(t.bi[b]), bb = pidx.get(t.bj[b]);
                if (a != null && bb != null && t.kind[b] % 2 == 0) {
                    double w = Math.max(t.pw[a], t.pw[bb]);
                    int s = Math.max(t.ws[b], Consts.searchLeft(c.WIDTHS, w - 1e-9));
                    if (s != t.ws[b]) { t.ws[b] = s; ch++; }
                }
            }
            nch += ch + 1;
        }
        return new int[]{nch, conv};
    }

    // ------------------------------------------------------------------ leg monotonicity (thick_above_thin)
    int legFix(Stitch.Tower t, boolean fix, int[] nviolOut) {
        Map<Long, Integer> idx = new HashMap<>();
        for (int b = 0; b < t.m; b++) idx.put(((long) Math.min(t.bi[b], t.bj[b]) << 32) | Math.max(t.bi[b], t.bj[b]), b);
        List<int[]> segs = new ArrayList<>();
        for (int[] ch : t.legs) {
            for (int q = 0; q + 1 < ch.length; q++) {
                int a = ch[q], b = ch[q + 1];
                int lo = t.P[3 * a + 1] < t.P[3 * b + 1] ? a : b, hi = lo == a ? b : a;
                Integer m = idx.get(((long) Math.min(a, b) << 32) | Math.max(a, b));
                if (m != null) segs.add(new int[]{lo, hi, m});
            }
        }
        Map<Integer, List<Integer>> byTop = new HashMap<>();
        for (int[] s : segs) byTop.computeIfAbsent(s[1], x -> new ArrayList<>()).add(s[2]);
        List<int[]> order = new ArrayList<>(segs);
        order.sort((x, y) -> Double.compare(-t.P[3 * x[1] + 1], -t.P[3 * y[1] + 1]));
        int nviol = 0, nfix = 0;
        for (int[] s : order) {
            int m = s[2];
            for (int mb : byTop.getOrDefault(s[0], List.of())) {
                double w0 = sec.width(t.kind[m], t.ws[m]), w1 = sec.width(t.kind[mb], t.ws[mb]);
                if (w0 > w1 * 1.001) {
                    nviol++;
                    if (fix) {
                        if (t.kind[m] == 2 && t.kind[mb] == 0) { t.kind[mb] = 2; t.cs[mb] = t.cs[m]; }
                        t.ws[mb] = Math.max(t.ws[mb], t.ws[m]);
                        if (t.kind[mb] == 2) t.cs[mb] = Math.max(t.cs[mb], 0);
                        nfix++;
                    }
                }
            }
        }
        if (nviolOut != null) nviolOut[0] = nviol;
        return nfix;
    }

    // ------------------------------------------------------------------ the pass
    private int[] widen(Stitch.Tower t) {
        int rounds = 0, conv = 0, bumps = 0;
        for (int it = 0; it < maxRounds; it++) {
            rounds = it + 1;
            Analysis an = analyze(t);
            if (an == null) throw new MechanismException("taut stiffness singular (mechanism) in round " + rounds);
            int[] r = resizePass(t, an);
            conv += r[1]; bumps += r[0];
            if (r[0] == 0) break;
        }
        return new int[]{rounds, conv, bumps};
    }

    public double volume(Stitch.Tower t) {
        double v = 0;
        for (int b = 0; b < t.m; b++) v += t.length(b) * sec.area(t.kind[b], t.ws[b], t.cs[b]);
        return v;
    }

    /** Runs the pass on a copy of the stitched tower; returns the final tower and fills info / final analysis. */
    public Stitch.Tower run(Stitch.Tower raw, Info info, Analysis[] finalOut) {
        long t0 = System.nanoTime();
        Stitch.Tower t = raw.copy();
        info.volRaw = volume(raw);
        Analysis a0 = analyze(t);
        info.rawUtil = a0 == null ? Double.NaN : a0.maxUtil;
        int[] w = widen(t);
        int nfix = 0;
        for (int s = 0; s < 3; s++) {
            int[] nv = new int[1];
            nfix += legFix(t, true, nv);
            if (nv[0] == 0) break;
        }
        if (nfix > 0) {
            int[] w2 = widen(t);
            w[0] += w2[0]; w[1] += w2[1]; w[2] += w2[2];
        }
        Analysis an = analyze(t);
        if (an == null) throw new MechanismException("final analysis singular");
        info.rounds = w[0]; info.converted = w[1]; info.bumps = w[2]; info.legFix = nfix;
        info.vol = volume(t);
        info.pass = an.maxUtil <= 1.0 + 1e-6 && an.minSlack >= -1e-6;
        info.tPassNs = System.nanoTime() - t0;
        if (finalOut != null) finalOut[0] = an;
        return t;
    }
}
