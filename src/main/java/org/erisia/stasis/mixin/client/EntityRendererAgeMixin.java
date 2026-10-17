package org.erisia.stasis.mixin.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.erisia.stasis.client.FrozenEntities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A frozen entity's idle animations (keyed to its age plus the frame's partial tick) stand still (FrozenEntities). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererAgeMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
        at = @At("TAIL"))
    private void stasis$frozenAge(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
        if (FrozenEntities.frozen(entity)) state.ageInTicks = entity.tickCount;
    }
}
