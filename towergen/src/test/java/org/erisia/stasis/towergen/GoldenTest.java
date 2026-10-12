package org.erisia.stasis.towergen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Golden tests against the Python reference (p3a_goldens.json.gz). Stage by stage: rings, boom keys, back-stays,
 * bay keys / rotations / lookup tiers, low-q variant choice, stitched topology; then the runtime pass invariants
 * (util <= 1.001 in all 11 cases, cables taut, volume within 5%, outer widths within one ladder step).
 */
class GoldenTest {
    static List<Map<String, Object>> goldens;
    static TowerGen gen;

    @BeforeAll
    static void setup() throws Exception {
        goldens = Goldens.load();
        Library lib = Library.shared();
        System.out.println("library: " + lib.entries.size() + " entries, loaded in " + lib.loadMs + " ms");
        gen = new TowerGen(lib);
    }

    @TestFactory
    Stream<DynamicTest> stitchMatchesGolden() {
        return goldens.stream().map(g -> DynamicTest.dynamicTest(Goldens.name(g), () -> {
            TowerGen.Result r = gen.stitchOnly(Goldens.layout(g), Json.num(g.get("q")));
            List<String> err = Goldens.compareStitch(g, r);
            assertTrue(err.isEmpty(), Goldens.name(g) + ":\n  " + String.join("\n  ", err));
        }));
    }

    @TestFactory
    Stream<DynamicTest> runtimePassInvariants() {
        return goldens.stream().map(g -> DynamicTest.dynamicTest(Goldens.name(g), () -> {
            TowerGen.Result r = gen.build(Goldens.layout(g), Json.num(g.get("q")));
            Map<String, Object> fin = Json.obj(g.get("final"));
            Map<String, Object> tol = Json.obj(g.get("tolerances"));
            RuntimePass.Analysis an = r.analysis;
            List<String> err = new ArrayList<>();
            double umax = Json.num(tol.get("util_max"));
            for (int k = 0; k < an.utilPerCase.length; k++)
                if (an.utilPerCase[k] > umax) err.add("case " + k + " util " + an.utilPerCase[k]);
            if (an.minSlack < Json.num(tol.get("min_slack"))) err.add("slack cable: margin " + an.minSlack);
            double vg = Json.num(fin.get("vol"));
            if (Math.abs(r.info.vol - vg) > Json.num(tol.get("vol_rel")) * vg) err.add("volume " + r.info.vol + " vs " + vg);
            List<Object> secs = Json.arr(fin.get("sections"));
            List<Object> added = Json.arr(fin.get("members_added"));
            if (!added.isEmpty()) err.add("golden added members (not expected for fixf)");
            int over = 0;
            String first = null;
            if (secs.size() != r.tower.m) err.add("member count " + r.tower.m + " vs " + secs.size());
            else for (int b = 0; b < secs.size(); b++) {
                int[] s = Json.iarr(secs.get(b));
                int d = Goldens.steps(r.tower.kind[b], r.tower.ws[b], s[0], s[1]);
                if (d > Json.inum(tol.get("outer_steps"))) {
                    over++;
                    if (first == null) first = "member " + b + " (" + r.tower.kind[b] + "," + r.tower.ws[b] + "," + r.tower.cs[b] + ") vs " + java.util.Arrays.toString(s);
                }
            }
            if (over > 0) err.add(over + " members beyond one width step, first: " + first);
            System.out.printf("%-12s util %.4f (golden %.3f) slack %.4f vol %.2f -> %.2f (golden %.2f -> %.2f) rounds %d (%s) conv %d legfix %d (%s)  statics %.1f ms pass %.1f ms%n",
                    Goldens.name(g), an.maxUtil, Json.num(fin.get("max_util")), an.minSlack, r.info.volRaw, r.info.vol,
                    Json.num(fin.get("vol_raw")), vg, r.info.rounds, fin.get("rounds"), r.info.converted, r.info.legFix,
                    fin.get("leg_fix"), r.msStatics(), r.msPass());
            assertTrue(err.isEmpty(), Goldens.name(g) + ":\n  " + String.join("\n  ", err));
            assertEquals(r.tower.m, r.raw.m);
        }));
    }
}
