package org.erisia.stasis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.erisia.stasis.Flames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Flames get random ticks, which sometimes make them gutter (Flames). */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
    @Inject(method = "isRandomlyTicking", at = @At("HEAD"), cancellable = true)
    private void stasis$flamesTick(CallbackInfoReturnable<Boolean> cir) {
        if (Flames.isFlame((BlockState) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "randomTick", at = @At("HEAD"))
    private void stasis$gutter(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        Flames.randomTick((BlockState) (Object) this, level, pos, random);
    }
}
