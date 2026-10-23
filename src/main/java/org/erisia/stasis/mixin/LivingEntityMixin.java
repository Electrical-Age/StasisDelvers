package org.erisia.stasis.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.erisia.stasis.PlayerRates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A player in a slow field: the server's item-use countdown (eating, drinking, drawing a bow) only
 * advances in the ticks the field's rate runs in, and in an acceleration field several times a tick, matching the
 * player's slowed or sped-up client (otherwise food vanishes with the
 * eating animation half done).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;updatingUsingItem()V"))
    private void stasis$useItemAtFieldRate(LivingEntity self, Operation<Void> op) {
        int k = self instanceof ServerPlayer p ? PlayerRates.ticksThisTurn(p) : 1;
        for (int i = 0; i < k; i++) {
            if (i > 0 && !self.isUsingItem()) return;   // (fast: finished in an earlier pass)
            op.call(self);
        }
    }

    /** A frozen mob doesn't cry out when hit (Baughn). */
    @WrapWithCondition(method = "hurtServer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;playHurtSound(Lnet/minecraft/world/damagesource/DamageSource;)V"))
    private boolean stasis$silentWhenFrozen(LivingEntity self, net.minecraft.world.damagesource.DamageSource source) {
        return !org.erisia.stasis.Stasis.isFrozen(self);
    }
}
