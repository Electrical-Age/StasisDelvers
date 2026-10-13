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
}
