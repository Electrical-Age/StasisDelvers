package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.erisia.stasis.Spread;

import java.util.ArrayList;
import java.util.List;

/**
 * The edge of the stilled land, made visible (Baughn: over snow a stilled patch was hard to see): where a stilled
 * column meets a running one, a dark violet curtain falls down the edge like a slow waterfall, from just above the
 * ground into it: about two blocks tall, its top ragged (Baughn: a third as tall as the first, six blocks, with ragged
 * tops). Spreading mode only; the edges within REACH blocks of the player,
 * found once a second; drawn untextured, fading with distance.
 */
public final class StillEdge {
    private StillEdge() {
    }

    static final int REACH = 48;
    /** The curtain's mean height (blocks); each point of its top is RAG_MIN..RAG_MAX of it. */
    static final double H = 2, RAG_MIN = 0.45, RAG_MAX = 1.35;

    /** One stretch of the edge: from (x0, z0) to (x1, z1) (blocks, smooth: the stillness field's zero line), each end's
     *  ground height. */
    record Edge(double x0, double z0, double y0, double x1, double z1, double y1) {
    }

    static volatile List<Edge> edges = List.of();

    /**
     * Once a second: the zero line of the stillness field (Spread.edgeField: stilled where negative), by marching squares
     * over the column centres within REACH, interpolated, so a diagonal edge is a straight curtain, not a staircase of
     * 1-block sheets seen edge-on (AdventurAgent: scattered thin ribbons).
     */
    static void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused() || mc.level.getGameTime() % 20 != 0) return;
        if (!Spread.active(mc.level)) {
            edges = List.of();
            return;
        }
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ(), n = 2 * REACH + 1;
        double[] f = new double[n * n];
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) {
                long v = Spread.edgeField(mc.level, px - REACH + i, pz - REACH + j);
                if (v == Long.MIN_VALUE) {
                    edges = List.of();
                    return;
                }
                f[j * n + i] = v + 0.5;   // (stilled where v < 0, i.e. f < 0.5; shifted so no corner is exactly 0)
            }
        List<Edge> out = new ArrayList<>();
        for (int j = 0; j + 1 < n; j++)
            for (int i = 0; i + 1 < n; i++) {
                double a = f[j * n + i], b = f[j * n + i + 1], c = f[(j + 1) * n + i + 1], d = f[(j + 1) * n + i];
                // the crossings on the cell's four sides (corners at column centres)
                double[] pts = new double[8];
                int k = 0;
                double x0 = px - REACH + i + 0.5, z0 = pz - REACH + j + 0.5;
                if ((a < 0) != (b < 0)) { pts[k++] = x0 + a / (a - b); pts[k++] = z0; }
                if ((b < 0) != (c < 0)) { pts[k++] = x0 + 1; pts[k++] = z0 + b / (b - c); }
                if ((c < 0) != (d < 0)) { pts[k++] = x0 + 1 - c / (c - d); pts[k++] = z0 + 1; }
                if ((d < 0) != (a < 0)) { pts[k++] = x0; pts[k++] = z0 + 1 - d / (d - a); }
                for (int q = 0; q + 3 < k; q += 4)
                    out.add(new Edge(pts[q], pts[q + 1], ground(mc, pts[q], pts[q + 1]), pts[q + 2], pts[q + 3], ground(mc, pts[q + 2], pts[q + 3])));
            }
        edges = out;
    }

    /** The ground's top at (x, z). */
    static double ground(Minecraft mc, double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!mc.level.hasChunk(bx >> 4, bz >> 4)) return mc.player.getY();
        return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }

    /** The curtain: per stretch, bands whose darkness runs downwards with time (falling streaks). */
    static void draw(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent e) {
        var es = edges;
        var mc = Minecraft.getInstance();
        if (es.isEmpty() || mc.level == null) return;
        var cam = e.getLevelRenderState().cameraRenderState.pos;
        double time = (mc.level.getGameTime() % 100000) / 20.0;
        int bands = 4;
        e.getSubmitNodeCollector().submitCustomGeometry(e.getPoseStack(), net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads(), (pose, buf) -> {
            for (Edge g : es) {
                double dist = Math.hypot(0.5 * (g.x0 + g.x1) - cam.x, 0.5 * (g.z0 + g.z1) - cam.z);
                double fade = Math.max(0, Math.min(1, (REACH - dist) / 12));
                if (fade <= 0) continue;
                // (each end's own top: neighbouring stretches share their ends, so the ragged top is unbroken)
                double t0 = top(g.x0, g.z0), t1 = top(g.x1, g.z1);
                for (int k = 0; k < bands; k++) {
                    double ha0 = -0.5 + (t0 + 0.5) * k / bands, ha1 = -0.5 + (t0 + 0.5) * (k + 1) / bands;
                    double hb0 = -0.5 + (t1 + 0.5) * k / bands, hb1 = -0.5 + (t1 + 0.5) * (k + 1) / bands;
                    int a0 = alpha(ha0, t0, time, g.x0 + g.z0, fade), a1 = alpha(ha1, t0, time, g.x0 + g.z0, fade);
                    int b0 = alpha(hb0, t1, time, g.x1 + g.z1, fade), b1 = alpha(hb1, t1, time, g.x1 + g.z1, fade);
                    v(buf, pose, cam, g.x0, g.y0 + ha0, g.z0, a0);
                    v(buf, pose, cam, g.x1, g.y1 + hb0, g.z1, b0);
                    v(buf, pose, cam, g.x1, g.y1 + hb1, g.z1, b1);
                    v(buf, pose, cam, g.x0, g.y0 + ha1, g.z0, a1);
                }
            }
        });
    }

    /** The curtain's top over the point (x, z) on the edge, above the ground: ragged, fixed per place (a hash of the
     *  point to 1/16 block, so both stretches meeting there agree). */
    static double top(double x, double z) {
        long hx = Math.round(x * 16), hz = Math.round(z * 16);
        long hsh = hx * 0x9E3779B97F4A7C15L ^ hz * 0xC2B2AE3D27D4EB4FL;
        hsh ^= hsh >>> 31;
        hsh *= 0xBF58476D1CE4E5B9L;
        hsh ^= hsh >>> 29;
        double u = (hsh >>> 11) * 0x1.0p-53;
        return H * (RAG_MIN + (RAG_MAX - RAG_MIN) * u);
    }

    /** Darker streaks sliding down (phase moves with time), fainter towards the top (t, that point's). */
    static int alpha(double h, double t, double time, double seed, double fade) {
        double streak = 0.55 + 0.45 * Math.sin((h + time * 1.6) * 2.2 + seed * 1.3);
        // (still 40 % at the top, so the ragged outline shows)
        double top = 1 - 0.6 * Math.max(0, Math.min(1, (h + 0.5) / (t + 0.5)));
        return (int) (255 * 0.9 * fade * streak * top);
    }

    static void v(com.mojang.blaze3d.vertex.VertexConsumer buf, com.mojang.blaze3d.vertex.PoseStack.Pose pose, net.minecraft.world.phys.Vec3 cam,
                  double x, double y, double z, int a) {
        buf.addVertex(pose, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).setColor(55, 12, 85, a);
    }
}
