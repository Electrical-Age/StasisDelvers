package org.erisia.stasis;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rate gate (Stasis.runs, dueTick), storm-warped time (Storms.tau) and the time-of-day wrap (LocalTime). */
class TickGateTest {
    // game time up to ~1.6 million real years at 20 TPS: far past any world, still clear of the long overflow at 9e15
    static final long T_MAX = 1_000_000_000_000_000L;

    static int count(double rate, long from, long n) {
        int k = 0;
        for (long t = from; t < from + n; t++) if (Stasis.runs(rate, t)) k++;
        return k;
    }

    /** Over any n ticks a block at rate r runs floor-difference times: within one of n r, never drifting. */
    @Property(tries = 2000)
    void runsCountMatchesTheRate(@ForAll @DoubleRange(min = 0, max = 1) @net.jqwik.api.constraints.Scale(4) double rate, @ForAll @LongRange(min = 0, max = T_MAX) long t,
                                 @ForAll @IntRange(min = 1, max = 2000) int n) {
        long m = Stasis.milli(rate);
        int k = count(rate, t, n);
        assertEquals(Math.floorDiv((t + n - 1) * m, 1000) - Math.floorDiv((t - 1) * m, 1000), k);
        assertTrue(Math.abs(k - n * m / 1000.0) < 1, () -> k + " of " + n + " at " + m + "/1000");
    }

    @Property(tries = 500)
    void fullRateAlwaysRunsAndZeroNever(@ForAll @LongRange(min = 0, max = T_MAX) long t) {
        assertTrue(Stasis.runs(1.0, t));
        assertTrue(Stasis.runs(0.9996, t));
        assertFalse(Stasis.runs(0.0, t));
        assertFalse(Stasis.runs(0.0004, t));
    }

    /** A scheduled tick falls due on a running tick, `delay` running ticks after now: no sooner, no later. */
    @Property(tries = 2000)
    void dueTickLandsOnTheDelayThRunningTick(@ForAll @DoubleRange(min = 0.001, max = 0.999) @net.jqwik.api.constraints.Scale(4) double rate,
                                             @ForAll @LongRange(min = 0, max = T_MAX) long now, @ForAll @IntRange(min = 1, max = 400) int delay) {
        long due = Stasis.dueTick(rate, now, delay);
        assertTrue(due > now);
        assertTrue(Stasis.runs(rate, due), "due on a stopped tick");
        assertEquals(delay, count(rate, now + 1, due - now), "running ticks in (now, due]");
        // monotone in the delay
        assertTrue(Stasis.dueTick(rate, now, delay + 1) > due);
    }

    @Property(tries = 500)
    void dueTickAtFullRateOrNoDelayIsVanilla(@ForAll @LongRange(min = 0, max = T_MAX) long now, @ForAll @IntRange(min = -5, max = 400) int delay) {
        assertEquals(now + delay, Stasis.dueTick(1.0, now, delay));
        if (delay <= 0) assertEquals(now + delay, Stasis.dueTick(0.5, now, delay));
    }

    // ---- storms

    @Property(tries = 2000)
    void tauIsMonotoneAndWithoutJumps(@ForAll @IntRange(min = 1, max = Integer.MAX_VALUE) int seed,
                                      @ForAll @DoubleRange(min = 0, max = 4e9) double t, @ForAll @DoubleRange(min = 0, max = 600) double dt) {
        double a = Storms.tau(seed, null, t), b = Storms.tau(seed, null, t + dt);
        // tau runs at 1 to 10 times the real clock (t + 9 x the storm integral, intensity <= 1)
        double slack = 1e-4;
        assertTrue(b - a >= dt - slack, () -> "tau slower than real time at " + t);
        assertTrue(b - a <= 10 * dt + slack, () -> "tau jumps at " + t + ": " + (b - a) + " over " + dt);
    }

