package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A stasis field: a sphere (stretched by a tower's booms: Stretch) where time runs. A block is inside only if all of it is (Baughn: a block the boundary
 * cuts through counts as outside), so the test uses the block's corner farthest from the centre.
 * <p>
 * failing (0..1): how far the shield has failed while its tower runs short of power (visual only). collapse (0..1):
 * after the tower's store ran out, the share of the field that has stopped again; outer blocks go first on average,
 * in noise patches, and at 1 nothing is left.
 * <p>
 * rate (0..MAX_RATE]: how fast time runs inside, as a share of the server's 20 TPS (Stasis.rateAt: where fields overlap the
 * fastest wins). 1 = full speed (the default, and what fields saved before rates existed load as); towers start their
 * field at StasisConfig.towerFieldRate. Above 1 an acceleration field: the rule counts it as 1, and Stasis.speedAt gives
 * the blocks inside extra ticks (λ-Nero).
 */
public record Field(int id, double x, double y, double z, double radius, double failing, double collapse, boolean column,
                    double rate, int seed, long formedAt, double amber, double dropout, double side, Stretch stretch) {
    /**
     * How far the field reaches each way along the ground, as multiples of radius (Baughn: a tower's booms stretch its
     * field towards their side, notes/tower/PLAN-stretch.md): east (+x), south (+z), west (-x), north (-z); up and down
     * stay at radius. The shape is an ellipsoid in quarters: (dx/ax)^2 + (dy/r)^2 + (dz/az)^2 <= 1 with ax the east or
     * west reach by the side dx is on (and z alike). The quarters meet with the same section and a vertical tangent,
     * so the surface has no crease, and it is convex.
     */
    public record Stretch(double e, double s, double w, double n) {
        public static final Stretch ONE = new Stretch(1, 1, 1, 1);
        public static final Codec<Stretch> CODEC = Codec.DOUBLE.listOf(4, 4).xmap(
            l -> new Stretch(l.get(0), l.get(1), l.get(2), l.get(3)), t -> java.util.List.of(t.e, t.s, t.w, t.n));

        public double max() {
            return Math.max(Math.max(e, s), Math.max(w, n));
        }

        public boolean isOne() {
            return e == 1 && s == 1 && w == 1 && n == 1;
        }

        /** Does any side reach less far than in o? */
        public boolean lessThan(Stretch o) {
            return e < o.e || s < o.s || w < o.w || n < o.n;
        }
    }

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
        Codec.LONG.optionalFieldOf("formed_at", 0L).forGetter(Field::formedAt),
        Codec.DOUBLE.optionalFieldOf("amber", 0.0).forGetter(Field::amber),
        Codec.DOUBLE.optionalFieldOf("dropout", 0.0).forGetter(Field::dropout),
        Codec.DOUBLE.optionalFieldOf("side", 0.0).forGetter(Field::side),
        Stretch.CODEC.optionalFieldOf("stretch", Stretch.ONE).forGetter(Field::stretch)
    ).apply(i, Field::new));

    public Field {
        if (rate > 1) Stasis.noteFast();
    }

    public Field(int id, double x, double y, double z, double radius) {
        this(id, x, y, z, radius, 0, 0, false, 1.0, 0, 0, 0, 0, 0, Stretch.ONE);
    }

    /** column: a vertical cylinder from the bottom of the world to the top (λ-Nero's test variant). */
    public Field(int id, double x, double y, double z, double radius, boolean column) {
        this(id, x, y, z, radius, 0, 0, column, 1.0, 0, 0, 0, 0, 0, Stretch.ONE);
    }

    /**
     * The fields whose running land changed shape from old to now (both versions of a changed one; added and removed
     * ones): what must be re-meshed. Failing and rate changes don't count (a rate is always above 0).
     */
    public static java.util.List<Field> changedShapes(java.util.List<Field> old, java.util.List<Field> now) {
        java.util.Map<Integer, Field> before = new java.util.HashMap<>();
        for (Field f : old) before.put(f.id(), f);
        java.util.List<Field> out = new java.util.ArrayList<>();
        for (Field f : now) {
            Field b = before.remove(f.id());
            if (b == null) out.add(f);
            else if (!b.runShape().equals(f.runShape())) {
                out.add(b);
                out.add(f);
            }
        }
        out.addAll(before.values());
        return out;
    }

    /** What decides which blocks run (failing and the shield's look are visual; a rate is always above 0). */
    Field runShape() {
        return withFailing(0).withRate(1.0).withShield(0, 0, side);
    }

    public Field withRadius(double r) {
        return new Field(id, x, y, z, r, failing, collapse, column, rate, seed, formedAt, amber, dropout, side, stretch);
    }

    public Field withFailing(double f) {
        return new Field(id, x, y, z, radius, f, collapse, column, rate, seed, formedAt, amber, dropout, side, stretch);
    }

    public Field withCollapse(double c) {
        return new Field(id, x, y, z, radius, failing, c, column, rate, seed, formedAt, amber, dropout, side, stretch);
    }

    public Field withRate(double r) {
        return new Field(id, x, y, z, radius, failing, collapse, column, clampRate(r), seed, formedAt, amber, dropout, side, stretch);
    }

    /** Formed at this game time: clients animate the start-up (towers only; 0 = no animation). */
    public Field withFormedAt(long t) {
        return new Field(id, x, y, z, radius, failing, collapse, column, rate, seed, t, amber, dropout, side, stretch);
    }

    /** A new seed (24 bits: the shader gets it as an exact float): a fresh pattern, e.g. for a collapse. */
    public Field withSeed(int s) {
        return new Field(id, x, y, z, radius, failing, collapse, column, rate, s & 0xFFFFFF, formedAt, amber, dropout, side, stretch);
    }

    /**
     * The shield's look (visual only, Baughn 2026-10-17): amber = the share of hexes gone orange, dropout = the share
     * dropped out (the same hexes first: each hex has one random key), side = the yaw (radians) a collapse leans
     * towards. In a collapse, dropout is held at its value when the store ran out.
     */
    public Field withShield(double a, double d, double sd) {
        return new Field(id, x, y, z, radius, failing, collapse, column, rate, seed, formedAt, a, d, sd, stretch);
    }

    /** Booms' reach (Stretch; column fields stay round). */
    public Field withStretch(Stretch st) {
        return new Field(id, x, y, z, radius, failing, collapse, column, rate, seed, formedAt, amber, dropout, side, st);
    }

    /** Half-axis along x on the side of dx (east if dx >= 0), and along z alike. */
    public double ax(double dx) {
        return radius * (column ? 1 : dx >= 0 ? stretch.e : stretch.w);
    }

    public double az(double dz) {
        return radius * (column ? 1 : dz >= 0 ? stretch.s : stretch.n);
    }

    /** The farthest the field reaches horizontally from its centre (bounds: chunk index, tickets, re-meshing). */
    public double reach() {
        return column ? radius : radius * stretch.max();
    }

    /** The westmost, eastmost, northmost and southmost x/z the field reaches. */
    public double minX() { return x - (column ? radius : radius * stretch.w); }
    public double maxX() { return x + (column ? radius : radius * stretch.e); }
    public double minZ() { return z - (column ? radius : radius * stretch.n); }
    public double maxZ() { return z + (column ? radius : radius * stretch.s); }

    /** Distance from the centre in units of the field's size: 1 on the surface (the sphere's d / r). */
    public double norm(double dx, double dy, double dz) {
        double u = dx / ax(dx), v = column ? 0 : dy / radius, w = dz / az(dz);
        return Math.sqrt(u * u + v * v + w * w);
    }

    /** The point at `frac` of the way from the centre to the surface in direction (dx, dy, dz) (any length; samplers,
     *  bolts). The direction is taken in the unit-sphere space, so 26 directions spread over the stretched shape. */
    public double[] toward(double dx, double dy, double dz, double frac) {
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l == 0) return new double[]{x, y, z};
        double ux = dx / l, uy = dy / l, uz = dz / l;
        return new double[]{x + ux * ax(ux) * frac, y + uy * radius * frac, z + uz * az(uz) * frac};
    }

    /**
     * The nearest and farthest |coordinate| of the span [a0, a1] on axis (0 x, 1 y, 2 z) from the centre, in units of
     * the half-axis on that side ({nearest, farthest}; y is 0 for a column field). For conservative box tests.
     */
    public double[] normSpan(int axis, double a0, double a1) {
        if (axis == 1 && column) return new double[]{0, 0};
        double c = axis == 0 ? x : axis == 1 ? y : z;
        double u0 = a0 - c, u1 = a1 - c;
        double v0 = Math.abs(u0) / half(axis, u0), v1 = Math.abs(u1) / half(axis, u1);
        double near = u0 <= 0 && u1 >= 0 ? 0 : Math.min(v0, v1);
        return new double[]{near, Math.max(v0, v1)};
    }

    /** Of the block's two faces on this axis (offsets d0 and d0 + 1 from the centre), the one farther in units of the
     *  half-axis on its side. Per axis, since norm is a sum over axes; on a stretched axis the block straddling the
     *  centre can reach farther on its shorter side, so the Euclidean far side isn't enough. */
    private double far(int axis, double d0) {
        double d1 = d0 + 1;
        return Math.abs(d0) / half(axis, d0) > Math.abs(d1) / half(axis, d1) ? d0 : d1;
    }

    private double half(int axis, double d) {
        return axis == 0 ? ax(d) : axis == 1 ? radius : az(d);
    }

    /** The fastest a field may run (λ-Nero: "cap at 10× for now"). */
    public static final double MAX_RATE = 10;

    /**
     * Field rates live in (0, MAX_RATE]: a field never stops time (that's the land outside). Above 1 it is an
     * acceleration field (Stasis.speedAt); for the rule (Stasis.rateAt) it counts as 1.
     */
    public static double clampRate(double r) {
        return Math.max(0.001, Math.min(MAX_RATE, r));
    }

    /** An acceleration field (rate above 1)? Constructing one tells Stasis.speedAt to look (Stasis.noteFast). */
    public boolean fast() {
        return rate > 1;
    }

    /** True if the whole block at (bx, by, bz) lies inside the field (and hasn't stopped in a collapse). */
    public boolean containsBlock(int bx, int by, int bz) {
        double dx = far(0, bx - x), dy = column ? 0 : far(1, by - y), dz = far(2, bz - z);
        double n = norm(dx, dy, dz);
        if (n > 1) return false;
        return collapse <= 0 || !collapsed(bx, by, bz, n);
    }

    /** True if the point is inside the field (entities: their feet). */
    public boolean containsPoint(double px, double py, double pz) {
        return norm(px - x, column ? 0 : py - y, pz - z) <= 1;
    }

    /**
     * During a collapse: has this block stopped yet? Outer blocks first on average (45% distance), in patches (55%:
     * two octaves of noise on the field's seed), plus 2-4 seeded "tears" (great-circle bands that fail early), leaning
     * towards the collapse's side (Baughn 2026-10-17). The shield's hexes follow their own rule (stasis.fsh: a collapse
     * ends when half of them are lost, at collapse 0.3-0.5), so the land goes 1.6x as fast here.
     */
    boolean collapsed(int bx, int by, int bz, double norm) {
        return 1 - collapseScore(bx, by, bz, norm) < 1.6 * collapse;
    }

    /** norm: the block's far corner's Field.norm. Directions are taken in the unit-sphere space (stretch divided out),
     *  so tears and the lean follow the stretched shape; stasis.fsh collapseScore mirrors this. */
    float collapseScore(int bx, int by, int bz, double norm) {
        double ox = bx + 0.5 - x, oz = bz + 0.5 - z;
        float dx = (float) (ox / ax(ox) * radius), dy = column ? 0 : (float) (by + 0.5 - y), dz = (float) (oz / az(oz) * radius);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float score = 0.45f * (float) Math.min(1, norm) + 0.55f * Noise.fbm32(bx / 6f, by / 6f, bz / 6f, seed);
        if (len > 1e-3f) {
            int tears = 2 + (int) (Noise.hash32(seed, 7, 0, 0) * 3);
            for (int i = 0; i < tears; i++) {
                float nx = Noise.hash32(seed, i, 1, 0) * 2 - 1, ny = Noise.hash32(seed, i, 2, 0) * 2 - 1, nz = Noise.hash32(seed, i, 3, 0) * 2 - 1;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-6f;
                float band = Math.abs((dx * nx + dy * ny + dz * nz) / (len * nl));
                if (band < 0.07f) score -= 0.35f * (1 - band / 0.07f);
            }
            score += 0.25f * (float) ((dx * Math.cos(side) + dz * Math.sin(side)) / len);
        }
        return score;
    }
}
