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
        assertTrue(Library.shared().c.BOOM_FAILED.contains("[9, 1, 6, 6]"), "the library lists its failed boom solves");
    }

    @Test
    void randomLayoutRefusalRate() {
        Consts c = Library.shared().c;
        Random rng = new Random(20261013);
        int n = 4000, same = 0, any = 0, withBooms = 0;
        Consts ca = c.forSpine(SolverParams.fromJson(Json.parse("{\"stayCap\": \"any\"}"), c));
        for (int i = 0; i < n; i++) {
            Layout l = LayoutGen.random(rng);
            if (!l.booms().isEmpty()) withBooms++;
            List<Refusal> r = new ArrayList<>();
            Spine.anchorLevels(l, c, r);
            if (!r.isEmpty()) same++;
            r.clear();
            Spine.anchorLevels(l, ca, r);
            if (!r.isEmpty()) any++;
        }
        System.out.printf("stay rule: %d random layouts (%d with booms): refused %d (%.1f%%) under \"same\", %d (%.1f%%) under \"any\"%n",
                n, withBooms, same, 100.0 * same / n, any, 100.0 * any / n);
        Layout b = LayoutGen.randomBuildable(new Random(5), c);
        assertTrue(new TowerGen(Library.shared()).check(b, null).isEmpty());
    }
}
