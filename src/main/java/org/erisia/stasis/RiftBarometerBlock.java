package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * The rift barometer (crystal uses; λ-Nero's early storm detection): a charged crystal in a riftstone case that
 * feels a coming storm. Its redstone output (direct and comparator) rises through the storm's warning (up to 4) and
 * the storm itself (to 15). Like all redstone, it only works where time runs (a field).
 */
public class RiftBarometerBlock extends Block {
    public static final IntegerProperty POWER = BlockStateProperties.POWER;

    public RiftBarometerBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(POWER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(POWER);
    }

    static int reading(Level level) {
        int seed = Deeps.seed(level);
        if (seed == 0) return 0;
        double t = Deeps.realTime(level);
        double[] ov = Storms.override(level);
        double i = Storms.intensity(seed, ov, t), w = Storms.warning(seed, ov, t);
        return i > 0 ? (int) Math.round(4 + 11 * i) : (int) Math.round(4 * w);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        if (!level.isClientSide()) level.scheduleTick(pos, this, 20);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        int r = reading(level);
        if (r != state.getValue(POWER)) {
            level.setBlock(pos, state.setValue(POWER, r), Block.UPDATE_ALL);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        level.scheduleTick(pos, this, 20);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
        return state.getValue(POWER);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction dir) {
        return state.getValue(POWER);
    }
}
