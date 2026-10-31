package org.erisia.stasis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A random tick in running land doesn't write into stopped land (Stasis.refusesWrite): bamboo, sugar cane, cactus and
 * kelp at a field's edge grew their new top into the stopped block above, vines and grass spread out, lava lit fires
 * (Baughn: "bamboo grows slightly inside stopped land"). Every setBlock overload ends here; ServerLevel doesn't
 * override it.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"), cancellable = true)
    private void stasis$noGrowthIntoStoppedLand(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
        if (Stasis.refusesWrite((Level) (Object) this, pos)) cir.setReturnValue(false);
    }
}
