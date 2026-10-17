package org.erisia.stasis.towergen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The stay rule (Spine.anchorLevels): stays anchor at or below the next boom level above, else the layout is refused. */
class StayRuleTest {
    static final Layout H40R3 = layout(40, 10, 16, 20, 10, 30, 6);

    static Layout layout(int H, int... yl) {
        List<Layout.Boom> b = new ArrayList<>();
        for (int i = 0; i < yl.length; i += 2) for (int d = 0; d < 4; d++) b.add(new Layout.Boom(yl[i], d, yl[i + 1]));
        return new Layout(H, b);
    }

    /** Every boom's stays anchor at or below the next boom above it in the same direction (and above the boom). */
    static void assertCapped(TowerGen.Result r, boolean anyDir) {
        List<Spine.BoomInfo> bs = r.spine.booms;
        for (Spine.BoomInfo b : bs) {
            assertTrue(b.ya > b.yb, "anchor above the boom");
            for (Spine.BoomInfo o : bs)
                if (o.yb > b.yb && (anyDir || o.dir == b.dir))
                    assertTrue(b.ya <= o.yb, "boom y" + b.yb + " anchors at y" + b.ya + " above the boom at y" + o.yb);
        }
        // and in the stitched tower: each stay's mast end is at the recorded anchor height
        Stitch.Tower t = r.raw;
        for (int k = 0; k < t.boomAnchors.length; k++)
            for (int a : t.boomAnchors[k]) assertEquals(bs.get(k).ya, t.P[3 * a + 1], 1e-9);
    }

    @Test
    void h40r3StaysStayBelowTheBoomsAbove() {
        TowerGen g = new TowerGen(Library.shared());
        TowerGen.Result r = g.build(H40R3, 1.0);
        assertCapped(r, false);
        assertEquals(20, r.spine.booms.get(0).ya);        // was 24 (through the y20 booms)
        assertTrue(r.refusal == null && r.analysis.maxUtil <= 1.001);
        // the old rule is still available (and puts the anchor through the y20 booms)
        SolverParams none = SolverParams.fromJson(Json.parse("{\"stayCap\": \"none\"}"), g.c);
        assertEquals(24, g.build(H40R3, 1.0, none).spine.booms.get(0).ya);
    }

    @Test
    void refusesWhenTheRiseIsTooSmall() {
        TowerGen g = new TowerGen(Library.shared());
        // E booms at y10 (L16) and y13: the highest ring level <= 13 is 12, rise 2 < 0.35 x 16
        Layout l = new Layout(40, List.of(new Layout.Boom(10, 0, 16), new Layout.Boom(13, 0, 6)));
        List<Refusal> pre = g.check(l, null);
        assertEquals(1, pre.size());
        assertEquals(Refusal.Reason.STAY_RISE, pre.get(0).reason());
        RefusedException ex = assertThrows(RefusedException.class, () -> g.build(l, 1.0));
        assertEquals(Refusal.Reason.STAY_RISE, ex.refusal.reason());
        assertThrows(RefusedException.class, () -> g.build(l, 1.0, SolverParams.defaults(g.c)));
        System.out.println("refusal: " + ex.refusal.message());
        // a perpendicular boom above does not cap the stays under "same", but does under "any"
        Layout p = new Layout(40, List.of(new Layout.Boom(10, 0, 16), new Layout.Boom(13, 1, 6)));
        assertTrue(g.check(p, null).isEmpty());
        assertEquals(1, g.check(p, SolverParams.fromJson(Json.parse("{\"stayCap\": \"any\"}"), g.c)).size());
    }

