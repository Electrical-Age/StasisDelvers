package org.erisia.stasis.towergen;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hull3 (joint hubs): closed, convex, holds every point, and the same hull whatever the order of the points. */
class Hull3PropertyTest {
    /** Random clouds (general position) and lattice clouds (duplicates, coplanar and collinear runs: the hard cases). */
    @Provide
    Arbitrary<double[]> clouds() {
        Arbitrary<double[]> random = Arbitraries.doubles().between(-2, 2).array(double[].class).ofMinSize(3).ofMaxSize(90)
            .filter(a -> a.length % 3 == 0);
        Arbitrary<double[]> lattice = Arbitraries.integers().between(0, 3).map(i -> i * 0.25).array(double[].class)
            .ofMinSize(3).ofMaxSize(90).filter(a -> a.length % 3 == 0);
        Arbitrary<double[]> flat = Arbitraries.doubles().between(-2, 2).array(double[].class).ofMinSize(6).ofMaxSize(60)
            .filter(a -> a.length % 3 == 0).map(a -> { for (int i = 2; i < a.length; i += 3) a[i] = 0.5 * a[i - 2] - a[i - 1]; return a; });
        return Arbitraries.oneOf(random, lattice, flat);
    }

    static double[] p(Hull3 h, int i) {
        return new double[]{h.xyz[3 * i], h.xyz[3 * i + 1], h.xyz[3 * i + 2]};
    }

    @Property(tries = 2000)
    void closedConvexAndHoldsEveryPoint(@ForAll("clouds") double[] in) {
        int m = in.length / 3;
        Hull3 h = Hull3.of(in, m);
        assertTrue(h.ok, "hull not ok");
        if (h.dim != 3) return;
        double tol = 1e-7;
        for (int i = 0; i < m; i++)
            assertTrue(h.contains(new double[]{in[3 * i], in[3 * i + 1], in[3 * i + 2]}, tol), "point " + i + " outside");
        // every directed edge has its reverse exactly once: a closed, consistently wound surface
        Map<Long, Integer> edges = new HashMap<>();
        Set<Integer> verts = new HashSet<>();
        for (int f = 0; f < h.nTri; f++)
            for (int k = 0; k < 3; k++) {
                int a = h.tri[3 * f + k], b = h.tri[3 * f + (k + 1) % 3];
                verts.add(a);
                edges.merge(((long) a << 32) | b, 1, Integer::sum);
            }
        for (var e : edges.entrySet()) {
            long rev = ((e.getKey() & 0xffffffffL) << 32) | (e.getKey() >>> 32);
            assertEquals(1, e.getValue(), "edge used twice the same way");
            assertEquals(1, edges.getOrDefault(rev, 0), "edge without its twin");
        }
        int V = verts.size(), E = edges.size() / 2, F = h.nTri;
        assertEquals(2, V - E + F, "Euler: V " + V + " E " + E + " F " + F);
        for (int f = 0; f < h.nTri; f++) {
            double nx = h.pl[4 * f], ny = h.pl[4 * f + 1], nz = h.pl[4 * f + 2];
            assertEquals(1, Math.sqrt(nx * nx + ny * ny + nz * nz), 1e-9, "unit normal");
            for (int k = 0; k < 3; k++) {
                double[] v = p(h, h.tri[3 * f + k]);
                assertEquals(h.pl[4 * f + 3], nx * v[0] + ny * v[1] + nz * v[2], 1e-7, "vertex on its face's plane");
            }
        }
    }

    static double volume(Hull3 h) {
        if (h.dim != 3) return 0;
        double v = 0;
        for (int f = 0; f < h.nTri; f++) {
            double[] a = p(h, h.tri[3 * f]), b = p(h, h.tri[3 * f + 1]), c = p(h, h.tri[3 * f + 2]);
            v += a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0]);
        }
        return v / 6;
    }

    @Property(tries = 1000)
    void orderOfThePointsDoesNotMatter(@ForAll("clouds") double[] in, @ForAll long seed) {
        int m = in.length / 3;
        Integer[] perm = new Integer[m];
        for (int i = 0; i < m; i++) perm[i] = i;
        java.util.Collections.shuffle(java.util.Arrays.asList(perm), new java.util.Random(seed));
        double[] sh = new double[in.length];
        for (int i = 0; i < m; i++) System.arraycopy(in, 3 * perm[i], sh, 3 * i, 3);
        Hull3 a = Hull3.of(in, m), b = Hull3.of(sh, m);
        assertEquals(a.dim, b.dim);
        assertEquals(volume(a), volume(b), 1e-9);
        if (a.dim == 3) assertTrue(volume(a) > 0, "outward winding");
        if (a.dim == 2) assertEquals(a.poly.length, b.poly.length, "polygon corners");
    }

    @Property(tries = 300)
    void degenerateCloudsGiveNothingOrAPolygon(@ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 20) int m, @ForAll long seed) {
        java.util.Random r = new java.util.Random(seed);
        double[] line = new double[3 * m];
        for (int i = 0; i < m; i++) { double t = r.nextDouble(); line[3 * i] = t; line[3 * i + 1] = 2 * t; line[3 * i + 2] = -t; }
        Hull3 h = Hull3.of(line, m);
        assertTrue(h.ok && h.dim < 2, "collinear: dim " + h.dim);
    }
}
