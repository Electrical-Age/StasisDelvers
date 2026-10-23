package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The chronal accelerator (λ-Nero, variant B): a machine set into a wall of a sealed riftstone room. It finds the room
 * (ChronalAcceleratorBlockEntity: a flood fill from its sides), takes FE from outside (an EA exporter against it) and
 * makes the room run, in stopped land too, at its setting: right-click raises it by half (from 10x round to 1x),
 * sneak-right-click with an empty hand lowers it: 1x .. 10x (STEP 0..18). Every click says how the room stands.
 */
public class ChronalAcceleratorBlock extends Block implements EntityBlock {
    public static final IntegerProperty STEP = IntegerProperty.create("step", 0, 18);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public ChronalAcceleratorBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(STEP, 2).setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(STEP, LIT);
    }

    /** The rate a setting stands for: 1 + step / 2. */
    public static double rate(int step) {
        return 1 + step * 0.5;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChronalAcceleratorBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        // up by half a step a click, round from 10x back to 1x (AdventurAgent: a sneak-click with something in hand never
        // reaches the block); a sneak-click with an empty hand goes down
        int step = player.isSecondaryUseActive() ? Math.max(0, state.getValue(STEP) - 1) : (state.getValue(STEP) + 1) % 19;
        if (level instanceof ServerLevel sl && level.getBlockEntity(pos) instanceof ChronalAcceleratorBlockEntity be) {
            level.setBlock(pos, state.setValue(STEP, step), Block.UPDATE_ALL);
            be.refill(sl);
            player.sendOverlayMessage(Component.literal(String.format("Chronal accelerator: %.1fx. ", rate(step)) + be.status()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (l, p, s, be) -> {
            if (be instanceof ChronalAcceleratorBlockEntity a && l instanceof ServerLevel sl) a.serverTick(sl);
        };
    }
}
