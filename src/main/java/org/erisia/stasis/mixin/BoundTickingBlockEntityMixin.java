package org.erisia.stasis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The time-stop gate for block entities, at the head of their tick: LevelChunkMixin gates LevelChunk.isTicking, but
 * Lithium's world_border mixin replaces that call, and with it in Incognito's pack furnaces, hoppers and brewing stands
 * ran in stopped land (AdventurAgent, 2026-10-23; LevelChunkMixin stays as the cheap early-out). And:
 * <p>
 * Block entities in an acceleration field (Stasis.speedAt above 1) tick that many times a game tick: furnaces smelt,
 * hoppers move and brewing stands brew faster (λ-Nero). Each extra pass re-checks that the block entity is still there
 * and valid for its block. Not for Stasis' own machines or EA's (Stasis.multiTickExempt). On the client too, so animations
 * (a campfire's smoke, a spawner's spin) keep the server's pace.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
    @Shadow
    @Final
    private BlockEntity blockEntity;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void stasis$timeStop(CallbackInfo ci) {
        BlockEntity be = blockEntity;
        Level level = be.getLevel();
        if (level == null || be.isRemoved()) return;
        BlockPos pos = be.getBlockPos();
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            if (!Stasis.ticksNow(sl, pos) && !Stasis.exemptBlockEntity(sl, pos)) ci.cancel();
        } else if (!Stasis.isRunning(level, pos)) ci.cancel();
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private <T extends BlockEntity> void stasis$fastTicks(BlockEntityTicker<T> ticker, Level level, BlockPos pos, BlockState state, T be, Operation<Void> op) {
        op.call(ticker, level, pos, state, be);
        if (!Stasis.hasFast(level) || Stasis.multiTickExempt(be)) return;   // (both sides: client animations keep pace)
        int k = Stasis.ticksThisTurn(Stasis.clientSpeedAt(level, pos), level.getGameTime());   // (the client: over its own tick rate)
        for (int i = 1; i < k; i++) {
            if (be.isRemoved() || level.getBlockEntity(pos) != be) return;
            BlockState now = level.getBlockState(pos);
            if (!be.getType().isValid(now)) return;
            op.call(ticker, level, pos, now, be);
        }
    }
}
