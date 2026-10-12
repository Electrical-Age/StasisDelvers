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
        VisualParams p = VisualParams.fromJson(Json.parse("{\"knuckleScale\": 2.0, \"colRust\": [1, 0, 0]}"));
        assertEquals(2.0, p.knuckleScale());
        assertEquals(1.0, p.colRust()[0]);
        assertEquals(d.lacingWidthFrac(), p.lacingWidthFrac());
    }
}
