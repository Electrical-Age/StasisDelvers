package org.erisia.stasis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stopped liquid in a block that isn't a LiquidBlock (EA reservoir surface cells, seagrass, kelp, bubble columns) is
 * solid too, as LiquidBlockMixin makes plain water and lava (Baughn 2026-10-29: stilled reservoirs weren't walkable).
 * Only cells with no collision of their own: a waterlogged stair keeps its shape.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class FluidCellCollisionMixin {
    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("RETURN"), cancellable = true)
    private void stasis$solidWhenStopped(BlockGetter getter, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (((BlockState) (Object) this).getFluidState().isEmpty() || !cir.getReturnValue().isEmpty()) return;
        // (as LiquidBlockMixin: whoever is already in it may move through it)
        if (context instanceof EntityCollisionContext ec && ec.getEntity() != null && !ec.getEntity().isInLiquid()
            && !Stasis.isRunning(ec.getEntity().level(), pos)) cir.setReturnValue(Shapes.block());
    }
}
