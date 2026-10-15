package org.erisia.stasis.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Particles in stopped land hold still (Baughn: campfire smoke kept drifting in the grey; smoke spawned before the
 * stop, or blown in, kept moving). They still age, so they fade where they hang rather than pile up for good.
 */
@Mixin(ParticleGroup.class)
public abstract class ParticleGroupMixin {
    @WrapWithCondition(method = "tickParticle", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;tick()V"))
    private boolean stasis$particleTick(Particle particle) {
        ParticleAccessor p = (ParticleAccessor) particle;
        var level = p.stasis$level();
        if (level == null || !Stasis.stops(level)) return true;
        int x = (int) Math.floor(p.stasis$x()), y = (int) Math.floor(p.stasis$y()), z = (int) Math.floor(p.stasis$z());
        if (Stasis.isRunning(level, x, y, z)) return true;
        p.stasis$setXo(p.stasis$x());
        p.stasis$setYo(p.stasis$y());
        p.stasis$setZo(p.stasis$z());
        int age = p.stasis$age() + 1;
        p.stasis$setAge(age);
        if (age >= p.stasis$lifetime()) particle.remove();
        return false;
    }
}
