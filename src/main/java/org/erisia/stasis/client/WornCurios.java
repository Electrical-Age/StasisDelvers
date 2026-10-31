package org.erisia.stasis.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemStack;
import org.erisia.stasis.StasisRegistry;

/**
 * Stasis Delvers' Curios accessories drawn on the wearer (Baughn: the curios show in third person, as other mods' do):
 * the rift pendants on the chest, the chronal anchor as a small charm pinned on the left of the chest. Each is its item
 * sprite, following the body; Curios draws them only while the slot's render toggle is on. No hard dependency: Curios
 * classes are touched only after {@link org.erisia.stasis.CuriosCompat#loaded}.
 */
public final class WornCurios {
    private WornCurios() {
    }

    static void register(net.neoforged.bus.api.IEventBus modBus) {
        if (org.erisia.stasis.CuriosCompat.loaded())
            modBus.addListener(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent.class, e -> e.enqueueWork(Impl::register));
    }

    /** How far in front of the chest the sprites hang, in model pixels: over a chestplate's 1 px (the battery suit). */
    static final float OFF_CHEST = 3.3f;

    private static final class Impl {
        static void register() {
            // the pendant: its cord's ends at the neck, 8 px across, the crystal on the breastbone
            Body pendant = new Body(0, 4, 0.5f);
            top.theillusivec4.curios.api.client.ICurioRenderer.register(StasisRegistry.RIFT_PENDANT.get(), () -> pendant);
            top.theillusivec4.curios.api.client.ICurioRenderer.register(StasisRegistry.CHARGED_RIFT_PENDANT.get(), () -> pendant);
            // the anchor: a 5 px disc on the wearer's left breast (+x; the right arm is at -x), clear of the pendant's cord
            top.theillusivec4.curios.api.client.ICurioRenderer.register(StasisRegistry.CHRONAL_ANCHOR.get(), () -> new Body(3.2f, 6, 0.3f));
        }
    }

    /** An item sprite flat on the front of the body, its centre at (x, y) model pixels from the neck (y down), scaled. */
    record Body(float x, float y, float scale) implements top.theillusivec4.curios.api.client.ICurioRenderer {
        @Override
        public <S extends LivingEntityRenderState, M extends net.minecraft.client.model.EntityModel<? super S>> void render(
            ItemStack stack, top.theillusivec4.curios.api.SlotContext ctx, PoseStack pose, SubmitNodeCollector out, int light, S state,
            net.minecraft.client.renderer.entity.RenderLayerParent<S, M> parent,
            net.minecraft.client.renderer.entity.EntityRendererProvider.Context context, float yRot, float xRot) {
            if (!(parent.getModel() instanceof HumanoidModel<?> m)) return;
            pose.pushPose();
            m.root().translateAndRotate(pose);
            m.body.translateAndRotate(pose);
            pose.translate(x / 16f, y / 16f, -OFF_CHEST / 16f);
            // the body's space is y down with the front at -z; a flat item faces +z: turned over about x, it stands
            // upright facing out
            pose.rotateDegrees(Axis.XP, 180);
            pose.scale(scale, scale, scale);
            var r = new net.minecraft.client.renderer.item.ItemStackRenderState();
            net.minecraft.client.Minecraft.getInstance().getItemModelResolver()
                .updateForLiving(r, stack, net.minecraft.world.item.ItemDisplayContext.NONE, ctx.entity());
            r.submit(pose, out, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
            pose.popPose();
        }
    }
}
