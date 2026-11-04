package org.erisia.stasis.compat.bluemap;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The BlueMap overlay's outlines (λ-Nero): the polygons cover exactly the filled cells. */
class GridOutlineTest {
    private static boolean covered(List<GridOutline.Polygon> ps, double x, double z) {
        int n = 0;
        for (var p : ps)
            if (p.outer().contains(x, z) && p.holes().stream().noneMatch(h -> h.contains(x, z))) n++;
        assertTrue(n <= 1, "polygons overlap at " + x + "," + z);
        return n == 1;
    }

    private static void check(boolean[] g, int w, int h) {
        var ps = GridOutline.trace(g, w, h);
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++)
                assertEquals(g[i + j * w], covered(ps, i + 0.5, j + 0.5), "cell " + i + "," + j);
        for (var p : ps) {
            assertTrue(p.outer().area2() > 0);
            for (var r : p.holes()) assertTrue(r.area2() < 0);
        }
    }

    @Test
    void squareWithHoleAndIsland() {
        int w = 7, h = 7;
        boolean[] g = new boolean[w * h];
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) g[i + j * w] = i == 0 || j == 0 || i == w - 1 || j == h - 1 || i == 3 && j == 3;
        var ps = GridOutline.trace(g, w, h);
        assertEquals(2, ps.size(), "the ring and the island");
        var big = ps.stream().filter(p -> p.outer().area2() == 2 * 49).findFirst().orElseThrow();
        assertEquals(1, big.holes().size());
        assertEquals(4, big.outer().size(), "collinear points dropped");
        check(g, w, h);
    }

    @Test
    void cornerTouchSplits() {
        boolean[] g = {true, false, false, true};
        var ps = GridOutline.trace(g, 2, 2);
        assertEquals(2, ps.size());
        check(g, 2, 2);
    }

    @Property(tries = 300)
    void coversExactlyTheFilledCells(@ForAll @IntRange(min = 1, max = 24) int w, @ForAll @IntRange(min = 1, max = 24) int h,
                                     @ForAll @IntRange(min = 5, max = 95) int percent, @ForAll long seed) {
        Random r = new Random(seed);
        boolean[] g = new boolean[w * h];
        for (int k = 0; k < g.length; k++) g[k] = r.nextInt(100) < percent;
        check(g, w, h);
    }
}
