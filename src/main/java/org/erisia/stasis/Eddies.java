package org.erisia.stasis;

import net.minecraft.world.level.Level;

/**
 * Eddies (Baughn): in a weak field (store under 30% and draining, i.e. failing > 0.7) and during its collapse, drifting
 * pockets where time stops. They drift with a per-field wind at about 0.7 blocks/s, so a spot is in one for seconds,
 * and an unsuited player caught in one is held, not made a statue (Freezing). They change block, entity and player
 * ticks only: flame light, EA nodes and the tower use the steady rule (Stasis.rateAtSteady), so nothing relights or
 * reconnects as pockets pass. Evaluated per 4-block cell; the shader draws the same noise as mist (stasis.fsh: eddy).
 */
public final class Eddies {
    private Eddies() {
    }

    /** Game time base shared with the shader (StasisPost data[30]): wraps every ~14.5 h of game time. */
    static float time(Level level) {
        return (level.getGameTime() % 1048576L) / 20f;
    }

    public static boolean weak(Field f) {
        return f.collapse() > 0 || f.failing() > 0.7;
    }

    /** Share of the field in pockets: 15% at 30% charge, 45% when empty; 35-60% through a collapse. */
    static float share(Field f) {
        if (f.collapse() > 0) return (float) (0.35 + 0.25 * f.collapse());
        return (float) (0.15 + 0.3 * Math.min(1, (f.failing() - 0.7) / 0.3));
    }

    /** The eddy noise at a point (0..1), drifting with the field's wind. */
    static float value(float x, float y, float z, float t, int seed) {
        float ang = Noise.hash32(seed, 11, 0, 0) * 6.2831853f;
        float vx = (float) Math.cos(ang) * 0.7f, vz = (float) Math.sin(ang) * 0.7f;
        return Noise.fbm32((x - vx * t) / 6f, y / 6f + t * 0.05f, (z - vz * t) / 6f, seed ^ 0x5eed);
    }

    /** Is the block in a stopped pocket of field f (which holds it)? */
    static boolean stopped(Level level, Field f, int x, int y, int z) {
        if (!weak(f)) return false;
        float thr = 0.5f + (0.5f - share(f)) * 0.45f;
        return value((x & ~3) + 2f, (y & ~3) + 2f, (z & ~3) + 2f, time(level), f.seed()) > thr;
    }
}
