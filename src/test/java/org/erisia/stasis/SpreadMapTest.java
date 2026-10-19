package org.erisia.stasis;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayDeque;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spreading mode's map (SpreadMap) and clock (Spread): the running land is always one piece with spawn in it, at
 * cell level and block by block through the bilinear; the ranks are a permutation; the build is deterministic; the
 * clock never jumps and its coverage follows the formula.
 */
class SpreadMapTest {
    static SpreadMap map(int seed, int sx, int sz, int radius) {
        return SpreadMap.build(seed, sx, sz, radius);
    }

    /** Ranks inside the disc are exactly 0..N-1, the spawn cell's is N-1, and cells outside are -1. */
    @Property(tries = 40)
    void ranksArePermutation(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -200000, max = 200000) int sx,
                             @ForAll @IntRange(min = -200000, max = 200000) int sz, @ForAll @IntRange(min = 64, max = 1500) int radius) {
        SpreadMap m = map(seed, sx, sz, radius);
        int[] in = Arrays.stream(m.ranks).filter(r -> r >= 0).sorted().toArray();
        assertEquals(m.count, in.length);
        for (int i = 0; i < in.length; i++) assertEquals(i, in[i]);
        assertTrue(Arrays.stream(m.ranks).allMatch(r -> r >= -1));
        // the spawn column's continuous rank is among the last four (its patch's cells went first)
        assertTrue(m.rank1024(sx, sz) >= (long) (m.count - 4) * SpreadMap.SCALE);
        // every disc cell is in (the cell centres within the radius), and none beyond
        long r2 = (long) radius * radius;
        for (int k = 0; k < m.n; k++)
            for (int i = 0; i < m.n; i++) {
                long dx = (m.cellX0 + i) * (long) SpreadMap.CELL + SpreadMap.HALF - sx, dz = (m.cellZ0 + k) * (long) SpreadMap.CELL + SpreadMap.HALF - sz;
                assertEquals(dx * dx + dz * dz <= r2, m.ranks[i + k * m.n] >= 0);
            }
    }

    @Property(tries = 20)
    void deterministic(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -5000, max = 5000) int sx,
                       @ForAll @IntRange(min = -5000, max = 5000) int sz, @ForAll @IntRange(min = 64, max = 1200) int radius) {
        assertArrayEquals(map(seed, sx, sz, radius).ranks, map(seed, sx, sz, radius).ranks);
    }

    /**
     * For every P the running cells {rank >= P} are 4-connected and hold spawn. Equivalent to: every cell but spawn has
     * a 4-neighbour of higher rank (then a strictly rising walk from any running cell stays running and ends at the top,
     * which is spawn), which checks all P at once.
     */
    @Property(tries = 60)
    void runningCellsAreOnePieceAtEveryP(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -100000, max = 100000) int sx,
                                         @ForAll @IntRange(min = -100000, max = 100000) int sz, @ForAll @IntRange(min = 64, max = 3000) int radius) {
        SpreadMap m = map(seed, sx, sz, radius);
        int top = m.count - 1;
        for (int k = 0; k < m.n; k++)
            for (int i = 0; i < m.n; i++) {
                int r = m.ranks[i + k * m.n];
                if (r < 0 || r == top) continue;
                int best = -1;
                if (i > 0) best = Math.max(best, m.ranks[i - 1 + k * m.n]);
                if (i < m.n - 1) best = Math.max(best, m.ranks[i + 1 + k * m.n]);
                if (k > 0) best = Math.max(best, m.ranks[i + (k - 1) * m.n]);
                if (k < m.n - 1) best = Math.max(best, m.ranks[i + (k + 1) * m.n]);
                assertTrue(best > r, "cell " + i + "," + k + " rank " + r + " has no higher neighbour");
            }
    }

    /** The running columns of a map at progress p, over its bounding box: [w*h], spawn at index sp. */
    record Blocks(boolean[] run, int w, int h, int x0, int z0) {
        static Blocks of(SpreadMap m, int p) {
            int x0 = m.cellX0 * SpreadMap.CELL - SpreadMap.CELL, z0 = m.cellZ0 * SpreadMap.CELL - SpreadMap.CELL;
            int w = (m.n + 2) * SpreadMap.CELL, h = w;
            boolean[] run = new boolean[w * h];
            for (int z = 0; z < h; z++)
                for (int x = 0; x < w; x++) run[x + z * w] = !SpreadMap.stilled(m, x0 + x, z0 + z, p);
            return new Blocks(run, w, h, x0, z0);
        }

        /** Blocks reached from (x, z) by 4-steps over running blocks. */
        boolean[] reach(int x, int z) {
            boolean[] seen = new boolean[run.length];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            int s = (x - x0) + (z - z0) * w;
            if (!run[s]) return seen;
            seen[s] = true;
            q.add(s);
            while (!q.isEmpty()) {
                int c = q.poll(), cx = c % w, cz = c / w;
                for (int d = 0; d < 4; d++) {
                    int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = cz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                    int nc = nx + nz * w;
                    if (run[nc] && !seen[nc]) {
                        seen[nc] = true;
                        q.add(nc);
                    }
                }
            }
            return seen;
        }
    }

    /**
     * Block by block (the bilinear between cell centres, as rateAt asks): at sampled P every running column is
     * 4-connected to the spawn column, and the spawn column runs until P = N.
     */
    @Property(tries = 25)
    void runningColumnsAreOnePiece(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -50000, max = 50000) int sx,
                                   @ForAll @IntRange(min = -50000, max = 50000) int sz, @ForAll @IntRange(min = 64, max = 400) int radius,
                                   @ForAll @DoubleRange(min = 0, max = 1) double share) {
        SpreadMap m = map(seed, sx, sz, radius);
        int p = Math.min(m.count - 4, (int) (share * m.count));
        for (int pp : new int[]{p, m.count - 4, m.count / 2}) {
            Blocks b = Blocks.of(m, pp);
            // from the spawn column (it runs while P <= N-4)
            boolean[] seen = b.reach(sx, sz);
            int running = 0, reached = 0;
            for (int i = 0; i < seen.length; i++) {
                if (b.run[i]) running++;
                if (seen[i]) reached++;
            }
            assertTrue(running > 0 && running == reached, "P " + pp + ": " + (running - reached) + " running columns cut off from spawn");
        }
        // at P = N everything is stilled
        assertTrue(SpreadMap.stilled(m, sx, sz, m.count));
    }

    /** A chunk's least and greatest rank1024 are the extremes over its columns (sectionState relies on it). */
    @Property(tries = 30)
    void chunkExtremesAreExact(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -3000, max = 3000) int sx,
                               @ForAll @IntRange(min = -3000, max = 3000) int sz, @ForAll @IntRange(min = -30, max = 30) int ccx,
                               @ForAll @IntRange(min = -30, max = 30) int ccz) {
        SpreadMap m = map(seed, sx, sz, 400);
        int cx = (sx >> 4) + ccx, cz = (sz >> 4) + ccz;
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                long r = m.rank1024((cx << 4) + x, (cz << 4) + z);
                lo = Math.min(lo, r);
                hi = Math.max(hi, r);
            }
        assertEquals(lo, SpreadMap.chunkMin1024(m, cx, cz));
        assertEquals(hi, SpreadMap.chunkMax1024(m, cx, cz));
    }

    /**
     * Not an assertion, a report (Argon asked about minimum corridor widths): for a realistic map, how much running land
     * hangs behind a narrow pinch at a few coverages. Width at a column = 2 d - 1, d its Chebyshev distance to stilled
     * land; a region's pinch is the widest path's narrowest point from spawn.
     */
    @Example
    void pinchReport() {
        int radius = 2000;
        StringBuilder out = new StringBuilder("spread pinch report (radius " + radius + "):\n");
        for (int seed : new int[]{12345, 777777, 4242424}) {
            long t0 = System.nanoTime();
            SpreadMap m = map(seed, 100, -200, radius);
            out.append(String.format("seed %d: %d cells, built in %d ms%n", seed, m.count, (System.nanoTime() - t0) / 1_000_000));
            for (double c : new double[]{0.2, 0.5, 0.8, 0.95, 0.99}) {
                int p = (int) (c * m.count);
                Blocks b = Blocks.of(m, p);
                int[] d = chebyshev(b);
                int[] widest = widest(b, d, 100, -200);
                // count only columns with some room round them (d >= 4: 7+ wide), so the edge itself isn't a "pinch"
                int room = 0, w1 = 0, w3 = 0, w8 = 0, w16 = 0, worst = Integer.MAX_VALUE;
                for (int i = 0; i < d.length; i++) {
                    if (!b.run[i] || d[i] < 4) continue;
                    room++;
                    int pinch = 2 * widest[i] - 1;
                    worst = Math.min(worst, pinch);
                    if (pinch <= 1) w1++;
                    if (pinch <= 3) w3++;
                    if (pinch <= 8) w8++;
                    if (pinch <= 16) w16++;
                }
                out.append(String.format("  c %.2f: %d roomy running cols, spawn's own width %d; behind a pinch <=1: %.2f%%, <=3: %.2f%%, <=8: %.2f%%, <=16: %.2f%%; narrowest %d%n",
                    c, room, 2 * d[(100 - b.x0()) + (-200 - b.z0()) * b.w()] - 1, 100.0 * w1 / room, 100.0 * w3 / room, 100.0 * w8 / room, 100.0 * w16 / room, worst));
            }
        }
        System.out.println(out);
        StasisDelvers.LOG.info("{}", out);
    }

    /** Build times at the default radius and the largest allowed (logged, not asserted: machines differ). */
    @Example
    void buildTimes() {
        for (int r : new int[]{8000, 8000, 30000}) {
            long t0 = System.nanoTime();
            SpreadMap m = map(4242424, -37, 1234, r);
            String l = String.format("spread map radius %d: %d cells in %d ms", r, m.count, (System.nanoTime() - t0) / 1_000_000);
            System.out.println(l);
            StasisDelvers.LOG.info(l);
        }
    }

    /** Chebyshev distance of each running block to the nearest stilled one (two-pass chamfer). */
    static int[] chebyshev(Blocks b) {
        int w = b.w(), h = b.h();
        int[] d = new int[w * h];
        int big = 1 << 20;
        for (int i = 0; i < d.length; i++) d[i] = b.run()[i] ? big : 0;
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                int i = x + z * w;
                if (d[i] == 0) continue;
                int v = d[i];
                if (x > 0) v = Math.min(v, d[i - 1] + 1);
                if (z > 0) {
                    v = Math.min(v, d[i - w] + 1);
                    if (x > 0) v = Math.min(v, d[i - w - 1] + 1);
                    if (x < w - 1) v = Math.min(v, d[i - w + 1] + 1);
                }
                if (x == 0 || z == 0) v = Math.min(v, 1);
                d[i] = v;
            }
        for (int z = h - 1; z >= 0; z--)
            for (int x = w - 1; x >= 0; x--) {
                int i = x + z * w;
                if (d[i] == 0) continue;
                int v = d[i];
                if (x < w - 1) v = Math.min(v, d[i + 1] + 1);
                if (z < h - 1) {
                    v = Math.min(v, d[i + w] + 1);
                    if (x < w - 1) v = Math.min(v, d[i + w + 1] + 1);
                    if (x > 0) v = Math.min(v, d[i + w - 1] + 1);
                }
                if (x == w - 1 || z == h - 1) v = Math.min(v, 1);
                d[i] = v;
            }
        return d;
    }

    /** Max over 4-paths from (sx, sz) of the least d on the path (bucket queue, highest first). */
    static int[] widest(Blocks b, int[] d, int sx, int sz) {
        int w = b.w(), h = b.h();
        int[] best = new int[d.length];
        int s = (sx - b.x0()) + (sz - b.z0()) * w;
        int maxD = 0;
        for (int v : d) maxD = Math.max(maxD, v);
        @SuppressWarnings("unchecked") ArrayDeque<Integer>[] buckets = new ArrayDeque[maxD + 1];
        for (int i = 0; i <= maxD; i++) buckets[i] = new ArrayDeque<>();
        best[s] = d[s];
        buckets[d[s]].add(s);
        for (int lvl = maxD; lvl >= 1; lvl--) {
            while (!buckets[lvl].isEmpty()) {
                int c = buckets[lvl].poll();
                if (best[c] != lvl) continue;
                int cx = c % w, cz = c / w;
                for (int k = 0; k < 4; k++) {
                    int nx = cx + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = cz + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                    int nc = nx + nz * w;
                    int v = Math.min(lvl, d[nc]);
                    if (v > best[nc]) {
                        best[nc] = v;
                        buckets[v].add(nc);
                    }
                }
            }
        }
        return best;
    }

    // ---- saved data ----------------------------------------------------------------------------------------------

    /** The map and the clock survive a save (NBT) round trip; an empty or old save gives defaults (no map). */
    @Example
    void savedDataRoundTrip() {
        var ops = net.minecraft.nbt.NbtOps.INSTANCE;
        SpreadMap m = map(99, 40, -70, 200);
        Spread.MapData d = new Spread.MapData();
        d.set(m);
        var tag = Spread.MapData.CODEC.encodeStart(ops, d).getOrThrow();
        Spread.MapData back = Spread.MapData.CODEC.parse(ops, tag).getOrThrow();
        assertArrayEquals(m.ranks, back.ranks);
        assertEquals(40, back.spawnX);
        assertEquals(-70, back.spawnZ);
        assertEquals(200, back.radius);
        assertArrayEquals(m.ranks, new SpreadMap(back.spawnX, back.spawnZ, back.radius, back.ranks).ranks);
        Spread.MapData empty = Spread.MapData.CODEC.parse(ops, new net.minecraft.nbt.CompoundTag()).getOrThrow();
        assertEquals(null, empty.ranks);

        Spread.Clock k = Spread.Clock.CODEC.parse(ops, new net.minecraft.nbt.CompoundTag()).getOrThrow();
        assertEquals(0, k.seconds);
        k.seconds = 12345.5;
        k.hard = true;
        k.hardAt = 99.25;
        Spread.Clock kb = Spread.Clock.CODEC.parse(ops, Spread.Clock.CODEC.encodeStart(ops, k).getOrThrow()).getOrThrow();
        assertEquals(12345.5, kb.seconds);
        assertTrue(kb.hard);
        assertEquals(99.25, kb.hardAt);
    }

    // ---- the clock -------------------------------------------------------------------------------------------------

    /** Coverage: cap * t / H up to the cap; in hard mode cap + (1 - cap) * the share of hardHours since; monotone. */
    @Property(tries = 500)
    void coverageFormulaAndMonotone(@ForAll @DoubleRange(min = 0, max = 1e6) double t, @ForAll @DoubleRange(min = 0, max = 1e5) double dt,
                                    @ForAll @DoubleRange(min = 0.01, max = 100) double hours, @ForAll @DoubleRange(min = 0.01, max = 100) double hardHours,
                                    @ForAll @DoubleRange(min = 0, max = 1) double cap, @ForAll @DoubleRange(min = 0, max = 1e6) double tHard) {
        double c = Spread.coverage(t, false, 0, hours, hardHours, cap);
        assertEquals(Math.min(cap, cap * t / (hours * 3600)), c, 1e-12);
        assertTrue(c <= cap && c >= 0);
        assertTrue(Spread.coverage(t + dt, false, 0, hours, hardHours, cap) >= c);
        if (t >= tHard) {
            double h = Spread.coverage(t, true, tHard, hours, hardHours, cap);
            assertEquals(cap + (1 - cap) * Math.min(1, (t - tHard) / (hardHours * 3600)), h, 1e-12);
            assertTrue(h >= cap && h <= 1 + 1e-12);
            assertTrue(Spread.coverage(t + dt, true, tHard, hours, hardHours, cap) >= h);
        }
        int n = 196_000;
        int p = Spread.progress(c, n);
        assertTrue(p >= 0 && p <= n && p == (int) Math.floor(c * n));
    }

    /** A tick adds at most a second, nothing with nobody on, never anything negative (a stalled server doesn't jump). */
    @Property(tries = 500)
    void tickSecondsCapped(@ForAll @IntRange(min = 0, max = 50) int players, @ForAll long last, @ForAll @DoubleRange(min = -10, max = 1e5) double gap) {
        long now = last + (long) (gap * 1e9);
        double s = Spread.tickSeconds(last, now, players);
        assertTrue(s >= 0 && s <= Spread.MAX_TICK_SECONDS);
        if (players == 0 || last == 0) assertEquals(0, s);
        else if (gap >= 0 && gap <= 1) assertEquals(gap, s, 1e-6);
    }

    /** At c = 1, P = N: everything is stilled (the spread is complete). */
    @Example
    void fullCoverageStillsEverything() {
        assertEquals(1000, Spread.progress(Spread.coverage(1e9, true, 0, 48, 48, 0.8), 1000));
        assertEquals(800, Spread.progress(Spread.coverage(48 * 3600, false, 0, 48, 48, 0.8), 1000));
        assertEquals(800, Spread.progress(Spread.coverage(1e9, false, 0, 48, 48, 0.8), 1000));
    }
}
