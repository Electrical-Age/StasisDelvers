package org.erisia.stasis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
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
 * Stopped water and lava are solid to entities: nobody swims in still time (Baughn). Both sides compute it (the client
 * from the synced fields), so movement prediction agrees with the server.
 */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockMixin {
    @Inject(method = "getCollisionShape", at = @At("RETURN"), cancellable = true)
    private void stasis$solidWhenStopped(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext context,
                                         CallbackInfoReturnable<VoxelShape> cir) {
        if (context instanceof EntityCollisionContext ec && ec.getEntity() != null
            && !Stasis.isRunning(ec.getEntity().level(), pos)) cir.setReturnValue(Shapes.block());
    }
}
