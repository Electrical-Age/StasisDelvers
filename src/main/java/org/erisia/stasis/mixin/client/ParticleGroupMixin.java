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
    private boolean stasis$particleTick(Particle p) {
        var level = p.level;
        if (level == null || !Stasis.stops(level)) return true;
        int x = (int) Math.floor(p.x), y = (int) Math.floor(p.y), z = (int) Math.floor(p.z);
        if (Stasis.isRunning(level, x, y, z)) return true;
        p.xo = p.x;
        p.yo = p.y;
        p.zo = p.z;
        if (++p.age >= p.lifetime) p.remove();
        return false;
    }
}
