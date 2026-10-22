package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.erisia.stasis.ClientFields;
import org.erisia.stasis.Deeps;
import org.erisia.stasis.Stasis;
import org.erisia.stasis.StaticDeeps;

/**
 * Deeps seen and heard from outside (Baughn 2026-10-22: Jared rode his horse into a rift; the mist alone is too
 * subtle for many, and there should be a sound outside any kind of deep):
 * <ul>
 *   <li>the border: violet motes rising along the edge of every deep near the player, drifting and static, where it
 *       meets the ground (a rift's sphere where it cuts the surface);</li>
 *   <li>a low, whispering drone from the nearest edge, louder as you come within ~48 blocks of it, from outside and
 *       inside alike, so you hear one before you're in it.</li>
 * </ul>
 * Client only, from the same seed and clock as everything else.
 */
public final class DeepWarning {
    private DeepWarning() {
    }

    static final double HEAR = 48, SEE = 72;
    static Drone drone;
    static float target;
    static double ex, ey, ez;

    /** A deep's ground ring near the player: centre, ring radius at the surface. */
    record Ring(double x, double z, double r, boolean rift) {
    }

    static void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.isPaused()) return;
        int seed = ClientFields.deepSeed();
        target = 0;
        if (seed == 0 || !Stasis.stops(mc.level)) {
            fade();
            return;
        }
        double t = Double.isNaN(StasisPost.smoothClock) ? Deeps.time(mc.level) : StasisPost.smoothClock;
        var p = mc.player.position();
        java.util.List<Ring> rings = new java.util.ArrayList<>();
        for (Deeps.Deep d : Deeps.calmed(mc.level, Deeps.within((float) p.x, (float) p.z, (float) SEE, t, seed)))
            if (d.strength() > 0.2f) rings.add(new Ring(d.x(), d.z(), d.radius(), false));
        for (StaticDeeps.Deep d : StaticDeeps.near((float) p.x, (float) p.y, (float) p.z, (float) SEE, seed)) {
            float r = StaticDeeps.effectiveRadius(seed, d);
            int gx = (int) Math.floor(d.x()), gz = (int) Math.floor(d.z());
            double ground = mc.level.hasChunk(gx >> 4, gz >> 4) ? mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz) : p.y;
            double dy = Math.min(Math.abs(ground - d.y()), Math.abs(p.y - d.y()));
            if (dy >= r) continue;
            rings.add(new Ring(d.x(), d.z(), Math.sqrt(r * r - dy * dy), true));
        }
        // the nearest edge: the sound comes from there
        double best = Double.MAX_VALUE;
        for (Ring g : rings) {
            double dc = Math.hypot(p.x - g.x(), p.z - g.z()), edge = Math.abs(dc - g.r());
            if (edge < best) {
                best = edge;
                double ux = dc > 1e-6 ? (p.x - g.x()) / dc : 1, uz = dc > 1e-6 ? (p.z - g.z()) / dc : 0;
                ex = g.x() + ux * g.r();
                ez = g.z() + uz * g.r();
                ey = p.y;
            }
        }
        if (best < HEAR) target = (float) (0.15 + 0.75 * Math.pow(1 - best / HEAR, 1.5));
        if (target > 0.01f && (drone == null || drone.isStopped())) {
            drone = new Drone();
            mc.getSoundManager().play(drone);
        }
        // the border's motes: on each ring's arc facing the player, within SEE
        RandomSource r = mc.level.getRandom();
        int budget = 24;
        for (Ring g : rings) {
            double dc = Math.hypot(p.x - g.x(), p.z - g.z());
            if (Math.abs(dc - g.r()) > SEE) continue;
            double face = Math.atan2(p.z - g.z(), p.x - g.x());
            double span = Math.min(Math.PI, (SEE + 8) / Math.max(1, g.r()));
            int n = Math.min(budget, 4 + (int) (g.r() * span / 6));
            budget -= n;
            for (int i = 0; i < n; i++) {
                double a = face + (r.nextDouble() * 2 - 1) * span;
                double x = g.x() + g.r() * Math.cos(a), z = g.z() + g.r() * Math.sin(a);
                int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
                if (!mc.level.hasChunk(bx >> 4, bz >> 4) || Math.hypot(x - p.x, z - p.z) > SEE) continue;
                double y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) + r.nextDouble() * (g.rift() ? 10 : 6);
                mc.level.addParticle(g.rift() ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.WITCH, x, y, z, 0, 0.02 + r.nextDouble() * 0.03, 0);
            }
            if (budget <= 0) break;
        }
    }

    static void fade() {
        target = 0;
    }

    /** A low whispering drone at the nearest deep's edge, its volume eased to the target. */
    static final class Drone extends AbstractTickableSoundInstance {
        Drone() {
            super(SoundEvents.AMBIENT_SOUL_SAND_VALLEY_LOOP.value(), SoundSource.AMBIENT, RandomSource.create());
            looping = true;
            volume = 0.01f;
            pitch = 0.6f;
            attenuation = SoundInstance.Attenuation.NONE;
            x = ex;
            y = ey;
            z = ez;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            volume += (target - volume) * 0.06f;
            x = ex;
            y = ey;
            z = ez;
            if (target <= 0.005f && volume < 0.01f) stop();
        }
    }
}
