package org.erisia.stasis.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.erisia.stasis.StasisRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A frozen boat doesn't hold its rider's view to ±105° of its own heading (Baughn: overtaken by a deep in a boat, the
 * camera "insisted on looking in a specific direction"; AdventurAgent: the frozen boat can't turn, so vanilla's clamp
 * pinned the view to one side). Only the body follows the boat; the player looks where they like.
 */
@Mixin(AbstractBoat.class)
public abstract class BoatClampMixin {
    @Inject(method = "clampRotation", at = @At("HEAD"), cancellable = true)
    private void stasis$frozenBoat(Entity passenger, CallbackInfoReturnable<Float> cir) {
        AbstractBoat self = (AbstractBoat) (Object) this;
        // (or the rider is held or freezing: a deep's hold leaves the boat running but unsteerable; AdventurAgent)
        boolean held = passenger instanceof net.minecraft.server.level.ServerPlayer sp ? org.erisia.stasis.Freezing.rate(sp) < 1
            : passenger instanceof Player && self.level().isClientSide() && org.erisia.stasis.StasisNet.clientFreeze > 0;
        if (passenger instanceof Player && (held || self.hasData(StasisRegistry.FROZEN.get()) && self.getData(StasisRegistry.FROZEN.get()))) {
            passenger.setYBodyRot(self.getYRot());
            cir.setReturnValue(0f);
            return;
        }
        // just after a hold or a freeze ends the view may lie past 105 degrees: the boat turns to it instead of the view
        // snapping back (AdventurAgent: a jolt as the clamp returned)
        if (passenger instanceof Player) {
            float delta = net.minecraft.util.Mth.wrapDegrees(passenger.getYRot() - self.getYRot());
            if (Math.abs(delta) > 105) {
                float turn = delta - Math.copySign(105f, delta);
                self.setYRot(self.getYRot() + turn);
                self.yRotO += turn;
            }
        }
    }
}
