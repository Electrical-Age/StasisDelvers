package org.erisia.stasis.towergen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spine statics (port of p3alib.spine3a / bay_specs with p2spine.sweep patched for arbitrary H, spine.point_loads
 * for the pass-1 boom estimate, p2lib.boom_loads_world, and the net-moment back-stays). Produces the ring sizes,
 * boom keys, back-stays and the integer bay keys (canonical form, NOTES-P3a section 1).
 */
public final class Spine {
    public static final int TOP = 0, ROOT = 1, ANCHOR = 2, BS = 3;

    /** A point load on the mast (position, force, tag type, boom index). */
    public record Load(double[] p, double[] f, int type, int boom) {}

    public record Wire(double[] a, double[] g, double T) {}

    public static final class BsInfo {
        public double T, peak, peak0, D, attY, angle;
        public int n, npair;
        public boolean feasible = true;
    }

    public static final class BoomInfo {
        public int yb, dir, L, ya;
        public double sr, sa;
        public int[] ikey;
        public Library.Entry entry;
        public String tier = "exact";
        public List<Wire> wires = new ArrayList<>();
        public BsInfo bs = new BsInfo();
    }

    public static final class Ifc {
        public double[][] R, M;
        public List<List<Load>> conc;
        public double N, Vv, Mm, T;
    }

    public static final class BaySpec {
        public int y0, y1, h;
        public double sb, st;
        public int r;
        public int[] ikey, seamKey;      // seamKey == ikey for the top bay
        public int aboveR = -1;
        public int[] aboveKey;
        public int[] rootb;
        public double envN, envVw, envMw, envMg, envT;
        // lookup result (Lookup.resolve)
        public Library.Entry entry;
        public String tier;
        public int kk;                   // rotation offset of a near match (added to r)
        Patch.Result patch;              // last-resort 'patch' tier: patched q1 geometry of the entry
        public int rWorld() { return Math.floorMod(r + kk, 4); }
    }

    public interface BoomResolver {
        /** Library entry for a boom key (exact or nearest); sets info.tier. */
        Library.Entry resolve(int[] key, BoomInfo info);
    }

    public static final class Result {
        public int H;
        public int[] ys;
        public double[] s;
        public Ifc[] ifc;
        public List<BoomInfo> booms = new ArrayList<>();
        public List<BaySpec> specs = new ArrayList<>();

        public double ring(int y) {
            int i = Arrays.binarySearch(ys, y);
            if (i < 0) throw new IllegalArgumentException("no ring at " + y);
            return s[i];
        }
    }

    private final Consts c;

    public Spine(Consts c) { this.c = c; }

    // ------------------------------------------------------------------ helpers
    public static int[] levels(int H) {
        int m = Math.max(1, H / 4);
        int[] r = new int[m + 1];
        for (int i = 0; i < m; i++) r[i] = 4 * i;
        r[m] = H;
        return r;
    }

    /** p3alib.ring_s_at: linear between ring levels. */
    public static double ringSAt(int[] ys, double[] s, double y) {
        int n = ys.length;
        if (y >= ys[n - 1]) return s[n - 1];
        if (y <= ys[0]) return s[0];
        int i = 0;
        while (i + 1 < n && ys[i + 1] <= y) i++;     // bisect_right - 1
        double t = (y - ys[i]) / (double) (ys[i + 1] - ys[i]);
        return (1 - t) * s[i] + t * s[i + 1];
    }

    public static int q4(double x) { return (int) Math.floor(4 * x + 0.5 + 1e-9); }

    double legForce(double M, double N, double s) { return s > 0 ? 0.354 * M / s + N / 4 : 1e9; }

    double legSig(double F) { return F > c.MID_F ? c.sigC(2.0) : c.sigC(4.0); }

    double s0cap(int H) { return Math.min(4.0, Math.max(c.S_CORE, 0.07 * H)); }

    int yFlare(int H) { return (int) Math.min(16, Math.max(4, 4 * Math.rint(H / 5.0 / 4.0))); }   // Python round: half even

    double sCap(int y, int H) {
        int yf = yFlare(H);
        if (y >= yf) return c.S_CORE;
        double t = 1 - y / (double) yf;
        return c.S_CORE + (s0cap(H) - c.S_CORE) * Math.pow(t, 1.6);
    }

    double pickS(double M, double N, double sAbove, boolean needLattice, double cap) {
        Double best = null;
        for (double s : c.S_LADDER) {
            if (s < sAbove - 1e-9 || s > cap + 1e-9) continue;
            if (s == 0) {
                if (M <= c.M_POLE_MAX && !needLattice) return 0.0;
                continue;
            }
            double Fl = legForce(M, N, s);
            best = s;
            if (Fl / legSig(Fl) <= c.W_LEG_TARGET * c.W_LEG_TARGET) return s;
        }
        return best != null ? best : sAbove;
    }

