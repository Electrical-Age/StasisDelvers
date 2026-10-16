package org.erisia.stasis;

import net.minecraft.world.level.Level;

/**
 * Strata weather (rift stage 2, notes/rifts/STAGE2-PLAN.md): the strata under each rift is split into pockets,
 * world-aligned 12-block cubes, each with its own deterministic cycle: clear (25%), mist, a 20 s violet warning,
 * rift (10-20%, 15% on average), mist again. Every cycle is re-hashed (rift share, where in the mist the rift
 * falls), so players can't learn a pocket's timetable. Both sides compute it from the deep seed and Deeps.time.
 * <p>
 * Where it applies: the strata mask, 4-block cells that touch the strata (Strata.at isn't OUT at any of the cell's
 * 8 corner blocks). Rules and visuals use the same mask, so what you see is what hits you.
 */
public final class Pockets {
    private Pockets() {
    }

    public static final int POCKET = 12, CELL = 4;
    /** Pocket periods (s); the shader gets t mod P and floor(t / P) for each. */
    static final int[] PERIODS = {600, 900, 1200, 1800};
    public static final double CLEAR = 0.25, VIOLET = 20, RAMP = 30;

    public enum State { CLEAR, MIST, VIOLET, RIFT }

    /** A pocket's weather: its state, a visual density (0 clear, ~0.6 mist, rising through violet to 1 in rift), and
     * the seconds until its state next changes. */
    public record Weather(State state, float density, double untilNext) {
    }

    static int period(int id, int px, int py, int pz) {
        return PERIODS[(int) (Noise.hash32(px, py, pz, id ^ 0x90C1) * PERIODS.length) & 3];
    }

    static double phase(int id, int px, int py, int pz, int period) {
        return Noise.hash32(px, py, pz, id ^ 0x90C2) * period;
    }

    /** The layout of one cycle (seconds from its start): mist from c, violet from v, rift from r, mist again from m. */
    record Cycle(double c, double v, double r, double m) {
    }

    static Cycle cycle(int id, int px, int py, int pz, long n, int P) {
        // per cycle: the rift's share (10-20%) and where in the mist it falls (30-70% of the way)
        int salt = (int) (n * 0x9E3779B1L) ^ id;
        double rift = (0.10 + 0.10 * Noise.hash32(px, py, pz, salt ^ 0x90C3)) * P;
        double c = CLEAR * P, mist = P - c - VIOLET - rift;
        double v = c + mist * (0.3 + 0.4 * Noise.hash32(px, py, pz, salt ^ 0x90C4));
        return new Cycle(c, v, v + VIOLET, v + VIOLET + rift);
    }

    /** The weather of pocket (px, py, pz) of the strata of deep id at time t (s, Deeps.time). */
    public static Weather weather(int id, int px, int py, int pz, double t) {
        int P = period(id, px, py, pz);
        double s = t + phase(id, px, py, pz, P);
        long n = (long) Math.floor(s / P);
        double u = s - (double) n * P;
        Cycle cy = cycle(id, px, py, pz, n, P);
        if (u < cy.c) return new Weather(State.CLEAR, 0, cy.c - u);
        if (u < cy.v) return new Weather(State.MIST, mist(Math.min(u - cy.c, P - u)), cy.v - u);
        if (u < cy.r) {
            float k = (float) ((u - cy.v) / VIOLET);
            return new Weather(State.VIOLET, 0.6f + 0.4f * k * k, cy.r - u);
        }
        if (u < cy.m) return new Weather(State.RIFT, 1, cy.m - u);
        return new Weather(State.MIST, mist(P - u), P - u);
    }

    private static float mist(double fromEdge) {
        double k = Math.min(1, fromEdge / RAMP);
        return (float) (0.6 * k * k * (3 - 2 * k));
    }

    /** Seconds of non-clear weather (mist, violet or rift: crystals grow) in pocket (px, py, pz) from time 0 to t. */
    public static double exposure(int id, int px, int py, int pz, double t) {
        int P = period(id, px, py, pz);
        double ph = phase(id, px, py, pz, P);
        return grey(t + ph, P) - grey(ph, P);
    }

