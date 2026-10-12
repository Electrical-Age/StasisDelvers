package org.erisia.stasis.towergen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.erisia.stasis.towergen.VisualParams.Part;

/**
 * Tower mesh (after p25shots.geometry): solid struts as boxes (rust in compression, steel in tension), laced members
 * as chords + zigzag lacing + end battens with chords converging at the ends, cables as thin boxes, back-stays to a
 * concrete deadman with a brass turnbuckle, insulator-stack emitters with a glow ring, brass pole collars, concrete
 * footings and core. Joints taper: a node block sized from the connected members (capped relative to the second
 * largest so one fat leg doesn't make a blob on every thin brace), plus a tapered stub from the block into each strut.
 * All proportions come from {@link VisualParams}.
 */
public final class MeshBuilder {
    private final Consts c;
    private final Sections sec;
    private final VisualParams vp;

    public MeshBuilder(Consts c, VisualParams vp) {
        this.c = c;
        this.sec = new Sections(c);
        this.vp = vp;
    }

    public Mesh build(TowerGen.Result r) { return build(r.tower != null ? r.tower : r.raw, r.analysis); }

    /** an may be null (no force colouring: all struts steel). */
    public Mesh build(Stitch.Tower t, RuntimePass.Analysis an) {
        Mesh mesh = new Mesh();
        int n = t.n;
        double[] P = t.P;
        Set<Long> poleSeg = new HashSet<>();
        Set<Integer> poleNodes = new HashSet<>();
        for (int i = 1; i < t.poleChain.length; i++) poleNodes.add(t.poleChain[i]);
        for (int i = 1; i + 1 < t.poleChain.length; i++) poleSeg.add(pk(t.poleChain[i], t.poleChain[i + 1]));
        Set<Integer> ground = new HashSet<>();
        for (int g : t.ground) ground.add(g);
        int[] deg = new int[n];
        double[] nb = new double[n];          // largest connected drawn width
        double[] nb2 = new double[n];         // second largest
        double[] endW = new double[t.m];       // drawn width at the member ends (for stubs)
        for (int b = 0; b < t.m; b++) {
            int i = t.bi[b], j = t.bj[b], k = t.kind[b];
            double[] pi = node(P, i), pj = node(P, j);
            double w = sec.width(k, t.ws[b]);
            if (k == 1) {
                mesh.beam(pi, pj, Math.max(w * vp.cableDrawScale(), vp.cableDrawMin()), Part.CABLE);
                continue;
            }
            if (k == 3) {
                mesh.beam(pi, pj, Math.max(w, vp.backstayDrawMin()), Part.BACKSTAY);
                continue;
            }
            Part col = Part.STRUT_T;
            if (an != null) {
                int kk = 0;
                double best = -1;
                for (int q = 0; q < an.dN.length; q++) {
                    double v = Math.abs(an.N0[b] + an.dN[q][b]);
                    if (v > best) { best = v; kk = q; }
                }
                col = an.N0[b] + an.dN[kk][b] > 0 ? Part.STRUT_T : Part.STRUT_C;
            }
            double ww;
            if (poleSeg.contains(pk(i, j))) {
                ww = Math.max(w, vp.poleMinWidth());
                mesh.beam(pi, pj, ww, Part.POLE);
            } else if (k == 2) {
                ww = laced(mesh, pi, pj, w, sec.chord(k, t.ws[b], t.cs[b]), col);
            } else {
                ww = Math.max(w, vp.strutMinWidth());
                mesh.beam(pi, pj, ww, col);
            }
            endW[b] = ww;
            for (int nd : new int[]{i, j}) {
                deg[nd]++;
                if (ww > nb[nd]) { nb2[nd] = nb[nd]; nb[nd] = ww; } else if (ww > nb2[nd]) nb2[nd] = ww;
            }
        }
        // pole collars (brass), sized from the pole width
        for (int q = 2; q + 1 < t.poleChain.length; q++) {
            int nd = t.poleChain[q];
            double w = vp.poleCollarScale() * Math.max(t.pw[q], vp.poleMinWidth()) + vp.poleCollarAdd();
            double[] p = node(P, nd);
            mesh.block(p, p[1] - vp.poleCollarHeight() / 2, p[1] + vp.poleCollarHeight() / 2, w, Part.BRASS);
        }
        // knuckles: node block + tapered stubs into the struts
        double[] ks = new double[n];
        for (int nd = 0; nd < n; nd++) {
            if (deg[nd] < 2 || P[3 * nd + 1] <= 0.01 || poleNodes.contains(nd)) continue;
            double s = vp.knuckleScale() * nb[nd] + vp.knuckleAdd();
            if (nb2[nd] > 0) s = Math.min(s, vp.knuckleMaxRatio() * nb2[nd] + vp.knuckleAdd());
            ks[nd] = s;
            double[] p = node(P, nd);
            mesh.block(p, p[1] - s / 2, p[1] + s / 2, s, Part.KNUCKLE);
        }
        if (vp.knuckleStubs()) {
            for (int b = 0; b < t.m; b++) {
                if (endW[b] <= 0 || poleSeg.contains(pk(t.bi[b], t.bj[b]))) continue;
                double L = t.length(b);
                for (int e = 0; e < 2; e++) {
                    int nd = e == 0 ? t.bi[b] : t.bj[b], other = e == 0 ? t.bj[b] : t.bi[b];
                    double s = ks[nd];
                    if (s <= 0) continue;
                    double w0 = Math.min(vp.knuckleTaperStart() * s, s);
                    double w1 = endW[b];
                    if (w0 <= w1 * 1.02) continue;          // member as wide as the block: no taper needed
                    double len = Math.min(vp.knuckleTaperLength() * s, 0.35 * L);
                    double[] p0 = node(P, nd), d = V.unit(V.sub(node(P, other), p0));
                    mesh.beam(p0, V.add(p0, V.mul(d, len)), w0, w1, Part.KNUCKLE);
                }
            }
        }
        // footings (not under the pole or back-stay anchors)
        for (int nd = 0; nd < n; nd++) {
            if (Math.abs(P[3 * nd + 1]) < 1e-6 && deg[nd] > 0 && !poleNodes.contains(nd) && !ground.contains(nd)) {
                double[] p = node(P, nd);
                mesh.block(p, -0.05, -0.05 + vp.footingHeight(), Math.max(vp.footingMin(), nb[nd] * vp.footingScale()), Part.FOOTING);
            }
        }
        // back-stay deadmen + turnbuckles
        for (int g : ground) {
            double[] p = node(P, g);
            double h = vp.deadmanHeight();
            mesh.block(p, p[1] - 0.45 * h, p[1] + 0.55 * h, vp.deadmanSize(), Part.DEADMAN);
            mesh.block(p, p[1] + 0.55 * h, p[1] + 0.55 * h + vp.turnbuckleLength(), vp.turnbuckleWidth(), Part.BRASS);
        }
        if (vp.drawCore()) {
            double[] o = {0, 0, 0};
            mesh.block(o, 0, vp.coreHeight(), vp.coreWidth(), Part.CORE);
            mesh.block(o, vp.coreHeight(), vp.coreHeight() + 0.25, 0.4 * vp.coreWidth(), Part.GLOW);
        }
        // emitters: porcelain insulator stack, glow spike and ring
        double es = vp.emitterScale();
        double[] up = {0, 1, 0};
        for (int e : t.em) {
            double[] p = node(P, e);
            for (int k = 0; k < 3; k++)
                mesh.prism(V.add(p, new double[]{0, es * (-0.18 - 0.13 * k), 0}), up, es * (0.22 - 0.03 * k), es * 0.06, 12, Part.PORCELAIN);
            mesh.block(p, p[1] - es * 0.15, p[1] + es * 0.35, es * 0.32, Part.GLOW);
            double[] prev = null;
            for (int k = 0; k <= 12; k++) {
                double a = 2 * Math.PI * k / 12;
                double[] q = {p[0] + es * 0.42 * Math.cos(a), p[1] + es * 0.12, p[2] + es * 0.42 * Math.sin(a)};
                if (prev != null) mesh.beam(prev, q, es * 0.08, Part.GLOW);
                prev = q;
            }
        }
        return mesh;
    }

