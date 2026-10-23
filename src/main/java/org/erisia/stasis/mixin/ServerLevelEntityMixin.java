package org.erisia.stasis.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entities in an acceleration field (Stasis.speedAt above 1) get that many ticks a game tick: they grow, breed, burn
 * and despawn faster, and items fall and age faster (λ-Nero). The extra passes go through tickNonPassenger itself
 * (EntityTickEvent, old position, riders), guarded against re-entry; each re-checks that the entity is still there and
 * still in the field. Not players (PlayerRates), not passengers (their vehicle carries them).
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityMixin {
    @Unique
    private boolean stasis$extra;

    @Inject(method = "tickNonPassenger", at = @At("TAIL"))
    private void stasis$fastTicks(Entity entity, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (stasis$extra || entity instanceof Player || !Stasis.hasFast(self)) return;
        int k = Stasis.ticksThisTurn(Stasis.speedAt(self, entity.blockPosition()), self.getGameTime());
        if (k <= 1) return;
        stasis$extra = true;
        try {
            for (int i = 1; i < k; i++) {
                if (entity.isRemoved() || entity.isPassenger() || entity.level() != self || Stasis.speedAt(self, entity.blockPosition()) <= 1) break;
                self.tickNonPassenger(entity);
            }
        } finally {
            stasis$extra = false;
        }
    }
}
