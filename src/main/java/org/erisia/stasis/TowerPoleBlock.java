package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A survey pole: the player's 1-wide sketch of the tower. Vertical poles stacked on the base are the mast, horizontal
 * ones the booms. It behaves like scaffolding (Baughn, msg 1559520240113680406): you walk on its top, climb the mast
 * from inside, and a pole used on a pole extends the line (PoleItem). After the raise, the mast column becomes the
 * tower's ladder well.
 */
public class TowerPoleBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;

    static final VoxelShape Y_SHAPE = Block.box(5, 0, 5, 11, 16, 11);
    static final VoxelShape X_SHAPE = Block.box(0, 5, 5, 16, 11, 11);
    static final VoxelShape Z_SHAPE = Block.box(5, 5, 0, 11, 11, 16);
    /** What you stand on: a thin deck at the top, like scaffolding's. */
    static final VoxelShape DECK = Block.box(0, 14, 0, 16, 16, 16);

    public TowerPoleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.Y));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    public static boolean isMast(BlockState s) {
        return s.is(StasisRegistry.TOWER_SEGMENT.get()) && s.getValue(AXIS) == Direction.Axis.Y;
    }

    /** Horizontal when placed against the side of another pole (a boom), else vertical. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction face = ctx.getClickedFace();
        BlockState behind = ctx.getLevel().getBlockState(ctx.getClickedPos().relative(face.getOpposite()));
        Direction.Axis axis = face.getAxis().isHorizontal() && behind.is(this) ? face.getAxis() : Direction.Axis.Y;
        return defaultBlockState().setValue(AXIS, axis);
    }

    /** A pole in hand replaces the clicked pole, so PoleItem sees the pole itself as the clicked position (as scaffolding does). */
    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext ctx) {
        return ctx.getItemInHand().is(asItem());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(AXIS)) {
            case X -> X_SHAPE;
            case Y -> Y_SHAPE;
            case Z -> Z_SHAPE;
        };
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        if (ctx.isPlacement()) return Shapes.empty();
        return ctx.isAbove(Shapes.block(), pos, true) && !ctx.isDescending() ? DECK : Shapes.empty();
    }

    @Override
    public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) {
        return state.getValue(AXIS) == Direction.Axis.Y;
    }

    @Override
    public boolean isScaffolding(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) {
        return state.getValue(AXIS) == Direction.Axis.Y;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        Direction.Axis a = state.getValue(AXIS);
        if (a == Direction.Axis.Y || rotation == Rotation.NONE || rotation == Rotation.CLOCKWISE_180) return state;
        return state.setValue(AXIS, a == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
    }
}
