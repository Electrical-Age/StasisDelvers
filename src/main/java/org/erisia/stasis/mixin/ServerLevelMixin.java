package org.erisia.stasis.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Where time is stopped: no scheduled block or fluid ticks, no random ticks, no snow or ice. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    private ServerLevel self() {
        return (ServerLevel) (Object) this;
    }

    /**
     * Scheduled ticks (LevelTicks) skip chunk columns this says no to and keep their ticks queued. A column with no
     * field in it is wholly stopped, so its ticks simply wait.
     */
    @Inject(method = "isPositionTickingWithEntitiesLoaded", at = @At("RETURN"), cancellable = true)
    private void stasis$columnTicks(long chunkKey, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !Stasis.mayRunInColumn(self(), chunkKey)) cir.setReturnValue(false);
    }

    /** In a column a field touches, check the block: a stopped one gets its tick back for the next game tick. */
    @Inject(method = "tickBlock", at = @At("HEAD"), cancellable = true)
    private void stasis$tickBlock(BlockPos pos, Block type, CallbackInfo ci) {
        if (!Stasis.isRunning(self(), pos)) {
            self().scheduleTick(pos, type, 1);
            ci.cancel();
        }
    }

    @Inject(method = "tickFluid", at = @At("HEAD"), cancellable = true)
    private void stasis$tickFluid(BlockPos pos, Fluid type, CallbackInfo ci) {
        if (!Stasis.isRunning(self(), pos)) {
            self().scheduleTick(pos, type, 1);
            ci.cancel();
        }
    }

    /** Random ticks, snow and ice: nothing at all in a wholly stopped column. */
    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void stasis$tickChunk(LevelChunk chunk, int tickSpeed, CallbackInfo ci) {
        if (!Stasis.mayRunInColumn(self(), chunk.getPos().pack())) ci.cancel();
    }

    @WrapWithCondition(method = "tickChunk", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private boolean stasis$randomTickBlock(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        return Stasis.isRunning(level, pos);
    }

    @WrapWithCondition(method = "tickChunk", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/material/FluidState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private boolean stasis$randomTickFluid(FluidState state, ServerLevel level, BlockPos pos, RandomSource random) {
        return Stasis.isRunning(level, pos);
    }
}
