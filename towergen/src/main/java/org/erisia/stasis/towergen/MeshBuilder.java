package org.erisia.stasis.towergen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.erisia.stasis.towergen.VisualParams.Part;

/**
 * Tower mesh (after p25shots.geometry): solid struts as boxes (rust in compression, steel in tension), laced members
 * as chords + zigzag lacing + end battens, cables as thin boxes, back-stays to a concrete deadman with a brass
 * turnbuckle, insulator-stack emitters with a glow ring, brass pole collars, concrete footings and core. All
 * proportions come from {@link VisualParams}.
 *
 * <p>Joints are watertight by construction: every node with two or more members gets a hub, a cube oriented to the
 * widest member there and just large enough that every member's end face (corners p + e d +- a u +- a v, crossing
 * offsets included) fits inside it; each member then starts at the largest such e, so its end face is sealed in the
 * hub and the union has no notches or cracks at any member angle or width. A leg running straight through a node
 * makes a hub that is exactly a short piece of the leg. Laced chords converge into the node hub at a free end; where a
 * laced member continues into another one (along a leg) the chords are paired and each pair meets in a small hub of
 * its own. Members that cross without sharing a node (X-braces, crossing cables) are pushed apart along the common
 * normal so no faces are coplanar (no z-fighting).
 */
public final class MeshBuilder {
    private final Consts c;
    private final Sections sec;
    private final VisualParams vp;

    private static final int SKIP = -1, STRUT = 0, CABLE = 1, LACED = 2, POLE = 3;

    public MeshBuilder(Consts c, VisualParams vp) {
        this.c = c;
        this.sec = new Sections(c);
        this.vp = vp;
    }

    /** A member end (or a laced chord end) that a hub seals. */
    private static final class End {
        final double[] p;      // end point of the drawn axis (node + crossing offset, or the chord end)
        final double[] d;      // unit axis pointing away from the hub
        final double[] sd;     // drawn direction of the box (p0 -> p1), u, v = Mesh.frame(sd) as Mesh.beam uses
        final double[] u, v;
        final double a;        // half drawn width
        final double len;      // length of the drawn segment (bounds the extension)
        final boolean cable;
        final Part part;
        double e;              // the box starts at p + e d
        boolean sealed;

        End(double[] p, double[] d, double[] sd, double a, double len, boolean cable, Part part) {
            this.p = p; this.d = d; this.sd = sd; this.a = a; this.len = len; this.cable = cable; this.part = part;
            double[][] f = Mesh.frame(sd);
            u = f[0]; v = f[1];
        }
    }

    private static final class Hub {
        final double[] c;
        final List<End> ends = new ArrayList<>();
        int solid;             // non-cable ends
        double h;              // half-size
        double[] z;            // axis (drawn direction of the widest end)
        Part part;

        Hub(double[] c) { this.c = c; }

        void add(End e) { ends.add(e); if (!e.cable) solid++; }
    }

    /** Laced member geometry: section frame (canonical axis sign), chord directions and polylines. */
    private static final class Laced {
        double[] p0, dv;       // start node, p1 - p0
        double L, h, cw, b;
        int nc;
        double[][] dir;
        boolean tap0, tap1;
        double[][][] pts;      // [chord][point][xyz]
        double[] ts;           // member parameter of each polyline point
        double taper, endF;

        double off(double t) {
            if (taper <= 0 || (t < 0.5 ? !tap0 : !tap1)) return 1.0;
            double x = Math.min(t, 1 - t) / taper;
            if (x >= 1) return 1.0;
            double s = x * x * (3 - 2 * x);
            return endF + (1 - endF) * s;
        }

        double[] pt(double[] dr, double t) {
            double s = h * off(t);
            return new double[]{p0[0] + dv[0] * t + dr[0] * s, p0[1] + dv[1] * t + dr[1] * s, p0[2] + dv[2] * t + dr[2] * s};
        }

        /** Point of chord k's polyline at member parameter t (lacing and battens attach to the drawn chords). */
        double[] at(int k, double t) {
            int q = 0;
            while (q + 2 < ts.length && t > ts[q + 1]) q++;
            double f = (t - ts[q]) / (ts[q + 1] - ts[q]);
            double[] a = pts[k][q], b = pts[k][q + 1];
            return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
        }

