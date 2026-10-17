package org.erisia.stasis.towergen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Determinism (same layout -> identical mesh hash, also from a fresh generator) and mesh sanity. */
class MeshTest {
    static final Layout H64MAX = new Layout(64, List.of(new Layout.Boom(24, 0, 40)));
    static final Layout H24B = new Layout(24, List.of(new Layout.Boom(10, 0, 8), new Layout.Boom(18, 3, 6)));

    @Test
    void deterministic() {
        TowerGen g1 = new TowerGen(Library.shared()), g2 = new TowerGen(Library.shared());
        MeshBuilder mb = new MeshBuilder(Library.shared().c, VisualParams.defaults());
        for (Layout l : List.of(H64MAX, H24B)) {
            for (double q : new double[]{1.0, 0.3}) {
                String h1 = mb.build(g1.build(l, q)).hash();
                String h2 = mb.build(g1.build(l, q)).hash();
                String h3 = mb.build(g2.build(l, q)).hash();
                assertEquals(h1, h2, "same generator, " + l + " q" + q);
                assertEquals(h1, h3, "fresh generator, " + l + " q" + q);
                System.out.println("mesh hash " + l + " q" + q + ": " + h1.substring(0, 16));
            }
        }
    }

    @Test
    void threadSafe() throws Exception {
        TowerGen g = new TowerGen(Library.shared());
        MeshBuilder mb = new MeshBuilder(g.c, VisualParams.defaults());
        List<Layout> ls = List.of(H64MAX, H24B, new Layout(40, List.of()), new Layout(100, List.of()));
        java.util.Map<String, String> seq = new java.util.HashMap<>();
        for (Layout l : ls) seq.put(l.toString(), mb.build(g.build(l, 1.0)).hash());
        var ex = java.util.concurrent.Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<String[]>> fs = new java.util.ArrayList<>();
        for (int rep = 0; rep < 4; rep++)
            for (Layout l : ls) fs.add(ex.submit(() -> new String[]{l.toString(), mb.build(g.build(l, 1.0)).hash()}));
        for (var f : fs) { String[] r = f.get(); assertEquals(seq.get(r[0]), r[1], "concurrent build of " + r[0]); }
        ex.shutdown();
    }

    @Test
    void meshHasAllParts() {
        TowerGen g = new TowerGen(Library.shared());
        Mesh m = new MeshBuilder(g.c, VisualParams.defaults()).build(g.build(H64MAX, 1.0));
        int[] cnt = new int[VisualParams.Part.values().length];
        for (int t = 0; t < m.tris; t++) cnt[m.part[t]]++;
        System.out.println("H64max mesh: " + m.tris + " triangles, per part " + java.util.Arrays.toString(cnt));
        for (VisualParams.Part p : VisualParams.Part.values()) assertTrue(cnt[p.ordinal()] > 0, "no triangles for " + p);
        for (int i = 0; i < 3 * m.tris; i++) assertTrue(Float.isFinite(m.nrm[i]));
        double[] b = m.bounds();
        assertTrue(b[4] > 64 && b[3] > 39, "bounds " + java.util.Arrays.toString(b));
    }

    @Test
    void visualParamsRoundTrip() {
        VisualParams d = VisualParams.defaults();
        VisualParams r = VisualParams.fromJson(Json.parse(Json.writePretty(d.toJson())));
        assertEquals(Json.write(d.toJson()), Json.write(r.toJson()));
        VisualParams p = VisualParams.fromJson(Json.parse("{\"jointMargin\": 2.0, \"colRust\": [1, 0, 0], \"hubScale\": 3, \"hubMemberColour\": true}"));
        assertEquals(2.0, p.jointMargin());                  // obsolete hub* keys parse and are ignored
        assertEquals(1.0, p.colRust()[0]);
        assertEquals(d.lacingWidthFrac(), p.lacingWidthFrac());
    }

    /** Triangle counts and mesh time (warm, median of 7) of the golden layouts at q 1. */
    @Test
    void stats() throws Exception {
        TowerGen g = new TowerGen(Library.shared());
        MeshBuilder mb = new MeshBuilder(g.c, VisualParams.defaults());
        for (String nm : new String[]{"H16", "H24b", "H40", "H40r3", "H40b4", "H64max", "H100"}) {
            Layout l = Layout.read(java.nio.file.Path.of("layouts", nm + ".json"));
            TowerGen.Result r = g.build(l, 1.0);
            double[] ms = new double[7];
            int tris = 0;
            for (int k = 0; k < 3; k++) mb.build(r);
            for (int k = 0; k < ms.length; k++) {
                long t0 = System.nanoTime();
                tris = mb.build(r).tris;
                ms[k] = (System.nanoTime() - t0) / 1e6;
            }
            java.util.Arrays.sort(ms);
            System.out.printf("mesh stats %s q1: %d triangles, %.2f ms%n", nm, tris, ms[3]);
        }
    }

