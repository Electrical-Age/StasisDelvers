package org.erisia.stasis;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Deeps (Baughn, design v3): where the stasis is stronger than in the ordinary Grey. This is the drifting kind,
 * surface weather: about 1.4 within a 192-block radius anywhere (a 256-block grid, 80% occupied), radius 20-48,
 * breathing, carried by a per-world wind of about 2 blocks/s (the biggest crosses a spot in a minute) that bends over hours, each one forming and fading
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

    /** How far the wind has carried everything by time t (seconds, Eddies.time base). */
    static float[] drift(float t, int seed) {
        float ang = Noise.hash32(seed, 21, 0, 0) * 6.2831853f;
        float s = 2.0f;   // Baughn: the biggest deep (~115 blocks across) crosses a player in about a minute
        return new float[]{
            (float) (s * t * Math.cos(ang) + 600 * Math.sin(t / 5400.0)),
            (float) (s * t * Math.sin(ang) + 600 * Math.cos(t / 7200.0))};
    }

    /** The drifting deeps whose grid cells are around (x, z): at most 9, only those present now. */
    public static List<Deep> near(float x, float z, float t, int seed) {
        List<Deep> out = new ArrayList<>(4);
        float[] d = drift(t, seed);
        float qx = x - d[0], qz = z - d[1];
        int cx = (int) Math.floor(qx / CELL), cz = (int) Math.floor(qz / CELL);
        for (int i = cx - 1; i <= cx + 1; i++)
            for (int k = cz - 1; k <= cz + 1; k++) {
                Deep deep = cell(i, k, t, seed, d);
                if (deep != null) out.add(deep);
            }
        return out;
    }

    static Deep cell(int i, int k, float t, int seed, float[] drift) {
        // its own life cycle: 20-60 min, phase-shifted per cell; each cycle is a new deep (occupancy, place, size)
        float period = 1200f + 2400f * Noise.hash32(i, k, 31, seed);
        float phase = Noise.hash32(i, k, 32, seed) * period;
        int cycle = (int) Math.floor((t + phase) / period);
        float u = (t + phase) / period - cycle;
        if (Noise.hash32(i, k, cycle, seed ^ 0x1D33) >= OCCUPANCY) return null;
        float env = smooth(u / 0.15f) * smooth((1 - u) / 0.15f);
        if (env <= 0.01f) return null;
        float ox = 32 + 192 * Noise.hash32(i, k, cycle, seed ^ 0x2D33), oz = 32 + 192 * Noise.hash32(i, k, cycle, seed ^ 0x3D33);
        float r0 = 20 + 28 * Noise.hash32(i, k, cycle, seed ^ 0x4D33);
        float breathe = 1 + 0.2f * (float) Math.sin(t / 150.0 + Noise.hash32(i, k, cycle, seed ^ 0x5D33) * 6.2831853);
        return new Deep(i * CELL + ox + drift[0], k * CELL + oz + drift[1], r0 * breathe * (0.4f + 0.6f * env), env,
            ((i * 73856093) ^ (k * 19349663) ^ cycle) & 0xFFFFFF);
    }

    private static float smooth(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** Horizontal intensity 0..1 of the drifting deeps at (x, z): soft over the last 4 blocks, with a ragged edge. */
    public static float horizontal(float x, float z, float t, int seed) {
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
        float h = horizontal(x + 0.5f, z + 0.5f, Eddies.time(level), seed);
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