    private static long pk(int a, int b) { return ((long) Math.min(a, b) << 32) | Math.max(a, b); }

    private static double[] node(double[] P, int i) { return new double[]{P[3 * i], P[3 * i + 1], P[3 * i + 2]}; }

    /** Laced member; returns the drawn width at its ends (for the knuckles). */
    double laced(Mesh mesh, double[] p0, double[] p1, double b, double cw, Part col) {
        double[] d = V.sub(p1, p0);
        double L = V.norm(d);
        double[] a = V.mul(d, 1 / L);
        double[][] f = Mesh.frame(a);
        double[] u = f[0], v = f[1];
        int nc = Sections.nchords(b);
        double[][] dir = new double[nc][];
        double h;
        if (nc == 4) {
            h = (b - cw) / 2 * Math.sqrt(2);
            double s = Math.sqrt(0.5);
            double[][] sg = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};
            for (int k = 0; k < 4; k++) dir[k] = V.add(V.mul(u, s * sg[k][0]), V.mul(v, s * sg[k][1]));
        } else {
            h = (b - cw) / Math.sqrt(3);
            double[] th = {Math.PI / 2, Math.PI * 7 / 6, Math.PI * 11 / 6};
            for (int k = 0; k < 3; k++) dir[k] = V.add(V.mul(u, Math.cos(th[k])), V.mul(v, Math.sin(th[k])));
        }
        double taper = Math.max(0, Math.min(0.45, vp.lacedEndTaper()));
        double endF = vp.lacedEndFrac();
        // offset scale along the member: endF at the ends, 1 in the middle
        java.util.function.DoubleUnaryOperator off = t -> {
            if (taper <= 0) return 1.0;
            double x = Math.min(t, 1 - t) / taper;
            if (x >= 1) return 1.0;
            double s = x * x * (3 - 2 * x);
            return endF + (1 - endF) * s;
        };
        // chords as polylines through the taper breakpoints
        List<Double> ts = new ArrayList<>(List.of(0.0));
        if (taper > 0) { ts.add(taper / 2); ts.add(taper); ts.add(1 - taper); ts.add(1 - taper / 2); }
        ts.add(1.0);
        for (int k = 0; k < nc; k++) {
            for (int q = 0; q + 1 < ts.size(); q++) {
                double t0 = ts.get(q), t1 = ts.get(q + 1);
                mesh.beam(pt(p0, d, dir[k], h, t0, off), pt(p0, d, dir[k], h, t1, off), cw, col);
            }
        }
        double lw = Math.max(vp.lacingMinWidth(), vp.lacingWidthFrac() * cw);
        int npan = (int) Math.max(vp.lacingPanelsMin(), Math.min(vp.lacingPanelsMax(), Math.rint(L / Math.max(b * vp.lacingPanelPerWidth(), 0.3))));
        for (int fc = 0; fc < nc; fc++) {
            double[] oa = dir[fc], ob = dir[(fc + 1) % nc];
            for (int k = 0; k < npan; k++) {
                double t0 = k / (double) npan, t1 = (k + 1) / (double) npan;
                double[] x0 = pt(p0, d, k % 2 == 0 ? oa : ob, h, t0, off);
                double[] x1 = pt(p0, d, k % 2 == 0 ? ob : oa, h, t1, off);
                mesh.beam(x0, x1, lw, Part.LACING);
            }
            for (double t : new double[]{0.0, 1.0})
                mesh.beam(pt(p0, d, oa, h, t, off), pt(p0, d, ob, h, t, off), lw * vp.battenScale(), Part.LACING);
        }
        return 2 * h * off.applyAsDouble(0) / (nc == 4 ? Math.sqrt(2) : 1) + cw;
    }

    private static double[] pt(double[] p0, double[] d, double[] dir, double h, double t,
                               java.util.function.DoubleUnaryOperator off) {
        double s = h * off.applyAsDouble(t);
        return new double[]{p0[0] + d[0] * t + dir[0] * s, p0[1] + d[1] * t + dir[1] * s, p0[2] + d[2] * t + dir[2] * s};
    }

    /** Arrays.toString helper for debugging. */
    static String s(double[] a) { return Arrays.toString(a); }
}
