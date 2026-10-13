package org.erisia.stasis.mixin.client;

import net.minecraft.client.ClientClockManager;
import org.erisia.stasis.client.LocalClock;
import org.erisia.stasis.client.LocalClockMark;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The client's overworld clock reads as the local time of day where the player stands (sky, sun, moon, light): frozen
 * in stopped land, the field's own clock in a field (Baughn + λ-Nero; visual only, the server's clock is untouched).
 */
@Mixin(ClientClockManager.ClientClockInstance.class)
public abstract class ClientClockInstanceMixin implements LocalClockMark {
    @Shadow
    private long totalTicks;
    @Shadow
    private float partialTick;
    @Unique
    private boolean stasis$overworld;

    @Override
    public void stasis$markOverworld() {
        stasis$overworld = true;
    }

    @Inject(method = "totalTicks", at = @At("RETURN"), cancellable = true)
    private void stasis$localTicks(CallbackInfoReturnable<Long> cir) {
        if (!stasis$overworld) return;
        double l = LocalClock.local(totalTicks, partialTick);
        if (!Double.isNaN(l)) cir.setReturnValue((long) Math.floor(l));
    }

    @Inject(method = "partialTick", at = @At("RETURN"), cancellable = true)
    private void stasis$localPartial(CallbackInfoReturnable<Float> cir) {
        if (!stasis$overworld) return;
        double l = LocalClock.local(totalTicks, partialTick);
        if (!Double.isNaN(l)) cir.setReturnValue((float) (l - Math.floor(l)));
    }
}
