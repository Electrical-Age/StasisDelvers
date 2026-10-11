package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A stasis field: a sphere where time runs. A block is inside only if all of it is (Baughn: a block the boundary
 * cuts through counts as outside), so the test uses the block's corner farthest from the centre.
 */
public record Field(int id, double x, double y, double z, double radius) {
    public static final Codec<Field> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.fieldOf("id").forGetter(Field::id),
        Codec.DOUBLE.fieldOf("x").forGetter(Field::x),
        Codec.DOUBLE.fieldOf("y").forGetter(Field::y),
        Codec.DOUBLE.fieldOf("z").forGetter(Field::z),
        Codec.DOUBLE.fieldOf("radius").forGetter(Field::radius)
    ).apply(i, Field::new));

    /** True if the whole block at (bx, by, bz) lies inside the sphere. */
    public boolean containsBlock(int bx, int by, int bz) {
        double dx = (bx + 0.5 >= x ? bx + 1 : bx) - x;
        double dy = (by + 0.5 >= y ? by + 1 : by) - y;
        double dz = (bz + 0.5 >= z ? bz + 1 : bz) - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** True if the point is inside the sphere (entities: their feet). */
    public boolean containsPoint(double px, double py, double pz) {
        double dx = px - x, dy = py - y, dz = pz - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
