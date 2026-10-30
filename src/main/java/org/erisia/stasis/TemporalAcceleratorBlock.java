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
 * the square of the speed-up over its own field's rate (Baughn: 0.6 to 1.5 is 2.5²). Its lever (or the tower console's
 * slider) sets it, 1x .. 10x (STEP 0..18; Baughn 2026-10-30: 1x simply lifts the field's slowness):
 * AcceleratorLevers. Short of power, the tower drops its rate back towards 1x before it shrinks its field
 * (TowerBlockEntity.safety).
 */
public class TemporalAcceleratorBlock extends Block implements net.minecraft.world.level.block.EntityBlock {
    public static final IntegerProperty STEP = IntegerProperty.create("step", 0, 18);

    public TemporalAcceleratorBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(STEP, 2));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(STEP);
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TemporalAcceleratorBlockEntity(pos, state);
    }

    /** The rate a setting stands for: 1 + step / 2. */
    public static double rate(int step) {
        return 1 + step * 0.5;
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
     *  against its core (both cells, any side), or its own field's rate without one. */
    public static double want(Level level, BlockPos master) {
        int step = step(level, master);
        return step < 0 ? StasisConfig.towerFieldRate() : Math.max(StasisConfig.towerFieldRate(), rate(step));
    }

    /** The highest setting of the accelerators against the core whose master cell is at master, or -1 without one. */
    public static int step(Level level, BlockPos master) {
        int step = -1;
        for (BlockPos p : against(level, master)) step = Math.max(step, level.getBlockState(p).getValue(STEP));
        return step;
    }

    /** Set every accelerator against the core to step (the tower console's slider). */
    public static void setStep(Level level, BlockPos master, int step) {
        int s = Math.max(0, Math.min(18, step));
        for (BlockPos p : against(level, master)) level.setBlock(p, level.getBlockState(p).setValue(STEP, s), Block.UPDATE_ALL);
    }

    static java.util.List<BlockPos> against(Level level, BlockPos master) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int c = 0; c < TowerBlock.HEIGHT; c++) {
            BlockPos cell = master.above(c);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos q = cell.relative(d);
                if (level.getBlockState(q).getBlock() instanceof TemporalAcceleratorBlock) out.add(q);
            }
        }
        return out;
    }
}
