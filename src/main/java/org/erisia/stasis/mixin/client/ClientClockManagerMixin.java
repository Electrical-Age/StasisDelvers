package org.erisia.stasis.mixin.client;

import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import org.erisia.stasis.client.LocalClockMark;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks the overworld clock's client instance, so its reads give the local time (LocalTime, visual only). */
@Mixin(ClientClockManager.class)
public abstract class ClientClockManagerMixin {
    @Inject(method = "getInstance", at = @At("RETURN"))
    private void stasis$mark(Holder<WorldClock> definition, CallbackInfoReturnable<ClientClockManager.ClientClockInstance> cir) {
        // (instanceof: if ClientClockInstanceMixin didn't apply, this does nothing rather than fail)
        if (definition.is(WorldClocks.OVERWORLD) && cir.getReturnValue() instanceof LocalClockMark m) m.stasis$markOverworld();
    }
}
