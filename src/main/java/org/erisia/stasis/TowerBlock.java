package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jspecify.annotations.Nullable;

/**
 * Tower core blocks (Baughn): the player places 18 of them, 3x3 wide and 2 high; the moment the last one goes in they
 * form a core. Every core block carries a TowerBlockEntity (one type for all, so client and server always agree on
 * it); the bottom centre's (the master's) is the tower: it stores power (FE from EA's exporter) and keeps a field
 * running, and the energy capability of every cell resolves to it, so overlays (Jade) and cables see the store on every
 * face. Breaking any block of a formed core turns the rest back into loose core blocks (the field goes). The mast
 * stands on the top centre. Right-click a formed core to see the tower's state.
 */
public class TowerBlock extends Block implements EntityBlock {
    /** Which of the 18 cells of a formed core: ((dy * 3) + dz + 1) * 3 + dx + 1, offsets from the master (bottom
     *  centre); LOOSE: a core block not (yet) part of a core. */
    public static final IntegerProperty CELL = IntegerProperty.create("cell", 0, 18);
    public static final int MASTER = 4, LOOSE = 18;
    public static final int HEIGHT = 2;

    public TowerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(CELL, LOOSE));
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

    public static boolean formed(BlockState s) {
        return s.getValue(CELL) != LOOSE;
    }

    /** The master (bottom centre) of the core this formed cell belongs to. */
    public static BlockPos master(BlockPos pos, BlockState state) {
        int c = state.getValue(CELL);
        return c == LOOSE ? pos : pos.offset(-dx(c), -dy(c), -dz(c));
    }

    /** Places a whole formed core with its master at pos, if all 18 places are free (tests, /stasis core). */
    public static boolean placeCore(Level level, BlockPos master, @Nullable Object unused) {
        for (int c = 0; c < 18; c++) {
            BlockPos p = master.offset(dx(c), dy(c), dz(c));
            if (!level.isInWorldBounds(p) || !level.getBlockState(p).canBeReplaced()) return false;
        }
        BlockState loose = StasisRegistry.TOWER.get().defaultBlockState();
        for (int c = 0; c < 18; c++) level.setBlock(master.offset(dx(c), dy(c), dz(c)), loose, Block.UPDATE_CLIENTS);
        form(level, master);
        return true;
    }

    /** A loose core block was placed: if it completes a 3x3x2 of loose core blocks, they form a core. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (level.isClientSide() || old.is(this) || formed(state)) return;
        for (int c = 0; c < 18; c++) {
            BlockPos m = pos.offset(-dx(c), -dy(c), -dz(c));
            if (complete(level, m)) {
                form(level, m);
                return;
            }
        }
    }

    static boolean complete(Level level, BlockPos master) {
        for (int c = 0; c < 18; c++) {
            BlockState s = level.getBlockState(master.offset(dx(c), dy(c), dz(c)));
            if (!s.is(StasisRegistry.TOWER.get()) || formed(s)) return false;
        }
        return true;
    }

    static void form(Level level, BlockPos master) {
        BlockState base = StasisRegistry.TOWER.get().defaultBlockState();
        for (int c = 0; c < 18; c++) {
            BlockPos p = master.offset(dx(c), dy(c), dz(c));
            BlockState s = base.setValue(CELL, c);
            level.setBlock(p, s, Block.UPDATE_ALL);
            // a cell formed from a pre-fix loose block may still hold the old proxy entity: make it a tower entity
            if (!(level.getBlockEntity(p) instanceof TowerBlockEntity)) {
                level.removeBlockEntity(p);
                level.setBlockEntity(new TowerBlockEntity(p, s));
            }
        }
        if (level.getBlockEntity(master) instanceof TowerBlockEntity t) {
            t.onLoad();
            t.setChanged();
            level.sendBlockUpdated(master, base.setValue(CELL, MASTER), base.setValue(CELL, MASTER), Block.UPDATE_CLIENTS);
        }
        StasisDelvers.LOG.info("Tower core formed at {}", master.toShortString());
    }

    /** A block of a formed core is gone: the rest fall back to loose core blocks, and the tower stops. */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state, level, pos, moved);
        if (!formed(state)) return;
        BlockPos master = master(pos, state);
        if (level.getBlockEntity(master) instanceof TowerBlockEntity t) t.unform();
        BlockState loose = StasisRegistry.TOWER.get().defaultBlockState();
        for (int c = 0; c < 18; c++) {
            BlockPos p = master.offset(dx(c), dy(c), dz(c));
            BlockState s = level.getBlockState(p);
            if (!s.is(this) || !formed(s)) continue;
            level.setBlock(p, loose, Block.UPDATE_ALL);
        }
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TowerBlockEntity(pos, state);
    }

    /** With something in hand (a pole to stack, a core block to place) the item does its thing; the status is for an
     *  empty hand (AdventurAgent: a pole used on the core showed the status instead of stacking). */
    @Override
    protected net.minecraft.world.InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level,
            BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        return stack.isEmpty() ? net.minecraft.world.InteractionResult.TRY_WITH_EMPTY_HAND : net.minecraft.world.InteractionResult.PASS;
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                                                   net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide()) return net.minecraft.world.InteractionResult.SUCCESS;
        if (!formed(state)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "Stasis tower core block: place 18 of them, 3x3 wide and 2 high, to form a tower core."));
        } else if (level.getBlockEntity(master(pos, state)) instanceof TowerBlockEntity t) player.sendSystemMessage(t.statusText());
        return net.minecraft.world.InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || state.getValue(CELL) != MASTER) return null;
        return (l, p, s, be) -> {
            if (be instanceof TowerBlockEntity t) t.serverTick();
        };
    }
}
