package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The rift quern's table (QuernBlockEntity). Use a rift shard (plain or charged) on it to queue it; sneak-use with an
 * empty hand to take the last queued one back; use with an empty hand for its state.
 */
public class QuernBlock extends Block implements EntityBlock {
    /** The table: a top 3 px thick at 12-15 px and four legs. */
    static final VoxelShape SHAPE = Shapes.or(Block.box(0, 12, 0, 16, 15, 16), Block.box(1, 0, 1, 4, 12, 4), Block.box(12, 0, 1, 15, 12, 4),
        Block.box(1, 0, 12, 4, 12, 15), Block.box(12, 0, 12, 15, 12, 15));

    public QuernBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new QuernBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                          BlockHitResult hit) {
        if (stack.isEmpty()) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!QuernBlockEntity.isFuel(stack)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof QuernBlockEntity q)) return InteractionResult.PASS;
        if (!q.offer(stack)) {
            player.sendOverlayMessage(Component.literal("The quern's queue is full."));
            return InteractionResult.SUCCESS_SERVER;
        }
        if (!player.getAbilities().instabuild) stack.shrink(1);
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1.2f);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof QuernBlockEntity q)) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            ItemStack s = q.takeLast();
            if (!s.isEmpty()) {
                if (!player.addItem(s)) net.minecraft.world.Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, s);
                return InteractionResult.SUCCESS_SERVER;
            }
        }
        player.sendSystemMessage(q.statusText());
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** Comparators read the queue (0..4 shards, scaled to 0..15). */
    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, net.minecraft.core.Direction direction) {
        if (!(level.getBlockEntity(pos) instanceof QuernBlockEntity q)) return 0;
        int n = 0;
        for (int i = 0; i < QuernBlockEntity.QUEUE; i++) if (!q.queued(i).isEmpty()) n++;
        return n * 15 / QuernBlockEntity.QUEUE;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (l, p, s, be) -> {
            if (be instanceof QuernBlockEntity q && l instanceof ServerLevel sl) q.serverTick(sl);
        };
    }
}
