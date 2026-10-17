package org.erisia.stasis.towergen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * SolverParams: the defaults give exactly the plain generator; wire reduction (maxStaysPerBoom 2, xPanels single) on
 * the 7 golden layouts gives standing towers (util <= 1.001 in all cases, taut cables).
 */
class SolverTest {
    static TowerGen gen;
    static final String[] LAYOUTS = {"H16", "H24b", "H40", "H40r3", "H40b4", "H64max", "H100"};

    @BeforeAll
    static void setup() { gen = new TowerGen(Library.shared()); }

    static Layout layout(String n) throws Exception { return Layout.fromJson(Json.parse(Files.readString(Path.of("layouts/" + n + ".json")))); }

    @Test
    void defaultsAreIdentity() {
        SolverParams d = SolverParams.defaults(gen.c);
        assertSame(gen.c, gen.c.forSpine(d));
        assertSame(gen.c, gen.c.forPass(d));
        assertEquals(d, SolverParams.fromJson(Json.parse("{}"), gen.c));
        assertEquals(d, SolverParams.fromJson(Json.parse(Json.write(d.toJson())), gen.c));
    }

    @TestFactory
    Stream<DynamicTest> defaultsMatchPlainBuild() throws Exception {
        List<Map<String, Object>> goldens = Goldens.load();
        SolverParams d = SolverParams.defaults(gen.c);
        return goldens.stream().map(g -> DynamicTest.dynamicTest(Goldens.name(g), () -> {
            Layout l = Goldens.layout(g);
            double q = Json.num(g.get("q"));
            TowerGen.Result a = gen.build(l, q), b = gen.build(l, q, d);
            assertEquals(a.tower.m, b.tower.m);
            assertArrayEquals(a.tower.kind, b.tower.kind);
            assertArrayEquals(a.tower.ws, b.tower.ws);
            assertArrayEquals(a.tower.cs, b.tower.cs);
            assertArrayEquals(a.tower.pw, b.tower.pw);
            assertEquals(a.analysis.maxUtil, b.analysis.maxUtil);
            assertTrue(b.warnings.isEmpty(), b.warnings.toString());
        }));
    }

    @Test
    void parseErrorsNameTheKey() {
        try { SolverParams.fromJson(Json.parse("{\"xPanels\": \"none\"}"), gen.c); throw new AssertionError(); }
        catch (IllegalArgumentException e) { assertTrue(e.getMessage().contains("xPanels"), e.getMessage()); }
        try { SolverParams.fromJson(Json.parse("{\"maxStaysPerBoom\": \"2\"}"), gen.c); throw new AssertionError(); }
        catch (IllegalArgumentException e) { assertTrue(e.getMessage().contains("maxStaysPerBoom"), e.getMessage()); }
    }

    static List<String> check(String tag, TowerGen.Result r) {
        List<String> err = new ArrayList<>();
        RuntimePass.Analysis an = r.analysis;
        if (an == null) { err.add(tag + ": no analysis (mechanism) " + r.warnings); return err; }
        for (int k = 0; k < an.utilPerCase.length; k++)
            if (an.utilPerCase[k] > 1.001) err.add(tag + ": case " + k + " util " + an.utilPerCase[k]);
        if (an.minSlack < -1e-6) err.add(tag + ": slack cable, margin " + an.minSlack);
        return err;
    }

    @Test
    void fewerWiresStillStand() throws Exception {
        SolverParams d = SolverParams.defaults(gen.c);
        SolverParams p = d.withWires(2, "single", 0);
        List<String> err = new ArrayList<>();
        System.out.println("maxStaysPerBoom 2, xPanels single:");
        System.out.printf("%-10s %4s | %-44s | %-44s | %s%n", "layout", "q", "default (final tower)", "reduced (final tower)", "util, vol, ms");
        for (String n : LAYOUTS) {
            for (double q : new double[]{1.0, 0.3}) {
                Layout l = layout(n);
                gen.build(l, q, p);  // warm
                TowerGen.Result a = gen.build(l, q, d);
                long t0 = System.nanoTime();
                TowerGen.Result b = gen.build(l, q, p);
                double ms = (System.nanoTime() - t0) / 1e6;
                long t1 = System.nanoTime();
                gen.build(l, q, d);
                double ms0 = (System.nanoTime() - t1) / 1e6;
                assertNotNull(b.tower);
                err.addAll(check(n + " q" + q, b));
                System.out.printf("%-10s %4.1f | %-44s | %-44s | util %.3f -> %.3f, vol %.1f -> %.1f, %.1f -> %.1f ms (reduce %.1f ms, %d analyses; %d x singled, stays -%d +%d (flat %d, mechanism %d, force %d), util +%d)%s%n",
                        n, q, WireReduction.Counts.of(a.tower), WireReduction.Counts.of(b.tower),
                        a.analysis.maxUtil, b.analysis == null ? Double.NaN : b.analysis.maxUtil, a.info.vol, b.info.vol, ms0, ms,
                        b.msReduce(), b.reduction.analyses, b.reduction.xSingled, b.reduction.staysRemoved, b.reduction.staysReadded,
                        b.reduction.readdFlat, b.reduction.readdMech, b.reduction.readdForce, b.reduction.utilReadded,
                        b.warnings.isEmpty() ? "" : "\n           warnings: " + b.warnings);
            }
        }
        assertTrue(err.isEmpty(), String.join("\n", err));
    }
}
