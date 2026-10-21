package org.erisia.stasis;

import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Lulls (Baughn 2026-10-21): rare running-time weather, the deeps' opposite and the respawn's last resort
 * (SafeRespawn). A lull is a round column where time runs: 1.0 at its core, fading to 0.4 at its edge, nothing past it.
 * They drift on the deeps' wind at a tenth of its speed (about 0.1 blocks/s) and are a sixteenth as common (a 1024-block
 * grid where the deeps' is 256, 80 % occupied: about one within 1.5 km). Radius 24-40. They never fade: each one only
 * drifts. A full column, top to bottom (caves under one run too; no heightmap read, so any thread may ask).
 * <p>
 * Effects: Stasis.rateAt takes the faster of a lull and the fields there (a rift still stops); so everything running
 * time gives applies (no freezing, no fauna, machines run, the colour comes back: StasisPost draws a lull as a column
 * field without a shell). Both sides compute the same lulls from the level's deep seed.
 */
public final class Lulls {
    private Lulls() {
    }

    static final float CELL = 1024f, OCCUPANCY = 0.8f;
    /** The core runs at 1, the edge at EDGE_RATE; the core is the inner CORE share of the radius. */
    public static final double EDGE_RATE = 0.4, CORE = 0.3;

    public record Lull(float x, float z, float radius, int id) {
        /** The rate at (x, z): 1 in the core, fading to EDGE_RATE at the edge, 0 outside. */
        public double rate(double px, double pz) {
            double d = Math.hypot(px - x, pz - z);
            if (d > radius) return 0;
            double u = Math.max(0, (d / radius - CORE) / (1 - CORE));
            return 1 - (1 - EDGE_RATE) * u;
        }

        /** Does the column hold the whole block (x, z) (its far corner inside, as Field.containsBlock)? */
        public boolean holds(int bx, int bz) {
            double fx = Math.max(Math.abs(bx - x), Math.abs(bx + 1 - x)), fz = Math.max(Math.abs(bz - z), Math.abs(bz + 1 - z));
            return fx * fx + fz * fz <= radius * radius;
        }
    }

    /** The lulls' clock: the deeps' (storms included) at a tenth of the speed. */
    public static double time(Level level) {
        return Deeps.time(level) / 10.0;
    }

    /** Lull of grid cell (i, k) at time t (lull clock), or null. */
    static Lull cell(int i, int k, double t, int seed, double[] drift) {
        if (Noise.hash32(i, k, 41, seed ^ 0x1A11) >= OCCUPANCY) return null;
        float ox = 128 + 768 * Noise.hash32(i, k, 42, seed ^ 0x2A11), oz = 128 + 768 * Noise.hash32(i, k, 43, seed ^ 0x3A11);
        float r = 24 + 16 * Noise.hash32(i, k, 44, seed ^ 0x4A11);
        // its own wander along the wind: up to ±48 blocks, back and forth over hours (so lulls don't move in lockstep)
        float own = Noise.hash32(i, k, 45, seed ^ 0x5A11) * 2 - 1;
        float ang = Deeps.windAngle(seed);
        double ownAlong = 48 * own * Math.sin(t / 2000.0 + Noise.hash32(i, k, 47, seed ^ 0x7A11) * 6.2831853);
        return new Lull((float) (i * (double) CELL + ox + drift[0] + ownAlong * Math.cos(ang)),
            (float) (k * (double) CELL + oz + drift[1] + ownAlong * Math.sin(ang)), r, Noise.hashInt(i, k, 46, seed ^ 0x6A11) & 0xFFFFFF);
    }

    /** The lulls whose centres may lie within {@code range} of (x, z) (each axis), at lull time t. */
    public static List<Lull> within(double x, double z, double range, double t, int seed) {
        List<Lull> out = new ArrayList<>(4);
        if (seed == 0 && TEST.isEmpty()) return out;
        out.addAll(TEST);
        if (seed == 0) return out;
        double[] d = Deeps.drift(t, seed);
        double qx = x - d[0], qz = z - d[1];
        int x0 = (int) Math.floor((qx - range - 896 - 64) / CELL), x1 = (int) Math.floor((qx + range - 128 + 64) / CELL);
        int z0 = (int) Math.floor((qz - range - 896 - 64) / CELL), z1 = (int) Math.floor((qz + range - 128 + 64) / CELL);
        for (int i = x0; i <= x1; i++)
            for (int k = z0; k <= z1; k++) {
                Lull l = cell(i, k, t, seed, d);
                if (l != null && Math.abs(l.x() - x) <= range + l.radius() && Math.abs(l.z() - z) <= range + l.radius()) out.add(l);
            }
        return out;
    }

    /** GameTests: lulls that are there whatever the seed. */
    public static final List<Lull> TEST = new java.util.concurrent.CopyOnWriteArrayList<>();

    // ---- per 256-block region, refreshed each second (a lull moves ~0.1 blocks in that): rateAt asks per block

    static final int REGION = 256;

    private record Snapshot(long second, int seed, java.util.concurrent.ConcurrentHashMap<Long, Lull[]> regions) {
    }

    private static final java.util.Map<Level, Snapshot> SNAPSHOTS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static Lull[] region(Level level, int seed, int rx, int rz) {
        long second = level.getGameTime() / 20;
        Snapshot s = SNAPSHOTS.get(level);
        if (s == null || s.second() != second || s.seed() != seed) {
            s = new Snapshot(second, seed, new java.util.concurrent.ConcurrentHashMap<>());
            SNAPSHOTS.put(level, s);
        }
        double t = time(level);
        return s.regions().computeIfAbsent(net.minecraft.world.level.ChunkPos.pack(rx, rz), k -> {
            double cx = rx * (double) REGION + REGION / 2.0, cz = rz * (double) REGION + REGION / 2.0;
            return within(cx, cz, REGION / 2.0, t, seed).toArray(new Lull[0]);
        });
    }

    /** The lulls' rate at a block column: the fastest lull holding the whole block, or 0. */
    public static double rateAt(Level level, int x, int z) {
        int seed = Deeps.seed(level);
        if (seed == 0 && TEST.isEmpty()) return 0;
        double best = 0;
        for (Lull l : region(level, seed, Math.floorDiv(x, REGION), Math.floorDiv(z, REGION)))
            if (l.holds(x, z)) best = Math.max(best, l.rate(x + 0.5, z + 0.5));
        return best;
    }

    /** The nearest lull to (x, z) within range, now, or null (SafeRespawn's last resort; /stasis lulls). */
    public static Lull nearest(Level level, double x, double z, double range) {
        Lull best = null;
        double bd = Double.MAX_VALUE;
        for (Lull l : within(x, z, range, time(level), Deeps.seed(level))) {
            double d = Math.hypot(l.x() - x, l.z() - z);
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }
}
