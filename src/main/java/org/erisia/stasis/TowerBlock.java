package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jspecify.annotations.Nullable;

/**
 * The stasis tower's core: 3x3 wide and 2 high (Baughn), placed and broken as one, like a door. The item places it
 * with the clicked spot as the bottom centre; that cell (the master) holds the block entity, which stores power (FE
 * from EA's exporter, on any cell) and keeps a field running. The mast stands on the top centre.
 */
public class TowerBlock extends Block implements EntityBlock {
    /** Which of the 18 cells: ((dy * 3) + dz + 1) * 3 + dx + 1, offsets from the master (bottom centre). */
    public static final IntegerProperty CELL = IntegerProperty.create("cell", 0, 17);
    public static final int MASTER = 4;
    public static final int HEIGHT = 2;

    public TowerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(CELL, MASTER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CELL);
    }

    static int cell(int dx, int dy, int dz) {
        return (dy * 3 + dz + 1) * 3 + dx + 1;
    }

    static int dx(int cell) {
        return cell % 3 - 1;
    }

    static int dz(int cell) {
        return (cell / 3) % 3 - 1;
    }

    static int dy(int cell) {
        return cell / 9;
    }

    /** The master (bottom centre) of the core this cell belongs to. */
    public static BlockPos master(BlockPos pos, BlockState state) {
        int c = state.getValue(CELL);
        return pos.offset(-dx(c), -dy(c), -dz(c));
    }

    /** Places a whole core with its master at pos, if all 18 places are free (also for tests and commands). */
    public static boolean placeCore(Level level, BlockPos master, @Nullable BlockPlaceContext ctx) {
        for (int c = 0; c < 18; c++) {
            BlockPos p = master.offset(dx(c), dy(c), dz(c));
            BlockState s = level.getBlockState(p);
            if (!(ctx != null ? s.canBeReplaced(ctx) : s.canBeReplaced()) || !level.isInWorldBounds(p)) return false;
        }
        BlockState base = StasisRegistry.TOWER.get().defaultBlockState();
        for (int c = 0; c < 18; c++) {
            if (c == MASTER) continue;
            level.setBlock(master.offset(dx(c), dy(c), dz(c)), base.setValue(CELL, c), Block.UPDATE_CLIENTS);
        }
        level.setBlock(master, base.setValue(CELL, MASTER), Block.UPDATE_ALL);
        return true;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos m = ctx.getClickedPos();
        Level level = ctx.getLevel();
        for (int c = 0; c < 18; c++) {
            BlockPos p = m.offset(dx(c), dy(c), dz(c));
            if (!level.isInWorldBounds(p) || !level.getBlockState(p).canBeReplaced(ctx)) return null;
        }
        return defaultBlockState().setValue(CELL, MASTER);
    }

    /** The item placed the master: fill in the other 17 cells. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        if (level.isClientSide()) return;
        for (int c = 0; c < 18; c++) {
            if (c == MASTER) continue;
            level.setBlock(pos.offset(dx(c), dy(c), dz(c)), state.setValue(CELL, c), Block.UPDATE_ALL);
        }
    }

    /** A cell whose neighbour in the core is gone goes too, so breaking one cell takes the whole core. */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction dir,
                                     BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        int c = state.getValue(CELL);
        int nx = dx(c) + dir.getStepX(), ny = dy(c) + dir.getStepY(), nz = dz(c) + dir.getStepZ();
        boolean inCore = nx >= -1 && nx <= 1 && nz >= -1 && nz <= 1 && ny >= 0 && ny < HEIGHT;
        if (inCore && !(neighbourState.is(this) && neighbourState.getValue(CELL) == cell(nx, ny, nz)))
            return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, level, ticks, pos, dir, neighbourPos, neighbourState, random);
    }

    /** Breaking any cell breaks the master, which drops the core (its loot table drops only for the master). */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && state.getValue(CELL) != MASTER) {
            BlockPos m = master(pos, state);
            if (level.getBlockState(m).is(this)) level.destroyBlock(m, !player.isCreative(), player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(CELL) == MASTER ? new TowerBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || state.getValue(CELL) != MASTER) return null;
        return (l, p, s, be) -> {
            if (be instanceof TowerBlockEntity t) t.serverTick();
        };
    }
}
