package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Daylight by the field's clock in wild fields (a running tower with a wild resonator: TowerSpawns), for the server's
 * light-dependent rules: monster spawn darkness and undead burning both read LevelReader.getMaxLocalRawBrightness(pos),
 * which LevelMixin routes here (Baughn: #300, mob spawning follows the field's time, for fields with the resonator;
 * λ-Nero: "It should be day/night if the visual is day/night"). Elsewhere, and on the client, it is vanilla's sky
 * darkening. Villagers, bees and other world-clock schedules stay on the world's time.
 */
public final class FieldDaylight {
    private FieldDaylight() {
    }

    /** Vanilla's overworld sky light factor over the day (Timelines: SKY_LIGHT_LEVEL keyframes, linear between). */
    private static final double[] T = {133, 11867, 13670, 22330, 24133};
    private static final double[] F = {1, 1, 4 / 15.0, 4 / 15.0, 1};

    /** Sky darkening (0 day .. 11 night) at this time of day, in clear weather. */
    public static int darkenAt(double t) {
        double tod = LocalTime.wrap(t);
        if (tod < T[0]) tod += LocalTime.DAY;
        double f = 1;
        for (int i = 0; i < T.length - 1; i++)
            if (tod >= T[i] && tod <= T[i + 1]) {
                f = F[i] + (F[i + 1] - F[i]) * (tod - T[i]) / (T[i + 1] - T[i]);
                break;
            }
        return (int) (15.0F - (float) (15 * f));
    }

    /** The sky darkening to use at pos: by its wild field's clock, plus what the weather adds; otherwise the level's. */
    public static int skyDarken(Level level, BlockPos pos) {
        int global = level.getSkyDarken();
        if (level.isClientSide() || !TowerSpawns.anyWild(level) || level.dimensionType().hasFixedTime()
            || !(level instanceof ServerLevel sl) || !sl.getServer().isSameThread()) return global;
        Field f = TowerSpawns.wildAt(sl, pos);
        if (f == null) return global;
        double now = level.getDefaultClockTime();
        int weather = Math.max(0, global - darkenAt(now));
        return Math.min(15, darkenAt(LocalTime.fieldClock(sl, f)) + weather);
    }
}
