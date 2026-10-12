package org.erisia.stasis.towergen;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stitching of the looked-up modules into one tower (port of p25lib.stitch with P3a specs). */
public final class Stitch {
    /** The stitched tower: nodes, members with their state, pole chain, supports, emitters, back-stay anchors. */
    public static final class Tower {
        public double[] P;            // n x 3
        public int n;
        public int[] bi, bj, kind, ws, cs;
        public boolean[] pole;
        public double[] floor;
        public int[] ownerType, ownerIdx;   // 0 bay, 1 boom, 2 back-stay
        public int m;
        public int[] poleChain;
        public double[] pw;
        public boolean[] fixed;
        public int[] em;
        public int[] ground;
        public List<int[]> legs = new ArrayList<>();
        public List<String> variants = new ArrayList<>();
        public int ghost;
        /** Per boom: the stitched node ids of its mast anchor node(s) (where the stays meet the mast). */
        public int[][] boomAnchors = new int[0][];

        public double[] node(int i) { return new double[]{P[3 * i], P[3 * i + 1], P[3 * i + 2]}; }

        public Tower copy() {
            Tower t = new Tower();
            t.P = P; t.n = n; t.m = m;
            t.bi = bi.clone(); t.bj = bj.clone(); t.kind = kind.clone(); t.ws = ws.clone(); t.cs = cs.clone();
            t.pole = pole.clone(); t.floor = floor.clone(); t.ownerType = ownerType.clone(); t.ownerIdx = ownerIdx.clone();
            t.poleChain = poleChain; t.pw = pw.clone(); t.fixed = fixed; t.em = em; t.ground = ground; t.legs = legs;
            t.variants = variants; t.ghost = ghost; t.boomAnchors = boomAnchors;
            return t;
        }

        /** A copy holding only the members with keep[b] (same nodes, same order otherwise). */
        public Tower subset(boolean[] keep) {
            Tower t = copy();
            int q = 0;
            for (int b = 0; b < m; b++) {
                if (!keep[b]) continue;
                t.bi[q] = bi[b]; t.bj[q] = bj[b]; t.kind[q] = kind[b]; t.ws[q] = ws[b]; t.cs[q] = cs[b];
                t.pole[q] = pole[b]; t.floor[q] = floor[b]; t.ownerType[q] = ownerType[b]; t.ownerIdx[q] = ownerIdx[b];
                q++;
            }
            t.m = q;
            t.bi = Arrays.copyOf(t.bi, q); t.bj = Arrays.copyOf(t.bj, q); t.kind = Arrays.copyOf(t.kind, q);
            t.ws = Arrays.copyOf(t.ws, q); t.cs = Arrays.copyOf(t.cs, q); t.pole = Arrays.copyOf(t.pole, q);
            t.floor = Arrays.copyOf(t.floor, q); t.ownerType = Arrays.copyOf(t.ownerType, q);
            t.ownerIdx = Arrays.copyOf(t.ownerIdx, q);
            return t;
        }

        /** True if member b is a stay of its boom (a boom member with one end at the boom's mast anchor). */
        public boolean isStay(int b) {
            if (ownerType[b] != 1 || ownerIdx[b] >= boomAnchors.length) return false;
            boolean a = false, z = false;
            for (int x : boomAnchors[ownerIdx[b]]) { if (x == bi[b]) a = true; if (x == bj[b]) z = true; }
            return a != z;
        }