    static final String[] GOLDEN = {"H16", "H24b", "H40", "H40r3", "H40b4", "H64max", "H100"};

    /** Distance from x to end prism b (p d u v a len), the prism run back through the node by its half-width. */
    static double boxDist(double[] b, double[] x) {
        double[] r = {x[0] - b[0], x[1] - b[1], x[2] - b[2]};
        double s = r[0] * b[3] + r[1] * b[4] + r[2] * b[5];
        double al = r[0] * b[6] + r[1] * b[7] + r[2] * b[8], be = r[0] * b[9] + r[1] * b[10] + r[2] * b[11];
        double a = b[12];
        double ds = Math.max(0, Math.max(-a - s, s - b[13])), da = Math.max(0, Math.abs(al) - a), db = Math.max(0, Math.abs(be) - a);
        return Math.sqrt(ds * ds + da * da + db * db);
    }

    /**
     * Hull hubs: (1) every sealed member end (its cut face) lies inside its hull, so joints are closed; (2) no part of
     * a hub reaches beyond the union of its members' prisms (each run back through the node by its half-width) by
     * more than 0.35 x the widest member there + 0.06 blocks (the fill between members; gusset blocks at K / Y nodes): sampled at face centroids, edge
     * midpoints and interior points of every hull face. Prints the measured worst case per layout.
     */
    @Test
    void hullJointsClosedAndContained() throws Exception {
        TowerGen g = new TowerGen(Library.shared());
        VisualParams vp = VisualParams.defaults();
        MeshBuilder mb = new MeshBuilder(g.c, vp);
        double[][] bary = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {1 / 3.0, 1 / 3.0, 1 / 3.0}, {0.5, 0.5, 0}, {0, 0.5, 0.5}, {0.5, 0, 0.5}, {0.6, 0.2, 0.2},
                {0.2, 0.6, 0.2}, {0.2, 0.2, 0.6}};
        java.util.LinkedHashMap<String, Layout> lays = new java.util.LinkedHashMap<>();
        for (String nm : GOLDEN) lays.put(nm, Layout.read(java.nio.file.Path.of("layouts", nm + ".json")));
        lays.put("H16W4@2", new Layout(16, List.of(new Layout.Boom(2, 2, 4))));
        lays.put("H16W5@2", new Layout(16, List.of(new Layout.Boom(2, 2, 5))));
        java.util.Random rng = new java.util.Random(99);
        for (int k = 0; k < 25; k++) { Layout l = LayoutGen.randomBuildable(rng, g.c); lays.put("random" + k + " " + l, l); }
        int spiky = 0, total = 0;
        for (var le : lays.entrySet()) {
            String nm = le.getKey();
            for (double q : new double[]{1.0, 0.3}) {
                TowerGen.Result r;
                try { r = g.build(le.getValue(), q); } catch (RefusedException ex) { continue; }
                List<MeshBuilder.JointInfo> js = new java.util.ArrayList<>();
                mb.build(r.tower, r.analysis, js);
                int hulls = 0, flat = 0, sealedEnds = 0;
                double worst = 0, worstRel = 0;
                for (MeshBuilder.JointInfo ji : js) {
                    Hull3 h = ji.hull;
                    assertTrue(h.ok, nm + ": hull construction failed");
                    if (h.dim == 2) { flat++; continue; }
                    if (h.dim != 3) continue;
                    hulls++;
                    for (double[][] f : ji.sealed) {
                        sealedEnds++;
                        for (double[] c : f) assertTrue(h.contains(c, 1e-6), nm + ": sealed end corner outside its hull");
                    }
                    for (int fi = 0; fi < h.nTri; fi++) {
                        int[] f = {h.tri[3 * fi], h.tri[3 * fi + 1], h.tri[3 * fi + 2]};
                        for (double[] w : bary) {
                            double[] x = new double[3];
                            for (int k = 0; k < 3; k++) for (int qq = 0; qq < 3; qq++) x[qq] += w[k] * h.xyz[3 * f[k] + qq];
                            double ex = 1e300;
                            for (double[] b : ji.boxes) ex = Math.min(ex, boxDist(b, x));
                            double allow = 0.35 * ji.maxW + 0.06;
                            worst = Math.max(worst, ex);
                            total++;
                            if (ex > allow) spiky++;
                            if (ex / ji.maxW > worstRel && System.getProperty("meshDebug") != null) {
                                System.out.println("DBGW " + nm + " q" + q + " x " + java.util.Arrays.toString(x) + " ex " + ex);
                                for (double[] b : ji.boxes) System.out.println("DBGW box a " + b[12] + " len " + b[13] + " p " + b[0] + "," + b[1] + "," + b[2] + " d " + b[3] + "," + b[4] + "," + b[5]);
                                System.out.println("DBGW face " + java.util.Arrays.toString(h.point(f[0])) + java.util.Arrays.toString(h.point(f[1])) + java.util.Arrays.toString(h.point(f[2])));
                            }
                            worstRel = Math.max(worstRel, ex / ji.maxW);
                            if (ex > allow) {
                                for (double[] b : ji.boxes) System.out.println("DBG box " + java.util.Arrays.toString(b));
                                for (int pi = 0; pi < h.n; pi++) System.out.println("DBG pt " + java.util.Arrays.toString(h.point(pi)));
                            }
                            if (ex > allow) System.out.println("SPIKY " + nm + " q" + q + ": hub point " + java.util.Arrays.toString(x) + " is " + ex
                                    + " outside its members (max width " + ji.maxW + ")");
                        }
                    }
                }
                if (nm.startsWith("random")) continue;
                System.out.printf("hull joints %s q%.1f: %d hulls, %d flat, %d sealed ends; worst excess %.4f blocks, %.3f x max width%n",
                        nm, q, hulls, flat, sealedEnds, worst, worstRel);
            }
        }
        System.out.printf("hull joints: %d layouts x 2 q, %d hub points sampled (vertices, edge midpoints, face points), %d spiky%n", lays.size(), total, spiky);
        assertEquals(0, spiky, "spiky hub points (beyond 0.35 x widest member + 0.06 outside the member envelopes)");
    }

    /**
     * Gusset joints (laced envelope ends): every chord's open end lies strictly inside the gusset hull (sunk into it,
     * not on its face: no slivers / z-fighting at the seam), and nearly all chords are sunk by a visible amount
     * (goldens and the owner's H16 W4 / W5 layouts, q 1 and 0.3).
     */
    @Test
    void gussetChordEndsSunk() throws Exception {
        TowerGen g = new TowerGen(Library.shared());
        MeshBuilder mb = new MeshBuilder(g.c, VisualParams.defaults());
        java.util.LinkedHashMap<String, Layout> lays = new java.util.LinkedHashMap<>();
        for (String nm : GOLDEN) lays.put(nm, Layout.read(java.nio.file.Path.of("layouts", nm + ".json")));
        lays.put("H16W4@2", new Layout(16, List.of(new Layout.Boom(2, 2, 4))));
        lays.put("H16W5@2", new Layout(16, List.of(new Layout.Boom(2, 2, 5))));
        int ends = 0, sunk = 0, outside = 0;
        double minRel = 1e300;
        for (var le : lays.entrySet()) {
            for (double q : new double[]{1.0, 0.3}) {
                TowerGen.Result r = g.build(le.getValue(), q);
                List<MeshBuilder.JointInfo> js = new java.util.ArrayList<>();
                mb.build(r.tower, r.analysis, js);
                for (MeshBuilder.JointInfo ji : js) {
                    for (int i = 0; i < ji.chordEnds.size(); i++) {
                        ends++;
                        double[] sk = ji.chordSink.get(i);
                        boolean in = true;
                        for (double[] c : ji.chordEnds.get(i)) in &= ji.hull.contains(c, -1e-7);
                        if (!in) {
                            outside++;
                            System.out.printf("chord end outside its gusset: %s q%.1f sink %.4f cw %.3f cut %.4f env %.4f%n", le.getKey(), q, sk[0], sk[1], sk[2], sk[3]);
                        }
                        if (Math.abs(sk[0]) >= 0.1 * sk[1]) sunk++;
                        minRel = Math.min(minRel, Math.abs(sk[0]) / sk[1]);
                    }
                }
            }
        }
        System.out.printf("gusset chord ends: %d, %d sunk >= 0.1 x chord width, smallest sink %.3f x chord width%n", ends, sunk, minRel);
        assertEquals(0, outside, "chord ends at a gusset not strictly inside its hull");
        assertTrue(ends > 0 && sunk >= 0.95 * ends, "chord ends sunk into their gussets");
    }

    /**
     * Strut colour (polish.md, H40b4: one strut differed between JS and the JVM). Unit cases: zero / near-zero forces
     * and mirrored-load ties are steel whatever the rounding; a clear compression stays rust. Goldens (q 1 and 0.3):
     * every strut keeps its colour when its forces are perturbed by 1e-9 relative (far beyond JS / JVM rounding).
     */
    @Test
    void strutColourStable() throws Exception {
        RuntimePass.Analysis a = new RuntimePass.Analysis();
        double[][] cases = {
            {0, 0, 0}, {1e-15, -1e-15, 0}, {-1e-13, -2e-13, 0},                         // unloaded / noise
            {0.034875000000004, -0.034875000000003}, {0.034875000000003, -0.034875000000004},  // mirrored wind tie
        };
        for (double[] dn : cases) {
            a.N0 = new double[]{0};
            a.dN = new double[dn.length][];
            for (int q = 0; q < dn.length; q++) a.dN[q] = new double[]{dn[q]};
            assertEquals(VisualParams.Part.STRUT_T, MeshBuilder.forceColour(a, 0), java.util.Arrays.toString(dn));
        }
        a.N0 = new double[]{-2};
        a.dN = new double[][]{{0.5}, {-0.5}};
        assertEquals(VisualParams.Part.STRUT_C, MeshBuilder.forceColour(a, 0));
        a.N0 = new double[]{2};
        assertEquals(VisualParams.Part.STRUT_T, MeshBuilder.forceColour(a, 0));

        TowerGen g = new TowerGen(Library.shared());
        java.util.Random rnd = new java.util.Random(1);
        int struts = 0, rust = 0;
        for (String nm : GOLDEN) {
            for (double q : new double[]{1.0, 0.3}) {
                TowerGen.Result r = g.build(Layout.read(java.nio.file.Path.of("layouts", nm + ".json")), q);
                RuntimePass.Analysis an = r.analysis;
                Stitch.Tower t = r.tower != null ? r.tower : r.raw;
                for (int b = 0; b < t.m; b++) {
                    if (t.kind[b] == 1 || t.kind[b] == 3) continue;
                    VisualParams.Part col = MeshBuilder.forceColour(an, b);
                    struts++;
                    if (col == VisualParams.Part.STRUT_C) rust++;
                    for (int rep = 0; rep < 4; rep++) {
                        RuntimePass.Analysis p = new RuntimePass.Analysis();
                        p.N0 = new double[]{an.N0[b] * (1 + 1e-9 * rnd.nextGaussian()) + 1e-12 * rnd.nextGaussian()};
                        p.dN = new double[an.dN.length][];
                        for (int k = 0; k < an.dN.length; k++)
                            p.dN[k] = new double[]{an.dN[k][b] * (1 + 1e-9 * rnd.nextGaussian()) + 1e-12 * rnd.nextGaussian()};
                        assertEquals(col, MeshBuilder.forceColour(p, 0), nm + " q" + q + " member " + b);
                    }
                }
            }
        }
        System.out.printf("strut colours: %d struts, %d rust, all stable under 1e-9 perturbation%n", struts, rust);
    }

    /** Mesh FNV hashes of the golden layouts (q 1, default params): compared with the web build's stats.meshHash. */
    @Test
    void portableHashes() throws Exception {
        TowerGen g = new TowerGen(Library.shared());
        MeshBuilder mb = new MeshBuilder(g.c, VisualParams.defaults());
        StringBuilder sb = new StringBuilder("{");
        for (String nm : GOLDEN) {
            TowerGen.Result r = g.build(Layout.read(java.nio.file.Path.of("layouts", nm + ".json")), 1.0);
            Mesh m = mb.build(r);
            sb.append(sb.length() > 1 ? ", " : "").append('"').append(nm).append("\": \"").append(m.fnv()).append('"');
            for (VisualParams.Part p : VisualParams.Part.values())
                sb.append(", \"").append(nm).append('.').append(p.name()).append("\": \"").append(m.fnv(p)).append('"');
            sb.append(", \"").append(nm).append(".STRUTS\": \"").append(m.fnv(VisualParams.Part.STRUT_C, VisualParams.Part.STRUT_T)).append('"');
        }
        sb.append('}');
        System.out.println("mesh fnv " + sb);
        java.nio.file.Files.writeString(java.nio.file.Path.of("build", "mesh-fnv.json"), sb.toString());
    }

    /**
     * Laced members: chords run straight (every chord segment within 1 degree of the member axis, so no chord bends
     * inside a member) and inside the member's outer envelope; and every pair of laced members meeting at a node
     * within 5 degrees of collinear is spliced (at least one of the two continues) or, at a node with more than two laced ends, joins the gusset
     * (envelope) joint; never two free ends. Goldens, the owner's
     * H16 W4/W5 boom-at-2 layouts and random layouts, q 1 and 0.3.
     */
    @Test
    void lacedChordsStraightAndSpliced() throws Exception {
        TowerGen g = new TowerGen(Library.shared());
        MeshBuilder mb = new MeshBuilder(g.c, VisualParams.defaults());
        List<Layout> ls = new java.util.ArrayList<>();
        for (String nm : GOLDEN) ls.add(Layout.read(java.nio.file.Path.of("layouts", nm + ".json")));
        ls.add(new Layout(16, List.of(new Layout.Boom(2, 2, 4))));
        ls.add(new Layout(16, List.of(new Layout.Boom(2, 2, 5))));
        java.util.Random rng = new java.util.Random(4242);
        for (int k = 0; k < 40; k++) ls.add(LayoutGen.randomBuildable(rng, g.c));
        double cos1 = Math.cos(Math.toRadians(1)), cos5 = Math.cos(Math.toRadians(5));
        int members = 0, spliced = 0;
        double maxBend = 0;
        for (Layout l : ls) {
            for (double q : new double[]{1.0, 0.3}) {
                TowerGen.Result r;
                try { r = g.build(l, q); } catch (RefusedException ex) { continue; }
                Stitch.Tower t = r.tower;
                List<MeshBuilder.LacedInfo> li = new java.util.ArrayList<>();
                mb.build(t, r.analysis, null, li);
                java.util.Map<Integer, MeshBuilder.LacedInfo> byMember = new java.util.HashMap<>();
                for (MeshBuilder.LacedInfo x : li) {
                    byMember.put(x.member, x);
                    members++;
                    double L = V.norm(x.dv);
                    double[] a = V.mul(x.dv, 1 / L);
                    double[][] f = Mesh.frame(a);
                    for (double[][] ch : x.pts) {
                        for (int i = 0; i + 1 < ch.length; i++) {
                            double[] sd = V.sub(ch[i + 1], ch[i]);
                            double sl = V.norm(sd);
                            if (sl < 1e-9) continue;
                            assertTrue(Math.abs(V.dot(sd, a)) / sl >= cos1, l + " q" + q + ": chord of member " + x.member + " bends");
                        }
                        for (double[] p : ch) {
                            double[] rr = V.sub(p, x.p0);
                            double sp = V.dot(rr, a) / L;
                            double cu = V.dot(rr, f[0]), cv = V.dot(rr, f[1]);
                            boolean in = x.pts.length == 3 ? Math.hypot(cu, cv) <= x.b / Math.sqrt(3) + 1e-9      // triangle of side b
                                    : Math.abs(cu) <= x.b / 2 + 1e-9 && Math.abs(cv) <= x.b / 2 + 1e-9;      // square of side b
                            assertTrue(sp > -1e-9 && sp < 1 + 1e-9 && in, l + " q" + q + ": chord point outside member " + x.member);
                        }
                    }
                }
                java.util.Map<Integer, Integer> nLaced = new java.util.HashMap<>();
                for (MeshBuilder.LacedInfo x : li) { nLaced.merge(t.bi[x.member], 1, Integer::sum); nLaced.merge(t.bj[x.member], 1, Integer::sum); }
                for (MeshBuilder.LacedInfo x : li) {
                    for (int e = 0; e < 2; e++) {
                        int nd = e == 0 ? t.bi[x.member] : t.bj[x.member];
                        double[] d = V.unit(e == 0 ? x.dv : V.mul(x.dv, -1));
                        int cont = e == 0 ? x.cont0 : x.cont1;
                        for (MeshBuilder.LacedInfo y : li) {
                            if (y == x || (t.bi[y.member] != nd && t.bj[y.member] != nd)) continue;
                            double[] d2 = V.unit(t.bi[y.member] == nd ? y.dv : V.mul(y.dv, -1));
                            double c = -V.dot(d, d2);
                            int ycont = t.bi[y.member] == nd ? y.cont0 : y.cont1;
                            // at least one of the two continues (the other may be a third, overlapping member whose
                            // partner was taken by a more collinear one)
                            // (a pair is not spliced only where a wider laced member arrives: an envelope / gusset joint)
                            if (c >= cos5) assertTrue(cont >= 0 || ycont >= 0 || nLaced.get(nd) > 2, l + " q" + q + ": collinear laced members " + x.member + ", " + y.member + " at node " + nd + " not spliced");
                            if (cont == y.member) { spliced++; maxBend = Math.max(maxBend, Math.toDegrees(Math.acos(Math.min(1, c)))); }
                        }
                    }
                }
            }
        }
        System.out.printf("laced: %d members, %d spliced ends, largest bend at a splice %.2f deg%n", members, spliced, maxBend);
    }
}