    /**
     * stayAtBoomAbove (off by default): booms 2-3 blocks apart on one face. The ring level under the upper boom is too
     * low, so the lower boom's stays end at the upper boom's root nodes (merged by the stitch: mast and upper-boom
     * members meet there). Known bad cases under the knob (the library is not trained for it, NOTES "Stays between
     * rings") are pinned so a library rebuild shows them flipping.
     */
    @Test
    void stackedBoomsHangFromTheBoomAbove() {
        TowerGen g = new TowerGen(Library.shared());
        SolverParams on = SolverParams.fromJson(Json.parse("{\"stayAtBoomAbove\": true}"), g.c);
        // E y10 L6 under E y13 L8: ring 12 gives rise 2 < 0.35 x 6 = 2.1; y13 gives 3
        Layout l = new Layout(40, List.of(new Layout.Boom(10, 0, 6), new Layout.Boom(13, 0, 8)));
        assertEquals(1, g.check(l, null).size(), "refused by default");
        assertTrue(g.check(l, on).isEmpty());
        for (double q : new double[]{1.0, 0.3}) {
            TowerGen.Result r = g.build(l, q, on);
            assertEquals(13, r.spine.booms.get(0).ya);
            assertCapped(r, false);
            assertTrue(r.refusal == null && r.analysis.maxUtil <= 1.001, "util " + r.analysis.maxUtil);
            Stitch.Tower t = r.raw;
            for (int a : t.boomAnchors[0]) {
                boolean mast = false, upper = false;
                for (int b = 0; b < t.m; b++) {
                    if (t.bi[b] != a && t.bj[b] != a) continue;
                    if (t.ownerType[b] == 0) mast = true;
                    if (t.ownerType[b] == 1 && t.ownerIdx[b] == 1) upper = true;
                }
                assertTrue(mast && upper, "anchor node " + a + " is a mast node at the upper boom's root");
            }
            System.out.printf("stacked E y10 L6 / y13 L8 q%.1f: util %.3f, vol %.1f%n", q, r.analysis.maxUtil, r.info.vol);
        }
        // one block apart: rise 1 < stayMinRise 2, still refused
        assertEquals(1, g.check(new Layout(40, List.of(new Layout.Boom(10, 0, 4), new Layout.Boom(11, 0, 4))), on).size());
        // under "any" a perpendicular boom caps the stays, but its roots are on another face: still refused
        Layout p = new Layout(40, List.of(new Layout.Boom(10, 0, 6), new Layout.Boom(13, 1, 8)));
        assertEquals(1, g.check(p, SolverParams.fromJson(Json.parse("{\"stayCap\": \"any\", \"stayAtBoomAbove\": true}"), g.c)).size());
        // known bad under the knob: the hang node is a mid-leg root node braced only for the upper boom's reactions
        Layout bad1 = new Layout(15, List.of(new Layout.Boom(3, 1, 6), new Layout.Boom(6, 1, 2)));
        TowerGen.Result r1 = g.build(bad1, 1.0, on);
        assertTrue(r1.refusal != null && r1.analysis.maxUtil > 5, "H15 S y3 L6 / y6 L2: util " + r1.analysis.maxUtil);
        Layout bad2 = new Layout(40, List.of(new Layout.Boom(2, 1, 8), new Layout.Boom(5, 1, 6)));
        assertThrows(RefusedException.class, () -> g.build(bad2, 1.0));
        boolean failed;
        try {
            TowerGen.Result r2 = g.build(bad2, 1.0, on);
            failed = r2.refusal != null || r2.analysis == null;      // a mechanism under build(.., sp): a warning, no analysis
            System.out.println("H40 S y2 L8 / y5 L6 under stayAtBoomAbove: " + r2.refusal + ", warnings " + r2.warnings);
        } catch (RuntimePass.MechanismException e) {
            failed = true;
            System.out.println("H40 S y2 L8 / y5 L6 under stayAtBoomAbove: " + e.getMessage());
        }
        assertTrue(failed, "H40 S y2 L8 / y5 L6: mechanism at the lower boom's tip");
    }

    @Test
    void failedBoomKeyIsRefusedNotApproximated() {
        TowerGen g = new TowerGen(Library.shared());
        Layout l = Layout.fromJson(Json.parse("{\"H\":38,\"booms\":[[2,\"W\",36],[14,\"E\",7],[16,\"N\",8],[24,\"N\",9],[26,\"W\",10],[29,\"S\",3],[35,\"S\",2]]}"));
        TowerGen.Result r = g.stitchOnly(l, 1.0);
        assertEquals("near", r.spine.booms.get(0).tier);       // key [9, 1, 9, 6]: not in the library, nearest entry
        String k = java.util.Arrays.toString(r.spine.booms.get(0).ikey);
        g.c.BOOM_FAILED.add(k);                                  // pretend its solve failed
        try {
            RefusedException ex = assertThrows(RefusedException.class, () -> g.build(l, 1.0));
            assertEquals(Refusal.Reason.NO_BOOM_DESIGN, ex.refusal.reason());
            System.out.println("refusal: " + ex.refusal.message());
        } finally {
            g.c.BOOM_FAILED.remove(k);
        }
        // the strength 1.4 library (2026-10-17): no boom solve failed repair; at 1.0 the header listed [9, 1, 6, 6]
        assertTrue(Library.shared().c.BOOM_FAILED.isEmpty(), "the library header lists no failed boom solves");
    }

    @Test
    void randomLayoutRefusalRate() {
        Consts c = Library.shared().c;
        Random rng = new Random(20261013);
        int n = 4000, same = 0, any = 0, ring = 0, withBooms = 0;
        Consts ca = c.forSpine(SolverParams.fromJson(Json.parse("{\"stayCap\": \"any\"}"), c));
        Consts cr = c.forSpine(SolverParams.fromJson(Json.parse("{\"stayAtBoomAbove\": true}"), c));
        for (int i = 0; i < n; i++) {
            Layout l = LayoutGen.random(rng);
            if (!l.booms().isEmpty()) withBooms++;
            List<Refusal> r = new ArrayList<>();
            Spine.anchorLevels(l, c, r);
            if (!r.isEmpty()) same++;
            r.clear();
            Spine.anchorLevels(l, ca, r);
            if (!r.isEmpty()) any++;
            r.clear();
            Spine.anchorLevels(l, cr, r);
            if (!r.isEmpty()) ring++;
        }
        System.out.printf("stay rule: %d random layouts (%d with booms): refused %d (%.1f%%) under \"same\", %d (%.1f%%) under \"any\", "
                + "%d (%.1f%%) under \"same\" with stayAtBoomAbove%n",
                n, withBooms, same, 100.0 * same / n, any, 100.0 * any / n, ring, 100.0 * ring / n);
        assertTrue(ring <= same, "hanging from the boom above only accepts more");
        Layout b = LayoutGen.randomBuildable(new Random(5), c);
        assertTrue(new TowerGen(Library.shared()).check(b, null).isEmpty());
    }
}
