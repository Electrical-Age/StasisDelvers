package org.erisia.stasis;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Deeps (Baughn, design v3): where the stasis is stronger than in the ordinary Grey. This is the drifting kind,
 * surface weather: about 1.4 within a 192-block radius anywhere (a 256-block grid, 80% occupied), radius 20-42,
 * breathing, carried by a per-world wind of 0.65-1.35 blocks/s (plus ±0.12 per deep) that bends over hours: about a
 * minute to pass a spot on average, 3 at worst (Baughn), each one forming and fading
 * over a 20-60 minute cycle (and re-forming somewhere else). Vertically from 6 below the local surface to 64 above
 * it, so caves under one are safe.
 * <p>
 * Effects (outside fields; a field holds against a deep, as "weather"): suits drain twice as fast; a Delver suit
 * isn't fully protective (freeze builds at a quarter rate, and a full freeze holds the player about a minute, no
 * statue); a tower whose field a deep crosses draws up to +50% and its shield brightens there; the post shader
 * darkens and drains the colour further and adds mist. Both sides compute the same deeps from the level's deep seed
 * (FieldData, synced with the fields).
 */
public final class Deeps {
    private Deeps() {
    }

    static final float CELL = 256f, OCCUPANCY = 0.8f;

    /** A drifting deep now: centre (x, z), radius (with breathing and its form/fade envelope applied), strength 0..1. */
    public record Deep(float x, float z, float radius, float strength, int id) {
    }

    /**
     * The deeps' clock (s): game time / 20, never wrapping (Eddies.time wraps every ~14.5 h, and every deep jumped
     * then). Double: after weeks a float can't hold the drift to a fraction of a block.
     */
    public static double time(Level level) {
        return level.getGameTime() / 20.0;
    }

    /**
     * How far the wind has carried everything by time t (seconds, Deeps.time). Baughn: a deep takes about a
     * minute to pass on average, 3 at worst, with size and speed varying: the wind runs 0.65-1.35 blocks/s over a
     * 3-hour swell (plus a slow bend sideways), and each deep adds its own ±0.12 (cell()).
     */
    static double[] drift(double t, int seed) {
        float ang = windAngle(seed);
        double along = 1.0 * t + 3780 * Math.sin(t / 10800.0);   // speed 1 + 0.35 cos(t / 3 h)
        return new double[]{
            along * Math.cos(ang) + 600 * Math.sin(t / 5400.0),
            along * Math.sin(ang) + 600 * Math.cos(t / 7200.0)};
    }

    static float windAngle(int seed) {
        return Noise.hash32(seed, 21, 0, 0) * 6.2831853f;
    }

    /** The drifting deeps whose grid cells are around (x, z) (5 x 5: deeps stray up to ~430 blocks), present now. */
    public static List<Deep> near(float x, float z, double t, int seed) {
        List<Deep> out = new ArrayList<>(4);
        double[] d = drift(t, seed);
        double qx = x - d[0], qz = z - d[1];
        int cx = (int) Math.floor(qx / CELL), cz = (int) Math.floor(qz / CELL);
        for (int i = cx - 2; i <= cx + 2; i++)
            for (int k = cz - 2; k <= cz + 2; k++) {
                Deep deep = cell(i, k, t, seed, d);
                if (deep != null) out.add(deep);
            }
        puffs(x, z, 0, t, seed, d, out);
        return out;
    }

    /**
     * The drifting deeps whose centres may lie within {@code range} blocks of (x, z) (x and z separately), present
     * now: the cells a deep can come from (offset 32-224 in its cell, plus up to ~432 blocks of its own drift). For the
     * client's render distance (StasisPost); near() is the point query.
     */
    public static List<Deep> within(float x, float z, float range, double t, int seed) {
        List<Deep> out = new ArrayList<>(16);
        double[] d = drift(t, seed);
        double qx = x - d[0], qz = z - d[1];
        int x0 = (int) Math.floor((qx - range - 224 - 440) / CELL), x1 = (int) Math.floor((qx + range - 32 + 440) / CELL);
        int z0 = (int) Math.floor((qz - range - 224 - 440) / CELL), z1 = (int) Math.floor((qz + range - 32 + 440) / CELL);
        for (int i = x0; i <= x1; i++)
            for (int k = z0; k <= z1; k++) {
                Deep deep = cell(i, k, t, seed, d);
                if (deep != null) out.add(deep);
            }
        puffs(x, z, range, t, seed, d, out);
        return out;
    }

    // ---- upwellings (rift stage 2): every rift breathes out a puff of deep every 60-120 s from its hole. A puff is a
    // drifting deep: carried off on the wind from where it left, growing from 6 to 24 blocks across 3-5 minutes, fading
    // as it goes. Gameplay mist like the others (at(), towers).

    static final float PUFF_REACH = 480;   // a puff's farthest drift (5 min at up to 1.35 b/s) plus its radius

    /** The puffs from the rifts within range (+ PUFF_REACH) of (x, z), alive now; added to near() and within(). */
    static void puffs(float x, float z, float range, double t, int seed, double[] drift, List<Deep> out) {
        for (StaticDeeps.Deep sd : StaticDeeps.near(x, 100, z, range + PUFF_REACH, seed)) {
            if (sd.y() < -20) continue;   // too deep to reach the surface
            float interval = 60 + 60 * Noise.hash32(sd.id(), 0, 41, seed);
            float phase = Noise.hash32(sd.id(), 0, 42, seed) * interval;
            long now = (long) Math.floor((t + phase) / interval);
            for (long j = now; j >= now - 6; j--) {
                double age = t + phase - j * (double) interval;
                int jj = (int) j;
                float life = 180 + 120 * Noise.hash32(sd.id(), jj, 43, seed);
                if (age >= life) break;
                if (Noise.hash32(sd.id(), jj, 44, seed) < 0.15f) continue;   // the rift skips a breath now and then
                double[] then = drift(t - age, seed);
                float u = (float) (age / life);
                float strength = Math.min(1f, (float) age / 20f) * (1 - u * u);
                float ox = (Noise.hash32(sd.id(), jj, 45, seed) - 0.5f) * 0.6f * sd.radius(), oz = (Noise.hash32(sd.id(), jj, 46, seed) - 0.5f) * 0.6f * sd.radius();
                out.add(new Deep((float) (sd.x() + ox + drift[0] - then[0]), (float) (sd.z() + oz + drift[1] - then[1]),
                    (float) (6 + 18 * Math.pow(u, 0.6)), strength, puffId(sd, jj)));
            }
        }
    }

