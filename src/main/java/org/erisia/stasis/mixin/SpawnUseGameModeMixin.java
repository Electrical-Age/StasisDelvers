package org.erisia.stasis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.erisia.stasis.SpawnUse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Under spawn protection (SpawnUseMixin): a usable block is used as with an empty hand, and the held item does nothing. */
@Mixin(ServerPlayerGameMode.class)
public abstract class SpawnUseGameModeMixin {
    private static final String USE = "useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;";

    @WrapOperation(method = USE, require = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/ItemStack;onItemUseFirst(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult stasis$noItemFirst(ItemStack stack, UseOnContext ctx, Operation<InteractionResult> original) {
        return SpawnUse.BLOCK_ONLY.get() ? InteractionResult.PASS : original.call(stack, ctx);
    }

    @WrapOperation(method = USE, require = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;useItemOn(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult stasis$emptyHandUse(BlockState state, ItemStack stack, Level level, Player player, InteractionHand hand, BlockHitResult hit,
                                                  Operation<InteractionResult> original) {
        return SpawnUse.BLOCK_ONLY.get() ? InteractionResult.TRY_WITH_EMPTY_HAND : original.call(state, stack, level, player, hand, hit);
    }

    @WrapOperation(method = USE, require = 1, allow = 2, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult stasis$noItemUse(ItemStack stack, UseOnContext ctx, Operation<InteractionResult> original) {
        return SpawnUse.BLOCK_ONLY.get() ? InteractionResult.PASS : original.call(stack, ctx);
    }
}
