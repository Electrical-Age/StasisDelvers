package org.erisia.stasis.mixin.client;

import java.util.Map;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.erisia.stasis.client.AntiSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The anti-sound's pitch sag inside a failing field (client.AntiSound), and access to the playing channels. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin implements AntiSound.Engine {
    @Accessor("instanceToChannel")
    public abstract Map<SoundInstance, ChannelAccess.ChannelHandle> stasis$channels();

    @Inject(method = "calculatePitch", at = @At("RETURN"), cancellable = true)
    private void stasis$sag(SoundInstance instance, CallbackInfoReturnable<Float> cir) {
        if (AntiSound.affects(instance.getSource())) cir.setReturnValue(cir.getReturnValueF() * AntiSound.pitch());
    }
}
