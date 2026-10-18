package org.erisia.stasis.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A push on a frozen entity moves it one tick's worth, without momentum (StasisEvents.pushFrozen). */
@Mixin(Entity.class)
public abstract class EntityPushMixin {
    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
    private void stasis$pushFrozen(double x, double y, double z, CallbackInfo ci) {
        if (org.erisia.stasis.StasisEvents.pushFrozen((Entity) (Object) this, x, y, z)) ci.cancel();
    }
}
