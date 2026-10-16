package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Storms (rift stage 3, notes/rifts/STAGE3-PLAN.md). Baughn: about one per in-game "month" (~10 h, config
 * stormEveryHours), 20 minutes each for now (stormMinutes), announced by a violet glow on the upwind horizon
 * (stormWarnMinutes ahead).
 * <p>
 * The schedule: time is cut into blocks of the mean gap, each holding one storm at a hashed start, so gaps vary from
 * ~0 to ~2x the mean, and any time maps to its storm in O(1). A storm's intensity I ramps in and out over 2 minutes.
 * During a storm the drifting weather (deeps, puffs) runs on warped time tau(t) = t + 9 x the integral of I, which
 * has a closed form, so it moves up to 10x faster with no jumps. Strata pockets and crystal growth keep real time.
 * Both sides compute it from the deep seed and synced config; a test override (/stasis storm start|stop) is saved and
 * sent to clients.
 */
public final class Storms {
    private Storms() {
    }

    static final double RAMP = 120;

    /** One storm: its start (s, Deeps' real clock) and length. */
    public record Storm(double start, double length) {
        double end() {
            return start + length;
        }
    }

    static double block() {
        return StasisConfig.stormEveryHours * 3600.0;
    }

    static double length() {
        return StasisConfig.stormMinutes * 60.0;
    }

    /** The scheduled storm of block n. */
    public static Storm scheduled(int seed, long n) {
        double B = block(), D = Math.min(length(), B * 0.5);
        double off = Noise.hash32((int) n, (int) (n >> 32), 61, seed ^ 0x5707) * (B - D);
        return new Storm(n * B + off, D);
    }

    /** The test override (or null): {start, end, tau offset of earlier overrides}; per level on the server, from
     *  the packet on the client. */
    static volatile double[] clientOverride;

    public static double[] clientOverrideForClient() {
        return clientOverride;
    }

    static double[] override(Level level) {
        if (level instanceof ServerLevel sl) {
            Forced o = Forced.get(sl);
            return o.active ? new double[]{o.start, o.end, o.folded} : null;
        }
        return clientOverride;
    }

    // ---- intensity and warped time

    private static float smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return (float) (x * x * (3 - 2 * x));
    }

    /** A storm's intensity 0..1 at time t. */
    static double intensity(Storm s, double t) {
        double u = t - s.start();
        if (u <= 0 || u >= s.length()) return 0;
        double r = Math.min(RAMP, s.length() / 2);
        return smooth(u / r) * smooth((s.length() - u) / r);
    }

    /** The integral of a storm's intensity from its start to t (0 before, all of it after). */
    static double integral(Storm s, double t) {
        double r = Math.min(RAMP, s.length() / 2), D = s.length();
        double u = Math.max(0, Math.min(D, t - s.start()));
        // smoothstep's integral S(x) = x^3 - x^4 / 2 (S(1) = 1/2)
        if (u < r) return r * S(u / r);
        if (u <= D - r) return r * 0.5 + (u - r);
        return r * 0.5 + (D - 2 * r) + r * (0.5 - S((D - u) / r));
    }

    private static double S(double x) {
        return x * x * x - x * x * x * x / 2;
    }

    /** The storm intensity 0..1 at time t (real clock, s): the scheduled storm's or the override's, the stronger. */
    public static double intensity(int seed, double[] override, double t) {
        if (!StasisConfig.storms || seed == 0) return 0;
        long n = (long) Math.floor(t / block());
        double i = intensity(scheduled(seed, n), t);
        if (override != null) i = Math.max(i, intensity(new Storm(override[0], override[1] - override[0]), t));
        return i;
    }

    /** Warped time: t + 9 x the storms' integral so far. */
    public static double tau(int seed, double[] override, double t) {
        if (!StasisConfig.storms || seed == 0) return t;
        double B = block();
        long n = (long) Math.floor(t / B);
        Storm s = scheduled(seed, n);
        double full = integral(s, s.end());
        double sum = Math.max(0, n) * full + integral(s, t);
        double extra = 0;
        if (override != null) {
            Storm o = new Storm(override[0], override[1] - override[0]);
            sum += integral(o, t);
            extra = override[2];
        }
        return t + 9 * sum + extra;
    }

    /** How near the next storm is, 0..1 over the warning (1 at its start, and through it); for the horizon glow. */
    public static double warning(int seed, double[] override, double t) {
        if (!StasisConfig.storms || seed == 0) return 0;
        double W = StasisConfig.stormWarnMinutes * 60.0;
        long n = (long) Math.floor(t / block());
        double best = 0;
        for (long k = n; k <= n + 1; k++) {
            Storm s = scheduled(seed, k);
            if (t >= s.start() && t <= s.end()) return 1;
            if (t < s.start() && s.start() - t < W) best = Math.max(best, 1 - (s.start() - t) / W);
        }
        if (override != null && t >= override[0] - W && t <= override[1]) best = Math.max(best, t >= override[0] ? 1 : 1 - (override[0] - t) / W);
        return best;
    }

    /** The next scheduled storm starting after t (or the current one). */
    public static Storm next(int seed, double t) {
        long n = (long) Math.floor(t / block());
        Storm s = scheduled(seed, n);
        return t <= s.end() ? s : scheduled(seed, n + 1);
    }

    // ---- level-side helpers

    public static double intensity(Level level) {
        return intensity(Deeps.seed(level), override(level), level.getGameTime() / 20.0);
    }

    /** The real clock (Deeps.realTime) warped. */
    public static double tau(Level level, double t) {
        return tau(Deeps.seed(level), override(level), t);
    }

    // ---- the test override

    public static final class Forced extends SavedData {
        static final Codec<Forced> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("active").forGetter(o -> o.active),
            Codec.DOUBLE.fieldOf("start").forGetter(o -> o.start),
            Codec.DOUBLE.fieldOf("end").forGetter(o -> o.end),
            Codec.DOUBLE.optionalFieldOf("folded", 0.0).forGetter(o -> o.folded)
        ).apply(i, Forced::new));
        static final SavedDataType<Forced> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "storm_override"), Forced::new, CODEC);

        boolean active;
        /** the override storm; folded: 9 x the intensity integrals of earlier overrides (tau must never jump back) */
        double start, end, folded;

        public Forced() {
        }

        Forced(boolean active, double start, double end, double folded) {
            this.active = active;
            this.start = start;
            this.end = end;
            this.folded = folded;
        }

        public static Forced get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }
    }

    /** /stasis storm start: a storm from now, the configured length (its warning skipped). */
    public static void start(ServerLevel level) {
        Forced o = Forced.get(level);
        double t = level.getGameTime() / 20.0;
        if (o.active && t < o.end) return;
        // a finished override becomes a fixed offset in tau (it must never jump back)
        if (o.active) o.folded += 9 * integral(new Storm(o.start, o.end - o.start), o.end);
        o.active = true;
        o.start = t;
        o.end = t + length();
        o.setDirty();
        StasisNet.sendStorm(level);
    }

    /** /stasis storm stop: the override storm ramps out from now (it stays counted in tau). */
    public static void stop(ServerLevel level) {
        Forced o = Forced.get(level);
        double t = level.getGameTime() / 20.0;
        if (!o.active || t >= o.end - RAMP) return;
        // ending at the start of a ramp-out keeps the integral so far as it was (no jump in tau)
        o.end = Math.max(t, o.start + RAMP) + RAMP;
        o.setDirty();
        StasisNet.sendStorm(level);
    }
}
