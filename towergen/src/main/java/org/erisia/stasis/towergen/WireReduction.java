package org.erisia.stasis.towergen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Wire reduction on the stitched tower (SolverParams maxStaysPerBoom, xPanels, maxCablesPerBay), before the runtime
 * pass re-sizes everything. Forces for the ranking come from one analysis of the stitched tower under the runtime
 * loads (largest |N| over the load cases incl. pretension).
 * <ol>
 *   <li>xPanels: crossing cable pairs within one mast bay become one strut diagonal (the other cable goes). The kept
 *   diagonal winds one way round the mast in even bays and the other way in odd bays (a zigzag up each face).
 *   "auto" does it only where a smallest strut carries twice the pair's largest force.</li>
 *   <li>maxCablesPerBay: the lowest-force cables of a bay beyond the limit become struts (no member is removed).</li>
 *   <li>maxStaysPerBoom: the stays (members from the boom's mast anchor to the boom) with the largest force are kept,
 *   the rest removed. Then each boom alone (its mast nodes held) is checked for a mechanism; while it is one, the
 *   removed stay nearest the singular node (largest force on ties) is put back. A last whole-tower check does the
 *   same globally. The library booms are flat trusses, so each boom node needs its own support: the floor is about
 *   one stay per boom node, plus some for the in-plane mechanism at collinear chord nodes.</li>
 * </ol>
 * Never throws for a mechanism: if nothing helps it returns the stitched tower unchanged, with a warning.
 */
public final class WireReduction {
    /** What was done (for stats / tests). */
    public static final class Report {
        public int xSingled, xRestored, bayConverted, staysRemoved, staysReadded, globalReadded, utilReadded, analyses;
        /** staysReadded by trigger: a boom node became flat, the boom alone was a mechanism, a member overloaded. */
        public int readdFlat, readdMech, readdForce;
        public List<String> warnings = new ArrayList<>();
    }

    /** Smallest eigenvalue of sum(e e^T) over a node's members below which the node counts as flat (~6 degrees). */
    static final double FLAT = 0.01;

    private final Stitch.Tower raw, t;
    private final RuntimePass pass;
    private final Sections sec;
    private final double[] f;
    private final boolean[] keep, removed;   // removed: members that may be put back (stays, X partners)
    private boolean failed;
    public final Report rep = new Report();

    /** Runs the reduction (see the class comment); {@link #tower()} is the result. */
    public WireReduction(Stitch.Tower raw, RuntimePass pass, SolverParams p) {
        this.raw = raw;
        this.pass = pass;
        this.sec = new Sections(pass.consts());
        int m = raw.m;
        t = raw.copy();
        f = new double[m];
        keep = new boolean[m];
        removed = new boolean[m];
        Arrays.fill(keep, true);
        RuntimePass.Analysis a0 = pass.analyzeOrMechanism(raw);
        rep.analyses++;
        if (a0.mechanism != null) {
            rep.warnings.add("wire reduction skipped: the stitched tower is already a mechanism (" + a0.mechanism + ")");
            failed = true;
            return;
        }
        for (int b = 0; b < m; b++) f[b] = Math.max(a0.Nt[b], a0.Nc[b]);
        xPanels(p);
        cablesPerBay(p);
        stays(p);
        globalCheck();
    }

    /** The reduced tower (the stitched one if the reduction failed). */
    public Stitch.Tower tower() { return failed ? raw : t.subset(keep); }

    public boolean failed() { return failed; }

    private void xPanels(SolverParams p) {
        if (p.xPanels().equals("keep")) return;
        int m = t.m;
        boolean auto = p.xPanels().equals("auto");
        int nbay = 0;
        for (int b = 0; b < m; b++) if (t.ownerType[b] == 0) nbay = Math.max(nbay, t.ownerIdx[b] + 1);
        List<List<Integer>> byBay = new ArrayList<>();
        for (int i = 0; i < nbay; i++) byBay.add(new ArrayList<>());
        for (int b = 0; b < m; b++) if (t.ownerType[b] == 0 && t.kind[b] == 1) byBay.get(t.ownerIdx[b]).add(b);
        for (int bay = 0; bay < nbay; bay++) {
            List<Integer> cs = byBay.get(bay);
            boolean[] used = new boolean[cs.size()];
            for (int i = 0; i < cs.size(); i++) {
                if (used[i]) continue;
                for (int j = i + 1; j < cs.size(); j++) {
                    if (used[j]) continue;
                    int b1 = cs.get(i), b2 = cs.get(j);
                    if (!crosses(t, b1, b2)) continue;
                    used[i] = used[j] = true;
                    int hand = bay % 2 == 0 ? 1 : -1;
                    int w1 = winding(t, b1), w2 = winding(t, b2);
                    int k;
                    if (w1 != 0 && w2 != 0 && w1 != w2) k = w1 == hand ? b1 : b2;
                    else k = f[b1] >= f[b2] ? b1 : b2;
                    int o = k == b1 ? b2 : b1;
                    if (auto) {
                        double L = t.length(k);
                        double cap = Math.min(sec.capT(0, 0, 0), sec.capC(0, 0, 0, L));
                        if (2 * Math.max(f[b1], f[b2]) * 1.04 > cap * pass.consts().UTIL_LIMIT) break;
                    }
                    toStrut(k);
                    keep[o] = false; removed[o] = true;
                    rep.xSingled++;
                    break;
                }
            }
        }
    }

    private void toStrut(int b) { t.kind[b] = 0; t.ws[b] = 0; t.cs[b] = 0; t.floor[b] = 0; t.pole[b] = false; }

    private void cablesPerBay(SolverParams p) {
        if (p.maxCablesPerBay() <= 0) return;
        int m = t.m, nbay = 0;
        for (int b = 0; b < m; b++) if (t.ownerType[b] == 0) nbay = Math.max(nbay, t.ownerIdx[b] + 1);
        for (int bay = 0; bay < nbay; bay++) {
            List<Integer> cs = new ArrayList<>();
            for (int b = 0; b < m; b++) if (keep[b] && t.ownerType[b] == 0 && t.ownerIdx[b] == bay && t.kind[b] == 1) cs.add(b);
            if (cs.size() <= p.maxCablesPerBay()) continue;
            cs.sort((x, y) -> f[x] != f[y] ? Double.compare(f[x], f[y]) : Integer.compare(x, y));
            for (int i = 0; i < cs.size() - p.maxCablesPerBay(); i++) { toStrut(cs.get(i)); rep.bayConverted++; }
        }
    }

    private void stays(SolverParams p) {
        if (p.maxStaysPerBoom() <= 0) return;
        int m = t.m;
        int lim = Math.max(p.maxStaysPerBoom(), p.minStaysPerBoom());
        for (int k = 0; k < t.boomAnchors.length; k++) {
            List<Integer> st = new ArrayList<>();
            for (int b = 0; b < m; b++) if (keep[b] && t.ownerType[b] == 1 && t.ownerIdx[b] == k && t.isStay(b)) st.add(b);
            if (st.size() <= lim) continue;
            double[] flat0 = flatness(k);
            st.sort((x, y) -> f[x] != f[y] ? Double.compare(f[y], f[x]) : Integer.compare(x, y));
            for (int i = lim; i < st.size(); i++) { keep[st.get(i)] = false; removed[st.get(i)] = true; rep.staysRemoved++; }
            // boom-local stabilisation: the boom's own members, every node shared with another part held
            int guard = st.size();
            while (guard-- > 0) {
                int node = flatNode(k, flat0), why = 0;
                if (node < 0) {
                    Stitch.Tower bt = boomOnly(k);
                    RuntimePass.Analysis a = pass.analyzeOrMechanism(bt);
                    rep.analyses++;
                    node = a.mechanism != null ? a.mechNode : overloaded(bt, a, idx(keepBoom(k)));
                    why = a.mechanism != null ? 1 : 2;
                }
                if (node == -2) break;
                int b = nearestRemoved(node, k);
                if (b < 0) break;
                keep[b] = true; removed[b] = false; rep.staysReadded++;
                if (why == 0) rep.readdFlat++; else if (why == 1) rep.readdMech++; else rep.readdForce++;
            }
        }
    }

    private void globalCheck() {
        for (int guard = 0; guard <= t.m; guard++) {
            Stitch.Tower out = t.subset(keep);
            RuntimePass.Analysis a = pass.analyzeOrMechanism(out);
            rep.analyses++;
            int node = a.mechanism != null ? a.mechNode : overloaded(out, a, idx(keep));
            if (node == -2) {
                if (rep.globalReadded > 0)
                    rep.warnings.add("wire reduction: " + rep.globalReadded + " members put back by the whole-tower check");
                return;
            }
            int b = nearestRemoved(node, -1);
            if (b < 0) {
                rep.warnings.add("wire reduction left a " + (a.mechanism != null ? "mechanism (" + a.mechanism + ")"
                        : "near-mechanism (forces beyond the largest section)") + "; using the unreduced tower");
                failed = true;
                return;
            }
            putBack(b);
            rep.globalReadded++;
        }
        rep.warnings.add("wire reduction did not converge; using the unreduced tower");
        failed = true;
    }

    private void putBack(int b) {
        keep[b] = true; removed[b] = false;
        if (t.isStay(b)) rep.staysReadded++; else rep.xRestored++;
    }

    /**
     * After an over-limit runtime pass on {@link #tower()}: puts back the {@code count} removed members nearest the
     * worst member (index into tower()), preferring its own boom. Returns false if there was nothing to put back.
     */
    public boolean putBackNear(int worst, int count) {
        if (failed) return false;
        int[] orig = idx(keep);
        int ob = orig[worst];
        int node = t.fixed[t.bj[ob]] ? t.bi[ob] : t.bj[ob];
        int n = 0;
        for (int i = 0; i < count; i++) {
            int b = t.ownerType[ob] == 1 ? nearestRemoved(node, t.ownerIdx[ob]) : -1;
            if (b < 0) b = nearestRemoved(node, -1);
            if (b < 0) break;
            putBack(b);
            rep.utilReadded++;
            n++;
        }
        return n > 0;
    }

    /** Per node: smallest eigenvalue of sum(e e^T) over the kept members at the node (boom k's free nodes only). */
    private double[] flatness(int k) {
        double[] S = new double[6 * t.n];
        boolean[] mine = new boolean[t.n], other = new boolean[t.n];
        for (int b = 0; b < t.m; b++) {
            boolean me = t.ownerType[b] == 1 && t.ownerIdx[b] == k;
            boolean[] x = me ? mine : other;
            x[t.bi[b]] = true; x[t.bj[b]] = true;
        }
        for (int b = 0; b < t.m; b++) {
            if (!keep[b]) continue;
            int i = t.bi[b], j = t.bj[b];
            if (!mine[i] && !mine[j]) continue;
            double L = t.length(b);
            double ex = (t.P[3 * j] - t.P[3 * i]) / L, ey = (t.P[3 * j + 1] - t.P[3 * i + 1]) / L, ez = (t.P[3 * j + 2] - t.P[3 * i + 2]) / L;
            for (int nd : new int[]{i, j}) {
                int o = 6 * nd;
                S[o] += ex * ex; S[o + 1] += ey * ey; S[o + 2] += ez * ez;
                S[o + 3] += ex * ey; S[o + 4] += ex * ez; S[o + 5] += ey * ez;
            }
        }
        double[] lam = new double[t.n];
        Arrays.fill(lam, Double.POSITIVE_INFINITY);
        for (int i = 0; i < t.n; i++) {
            if (!mine[i] || t.fixed[i] || other[i]) continue;
            int o = 6 * i;
            lam[i] = minEig(S[o], S[o + 1], S[o + 2], S[o + 3], S[o + 4], S[o + 5]);
        }
        return lam;
    }

    /** A free node of boom k that became flat by the reduction (worst first), or -1. */
    private int flatNode(int k, double[] flat0) {
        double[] lam = flatness(k);
        int best = -1;
        double bl = Double.POSITIVE_INFINITY;
        for (int i = 0; i < t.n; i++) {
            if (lam[i] < Math.min(FLAT, 0.5 * flat0[i]) && lam[i] < bl) { bl = lam[i]; best = i; }
        }
        return best;
    }

    /** Smallest eigenvalue of the symmetric 3x3 [[a, d, e], [d, b, f], [e, f, c]] (trigonometric form). */
    static double minEig(double a, double b, double c, double d, double e, double f) {
        double p1 = d * d + e * e + f * f;
        double q = (a + b + c) / 3;
        if (p1 < 1e-18) return Math.min(a, Math.min(b, c));
        double p2 = (a - q) * (a - q) + (b - q) * (b - q) + (c - q) * (c - q) + 2 * p1;
        double p = Math.sqrt(p2 / 6);
        double b11 = (a - q) / p, b22 = (b - q) / p, b33 = (c - q) / p, b12 = d / p, b13 = e / p, b23 = f / p;
        double det = b11 * (b22 * b33 - b23 * b23) - b12 * (b12 * b33 - b23 * b13) + b13 * (b12 * b23 - b22 * b13);
        double r = Math.max(-1, Math.min(1, det / 2));
        double phi = Math.acos(r) / 3;
        return q + 2 * p * Math.cos(phi + 2 * Math.PI / 3);
    }

    /**
     * Near-mechanism test: a member whose force (sections as stitched) exceeds OVERLOAD x the capacity of the largest
     * laced section at its length and at least twice its force before the reduction. Returns a free end node of the worst such member, or -2 if there is none.
     */
    private int overloaded(Stitch.Tower t, RuntimePass.Analysis a, int[] orig) {
        Consts c = sec.consts();
        int wl = c.WEXT.length - 1;
        double b0 = c.WEXT[wl];
        int cl = Math.max(0, Consts.searchLeft(c.WEXT, c.CH_MAX * b0) - 1);
        double worst = OVERLOAD;
        int wb = -1;
        for (int b = 0; b < t.m; b++) {
            double cap = Math.min(sec.capT(2, wl, cl), sec.capC(2, wl, cl, a.L[b]));
            double nb = Math.max(a.Nt[b], a.Nc[b]);
            double r = nb / cap;
            if (nb < 2 * f[orig[b]]) continue;          // was (nearly) as loaded before the reduction
            if (r > worst) { worst = r; wb = b; }
        }
        if (wb < 0) return -2;
        return t.fixed[t.bj[wb]] ? t.bi[wb] : t.bj[wb];
    }

    /** Overload fraction of the largest section that counts as a near-mechanism. */
    static final double OVERLOAD = 0.5;

    private static int[] idx(boolean[] sel) {
        int n = 0;
        for (boolean x : sel) if (x) n++;
        int[] r = new int[n];
        n = 0;
        for (int b = 0; b < sel.length; b++) if (sel[b]) r[n++] = b;
        return r;
    }

    private boolean[] keepBoom(int k) {
        boolean[] mine = new boolean[t.m];
        for (int b = 0; b < t.m; b++) mine[b] = keep[b] && t.ownerType[b] == 1 && t.ownerIdx[b] == k;
        return mine;
    }

    /** Boom k's kept members alone; nodes that also belong to other members (mast, other booms) are held. */
    private Stitch.Tower boomOnly(int k) {
        boolean[] mine = new boolean[t.m];
        boolean[] other = new boolean[t.n];
        for (int b = 0; b < t.m; b++) {
            if (t.ownerType[b] == 1 && t.ownerIdx[b] == k) { mine[b] = keep[b]; continue; }
            if (keep[b]) { other[t.bi[b]] = true; other[t.bj[b]] = true; }
        }
        Stitch.Tower s = t.subset(mine);
        boolean[] fx = t.fixed.clone();
        for (int i = 0; i < t.n; i++) if (other[i]) fx[i] = true;
        for (int x : t.boomAnchors[k]) fx[x] = true;
        s.fixed = fx;
        s.poleChain = new int[0];
        s.pw = new double[0];
        return s;
    }

    /** The removed stay (of boom k, or any boom if k < 0) nearest node p; ties by the larger force. -1 if none. */
    private int nearestRemoved(int p, int k) {
        int best = -1;
        double bd = Double.POSITIVE_INFINITY;
        for (int b = 0; b < t.m; b++) {
            if (!removed[b] || (k >= 0 && (t.ownerType[b] != 1 || t.ownerIdx[b] != k))) continue;
            double d = p < 0 ? 0 : Math.min(dist(t, p, t.bi[b]), dist(t, p, t.bj[b]));
            if (d < bd - 1e-9 || (Math.abs(d - bd) <= 1e-9 && f[b] > f[best])) { bd = d; best = b; }
        }
        return best;
    }

    private static double dist(Stitch.Tower t, int a, int b) {
        double dx = t.P[3 * a] - t.P[3 * b], dy = t.P[3 * a + 1] - t.P[3 * b + 1], dz = t.P[3 * a + 2] - t.P[3 * b + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Sign of the turn about the mast axis from the lower end to the upper end (0 if level or radial). */
    private static int winding(Stitch.Tower t, int b) {
        int i = t.bi[b], j = t.bj[b];
        if (Math.abs(t.P[3 * i + 1] - t.P[3 * j + 1]) < 1e-6) return 0;
        int lo = t.P[3 * i + 1] < t.P[3 * j + 1] ? i : j, hi = lo == i ? j : i;
        double cr = t.P[3 * lo] * t.P[3 * hi + 2] - t.P[3 * lo + 2] * t.P[3 * hi];
        return Math.abs(cr) < 1e-9 ? 0 : (cr > 0 ? 1 : -1);
    }

    /** Two members cross: their segments meet (gap < 1e-3) at interior points of both. */
    static boolean crosses(Stitch.Tower t, int b1, int b2) {
        double[] p = t.node(t.bi[b1]), q = t.node(t.bj[b1]), r = t.node(t.bi[b2]), s = t.node(t.bj[b2]);
        double[] d1 = V.sub(q, p), d2 = V.sub(s, r), w = V.sub(p, r);
        double a = V.dot(d1, d1), bb = V.dot(d1, d2), c = V.dot(d2, d2), d = V.dot(d1, w), e = V.dot(d2, w);
        double den = a * c - bb * bb;
        if (den < 1e-9 * a * c) return false;              // parallel
        double sc = (bb * e - c * d) / den, tc = (a * e - bb * d) / den;
        if (sc < 0.02 || sc > 0.98 || tc < 0.02 || tc > 0.98) return false;
        double[] g = V.sub(V.add(w, V.mul(d1, sc)), V.mul(d2, tc));
        return V.norm(g) < 1e-3 * Math.sqrt(Math.max(a, c));
    }

    /** Wire counts of a tower: cables, stays (all kinds), stays that are cables, back-stays. */
    public record Counts(int cables, int stays, int stayCables, int backstays) {
        public static Counts of(Stitch.Tower t) {
            int c = 0, s = 0, sc = 0, bs = 0;
            for (int b = 0; b < t.m; b++) {
                if (t.kind[b] == 1) c++;
                if (t.kind[b] == 3) bs++;
                if (t.isStay(b)) { s++; if (t.kind[b] == 1) sc++; }
            }
            return new Counts(c, s, sc, bs);
        }

        @Override public String toString() { return cables + " cables, " + stays + " stays (" + stayCables + " cables), " + backstays + " back-stays"; }
    }
}
