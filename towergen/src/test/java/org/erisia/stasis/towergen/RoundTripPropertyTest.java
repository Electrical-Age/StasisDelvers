package org.erisia.stasis.towergen;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Layout, Json and the TGLB1 coder (LibraryBin) give back exactly what they were given. */
class RoundTripPropertyTest {
    @Provide
    Arbitrary<Layout> layouts() {
        Arbitrary<Layout.Boom> boom = Combinators.combine(Arbitraries.integers().between(-5, 120), Arbitraries.integers().between(0, 3),
            Arbitraries.integers().between(-5, 120)).as(Layout.Boom::new);
        return Combinators.combine(Arbitraries.integers().between(-5, 200), boom.list().ofMaxSize(12)).as(Layout::new);
    }

    @Property(tries = 1000)
    void layoutJsonRoundTrips(@ForAll("layouts") Layout l) {
        assertEquals(l, Layout.fromJson(Json.parse(l.toString())));
        assertEquals(l, Layout.fromJson(Json.parse(Json.writePretty(l.toJson()))));
    }

    @Property(tries = 1000)
    void randomLayoutsKeepTheRules(@ForAll long seed) {
        java.util.Random r = new java.util.Random(seed);
        Layout l = LayoutGen.random(r);
        assertEquals(List.of(), l.violations(), l::toString);
    }

    // ---- Json trees: maps, lists, finite doubles (not -0.0: written as 0), strings (any UTF-16), booleans, null

    @Provide
    Arbitrary<Object> trees() {
        return tree(3);
    }

    Arbitrary<Object> tree(int depth) {
        Arbitrary<Object> leaf = Arbitraries.oneOf(
            Arbitraries.doubles().filter(d -> !(d == 0 && 1 / d < 0)).map(d -> (Object) d),
            Arbitraries.integers().map(i -> (Object) (double) i),
            Arbitraries.strings().ofMaxLength(12).map(s -> (Object) s),
            Arbitraries.of(true, false).map(b -> (Object) b),
            Arbitraries.just((Object) null));
        if (depth == 0) return leaf;
        Arbitrary<Object> sub = tree(depth - 1);
        Arbitrary<Object> list = sub.list().ofMaxSize(4).map(l -> (Object) new ArrayList<>(l));
        Arbitrary<Object> map = Arbitraries.maps(Arbitraries.strings().ofMaxLength(6), sub).ofMaxSize(4)
            .map(m -> (Object) new LinkedHashMap<>(m));
        return Arbitraries.frequencyOf(net.jqwik.api.Tuple.of(3, leaf), net.jqwik.api.Tuple.of(1, list), net.jqwik.api.Tuple.of(1, map));
    }

    @Property(tries = 2000)
    void jsonRoundTrips(@ForAll("trees") Object t) {
        assertEquals(t, Json.parse(Json.write(t)));
        assertEquals(t, Json.parse(Json.writePretty(t)));
    }

    @Property(tries = 300)
    void jsonMapKeysKeepTheirOrder(@ForAll @Size(max = 10) List<String> keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) m.put(keys.get(i), (double) i);
        @SuppressWarnings("unchecked") Map<String, Object> back = (Map<String, Object>) Json.parse(Json.write(m));
        assertEquals(new ArrayList<>(m.keySet()), new ArrayList<>(back.keySet()));
    }

    // ---- TGLB1 numbers: bit-exact doubles (also -0.0, NaN payloads, subnormals), zigzag ints, unsigned varints

    @Provide
    Arbitrary<Double> libDoubles() {
        return Arbitraries.oneOf(
            Arbitraries.longs().map(Double::longBitsToDouble),                              // any bit pattern
            Arbitraries.integers().between(-(1 << 27), 1 << 27).map(k -> k / 16.0),         // the 1/16 grid
            Arbitraries.integers().between(-(1 << 27), 1 << 27).map(k -> k / 1e4),          // the 1e-4 grid
            Arbitraries.of(-0.0, 0.0, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e-310, (1 << 28) / 16.0, -(1 << 28) / 1e4));
    }

    @Property(tries = 3000)
    void libraryNumbersAreBitExact(@ForAll @Size(max = 12) List<@net.jqwik.api.From("libDoubles") Double> ds, @ForAll @Size(max = 12) List<Integer> is,
                                   @ForAll int u) {
        LibraryBin.Out o = new LibraryBin.Out();
        double[] da = ds.stream().mapToDouble(Double::doubleValue).toArray();
        int[] ia = is.stream().mapToInt(Integer::intValue).toArray();
        o.da(da);
        o.ia(ia);
        o.uv(u);
        o.str("é∑ " + u);
        LibraryBin.In in = new LibraryBin.In(o.b.toByteArray());
        double[] db = in.da();
        assertEquals(da.length, db.length);
        for (int k = 0; k < da.length; k++)
            assertEquals(Double.doubleToRawLongBits(da[k]), Double.doubleToRawLongBits(db[k]), "double " + da[k]);
        assertEquals(java.util.Arrays.toString(ia), java.util.Arrays.toString(in.ia()));
        assertEquals(u, in.uv());
        assertEquals("é∑ " + u, in.str());
        assertEquals(o.b.size(), in.p, "all bytes read");
    }
}
