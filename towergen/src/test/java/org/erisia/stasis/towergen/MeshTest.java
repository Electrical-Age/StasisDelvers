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
     * more than 0.3 x the widest member there + 0.06 blocks (the fill between members): sampled at face centroids, edge
     * midpoints and interior points of every hull face. Prints the measured worst case per layout.
     */
    @Test
    void hullJointsClosedAndContained() throws Exception {
        TowerGen g = new TowerGen(Library.shared());
        VisualParams vp = VisualParams.defaults();
        MeshBuilder mb = new MeshBuilder(g.c, vp);
        double[][] bary = {{1 / 3.0, 1 / 3.0, 1 / 3.0}, {0.5, 0.5, 0}, {0, 0.5, 0.5}, {0.5, 0, 0.5}, {0.6, 0.2, 0.2},
                {0.2, 0.6, 0.2}, {0.2, 0.2, 0.6}};
        for (String nm : GOLDEN) {
            for (double q : new double[]{1.0, 0.3}) {
                TowerGen.Result r = g.build(Layout.read(java.nio.file.Path.of("layouts", nm + ".json")), q);
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
                            double allow = 0.3 * ji.maxW + 0.06;
                            worst = Math.max(worst, ex);
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
                            assertTrue(ex <= allow, nm + " q" + q + ": hub point " + java.util.Arrays.toString(x) + " is " + ex
                                    + " outside its members (max width " + ji.maxW + ")");
                        }
                    }
                }
                System.out.printf("hull joints %s q%.1f: %d hulls, %d flat, %d sealed ends; worst excess %.4f blocks, %.3f x max width%n",
                        nm, q, hulls, flat, sealedEnds, worst, worstRel);
            }
        }
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
}
