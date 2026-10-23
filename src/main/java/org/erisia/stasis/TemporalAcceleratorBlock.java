package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The temporal accelerator (λ-Nero: a block placed against the tower's power core): a tower with one beside its core
 * runs its field faster than time (Field.fast; the blocks inside get Stasis.speedAt ticks), for the tower's draw times
 * the rate squared (2x for 4x the power). Right-click raises its setting by half, sneak-right-click lowers it:
 * 1.5x .. 10x (STEP 1..18). Short of power, the tower drops its rate back towards 1x before it shrinks its field
 * (TowerBlockEntity.safety).
 */
public class TemporalAcceleratorBlock extends Block {
    public static final IntegerProperty STEP = IntegerProperty.create("step", 1, 18);

    public TemporalAcceleratorBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(STEP, 2));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(STEP);
    }

    /** The rate a setting stands for: 1 + step / 2. */
    public static double rate(int step) {
        return 1 + step * 0.5;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        int step = Math.max(1, Math.min(18, state.getValue(STEP) + (player.isSecondaryUseActive() ? -1 : 1)));
        if (!level.isClientSide()) {
            level.setBlock(pos, state.setValue(STEP, step), Block.UPDATE_ALL);
            boolean atCore = coreBeside(level, pos) != null;
            player.sendOverlayMessage(Component.literal(String.format("Temporal accelerator: %.1fx", rate(step))
                + (atCore ? "" : " (not against a tower core)")));
        }
        return InteractionResult.SUCCESS;
    }

    /** The formed tower core block next to pos (sideways), or null. */
    static BlockPos coreBeside(Level level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos q = pos.relative(d);
            BlockState s = level.getBlockState(q);
            if (s.is(StasisRegistry.TOWER.get()) && s.getValue(TowerBlock.CELL) != TowerBlock.LOOSE) return q;
        }
        return null;
    }

    /** The rate a tower whose master cell is at master is asked to run at: the highest setting of the accelerators
     *  against its core (both cells, any side), or 1 without one. */
    public static double want(Level level, BlockPos master) {
        double want = 1;
        for (int c = 0; c < TowerBlock.HEIGHT; c++) {
            BlockPos cell = master.above(c);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState s = level.getBlockState(cell.relative(d));
                if (s.getBlock() instanceof TemporalAcceleratorBlock) want = Math.max(want, rate(s.getValue(STEP)));
            }
        }
        return want;
    }
}
