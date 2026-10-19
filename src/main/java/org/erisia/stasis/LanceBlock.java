package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The rift lance's core (L2; docs/lance.md): one block at a rift's rim, under a mast of survey poles with a boom off the
 * mast's top reaching out over the rift's eye (LanceBlockEntity.scan). EA power comes in as FE from EA's exporter, like
 * a tower's. Use a crystal focus on it to put it in (not consumed); sneak-use with an empty hand to take it out; use
 * with an empty hand for its state.
 */
public class LanceBlock extends Block implements EntityBlock {
    public LanceBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LanceBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                          BlockHitResult hit) {
        if (stack.isEmpty()) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(stack.getItem() instanceof RiftFocusItem)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof LanceBlockEntity l)) return InteractionResult.PASS;
        if (!l.focus().isEmpty()) {
            player.sendOverlayMessage(Component.literal("The lance already holds a focus (sneak-use with an empty hand to take it out)."));
            return InteractionResult.SUCCESS_SERVER;
        }
        l.setFocus(stack.copyWithCount(1));
        if (!player.getAbilities().instabuild) stack.shrink(1);
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 0.8f);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof LanceBlockEntity l)) return InteractionResult.PASS;
        if (player.isShiftKeyDown() && !l.focus().isEmpty()) {
            ItemStack f = l.focus();
            l.setFocus(ItemStack.EMPTY);
            if (!player.addItem(f)) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, f);
            return InteractionResult.SUCCESS_SERVER;
        }
        player.sendSystemMessage(l.statusText());
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (l, p, s, be) -> {
            if (be instanceof LanceBlockEntity t) t.serverTick();
        };
    }
}
