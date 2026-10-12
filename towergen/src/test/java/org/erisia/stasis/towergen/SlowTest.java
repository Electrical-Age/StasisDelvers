package org.erisia.stasis.towergen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Timings and random layouts: ./gradlew :towergen:slowTest */
@Tag("slow")
class SlowTest {
    static final Map<String, Layout> TIMED = new java.util.LinkedHashMap<>();
    static {
        TIMED.put("H16", new Layout(16, List.of()));
        TIMED.put("H40", new Layout(40, List.of()));
        TIMED.put("H64max", new Layout(64, List.of(new Layout.Boom(24, 0, 40))));
        TIMED.put("H100", new Layout(100, List.of()));
    }

    @Test
    void timings() {
        long t0 = System.nanoTime();
        Library lib = Library.shared();
        System.out.printf("library load %d ms (%d entries)%n", lib.loadMs, lib.entries.size());
        TowerGen g = new TowerGen(lib);
        MeshBuilder mb = new MeshBuilder(lib.c, VisualParams.defaults());
        for (var e : TIMED.entrySet()) {
            for (double q : new double[]{1.0, 0.3}) {
                double[] st = new double[6], ps = new double[6], me = new double[6];
                int nf = 0, ncab = 0;
                for (int k = 0; k < 6; k++) {
                    TowerGen.Result r = g.build(e.getValue(), q);
                    long m0 = System.nanoTime();
                    Mesh m = mb.build(r);
                    me[k] = (System.nanoTime() - m0) / 1e6;
                    st[k] = r.msStatics(); ps[k] = r.msPass();
                    nf = r.analysis.nFree; ncab = r.analysis.nCab;
                }
                System.out.printf("TIMING %-7s q%.1f  n_free %4d cables %3d | statics+lookup+stitch: first %6.1f ms, warm %6.1f ms | runtime pass: first %7.1f ms, warm %7.1f ms | mesh warm %5.1f ms%n",
                        e.getKey(), q, nf, ncab, st[0], median(st), ps[0], median(ps), median(me));
            }
        }
    }

    static double median(double[] a) {
        double[] b = Arrays.copyOfRange(a, 1, a.length);
        Arrays.sort(b);
        return b[b.length / 2];
    }

    @Test
    void randomLayouts() {
        TowerGen g = new TowerGen(Library.shared());
        Random rng = new Random(Long.getLong("towergen.seed", 20261012L));
        int N = Integer.getInteger("towergen.random", 60);
        List<String> fails = new ArrayList<>();
        Map<String, Integer> tiers = new TreeMap<>(), boomTiers = new TreeMap<>();
        int pass = 0, runs = 0, over = 0;
        double maxMs = 0;
        for (int i = 0; i < N; i++) {
            Layout l = LayoutGen.random(rng);
            assertTrue(l.violations().isEmpty(), l + " " + l.violations());
            for (double q : new double[]{1.0, 0.3}) {
                runs++;
                try {
                    TowerGen.Result r = g.build(l, q);
                    for (String t : r.bayTiers) tiers.merge(t, 1, Integer::sum);
                    for (String t : r.boomTiers) boomTiers.merge(t, 1, Integer::sum);
                    boolean ok = r.info.pass;
                    maxMs = Math.max(maxMs, r.msStatics() + r.msPass());
                    if (ok) pass++;
                    else {
                        over++;
                        String msg = String.format("util %.3f slack %.4f %s q%.1f", r.analysis.maxUtil, r.analysis.minSlack, l, q);
                        if (l.H() <= 40) fails.add("H<=40 not passing: " + msg);
                        System.out.println("  over the catalogue: " + msg);
                    }
                    System.out.printf("RANDOM %3d q%.1f H%3d booms %2d: util %.3f slack %+.4f vol %.1f->%.1f rounds %d conv %d %.0f ms tiers %s%n",
                            i, q, l.H(), l.booms().size(), r.analysis.maxUtil, r.analysis.minSlack, r.info.volRaw, r.info.vol,
                            r.info.rounds, r.info.converted, r.msStatics() + r.msPass(), shortTiers(r.bayTiers));
                } catch (RuntimeException ex) {
                    fails.add(ex + " for " + l + " q" + q);
                    ex.printStackTrace(System.out);
                }
            }
        }
        System.out.printf("RANDOM SUMMARY: %d runs, %d pass util<=1 and taut, %d over (H>40 only allowed), exceptions/H<=40 failures %d; max %.0f ms%n  bay tiers %s%n  boom tiers %s%n",
                runs, pass, over, fails.size(), maxMs, tiers, boomTiers);
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    static String shortTiers(List<String> t) {
        Map<String, Integer> m = new TreeMap<>();
        for (String s : t) m.merge(s, 1, Integer::sum);
        return m.toString();
    }
}
