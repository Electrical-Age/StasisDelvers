package org.erisia.stasis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block time by rate (Stasis.rateAt; stopped = 0): scheduled block and fluid ticks are delayed by the rate and only
 * run in game ticks their rate runs in; random ticks are skipped in the ticks it skips. Where time is stopped: no
 * scheduled ticks, no random ticks, no snow or ice. In an acceleration field (Stasis.speedAt) scheduled delays shrink
 * and random ticks run several times. A random tick at a field's edge can't write into stopped land (LevelMixin).
 */
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

    /**
     * Scheduled ticks fall due `delay` running ticks from now (overrides LevelAccessor's defaults; at rate 1, and in
     * dimensions that don't stop, exactly vanilla's now + delay).
     */
    public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int tickDelay, TickPriority priority) {
        ServerLevel l = self();
        return new ScheduledTick<>(type, pos, Stasis.dueTick(l, pos, tickDelay), priority, l.nextSubTickCount());
    }

    public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int tickDelay) {
        ServerLevel l = self();
        return new ScheduledTick<>(type, pos, Stasis.dueTick(l, pos, tickDelay), l.nextSubTickCount());
    }

    /**
     * In a column a field touches, check the block: one that doesn't run this tick (stopped, or a slow field's
     * skipped tick, e.g. a tick scheduled before the field's rate changed) gets its tick back for its next running
     * tick (createTick puts delay 1 there; stopped blocks retry every tick).
     */
    @Inject(method = "tickBlock", at = @At("HEAD"), cancellable = true)
    private void stasis$tickBlock(BlockPos pos, Block type, CallbackInfo ci) {
        if (!Stasis.ticksNow(self(), pos)) {
            self().scheduleTick(pos, type, 1);
            ci.cancel();
        }
    }

    @Inject(method = "tickFluid", at = @At("HEAD"), cancellable = true)
    private void stasis$tickFluid(BlockPos pos, Fluid type, CallbackInfo ci) {
        if (!Stasis.ticksNow(self(), pos)) {
            self().scheduleTick(pos, type, 1);
            ci.cancel();
        }
    }

    /** Random ticks, snow and ice: nothing at all in a wholly stopped column (precipitation isn't rate-scaled). */
    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void stasis$tickChunk(LevelChunk chunk, int tickSpeed, CallbackInfo ci) {
        if (!Stasis.mayRunInColumn(self(), chunk.getPos().pack())) ci.cancel();
    }

    /** Random ticks run only when the block's rate says so; in an acceleration field, that many times (λ-Nero). */
    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void stasis$randomTickBlock(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> op) {
        if (!Stasis.ticksNow(level, pos)) return;
        Stasis.randomTicking(level, pos);   // (what it writes into stopped land is refused: LevelMixin)
        try {
            op.call(state, level, pos, random);
            if (!Stasis.hasFast(level)) return;
            int k = Stasis.ticksThisTurn(Stasis.speedAt(level, pos), level.getGameTime());
            for (int i = 1; i < k; i++) {
                BlockState now = level.getBlockState(pos);   // (a crop that grew is the next stage now)
                if (!now.isRandomlyTicking()) return;
                op.call(now, level, pos, random);
            }
        } finally {
            Stasis.randomTicking(level, null);
        }
    }

    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/material/FluidState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void stasis$randomTickFluid(FluidState state, ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> op) {
        if (!Stasis.ticksNow(level, pos)) return;
        Stasis.randomTicking(level, pos);   // (lava lights no fires in stopped land: LevelMixin)
        try {
            op.call(state, level, pos, random);
            if (!Stasis.hasFast(level)) return;
            int k = Stasis.ticksThisTurn(Stasis.speedAt(level, pos), level.getGameTime());
            for (int i = 1; i < k; i++) {
                FluidState now = level.getFluidState(pos);
                if (!now.isRandomlyTicking()) return;
                op.call(now, level, pos, random);
            }
        } finally {
            Stasis.randomTicking(level, null);
        }
    }
}
