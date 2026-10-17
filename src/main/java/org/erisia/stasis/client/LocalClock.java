package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.erisia.stasis.LocalTime;
import org.erisia.stasis.Stasis;

/** The overworld clock as the client draws it: the local time where the player is (the grey's or a field's, day count included). */
public final class LocalClock {
    private LocalClock() {
    }

    /** The local absolute time (ticks + fraction), or NaN to use the world's. */
    public static double local(long totalTicks, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.level.dimension() != Level.OVERWORLD || !Stasis.stops(mc.level)) return Double.NaN;
        var p = mc.player.getEyePosition(partial);
        return LocalTime.Client.time(mc.level, p.x, p.y, p.z, partial, totalTicks + (double) partial);
    }
}
