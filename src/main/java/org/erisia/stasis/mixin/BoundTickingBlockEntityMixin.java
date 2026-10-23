package org.erisia.stasis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Block entities in an acceleration field (Stasis.speedAt above 1) tick that many times a game tick: furnaces smelt,
 * hoppers move and brewing stands brew faster (λ-Nero). Each extra pass re-checks that the block entity is still there
 * and valid for its block. Not for Stasis' own machines or EA's (Stasis.multiTickExempt). On the client too, so animations
 * (a campfire's smoke, a spawner's spin) keep the server's pace.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private <T extends BlockEntity> void stasis$fastTicks(BlockEntityTicker<T> ticker, Level level, BlockPos pos, BlockState state, T be, Operation<Void> op) {
        op.call(ticker, level, pos, state, be);
        if (!Stasis.hasFast(level) || Stasis.multiTickExempt(be)) return;   // (both sides: client animations keep pace)
        int k = Stasis.ticksThisTurn(Stasis.speedAt(level, pos), level.getGameTime());
        for (int i = 1; i < k; i++) {
            if (be.isRemoved() || level.getBlockEntity(pos) != be) return;
            BlockState now = level.getBlockState(pos);
            if (!be.getType().isValid(now)) return;
            op.call(ticker, level, pos, now, be);
        }
    }
}
