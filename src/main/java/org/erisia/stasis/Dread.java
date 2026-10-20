package org.erisia.stasis;

import net.minecraft.world.level.Level;

/**
 * How strongly a place calls rift fauna, 0..1 (notes/fauna/PLAN.md; Baughn: they come near weather, rifts and storms, and
 * the world stays quiet elsewhere). It sets both whether fauna spawn there and how fast they move (λ-Nero: faster the
 * more time is stopped, at most 2x). 0 wherever time runs: fields, the spawn area, running spread land (a drifting
 * deep over running land stops it, and counts).
 */
public final class Dread {
    private Dread() {
    }

    /** Strata pocket states: CLEAR still lies under a rift. */
    static final float POCKET_CLEAR = 0.15f, POCKET_MIST = 0.35f, POCKET_VIOLET = 0.7f, POCKET_RIFT = 1f;
    /** Static deeps: the outer zone and the rift. */
    static final float OUTER = 0.5f, RIFT = 1f;
    /** Drifting deeps: base + share x intensity. */
    static final float DEEP_BASE = 0.2f, DEEP_SHARE = 0.3f;
    /** Storms, times the storm's intensity. */
    static final float STORM_MIST = 0.6f, STORM_RIFT = 0.9f;

    public static float at(Level level, int x, int y, int z) {
        if (!Stasis.stops(level) || Stasis.rateAt(level, x, y, z) > 0) return 0;
        float d = switch (StaticDeeps.zone(level, x, y, z)) {
            case 2 -> RIFT;
            case 1 -> OUTER;
            default -> 0;
        };
        var w = Pockets.at(level, x, y, z);
        if (w != null) d = Math.max(d, switch (w.state()) {
            case CLEAR -> POCKET_CLEAR;
            case MIST -> POCKET_MIST;
            case VIOLET -> POCKET_VIOLET;
            case RIFT -> POCKET_RIFT;
        });
        float deep = Deeps.at(level, x, y, z);
        if (deep > 0) d = Math.max(d, DEEP_BASE + DEEP_SHARE * Math.min(deep, 1f));
        int storm = Storms.at(level, x, y, z);
        if (storm > 0) {
            float i = (float) Math.min(1.0, Math.max(Storms.intensity(level), Storms.TEST.isEmpty() ? 0 : 1));
            d = Math.max(d, (storm == 2 ? STORM_RIFT : STORM_MIST) * i);
        }
        return Math.min(d, 1f);
    }

    /** Is a storm on over this place (the fauna caps and cooldowns are looser then)? */
    public static boolean storm(Level level, int x, int y, int z) {
        return Stasis.stops(level) && Storms.at(level, x, y, z) > 0;
    }
}
