package org.erisia.stasis.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.erisia.stasis.QuernBlockEntity;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The rift quern's moving parts (Baughn: the crystal hovers over the table's metal bowl like the enchanting table's
 * book; five articulated robot arms poke it with lasers from random angles; the queue in the table's corners). The
 * table and bowl are the block model; the dust it drips is QuernBlock's client particles.
 * <p>
 * Each arm stands on the table round the bowl, two segments and a nozzle, solved as a two-bone arm (the elbow up)
 * towards a point near the crystal that changes every ~0.7-1.3 s (eased over the first fifth), with a violet laser
 * from the nozzle into the crystal while it holds. Its clock runs at the quern's rate while it grinds; idle, the arms
 * rest folded and the crystal sits still.
 */
public class QuernRenderer implements BlockEntityRenderer<QuernBlockEntity, QuernRenderer.State> {
    static final Identifier ARM = Identifier.withDefaultNamespace("textures/block/netherite_block.png");
    static final Identifier JOINT = Identifier.withDefaultNamespace("textures/block/copper_block.png");
    static final int ARMS = 5;
    static final float TOP = 15.3f / 16f, L1 = 0.27f, L2 = 0.23f, NOZZLE = 0.05f, BASE_R = 0.41f;

    public static class State extends BlockEntityRenderState {
        final ItemStackRenderState crystal = new ItemStackRenderState();
        final ItemStackRenderState[] queue = {new ItemStackRenderState(), new ItemStackRenderState(), new ItemStackRenderState(), new ItemStackRenderState()};
        boolean grinding, hasCrystal;
        float left, time;
        int seed;
    }

    private final ItemModelResolver items;

    public QuernRenderer(BlockEntityRendererProvider.Context context) {
        this.items = context.itemModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    /** The arms' clock (seconds): real time at the quern's rate while it grinds, held while it doesn't. */
    static final java.util.Map<net.minecraft.core.BlockPos, double[]> CLOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    static float clock(net.minecraft.core.BlockPos pos, boolean running) {
        long now = System.nanoTime();
        double[] c = CLOCKS.computeIfAbsent(pos.immutable(), k -> new double[]{0, now});
        if (running) c[0] += Math.min(0.25, (now - c[1]) / 1e9) * QuernBlockEntity.RATE;
        c[1] = now;
        if (CLOCKS.size() > 256) CLOCKS.clear();
        return (float) c[0];
    }

    @Override
    public void extractRenderState(QuernBlockEntity be, State state, float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, cameraPosition, breakProgress);
        int seed = (int) be.getBlockPos().asLong();
        state.seed = seed;
        state.grinding = be.grinding();
        state.hasCrystal = !be.burning().isEmpty();
        state.left = be.left();
        state.time = clock(be.getBlockPos(), state.grinding);
        items.updateForTopItem(state.crystal, be.burning(), ItemDisplayContext.FIXED, be.getLevel(), null, seed);
        for (int i = 0; i < QuernBlockEntity.QUEUE; i++)
            items.updateForTopItem(state.queue[i], be.queued(i), ItemDisplayContext.FIXED, be.getLevel(), null, seed + 1 + i);
    }

    @Override
    public AABB getRenderBoundingBox(QuernBlockEntity be) {
        return new AABB(be.getBlockPos()).expandTowards(0, 1, 0);
    }

