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

    static final double HEAR = 48, SEE = 96;
    /** This tick's rings (for the wall drawn each frame). */
    static volatile java.util.List<Ring> current = java.util.List.of();
    static Drone drone;
    static float target;
    static double ex, ey, ez;

    /** A deep's ground ring near the player: centre, ring radius at the surface. */
    record Ring(double x, double z, double r, boolean rift, double cy, double big) {
        /** The edge's distance from the centre at angle a: a rift's sphere (radius big, centre height cy) where it meets the
         *  ground there, not one radius for the whole ring (Baughn: the wall moved as he flew up and down). */
        double at(net.minecraft.client.multiplayer.ClientLevel level, double a, double fallbackY) {
            if (!rift) return r;
            double rr = r;
            for (int i = 0; i < 3; i++) {
                double y = ground(level, x + rr * Math.cos(a), z + rr * Math.sin(a), fallbackY);
                double dy = Math.abs(y - cy);
                if (dy >= big) return rr;
                rr = Math.sqrt(big * big - dy * dy);
            }
            return rr;
        }
    }

    /**
     * The ground's top at (x, z): the first solid block down from the top with solid under it, so rift debris floating
     * over a deep doesn't count (AdventurAgent: a curtain segment ran up to the debris as a big blue wedge).
     */
    static double ground(net.minecraft.client.multiplayer.ClientLevel level, double x, double z, double fallback) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return fallback;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) - 1;
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int n = 0; n < 96 && y > level.getMinY(); n++) {
            boolean grounded = true;
            for (int d = 1; d <= 3; d++)
                if (level.getBlockState(pos.set(bx, y - d, bz)).isAir()) {
                    grounded = false;
                    y -= d;
                    break;
                }
            if (grounded) return y + 1;
            while (y > level.getMinY() && level.getBlockState(pos.set(bx, y, bz)).isAir()) y--;
        }
        return y + 1;
    }

    static void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.isPaused()) return;
        int seed = ClientFields.deepSeed();
        target = 0;
        if (seed == 0 || !Stasis.stops(mc.level)) {
            current = java.util.List.of();
            fade();
            return;
        }
        double t = Double.isNaN(StasisPost.smoothClock) ? Deeps.time(mc.level) : StasisPost.smoothClock;
        var p = mc.player.position();
        java.util.List<Ring> rings = new java.util.ArrayList<>();
        for (Deeps.Deep d : Deeps.calmed(mc.level, Deeps.within((float) p.x, (float) p.z, (float) SEE, t, seed)))
            if (d.strength() > 0.2f) rings.add(new Ring(d.x(), d.z(), d.radius(), false, 0, 0));
        for (StaticDeeps.Deep d : StaticDeeps.near((float) p.x, (float) p.y, (float) p.z, (float) SEE, seed)) {
            float r = StaticDeeps.effectiveRadius(seed, d);
            int gx = (int) Math.floor(d.x()), gz = (int) Math.floor(d.z());
            double ground = mc.level.hasChunk(gx >> 4, gz >> 4) ? mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz) : p.y;
            // (the ground's, not the player's height: the ring mustn't move as you fly, Baughn)
            double dy = Math.abs(ground - d.y());
            if (dy >= r) continue;
            rings.add(new Ring(d.x(), d.z(), Math.sqrt(r * r - dy * dy), true, d.y(), r));
        }
        current = rings;
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
        int budget = 12;
        for (Ring g : rings) {
            double dc = Math.hypot(p.x - g.x(), p.z - g.z());
            if (Math.abs(dc - g.r()) > SEE) continue;
            double face = Math.atan2(p.z - g.z(), p.x - g.x());
            double span = Math.min(Math.PI, (SEE + 8) / Math.max(1, g.r()));
            int n = Math.min(budget, 4 + (int) (g.r() * span / 6));
            budget -= n;
            for (int i = 0; i < n; i++) {
                double a = face + (r.nextDouble() * 2 - 1) * span;
                double rr = g.at(mc.level, a, p.y);
                double x = g.x() + rr * Math.cos(a), z = g.z() + rr * Math.sin(a);
                int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
                if (!mc.level.hasChunk(bx >> 4, bz >> 4) || Math.hypot(x - p.x, z - p.z) > SEE) continue;
                double y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) + r.nextDouble() * (g.rift() ? 10 : 6);
                mc.level.addParticle(ParticleTypes.REVERSE_PORTAL, x, y, z, 0, 0.02 + r.nextDouble() * 0.03, 0);
            }
            if (budget <= 0) break;
        }
    }

    /**
     * The border as a wall (AdventurAgent: motes alone were dark specks, seen only within ~10 blocks): a faint violet
     * curtain standing on the ground along each deep's edge where it faces the player, shimmering, fading upwards;
     * untextured, without terrain fog, out to SEE blocks.
     */
    static void wall(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent e) {
        var rings = current;
        var mc = Minecraft.getInstance();
        if (rings.isEmpty() || mc.level == null) return;
        var cam = e.getLevelRenderState().cameraRenderState.pos;
        double time = (mc.level.getGameTime() % 100000) / 20.0;
        e.getSubmitNodeCollector().submitCustomGeometry(e.getPoseStack(), net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads(), (pose, buf) -> {
            for (Ring g : rings) {
                double dc = Math.hypot(cam.x - g.x(), cam.z - g.z());
                if (Math.abs(dc - g.r()) > SEE) continue;
                double face = Math.atan2(cam.z - g.z(), cam.x - g.x());
                double span = Math.min(Math.PI, (SEE + 8) / Math.max(1, g.r()));
                // a fixed angular grid (~3 blocks a step), so the curtain's corners stay put as the camera moves (Baughn: it
                // shifted with x and z)
                double step = Math.min(Math.PI / 4, 3 / Math.max(1, g.r()));
                int k0 = (int) Math.floor((face - span) / step), k1 = (int) Math.ceil((face + span) / step);
                // tall seen from the ground, low seen from above: a tall wall seen from above slid about with the
                // viewpoint (Baughn: it moved as he flew up), a low one was hard to see at eye level (AdventurAgent)
                double[] prev = null;
                for (int k = k0; k <= k1; k++) {
                    double a = k * step;
                    double rr = g.at(mc.level, a, cam.y - 2);
                    double x = g.x() + rr * Math.cos(a), z = g.z() + rr * Math.sin(a);
                    double y = ground(mc.level, x, z, cam.y - 2) - 0.5;   // (from a little under the ground: no gap on slopes)
                    double dist = Math.hypot(x - cam.x, z - cam.z);
                    double fadeOut = Math.max(0, Math.min(1, (SEE - dist) / 24));
                    double shimmer = 0.75 + 0.25 * Math.sin(time * 1.7 + a * g.r() / 5);
                    int alpha = (int) (255 * 0.40 * fadeOut * shimmer);
                    double h = 9 - 7 * Math.max(0, Math.min(1, (cam.y - y - 6) / 14));
                    double[] cur = {x, y, z, alpha, h};
                    if (prev != null && (prev[3] > 2 || alpha > 2)) {
                        int[] c = g.rift() ? new int[]{170, 60, 255} : new int[]{140, 110, 255};
                        v(buf, pose, cam, prev[0], prev[1], prev[2], c, (int) prev[3]);
                        v(buf, pose, cam, x, y, z, c, alpha);
                        v(buf, pose, cam, x, y + h, z, c, 0);
                        v(buf, pose, cam, prev[0], prev[1] + prev[4], prev[2], c, 0);
                    }
                    prev = cur;
                }
            }
        });
    }

    static void v(com.mojang.blaze3d.vertex.VertexConsumer buf, com.mojang.blaze3d.vertex.PoseStack.Pose pose, net.minecraft.world.phys.Vec3 cam,
                  double x, double y, double z, int[] c, int a) {
        buf.addVertex(pose, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).setColor(c[0], c[1], c[2], a);
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
