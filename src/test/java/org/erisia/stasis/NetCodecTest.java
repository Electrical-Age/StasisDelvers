package org.erisia.stasis;

import io.netty.buffer.Unpooled;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** decode(encode(x)) = x for every StasisNet payload (none of them needs registries), and the whole buffer is read. */
class NetCodecTest {
    static RegistryFriendlyByteBuf buf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
    }

    static <T> T trip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf b = buf();
        codec.encode(b, value);
        T back = codec.decode(b);
        assertEquals(0, b.readableBytes(), "bytes left over");
        return back;
    }

    @Provide
    Arbitrary<Double> anyDouble() {
        return Arbitraries.oneOf(Arbitraries.doubles().between(-1e9, 1e9),
            Arbitraries.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, Double.MIN_VALUE, Double.MAX_VALUE));
    }

    @Provide
    Arbitrary<BlockPos> positions() {
        return Combinators.combine(Arbitraries.integers().between(-30_000_000, 30_000_000), Arbitraries.integers().between(-2048, 2047),
            Arbitraries.integers().between(-30_000_000, 30_000_000)).as(BlockPos::new);
    }

    @Provide
    Arbitrary<Field> fields() {
        Arbitrary<Double> d = anyDouble();
        Arbitrary<Double> f = Arbitraries.doubles().between(-10, 10);
        Arbitrary<Field.Stretch> st = Combinators.combine(f, f, f, f).as(Field.Stretch::new);
        return Combinators.combine(Arbitraries.integers().greaterOrEqual(0), d, d, d, d, d, d, Arbitraries.of(true, false))
            .flatAs((id, x, y, z, r, failing, collapse, column) -> Combinators.combine(d, Arbitraries.integers(), Arbitraries.longs(), f, f, f, st)
                .as((rate, seed, formed, amber, dropout, side, s) ->
                    new Field(id, x, y, z, r, failing, collapse, column, rate, seed, formed, amber, dropout, side, s)));
    }

    /** FIELD sends the shield's look and the stretch as floats: what comes back is the float-rounded field. */
    static Field rounded(Field f) {
        var s = f.stretch();
        return new Field(f.id(), f.x(), f.y(), f.z(), f.radius(), f.failing(), f.collapse(), f.column(), f.rate(), f.seed(), f.formedAt(),
            (float) f.amber(), (float) f.dropout(), (float) f.side(),
            new Field.Stretch((float) s.e(), (float) s.s(), (float) s.w(), (float) s.n()));
    }

    @Property(tries = 300)
    void fieldsRoundTrip(@ForAll @Size(max = 8) List<@net.jqwik.api.From("fields") Field> fs, @ForAll int deepSeed) {
        StasisNet.Fields back = trip(StasisNet.Fields.CODEC, new StasisNet.Fields(fs, deepSeed));
        assertEquals(new StasisNet.Fields(fs.stream().map(NetCodecTest::rounded).toList(), deepSeed), back);
    }

    @Property(tries = 200)
    void gateRiftsRoundTrip(@ForAll int seed, @ForAll @Size(max = 20) List<Long> centres, @ForAll @Size(max = 20) List<Long> returns,
                            @ForAll @Size(max = 20) List<Integer> steps) {
        var p = new StasisNet.GateRifts(seed, centres, returns, steps);
        assertEquals(p, trip(StasisNet.GateRifts.CODEC, p));
    }

    /** The lanced rifts' steps survive packing (24-bit ids, steps 0..8) and unpack to the strengths both sides use. */
    @Property(tries = 200)
    void lanceStepsPack(@ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 0xFFFFFF) int id,
                        @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 8) int step) {
        var m = Lance.strengths(List.of(Lance.packStep(id, step)));
        assertEquals(java.util.Map.of(id, Lance.strength(step)), m);
    }

    @Property(tries = 200)
    void stormOverrideRoundTrips(@ForAll boolean active, @ForAll("anyDouble") double a, @ForAll("anyDouble") double b, @ForAll("anyDouble") double c) {
        var p = new StasisNet.StormOverride(active, a, b, c);
        assertEquals(p, trip(StasisNet.StormOverride.CODEC, p));
    }

    @Property(tries = 300)
    void consolePayloadsRoundTrip(@ForAll("positions") BlockPos pos, @ForAll("anyDouble") double target, @ForAll boolean override,
                                  @ForAll @Size(max = 16) List<@net.jqwik.api.From("anyDouble") Double> v, @ForAll String text) {
        assertEquals(new StasisNet.ConsoleQuery(pos), trip(StasisNet.ConsoleQuery.CODEC, new StasisNet.ConsoleQuery(pos)));
        assertEquals(new StasisNet.ConsoleFeed(pos), trip(StasisNet.ConsoleFeed.CODEC, new StasisNet.ConsoleFeed(pos)));
        // (a NaN target means "the preferred range": it must survive)
        assertEquals(new StasisNet.ConsoleSet(pos, target, override), trip(StasisNet.ConsoleSet.CODEC, new StasisNet.ConsoleSet(pos, target, override)));
        var st = new StasisNet.ConsoleState(pos, v, text);
        assertEquals(st, trip(StasisNet.ConsoleState.CODEC, st));
    }

    @Example
    void digStilledRoundTrips() {
        assertEquals(new StasisNet.DigStilled(), trip(StasisNet.DigStilled.CODEC, new StasisNet.DigStilled()));
    }

    @Property(tries = 300)
    void reportPayloadsRoundTrip(@ForAll int nonce, @ForAll("positions") BlockPos look, @ForAll boolean hasLook, @ForAll byte face,
                                 @ForAll @Size(max = 2000) String json) {
        var q = new StasisNet.ReportQuery(nonce, hasLook ? Optional.of(look) : Optional.empty(), face);
        assertEquals(q, trip(StasisNet.ReportQuery.CODEC, q));
        var s = new StasisNet.ReportState(nonce, json);
        assertEquals(s, trip(StasisNet.ReportState.CODEC, s));
    }

    @Example
    void reportStateOverTheCapThrows() {
        String big = "x".repeat(StasisNet.ReportState.REPORT_MAX + 1);
        assertThrows(Exception.class, () -> StasisNet.ReportState.CODEC.encode(buf(), new StasisNet.ReportState(1, big)));
        String atCap = "x".repeat(StasisNet.ReportState.REPORT_MAX);
        assertEquals(atCap, trip(StasisNet.ReportState.CODEC, new StasisNet.ReportState(1, atCap)).json());
    }

    @Property(tries = 200)
    void freezeRoundTrips(@ForAll float progress) {
        assertEquals(new StasisNet.Freeze(progress), trip(StasisNet.Freeze.CODEC, new StasisNet.Freeze(progress)));
    }

    @Property(tries = 200)
    void clockMapsRoundTrip(@ForAll @Size(max = 20) Map<Integer, Double> fieldTimes, @ForAll @Size(max = 20) Map<Long, Byte> lights) {
        Map<Integer, double[]> clocks = new HashMap<>();
        fieldTimes.forEach((id, t) -> clocks.put(id, new double[]{t, t / 7}));
        var back = trip(StasisNet.FieldClocks.CODEC, new StasisNet.FieldClocks(clocks)).clocks();
        assertEquals(clocks.keySet(), back.keySet());
        clocks.forEach((id, v) -> assertArrayEquals(v, back.get(id)));
        assertEquals(new StasisNet.Gutters(lights), trip(StasisNet.Gutters.CODEC, new StasisNet.Gutters(lights)));
        assertEquals(new StasisNet.FieldSpeeds(fieldTimes), trip(StasisNet.FieldSpeeds.CODEC, new StasisNet.FieldSpeeds(fieldTimes)));
    }

    @Property(tries = 100)
    void roomsRoundTrip(@ForAll long key, @ForAll int x0, @ForAll int y0, @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 16) int sx,
                        @ForAll @Size(max = 64) java.util.List<Long> bits, @ForAll double rate) {
        var r = new Rooms.Room(key, x0, y0, -x0, sx, 3, 2, bits.stream().mapToLong(Long::longValue).toArray(), rate);
        var back = trip(StasisNet.RoomsSync.CODEC, new StasisNet.RoomsSync(java.util.List.of(r, r))).rooms();
        assertEquals(2, back.size());
        for (var b : back) {
            assertEquals(java.util.List.of(r.key(), r.x0(), r.y0(), r.z0(), r.sx(), r.sy(), r.sz()), java.util.List.of(b.key(), b.x0(), b.y0(), b.z0(), b.sx(), b.sy(), b.sz()));
            assertArrayEquals(r.bits(), b.bits());
            assertEquals(r.rate(), b.rate());
        }
    }

    @Property(tries = 200)
    void boltRoundTrips(@ForAll("anyDouble") double fx, @ForAll("anyDouble") double fy, @ForAll("anyDouble") double fz,
                        @ForAll("anyDouble") double tx, @ForAll("anyDouble") double ty, @ForAll("anyDouble") double tz, @ForAll int seed) {
        var b = new StasisNet.Bolt(fx, fy, fz, tx, ty, tz, seed);
        assertEquals(b, trip(StasisNet.Bolt.CODEC, b));
    }

    @Property(tries = 200)
    void spreadStateRoundTrips(@ForAll boolean reset, @ForAll boolean map, @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 30_000_000) int count,
                               @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 30_000_000) int p, @ForAll boolean hard, @ForAll boolean complete,
                               @ForAll boolean endStill, @ForAll boolean endEvent, @ForAll double stormOrigin) {
        var s = new StasisNet.SpreadState(reset, map, count, p, hard, complete, endStill, endEvent, stormOrigin);
        assertEquals(s, trip(StasisNet.SpreadState.CODEC, s));
        // before hard mode: no origin (NaN) comes back as NaN
        var none = new StasisNet.SpreadState(reset, map, count, p, false, complete, false, endEvent, Double.NaN);
        assertTrue(Double.isNaN(trip(StasisNet.SpreadState.CODEC, none).stormOrigin()));
    }

    /** Any tile of ranks (-1..N-1 as sent, and MISSING or other extremes all the same) comes back cell for cell. */
    @Property(tries = 200)
    void spreadTileRoundTrips(@ForAll int tx, @ForAll int tz, @ForAll("tileRanks") int[] ranks) {
        var t = new StasisNet.SpreadTile(tx, tz, ranks);
        assertEquals(t, trip(StasisNet.SpreadTile.CODEC, t));
    }

    @Provide
    Arbitrary<int[]> tileRanks() {
        Arbitrary<Integer> rank = Arbitraries.oneOf(Arbitraries.integers().between(-1, 3_000_000),
            Arbitraries.of(-1, 0, SpreadTiles.MISSING, Integer.MIN_VALUE));
        Arbitrary<int[]> mixed = rank.array(int[].class).ofSize(SpreadTiles.TILE_CELLS);
        Arbitrary<int[]> uniform = rank.map(v -> {
            int[] r = new int[SpreadTiles.TILE_CELLS];
            java.util.Arrays.fill(r, v);
            return r;
        });
        return Arbitraries.oneOf(mixed, uniform);
    }

    @Example
    void spreadTileIsSmall() {
        // a tile beyond the map's radius is a few bytes; a real one about 3 KB (ranks of a 8 km map are 1-3 bytes)
        int[] out = new int[SpreadTiles.TILE_CELLS];
        java.util.Arrays.fill(out, -1);
        RegistryFriendlyByteBuf b = buf();
        StasisNet.SpreadTile.CODEC.encode(b, new StasisNet.SpreadTile(-3, 7, out));
        assertTrue(b.readableBytes() < 16, b.readableBytes() + " bytes");
        SpreadMap m = SpreadMap.build(5, 0, 0, 8000);
        RegistryFriendlyByteBuf c = buf();
        StasisNet.SpreadTile.CODEC.encode(c, new StasisNet.SpreadTile(0, 0, SpreadTiles.cut(m, 0, 0)));
        assertTrue(c.readableBytes() < 3 * SpreadTiles.TILE_CELLS + 16, c.readableBytes() + " bytes");
    }

    @Example
    void spreadTileRefusesAWrongSize() {
        assertThrows(IllegalArgumentException.class, () -> StasisNet.SpreadTile.CODEC.encode(buf(), new StasisNet.SpreadTile(0, 0, new int[5])));
    }

    @Property(tries = 100)
    void spreadForgetRoundTrips(@ForAll @Size(max = 40) List<Long> keys) {
        var f = new StasisNet.SpreadForget(keys);
        assertEquals(f, trip(StasisNet.SpreadForget.CODEC, f));
    }

    /** FIELD's float rounding must not move a block across the field's edge, except right on it (|norm - 1| tiny). */
    @Property(tries = 1000)
    void sentFieldsAgreeOnBlocks(@ForAll("fieldsInRange") Field f, @ForAll @net.jqwik.api.constraints.IntRange(min = -20, max = 20) int bx,
                                 @ForAll @net.jqwik.api.constraints.IntRange(min = -20, max = 20) int by,
                                 @ForAll @net.jqwik.api.constraints.IntRange(min = -20, max = 20) int bz) {
        Field g = trip(StasisNet.Fields.CODEC, new StasisNet.Fields(List.of(f), 0)).fields().getFirst();
        double n = 0;
        for (int i = 0; i < 8; i++)
            n = Math.max(n, f.norm(bx + (i & 1) - f.x(), f.column() ? 0 : by + (i >> 1 & 1) - f.y(), bz + (i >> 2 & 1) - f.z()));
        if (Math.abs(n - 1) < 1e-5) return;
        assertEquals(f.containsBlock(bx, by, bz), g.containsBlock(bx, by, bz));
        assertTrue(f.stretch().isOne() == g.stretch().isOne());
    }

    @Provide
    Arbitrary<Field> fieldsInRange() {
        Arbitrary<Double> c = Arbitraries.doubles().between(-5, 5);
        Arbitrary<Double> reach = Arbitraries.oneOf(Arbitraries.just(1.0), Arbitraries.doubles().between(1, 2.5));
        return Combinators.combine(c, c, c, Arbitraries.doubles().between(1, 12), reach, reach, reach, reach)
            .as((x, y, z, r, e, s, w, n) -> new Field(1, x, y, z, r).withStretch(new Field.Stretch(e, s, w, n)));
    }
}