        public double length(int b) {
            double dx = P[3 * bj[b]] - P[3 * bi[b]], dy = P[3 * bj[b] + 1] - P[3 * bi[b] + 1], dz = P[3 * bj[b] + 2] - P[3 * bi[b] + 2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private final Consts c;
    private final Sections sec;

    public Stitch(Consts c) { this.c = c; this.sec = new Sections(c); }

    /** p2lib.module_threshold. */
    public static double moduleThreshold(double qLow, int seed, String tag) {
        byte[] h = Portable.sha1((seed + ":" + tag).getBytes(StandardCharsets.UTF_8));
        long v = ((h[0] & 0xffL) << 24) | ((h[1] & 0xffL) << 16) | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL);
        return qLow + (1 - qLow) * (v / (double) 0xffffffffL) + 1e-6;
    }

    private Library.Variant variant(Library.Entry e, double q, int seed, String tag, List<String> vl) {
        if (q >= moduleThreshold(c.Q_LOW, seed, tag) || e.q03 == null) { vl.add("q1"); return e.q1; }
        vl.add("low");
        return e.q03;
    }

    private static final class Builder {
        final List<double[]> pts = new ArrayList<>();
        final Map<Long, Integer> idx = new HashMap<>();

        static long rk(double v) { return (long) Math.rint(v * 1000); }

        int node(double[] p) {
            long k = ((rk(p[0]) & 0x1fffffL) << 42) | ((rk(p[1]) & 0x1fffffL) << 21) | (rk(p[2]) & 0x1fffffL);
            Integer i = idx.get(k);
            if (i != null) return i;
            idx.put(k, pts.size());
            pts.add(p.clone());
            return pts.size() - 1;
        }
    }

    /**
     * Last-resort stretch (Lookup ring_near / ring_near_h): radial scale from the entry's ring half-widths to the
     * actual ones, linear in y, and a y scale to the actual bay height.
     */
    static double[] stretch(double[] p, double sbE, double stE, double hE, double sb, double st, double h) {
        double t = p[1] / hE;
        double se = sbE + t * (stE - sbE), sa = sb + t * (st - sb);
        double f = se > 1e-9 ? sa / se : 1.0;
        return new double[]{p[0] * f, p[1] * h / hE, p[2] * f};
    }

    public Tower stitch(Layout layout, Spine.Result sp, double q, int seed) {
        int H = layout.H();
        Builder B = new Builder();
        int ghost = B.node(new double[]{0, -2.0, 0});
        List<int[]> bars = new ArrayList<>();
        List<int[]> state = new ArrayList<>();      // kind, ws, cs, ownerType, ownerIdx
        List<Double> floors = new ArrayList<>();
        LinkedHashMap<Integer, Double> poleNodes = new LinkedHashMap<>();
        Map<Integer, Double> poleW = new HashMap<>();
        List<String> vlist = new ArrayList<>();
        List<int[]> legs = new ArrayList<>();
        for (int b = 0; b < sp.specs.size(); b++) {
            Spine.BaySpec spec = sp.specs.get(b);
            Library.Entry ent = spec.entry;
            Library.Variant v;
            int[] epole = ent.pole;
            int[][] elegs = ent.legs;
            if (spec.patch != null) {                    // last-resort patch: q1 geometry, already stretched
                v = spec.patch.v; epole = spec.patch.pole; elegs = spec.patch.legs;
                vlist.add("q1");
            } else v = variant(ent, q, seed, "bay" + spec.y0, vlist);
            int r = spec.rWorld();
            boolean str = spec.tier != null && spec.tier.startsWith("ring_near");
            int[] ek = ent.key.k();
            int[] loc = new int[v.n()];
            Arrays.fill(loc, -1);
            for (int i = 0; i < v.n(); i++) {
                if (contains(ent.ghost, i)) continue;
                double[] p = {v.nodes[3 * i], v.nodes[3 * i + 1], v.nodes[3 * i + 2]};
                if (str) p = stretch(p, ek[0] / 4.0, ek[1] / 4.0, ek[10], spec.sb, spec.st, spec.h);
                double[] pw = V.rot(p, r);
                pw[1] += spec.y0;
                loc[i] = B.node(pw);
            }
            for (int k = 0; k < epole.length; k++) {
                int pi = epole[k];
                if (contains(ent.ghost, pi)) continue;
                int gi = loc[pi];
                poleNodes.put(gi, B.pts.get(gi)[1]);
                double w = k < v.poleW.length ? v.poleW[k] : c.W_POLE_MIN;
                poleW.put(gi, Math.max(poleW.getOrDefault(gi, 0.0), w));
            }
            for (int[] ch : elegs) {
                int[] g = new int[ch.length];
                for (int i = 0; i < ch.length; i++) g[i] = loc[ch[i]];
                legs.add(g);
            }
            for (int m = 0; m < v.m(); m++) {
                bars.add(new int[]{loc[v.mi[m]], loc[v.mj[m]]});
                state.add(new int[]{v.kind[m], v.ws[m], v.cs[m], 0, b});
                floors.add(v.pre[m]);
            }
        }
        List<Integer> em = new ArrayList<>();
        em.add(B.node(new double[]{0, H, 0}));
        List<Integer> ground = new ArrayList<>();
        int[][] banch = new int[sp.booms.size()][];
        for (int k = 0; k < sp.booms.size(); k++) {
            Spine.BoomInfo bo = sp.booms.get(k);
            Library.Entry be = bo.entry;
            Library.Variant v = variant(be, q, seed, "boom" + k, vlist);
            int q4 = bo.dir;
            double Lc = c.L_LADDER[be.key.k()[0]], sr = be.key.k()[2] / 4.0;
            int[] loc = new int[v.n()];
            for (int i = 0; i < v.n(); i++) {
                double px = v.nodes[3 * i], py = v.nodes[3 * i + 1], pz = v.nodes[3 * i + 2];
                double[] pw;
                if (contains(be.roots, i)) {
                    pw = V.rot(new double[]{bo.sr, 0, Math.signum(pz) * bo.sr}, q4);
                    pw[1] += bo.yb;
                } else if (contains(be.anchors, i)) {
                    if (bo.sa > 0) {
                        pw = V.rot(new double[]{bo.sa, 0, Math.signum(pz) * bo.sa}, q4);
                        pw[1] += bo.ya;
                    } else pw = new double[]{0, bo.ya, 0};
                } else {
                    double x = bo.sr + (px - sr) * (bo.L - bo.sr) / (Lc - sr);
                    pw = V.rot(new double[]{x, py, pz}, q4);
                    pw[1] += bo.yb;
                }
                loc[i] = B.node(pw);
            }
            banch[k] = new int[be.anchors.length];
            for (int i = 0; i < be.anchors.length; i++) banch[k][i] = loc[be.anchors[i]];
            for (int m = 0; m < v.m(); m++) {
                bars.add(new int[]{loc[v.mi[m]], loc[v.mj[m]]});
                state.add(new int[]{v.kind[m], v.ws[m], v.cs[m], 1, k});
                floors.add(v.pre[m]);
            }
            em.add(loc[be.tip]);
            for (Spine.Wire w : bo.wires) {
                int ia = B.node(w.a()), ig = B.node(w.g());
                ground.add(ig);
                bars.add(new int[]{ia, ig});
                double need = 1.6 * w.T() / c.SIG_CAB;
                double[] bs2 = new double[c.BS_W.length];
                for (int i = 0; i < bs2.length; i++) bs2[i] = c.BS_W[i] * c.BS_W[i];
                int wsI = Math.min(Consts.searchLeft(bs2, need), c.BS_W.length - 1);
                state.add(new int[]{3, wsI, 0, 2, k});
                floors.add(w.T());
            }
        }
        Tower t = new Tower();
        t.n = B.pts.size();
        t.P = new double[3 * t.n];
        for (int i = 0; i < t.n; i++) System.arraycopy(B.pts.get(i), 0, t.P, 3 * i, 3);
        List<Integer> pole = new ArrayList<>(poleNodes.keySet());
        pole.sort((a, b2) -> Double.compare(poleNodes.get(a), poleNodes.get(b2)));
        t.poleChain = new int[pole.size() + 1];
        t.pw = new double[pole.size() + 1];
        t.poleChain[0] = ghost;
        t.pw[0] = c.W_POLE_MIN;
        for (int i = 0; i < pole.size(); i++) {
            t.poleChain[i + 1] = pole.get(i);
            t.pw[i + 1] = Math.max(poleW.getOrDefault(pole.get(i), c.W_POLE_MIN), c.W_POLE_MIN);
        }
        t.ghost = ghost;
        t.fixed = new boolean[t.n];
        t.fixed[ghost] = true;
        for (int i = 0; i < t.n; i++) if (Math.abs(t.P[3 * i + 1]) < 1e-6) t.fixed[i] = true;
        // duplicates: keep the larger section (a strut beats a cable), carry the larger pretension floor
        int M = bars.size();
        double[] floor = new double[M];
        for (int i = 0; i < M; i++) floor[i] = floors.get(i);
        boolean[] keep = new boolean[M];
        Arrays.fill(keep, true);
        Map<Long, Integer> seen = new HashMap<>();
        double[] area = new double[M];
        for (int b = 0; b < M; b++) { int[] s = state.get(b); area[b] = sec.area(s[0], s[1], s[2]); }
        for (int b = 0; b < M; b++) {
            int i = bars.get(b)[0], j = bars.get(b)[1];
            if (i == j) { keep[b] = false; continue; }
            long kk = ((long) Math.min(i, j) << 32) | Math.max(i, j);
            Integer o = seen.get(kk);
            if (o == null) { seen.put(kk, b); continue; }
            double fl = Math.max(floor[o], floor[b]);
            int ko = state.get(o)[0], kb = state.get(b)[0];
            if (area[b] > area[o] || (ko % 2 == 1 && kb % 2 == 0)) {
                keep[o] = false; seen.put(kk, b); floor[b] = kb % 2 == 1 ? fl : 0;
            } else {
                keep[b] = false; floor[o] = ko % 2 == 1 ? fl : 0;
            }
        }
        int m = 0;
        for (boolean k : keep) if (k) m++;
        t.m = m;
        t.bi = new int[m]; t.bj = new int[m]; t.kind = new int[m]; t.ws = new int[m]; t.cs = new int[m];
        t.floor = new double[m]; t.ownerType = new int[m]; t.ownerIdx = new int[m]; t.pole = new boolean[m];
        int q2 = 0;
        for (int b = 0; b < M; b++) {
            if (!keep[b]) continue;
            int[] s = state.get(b);
            t.bi[q2] = bars.get(b)[0]; t.bj[q2] = bars.get(b)[1];
            t.kind[q2] = s[0]; t.ws[q2] = s[1]; t.cs[q2] = s[2]; t.ownerType[q2] = s[3]; t.ownerIdx[q2] = s[4];
            t.floor[q2] = floor[b];
            q2++;
        }
        java.util.Set<Long> pset = new java.util.HashSet<>();
        for (int i = 0; i + 1 < t.poleChain.length; i++) {
            int a = t.poleChain[i], b = t.poleChain[i + 1];
            pset.add(((long) Math.min(a, b) << 32) | Math.max(a, b));
        }
        for (int b = 0; b < m; b++) {
            long kk = ((long) Math.min(t.bi[b], t.bj[b]) << 32) | Math.max(t.bi[b], t.bj[b]);
            t.pole[b] = pset.contains(kk) && t.kind[b] % 2 == 0;
        }
        t.em = em.stream().mapToInt(Integer::intValue).toArray();
        t.ground = ground.stream().mapToInt(Integer::intValue).toArray();
        t.legs = legs;
        t.variants = vlist;
        t.boomAnchors = banch;
        return t;
    }

    private static boolean contains(int[] a, int x) {
        for (int v : a) if (v == x) return true;
        return false;
    }
}
