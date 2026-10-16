package org.erisia.stasis;

import net.minecraft.world.level.Level;

/**
 * Strata weather (rift stage 2, notes/rifts/STAGE2-PLAN.md): the strata under each rift is split into pockets,
 * world-aligned 12-block cubes, each with its own weather: a stochastic process (Baughn) made of epochs of 10-30
 * minutes, each with its own random number of rift spells (0-2, each after a 20 s violet warning), their lengths and
 * places, and its clear spells. Long-run shares: clear 25%, rift ~15%, mist the rest. Players can't learn a timetable. Both sides compute it from the deep seed and Deeps.time.
 * <p>
 * Where it applies: the strata mask, 4-block cells that touch the strata (Strata.at is a cavity or vent at one of the
 * cell's 8 corners on the 4-block lattice). Rules and visuals use the same mask, so what you see is what hits you.
 */
public final class Pockets {
    private Pockets() {
    }

    public static final int POCKET = 12, CELL = 4;
    /** Pocket epoch lengths (s); the shader gets t mod P and floor(t / P) for each. */
    static final int[] PERIODS = {600, 900, 1200, 1800};
    public static final double CLEAR = 0.25, VIOLET = 20, RAMP = 30;   // CLEAR: the mean clear share

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

    /**
     * One epoch of a pocket (P seconds, from its start): clear for c1, then mist, then k rift spells (0-2; each a
     * 20 s violet warning, the rift, then mist again), and clear for the last c2. Everything is hashed per epoch:
     * how many spells, how long, where the mist splits, how long the clear spells are. Long-run shares: clear 25%,
     * rift ~15%, the rest mist and violet. Each epoch starts and ends clear, so epochs join seamlessly.
     */
    record Epoch(double c1, double c2, double[] start, double[] end) {
        /** Spell i: violet from start[i], rift from start[i] + VIOLET, mist again from end[i]. */
    }

    static Epoch epoch(int id, int px, int py, int pz, long n, int P) {
        int salt = (int) (n * 0x9E3779B1L) ^ id;
        float hk = Noise.hash32(px, py, pz, salt ^ 0x90C3);
        int k = hk < 0.25f ? 0 : hk < 0.70f ? 1 : 2;
        double clear = P * (0.15 + 0.20 * Noise.hash32(px, py, pz, salt ^ 0x90C4));
        double c1 = clear * Noise.hash32(px, py, pz, salt ^ 0x90C5), c2 = clear - c1;
        double[] rift = new double[k], w = new double[k + 1];
        double mist = P - clear, wsum = 0;
        for (int i = 0; i < k; i++) {
            rift[i] = P * (0.07 + 0.14 * Noise.hash32(px, py, pz, salt ^ (0x90D0 + i)));
            mist -= VIOLET + rift[i];
        }
        for (int i = 0; i <= k; i++) wsum += w[i] = 0.5 + Noise.hash32(px, py, pz, salt ^ (0x90E0 + i));
        double[] start = new double[k], end = new double[k];
        double u = c1 + mist * w[0] / wsum;
        for (int i = 0; i < k; i++) {
            start[i] = u;
            end[i] = u + VIOLET + rift[i];
            u = end[i] + mist * w[i + 1] / wsum;
        }
        return new Epoch(c1, c2, start, end);
    }

    /** The weather of pocket (px, py, pz) of the strata of deep id at time t (s, Deeps.time). */
    public static Weather weather(int id, int px, int py, int pz, double t) {
        int P = period(id, px, py, pz);
        double s = t + phase(id, px, py, pz, P);
        long n = (long) Math.floor(s / P);
        double u = s - (double) n * P;
        Epoch ep = epoch(id, px, py, pz, n, P);
        if (u < ep.c1) return new Weather(State.CLEAR, 0, ep.c1 - u);
        if (u >= P - ep.c2) {
            // clear until the next epoch's mist
            double next = P - u + epoch(id, px, py, pz, n + 1, P).c1;
            return new Weather(State.CLEAR, 0, next);
        }
        double mistEnd = P - ep.c2;
        for (int i = 0; i < ep.start.length; i++) {
            if (u < ep.start[i]) return new Weather(State.MIST, mist(u, ep, P), ep.start[i] - u);
            if (u < ep.start[i] + VIOLET) {
                float k = (float) ((u - ep.start[i]) / VIOLET);
                return new Weather(State.VIOLET, 0.6f + 0.4f * k * k, ep.start[i] + VIOLET - u);
            }
            if (u < ep.end[i]) return new Weather(State.RIFT, 1, ep.end[i] - u);
        }
        return new Weather(State.MIST, mist(u, ep, P), mistEnd - u);
    }

    /** Mist density: 0.6, ramping over RAMP s from the clear spells at either end of the epoch's weather. */
    private static float mist(double u, Epoch ep, int P) {
        double k = Math.min(1, Math.min(u - ep.c1, P - ep.c2 - u) / RAMP);
        return (float) (0.6 * k * k * (3 - 2 * k));
    }

    /**
     * Seconds of weather (mist, violet or rift: crystals grow) in pocket (px, py, pz) between times t0 and t1. Long
     * gaps (over 4096 epochs, weeks) count the middle epochs at the mean share.
     */
    public static double exposure(int id, int px, int py, int pz, double t0, double t1) {
        if (t1 <= t0) return 0;
        int P = period(id, px, py, pz);
        double ph = phase(id, px, py, pz, P), s0 = t0 + ph, s1 = t1 + ph;
        long n0 = (long) Math.floor(s0 / P), n1 = (long) Math.floor(s1 / P);
        double u0 = s0 - (double) n0 * P, u1 = s1 - (double) n1 * P;
        if (n0 == n1) return grey(id, px, py, pz, n0, P, u1) - grey(id, px, py, pz, n0, P, u0);
        double sum = grey(id, px, py, pz, n0, P, P) - grey(id, px, py, pz, n0, P, u0) + grey(id, px, py, pz, n1, P, u1);
        long mid = n1 - n0 - 1;
        if (mid > 4096) return sum + mid * (1 - CLEAR) * P;
        for (long n = n0 + 1; n < n1; n++) sum += grey(id, px, py, pz, n, P, P);
        return sum;
    }

    /** Weather seconds in epoch n before epoch time u: the weather is one stretch, [c1, P - c2). */
    private static double grey(int id, int px, int py, int pz, long n, int P, double u) {
        Epoch ep = epoch(id, px, py, pz, n, P);
        return Math.max(0, Math.min(u, P - ep.c2) - ep.c1);
    }

    // ---- the strata mask

    /** How far any strata reaches from its deep's centre (bounds radius 2.3R+4 at R=64, roots, vents to R+80). */
    public static final float REACH = 200;

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

    /** Strata open space (cavities and vents), where weather happens; not the rock, nor the spires in the open air
     *  (their pockets floated as black boxes beside them: AdventurAgent). */
    public static boolean open(int strataClass) {
        return strataClass == Strata.CAVITY || strataClass == Strata.VENT;
    }

    /** Is the 4-block cell at (cx, cy, cz) (its low corner) strata: Strata.at not OUT at one of its 8 corners, the
     *  points of the 4-block lattice (the client's StrataMask evaluates each lattice point once)? */
    public static boolean masked(StaticDeeps.Deep d, int cx, int cy, int cz) {
        Strata.Shape shape = Strata.shapeOf(d);
        for (int i = 0; i < 8; i++)
            if (open(Strata.at(shape, d, cx + (i & 1) * CELL, cy + (i >> 1 & 1) * CELL, cz + (i >> 2 & 1) * CELL)))
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
