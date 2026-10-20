package org.erisia.stasis.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.erisia.stasis.RiftFauna;
import org.erisia.stasis.StasisDelvers;
import org.erisia.stasis.StasisRegistry;

/**
 * A Puppeteer's host (Possession): two glowing purple eyes (λ-Nero) and static hanging round it. The eyes are two small
 * full-bright quads at the head's eye height, on the front of the head by its yaw; models differ, so they sit where most
 * heads are, not on each model's own eye pixels.
 */
public final class PossessedLook {
    private PossessedLook() {
    }

    static final ContextKey<Boolean> POSSESSED = new ContextKey<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "possessed"));

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void modifiers(RegisterRenderStateModifiersEvent e) {
        java.util.function.BiConsumer<LivingEntity, LivingEntityRenderState> set = (en, s) ->
            s.setRenderData(POSSESSED, en.getData(StasisRegistry.POSSESSED.get()));
        e.registerEntityModifier((Class) LivingEntityRenderer.class, (java.util.function.BiConsumer) set);
    }

    static void eyes(RenderLivingEvent.Post<?, ?, ?> e) {
        LivingEntityRenderState s = e.getRenderState();
        if (!Boolean.TRUE.equals(s.getRenderData(POSSESSED))) return;
        float yaw = (float) Math.toRadians(s.bodyRot + s.yRot);
        float fx = -(float) Math.sin(yaw), fz = (float) Math.cos(yaw);   // facing
        float rx = (float) Math.cos(yaw), rz = (float) Math.sin(yaw);    // its right
        float w = s.boundingBoxWidth;
        float ahead = Math.max(0.3f, w * 0.55f), apart = Math.max(0.09f, w * 0.17f), size = 0.07f, y = s.eyeHeight;
        PoseStack ps = e.getPoseStack();
        e.getSubmitNodeCollector().submitCustomGeometry(ps, RenderTypes.lightning(), (pose, vc) -> {
            for (int side = -1; side <= 1; side += 2) {
                float cx = fx * ahead + rx * apart * side, cz = fz * ahead + rz * apart * side;
                quad(pose, vc, cx, y, cz, rx * size, rz * size, size);
            }
        });
    }

    /** A square facing along the head (both windings, so it shows from either side). */
    private static void quad(PoseStack.Pose pose, VertexConsumer vc, float cx, float cy, float cz, float hx, float hz, float hy) {
        float[][] v = {{cx - hx, cy - hy, cz - hz}, {cx + hx, cy - hy, cz + hz}, {cx + hx, cy + hy, cz + hz}, {cx - hx, cy + hy, cz - hz}};
        int[][] order = {{0, 1, 2, 3}, {3, 2, 1, 0}};
        for (int[] o : order)
            for (int i : o) vc.addVertex(pose, v[i][0], v[i][1], v[i][2]).setColor(0.75f, 0.25f, 1f, 1f);
    }

    /** Static round a host, also while it stands frozen between bursts (so this runs before FrozenEntities cancels). */
    static void haze(EntityTickEvent.Pre e) {
        var en = e.getEntity();
        if (!en.level().isClientSide() || !en.getData(StasisRegistry.POSSESSED.get())) return;
        var r = en.getRandom();
        for (int i = 0; i < 2; i++)
            en.level().addParticle(RiftFauna.staticDust(r.nextInt(3)), en.getX() + (r.nextDouble() - 0.5) * en.getBbWidth() * 1.2,
                en.getY() + r.nextDouble() * en.getBbHeight(), en.getZ() + (r.nextDouble() - 0.5) * en.getBbWidth() * 1.2, 0, 0, 0);
    }
}
