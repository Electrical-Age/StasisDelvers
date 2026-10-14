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
 * <p>
 * rate (0..1]: how fast time runs inside, as a share of the server's 20 TPS (Stasis.rateAt: where fields overlap the
 * fastest wins). 1 = full speed (the default, and what fields saved before rates existed load as); towers start their
 * field at StasisConfig.towerFieldRate.
 */
public record Field(int id, double x, double y, double z, double radius, double failing, double collapse, boolean column,
                    double rate, int seed, long formedAt) {
    public static final Codec<Field> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.fieldOf("id").forGetter(Field::id),
        Codec.DOUBLE.fieldOf("x").forGetter(Field::x),
        Codec.DOUBLE.fieldOf("y").forGetter(Field::y),
        Codec.DOUBLE.fieldOf("z").forGetter(Field::z),
        Codec.DOUBLE.fieldOf("radius").forGetter(Field::radius),
        Codec.DOUBLE.optionalFieldOf("failing", 0.0).forGetter(Field::failing),
        Codec.DOUBLE.optionalFieldOf("collapse", 0.0).forGetter(Field::collapse),
        Codec.BOOL.optionalFieldOf("column", false).forGetter(Field::column),
        Codec.DOUBLE.optionalFieldOf("rate", 1.0).forGetter(Field::rate),
        Codec.INT.optionalFieldOf("seed", 0).forGetter(Field::seed),
        Codec.LONG.optionalFieldOf("formed_at", 0L).forGetter(Field::formedAt)
    ).apply(i, Field::new));

    public Field(int id, double x, double y, double z, double radius) {
        this(id, x, y, z, radius, 0, 0, false, 1.0, 0, 0);
    }

    /** column: a vertical cylinder from the bottom of the world to the top (λ-Nero's test variant). */
    public Field(int id, double x, double y, double z, double radius, boolean column) {
        this(id, x, y, z, radius, 0, 0, column, 1.0, 0, 0);
    }

    public Field withRadius(double r) {
        return new Field(id, x, y, z, r, failing, collapse, column, rate, seed, formedAt);
    }

    public Field withFailing(double f) {
        return new Field(id, x, y, z, radius, f, collapse, column, rate, seed, formedAt);
    }

    public Field withCollapse(double c) {
        return new Field(id, x, y, z, radius, failing, c, column, rate, seed, formedAt);
    }

    public Field withRate(double r) {
        return new Field(id, x, y, z, radius, failing, collapse, column, clampRate(r), seed, formedAt);
    }

    /** A new seed (24 bits: the shader gets it as an exact float): a fresh pattern, e.g. for a collapse. */
    public Field withSeed(int s) {
        return new Field(id, x, y, z, radius, failing, collapse, column, rate, s & 0xFFFFFF, formedAt);
    }

    /** Field rates live in (0, 1]: a field never stops time (that's the land outside) and never speeds it up. */
    public static double clampRate(double r) {
        return Math.max(0.001, Math.min(1.0, r));
    }

    /** True if the whole block at (bx, by, bz) lies inside the sphere (and hasn't stopped in a collapse). */
    public boolean containsBlock(int bx, int by, int bz) {
        double dx = (bx + 0.5 >= x ? bx + 1 : bx) - x;
        double dy = column ? 0 : (by + 0.5 >= y ? by + 1 : by) - y;
        double dz = (bz + 0.5 >= z ? bz + 1 : bz) - z;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > radius * radius) return false;
        return collapse <= 0 || !collapsed(bx, by, bz, Math.sqrt(d2));
    }

    /** True if the point is inside the sphere (entities: their feet). */
    public boolean containsPoint(double px, double py, double pz) {
        double dx = px - x, dy = column ? 0 : py - y, dz = pz - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /**
     * During a collapse: has this block stopped yet? Outer blocks first on average (45% distance), in patches (55%:
     * two octaves of noise on the field's seed), plus 2-4 seeded "tears" (great-circle bands that fail early), so no
     * two collapses look alike (Baughn). The shader draws the same rule (stasis.fsh: collapseScore).
     */
    boolean collapsed(int bx, int by, int bz, double dist) {
        return 1 - collapseScore(bx, by, bz, dist) < collapse;
    }

    float collapseScore(int bx, int by, int bz, double dist) {
        float dx = (float) (bx + 0.5 - x), dy = column ? 0 : (float) (by + 0.5 - y), dz = (float) (bz + 0.5 - z);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float score = 0.45f * (float) Math.min(1, dist / radius) + 0.55f * Noise.fbm32(bx / 6f, by / 6f, bz / 6f, seed);
        if (len > 1e-3f) {
            int tears = 2 + (int) (Noise.hash32(seed, 7, 0, 0) * 3);
            for (int i = 0; i < tears; i++) {
                float nx = Noise.hash32(seed, i, 1, 0) * 2 - 1, ny = Noise.hash32(seed, i, 2, 0) * 2 - 1, nz = Noise.hash32(seed, i, 3, 0) * 2 - 1;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-6f;
                float band = Math.abs((dx * nx + dy * ny + dz * nz) / (len * nl));
                if (band < 0.07f) score -= 0.35f * (1 - band / 0.07f);
            }
        }
        return score;
    }
}
