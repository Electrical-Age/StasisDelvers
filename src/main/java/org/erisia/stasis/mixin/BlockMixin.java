package org.erisia.stasis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standing on stopped lava hurts like a magma block (no fire: the heat doesn't move either). Flames' light depends on
 * where they are (Flames): these override NeoForge's IBlockExtension defaults.
 */
@Mixin(Block.class)
public abstract class BlockMixin {
    public int getLightEmission(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return org.erisia.stasis.Flames.emission(state, level, pos);
    }

    public boolean hasDynamicLightEmission(BlockState state) {
        return org.erisia.stasis.Flames.isFlame(state);
    }

    @Inject(method = "stepOn", at = @At("HEAD"))
    private void stasis$stoppedLava(Level level, BlockPos pos, BlockState onState, Entity entity, CallbackInfo ci) {
        if ((Object) this instanceof LiquidBlock && !level.isClientSide() && onState.getFluidState().is(FluidTags.LAVA)
            && entity instanceof LivingEntity && !entity.isSteppingCarefully() && !Stasis.isRunning(level, pos))
            entity.hurt(level.damageSources().hotFloor(), 1.0F);
    }
}
