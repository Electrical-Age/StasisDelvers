package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.erisia.stasis.Spread;

import java.util.ArrayList;
import java.util.List;

/**
 * The edge of the stilled land, made visible (Baughn: over snow a stilled patch was hard to see): where a stilled
 * column meets a running one, a dark violet curtain falls a few blocks down the edge like a slow waterfall, from just
 * above the ground on the higher side into it. Spreading mode only; the edges within REACH blocks of the player,
 * found once a second; drawn untextured, fading with distance.
 */
public final class StillEdge {
    private StillEdge() {
    }

    static final int REACH = 48;
    static final double H = 3.5;

    /** One edge: a block side between columns (x0, z0) and its neighbour along +x (alongX) or +z, the ground height. */
    record Edge(int x, int z, boolean alongX, double y) {
    }

    static volatile List<Edge> edges = List.of();

    static void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused() || mc.level.getGameTime() % 20 != 0) return;
        if (!Spread.active(mc.level)) {
            edges = List.of();
            return;
        }
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ(), n = 2 * REACH + 1;
        boolean[] run = new boolean[n * n];
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) run[j * n + i] = Spread.runsAt(mc.level, px - REACH + i, pz - REACH + j);
        List<Edge> out = new ArrayList<>();
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) {
                int x = px - REACH + i, z = pz - REACH + j;
                if (i + 1 < n && run[j * n + i] != run[j * n + i + 1]) out.add(new Edge(x, z, true, ground(mc, x, z, x + 1, z)));
                if (j + 1 < n && run[j * n + i] != run[(j + 1) * n + i]) out.add(new Edge(x, z, false, ground(mc, x, z, x, z + 1)));
            }
        edges = out;
    }

    /** The higher of the two columns' ground (the curtain stands on the edge above both). */
    static double ground(Minecraft mc, int x0, int z0, int x1, int z1) {
        if (!mc.level.hasChunk(x0 >> 4, z0 >> 4) || !mc.level.hasChunk(x1 >> 4, z1 >> 4)) return mc.player.getY();
        return Math.max(mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, z0), mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x1, z1));
    }

    /** The curtain: per edge, a few bands whose brightness runs downwards with time (falling streaks). */
    static void draw(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent e) {
        var es = edges;
        var mc = Minecraft.getInstance();
        if (es.isEmpty() || mc.level == null) return;
        var cam = e.getLevelRenderState().cameraRenderState.pos;
        double time = (mc.level.getGameTime() % 100000) / 20.0;
        int bands = 6;
        e.getSubmitNodeCollector().submitCustomGeometry(e.getPoseStack(), net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads(), (pose, buf) -> {
            for (Edge g : es) {
                double ax = g.alongX ? g.x + 1 : g.x, az = g.alongX ? g.z : g.z + 1;
                double bx = g.alongX ? g.x + 1 : g.x + 1, bz = g.alongX ? g.z + 1 : g.z + 1;
                double dist = Math.hypot(ax - cam.x, az - cam.z);
                double fade = Math.max(0, Math.min(1, (REACH - dist) / 12));
                if (fade <= 0) continue;
                for (int k = 0; k < bands; k++) {
                    double y0 = g.y - 0.5 + H * k / bands, y1 = g.y - 0.5 + H * (k + 1) / bands;
                    int a0 = alpha(y0 - g.y, time, ax + az, fade), a1 = alpha(y1 - g.y, time, ax + az, fade);
                    v(buf, pose, cam, ax, y0, az, a0);
                    v(buf, pose, cam, bx, y0, bz, a0);
                    v(buf, pose, cam, bx, y1, bz, a1);
                    v(buf, pose, cam, ax, y1, az, a1);
                }
            }
        });
    }

    /** Darker streaks sliding down (phase moves with time), thinning towards the top. */
    static int alpha(double h, double time, double seed, double fade) {
        double streak = 0.6 + 0.4 * Math.sin((h + time * 1.6) * 3.0 + seed * 1.7);
        double top = Math.max(0, 1 - h / H);
        return (int) (255 * 0.55 * fade * streak * top);
    }

    static void v(com.mojang.blaze3d.vertex.VertexConsumer buf, com.mojang.blaze3d.vertex.PoseStack.Pose pose, net.minecraft.world.phys.Vec3 cam,
                  double x, double y, double z, int a) {
        buf.addVertex(pose, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).setColor(40, 10, 60, a);
    }
}
