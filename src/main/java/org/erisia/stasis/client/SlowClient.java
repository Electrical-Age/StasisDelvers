package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;

/**
 * The client in a slow field (PlayerRates sends it the field's tick rate): how many interpolation steps other entities
 * take per client tick so they keep the server's real speed. 1 at full speed; at 12 TPS alternately 1 and 2 (20 / 12
 * on average). Updated once per client tick (StasisClient).
 */
public final class SlowClient {
    private SlowClient() {
    }

    private static double acc;
    private static volatile int steps = 1;

    public static int steps() {
        return steps;
    }

    static void tick() {
        var level = Minecraft.getInstance().level;
        float rate = level == null ? 20f : level.tickRateManager().tickrate();
        if (rate >= 19.99f || level.tickRateManager().isFrozen()) {
            acc = 0;
            steps = 1;
            return;
        }
        acc += 20.0 / Math.max(1f, rate);
        int n = (int) Math.floor(acc);
        acc -= n;
        steps = Math.max(1, n);
    }
}
