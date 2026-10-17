package org.erisia.stasis.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stopped land is still: no block or fluid ambience there (Baughn: lava popped and crackled in the grey). Torch flames,
 * lava bubbles and their sounds, drips and the like come from the client's random animate ticks; those are skipped
 * where time stands still.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @WrapWithCondition(method = "doAnimateTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/Block;animateTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private boolean stasis$blockAnimate(Block block, BlockState state, Level level, BlockPos pos, RandomSource random) {
        return Stasis.isRunning(level, pos);
    }

    @WrapWithCondition(method = "doAnimateTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/material/FluidState;animateTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private boolean stasis$fluidAnimate(FluidState fluid, Level level, BlockPos pos, RandomSource random) {
        return Stasis.isRunning(level, pos);
    }

    @WrapWithCondition(method = "doAnimateTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;trySpawnDripParticles(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/particles/ParticleOptions;Z)V"))
    private boolean stasis$drips(ClientLevel level, BlockPos below, BlockState state, net.minecraft.core.particles.ParticleOptions particle, boolean watertight) {
        return Stasis.isRunning(level, below.above());
    }

    // rain hangs still in stopped land (WeatherEffectRendererMixin): no splashes there, and no rain sound from there
    // (AdventurAgent: after a collapse froze, the rain went on sounding)
    @WrapWithCondition(method = "tickWeatherEffects", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private boolean stasis$rainSplash(ClientLevel level, net.minecraft.core.particles.ParticleOptions particle, double x, double y, double z,
                                      double xd, double yd, double zd) {
        // (and none under a field's shield: it keeps the weather out, Baughn)
        return Stasis.isRunning(level, BlockPos.containing(x, y, z)) && !org.erisia.stasis.ClientFields.underShield(x, y, z);
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "tickWeatherEffects", require = 2, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
    private void stasis$rainSound(ClientLevel level, BlockPos pos, net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source,
                                  float volume, float pitch, boolean delay,
                                  com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        if (!Stasis.isRunning(level, pos.above())) return;   // (stopped land: the rain hangs still and silent)
        // under a field's shield the rain drums on it, muffled (Baughn)
        var cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position();
        if (org.erisia.stasis.ClientFields.underShield(cam.x, cam.y, cam.z)) {
            volume *= 0.35f;
            pitch *= 0.7f;
        }
        original.call(level, pos, sound, source, volume, pitch, delay);
    }
}
