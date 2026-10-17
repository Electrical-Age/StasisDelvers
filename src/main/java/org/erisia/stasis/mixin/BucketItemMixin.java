package org.erisia.stasis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.erisia.stasis.Stasis;
import org.erisia.stasis.StasisRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Water poured in stopped land freezes as it lands: stilled water (it thaws where a field comes). Only water that was
 * there when the world crashed shows the water lines (Baughn; client.WaterLines), so placed water mustn't.
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
    @WrapOperation(method = "emptyContents(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z",
        require = 1, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean stasis$pourFrozen(Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        if (state.getFluidState().is(FluidTags.WATER) && Stasis.stops(level) && !Stasis.isRunning(level, pos))
            state = StasisRegistry.STILLED_WATER_BLOCK.get().defaultBlockState();
        return original.call(level, pos, state, flags);
    }
}
