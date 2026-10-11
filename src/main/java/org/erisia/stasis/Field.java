package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A stasis field: a sphere where time runs. A block is inside only if all of it is (Baughn: a block the boundary
 * cuts through counts as outside), so the test uses the block's corner farthest from the centre.
 * <p>
 * failing (0..1): how far the shield has failed while its tower runs short of power (visual only). collapse (0..1):
 * after the tower's store ran out, the share of the field that has stopped again; outer blocks go first on average,
 * in noise patches, and at 1 nothing is left.
 */
public record Field(int id, double x, double y, double z, double radius, double failing, double collapse) {
    public static final Codec<Field> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.fieldOf("id").forGetter(Field::id),
        Codec.DOUBLE.fieldOf("x").forGetter(Field::x),
        Codec.DOUBLE.fieldOf("y").forGetter(Field::y),
        Codec.DOUBLE.fieldOf("z").forGetter(Field::z),
        Codec.DOUBLE.fieldOf("radius").forGetter(Field::radius),
        Codec.DOUBLE.optionalFieldOf("failing", 0.0).forGetter(Field::failing),
        Codec.DOUBLE.optionalFieldOf("collapse", 0.0).forGetter(Field::collapse)
    ).apply(i, Field::new));

    public Field(int id, double x, double y, double z, double radius) {
        this(id, x, y, z, radius, 0, 0);
    }

    public Field withRadius(double r) {
        return new Field(id, x, y, z, r, failing, collapse);
    }

    public Field withFailing(double f) {
        return new Field(id, x, y, z, radius, f, collapse);
    }

    public Field withCollapse(double c) {
        return new Field(id, x, y, z, radius, failing, c);
    }

    /** True if the whole block at (bx, by, bz) lies inside the sphere (and hasn't stopped in a collapse). */
    public boolean containsBlock(int bx, int by, int bz) {
        double dx = (bx + 0.5 >= x ? bx + 1 : bx) - x;
        double dy = (by + 0.5 >= y ? by + 1 : by) - y;
        double dz = (bz + 0.5 >= z ? bz + 1 : bz) - z;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > radius * radius) return false;
        return collapse <= 0 || !collapsed(bx, by, bz, Math.sqrt(d2));
    }

    /** True if the point is inside the sphere (entities: their feet). */
    public boolean containsPoint(double px, double py, double pz) {
        double dx = px - x, dy = py - y, dz = pz - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** During a collapse: has this block stopped yet? Outer blocks first on average (70% distance, 30% noise). */
    boolean collapsed(int bx, int by, int bz, double dist) {
        double score = 0.7 * Math.min(1, dist / radius) + 0.3 * Noise.value(bx / 5.0, by / 5.0, bz / 5.0);
        return 1 - score < collapse;
    }
}
