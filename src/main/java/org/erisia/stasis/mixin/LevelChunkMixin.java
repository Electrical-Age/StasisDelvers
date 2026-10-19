package org.erisia.stasis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block entities tick only in the game ticks their block's rate runs in (never where time is stopped): every ticking
 * block entity asks LevelChunk.isTicking(pos) first. Exempt ones (Stasis.exemptBlockEntity: tower bases, End gateways,
 * EA's light blocks) always tick.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    @Final
    Level level;

    @Inject(method = "isTicking", at = @At("RETURN"), cancellable = true)
    private void stasis$isTicking(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && level instanceof ServerLevel sl && !Stasis.ticksNow(sl, pos)
            && !Stasis.exemptBlockEntity(sl, pos)) cir.setReturnValue(false);
        // client block-entity ticks too (trial spawner hum, campfire smoke): stopped land is still (AdventurAgent)
        else if (cir.getReturnValueZ() && level.isClientSide() && !Stasis.isRunning(level, pos)) cir.setReturnValue(false);
    }
}
