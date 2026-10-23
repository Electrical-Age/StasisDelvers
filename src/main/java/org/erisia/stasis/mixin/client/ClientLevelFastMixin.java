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
 * the server, so items fall and arrows fly at the server's pace instead of being dragged back at every sync (Argon).
 * Not interpolated entities (mobs: they follow the server's positions anyway). Not players, nor anything a player rides (the player drives it).
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
        // entities the client interpolates towards the server's positions (mobs, minecarts, boats) already move at the
        // server's pace; extra ticks would finish each step early and wait (AdventurAgent saw them jump). Only what the
        // client moves itself (items, falling blocks, arrows) needs them.
        if (entity.getInterpolation() != net.minecraft.world.entity.InterpolationHandler.NO_OP) return;
        int k = Stasis.ticksThisTurn(Stasis.clientSpeedAt(self, entity.blockPosition()), self.getGameTime());   // (over the client's own tick rate)
        if (k <= 1) return;
        stasis$extra = true;
        // the frame's interpolation runs from the old position: keep the one from before this tick's first pass, or it
        // would only span the last pass and jump the rest (AdventurAgent: items fell in bursts)
        double xo = entity.xo, yo = entity.yo, zo = entity.zo;
        float yRotO = entity.yRotO, xRotO = entity.xRotO;
        try {
            for (int i = 1; i < k; i++) {
                if (entity.isRemoved() || entity.isPassenger() || Stasis.speedAt(self, entity.blockPosition()) <= 1) break;
                self.tickNonPassenger(entity);
            }
        } finally {
            stasis$extra = false;
            entity.xo = xo;
            entity.yo = yo;
            entity.zo = zo;
            entity.yRotO = yRotO;
            entity.xRotO = xRotO;
        }
    }
}
