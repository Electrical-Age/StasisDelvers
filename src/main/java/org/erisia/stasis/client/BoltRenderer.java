package org.erisia.stasis.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.erisia.stasis.StasisNet;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightning from a tower's shield to its mast (Baughn: visual only, it hits the tower). The server sends from, to and
 * a seed (StasisNet.Bolt); every client draws the same branched bolt: a midpoint-displaced trunk with 2-3 branches,
 * camera-facing ribbons in the vanilla lightning render type (additive), bright for a few frames, then an afterglow.
 */
public final class BoltRenderer {
    private BoltRenderer() {
    }

    /** Seconds a bolt lasts (flash then afterglow). */
    static final double LIFE = 0.45;

    record Live(List<Vector3d[]> strokes, long born) {
    }

    static final List<Live> LIVE = new ArrayList<>();

    static void receive(StasisNet.Bolt b) {
        List<Vector3d[]> strokes = new ArrayList<>();
        RandomSource rnd = RandomSource.create(b.seed());
        Vector3d from = new Vector3d(b.fx(), b.fy(), b.fz()), to = new Vector3d(b.tx(), b.ty(), b.tz());
        Vector3d[] trunk = displace(from, to, 5, 0.22, rnd);
        strokes.add(trunk);
        int branches = 2 + rnd.nextInt(2);
        for (int i = 0; i < branches; i++) {
            Vector3d start = trunk[1 + rnd.nextInt(trunk.length / 2)];
            double len = from.distance(to) * (0.15 + 0.2 * rnd.nextDouble());
            Vector3d dir = new Vector3d(to).sub(from).normalize()
                .add(rnd.nextGaussian() * 0.8, rnd.nextGaussian() * 0.8, rnd.nextGaussian() * 0.8).normalize().mul(len);
            strokes.add(displace(start, new Vector3d(start).add(dir), 3, 0.3, rnd));
        }
        synchronized (LIVE) {
            LIVE.add(new Live(strokes, System.nanoTime()));
        }
    }

    /** Midpoint displacement: 2^depth segments, sideways jitter of `rough` x the segment length. */
    static Vector3d[] displace(Vector3d a, Vector3d b, int depth, double rough, RandomSource rnd) {
        List<Vector3d> pts = new ArrayList<>(List.of(a, b));
        for (int d = 0; d < depth; d++) {
            List<Vector3d> next = new ArrayList<>();
            for (int i = 0; i < pts.size() - 1; i++) {
                Vector3d p = pts.get(i), q = pts.get(i + 1);
                double len = p.distance(q);
                Vector3d m = new Vector3d(p).add(q).mul(0.5)
                    .add(rnd.nextGaussian() * rough * len, rnd.nextGaussian() * rough * len, rnd.nextGaussian() * rough * len);
                next.add(p);
                next.add(m);
            }
            next.add(pts.getLast());
            pts = next;
        }
        return pts.toArray(new Vector3d[0]);
    }

    static void submit(SubmitCustomGeometryEvent e) {
        List<Live> live;
        long now = System.nanoTime();
        synchronized (LIVE) {
            LIVE.removeIf(l -> (now - l.born()) / 1e9 > LIFE);
            if (LIVE.isEmpty()) return;
            live = new ArrayList<>(LIVE);
        }
        Vec3 cam = e.getLevelRenderState().cameraRenderState.pos;
        PoseStack ps = e.getPoseStack();
        e.getSubmitNodeCollector().submitCustomGeometry(ps, RenderTypes.lightning(), (pose, vc) -> {
            for (Live l : live) {
                double age = (now - l.born()) / 1e9;
                // flash for ~0.12 s (with a flicker), then fade
                float a = age < 0.12 ? (((int) (age * 60)) % 2 == 0 ? 1f : 0.6f) : (float) Math.max(0, 1 - (age - 0.12) / (LIFE - 0.12)) * 0.5f;
                for (int s = 0; s < l.strokes().size(); s++) {
                    float w = s == 0 ? 0.18f : 0.09f;
                    ribbon(vc, pose, l.strokes().get(s), cam, w * 2.5f, a * 0.35f);   // glow
                    ribbon(vc, pose, l.strokes().get(s), cam, w, a);                 // core
                }
            }
        });
    }

    private static void ribbon(VertexConsumer vc, PoseStack.Pose pose, Vector3d[] pts, Vec3 cam, float width, float alpha) {
        int col = ((int) (Math.min(1, alpha) * 255) << 24) | (0xC8 << 16) | (0xD8 << 8) | 0xFF;
        Vector3d c = new Vector3d(cam.x, cam.y, cam.z);
        for (int i = 0; i < pts.length - 1; i++) {
            Vector3d p = pts[i], q = pts[i + 1];
            Vector3d seg = new Vector3d(q).sub(p);
            Vector3d side = new Vector3d(seg).cross(new Vector3d(p).sub(c)).normalize().mul(width / 2);
            if (!Double.isFinite(side.x)) continue;
            vertex(vc, pose, new Vector3d(p).sub(side), cam, col);
            vertex(vc, pose, new Vector3d(p).add(side), cam, col);
            vertex(vc, pose, new Vector3d(q).add(side), cam, col);
            vertex(vc, pose, new Vector3d(q).sub(side), cam, col);
        }
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vector3d p, Vec3 cam, int col) {
        vc.addVertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).setColor(col);
    }

    static void init() {
        StasisNet.boltSink = BoltRenderer::receive;
    }

    static boolean any() {
        return !LIVE.isEmpty() && Minecraft.getInstance().level != null;
    }
}
