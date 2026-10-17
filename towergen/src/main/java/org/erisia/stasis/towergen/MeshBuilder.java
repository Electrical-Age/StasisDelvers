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
 * <p>Joints (the lattice-CAD scheme): at every node with two or more solid member ends, each member is cut back by its
 * setback, the distance along it at which its square section (from the node, crossing offset included) has left every
 * other member's prism there (exact prism-prism intersection, {@link #clearance}), plus jointMargin x its width; at
 * most jointMaxSetbackFrac x its length and jointMaxSetback x the widest member there (nearly parallel members would
 * otherwise push each other far out: they overlap instead). The hub is the convex hull ({@link Hull3}) of all the cut faces' corners, so
 * members run from hull face to hull face, every open member end lies inside the hull (watertight) and the hub never
 * reaches beyond the members' own envelopes except for the small fill between them. Collinear members meet flush (the
 * hull is a thin prism of the section, or a flat polygon at margin 0). If the hull still covers a member beyond its
 * cut (a wide neighbour), the member is cut where its edges leave the hull and the hull rebuilt (no coplanar faces).
 * Cables do not size hubs: they end where they leave the hull (or are added to it when a crossing offset moved their
 * end outside). Laced chords at a free end are ordinary members of the node joint; where a laced member continues
 * into another (along a leg) each chord pair gets a two-member hull joint (flush when collinear). A laced member ending
 * at a branch joins its node's hull by its envelope (a gusset, gussetLip wider than its chords); its chords' open ends
 * sink into the gusset (chordSink) and its end batten stands just off the gusset face. Brace hubs inside a continuing
 * laced leg take the widest brace's part (not the dark knuckle colour). Members that cross
 * without sharing a node (X-braces, crossing cables) are pushed apart along the common normal so no faces are
 * coplanar (no z-fighting).
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
        double[][] pl, vt;     // prism planes / vertices (clearance), computed on demand
        boolean envelope;      // a laced member's outer section (not drawn; its chords are cut at e)
        double vis = -1;       // chord ends at a gusset: the visible cut (the gusset face; e is sunk below it)

        End(double[] p, double[] d, double[] sd, double a, double len, boolean cable, Part part) {
            this.p = p; this.d = d; this.sd = sd; this.a = a; this.len = len; this.cable = cable; this.part = part;
            double[][] f = Mesh.frame(sd);
            u = f[0]; v = f[1];
        }
    }

    /** The member ends meeting at a node (or a chord pair along a laced leg) and their hull hub. */
    private static final class Joint {
        final List<End> ends = new ArrayList<>();
        final List<double[]> env = new ArrayList<>();       // outer envelopes of laced members ending here (tests)
        Hull3 hull;
        Part part;
        JointInfo info;                                     // tests
        boolean inLeg;                                      // a node inside a continuing laced leg

        void add(End e) { ends.add(e); }
    }

    /** Test hook: one joint's hull and the prisms of its member ends. */
    static final class JointInfo {
        Hull3 hull;
        final List<double[]> boxes = new ArrayList<>();     // p(3) d(3) u(3) v(3) a len
        final List<double[][]> sealed = new ArrayList<>();  // cut-face corners of the sealed ends
        final List<double[][]> chordEnds = new ArrayList<>(); // gusset joints: corners of the chords' (sunk) open ends
        final List<double[]> chordSink = new ArrayList<>();   // ... and per chord {sink, chord width, cut, envelope cut}
        double maxW;
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
    public Mesh build(Stitch.Tower t, RuntimePass.Analysis an) { return build(t, an, null); }

    /** As {@link #build(Stitch.Tower, RuntimePass.Analysis)}; sink (may be null) receives every joint (tests). */
    Mesh build(Stitch.Tower t, RuntimePass.Analysis an, List<JointInfo> sink) { return build(t, an, sink, null); }

    /** Test hook: one laced member's frame, chord polylines and continuation partners (-1: free end). */
    static final class LacedInfo {
        int member, cont0, cont1;
        double[] p0, dv;
        double b;
        double[][][] pts;
    }

    Mesh build(Stitch.Tower t, RuntimePass.Analysis an, List<JointInfo> sink, List<LacedInfo> lacedSink) {
        Mesh mesh = new Mesh();
        int n = t.n, m = t.m;
        double[] P = t.P;
        Set<Long> poleSeg = new HashSet<>();
        Set<Integer> poleNodes = new HashSet<>();
        int pc0 = t.poleChain.length > 0 && t.poleChain[0] == t.ghost ? 1 : 0;    // skip the ghost clamp, if any
        for (int i = pc0; i < t.poleChain.length; i++) poleNodes.add(t.poleChain[i]);
        for (int i = pc0; i + 1 < t.poleChain.length; i++) poleSeg.add(pk(t.poleChain[i], t.poleChain[i + 1]));
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
            Part col = an != null ? forceColour(an, b) : Part.STRUT_T;
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

        // --- laced continuation: the most collinear laced partner (within CONT_DOT, any section / chord count), if mutual
        int[] best = new int[2 * m], cont = new int[2 * m];
        Arrays.fill(best, -1);
        Arrays.fill(cont, -1);
        for (int b = 0; b < m; b++) {
            if (type[b] != LACED) continue;
            for (int e = 0; e < 2; e++) {
                int nd = e == 0 ? t.bi[b] : t.bj[b];
                double[] d = e == 0 ? ax[b] : V.mul(ax[b], -1);
                double bd = CONT_DOT;
                for (int b2 : at.get(nd)) {
                    if (b2 == b || type[b2] != LACED) continue;
                    double[] d2 = t.bi[b2] == nd ? ax[b2] : V.mul(ax[b2], -1);
                    double dd = V.dot(d, d2);
                    if (dd < bd - 1e-12) { bd = dd; best[2 * b + e] = b2; }
                }
            }
        }
        int[][] chordGrp = new int[2 * m][];   // for the lower-index member of a continuing pair: splice pair per chord
        for (int b = 0; b < m; b++) {
            if (type[b] != LACED) continue;
            for (int e = 0; e < 2; e++) {
                int b2 = best[2 * b + e];
                if (b2 < 0) continue;
                int nd = e == 0 ? t.bi[b] : t.bj[b], e2 = t.bi[b2] == nd ? 0 : 1;
                if (best[2 * b2 + e2] != b || b > b2) continue;
                // splice pairs: chords matched one-to-one by increasing end distance (ties: lower indices); where
                // the chord counts differ (4 -> 3) the unmatched chord just ends at the node (capped, no hub)
                int na = lg[b].nc, nb2 = lg[b2].nc;
                int[] grp = new int[na + nb2];
                Arrays.fill(grp, -1);
                boolean[] ua = new boolean[na], ub = new boolean[nb2];
                for (int r = 0; r < Math.min(na, nb2); r++) {
                    int bk = -1, bq = -1;
                    double bd2 = 1e300;
                    for (int k = 0; k < na; k++) {
                        if (ua[k]) continue;
                        for (int q2 = 0; q2 < nb2; q2++) {
                            if (ub[q2]) continue;
                            double dd = dist2(lg[b].rawEnd(k, e), lg[b2].rawEnd(q2, e2));
                            if (dd < bd2 - 1e-15) { bd2 = dd; bk = k; bq = q2; }
                        }
                    }
                    ua[bk] = ub[bq] = true;
                    grp[bk] = bk;
                    grp[na + bq] = bk;
                }
                chordGrp[2 * b + e] = grp;
                cont[2 * b + e] = b2;
                cont[2 * b2 + e2] = b;
            }
        }
        // chord-pair splices only where the continuing pair is the main member at the node: no other laced member
        // there is wider (a leg with laced bracing keeps its splices; the bracing joins the node's inner hull by its
        // envelope, like solid bracing). Every other laced end (branch / Y / T where a wider member arrives, a laced
        // member ending on another, boom roots, free ends) is an envelope end of the node joint (below).
        double[] bOther = new double[2 * m];
        for (int b = 0; b < m; b++) {
            if (type[b] != LACED) continue;
            for (int e = 0; e < 2; e++) {
                int nd = e == 0 ? t.bi[b] : t.bj[b], b2 = cont[2 * b + e];
                for (int b3 : at.get(nd)) if (type[b3] == LACED && b3 != b && b3 != b2) bOther[2 * b + e] = Math.max(bOther[2 * b + e], lg[b3].b);
            }
        }
        for (int b = 0; b < m; b++) {
            if (type[b] != LACED) continue;
            for (int e = 0; e < 2; e++) {
                int b2 = cont[2 * b + e];
                if (b2 < 0) continue;
                if (bOther[2 * b + e] > Math.min(lg[b].b, lg[b2].b) + 1e-9) { cont[2 * b + e] = -1; chordGrp[2 * b + e] = null; }
            }
        }
        for (int b = 0; b < m; b++) {
            if (type[b] != LACED) continue;
            lg[b].tap0 = cont[2 * b] < 0;
            lg[b].tap1 = cont[2 * b + 1] < 0;
            chords(lg[b]);
        }

        // --- hubs: node hubs (node + offset / chord ends at free laced ends), chord-pair hubs (continuing laced)
        Joint[] nodeHub = new Joint[n];
        List<Joint> chordHubs = new ArrayList<>();
        End[] mEnd = new End[2 * m];           // solid / pole / cable member ends
        End[][] cEnd = new End[2 * m][];       // laced chord ends
        End[] envEnd = new End[2 * m];         // laced envelope ends (envelope joints)
        for (int b = 0; b < m; b++) {
            if (type[b] == SKIP) continue;
            for (int e = 0; e < 2; e++) {
                int nd = e == 0 ? t.bi[b] : t.bj[b];
                if (type[b] == LACED) {
                    if (cont[2 * b + e] >= 0) continue;
                    Laced g = lg[b];
                    cEnd[2 * b + e] = new End[g.nc];
                    if (sink != null) {
                        double[] d = e == 0 ? ax[b] : V.mul(ax[b], -1), q = node(P, nd);
                        double[][] f = Mesh.frame(d);
                        joint(nodeHub, nd).env.add(new double[]{q[0], q[1], q[2], d[0], d[1], d[2], f[0][0], f[0][1], f[0][2],
                                f[1][0], f[1][1], f[1][2], dw[b] / 2, len[b]});
                    }
                    // the member joins the node joint by its outer section: a square (frame of the axis) holding every
                    // chord's section; its chords are cut where the envelope is
                    double[] d = e == 0 ? ax[b] : V.mul(ax[b], -1);
                    End env = new End(node(P, nd), d, ax[b], 0, len[b], false, part[b]);
                    double aEnv = 0;
                    for (int k = 0; k < g.nc; k++)
                        aEnv = Math.max(aEnv, g.h * Math.max(Math.abs(V.dot(g.dir[k], env.u)), Math.abs(V.dot(g.dir[k], env.v))) + g.cw / 2);
                    env = new End(node(P, nd), d, ax[b], aEnv + Math.max(0, vp.gussetLip()) * g.cw, len[b], false, part[b]);
                    env.envelope = true;
                    envEnd[2 * b + e] = env;
                    joint(nodeHub, nd).add(env);
                    for (int k = 0; k < g.nc; k++) cEnd[2 * b + e][k] = chordEnd(g, k, e, part[b]);
                } else {
                    double[] p = V.add(node(P, nd), off[b]);
                    End en = new End(p, e == 0 ? ax[b] : V.mul(ax[b], -1), ax[b], dw[b] / 2, len[b], type[b] == CABLE, part[b]);
                    mEnd[2 * b + e] = en;
                    joint(nodeHub, nd).add(en);
                }
            }
        }
        for (int b = 0; b < m; b++) {
            if (type[b] != LACED) continue;
            for (int e = 0; e < 2; e++) {
                int b2 = cont[2 * b + e];
                if (b2 < b) continue;
                int nd = e == 0 ? t.bi[b] : t.bj[b], e2 = t.bi[b2] == nd ? 0 : 1;
                int na = lg[b].nc, nb2 = lg[b2].nc;
                int[] grp = chordGrp[2 * b + e];          // -1: unmatched chord, capped
                cEnd[2 * b + e] = new End[na];
                cEnd[2 * b2 + e2] = new End[nb2];
                // chords stay straight; each splice group of chord ends is one hull joint (flush when the chords are
                // collinear, a short frustum / bridge where the section or chord count changes)
                for (int r = 0; r < na + nb2; r++) {
                    if (grp[r] != r || r >= na) continue;
                    Joint hb = new Joint();
                    if (sink != null) for (int[] me : new int[][]{{b, e}, {b2, e2}}) {
                        double[] d = me[1] == 0 ? ax[me[0]] : V.mul(ax[me[0]], -1), q = node(P, nd);
                        double[][] f = Mesh.frame(d);
                        hb.env.add(new double[]{q[0], q[1], q[2], d[0], d[1], d[2], f[0][0], f[0][1], f[0][2],
                                f[1][0], f[1][1], f[1][2], dw[me[0]] / 2, len[me[0]]});
                    }
                    for (int k = 0; k < na + nb2; k++) {
                        if (grp[k] != r) continue;
                        End en = k < na ? chordEnd(lg[b], k, e, part[b]) : chordEnd(lg[b2], k - na, e2, part[b2]);
                        if (k < na) cEnd[2 * b + e][k] = en; else cEnd[2 * b2 + e2][k - na] = en;
                        hb.add(en);
                    }
                    chordHubs.add(hb);
                }
            }
        }
        for (int nd = 0; nd < n; nd++) {
            if (nodeHub[nd] == null) continue;
            // a brace hub inside a continuing laced leg (chord splices through the node) sits in the leg's hollow,
            // seen through the lacing: drawn in its widest member's part, not as a dark knuckle block
            for (int b : at.get(nd))
                if (type[b] == LACED && cont[2 * b + (t.bi[b] == nd ? 0 : 1)] >= 0) nodeHub[nd].inLeg = true;
            solve(nodeHub[nd], sink);
        }
        for (Joint hb : chordHubs) { solve(hb, sink); hb.part = hb.ends.get(0).part; }   // chord splices: a piece of chord
        // chords run straight to their envelope's cut (the gusset face), and their open ends sink into the gusset by
        // up to chordSink x their width, as far as every edge stays strictly inside the hull: no open chord end lies on
        // a hull face (no slivers). Normally the hull is on the node side of the cut; where it reaches past the cut
        // along the chords instead (a wide neighbour inside the leg), the ends sink forward into it. The visible cut
        // (battens, lacing) is where the last chord edge leaves the hull.
        double sinkF = Math.max(0, vp.chordSink());
        for (int k = 0; k < 2 * m; k++) {
            End env = envEnd[k];
            if (env == null || !env.sealed) continue;
            Joint jt = nodeHub[k % 2 == 0 ? t.bi[k / 2] : t.bj[k / 2]];
            Hull3 h = jt.hull;
            for (End ce : cEnd[k]) {
                double cut = Math.min(env.e, 0.49 * ce.len), e = cut, vis = cut;
                if (h != null && h.dim == 3) {
                    double lo = -1e300, hi = 1e300, hiMax = cut;
                    for (int c = 0; c < 4; c++) {
                        double[] iv = h.clip(corner(ce, 0, c), ce.d, -1e-6);
                        if (iv == null) { lo = 1e300; break; }
                        lo = Math.max(lo, iv[0]);
                        hi = Math.min(hi, iv[1]);
                        hiMax = Math.max(hiMax, iv[1]);
                    }
                    double sMax = sinkF * 2 * ce.a;
                    if (lo < cut - 1e-6 && hi > cut - 1e-4) e = Math.max(Math.max(0, cut - sMax), lo);       // back into it
                    else if (lo < cut + 1e-4 && hi > cut + 1e-6) e = cut + Math.min(sMax, 0.5 * (hi - cut));  // forward
                    if (hiMax < 0.49 * ce.len) vis = hiMax;
                }
                ce.e = e;
                ce.vis = vis;
                ce.sealed = true;
                if (jt.info != null) {
                    double[][] f = new double[4][];
                    for (int c = 0; c < 4; c++) f[c] = corner(ce, e, c);
                    jt.info.chordEnds.add(f);
                    jt.info.chordSink.add(new double[]{cut - e, 2 * ce.a, cut, env.e});
                }
            }
        }

        // --- members
        for (int b = 0; b < m; b++) {
            if (type[b] == SKIP) continue;
            if (type[b] == LACED) {
                if (lacedSink != null) {
                    LacedInfo li = new LacedInfo();
                    li.member = b; li.cont0 = cont[2 * b]; li.cont1 = cont[2 * b + 1];
                    li.p0 = lg[b].p0; li.dv = lg[b].dv; li.b = lg[b].b; li.pts = lg[b].pts;
                    lacedSink.add(li);
                }
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
        for (int nd = 0; nd < n; nd++) if (nodeHub[nd] != null && nodeHub[nd].hull != null) nodeHub[nd].hull.draw(mesh, nodeHub[nd].part);
        for (Joint hb : chordHubs) if (hb.hull != null) hb.hull.draw(mesh, hb.part);

        // pole collars (brass), sized from the pole width
        for (int q = pc0 + 1; q + 1 < t.poleChain.length; q++) {
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

    private static Joint joint(Joint[] js, int nd) {
        if (js[nd] == null) js[nd] = new Joint();
        return js[nd];
    }

    private static final double TOL = 1e-7;
    private static final double[][] SG = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};   // corner order of Mesh.beam

    /** Corner k of end en's section at extension s. */
    private static double[] corner(End en, double s, int k) {
        double[] r = new double[3];
        for (int q = 0; q < 3; q++) r[q] = en.p[q] + s * en.d[q] + en.a * (SG[k][0] * en.u[q] + SG[k][1] * en.v[q]);
        return r;
    }

    /** The prism of end en (extension 0 .. len): 6 half-spaces n . x <= c (rows nx ny nz c) and 8 vertices (corners
     *  0..3 at the end point, 4..7 at the far end; Mesh.beam's corner order). Cached on the end. */
    private static void prism(End en) {
        if (en.pl != null) return;
        double dp = V.dot(en.d, en.p), up = V.dot(en.u, en.p), vp = V.dot(en.v, en.p);
        en.pl = new double[][]{
                {en.d[0], en.d[1], en.d[2], dp + en.len}, {-en.d[0], -en.d[1], -en.d[2], -dp},
                {en.u[0], en.u[1], en.u[2], up + en.a}, {-en.u[0], -en.u[1], -en.u[2], -up + en.a},
                {en.v[0], en.v[1], en.v[2], vp + en.a}, {-en.v[0], -en.v[1], -en.v[2], -vp + en.a}};
        en.vt = new double[8][];
        for (int k = 0; k < 4; k++) { en.vt[k] = corner(en, 0, k); en.vt[4 + k] = corner(en, en.len, k); }
    }

    private static final int[][] EDGES = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    /**
     * Setback of a against b: the largest (x - a.p) . a.d over the intersection of the two end prisms (0 if they do
     * not meet). The maximum of a linear function over the convex intersection is at one of its vertices: a vertex
     * of one prism inside the other, or an edge of one crossing a face plane of the other inside it. Points on an edge
     * of a prism are inside that prism, and their plane distances and s interpolate linearly along the edge, so only
     * the 8 x 6 vertex-plane distances per side are dot products.
     */
    static double clearance(End a, End b) { return clearance(a, b, new double[48], new double[8]); }

    /** As {@link #clearance(End, End)} with scratch arrays (48 and 8 doubles). */
    static double clearance(End a, End b, double[] dist, double[] sv) {
        prism(a);
        prism(b);
        double best = 0;
        for (int side = 0; side < 2; side++) {
            double[][] v1 = side == 0 ? a.vt : b.vt, p2 = side == 0 ? b.pl : a.pl;
            for (int i = 0; i < 8; i++) {
                double[] x = v1[i];
                boolean in = true;
                for (int k = 0; k < 6; k++) {
                    double[] h = p2[k];
                    double dd = h[0] * x[0] + h[1] * x[1] + h[2] * x[2] - h[3];
                    dist[6 * i + k] = dd;
                    if (dd > TOL) in = false;
                }
                sv[i] = (x[0] - a.p[0]) * a.d[0] + (x[1] - a.p[1]) * a.d[1] + (x[2] - a.p[2]) * a.d[2];
                if (in && sv[i] > best) best = sv[i];
            }
            for (int[] e : EDGES) {
                int i0 = 6 * e[0], i1 = 6 * e[1];
                for (int k = 0; k < 6; k++) {
                    double d0 = dist[i0 + k], d1 = dist[i1 + k];
                    if ((d0 > 0) == (d1 > 0) || d0 == d1) continue;    // the edge does not cross plane k
                    double tt = d0 / (d0 - d1);
                    double st = sv[e[0]] + tt * (sv[e[1]] - sv[e[0]]);
                    if (st <= best) continue;
                    boolean in = true;
                    for (int q = 0; q < 6 && in; q++) if (q != k && dist[i0 + q] + tt * (dist[i1 + q] - dist[i0 + q]) > TOL) in = false;
                    if (in) best = st;
                }
            }
        }
        return best;
    }

    private static Hull3 hullOf(List<End> core, List<double[]> extra) {
        int m = 4 * core.size() + (extra == null ? 0 : extra.size());
        double[] xyz = new double[3 * m];
        int k = 0;
        for (End en : core) {
            for (int c = 0; c < 4; c++) {
                for (int q = 0; q < 3; q++) xyz[k + q] = en.p[q] + en.e * en.d[q] + en.a * (SG[c][0] * en.u[q] + SG[c][1] * en.v[q]);
                k += 3;
            }
        }
        if (extra != null) for (double[] x : extra) { xyz[k] = x[0]; xyz[k + 1] = x[1]; xyz[k + 2] = x[2]; k += 3; }
        return Hull3.of(xyz, m);
    }

    /**
     * Setbacks and the hull of one joint. Solid ends (struts, pole, laced chords) form the hull; with fewer than two
     * solids, two or more cables form it instead, and a single solid end stays a plain capped end. Cables attach to
     * the hull: cut where all four edges have left it, or (end outside it) added to it.
     */
    private void solve(Joint jt, List<JointInfo> sink) {
        List<End> core = new ArrayList<>(), att = new ArrayList<>();
        for (End en : jt.ends) (en.cable ? att : core).add(en);
        if (core.size() < 2) {
            if (!core.isEmpty() || att.size() < 2) return;
            core = att;
            att = new ArrayList<>();
        }
        double maxF = Math.max(0, Math.min(0.49, vp.jointMaxSetbackFrac()));
        double margin = Math.max(0, vp.jointMargin());
        int nc = core.size();
        double wMax = 0;
        for (End en : core) wMax = Math.max(wMax, 2 * en.a);
        double capW = Math.max(0, vp.jointMaxSetback()) * wMax;
        double[] bound = new double[nc];
        int[] ord = new int[nc];
        double[] scratchD = new double[48], scratchS = new double[8];
        for (End en : core) {
            // exact clearances, the most promising neighbours first: a neighbour b can hold a back by at most
            // (r_a + r_b + |p_a - p_b|) / sin(angle) (circumscribed cylinders), so stop once that is below the best
            double cap = Math.min(maxF * en.len, capW), s = 0;
            for (int j = 0; j < nc; j++) {
                End o = core.get(j);
                ord[j] = j;
                if (o == en) { bound[j] = -1; continue; }
                double sn = V.crossNorm(en.d, o.d);
                double wx = en.p[0] - o.p[0], wy = en.p[1] - o.p[1], wz = en.p[2] - o.p[2];
                double w = Math.sqrt(wx * wx + wy * wy + wz * wz);
                bound[j] = sn < 1e-9 ? 1e300 : (Math.sqrt(2) * (en.a + o.a) + w) / sn;
            }
            for (int x = 1; x < nc; x++) {                    // insertion sort: bound descending, then index
                int v = ord[x], y = x - 1;
                while (y >= 0 && (bound[ord[y]] < bound[v] || (bound[ord[y]] == bound[v] && ord[y] > v))) { ord[y + 1] = ord[y]; y--; }
                ord[y + 1] = v;
            }
            for (int j : ord) {
                if (bound[j] <= s || s >= cap) break;
                s = Math.max(s, clearance(en, core.get(j), scratchD, scratchS));
            }
            en.e = Math.min(s + margin * 2 * en.a, cap);
        }
        Hull3 h = null;
        for (int it = 0; it < 5; it++) {
            h = hullOf(core, null);
            if (h.dim != 3 || it == 4) break;
            boolean changed = false;
            for (End en : core) {
                double ex = en.e;
                for (int k = 0; k < 4; k++) {
                    double[] iv = h.clip(corner(en, 0, k), en.d, TOL);
                    if (iv != null && iv[0] <= en.e + 1e-6 && iv[1] > ex) ex = iv[1];
                }
                ex = Math.min(ex, Math.min(maxF * en.len, capW));
                if (ex > en.e + 1e-6) { en.e = ex; changed = true; }
            }
            if (!changed) break;
        }
        if (h.dim < 2) {                                     // degenerate (collinear points): plain capped ends
            for (End en : core) en.e = 0;
            return;
        }
        if (h.dim == 3) {
            List<double[]> extra = new ArrayList<>();
            for (End en : att) {
                double ex = 1e300;
                boolean in = true;
                for (int k = 0; k < 4 && in; k++) {
                    double[] iv = h.clip(corner(en, 0, k), en.d, TOL);
                    if (iv == null || iv[0] > 1e-6 || iv[1] < 0) in = false;
                    else ex = Math.min(ex, iv[1]);
                }
                if (in) en.e = Math.min(ex, maxF * en.len);
                else { en.e = 0; for (int k = 0; k < 4; k++) extra.add(corner(en, 0, k)); }
                en.sealed = true;
            }
            if (!extra.isEmpty()) {

                List<End> all = new ArrayList<>(core);
                h = hullOf(all, extra);
            }
        }
        for (End en : core) en.sealed = true;
        jt.hull = h;
        End w = null;
        for (End en : core) if (w == null || en.a > w.a + 1e-12) w = en;
        // a pure pass-through (only collinear members, e.g. a leg with just cables attached) is a piece of that
        // member, not a joint; so is a hub on the pole (its brass collar marks the joint)
        boolean through = true, pole = false;
        for (End en : core) {
            if (en.part == Part.POLE) pole = true;
            if (Math.abs(V.dot(en.d, w.d)) < 0.9986) through = false;
        }
        through |= pole;
        boolean gusset = false;
        for (End en : core) gusset |= en.envelope;
        // hubs holding laced members are gusset blocks in the widest member's part
        jt.part = vp.jointMemberColour() || through || gusset || jt.inLeg ? w.part : Part.KNUCKLE;
        if (sink != null) {
            JointInfo ji = new JointInfo();
            ji.hull = h;
            ji.boxes.addAll(jt.env);
            for (double[] ev : jt.env) ji.maxW = Math.max(ji.maxW, 2 * ev[12]);
            for (End en : jt.ends) {
                ji.boxes.add(new double[]{en.p[0], en.p[1], en.p[2], en.d[0], en.d[1], en.d[2], en.u[0], en.u[1], en.u[2],
                        en.v[0], en.v[1], en.v[2], en.a, en.len});
                ji.maxW = Math.max(ji.maxW, 2 * en.a);
                if (en.sealed) {
                    double[][] f = new double[4][];
                    for (int k = 0; k < 4; k++) f[k] = corner(en, en.e, k);
                    ji.sealed.add(f);
                }
            }
            jt.info = ji;
            synchronized (sink) { sink.add(ji); }
        }
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

    /** Continuing laced members: the bend between them below 30 degrees (leg kinks at the base flare included). */
    static final double CONT_DOT = -0.866;

    /** Relative margin by which the larger of max tension / max compression must win to set the colour. */
    static final double COLOUR_TIE = 1e-6;

    /**
     * Strut colour from its forces over the load cases: compression (rust) if the largest compression beats the
     * largest tension by more than {@link #COLOUR_TIE} (relative), else tension (steel). Ties (members loaded only by
     * the mirrored wind / torsion cases, |N+| = |N-| up to rounding) and unloaded members are steel, so the colour does
     * not hang on the last bits of the solve (the JVM and the TeaVM build round differently; H40b4 flipped).
     */
    static Part forceColour(RuntimePass.Analysis an, int b) {
        double mt = 0, mc = 0;
        for (int q = 0; q < an.dN.length; q++) {
            double v = an.N0[b] + an.dN[q][b];
            if (v > mt) mt = v;
            if (-v > mc) mc = -v;
        }
        return mc > mt * (1 + COLOUR_TIE) + 1e-9 ? Part.STRUT_C : Part.STRUT_T;
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
        // lacing and battens span the visible part: from the chords' cut at each end (splice hull face), or just off
        // the gusset face (the batten stands on it, sunk 0.15 x its width: no face of it coplanar with the gusset's)
        double bw = lw * vp.battenScale();
        double tA = Math.min(0.45, cutFrac(end0, g, bw)), tB = 1 - Math.min(0.45, cutFrac(end1, g, bw));
        for (int fc = 0; fc < g.nc; fc++) {
            int oa = fc, ob = (fc + 1) % g.nc;
            for (int k = 0; k < npan; k++) {
                double t0 = tA + (tB - tA) * k / npan, t1 = tA + (tB - tA) * (k + 1) / npan;
                mesh.beam(g.at(k % 2 == 0 ? oa : ob, t0), g.at(k % 2 == 0 ? ob : oa, t1), lw, Part.LACING);
            }
            if (!skip0) mesh.beam(g.at(oa, tA), g.at(ob, tA), bw, Part.LACING);
            if (!skip1) mesh.beam(g.at(oa, tB), g.at(ob, tB), bw, Part.LACING);
        }
    }

    /** Fraction of the member length cut off at one end: the smallest sealed chord cut (0 if any chord is open); at a
     *  gusset end the largest visible cut (where the chords leave the gusset) + 0.35 x the batten width bw. */
    private static double cutFrac(End[] ends, Laced g, double bw) {
        if (ends == null) return 0;
        double f = 1e300, fv = 0;
        boolean gusset = false;
        for (End en : ends) {
            if (en == null || !en.sealed) return 0;
            if (en.vis >= 0) { gusset = true; fv = Math.max(fv, en.vis / g.L); }
            f = Math.min(f, en.e / g.L);
        }
        if (gusset) return fv + 0.35 * bw / g.L;
        return f == 1e300 ? 0 : f;
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
