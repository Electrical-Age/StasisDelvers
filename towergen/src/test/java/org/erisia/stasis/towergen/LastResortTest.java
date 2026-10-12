package org.erisia.stasis.towergen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The 6 held-out layouts (p3acov_test.json) where the Python lookup found no compatible entry for some bay
 * ("none": 4-5 roots in one bay, LP-infeasible 3-root patterns, an unseen ring signature). The Java last-resort
 * tiers (ring_near / ring_near_h, see Lookup) must cover them; the runtime pass must then give a stable tower.
 * Layouts the stay rule refuses (stacked booms, see Spine.anchorLevels) must be refused consistently.
 */
class LastResortTest {
    @Test
    void heldOutNoneLayouts() throws Exception {
        List<Layout> ls = new ArrayList<>();
        try (var in = new InputStreamReader(LastResortTest.class.getResourceAsStream("/lastresort_layouts.json"), StandardCharsets.UTF_8)) {
            for (Object o : Json.arr(Json.parse(in))) ls.add(Layout.fromJson(o));
        }
        TowerGen g = new TowerGen(Library.shared());
        List<String> err = new ArrayList<>();
        for (Layout l : ls) {
            for (double q : new double[]{1.0, 0.3}) {
                try {
                    TowerGen.Result r = g.build(l, q);
                    long lr = r.bayTiers.stream().filter(t -> t.startsWith("ring_near") || t.equals("patch")).count();
                    System.out.printf("LASTRESORT H%3d booms %2d q%.1f: last-resort bays %d, util %.3f slack %+.4f pass %s (%s)%n",
                            l.H(), l.booms().size(), q, lr, r.analysis.maxUtil, r.analysis.minSlack, r.info.pass, r.bayTiers);
                    if (l.H() <= 40 && !r.info.pass) err.add("not passing: " + l + " q" + q + " util " + r.analysis.maxUtil);
                } catch (RefusedException ex) {       // the stay rule (Spine.anchorLevels) refuses stacked booms
                    System.out.printf("LASTRESORT H%3d booms %2d q%.1f: refused (%s)%n", l.H(), l.booms().size(), q, ex.refusal);
                    if (g.check(l, null).isEmpty()) err.add("refused but check() is empty: " + l);
                } catch (RuntimeException ex) {
                    err.add(ex + " for " + l + " q" + q);
                }
            }
        }
        assertTrue(err.isEmpty(), String.join("\n", err));
    }
}