        /** Chord end point at the start (end 0) or the end (end 1) without taper. */
        double[] rawEnd(int k, int end) { double s = h; double[] q = end == 0 ? p0 : V.add(p0, dv); return V.add(q, V.mul(dir[k], s)); }
    }

    public Mesh build(TowerGen.Result r) { return build(r.tower != null ? r.tower : r.raw, r.analysis); }

    /** an may be null (no force colouring: all struts steel). */
    public Mesh build(Stitch.Tower t, RuntimePass.Analysis an) {
        Mesh mesh = new Mesh();
        int n = t.n, m = t.m;
        double[] P = t.P;
        Set<Long> poleSeg = new HashSet<>();
        Set<Integer> poleNodes = new HashSet<>();
        for (int i = 1; i < t.poleChain.length; i++) poleNodes.add(t.poleChain[i]);
        for (int i = 1; i + 1 < t.poleChain.length; i++) poleSeg.add(pk(t.poleChain[i], t.poleChain[i + 1]));
        Set<Integer> ground = new HashSet<>();
        for (int g : t.ground) ground.add(g);

        // --- members: type, axis, drawn width, part
        int[] type = new int[m];
        double[][] ax = new double[m][];
        double[] len = new double[m], dw = new double[m];
        Part[] part = new Part[m];
        Laced[] lg = new Laced[m];
        int[] deg = new int[n];
        double[] nb = new double[n];           // largest connected drawn width (footings)
        List<List<Integer>> at = new ArrayList<>();
        for (int i = 0; i < n; i++) at.add(new ArrayList<>());
        for (int b = 0; b < m; b++) {
            int i = t.bi[b], j = t.bj[b], k = t.kind[b];
            double[] dv = V.sub(node(P, j), node(P, i));
            len[b] = V.norm(dv);
            if (len[b] < 1e-9) { type[b] = SKIP; continue; }
            ax[b] = V.mul(dv, 1 / len[b]);
            double w = sec.width(k, t.ws[b]);
            if (k == 1 || k == 3) {
                type[b] = CABLE;
                dw[b] = k == 1 ? Math.max(w * vp.cableDrawScale(), vp.cableDrawMin()) : Math.max(w, vp.backstayDrawMin());
                part[b] = k == 1 ? Part.CABLE : Part.BACKSTAY;
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
                type[b] = POLE;
                dw[b] = ww = Math.max(w, vp.poleMinWidth());
                part[b] = Part.POLE;
            } else if (k == 2) {
                type[b] = LACED;
                dw[b] = w;
                part[b] = col;
                lg[b] = lacedFrame(node(P, i), dv, w, sec.chord(k, t.ws[b], t.cs[b]));
                ww = lg[b].cw;
            } else {
                type[b] = STRUT;
                dw[b] = ww = Math.max(w, vp.strutMinWidth());
                part[b] = col;
            }
            at.get(i).add(b);
            at.get(j).add(b);
            for (int nd : new int[]{i, j}) { deg[nd]++; nb[nd] = Math.max(nb[nd], ww); }
        }

        // --- crossing offsets (z-fighting)
        double[][] off = crossOffsets(t, type, ax, len, dw);

        // --- laced continuation: the most collinear laced partner with the same chord count, if mutual
        int[] best = new int[2 * m], cont = new int[2 * m];
        Arrays.fill(best, -1);
        Arrays.fill(cont, -1);
        for (int b = 0; b < m; b++) {
            if (type[b] != LACED) continue;
            for (int e = 0; e < 2; e++) {
                int nd = e == 0 ? t.bi[b] : t.bj[b];
                double[] d = e == 0 ? ax[b] : V.mul(ax[b], -1);
                double bd = -0.98;
                for (int b2 : at.get(nd)) {
                    if (b2 == b || type[b2] != LACED || lg[b2].nc != lg[b].nc) continue;
                    double[] d2 = t.bi[b2] == nd ? ax[b2] : V.mul(ax[b2], -1);
                    double dd = V.dot(d, d2);
                    if (dd < bd - 1e-12) { bd = dd; best[2 * b + e] = b2; }
                }
            }
        }
        int[][] chordMap = new int[2 * m][];   // for the lower-index member of a continuing pair: partner chord per chord
        for (int b = 0; b < m; b++) {
            if (type[b] != LACED) continue;
            for (int e = 0; e < 2; e++) {
                int b2 = best[2 * b + e];
                if (b2 < 0) continue;
                int nd = e == 0 ? t.bi[b] : t.bj[b], e2 = t.bi[b2] == nd ? 0 : 1;
                if (best[2 * b2 + e2] != b) continue;
                if (b < b2) {
                    // pair chords by nearest end point; give up (taper both) unless it is one-to-one
                    int nc = lg[b].nc;
                    int[] map = new int[nc];
                    boolean[] used = new boolean[nc];
                    boolean ok = true;
                    for (int k = 0; k < nc && ok; k++) {
                        double[] pa = lg[b].rawEnd(k, e);
                        int bj = -1;
                        double bdist = 1e300;
                        for (int q = 0; q < nc; q++) {
                            double dd = dist2(pa, lg[b2].rawEnd(q, e2));
                            if (dd < bdist - 1e-15) { bdist = dd; bj = q; }
                        }
                        if (used[bj]) ok = false;
                        used[bj] = true;
                        map[k] = bj;
                    }
                    if (!ok) continue;
                    chordMap[2 * b + e] = map;
                    cont[2 * b + e] = b2;
                    cont[2 * b2 + e2] = b;
                }
            }
        }
        for (int b = 0; b < m; b++) {
            if (type[b] != LACED) continue;
            lg[b].tap0 = cont[2 * b] < 0;
            lg[b].tap1 = cont[2 * b + 1] < 0;
            chords(lg[b]);
        }

        // --- hubs: node hubs (node + offset / chord ends at free laced ends), chord-pair hubs (continuing laced)
        Hub[] nodeHub = new Hub[n];
        List<Hub> chordHubs = new ArrayList<>();
        End[] mEnd = new End[2 * m];           // solid / pole / cable member ends
        End[][] cEnd = new End[2 * m][];       // laced chord ends
        for (int b = 0; b < m; b++) {
            if (type[b] == SKIP) continue;
            for (int e = 0; e < 2; e++) {
                int nd = e == 0 ? t.bi[b] : t.bj[b];
                if (type[b] == LACED) {
                    if (cont[2 * b + e] >= 0) continue;
                    Laced g = lg[b];
                    cEnd[2 * b + e] = new End[g.nc];
                    for (int k = 0; k < g.nc; k++) {
                        End en = chordEnd(g, k, e, part[b]);
                        cEnd[2 * b + e][k] = en;
                        hub(nodeHub, nd, P).add(en);
                    }
                } else {
                    double[] p = V.add(node(P, nd), off[b]);
                    End en = new End(p, e == 0 ? ax[b] : V.mul(ax[b], -1), ax[b], dw[b] / 2, len[b], type[b] == CABLE, part[b]);
                    mEnd[2 * b + e] = en;
                    hub(nodeHub, nd, P).add(en);
                }
            }
        }
        for (int b = 0; b < m; b++) {
            if (type[b] != LACED) continue;
            for (int e = 0; e < 2; e++) {
                int b2 = cont[2 * b + e];
                if (b2 < b) continue;
                int nd = e == 0 ? t.bi[b] : t.bj[b], e2 = t.bi[b2] == nd ? 0 : 1;
                int nc = lg[b].nc;
                cEnd[2 * b + e] = new End[nc];
                if (cEnd[2 * b2 + e2] == null) cEnd[2 * b2 + e2] = new End[nc];
                for (int k = 0; k < nc; k++) {
                    int q = chordMap[2 * b + e][k];
                    // both chords end at the midpoint of their raw end points: the leg's chords run on through the
                    // node (a slight kink where the sections differ) and meet in a chord-sized splice hub
                    double[][] pa = lg[b].pts[k], pb = lg[b2].pts[q];
                    int ia = e == 0 ? 0 : pa.length - 1, ib = e2 == 0 ? 0 : pb.length - 1;
                    double[] mid = V.mul(V.add(pa[ia], pb[ib]), 0.5);
                    pa[ia] = mid;
                    pb[ib] = mid.clone();
                    End ea = chordEnd(lg[b], k, e, part[b]), eb = chordEnd(lg[b2], q, e2, part[b2]);
                    Hub hb = new Hub(V.mul(V.add(ea.p, eb.p), 0.5));
                    hb.add(ea);
                    hb.add(eb);
                    cEnd[2 * b + e][k] = ea;
                    cEnd[2 * b2 + e2][q] = eb;
                    chordHubs.add(hb);
                }
            }
        }
        for (int nd = 0; nd < n; nd++) if (nodeHub[nd] != null && nodeHub[nd].solid >= 2) solve(nodeHub[nd]);
        for (Hub hb : chordHubs) { solve(hb); hb.part = hb.ends.get(0).part; }   // chord splices: a piece of chord

        // --- members
        for (int b = 0; b < m; b++) {
            if (type[b] == SKIP) continue;
            if (type[b] == LACED) {
                drawLaced(mesh, lg[b], part[b], cEnd[2 * b], cEnd[2 * b + 1], cont[2 * b] >= 0 && cont[2 * b] < b,
                        cont[2 * b + 1] >= 0 && cont[2 * b + 1] < b);
                continue;
            }
            End e0 = mEnd[2 * b], e1 = mEnd[2 * b + 1];
            double t0 = e0.sealed ? e0.e : 0, t1 = e1.sealed ? e1.e : 0;
            if (len[b] - t0 - t1 < 1e-6) continue;
            double[] p0 = V.add(e0.p, V.mul(ax[b], t0)), p1 = V.sub(e1.p, V.mul(ax[b], t1));
            mesh.beam(p0, p1, dw[b], dw[b], part[b], !e0.sealed, !e1.sealed);
        }
        // --- hubs
        for (int nd = 0; nd < n; nd++) if (nodeHub[nd] != null && nodeHub[nd].h > 0) drawHub(mesh, nodeHub[nd]);
        for (Hub hb : chordHubs) drawHub(mesh, hb);

        // pole collars (brass), sized from the pole width
        for (int q = 2; q + 1 < t.poleChain.length; q++) {
            int nd = t.poleChain[q];
            double w = vp.poleCollarScale() * Math.max(t.pw[q], vp.poleMinWidth()) + vp.poleCollarAdd();
            double[] p = node(P, nd);
            mesh.block(p, p[1] - vp.poleCollarHeight() / 2, p[1] + vp.poleCollarHeight() / 2, w, Part.BRASS);
        }
        // footings (not under the pole or back-stay anchors)
        for (int nd = 0; nd < n; nd++) {
            if (Math.abs(P[3 * nd + 1]) < 1e-6 && deg[nd] > 0 && !poleNodes.contains(nd) && !ground.contains(nd)) {
                double[] p = node(P, nd);
                mesh.block(p, -0.05, -0.05 + vp.footingHeight(), Math.max(vp.footingMin(), nb[nd] * vp.footingScale()), Part.FOOTING);
            }
        }
        // back-stay deadmen + turnbuckles
        for (int g : t.ground) {                  // array order (not hash order): deterministic triangle order
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

    private static Hub hub(Hub[] hubs, int nd, double[] P) {
        if (hubs[nd] == null) hubs[nd] = new Hub(node(P, nd));
        return hubs[nd];
    }

    /**
     * Hub size and member extensions. In the frame (X, Y, Z) of the widest end, the end face of end i at extension e
     * lies in the cube of half-size h iff for every axis k: |delta_k + e d_k| + a (|u_k| + |v_k|) <= h (delta = end
     * point - hub centre). h is the smallest size that holds at e = 0 for all ends (times hubScale, plus hubAdd);
     * each end then gets the largest e that still holds, at most hubMaxExtendFrac x its length.
     */
    private void solve(Hub hb) {
        End w = null;
        for (End en : hb.ends) if (!en.cable && (w == null || en.a > w.a + 1e-12)) w = en;
        double[][] B = {w.u, w.v, w.sd};
        int ne = hb.ends.size();
        double[][] dl = new double[ne][3], s = new double[ne][3];
        double need = 0;
        for (int i = 0; i < ne; i++) {
            End en = hb.ends.get(i);
            double[] r = V.sub(en.p, hb.c);
            for (int k = 0; k < 3; k++) {
                dl[i][k] = V.dot(r, B[k]);
                s[i][k] = Math.abs(V.dot(en.u, B[k])) + Math.abs(V.dot(en.v, B[k]));
                need = Math.max(need, Math.abs(dl[i][k]) + en.a * s[i][k]);
            }
        }
        double h = Math.max(need, vp.hubScale() * need + vp.hubAdd());
        double maxF = Math.max(0, Math.min(0.49, vp.hubMaxExtendFrac()));
        for (int i = 0; i < ne; i++) {
            End en = hb.ends.get(i);
            double eh = Double.MAX_VALUE;
            for (int k = 0; k < 3; k++) {
                double dk = V.dot(en.d, B[k]);
                if (Math.abs(dk) < 1e-9) continue;
                double r = h - en.a * s[i][k];
                eh = Math.min(eh, Math.max((r - dl[i][k]) / dk, (-r - dl[i][k]) / dk));
            }
            en.e = Math.max(0, Math.min(eh, maxF * en.len));
            en.sealed = true;
        }
        hb.h = h;
        hb.z = w.sd;
        hb.part = vp.hubMemberColour() ? w.part : Part.KNUCKLE;
    }

    private static void drawHub(Mesh mesh, Hub hb) {
        mesh.beam(V.sub(hb.c, V.mul(hb.z, hb.h)), V.add(hb.c, V.mul(hb.z, hb.h)), 2 * hb.h, 2 * hb.h, hb.part, true, true);
    }

    /**
     * Per-member offsets so members that cross without sharing a node have no coplanar faces: each pair whose axes
     * pass closer than target = crossOffsetFrac x (w1 + w2) (closest points inside both members, axes at least
     * crossMinAngle apart) is pushed apart to target along unit(d1 x d2), each member in proportion to the other's
     * width (a thin cable moves rather than a fat strut), keeping the side it is already on (exactly coplanar: the
     * higher index goes +n). Pairs come from a sweep over y-sorted padded boxes and are
     * processed in (i, j) order with the offsets so far. Struts and cables only (not laced members or the pole).
     */
    private double[][] crossOffsets(Stitch.Tower t, int[] type, double[][] ax, double[] len, double[] dw) {
        int m = t.m;
        double[][] off = new double[m][3];
        double frac = vp.crossOffsetFrac();
        if (frac <= 0) return off;
        double sinMin = Math.sin(Math.toRadians(Math.max(0, Math.min(89, vp.crossMinAngle()))));
        double[] P = t.P;
        int cnt = 0;
        Integer[] ord = new Integer[m];
        double[] lo = new double[3 * m], hi = new double[3 * m];
        for (int b = 0; b < m; b++) {
            if (type[b] != STRUT && type[b] != CABLE) continue;
            double pad = frac * dw[b];
            for (int q = 0; q < 3; q++) {
                double x0 = P[3 * t.bi[b] + q], x1 = P[3 * t.bj[b] + q];
                lo[3 * b + q] = Math.min(x0, x1) - pad;
                hi[3 * b + q] = Math.max(x0, x1) + pad;
            }
            ord[cnt++] = b;
        }
        Integer[] o = Arrays.copyOf(ord, cnt);
        Arrays.sort(o, (x, y) -> lo[3 * x + 1] != lo[3 * y + 1] ? Double.compare(lo[3 * x + 1], lo[3 * y + 1]) : Integer.compare(x, y));
        long[] pairs = new long[64];
        int np = 0;
        for (int a = 0; a < cnt; a++) {
            int i = o[a];
            for (int c2 = a + 1; c2 < cnt; c2++) {
                int j = o[c2];
                if (lo[3 * j + 1] > hi[3 * i + 1]) break;
                if (lo[3 * j] > hi[3 * i] || lo[3 * i] > hi[3 * j] || lo[3 * j + 2] > hi[3 * i + 2] || lo[3 * i + 2] > hi[3 * j + 2]) continue;
                if (t.bi[i] == t.bi[j] || t.bi[i] == t.bj[j] || t.bj[i] == t.bi[j] || t.bj[i] == t.bj[j]) continue;
                if (np == pairs.length) pairs = Arrays.copyOf(pairs, 2 * np);
                pairs[np++] = ((long) Math.min(i, j) << 32) | Math.max(i, j);
            }
        }
        Arrays.sort(pairs, 0, np);
        for (int q = 0; q < np; q++) {
            int i = (int) (pairs[q] >>> 32), j = (int) (pairs[q] & 0xffffffffL);
            double[] cr = V.cross(ax[i], ax[j]);
            double cn = V.norm(cr);
            if (cn < sinMin || cn < 1e-9) continue;
            double[] nrm = V.mul(cr, 1 / cn);
            double[] p1 = V.add(node(P, t.bi[i]), off[i]), p2 = V.add(node(P, t.bi[j]), off[j]);
            double[] e1 = V.mul(ax[i], len[i]), e2 = V.mul(ax[j], len[j]);
            double[] w0 = V.sub(p1, p2);
            double A = V.dot(e1, e1), Bq = V.dot(e1, e2), C = V.dot(e2, e2), D = V.dot(e1, w0), E = V.dot(e2, w0);
            double den = A * C - Bq * Bq;
            if (den <= 1e-12 * A * C) continue;
            double sp = (Bq * E - C * D) / den, tp = (A * E - Bq * D) / den;
            if (sp <= 1e-3 || sp >= 1 - 1e-3 || tp <= 1e-3 || tp >= 1 - 1e-3) continue;
            double sep = -V.dot(w0, nrm);              // (p2 - p1) . n
            double target = frac * (dw[i] + dw[j]);
            if (Math.abs(sep) >= target) continue;
            double push = (target - Math.abs(sep)) / (dw[i] + dw[j]) * (sep < 0 ? -1 : 1);   // the thinner moves more
            for (int k = 0; k < 3; k++) { off[j][k] += push * dw[i] * nrm[k]; off[i][k] -= push * dw[j] * nrm[k]; }
        }
        return off;
    }

    private static long pk(int a, int b) { return ((long) Math.min(a, b) << 32) | Math.max(a, b); }

    private static double[] node(double[] P, int i) { return new double[]{P[3 * i], P[3 * i + 1], P[3 * i + 2]}; }

    private static double dist2(double[] a, double[] b) { double[] d = V.sub(a, b); return V.dot(d, d); }

    /** Laced member section: frame of the axis with a canonical sign (so continuing members' chords line up). */
    private Laced lacedFrame(double[] p0, double[] dv, double b, double cw) {
        Laced g = new Laced();
        g.p0 = p0;
        g.dv = dv;
        g.L = V.norm(dv);
        g.b = b;
        g.cw = cw;
        double[] a = V.mul(dv, 1 / g.L);
        boolean flip = Math.abs(a[1]) > 1e-6 ? a[1] < 0 : Math.abs(a[0]) > 1e-6 ? a[0] < 0 : a[2] < 0;
        double[][] f = Mesh.frame(flip ? V.mul(a, -1) : a);
        double[] u = f[0], v = f[1];
        g.nc = Sections.nchords(b);
        g.dir = new double[g.nc][];
        if (g.nc == 4) {
            g.h = (b - cw) / 2 * Math.sqrt(2);
            double s = Math.sqrt(0.5);
            double[][] sg = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};
            for (int k = 0; k < 4; k++) g.dir[k] = V.add(V.mul(u, s * sg[k][0]), V.mul(v, s * sg[k][1]));
        } else {
            g.h = (b - cw) / Math.sqrt(3);
            double[] th = {Math.PI / 2, Math.PI * 7 / 6, Math.PI * 11 / 6};
            for (int k = 0; k < 3; k++) g.dir[k] = V.add(V.mul(u, Math.cos(th[k])), V.mul(v, Math.sin(th[k])));
        }
        g.taper = Math.max(0, Math.min(0.45, vp.lacedEndTaper()));
        g.endF = vp.lacedEndFrac();
        return g;
    }

    /** Chord polylines through the taper breakpoints (chords converge only at free ends). */
    private static void chords(Laced g) {
        List<Double> ts = new ArrayList<>(List.of(0.0));
        if (g.taper > 0 && g.tap0) { ts.add(g.taper / 2); ts.add(g.taper); }
        if (g.taper > 0 && g.tap1) { ts.add(1 - g.taper); ts.add(1 - g.taper / 2); }
        ts.add(1.0);
        g.ts = new double[ts.size()];
        for (int q = 0; q < ts.size(); q++) g.ts[q] = ts.get(q);
        g.pts = new double[g.nc][ts.size()][];
        for (int k = 0; k < g.nc; k++) for (int q = 0; q < ts.size(); q++) g.pts[k][q] = g.pt(g.dir[k], g.ts[q]);
    }

    /** End of chord k at member end e as a hub end. */
    private static End chordEnd(Laced g, int k, int e, Part part) {
        double[][] pp = g.pts[k];
        int last = pp.length - 1;
        double[] p = e == 0 ? pp[0] : pp[last], q = e == 0 ? pp[1] : pp[last - 1];
        double[] dq = V.sub(q, p);
        double l = V.norm(dq);
        double[] d = V.mul(dq, 1 / l);
        return new End(p, d, e == 0 ? d : V.mul(d, -1), g.cw / 2, l, false, part);
    }

    /**
     * Laced member: chords (ends trimmed into their hubs, bends mitred: each segment runs on by a sqrt2 tan(theta/2)
     * so the outside of the bend has no notch), zigzag lacing and end battens (the batten at a continuing end is drawn
     * by the lower-index member only: skip0 / skip1).
     */
    private void drawLaced(Mesh mesh, Laced g, Part col, End[] end0, End[] end1, boolean skip0, boolean skip1) {
        double cw = g.cw;
        for (int k = 0; k < g.nc; k++) {
            double[][] pp = g.pts[k];
            int ns = pp.length - 1;
            double[] prev = null;
            for (int q = 0; q < ns; q++) {
                double[] a0 = pp[q], a1 = pp[q + 1];
                double[] dq = V.sub(a1, a0);
                double l = V.norm(dq);
                if (l < 1e-9) continue;
                double[] sd = V.mul(dq, 1 / l);
                double x0 = 0, x1 = 0;
                if (prev != null) x0 = -miter(prev, sd, cw);
                if (q + 1 < ns) {
                    double[] nq = V.sub(pp[q + 2], a1);
                    double nl = V.norm(nq);
                    if (nl > 1e-9) x1 = -miter(sd, V.mul(nq, 1 / nl), cw);
                }
                boolean c0 = true, c1 = true;
                if (q == 0 && end0 != null && end0[k] != null && end0[k].sealed) { x0 = end0[k].e; c0 = false; }
                if (q == ns - 1 && end1 != null && end1[k] != null && end1[k].sealed) { x1 = end1[k].e; c1 = false; }
                if (l - x0 - x1 > 1e-6) mesh.beam(V.add(a0, V.mul(sd, x0)), V.sub(a1, V.mul(sd, x1)), cw, cw, col, c0, c1);
                prev = sd;
            }
        }
        double lw = Math.max(vp.lacingMinWidth(), vp.lacingWidthFrac() * cw);
        int npan = (int) Math.max(vp.lacingPanelsMin(), Math.min(vp.lacingPanelsMax(), Math.rint(g.L / Math.max(g.b * vp.lacingPanelPerWidth(), 0.3))));
        for (int fc = 0; fc < g.nc; fc++) {
            int oa = fc, ob = (fc + 1) % g.nc;
            for (int k = 0; k < npan; k++) {
                double t0 = k / (double) npan, t1 = (k + 1) / (double) npan;
                mesh.beam(g.at(k % 2 == 0 ? oa : ob, t0), g.at(k % 2 == 0 ? ob : oa, t1), lw, Part.LACING);
            }
            if (!skip0) mesh.beam(g.at(oa, 0), g.at(ob, 0), lw * vp.battenScale(), Part.LACING);
            if (!skip1) mesh.beam(g.at(oa, 1), g.at(ob, 1), lw * vp.battenScale(), Part.LACING);
        }
    }

    /** Run-on at a bend from unit d1 to unit d2 for a square section of width w: (w/2) sqrt2 tan(theta/2). */
    private static double miter(double[] d1, double[] d2, double w) {
        double cs = Math.max(-1, Math.min(1, V.dot(d1, d2)));
        if (cs > 1 - 1e-12) return 0;
        return w / 2 * Math.sqrt(2) * Math.sqrt((1 - cs) / (1 + cs));
    }

    /** Arrays.toString helper for debugging. */
    static String s(double[] a) { return Arrays.toString(a); }
}
