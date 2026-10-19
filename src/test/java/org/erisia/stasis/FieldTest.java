package org.erisia.stasis;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Field geometry: whole blocks, points, the conservative box bounds, and the save codec. */
class FieldTest {
    @Provide
    Arbitrary<Field> fields() {
        Arbitrary<Double> coord = Arbitraries.doubles().between(-20, 20).ofScale(2);
        Arbitrary<Double> radius = Arbitraries.doubles().between(0.5, 14).ofScale(2);
        Arbitrary<Double> reach = Arbitraries.doubles().between(1, 2.5).ofScale(2);
        Arbitrary<Field.Stretch> stretch = Combinators.combine(reach, reach, reach, reach).as(Field.Stretch::new);
        return Combinators.combine(coord, coord, coord, radius, Arbitraries.of(false, false, false, true), stretch)
            .as((x, y, z, r, column, st) -> new Field(1, x, y, z, r, column).withStretch(st));
    }

    /** The definition (Field's javadoc): a block is inside only if all of it is, i.e. all 8 corners. */
    static boolean allCornersInside(Field f, int bx, int by, int bz) {
        for (int i = 0; i < 8; i++) {
            double cx = bx + (i & 1), cy = by + (i >> 1 & 1), cz = bz + (i >> 2 & 1);
            if (!f.containsPoint(cx, cy, cz)) return false;
        }
        return true;
    }

    static int[] block(Field f, double u, double v, double w) {
        double R = f.reach() + 2;
        return new int[]{(int) Math.floor(f.x() + u * R), (int) Math.floor(f.y() + v * (f.radius() + 2)), (int) Math.floor(f.z() + w * R)};
    }

    @Property(tries = 3000)
    void containsBlockIsAllEightCorners(@ForAll("fields") Field f, @ForAll @DoubleRange(min = -1, max = 1) double u,
                                        @ForAll @DoubleRange(min = -1, max = 1) double v, @ForAll @DoubleRange(min = -1, max = 1) double w) {
        int[] b = block(f, u, v, w);
        assertEquals(allCornersInside(f, b[0], b[1], b[2]), f.containsBlock(b[0], b[1], b[2]), () -> f + " block " + java.util.Arrays.toString(b));
    }

    /** Blocks straddling the centre on a stretched axis: the case the far-corner shortcut got wrong (bug A). Every
     *  block of the plane through the centre block, so the field's edge is always crossed. */
    @Property(tries = 1000)
    void containsBlockStraddlingTheCentre(@ForAll("fields") Field f, @ForAll boolean alongX) {
        int cx = (int) Math.floor(f.x()), cy = (int) Math.floor(f.y()), cz = (int) Math.floor(f.z());
        int R = (int) Math.ceil(f.reach()) + 2;
        for (int d = -R; d <= R; d++)
            for (int dy = -R; dy <= R; dy++) {
                int bx = cx + (alongX ? 0 : d), by = cy + dy, bz = cz + (alongX ? d : 0);
                assertEquals(allCornersInside(f, bx, by, bz), f.containsBlock(bx, by, bz), () -> f + " block " + bx + "," + by + "," + bz);
            }
    }

    @Example
    void bugA_westCornerOfACentreBlock() {
        // the survey's repro: a field stretched 1.5 east; block (0, 9, 0) has a west corner outside
        Field f = new Field(1, 0.5, 0.5, 0.5, 9.52).withStretch(new Field.Stretch(1.5, 1, 1, 1));
        assertFalse(allCornersInside(f, 0, 9, 0));
        assertFalse(f.containsBlock(0, 9, 0));
    }

    @Property(tries = 2000)
    void containsPointMatchesNorm(@ForAll("fields") Field f, @ForAll @DoubleRange(min = -1, max = 1) double u,
                                  @ForAll @DoubleRange(min = -1, max = 1) double v, @ForAll @DoubleRange(min = -1, max = 1) double w) {
        double px = f.x() + u * (f.reach() + 1), py = f.y() + v * (f.radius() + 1), pz = f.z() + w * (f.reach() + 1);
        double n = f.norm(px - f.x(), f.column() ? 0 : py - f.y(), pz - f.z());
        assertEquals(n <= 1, f.containsPoint(px, py, pz));
        // the bounds used for chunk indexing and tickets hold every inside point
        if (f.containsPoint(px, py, pz)) {
            assertTrue(px >= f.minX() - 1e-9 && px <= f.maxX() + 1e-9 && pz >= f.minZ() - 1e-9 && pz <= f.maxZ() + 1e-9);
        }
    }

    @Property(tries = 2000)
    void towardLandsOnTheScaledSurface(@ForAll("fields") Field f, @ForAll @DoubleRange(min = -1, max = 1) double dx,
                                       @ForAll @DoubleRange(min = -1, max = 1) double dy, @ForAll @DoubleRange(min = -1, max = 1) double dz,
                                       @ForAll @DoubleRange(min = 0, max = 1) double frac) {
        if (f.column() || dx * dx + dy * dy + dz * dz < 1e-6) return;
        double[] p = f.toward(dx, dy, dz, frac);
        assertEquals(frac, f.norm(p[0] - f.x(), p[1] - f.y(), p[2] - f.z()), 1e-9);
    }

    /** normSpan bounds every block in a box: what Stasis.sectionState relies on for whole sections. */
    @Property(tries = 1000)
    void normSpanBoundsTheBlocksOfABox(@ForAll("fields") Field f, @ForAll @DoubleRange(min = -1, max = 1) double u,
                                       @ForAll @DoubleRange(min = -1, max = 1) double v, @ForAll @DoubleRange(min = -1, max = 1) double w,
                                       @ForAll @IntRange(min = 1, max = 5) int size) {
        int[] b = block(f, u, v, w);
        double[] ex = f.normSpan(0, b[0], b[0] + size), ey = f.normSpan(1, b[1], b[1] + size), ez = f.normSpan(2, b[2], b[2] + size);
        double near = ex[0] * ex[0] + ey[0] * ey[0] + ez[0] * ez[0], far = ex[1] * ex[1] + ey[1] * ey[1] + ez[1] * ez[1];
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++)
                for (int k = 0; k < size; k++) {
                    boolean in = f.containsBlock(b[0] + i, b[1] + j, b[2] + k);
                    if (near > 1) assertFalse(in, () -> "near bound says none inside: " + f);
                    if (far <= 1) assertTrue(in, () -> "far bound says all inside: " + f);
                }
    }

    @Property(tries = 500)
    void codecRoundTrips(@ForAll("fields") Field f, @ForAll @DoubleRange(min = 0, max = 1) double collapse,
                         @ForAll @DoubleRange(min = 0.01, max = 1) double rate, @ForAll int seed, @ForAll long formedAt) {
        Field g = f.withCollapse(collapse).withRate(rate).withSeed(seed).withFormedAt(formedAt).withShield(0.25, 0.5, -1.5);
        var json = Field.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, g).getOrThrow();
        assertEquals(g, Field.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow());
        var nbt = Field.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, g).getOrThrow();
        assertEquals(g, Field.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, nbt).getOrThrow());
    }

    @Example
    void codecReadsOldFieldsWithDefaults() {
        var json = com.google.gson.JsonParser.parseString("{\"id\":3,\"x\":1.5,\"y\":64,\"z\":-2,\"radius\":6}");
        Field f = Field.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(new Field(3, 1.5, 64, -2, 6), f);
        assertEquals(1.0, f.rate());
        assertArrayEquals(new double[]{1, 1, 1, 1}, new double[]{f.stretch().e(), f.stretch().s(), f.stretch().w(), f.stretch().n()});
    }
}
