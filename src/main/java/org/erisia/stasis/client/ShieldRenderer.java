package org.erisia.stasis.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.erisia.stasis.ClientFields;
import org.erisia.stasis.Field;
import org.erisia.stasis.Stasis;
import org.joml.Vector3d;

import java.util.List;

/**
 * The shield: a hexagon ("beehive") sphere on each field, plain but visible (Baughn), as in the render prototype:
 * faint cells, brighter rims, a slow wave climbing it, the far side fainter. While the tower runs short (failing),
 * cells flicker and drop away and the colour warms to amber. Drawn camera-relative as translucent quads.
 */
final class ShieldRenderer {
    private ShieldRenderer() {
    }

    static void submit(SubmitCustomGeometryEvent e) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !Stasis.stops(mc.level)) return;
        List<Field> fields = ClientFields.fields();
        if (fields.isEmpty()) return;
        Vec3 cam = e.getLevelRenderState().cameraRenderState.pos;
        double time = (mc.level.getGameTime() % 24000L) / 20.0;
        double view = mc.options.getEffectiveRenderDistance() * 16.0;
        PoseStack ps = e.getPoseStack();
        for (Field f : fields) {
            double dist = Math.sqrt(Math.pow(f.x() - cam.x, 2) + Math.pow(f.y() - cam.y, 2) + Math.pow(f.z() - cam.z, 2));
            if (dist - f.radius() > view || f.collapse() >= 1) continue;
            int freq = Math.max(4, (int) Math.round(f.radius() / 2.3));
            List<Goldberg.Cell> cells = Goldberg.cells(freq);
            boolean camInside = dist < f.radius();
            e.getSubmitNodeCollector().submitCustomGeometry(ps, RenderTypes.debugQuads(), (pose, vc) -> {
                for (Goldberg.Cell c : cells) drawCell(vc, pose, f, c, cam, time, camInside);
            });
        }
    }

    private static void drawCell(VertexConsumer vc, PoseStack.Pose pose, Field f, Goldberg.Cell c, Vec3 cam, double time, boolean camInside) {
        double r = f.radius();
        Vector3d centre = new Vector3d(c.centre()).mul(r).add(f.x(), f.y(), f.z());
        // only the part above ground matters visually; skip cells far below the centre
        if (centre.y < f.y() - r * 0.35) return;
        double fail = f.failing();
        double gone = Math.max(0, Math.min(1, (fail - (1 - c.rnd()) * 0.9) * 5));
        if (gone >= 1) return;
        // a failing cell flickers before it goes
        if (fail > 0 && fail - (1 - c.rnd()) * 0.9 > -0.25 && Math.floorMod((long) (time * 14 + c.rnd() * 97), 3) == 0) return;
        Vector3d drop = new Vector3d(c.centre()).mul(1.5).add(0, -7, 0).mul(gone * gone);
        Vector3d toCam = new Vector3d(cam.x, cam.y, cam.z).sub(centre).normalize();
        double facing = c.centre().dot(toCam);
        double fres = Math.pow(1 - Math.abs(facing), 2);
        double wave = 0.5 + 0.5 * Math.sin(time * 1.3 - centre.y * 0.18 + c.rnd() * 1.5);
        double backFactor = (facing < 0) != camInside ? 0.5 : 1.0;
        double fillA = (0.015 + 0.06 * fres) * backFactor * (1 - gone);
        double rimA = (0.10 + 0.20 * wave) * backFactor * (1 - gone);
        float tr = (float) lerp(0.62, 1.0, Math.min(0.7, fail * 1.5)), tg = (float) lerp(0.84, 0.62, Math.min(0.7, fail * 1.5)),
            tb = (float) lerp(1.0, 0.35, Math.min(0.7, fail * 1.5));
        float bright = (float) (0.8 + 0.4 * wave);
        int fill = argb(fillA, tr * bright, tg * bright, tb * bright);
        int rim = argb(rimA, tr * bright, tg * bright, tb * bright);
        Vector3d[] ring = c.ring();
        int m = ring.length;
        Vector3d[] outer = new Vector3d[m], inner = new Vector3d[m];
        for (int i = 0; i < m; i++) {
            Vector3d p = new Vector3d(ring[i]).mul(r).add(f.x(), f.y(), f.z());
            outer[i] = lerpv(centre, p, 0.96).add(drop);
            inner[i] = lerpv(centre, p, 0.82).add(drop);
        }
        Vector3d cd = new Vector3d(centre).add(drop);
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            quad(vc, pose, cam, cd, inner[i], inner[j], inner[j], fill);
            quad(vc, pose, cam, inner[i], outer[i], outer[j], inner[j], rim);
        }
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam, Vector3d a, Vector3d b, Vector3d c, Vector3d d, int color) {
        for (Vector3d p : new Vector3d[]{a, b, c, d})
            vc.addVertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).setColor(color);
    }

    private static Vector3d lerpv(Vector3d a, Vector3d b, double t) {
        return new Vector3d(a).lerp(b, t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int argb(double a, double r, double g, double b) {
        return ((int) (Math.min(1, a) * 255) << 24) | ((int) (Math.min(1, r) * 255) << 16) | ((int) (Math.min(1, g) * 255) << 8) | (int) (Math.min(1, b) * 255);
    }
}