    // ------------------------------------------------------------------ sweep (p2spine.sweep, patched)
    @SuppressWarnings("unchecked")
    Result sweep(int H, List<Layout.Boom> booms, double[] rings, List<Load>[] loads) {
        int K = c.K;
        int[] ys = levels(H);
        boolean[] ringAt = new boolean[H + 1];
        for (int y : ys) ringAt[y] = true;
        boolean[] need = new boolean[H + 1];
        for (Layout.Boom b : booms) {
            int lo = 0;
            for (int i = 0; i < ys.length; i++) if (ys[i] <= b.y()) lo = i;
            need[ys[lo]] = true;
            need[ys[Math.min(lo + 1, ys.length - 1)]] = true;
        }
        boolean choose = rings == null;
        double[] s = choose ? new double[ys.length] : rings;
        double[][] R = new double[K][3], Mo = new double[K][3];
        Ifc[] out = new Ifc[ys.length];
        double sAbove = 0.0;
        List<Load>[] pls = new List[K];
        for (int k = 0; k < K; k++) {
            pls[k] = new ArrayList<>(loads[k]);
            pls[k].sort((a, b) -> Double.compare(-a.p[1], -b.p[1]));   // stable, as Python sorted(key=-y)
        }
        int[] idx = new int[K];
        for (int y = H; y >= 0; y--) {
            List<List<Load>> conc = new ArrayList<>();
            for (int k = 0; k < K; k++) {
                List<Load> pl = pls[k];
                while (idx[k] < pl.size() && pl.get(idx[k]).p[1] > y + 1e-6) {
                    Load L = pl.get(idx[k]);
                    V.addTo(R[k], L.f); V.addTo(Mo[k], V.cross(L.p, L.f)); idx[k]++;
                }
                List<Load> cc = new ArrayList<>();
                int j = idx[k];
                while (j < pl.size() && Math.abs(pl.get(j).p[1] - y) < 1e-6) { cc.add(pl.get(j)); j++; }
                conc.add(cc);
            }
            double[] yv = {0, y, 0};
            double[][] Mres = new double[K][], Rc = new double[K][], Mc = new double[K][];
            double eN = 0, eV = 0, eM = 0, eT = 0;
            for (int k = 0; k < K; k++) {
                Mres[k] = V.sub(Mo[k], V.cross(yv, R[k]));
                Rc[k] = R[k].clone(); Mc[k] = Mres[k].clone();
                for (Load L : conc.get(k)) {
                    V.addTo(Rc[k], L.f);
                    V.addTo(Mc[k], V.cross(V.sub(L.p, yv), L.f));
                }
                eN = Math.max(eN, -Rc[k][1]);
                eV = Math.max(eV, Math.hypot(Rc[k][0], Rc[k][2]));
                eM = Math.max(eM, Math.hypot(Mc[k][0], Mc[k][2]));
                eT = Math.max(eT, Math.abs(Mc[k][1]));
            }
            eN = Math.max(0.0, eN);
            if (ringAt[y]) {
                int ri = Arrays.binarySearch(ys, y);
                if (choose) {
                    double sv;
                    if (y == H) sv = 0.0;
                    else {
                        sv = pickS(eM, eN, sAbove, need[y], sCap(y, H));
                        if (y == 0) sv = Math.max(sv, c.S_CORE);
                    }
                    if (need[y] && sv < 0.75) sv = 0.75;
                    s[ri] = sv; sAbove = sv;
                }
                Ifc f = new Ifc();
                f.R = new double[K][]; f.M = new double[K][];
                for (int k = 0; k < K; k++) { f.R[k] = R[k].clone(); f.M[k] = Mres[k].clone(); }
                f.conc = conc; f.N = eN; f.Vv = eV; f.Mm = eM; f.T = eT;
                out[ri] = f;
            }
            if (y > 0) {
                double sHere = choose ? sAbove : ringSAt(ys, s, y - 0.5);
                double Aleg = 0;
                if (sHere > 0) {
                    double Fl = legForce(eM, eN, sHere);
                    Aleg = Math.max(Fl / legSig(Fl), c.AREAS[0]);
                }
                double slope = 1.0;
                if (!choose) {
                    double ds = Math.abs(ringSAt(ys, s, y) - ringSAt(ys, s, y - 1));
                    slope = Math.sqrt(1 + 2 * ds * ds);
                }
                double vol = c.POLE_A + 4 * Aleg * slope + (sHere > 0 ? 0.08 * Math.max(1.0, sHere / 1.5) : 0.0);
                double wdt = 0.3 + 4 * Math.sqrt(Aleg) + (sHere > 0 ? 0.25 : 0.0);
                double[] p = {0, y - 0.5, 0};
                for (int k = 0; k < K; k++) {
                    double[] f = {0, -c.RHO * vol, 0};
                    double[] wv = c.caseWind[k];
                    if (wv != null) { f[0] += c.CD0 * wdt * wv[0]; f[1] += c.CD0 * wdt * wv[1]; f[2] += c.CD0 * wdt * wv[2]; }
                    V.addTo(R[k], f); V.addTo(Mo[k], V.cross(p, f));
                }
            }
        }
        Result res = new Result();
        res.H = H; res.ys = ys; res.s = s; res.ifc = out;
        return res;
    }

    @SuppressWarnings("unchecked")
    List<Load>[] topLoads(int H) {
        List<Load>[] out = new List[c.K];
        for (int k = 0; k < c.K; k++) {
            double[] f = {0, -c.EMIT_W, 0};
            double[] wv = c.caseWind[k];
            if (wv != null) for (int a = 0; a < 3; a++) f[a] += c.EMIT_WIND0 * wv[a];
            out[k] = new ArrayList<>();
            out[k].add(new Load(new double[]{0, H, 0}, f, TOP, -1));
        }
        return out;
    }

