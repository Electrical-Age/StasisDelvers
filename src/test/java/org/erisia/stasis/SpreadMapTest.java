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
 * The spreading mode's map (SpreadMap) and clock (Spread): the ranks are a permutation; the high ranks follow S (rising
 * water); until the split every running low cell is connected to spawn, at cell level and block by block through the
 * bilinear; the build is deterministic; the compass search finds the nearest cell; the clock never jumps and its
 * coverage follows the formula. coherenceReport prints how coherent the running land is (Baughn: regions for bases).
 */
class SpreadMapTest {
    static SpreadMap map(int seed, int sx, int sz, int radius) {
        return SpreadMap.build(seed, sx, sz, radius);
    }

    static int split(SpreadMap m, double connectedUntil) {
        return (int) Math.floor(connectedUntil * m.count);
    }

    /** The index into m.ranks of the cell holding block (x, z). */
    static int cellOf(SpreadMap m, int x, int z) {
        return (Math.floorDiv(x, SpreadMap.CELL) - m.cellX0) + (Math.floorDiv(z, SpreadMap.CELL) - m.cellZ0) * m.n;
    }

    /** Ranks inside the disc are exactly 0..N-1, cells outside are -1, and spawn's cell is at least split - 1. */
    @Property(tries = 40)
    void ranksArePermutation(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -200000, max = 200000) int sx,
                             @ForAll @IntRange(min = -200000, max = 200000) int sz, @ForAll @IntRange(min = 64, max = 1500) int radius,
                             @ForAll @DoubleRange(min = 0, max = 1) double connectedUntil) {
        SpreadMap m = SpreadMap.build(seed, sx, sz, radius, connectedUntil);
        int[] in = Arrays.stream(m.ranks).filter(r -> r >= 0).sorted().toArray();
        assertEquals(m.count, in.length);
        for (int i = 0; i < in.length; i++) assertEquals(i, in[i]);
        assertTrue(Arrays.stream(m.ranks).allMatch(r -> r >= -1));
        // the flood starts at spawn's cell: the last low cell to still, or high
        assertTrue(m.ranks[cellOf(m, sx, sz)] >= split(m, connectedUntil) - 1);
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
                       @ForAll @IntRange(min = -5000, max = 5000) int sz, @ForAll @IntRange(min = 64, max = 1200) int radius,
                       @ForAll @DoubleRange(min = 0, max = 1) double connectedUntil) {
        assertArrayEquals(SpreadMap.build(seed, sx, sz, radius, connectedUntil).ranks, SpreadMap.build(seed, sx, sz, radius, connectedUntil).ranks);
    }

    /** Rising water: the ranks from the split up are the cells in the order of S (ties: the lower index first). */
    @Property(tries = 30)
    void highRanksFollowS(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -100000, max = 100000) int sx,
                          @ForAll @IntRange(min = -100000, max = 100000) int sz, @ForAll @IntRange(min = 64, max = 3000) int radius,
                          @ForAll @DoubleRange(min = 0, max = 1) double connectedUntil) {
        SpreadMap m = SpreadMap.build(seed, sx, sz, radius, connectedUntil);
        float[] s = SpreadMap.stability(seed, sx, sz, radius, m.cellX0, m.cellZ0, m.n);
        int split = split(m, connectedUntil);
        int[] byRank = new int[m.count];
        for (int c = 0; c < m.ranks.length; c++) if (m.ranks[c] >= 0) byRank[m.ranks[c]] = c;
        float least = Float.POSITIVE_INFINITY;
        for (int r = split; r < m.count; r++) {
            if (r > split) {
                int a = byRank[r - 1], b = byRank[r];
                assertTrue(s[a] < s[b] || s[a] == s[b] && a > b, "ranks " + (r - 1) + ", " + r + " out of S order");
            }
            least = Math.min(least, s[byRank[r]]);
        }
        // and every low cell is at most as stable as the least high one
        for (int r = 0; r < split; r++) assertTrue(s[byRank[r]] <= least);
    }

    /** The running cells reached from spawn's cell by 4-steps at progress p. */
    static boolean[] reachCells(SpreadMap m, int start, int p) {
        boolean[] seen = new boolean[m.ranks.length];
        if (m.ranks[start] < p) return seen;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        seen[start] = true;
        q.add(start);
        while (!q.isEmpty()) {
            int c = q.poll(), i = c % m.n, k = c / m.n;
            for (int nc : new int[]{i > 0 ? c - 1 : -1, i < m.n - 1 ? c + 1 : -1, k > 0 ? c - m.n : -1, k < m.n - 1 ? c + m.n : -1})
                if (nc >= 0 && !seen[nc] && m.ranks[nc] >= p) {
                    seen[nc] = true;
                    q.add(nc);
                }
        }
        return seen;
    }

    /**
     * Until the split, every running low cell (rank in [P, split)) is 4-connected to spawn's cell through running cells
     * (high ground may be a running island). Checked at P = 0, split - 1 and a few between.
     */
    @Property(tries = 40)
    void runningLowCellsReachSpawn(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -100000, max = 100000) int sx,
                                   @ForAll @IntRange(min = -100000, max = 100000) int sz, @ForAll @IntRange(min = 64, max = 3000) int radius,
                                   @ForAll @DoubleRange(min = 0.05, max = 1) double connectedUntil, @ForAll @DoubleRange(min = 0, max = 1) double share) {
        SpreadMap m = SpreadMap.build(seed, sx, sz, radius, connectedUntil);
        int split = split(m, connectedUntil), start = cellOf(m, sx, sz);
        if (split == 0) return;
        for (int p : new int[]{0, split - 1, (int) (share * (split - 1)), split / 2, split * 9 / 10}) {
            boolean[] seen = reachCells(m, start, p);
            for (int c = 0; c < m.ranks.length; c++) {
                int r = m.ranks[c];
                if (r >= p && r < split) assertTrue(seen[c], "P " + p + ": low cell " + c + " (rank " + r + ") cut off from spawn");
            }
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
     * Block by block (the bilinear between cell centres, as rateAt asks), below the split: the centre column of every
     * running low cell is 4-connected to the centre column of spawn's cell (between two running centres the bilinear is
     * linear, so a cell path is a block path).
     */
    @Property(tries = 25)
    void runningLowColumnsReachSpawn(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -50000, max = 50000) int sx,
                                     @ForAll @IntRange(min = -50000, max = 50000) int sz, @ForAll @IntRange(min = 64, max = 400) int radius,
                                     @ForAll @DoubleRange(min = 0, max = 1) double share) {
        SpreadMap m = map(seed, sx, sz, radius);
        int split = split(m, SpreadMap.CONNECTED_UNTIL);
        int start = cellOf(m, sx, sz);
        int cx = (m.cellX0 + start % m.n) * SpreadMap.CELL + SpreadMap.HALF, cz = (m.cellZ0 + start / m.n) * SpreadMap.CELL + SpreadMap.HALF;
        for (int p : new int[]{(int) (share * (split - 1)), split - 1}) {
            Blocks b = Blocks.of(m, p);
            boolean[] seen = b.reach(cx, cz);
            for (int c = 0; c < m.ranks.length; c++) {
                int r = m.ranks[c];
                if (r < p || r >= split) continue;
                int x = (m.cellX0 + c % m.n) * SpreadMap.CELL + SpreadMap.HALF - b.x0(), z = (m.cellZ0 + c / m.n) * SpreadMap.CELL + SpreadMap.HALF - b.z0();
                assertTrue(seen[x + z * b.w()], "P " + p + ": low cell " + c + "'s centre column cut off from spawn");
            }
        }
    }

    /** The compass search = the brute-force nearest cell centre at or above minRank (ties: the same distance). */
    @Property(tries = 200)
    void nearestAtLeastIsNearest(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -900, max = 900) int x,
                                 @ForAll @IntRange(min = -900, max = 900) int z, @ForAll @DoubleRange(min = 0, max = 1.05) double share) {
        SpreadMap m = map(seed, 10, -20, 700);
        int min = (int) Math.ceil(share * m.count);
        int[] t = SpreadMap.nearestAtLeast(m, x, z, min, m.n);
        long best = Long.MAX_VALUE;
        for (int c = 0; c < m.ranks.length; c++) {
            if (m.ranks[c] < min) continue;
            long dx = (m.cellX0 + c % m.n) * (long) SpreadMap.CELL + SpreadMap.HALF - x, dz = (m.cellZ0 + c / m.n) * (long) SpreadMap.CELL + SpreadMap.HALF - z;
            best = Math.min(best, dx * dx + dz * dz);
        }
        if (best == Long.MAX_VALUE) {
            assertEquals(null, t);
            return;
        }
        assertTrue(t != null, "found none");
        assertTrue(m.rank1024(t[0], t[1]) >= (long) min * SpreadMap.SCALE);
        long dx = t[0] - x, dz = t[1] - z;
        assertEquals(best, dx * dx + dz * dz);
    }

    /**
     * The spawn move's candidates (SpawnMove; Baughn 2026-10-19): exactly the cells at or above minRank, nearest the old
     * spawn first (ties by index), the first being the compass's nearestAtLeast.
     */
    @Property(tries = 100)
    void cellsAtLeastAreOrdered(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -500, max = 500) int x,
                                @ForAll @IntRange(min = -500, max = 500) int z, @ForAll @DoubleRange(min = 0, max = 1.05) double share) {
        SpreadMap m = map(seed, 10, -20, 500);
        int min = (int) Math.ceil(share * m.count);
        int[] cs = SpreadMap.cellsAtLeast(m, x, z, min);
        int brute = 0;
        for (int r : m.ranks) if (r >= min) brute++;
        assertEquals(brute, cs.length);
        long last = -1;
        int lastC = -1;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int c : cs) {
            assertTrue(m.ranks[c] >= min, "rank " + m.ranks[c] + " < " + min);
            assertTrue(seen.add(c), "cell twice");
            long dx = (m.cellX0 + c % m.n) * (long) SpreadMap.CELL + SpreadMap.HALF - x, dz = (m.cellZ0 + c / m.n) * (long) SpreadMap.CELL + SpreadMap.HALF - z;
            long d = dx * dx + dz * dz;
            assertTrue(d > last || d == last && c > lastC, "out of order");
            last = d;
            lastC = c;
        }
        int[] t = SpreadMap.nearestAtLeast(m, x, z, min, m.n);
        if (cs.length == 0) assertEquals(null, t);
        else {
            assertTrue(t != null);
            int c = cs[0];
            long dx = t[0] - x, dz = t[1] - z;
            long fx = (m.cellX0 + c % m.n) * (long) SpreadMap.CELL + SpreadMap.HALF - x, fz = (m.cellZ0 + c / m.n) * (long) SpreadMap.CELL + SpreadMap.HALF - z;
            assertEquals(dx * dx + dz * dz, fx * fx + fz * fz);
        }
    }

    /**
     * Not an assertion, a report (Baughn 2026-10-19: coherent running regions for bases): at a few coverages, the running
     * cells' 4-connected components: how many, how many big (>= BIG cells, ~ 320 x 320 blocks), the share of running
     * land in big ones, the largest. Also writes the lab world's map (deep seed 3201319, spawn 0 0, 8 km) as raw ranks
     * for /var/lib/mclab/agent/tools/spread-render.py: build/spread-lab-v2.ranks (int n, then n * n ints, big-endian).
     */
    @Example
    void coherenceReport() throws java.io.IOException {
        StringBuilder out = new StringBuilder("spread coherence report (8 km; big = >= " + BIG + " cells):\n");
        for (int seed : new int[]{3201319, 12345, 777777}) {
            long t0 = System.nanoTime();
            SpreadMap m = map(seed, 0, 0, 8000);
            out.append(String.format("seed %d: %d cells, built in %d ms%n", seed, m.count, (System.nanoTime() - t0) / 1_000_000));
            for (double c : new double[]{0.2, 0.5, 0.8, 0.9, 0.95}) out.append("  ").append(coherence(m.ranks, m.n, (int) (c * m.count), c)).append('\n');
            if (seed == 3201319) {
                // next to jqwik's database (build.gradle sets it absolute, in build/): the tests run in their own directory
                String db = System.getProperty("jqwik.database");
                java.nio.file.Path f = (db != null ? java.nio.file.Path.of(db).getParent() : java.nio.file.Path.of("build")).resolve("spread-lab-v2.ranks").toAbsolutePath();
                java.nio.file.Files.createDirectories(f.getParent());
                try (var o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(f)))) {
                    o.writeInt(m.n);
                    for (int r : m.ranks) o.writeInt(r);
                }
                out.append("  (ranks written to ").append(f).append(")\n");
            }
        }
        System.out.println(out);
        StasisDelvers.LOG.info("{}", out);
    }

    static final int BIG = 100;

    static String coherence(int[] ranks, int n, int p, double c) {
        int[] comp = new int[ranks.length];
        Arrays.fill(comp, -1);
        int comps = 0, big = 0, running = 0, inBig = 0, largest = 0, shore = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < ranks.length; s++) {
            if (ranks[s] < p || comp[s] >= 0) continue;
            int size = 0;
            comp[s] = comps;
            q.add(s);
            while (!q.isEmpty()) {
                int x = q.poll(), i = x % n, k = x / n;
                size++;
                for (int nc : new int[]{i > 0 ? x - 1 : -1, i < n - 1 ? x + 1 : -1, k > 0 ? x - n : -1, k < n - 1 ? x + n : -1})
                    if (nc >= 0 && comp[nc] < 0 && ranks[nc] >= p) {
                        comp[nc] = comps;
                        q.add(nc);
                    }
                for (int nc : new int[]{i > 0 ? x - 1 : -1, i < n - 1 ? x + 1 : -1, k > 0 ? x - n : -1, k < n - 1 ? x + n : -1})
                    if (nc < 0 || ranks[nc] < p) {
                        shore++;
                        break;
                    }
            }
            comps++;
            running += size;
            largest = Math.max(largest, size);
            if (size >= BIG) {
                big++;
                inBig += size;
            }
        }
        // shore: running cells next to stilled land (scattered land is mostly shore; v1's lab map at c 0.8: 9 %)
        return String.format("c %.2f: %d running cells in %d components, %d big holding %.1f%%, largest %d cells (%.1f%%), shore %.1f%%",
            c, running, comps, big, running == 0 ? 0 : 100.0 * inBig / running, largest, running == 0 ? 0 : 100.0 * largest / running,
            running == 0 ? 0 : 100.0 * shore / running);
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
        // a save without the field is algorithm 1 (DFU drops a field equal to its default: every v1 save lacks it)
        assertEquals(1, empty.algorithm);
        assertEquals(SpreadMap.ALGORITHM, back.algorithm);

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

    /** climb ends on a local maximum of the ranks, never lower than where it started (the peak stills last). */
    @Property(tries = 200)
    void climbEndsOnALocalMaximum(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -500, max = 500) int x,
                                  @ForAll @IntRange(min = -500, max = 500) int z) {
        SpreadMap m = map(seed, 10, -20, 500);
        int[] p = SpreadMap.climb(m, x, z);
        int ci = Math.floorDiv(p[0], SpreadMap.CELL), ck = Math.floorDiv(p[1], SpreadMap.CELL);
        int r = m.rank(ci, ck);
        assertTrue(r >= m.rank(Math.floorDiv(x, SpreadMap.CELL), Math.floorDiv(z, SpreadMap.CELL)));
        for (int[] e : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) assertTrue(m.rank(ci + e[0], ck + e[1]) <= r);
    }
}
