package org.erisia.stasis.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client's half of ServerLevelEntityMixin (cosmetic: without it only the look suffers): entities in an acceleration field tick as many times on the client as on
 * the server, so items fall, mobs walk and animate at the server's pace instead of being dragged back at every sync
 * (Argon). Not players, nor anything a player rides (the player drives it).
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelFastMixin {
    @Unique
    private boolean stasis$extra;

    @Inject(method = "tickNonPassenger", at = @At("TAIL"))
    private void stasis$fastTicks(Entity entity, CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (stasis$extra || entity instanceof Player || !Stasis.hasFast(self)) return;
        for (Entity p : entity.getIndirectPassengers()) if (p instanceof Player) return;
        int k = Stasis.ticksThisTurn(Stasis.speedAt(self, entity.blockPosition()), self.getGameTime());
        if (k <= 1) return;
        stasis$extra = true;
        try {
            for (int i = 1; i < k; i++) {
                if (entity.isRemoved() || entity.isPassenger() || Stasis.speedAt(self, entity.blockPosition()) <= 1) break;
                self.tickNonPassenger(entity);
            }
        } finally {
            stasis$extra = false;
        }
    }
}