    static float hash(int seed, int a, int b) {
        int h = seed * 0x27d4eb2d ^ a * 0x165667b1 ^ b * 0x61c88647;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        float t = s.time;
        Vector3f crystal = new Vector3f(0.5f, 1.12f + (s.grinding ? 0.02f * (float) Math.sin(t * 2.2) : 0), 0.5f);
        // the queue in the corners, lying on the table
        for (int i = 0; i < QuernBlockEntity.QUEUE; i++) {
            if (s.queue[i].isEmpty()) continue;
            pose.pushPose();
            pose.translate(i % 2 == 0 ? 0.16f : 0.84f, TOP + 0.01f, i < 2 ? 0.16f : 0.84f);
            pose.rotateDegrees(Axis.YP, 45 + 90 * i);
            pose.rotateDegrees(Axis.XP, 90);
            pose.scale(0.28f, 0.28f, 0.28f);
            s.queue[i].submit(pose, out, s.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
        // the crystal, hovering and turning, smaller as it wears down
        if (s.hasCrystal && !s.crystal.isEmpty()) {
            pose.pushPose();
            pose.translate(crystal.x, crystal.y, crystal.z);
            pose.rotateDegrees(Axis.YP, (float) Math.toDegrees(t * 0.9f));
            float sc = 0.42f * (0.35f + 0.65f * s.left);
            pose.scale(sc, sc, sc);
            s.crystal.submit(pose, out, 0xF000F0, OverlayTexture.NO_OVERLAY, 0);   // (full bright: it glows)
            pose.popPose();
        }
        // the arms, and their lasers
        Vector3f[][] arms = new Vector3f[ARMS][];
        boolean[] firing = new boolean[ARMS];
        for (int k = 0; k < ARMS; k++) {
            float a = (float) (k * Math.PI * 2 / ARMS + 0.3 + 0.2 * hash(s.seed, k, -1));
            Vector3f base = new Vector3f(0.5f + BASE_R * (float) Math.cos(a), TOP, 0.5f + BASE_R * (float) Math.sin(a));
            Vector3f tip;
            if (s.grinding && s.hasCrystal) {
                // each arm on its own beat: a new aim every 0.7-1.3 s, eased in over the first fifth
                float period = 0.7f + 0.6f * hash(s.seed, k, -2);
                float u = t / period + hash(s.seed, k, -3);
                int e = (int) Math.floor(u);
                float f = u - e;
                Vector3f now = aim(s.seed, k, e, a, crystal), before = aim(s.seed, k, e - 1, a, crystal);
                float ease = Math.min(1, f / 0.2f);
                ease = ease * ease * (3 - 2 * ease);
                tip = before.lerp(now, ease, new Vector3f());
                firing[k] = f > 0.22f;
            } else {   // folded at rest, leaning in over the bowl's rim
                tip = new Vector3f(base).lerp(new Vector3f(0.5f, TOP, 0.5f), 0.35f).add(0, 0.12f, 0);
            }
            arms[k] = solve(base, tip, crystal);
        }
        out.submitCustomGeometry(pose, RenderTypes.entitySolid(ARM), (p, vc) -> {
            for (Vector3f[] arm : arms) {
                box(p, vc, arm[0], arm[1], 0.045f, s.lightCoords);
                box(p, vc, arm[1], arm[2], 0.035f, s.lightCoords);
                box(p, vc, arm[2], arm[3], 0.022f, s.lightCoords);
            }
        });
        out.submitCustomGeometry(pose, RenderTypes.entitySolid(JOINT), (p, vc) -> {
            for (Vector3f[] arm : arms) {
                box(p, vc, new Vector3f(arm[0]).sub(0, 0.02f, 0), new Vector3f(arm[0]).add(0, 0.035f, 0), 0.09f, s.lightCoords);   // (its foot)
                box(p, vc, new Vector3f(arm[1]).sub(0, 0.03f, 0), new Vector3f(arm[1]).add(0, 0.03f, 0), 0.06f, s.lightCoords);   // (its elbow)
                box(p, vc, new Vector3f(arm[2]).sub(0, 0.022f, 0), new Vector3f(arm[2]).add(0, 0.022f, 0), 0.045f, s.lightCoords);   // (its wrist)
            }
        });
        out.submitCustomGeometry(pose, RenderTypes.lightning(), (p, vc) -> {
            for (int k = 0; k < ARMS; k++) {
                if (!firing[k]) continue;
                float flick = 0.75f + 0.25f * hash(s.seed, k, (int) (t * 30));
                beam(p, vc, arms[k][3], crystal, 0.012f, 0.75f * flick, 0.35f * flick, 1.0f * flick, 0.9f);
                beam(p, vc, arms[k][3], crystal, 0.004f, 1f, 0.9f, 1f, 1f);   // (a hot white core)
            }
        });
    }

    /** Where arm k's nozzle points from in epoch e: a point 0.17-0.24 from the crystal, on the arm's side and above. */
    static Vector3f aim(int seed, int k, int e, float armAngle, Vector3f crystal) {
        float az = armAngle + (hash(seed, k, e) - 0.5f) * 1.6f;
        float el = -0.2f + 1.0f * hash(seed, k, e + 7919);
        float r = 0.17f + 0.07f * hash(seed, k, e + 104729);
        return new Vector3f((float) (Math.cos(az) * Math.cos(el)), (float) Math.sin(el), (float) (Math.sin(az) * Math.cos(el))).mul(r).add(crystal);
    }

    /** {base, elbow, wrist, nozzle end}: a two-bone arm from base to the wrist (elbow up), its nozzle pointing at the crystal. */
    static Vector3f[] solve(Vector3f base, Vector3f wrist, Vector3f crystal) {
        Vector3f bt = new Vector3f(wrist).sub(base);
        float d = Math.min(bt.length(), L1 + L2 - 0.005f);
        if (d < 1e-4f) d = 1e-4f;
        Vector3f u = new Vector3f(bt).normalize();
        Vector3f w = new Vector3f(0, 1, 0).sub(new Vector3f(u).mul(u.y));
        if (w.lengthSquared() < 1e-6f) w.set(1, 0, 0);
        w.normalize();
        float a = (L1 * L1 - L2 * L2 + d * d) / (2 * d);
        float h = (float) Math.sqrt(Math.max(0, L1 * L1 - a * a));
        Vector3f elbow = new Vector3f(base).add(new Vector3f(u).mul(a)).add(new Vector3f(w).mul(h));
        Vector3f tip = new Vector3f(base).add(new Vector3f(u).mul(d));
        Vector3f nozzle = new Vector3f(crystal).sub(tip);
        if (nozzle.lengthSquared() > 1e-8f) nozzle.normalize().mul(NOZZLE);
        return new Vector3f[]{base, elbow, tip, new Vector3f(tip).add(nozzle)};
    }

    /** A square rod from a to b, `size` across, lit by its face normals. */
    static void box(PoseStack.Pose p, VertexConsumer vc, Vector3f a, Vector3f b, float size, int light) {
        Vector3f dir = new Vector3f(b).sub(a);
        if (dir.lengthSquared() < 1e-10f) return;
        dir.normalize();
        Vector3f s1 = Math.abs(dir.y) < 0.9f ? new Vector3f(0, 1, 0).cross(dir).normalize() : new Vector3f(1, 0, 0).cross(dir).normalize();
        Vector3f s2 = new Vector3f(dir).cross(s1).normalize();
        s1.mul(size / 2);
        s2.mul(size / 2);
        Vector3f[] c = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            Vector3f q = new Vector3f((i & 4) == 0 ? a : b);
            q.add(new Vector3f(s1).mul((i & 1) == 0 ? -1 : 1)).add(new Vector3f(s2).mul((i & 2) == 0 ? -1 : 1));
            c[i] = q;
        }
        int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        for (int[] f : faces) {
            Vector3f n = new Vector3f(c[f[1]]).sub(c[f[0]]).cross(new Vector3f(c[f[2]]).sub(c[f[0]])).normalize();
            float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int v = 0; v < 4; v++) {
                Vector3f q = c[f[v]];
                vc.addVertex(p, q.x, q.y, q.z).setColor(1f, 1f, 1f, 1f).setUv(uv[v][0] * 0.25f, uv[v][1] * 0.25f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, n.x, n.y, n.z);
            }
        }
    }

