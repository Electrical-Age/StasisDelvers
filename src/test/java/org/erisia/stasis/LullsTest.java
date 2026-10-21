package org.erisia.stasis;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Lulls (Baughn 2026-10-21): 1.0 at the core fading to 0.4 at the edge; a sixteenth as common as deeps; a tenth as fast. */
class LullsTest {
    @Test
    void rateProfile() {
        var l = new Lulls.Lull(100, 200, 30, 1);
        assertEquals(1.0, l.rate(100, 200), 1e-9, "the core");
        assertEquals(1.0, l.rate(100 + 30 * Lulls.CORE, 200), 1e-9, "the core's edge");
        assertEquals(Lulls.EDGE_RATE, l.rate(130, 200), 1e-9, "the edge");
        assertEquals(0.0, l.rate(131, 200), 0, "outside");
        double mid = l.rate(100 + 30 * (Lulls.CORE + 1) / 2, 200);
        assertTrue(mid > Lulls.EDGE_RATE && mid < 1, "fading between: " + mid);
    }

    @Property(tries = 20)
    void aSixteenthAsCommonAsDeeps(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed) {
        // count centres in a 16 x 16 km square, against the deeps' cells at the same moment (the 80 % occupancy both)
        double range = 8192;
        int lulls = Lulls.within(0, 0, range, 0, seed).stream()
            .filter(l -> Math.abs(l.x()) <= range && Math.abs(l.z()) <= range).toList().size();
        double expected = 0.8 * (2 * range / Lulls.CELL) * (2 * range / Lulls.CELL);   // 204.8
        assertTrue(Math.abs(lulls - expected) < 45, lulls + " lulls, expected about " + expected);
        assertEquals(16, (Lulls.CELL / Deeps.CELL) * (Lulls.CELL / Deeps.CELL), 1e-9, "a 4x wider grid: 1/16 as many");
    }

    @Property(tries = 20)
    void aTenthAsFastAsDeeps(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed) {
        // over 2000 s of the deeps' clock, the wind carries a deep ~1-2.7 km; a lull is carried a tenth of that
        double t0 = 50000, dt = 2000;
        double[] a = Deeps.drift(t0 / 10, seed), b = Deeps.drift((t0 + dt) / 10, seed), da = Deeps.drift(t0, seed), db = Deeps.drift(t0 + dt, seed);
        double lull = Math.hypot(b[0] - a[0], b[1] - a[1]), deep = Math.hypot(db[0] - da[0], db[1] - da[1]);
        assertTrue(lull > 0 && lull < deep / 5, "lull wind " + lull + " vs deep wind " + deep);
        assertTrue(lull / dt < 0.2, "about 0.1 blocks/s: " + lull / dt);
    }
}
