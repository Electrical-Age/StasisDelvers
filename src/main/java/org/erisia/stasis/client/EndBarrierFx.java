package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.erisia.stasis.EndBarrier;

/**
 * The End's wall (EndBarrier; λ-Nero: "a barrier that looks like a time field with black lines") drawn near the player
 * as particles on the cylinder: a pale shimmer over its face and a grid of black lines (every 4 blocks round, every 6 up),
 * only where the player is within 20 blocks of it
 * (vanilla drops particles more than 32 blocks from the camera). The field shield's look lives in the post shader, which is off in a
 * running End (StasisPost runs where Stasis.stops), so this is a modest stand-in: AdventurAgent to check by eye.
 */
public final class EndBarrierFx {
    private EndBarrierFx() {
    }

    static final double NEAR = 20, SPAN = 20;
    private static final DustParticleOptions SHIMMER = new DustParticleOptions(0xC8E6FF, 1.6f);
    private static final DustParticleOptions LINE = new DustParticleOptions(0x000000, 1.1f);
    private static final RandomSource RND = RandomSource.create();

    static void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused() || !EndBarrier.active(mc.level)) return;
        double r = EndBarrier.radius(), px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        double d = Math.sqrt(px * px + pz * pz);
        if (Math.abs(d - r) > NEAR) return;
        double a0 = Math.atan2(pz, px), span = SPAN / r;
        // the shimmer over the face
        for (int i = 0; i < 24; i++) {
            double a = a0 + (RND.nextDouble() * 2 - 1) * span, y = py - 6 + RND.nextDouble() * 18;
            mc.level.addParticle(SHIMMER, Math.cos(a) * r, y, Math.sin(a) * r, 0, 0, 0);
        }
        // the black lines: vertical every 4 blocks of arc, horizontal every 6 blocks of height
        double step = 4 / r;
        long k0 = (long) Math.ceil((a0 - span) / step), k1 = (long) Math.floor((a0 + span) / step);
        for (long k = k0; k <= k1; k++) {
            double a = k * step, y = py - 6 + RND.nextDouble() * 18;
            mc.level.addParticle(LINE, Math.cos(a) * r, y, Math.sin(a) * r, 0, 0, 0);
        }
        for (int j = (int) Math.ceil((py - 6) / 6); j <= (int) Math.floor((py + 12) / 6); j++)
            for (int i = 0; i < 3; i++) {
                double a = a0 + (RND.nextDouble() * 2 - 1) * span;
                mc.level.addParticle(LINE, Math.cos(a) * r, j * 6.0, Math.sin(a) * r, 0, 0, 0);
            }
    }
}
