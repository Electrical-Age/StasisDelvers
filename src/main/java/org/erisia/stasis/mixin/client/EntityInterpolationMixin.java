package org.erisia.stasis.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import org.erisia.stasis.client.SlowClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Inside a slow field the client ticks slower than the server, which still moves other entities at 20 TPS: they were
 * drawn creeping, then snapped at each position update (AdventurAgent). Their interpolation towards the server's
 * positions takes as many steps per client tick as real time needs (SlowClient.steps), so they move at real speed.
 */
@Mixin(Entity.class)
public abstract class EntityInterpolationMixin {
    @WrapOperation(method = "commonTick", require = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/InterpolationHandler;interpolate()V"))
    private void stasis$realTimeInterpolation(InterpolationHandler handler, Operation<Void> original) {
        int n = SlowClient.steps();
        for (int i = 0; i < n; i++) original.call(handler);
    }
}