    /** At the edges between storm blocks, where tau switches to the next block's storm. */
    @Property(tries = 1000)
    void tauIsContinuousAcrossBlockEdges(@ForAll @IntRange(min = 1, max = Integer.MAX_VALUE) int seed, @ForAll @IntRange(min = 1, max = 100_000) int n) {
        double B = Storms.block(), e = 1e-3;
        double a = Storms.tau(seed, null, n * B - e), b = Storms.tau(seed, null, n * B + e);
        assertTrue(b - a >= 2 * e - 1e-4 && b - a <= 20 * e + 1e-4, () -> "edge " + n + ": " + (b - a));
    }

    @Property(tries = 500)
    void tauWithAnOverrideIsMonotoneToo(@ForAll @IntRange(min = 1, max = 1_000_000) int seed, @ForAll @DoubleRange(min = 0, max = 1e6) double start,
                                        @ForAll @DoubleRange(min = 240, max = 7200) double len, @ForAll @DoubleRange(min = 0, max = 1e5) double folded,
                                        @ForAll @DoubleRange(min = -1, max = 2) double u, @ForAll @DoubleRange(min = 0, max = 60) double dt) {
        double[] o = {start, start + len, folded};
        double t = Math.max(0, start + u * len);
        double a = Storms.tau(seed, o, t), b = Storms.tau(seed, o, t + dt);
        // two storms can overlap (scheduled and override): up to 1 + 9 + 9
        assertTrue(b - a >= dt - 1e-6 && b - a <= 19 * dt + 1e-6, () -> (b - a) + " over " + dt);
    }

    @Property(tries = 500)
    void integralIsTheIntegralOfIntensity(@ForAll @DoubleRange(min = 0, max = 1e6) double start, @ForAll @DoubleRange(min = 1, max = 7200) double len,
                                          @ForAll @DoubleRange(min = -0.2, max = 1.2) double u) {
        Storms.Storm s = new Storms.Storm(start, len);
        double t = start + u * len;
        int steps = 40000;
        double a = start, h = (Math.max(a, Math.min(t, s.end())) - a) / steps, sum = 0;
        for (int i = 0; i < steps; i++) {
            double i0 = Storms.intensity(s, a + i * h), i1 = Storms.intensity(s, a + (i + 1) * h);
            assertTrue(i0 >= 0 && i0 <= 1);
            sum += (i0 + i1) / 2 * h;
        }
        assertEquals(sum, Storms.integral(s, t), 1e-3 * (1 + len / steps));
    }

    @Property(tries = 1000)
    void scheduledStormStaysInItsBlock(@ForAll @IntRange(min = 1, max = Integer.MAX_VALUE) int seed, @ForAll @LongRange(min = 0, max = 10_000_000) long n) {
        Storms.Storm s = Storms.scheduled(seed, n);
        double B = Storms.block();
        assertTrue(s.start() >= n * B && s.end() <= (n + 1) * B + 1e-6, () -> s + " in block " + n);
        assertEquals(s, Storms.scheduled(seed, n));
    }

    // ---- local time

    @Property(tries = 2000)
    void wrapDiffIsTheShortWayRound(@ForAll @DoubleRange(min = -1e7, max = 1e7) double from, @ForAll @DoubleRange(min = -1e7, max = 1e7) double to) {
        double d = LocalTime.wrapDiff(from, to);
        assertTrue(d > -LocalTime.DAY / 2 - 1e-9 && d <= LocalTime.DAY / 2 + 1e-9, () -> "wrapDiff " + d);
        double w = LocalTime.wrap(from + d), v = LocalTime.wrap(to);
        assertTrue(Math.abs(LocalTime.wrapDiff(w, v)) < 1e-6, () -> w + " vs " + v);
        double x = LocalTime.wrap(to);
        assertTrue(x >= 0 && x < LocalTime.DAY);
    }

    @Example
    void nightIsTheBedSpan() {
        assertFalse(LocalTime.night(12541));
        assertTrue(LocalTime.night(12542));
        assertTrue(LocalTime.night(23459 + 24000 * 7));
        assertFalse(LocalTime.night(-1));
    }
}