    /** A laser from a to b: two crossed quads of `width` (additive). */
    static void beam(PoseStack.Pose p, VertexConsumer vc, Vector3f a, Vector3f b, float width, float r, float g, float bl, float alpha) {
        Vector3f dir = new Vector3f(b).sub(a);
        if (dir.lengthSquared() < 1e-10f) return;
        dir.normalize();
        Vector3f s1 = Math.abs(dir.y) < 0.9f ? new Vector3f(0, 1, 0).cross(dir).normalize() : new Vector3f(1, 0, 0).cross(dir).normalize();
        Vector3f s2 = new Vector3f(dir).cross(s1).normalize();
        for (Vector3f s : new Vector3f[]{s1.mul(width / 2), s2.mul(width / 2)}) {
            vc.addVertex(p, a.x - s.x, a.y - s.y, a.z - s.z).setColor(r, g, bl, alpha);
            vc.addVertex(p, a.x + s.x, a.y + s.y, a.z + s.z).setColor(r, g, bl, alpha);
            vc.addVertex(p, b.x + s.x, b.y + s.y, b.z + s.z).setColor(r, g, bl, alpha);
            vc.addVertex(p, b.x - s.x, b.y - s.y, b.z - s.z).setColor(r, g, bl, alpha);
        }
    }
}