    /** A puff's id (24 bits, like a drifting deep's): its rift's and its number's. */
    public static int puffId(StaticDeeps.Deep sd, int j) {
        return (int) ((sd.id() * 31L + j * 2654435761L) & 0xFFFFFF);
    }

    static Deep cell(int i, int k, double t, int seed, double[] drift) {
        // its own life cycle: 20-60 min, phase-shifted per cell; each cycle is a new deep (occupancy, place, size)
        float period = 1200f + 2400f * Noise.hash32(i, k, 31, seed);
        float phase = Noise.hash32(i, k, 32, seed) * period;
        long cyc = (long) Math.floor((t + phase) / period);
        int cycle = (int) cyc;
        float u = (float) ((t + phase) / period - cyc);
        if (Noise.hash32(i, k, cycle, seed ^ 0x1D33) >= OCCUPANCY) return null;
        float env = smooth(u / 0.15f) * smooth((1 - u) / 0.15f);
        if (env <= 0.01f) return null;
        float ox = 32 + 192 * Noise.hash32(i, k, cycle, seed ^ 0x2D33), oz = 32 + 192 * Noise.hash32(i, k, cycle, seed ^ 0x3D33);
        // radius 20-42, breathing ±15%: the widest (~97 across) at the slowest (0.53 b/s) passes in ~3 min
        float r0 = 20 + 22 * Noise.hash32(i, k, cycle, seed ^ 0x4D33);
        float breathe = 1 + 0.15f * (float) Math.sin(t / 150.0 + Noise.hash32(i, k, cycle, seed ^ 0x5D33) * 6.2831853);
        // its own speed: ±0.12 blocks/s along the wind, over its cycle (at most ~430 blocks: near() looks 2 cells out)
        float own = (Noise.hash32(i, k, cycle, seed ^ 0x6D33) * 2 - 1) * 0.12f * (float) (t + phase - cyc * (double) period);
        float ang = windAngle(seed);
        return new Deep((float) (i * (double) CELL + ox + drift[0] + own * Math.cos(ang)), (float) (k * (double) CELL + oz + drift[1] + own * Math.sin(ang)),
            r0 * breathe * (0.4f + 0.6f * env), env,
            ((i * 73856093) ^ (k * 19349663) ^ cycle) & 0xFFFFFF);
    }

    private static float smooth(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** Horizontal intensity 0..1 of the drifting deeps at (x, z): soft over the last 4 blocks, with a ragged edge. */
    public static float horizontal(float x, float z, double t, int seed) {
        float best = 0;
        for (Deep d : near(x, z, t, seed)) {
            float dx = x - d.x(), dz = z - d.z();
            float dist = (float) Math.sqrt(dx * dx + dz * dz) + 3 * (Noise.value32(x / 7f, 0.5f, z / 7f, d.id()) - 0.5f) * 2;
            float in = smooth((d.radius() - dist) / 4f) * Math.min(1, d.strength() * 1.5f);
            best = Math.max(best, in);
        }
        return best;
    }

    /** Is (x, y) within the drifting deeps' height band at this column (surface - 6 .. surface + 64)? */
    static boolean inBand(Level level, int x, int y, int z) {
        if (!level.hasChunkAt(new net.minecraft.core.BlockPos(x, 0, z))) return false;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return y >= surface - 6 && y <= surface + 64;
    }

    /** Deep intensity 0..1 at a block (drifting deeps; 0 in dimensions where time doesn't stop). */
    public static float at(Level level, int x, int y, int z) {
        if (!Stasis.stops(level)) return 0;
        int seed = seed(level);
        if (seed == 0) return 0;
        float h = horizontal(x + 0.5f, z + 0.5f, time(level), seed);
        return h > 0 && inBand(level, x, y, z) ? h : 0;
    }

    /** GameTests run without deeps (their spots must be ordinary Grey), except tests that switch them on. */
    public static volatile boolean testDeeps;

    public static int seed(Level level) {
        if (StasisConfig.NO_SPAWN_AREA && !testDeeps) return 0;
        if (level instanceof net.minecraft.server.level.ServerLevel sl)
            return sl.getServer().isSameThread() ? FieldData.get(sl).deepSeed() : FieldData.peekDeepSeed(sl);   // light, worldgen threads
        return ClientFields.deepSeed();
    }

    /**
     * Share of a field's volume in a drifting deep (27 samples: the centre and 26 directions at 0.7 r), for the
     * tower's draw (weather) and its lightning.
     */
    public static float overlap(Level level, Field f) {
        int in = 0, n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double k = l == 0 ? 0 : 0.7 * f.radius() / l;
                    int x = (int) Math.floor(f.x() + dx * k), y = (int) Math.floor(f.y() + dy * k), z = (int) Math.floor(f.z() + dz * k);
                    n++;
                    if (at(level, x, y, z) > 0.5f) in++;
                }
        return in / (float) n;
    }
}