    /** Non-clear seconds in cycle time [0, s): the clear part is always the first 25% of a cycle. */
    private static double grey(double s, int P) {
        long n = (long) Math.floor(s / P);
        double u = s - (double) n * P;
        return n * (1 - CLEAR) * P + Math.max(0, u - CLEAR * P);
    }

    // ---- the strata mask

    /** How far any strata reaches from its deep's centre (bounds radius 2.3R+4 at R=64, roots, vents to R+80). */
    static final float REACH = 200;

    /** The deep whose strata mask covers block (x, y, z), or null. Only in stopping dimensions (Level form). */
    public static StaticDeeps.Deep strataAt(Level level, int x, int y, int z) {
        if (!Stasis.stops(level)) return null;
        int seed = Deeps.seed(level);
        return seed == 0 ? null : strataAt(seed, x, y, z);
    }

    public static StaticDeeps.Deep strataAt(int seed, int x, int y, int z) {
        int cx = Math.floorDiv(x, CELL) * CELL, cy = Math.floorDiv(y, CELL) * CELL, cz = Math.floorDiv(z, CELL) * CELL;
        for (StaticDeeps.Deep d : StaticDeeps.near(x, y, z, REACH, seed)) {
            Strata.Bounds b = Strata.bounds(d);
            if (cy + CELL - 1 < b.minY() || cy > b.maxY()) continue;
            double dx = Math.max(0, Math.max(cx - d.x(), d.x() - (cx + CELL))), dz = Math.max(0, Math.max(cz - d.z(), d.z() - (cz + CELL)));
            if (dx * dx + dz * dz > b.radius() * b.radius()) continue;
            if (masked(d, cx, cy, cz)) return d;
        }
        return null;
    }

    /** Is the 4-block cell at (cx, cy, cz) (its low corner) strata: Strata.at not OUT at one of its 8 corner blocks? */
    public static boolean masked(StaticDeeps.Deep d, int cx, int cy, int cz) {
        Strata.Shape shape = Strata.shapeOf(d);
        for (int i = 0; i < 8; i++)
            if (Strata.at(shape, d, cx + (i & 1) * (CELL - 1), cy + (i >> 1 & 1) * (CELL - 1), cz + (i >> 2 & 1) * (CELL - 1)) != Strata.OUT)
                return true;
        return false;
    }

    /** GameTests: boxes (world coordinates) with forced weather, so a test needs no real strata under it. */
    public static final java.util.Map<net.minecraft.world.phys.AABB, Weather> TEST = new java.util.concurrent.ConcurrentHashMap<>();

    /** GameTests: boxes that count as strata of the given deep id. */
    public static final java.util.Map<net.minecraft.world.phys.AABB, Integer> TEST_STRATA = new java.util.concurrent.ConcurrentHashMap<>();

    /** The id of the deep whose strata mask covers block (x, y, z), or -1. */
    public static int strataId(Level level, int x, int y, int z) {
        if (!TEST_STRATA.isEmpty())
            for (var e : TEST_STRATA.entrySet())
                if (e.getKey().contains(x + 0.5, y + 0.5, z + 0.5)) return e.getValue();
        StaticDeeps.Deep d = strataAt(level, x, y, z);
        return d == null ? -1 : d.id();
    }

    /** The strata weather at block (x, y, z) now, or null outside every strata mask. */
    public static Weather at(Level level, int x, int y, int z) {
        if (!TEST.isEmpty())
            for (var e : TEST.entrySet())
                if (e.getKey().contains(x + 0.5, y + 0.5, z + 0.5)) return e.getValue();
        int id = strataId(level, x, y, z);
        if (id < 0) return null;
        return weather(id, Math.floorDiv(x, POCKET), Math.floorDiv(y, POCKET), Math.floorDiv(z, POCKET), Deeps.time(level));
    }
}
