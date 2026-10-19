package org.erisia.stasis;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The client's spread lookup gives the server's answer (docs/spreading-mode.md): the rank tiles (SpreadTiles) read
 * through SpreadMap.rank1024 equal the SpreadMap itself on every column they cover, through the wire codec too; and
 * the shader's 32-bit split (SpreadMap.stilledSplit, mirrored in stasis.fsh spreadStilled) equals stilled() on every
 * boundary: all 32 x 32 offsets in a cell, ranks -1, 0, N-1 and MISSING, and P at 0, N and next to the corners' ranks.
 */
class SpreadTilesTest {
    /** The tiles the server would send for blocks x0..x1, z0..z1, after the wire codec. */
    static SpreadTiles tilesFor(SpreadMap m, int x0, int x1, int z0, int z1) {
        SpreadTiles t = new SpreadTiles();
        int[] tx = SpreadTiles.tilesFor(x0, x1), tz = SpreadTiles.tilesFor(z0, z1);
        for (int i = tx[0]; i <= tx[1]; i++)
            for (int k = tz[0]; k <= tz[1]; k++) {
                var sent = NetCodecTest.trip(StasisNet.SpreadTile.CODEC, new StasisNet.SpreadTile(i, k, SpreadTiles.cut(m, i, k)));
                t.put(sent.tx(), sent.tz(), sent.ranks());
            }
        return t;
    }

    @Property(tries = 30)
    void clientLookupIsTheServers(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = -300000, max = 300000) int sx,
                                  @ForAll @IntRange(min = -300000, max = 300000) int sz, @ForAll @IntRange(min = 64, max = 2500) int radius,
                                  @ForAll @IntRange(min = -3000, max = 3000) int px, @ForAll @IntRange(min = -3000, max = 3000) int pz) {
        SpreadMap m = SpreadMap.build(seed, sx, sz, radius);
        // a player somewhere near (or beyond) the map's edge, with the server's range round them
        int x = sx + px, z = sz + pz, range = 13 * 16 + 32;
        SpreadTiles t = tilesFor(m, x - range, x + range, z - range, z + range);
        Random r = new Random(seed);
        for (int n = 0; n < 4000; n++) {
            int bx = x - range + r.nextInt(2 * range + 1), bz = z - range + r.nextInt(2 * range + 1);
            assertEquals(SpreadMap.rank1024(m, bx, bz), SpreadMap.rank1024(t, bx, bz), "column " + bx + " " + bz);
            int p = r.nextInt(m.count + 1);
            assertEquals(SpreadMap.stilled(m, bx, bz, p), SpreadMap.stilled(t, bx, bz, p));
        }
        // and the chunk extremes the client re-meshes by
        for (int cx = (x - range) >> 4; cx <= (x + range) >> 4; cx++) {
            int cz = (z >> 4) + r.nextInt(5) - 2;
            assertEquals(SpreadMap.chunkMin1024(m, cx, cz), SpreadMap.chunkMin1024(t, cx, cz));
            assertEquals(SpreadMap.chunkMax1024(m, cx, cz), SpreadMap.chunkMax1024(t, cx, cz));
        }
    }

    /** Uncovered cells run at every P (the client's answer before its tiles come), and dropping a tile restores that. */
    @Property(tries = 50)
    void missingCellsRun(@ForAll int cx, @ForAll int cz, @ForAll @IntRange(min = 0, max = 30_000_000) int p) {
        SpreadTiles t = new SpreadTiles();
        assertEquals(SpreadTiles.MISSING, t.rank(cx, cz));
        int tx = Math.floorDiv(cx, SpreadTiles.TILE), tz = Math.floorDiv(cz, SpreadTiles.TILE);
        int[] all = new int[SpreadTiles.TILE_CELLS];
        java.util.Arrays.fill(all, -1);
        t.put(tx, tz, all);
        assertEquals(-1, t.rank(cx, cz));
        t.remove(SpreadTiles.key(tx, tz));
        int bx = (int) Math.max(Integer.MIN_VALUE / 2, Math.min(Integer.MAX_VALUE / 2, (long) cx * SpreadMap.CELL));
        int bz = (int) Math.max(Integer.MIN_VALUE / 2, Math.min(Integer.MAX_VALUE / 2, (long) cz * SpreadMap.CELL));
        assertEquals(false, SpreadMap.stilled(t, bx, bz, p));
    }

    @Provide
    Arbitrary<Integer> rank() {
        return Arbitraries.oneOf(Arbitraries.integers().between(-1, 3_000_000), Arbitraries.of(-1, 0, 1, 2_999_999, SpreadTiles.MISSING));
    }

    /** The shader's split arithmetic against the exact long one, at all 32 x 32 offsets, P near each corner and at ends. */
    @Property(tries = 400)
    void shaderSplitIsExact(@ForAll("rank") int r00, @ForAll("rank") int r10, @ForAll("rank") int r01, @ForAll("rank") int r11,
                            @ForAll @IntRange(min = 0, max = 3_000_001) int pRandom) {
        int[] ps = {0, 3_000_000, pRandom, Math.max(0, r00), Math.max(0, r00 + 1), Math.max(0, r11 - 1), Math.max(0, r10), Math.max(0, r01),
            SpreadTiles.MISSING};
        for (int p : ps)
            for (int a = 0; a < SpreadMap.CELL; a++)
                for (int b = 0; b < SpreadMap.CELL; b++) {
                    long exact = (long) r00 * (32 - a) * (32 - b) + (long) r10 * a * (32 - b) + (long) r01 * (32 - a) * b + (long) r11 * a * b;
                    assertEquals(exact < (long) p * SpreadMap.SCALE, SpreadMap.stilledSplit(r00, r10, r01, r11, a, b, p),
                        r00 + " " + r10 + " " + r01 + " " + r11 + " a " + a + " b " + b + " p " + p);
                }
    }

    /** ... and on real maps through the same corner lookup the shader does (cells i, i + 1 from floor((x - 16) / 32)). */
    @Property(tries = 20)
    void shaderSplitMatchesStilledOnMaps(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed, @ForAll @IntRange(min = 64, max = 1500) int radius) {
        SpreadMap m = SpreadMap.build(seed, 0, 0, radius);
        Random r = new Random(seed ^ 77);
        for (int n = 0; n < 20000; n++) {
            int x = r.nextInt(2 * radius + 200) - radius - 100, z = r.nextInt(2 * radius + 200) - radius - 100;
            int p = r.nextInt(m.count + 1);
            int fx = x - SpreadMap.HALF, fz = z - SpreadMap.HALF;
            int i = Math.floorDiv(fx, SpreadMap.CELL), k = Math.floorDiv(fz, SpreadMap.CELL);
            boolean split = SpreadMap.stilledSplit(m.rank(i, k), m.rank(i + 1, k), m.rank(i, k + 1), m.rank(i + 1, k + 1),
                fx - i * SpreadMap.CELL, fz - k * SpreadMap.CELL, p);
            assertEquals(SpreadMap.stilled(m, x, z, p), split, x + " " + z + " p " + p);
        }
    }
}