    // ------------------------------------------------------------------ pass-1 boom estimate (spine.py)
    private static final int[][] S1DIR = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};   // E S W N as (dx, dz)

    private record BoomGeo(double[] tip, double[][] roots, double[][] anchors, int ya) {}

    private BoomGeo boomNodes(int[] ys, double[] s, int H, int yb, int dir, int L) {
        int dx = S1DIR[dir][0], dz = S1DIR[dir][1];
        double[] dv = {dx, 0, dz}, pv = {-dz, 0, dx};
        double[] tip = {L * dx, yb, L * dz};
        double sr = ringSAt(ys, s, yb);
        double[][] roots = new double[2][];
        int k = 0;
        for (int sg : new int[]{1, -1})
            roots[k++] = new double[]{sr * dv[0] + sg * sr * pv[0], yb, sr * dv[2] + sg * sr * pv[2]};
        int ya = H;
        for (int y : ys) if (y >= yb + Math.max(c.BAY, 0.7 * L) - 1e-9) { ya = y; break; }
        double sa = s[Arrays.binarySearch(ys, ya)];
        double[][] anchors;
        if (sa > 0) {
            anchors = new double[2][];
            k = 0;
            for (int sg : new int[]{1, -1})
                anchors[k++] = new double[]{sa * dv[0] + sg * sa * pv[0], ya, sa * dv[2] + sg * sa * pv[2]};
        } else anchors = new double[][]{{0, ya, 0}};
        return new BoomGeo(tip, roots, anchors, ya);
    }

    private record BoomMem(double[] t, double[][] u, double[] len, boolean[] cab, double[][] endLoads, double[][] ends) {}

    private BoomMem boomMembers(BoomGeo g, int k, double[] area) {
        int nr = g.roots.length, m = nr + g.anchors.length;
        double[][] ends = new double[m][];
        boolean[] cb = new boolean[m];
        for (int i = 0; i < m; i++) { ends[i] = i < nr ? g.roots[i] : g.anchors[i - nr]; cb[i] = i >= nr; }
        double[] wv = c.caseWind[k];
        int ts = c.caseTors[k];
        double[] ftip = {0, -c.EMIT_W, 0};
        if (wv != null) for (int a = 0; a < 3; a++) ftip[a] += c.EMIT_WIND0 * wv[a];
        double[] r = {g.tip[0], 0, g.tip[2]};
        if (ts != 0) {
            double[] t = V.cross(new double[]{0, 1, 0}, V.mul(r, 1 / V.norm(r)));
            for (int a = 0; a < 3; a++) ftip[a] += ts * c.TORQ0 * t[a];
        }
        double[][] u = new double[m][];
        double[] len = new double[m];
        double[][] el = new double[m][];
        for (int i = 0; i < m; i++) {
            double[] dvec = V.sub(ends[i], g.tip);
            double L = V.norm(dvec);
            u[i] = V.mul(dvec, 1 / L);
            len[i] = L;
            double a = area != null ? area[i] : (cb[i] ? c.CAB_A[0] : 0.04);
            double w = Math.sqrt(a);
            double[] half = {0, -c.RHO * a * L / 2, 0};
            if (wv != null) {
                double s = c.CD0 * V.crossNorm(dvec, wv) * w / 2;
                for (int q = 0; q < 3; q++) half[q] += s * wv[q];
            }
            for (int q = 0; q < 3; q++) ftip[q] = ftip[q] + half[q];
            el[i] = half;
        }
        // min-norm: (A A^T) x = -f_tip, t = A^T x; A = [u_0 .. u_m-1] (3 x m)
        double[][] AA = new double[3][3];
        for (int i = 0; i < m; i++) for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) AA[a][b] += u[i][a] * u[i][b];
        double[] x = Dense.solve3(AA, new double[]{-ftip[0], -ftip[1], -ftip[2]});
        double[] t = new double[m];
        for (int i = 0; i < m; i++) t[i] = V.dot(u[i], x);
        return new BoomMem(t, u, len, cb, el, ends);
    }

    private double[] sizeBoom(BoomGeo g) {
        double[] area = null;
        int m = g.roots.length + g.anchors.length;
        for (int it = 0; it < 8; it++) {
            double[] tmx = new double[m], tmn = new double[m];
            Arrays.fill(tmx, Double.NEGATIVE_INFINITY); Arrays.fill(tmn, Double.POSITIVE_INFINITY);
            BoomMem last = null;
            for (int k = 0; k < c.K; k++) {
                BoomMem bm = boomMembers(g, k, area);
                last = bm;
                for (int j = 0; j < m; j++) { tmx[j] = Math.max(tmx[j], bm.t[j]); tmn[j] = Math.min(tmn[j], bm.t[j]); }
            }
            double[] nw = new double[m];
            for (int j = 0; j < m; j++) {
                double L = last.len[j];
                if (last.cab[j]) nw[j] = Math.max(c.CAB_A[0], tmx[j] / c.SIG_CAB);
                else nw[j] = Math.max(c.AREAS[0], Math.max(tmx[j] / c.SIG_T, -tmn[j] / c.sigC(L <= 12 ? L : 4.0)));
            }
            area = nw;
        }
        return area;
    }

    /** spine.point_loads: per case the top emitter plus the boom root / anchor reactions (P1 min-norm estimate). */
    @SuppressWarnings("unchecked")
    private List<Load>[] pointLoads1(int H, List<Layout.Boom> booms, int[] ys, double[] s, int[] yaOut) {
        List<BoomGeo> geos = new ArrayList<>();
        List<double[]> areas = new ArrayList<>();
        for (int bi = 0; bi < booms.size(); bi++) {
            Layout.Boom b = booms.get(bi);
            BoomGeo g = boomNodes(ys, s, H, b.y(), b.dir(), b.length());
            geos.add(g); areas.add(sizeBoom(g)); yaOut[bi] = g.ya;
        }
        List<Load>[] out = new List[c.K];
        for (int k = 0; k < c.K; k++) {
            out[k] = new ArrayList<>();
            double[] f = {0, -c.EMIT_W, 0};
            double[] wv = c.caseWind[k];
            if (wv != null) for (int a = 0; a < 3; a++) f[a] += c.EMIT_WIND0 * wv[a];
            out[k].add(new Load(new double[]{0, H, 0}, f, TOP, -1));
            for (int bi = 0; bi < geos.size(); bi++) {
                BoomMem bm = boomMembers(geos.get(bi), k, areas.get(bi));
                for (int j = 0; j < bm.t.length; j++) {
                    double[] ff = new double[3];
                    for (int a = 0; a < 3; a++) ff[a] = -bm.t[j] * bm.u[j][a] + bm.endLoads[j][a];
                    out[k].add(new Load(bm.ends[j], ff, bm.cab[j] ? ANCHOR : ROOT, bi));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ boom keys / world loads
    /** p3alib.boom_key_int -> (key, ya). */
    int[] boomKey(int[] ys, double[] s, int H, int yb, int Lb, int[] yaOut) {
        double sr = ringSAt(ys, s, yb);
        int ya = H;
        for (int y : ys) if (y >= yb + Math.max(c.BAY, 0.7 * Lb) - 1e-9) { ya = y; break; }
        double sa = s[Arrays.binarySearch(ys, ya)];
        int li = 0;
        while (li < c.L_LADDER.length && c.L_LADDER[li] < Lb - 1e-9) li++;
        if (li == c.L_LADDER.length) throw new IllegalArgumentException("boom longer than the L ladder: " + Lb);
        double ratio = (ya - yb) / (double) Lb;
        int ri = 0;
        for (int i = 0; i < c.RHO_LADDER.length; i++) if (c.RHO_LADDER[i] <= ratio + 1e-9) ri = i;
        yaOut[0] = ya;
        return new int[]{li, ri, q4(sr), q4(sa)};
    }

    /** p2lib.boom_loads_world. */
    @SuppressWarnings("unchecked")
    List<Load>[] boomLoadsWorld(Library.Entry be, int bi, int yb, int d, double sr, int ya, double sa) {
        List<Load>[] out = new List[c.K];
        double[][] rp = new double[2][];
        int i = 0;
        for (int sg : new int[]{1, -1}) rp[i++] = V.add(V.rot(new double[]{sr, 0, sg * sr}, d), new double[]{0, yb, 0});
        double[][] ap;
        if (sa > 0) {
            ap = new double[2][];
            i = 0;
            for (int sg : new int[]{1, -1}) ap[i++] = V.add(V.rot(new double[]{sa, 0, sg * sa}, d), new double[]{0, ya, 0});
        } else ap = new double[][]{{0, ya, 0}};
        // rot_cases: out[perm(k, q)] = rot(v[k], q)
        double[][][] Rr = new double[c.K][][], Ra = new double[c.K][][];
        for (int k = 0; k < c.K; k++) {
            int pk = Consts.perm(k, d);
            Rr[pk] = new double[be.reacRoots[k].length][];
            for (int j = 0; j < be.reacRoots[k].length; j++) Rr[pk][j] = V.rot(be.reacRoots[k][j], d);
            Ra[pk] = new double[be.reacAnchors[k].length][];
            for (int j = 0; j < be.reacAnchors[k].length; j++) Ra[pk][j] = V.rot(be.reacAnchors[k][j], d);
        }
        for (int k = 0; k < c.K; k++) {
            out[k] = new ArrayList<>();
            for (int j = 0; j < rp.length; j++) out[k].add(new Load(rp[j], Rr[k][j], ROOT, bi));
            if (ap.length == Ra[k].length) {
                for (int j = 0; j < ap.length; j++) out[k].add(new Load(ap[j], Ra[k][j], ANCHOR, bi));
            } else {                                   // anchor count mismatch: lump
                double[] sum = new double[3];
                for (double[] r : Ra[k]) V.addTo(sum, r);
                for (double[] p : ap) out[k].add(new Load(p, V.mul(sum, 1.0 / ap.length), ANCHOR, bi));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ back-stays (net moment, capped anchors)
    private record BsGeo(double[][] att, double[][] gnd, double[][] uv) {}

    private BsGeo bsGeometry(int d, int ya, int[] ys, double[] s, int npair) {
        List<Integer> lev = new ArrayList<>();
        lev.add(ya);
        if (npair == 2) for (int y : ys) if (y > ya) { lev.add(y); break; }
        if (lev.size() < npair) return null;
        List<double[]> att = new ArrayList<>(), gnd = new ArrayList<>();
        for (int y : lev) {
            double sv = s[Arrays.binarySearch(ys, y)];
            double D = Math.min(c.D_FRAC * y, c.D_CAP) + sv;
            for (int sg : new int[]{1, -1}) {
                att.add(sv > 0 ? V.add(V.rot(new double[]{-sv, 0, sg * sv}, d), new double[]{0, y, 0}) : new double[]{0, y, 0});
                gnd.add(V.rot(new double[]{-D, 0, sg * (sv + 0.35 * D)}, d));
            }
        }
        double[][] a = att.toArray(new double[0][]), g = gnd.toArray(new double[0][]);
        double[][] uv = new double[a.length][];
        for (int i = 0; i < a.length; i++) uv[i] = V.unit(V.sub(g[i], a[i]));
        return new BsGeo(a, g, uv);
    }

    /** Horizontal moment (x, z) about the mast axis at each y from the forces above y. */
    private static double[][] momCurve(List<double[][]> pf, double[] ys) {
        double[][] out = new double[ys.length][2];
        for (int k = 0; k < ys.length; k++) {
            double y = ys[k];
            double mx = 0, mz = 0;
            for (double[][] e : pf) {
                double[] p = e[0], f = e[1];
                if (p[1] > y - 1e-9) {
                    double[] m = V.cross(new double[]{p[0], p[1] - y, p[2]}, f);
                    mx += m[0]; mz += m[2];
                }
            }
            out[k][0] = mx; out[k][1] = mz;
        }
        return out;
    }

    private record BSpec(int bi, int d, int ya) {}

    /** p3alib.backstays_net; grav = gravity-case (pos, force) of all booms. Returns wires / info per boom. */
    private void backstaysNet(List<double[][]> grav, List<BSpec> bspec, int H, int[] ys, double[] s,
                              List<List<Wire>> wiresOut, List<BsInfo> infoOut) {
        int ny = 2 * H + 2;
        double[] yy = new double[ny];
        for (int i = 0; i < ny; i++) yy[i] = 0.5 * i;
        double[][] Mb = momCurve(grav, yy);
        int nb = bspec.size();
        BsGeo[] geo = new BsGeo[nb];
        double[][][] mw = new double[nb][][];
        int[] npairs = new int[nb];
        Arrays.fill(npairs, 1);
        double[] T = new double[nb];
        double maxMb = 0;
        for (double[] r : Mb) maxMb = Math.max(maxMb, Math.max(Math.abs(r[0]), Math.abs(r[1])));
        for (int rnd = 0; rnd < 2; rnd++) {
            for (int b = 0; b < nb; b++) {
                BSpec sp = bspec.get(b);
                BsGeo g = bsGeometry(sp.d, sp.ya, ys, s, npairs[b]);
                if (g == null) g = bsGeometry(sp.d, sp.ya, ys, s, 1);
                geo[b] = g;
                List<double[][]> pf = new ArrayList<>();
                for (int i = 0; i < g.att.length; i++) pf.add(new double[][]{g.att[i], g.uv[i]});
                mw[b] = momCurve(pf, yy);
            }
            Arrays.fill(T, 0.0);
            for (int sweep = 0; sweep < 4; sweep++) {
                for (int b = 0; b < nb; b++) {
                    // rest = Mb + sum(T[j] mw[j], j != b), summed as Python's sum() (0 + a + b ...) then added to Mb
                    double[][] rest = new double[ny][2];
                    for (int j = 0; j < nb; j++) {
                        if (j == b) continue;
                        for (int i = 0; i < ny; i++) { rest[i][0] += T[j] * mw[j][i][0]; rest[i][1] += T[j] * mw[j][i][1]; }
                    }
                    for (int i = 0; i < ny; i++) { rest[i][0] = Mb[i][0] + rest[i][0]; rest[i][1] = Mb[i][1] + rest[i][1]; }
                    double maxMw = 0;
                    for (double[] r : mw[b]) maxMw = Math.max(maxMw, Math.max(Math.abs(r[0]), Math.abs(r[1])));
                    double tmax = 4 * maxMb / Math.max(1e-6, maxMw);
                    double step = tmax / 400.0;
                    double bestPeak = Double.POSITIVE_INFINITY, bestT = 0;
                    for (int q = 0; q <= 400; q++) {
                        double t = q * step;             // numpy.linspace(0, tmax, 401)
                        if (q == 400) t = tmax;
                        double pk = 0;
                        for (int i = 0; i < ny; i++) {
                            double x = rest[i][0] + t * mw[b][i][0], z = rest[i][1] + t * mw[b][i][1];
                            pk = Math.max(pk, Math.sqrt(x * x + z * z));
                        }
                        if (pk < bestPeak) { bestPeak = pk; bestT = t; }
                    }
                    T[b] = bestT;
                }
            }
            boolean any = false;
            for (int b = 0; b < nb; b++) {
                BSpec sp = bspec.get(b);
                if (T[b] > c.BS_CAP && npairs[b] == 1 && bsGeometry(sp.d, sp.ya, ys, s, 2) != null) { npairs[b] = 2; any = true; }
            }
            if (!any) break;
        }
        double peak0 = 0, peak = 0;
        for (int i = 0; i < ny; i++) {
            peak0 = Math.max(peak0, Math.hypot(Mb[i][0], Mb[i][1]));
            double x = Mb[i][0], z = Mb[i][1];
            for (int j = 0; j < nb; j++) { x += T[j] * mw[j][i][0]; z += T[j] * mw[j][i][1]; }
            peak = Math.max(peak, Math.hypot(x, z));
        }
        for (int b = 0; b < nb; b++) {
            BsGeo g = geo[b];
            List<Wire> w = new ArrayList<>();
            if (T[b] > c.BS_T_MIN) for (int i = 0; i < g.att.length; i++) w.add(new Wire(g.att[i], g.gnd[i], T[b]));
            BsInfo inf = new BsInfo();
            inf.T = T[b]; inf.n = w.size(); inf.npair = npairs[b]; inf.peak = peak; inf.peak0 = peak0;
            inf.D = Math.hypot(g.gnd[0][0] - g.att[0][0], g.gnd[0][2] - g.att[0][2]);
            inf.feasible = T[b] <= c.BS_CAP; inf.attY = g.att[0][1];
            inf.angle = Math.toDegrees(Math.atan2(g.att[0][1], inf.D));
            wiresOut.add(w); infoOut.add(inf);
        }
    }

    /** Returns per boom index: wires (empty if none). */
    private List<List<Wire>> applyBackstays(List<Layout.Boom> booms, int[] ys, double[] s, int H,
                                            List<List<double[][]>> gravByBoom, int[] ya, List<BsInfo> infos) {
        List<List<Wire>> res = new ArrayList<>();
        for (int i = 0; i < booms.size(); i++) { res.add(new ArrayList<>()); infos.add(null); }
        List<BSpec> bspec = new ArrayList<>();
        for (int bi = 0; bi < booms.size(); bi++)
            if (booms.get(bi).length() >= c.BS_MIN_L) bspec.add(new BSpec(bi, booms.get(bi).dir(), ya[bi]));
        if (bspec.isEmpty()) return res;
        List<double[][]> grav = new ArrayList<>();
        for (List<double[][]> g : gravByBoom) grav.addAll(g);
        List<List<Wire>> w = new ArrayList<>();
        List<BsInfo> inf = new ArrayList<>();
        backstaysNet(grav, bspec, H, ys, s, w, inf);
        for (int i = 0; i < bspec.size(); i++) { res.set(bspec.get(i).bi, w.get(i)); infos.set(bspec.get(i).bi, inf.get(i)); }
        return res;
    }

    private void addBsLoads(List<Load>[] loads, List<Wire> wires, int bi) {
        for (Wire w : wires) {
            double[] f = V.mul(V.unit(V.sub(w.g, w.a)), w.T);
            for (int k = 0; k < c.K; k++) loads[k].add(new Load(w.a, f, BS, bi));
        }
    }

    // ------------------------------------------------------------------ spine3a
    public Result run(Layout layout, BoomResolver resolver) {
        int H = layout.H();
        List<Layout.Boom> booms = layout.booms();
        int[] ys0 = levels(H);
        double[] tmp = new double[ys0.length];
        Arrays.fill(tmp, 1.5);
        List<Load>[] loads1;
        if (!booms.isEmpty()) {
            int[] ya1 = new int[booms.size()];
            loads1 = pointLoads1(H, booms, ys0, tmp, ya1);
            List<List<double[][]>> grav = new ArrayList<>();
            for (int bi = 0; bi < booms.size(); bi++) {
                List<double[][]> g = new ArrayList<>();
                for (Load L : loads1[0]) if (L.type != TOP && L.boom == bi) g.add(new double[][]{L.p, L.f});
                grav.add(g);
            }
            List<BsInfo> inf = new ArrayList<>();
            List<List<Wire>> bs = applyBackstays(booms, ys0, tmp, H, grav, ya1, inf);
            for (int bi = 0; bi < booms.size(); bi++) addBsLoads(loads1, bs.get(bi), bi);
        } else loads1 = topLoads(H);
        Result p1 = sweep(H, booms, null, loads1);
        int[] ys = p1.ys;
        double[] rings = p1.s;
        List<Load>[] loads2 = topLoads(H);
        List<BoomInfo> binfo = new ArrayList<>();
        List<List<double[][]>> grav = new ArrayList<>();
        int[] yaArr = new int[booms.size()];
        for (int bi = 0; bi < booms.size(); bi++) {
            Layout.Boom b = booms.get(bi);
            int[] yaO = new int[1];
            int[] key = boomKey(ys, rings, H, b.y(), b.length(), yaO);
            BoomInfo bo = new BoomInfo();
            bo.yb = b.y(); bo.dir = b.dir(); bo.L = b.length(); bo.ya = yaO[0]; bo.ikey = key;
            bo.sr = ringSAt(ys, rings, b.y()); bo.sa = rings[Arrays.binarySearch(ys, bo.ya)];
            bo.entry = resolver.resolve(key, bo);
            List<Load>[] bl = boomLoadsWorld(bo.entry, bi, bo.yb, bo.dir, bo.sr, bo.ya, bo.sa);
            List<double[][]> g = new ArrayList<>();
            for (Load L : bl[0]) g.add(new double[][]{L.p, L.f});
            grav.add(g);
            yaArr[bi] = bo.ya;
            for (int k = 0; k < c.K; k++) loads2[k].addAll(bl[k]);
            binfo.add(bo);
        }
        if (!booms.isEmpty()) {
            List<BsInfo> inf = new ArrayList<>();
            List<List<Wire>> bs = applyBackstays(booms, ys, rings, H, grav, yaArr, inf);
            for (int bi = 0; bi < booms.size(); bi++) {
                binfo.get(bi).wires = bs.get(bi);
                if (inf.get(bi) != null) binfo.get(bi).bs = inf.get(bi);
                addBsLoads(loads2, bs.get(bi), bi);
            }
        }
        Result p2 = sweep(H, booms, rings, loads2);
        p2.booms = binfo;
        p2.specs = baySpecs(layout, p2);
        return p2;
    }

    // ------------------------------------------------------------------ demand classes
    static double interp(double x, double[] xp, double[] fp) {
        int n = xp.length;
        if (x <= xp[0]) return fp[0];
        if (x >= xp[n - 1]) return fp[n - 1];
        int lo = 0, hi = n - 1;                       // largest j with xp[j] <= x
        while (hi - lo > 1) { int m = (lo + hi) >>> 1; if (xp[m] <= x) lo = m; else hi = m; }
        double sl = (fp[lo + 1] - fp[lo]) / (xp[lo + 1] - xp[lo]);
        return sl * (x - xp[lo]) + fp[lo];
    }

    double eqDepth(double[] arr, double v) {
        double[] d = c.refD;
        int n = arr.length;
        if (v <= arr[0]) return 0.0;
        if (v <= arr[n - 1]) return interp(v, arr, d);
        double g = Math.log(arr[n - 1] / arr[n - 5]) / (d[n - 1] - d[n - 5]);
        return d[n - 1] + Math.log(v / arr[n - 1]) / g;
    }

    int demandClass(double N, double Vv, double Mw) {
        double d = Math.max(eqDepth(c.refN, N), Math.max(eqDepth(c.refV, Vv), eqDepth(c.refM, Mw)));
        for (int k = 0; k < c.DEPTH_LADDER.length; k++) if (c.DEPTH_LADDER[k] >= d - c.DEMAND_EPS) return k;
        return c.DEPTH_LADDER.length - 1;
    }

    double[] demandLoads(int k) {
        double x = c.DEPTH_LADDER[k];
        double[] d = c.refD;
        int n = d.length;
        double[][] arrs = {c.refN, c.refV, c.refM};
        double[] out = new double[3];
        for (int q = 0; q < 3; q++) {
            double[] arr = arrs[q];
            if (x <= d[n - 1]) out[q] = interp(x, d, arr);
            else {
                double g = Math.log(arr[n - 1] / arr[n - 5]) / (d[n - 1] - d[n - 5]);
                out[q] = arr[n - 1] * Math.exp(g * (x - d[n - 1]));
            }
        }
        return out;
    }

    /** p2spine.ladder -> {k, value}. */
    double[] ladder(double v, double floor, double r) {
        if (v <= c.BELOW * floor) return new double[]{-1, 0.0};
        if (v <= floor) return new double[]{0, floor};
        int k = (int) Math.ceil(Math.log(v / floor) / Math.log(r) - c.CEIL_EPS);
        return new double[]{k, floor * Math.pow(r, k)};
    }

    /** p3alib.canon_rot. roots: (y, d, bk...) rows. */
    static int canonRot(List<int[]> roots, int mga) {
        int[] best = null;
        int bestR = 0;
        for (int r = 0; r < 4; r++) {
            int[] cand = canonForm(roots, mga, r);
            if (best == null || Arrays.compare(cand, best) < 0) { best = cand; bestR = r; }
        }
        return bestR;
    }

    /** Sorted rotated roots flattened, then the rotated moment axis (lexicographic comparison key). */
    private static int[] canonForm(List<int[]> roots, int mga, int r) {
        List<int[]> rr = rotRoots(roots, -r);
        int w = roots.isEmpty() ? 0 : roots.get(0).length;
        int[] out = new int[rr.size() * w + 1];
        int i = 0;
        for (int[] x : rr) for (int v : x) out[i++] = v;
        out[i] = mga >= 0 ? Math.floorMod(mga - 2 * r, 8) : -1;
        return out;
    }

    /** Roots with d -> (d + q) mod 4, sorted lexicographically. */
    static List<int[]> rotRoots(List<int[]> roots, int q) {
        List<int[]> rr = new ArrayList<>();
        for (int[] x : roots) {
            int[] y = x.clone();
            y[1] = Math.floorMod(x[1] + q, 4);
            rr.add(y);
        }
        rr.sort(Arrays::compare);
        return rr;
    }

    int pwIndex(double pw) {
        int best = 0;
        for (int i = 1; i < c.WIDTHS.length; i++) if (Math.abs(c.WIDTHS[i] - pw) < Math.abs(c.WIDTHS[best] - pw)) best = i;
        return best;
    }

    // ------------------------------------------------------------------ bay specs (p3alib.bay_specs)
    List<BaySpec> baySpecs(Layout layout, Result sp) {
        int K = c.K;
        int[] lv = sp.ys;
        List<BaySpec> specs = new ArrayList<>();
        for (int li = 0; li + 1 < lv.length; li++) {
            int y0 = lv[li], y1 = lv[li + 1];
            int hb = y1 - y0;
            double sb = sp.s[li], st = sp.s[li + 1];
            Ifc ifc = sp.ifc[li + 1];
            double[][] Rt = new double[K][], Mt = new double[K][];
            double[] y1v = {0, y1, 0};
            for (int k = 0; k < K; k++) {
                Rt[k] = ifc.R[k].clone(); Mt[k] = ifc.M[k].clone();
                for (Load L : ifc.conc.get(k)) {
                    if (L.type == TOP || L.type == ANCHOR || L.type == BS) {
                        V.addTo(Rt[k], L.f); V.addTo(Mt[k], V.cross(V.sub(L.p, y1v), L.f));
                    }
                }
            }
            List<int[]> roots = new ArrayList<>();
            List<Integer> rootb = new ArrayList<>();
            for (int bi = 0; bi < sp.booms.size(); bi++) {
                BoomInfo b = sp.booms.get(bi);
                if (y0 + 1e-6 < b.yb && b.yb <= y1 + 1e-6) {
                    int[] rt = new int[6];
                    rt[0] = b.yb - y0; rt[1] = b.dir;
                    System.arraycopy(b.ikey, 0, rt, 2, 4);
                    roots.add(rt); rootb.add(bi);
                }
            }
            double N = 0, Vw = 0, Mw = 0, T = 0;
            for (int k = 0; k < K; k++) {
                N = Math.max(N, -Rt[k][1]);
                Vw = Math.max(Vw, Math.hypot(Rt[k][0], Rt[k][2]));
                double ax = Mt[k][0] - Mt[0][0], az = Mt[k][2] - Mt[0][2];
                Mw = Math.max(Mw, Math.sqrt(ax * ax + az * az));
                T = Math.max(T, Math.abs(Mt[k][1]));
            }
            N = Math.max(0.0, N);
            double mgx = Mt[0][0], mgz = Mt[0][2];
            double Mg = Math.sqrt(mgx * mgx + mgz * mgz);
            int kd = demandClass(0.0, 0.0, Mw);
            double[] dl = demandLoads(kd);
            double Nr = dl[0], Vr = dl[1], Mr = dl[2];
            double[] kn = N <= c.CLASS_TOL * Nr ? new double[]{-1, Nr} : ladder(N, c.N_FLOOR, c.N_R);
            double[] kv = Vw <= c.CLASS_TOL * Vr ? new double[]{-1, Vr} : ladder(Vw, c.V_FLOOR, c.V_R);
            double[] kmg = ladder(Mg, c.MG_FLOOR, c.MG_R);
            int mga = -1;
            if (kmg[0] >= 0) mga = Math.floorMod((int) Math.rint(Math.toDegrees(Math.atan2(mgz, mgx)) / 45), 8);
            double[] kt = ladder(T, c.T_FLOOR, c.T_R);
            int r = canonRot(roots, mga);
            boolean mid = sb > 0 && st > 0 && legForce(Mw + Mg, N, sb) > c.MID_F;
            double pw = c.W_POLE_MIN;
            double Nc = Math.max(kn[1], Nr), Vc = Math.max(kv[1], Vr), Mc = Mr;
            if (st == 0) {
                double need = Mc + kmg[1] + (sb == 0 ? 4 : 2) * Vc;
                pw = Math.max(c.W_POLE_MIN, Math.cbrt(1.15 * need / 25.0));
                int i = Math.min(Consts.searchLeft(c.WIDTHS, pw - 1e-9), c.WIDTHS.length - 1);
                pw = c.WIDTHS[i];
            }
            List<int[]> croots = rotRoots(roots, -r);
            int mgc = mga >= 0 ? Math.floorMod(mga - 2 * r, 8) : -1;
            int[] key = new int[12 + 6 * croots.size()];
            key[0] = q4(sb); key[1] = q4(st); key[2] = mid ? 1 : 0; key[3] = kd; key[4] = (int) kn[0];
            key[5] = (int) kv[0]; key[6] = (int) kmg[0]; key[7] = mgc; key[8] = (int) kt[0]; key[9] = pwIndex(pw);
            key[10] = hb; key[11] = croots.size();
            int p = 12;
            for (int[] rt : croots) for (int v : rt) key[p++] = v;
            BaySpec s = new BaySpec();
            s.y0 = y0; s.y1 = y1; s.h = hb; s.sb = sb; s.st = st; s.r = r; s.ikey = key;
            s.rootb = rootb.stream().mapToInt(Integer::intValue).toArray();
            s.envN = N; s.envVw = Vw; s.envMw = Mw; s.envMg = Mg; s.envT = T;
            specs.add(s);
        }
        for (int i = 0; i < specs.size(); i++) {
            BaySpec s = specs.get(i);
            if (i + 1 < specs.size()) {
                BaySpec ab = specs.get(i + 1);
                s.aboveR = ab.r; s.aboveKey = ab.ikey;
                int[] sk = new int[s.ikey.length + 2 + ab.ikey.length];
                System.arraycopy(s.ikey, 0, sk, 0, s.ikey.length);
                sk[s.ikey.length] = -9;
                sk[s.ikey.length + 1] = Math.floorMod(ab.r - s.r, 4);
                System.arraycopy(ab.ikey, 0, sk, s.ikey.length + 2, ab.ikey.length);
                s.seamKey = sk;
            } else s.seamKey = s.ikey;
        }
        return specs;
    }
}
