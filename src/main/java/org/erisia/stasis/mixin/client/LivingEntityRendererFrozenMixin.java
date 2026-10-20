package org.erisia.stasis.mixin.client;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.erisia.stasis.client.FrozenEntities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A frozen living entity is drawn at exactly its frozen pose: no blend between last tick's rotation and walk phase and
 * this one's. Its client tick is cancelled (FrozenEntities), so the "last tick" values stay where they were when it
 * stopped, and every frame blended between the two: a Puppeteer's host, stopping mid-stride between bursts, flickered its
 * head and limbs back and forth (Baughn).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererFrozenMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL"))
    private void stasis$frozenPose(LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
        if (!FrozenEntities.frozen(entity)) return;
        state.bodyRot = entity.yBodyRot;
        state.yRot = Mth.wrapDegrees(entity.yHeadRot - entity.yBodyRot);
        state.xRot = entity.getXRot();
        if (state.isUpsideDown) {
            state.xRot *= -1.0F;
            state.yRot *= -1.0F;
        }
        if (!entity.isPassenger() && entity.isAlive()) {
            state.walkAnimationPos = entity.walkAnimation.position();
            state.walkAnimationSpeed = entity.walkAnimation.speed();
            state.wornHeadAnimationPos = state.walkAnimationPos;
        }
    }
}
