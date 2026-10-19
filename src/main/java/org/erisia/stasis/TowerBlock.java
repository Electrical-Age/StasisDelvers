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
 * Tower core blocks (Baughn): the player places 2, one on the other (1x1x2; was 3x3x2 until 2026-10-14, when Baughn
 * dropped it so booms can start as low as the core's top); the moment the second goes in they
 * form a core. Every core block carries a TowerBlockEntity (one type for all, so client and server always agree on
 * it); the bottom centre's (the master's) is the tower: it stores power (FE from EA's exporter) and keeps a field
 * running, and the energy capability of every cell resolves to it, so overlays (Jade) and cables see the store on every
 * face. Breaking any block of a formed core turns the rest back into loose core blocks (the field goes). The mast
 * stands on the top centre. Right-click a formed core to see the tower's state.
 */
public class TowerBlock extends Block implements EntityBlock {
    /** Which cell of a formed core: 0 the master (bottom), 1 the top; LOOSE: a core block not (yet) part of a core.
     *  (States of the old 3x3x2 core, cells up to 18, load as the default: loose; so does the dropped "hidden"
     *  property of the abandoned core hiding.) */
    public static final IntegerProperty CELL = IntegerProperty.create("cell", 0, 2);
    public static final int MASTER = 0, TOP = 1, LOOSE = 2, CELLS = 2;
    public static final int HEIGHT = 2;

    public TowerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(CELL, LOOSE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CELL);
    }

    /** The top block of a formed core (booms may start from its sides). */
    public static boolean isCoreTop(BlockState s) {
        return s.is(StasisRegistry.TOWER.get()) && s.getValue(CELL) == TOP;
    }

    public static boolean formed(BlockState s) {
        return s.getValue(CELL) != LOOSE;
    }

    /** The master (the bottom block) of the core this formed cell belongs to. */
    public static BlockPos master(BlockPos pos, BlockState state) {
        int c = state.getValue(CELL);
        return c == LOOSE ? pos : pos.below(c);
    }

    /** Places a whole formed core with its master at pos, if both places are free (tests, /stasis core). */
    public static boolean placeCore(Level level, BlockPos master) {
        for (int c = 0; c < CELLS; c++) {
            BlockPos p = master.above(c);
            if (!level.isInWorldBounds(p) || !level.getBlockState(p).canBeReplaced()) return false;
        }
        BlockState loose = StasisRegistry.TOWER.get().defaultBlockState();
        for (int c = 0; c < CELLS; c++) level.setBlock(master.above(c), loose, Block.UPDATE_CLIENTS);
        form(level, master);
        return true;
    }

    /** A loose core block was placed: if it completes a stack of two loose core blocks, they form a core. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (level.isClientSide() || old.is(this) || formed(state)) return;
        for (int c = 0; c < CELLS; c++) {
            BlockPos m = pos.below(c);
            if (complete(level, m)) {
                form(level, m);
                return;
            }
        }
    }

    static boolean complete(Level level, BlockPos master) {
        for (int c = 0; c < CELLS; c++) {
            BlockState s = level.getBlockState(master.above(c));
            if (!s.is(StasisRegistry.TOWER.get()) || formed(s)) return false;
        }
        return true;
    }

    static void form(Level level, BlockPos master) {
        BlockState base = StasisRegistry.TOWER.get().defaultBlockState();
        for (int c = 0; c < CELLS; c++) {
            BlockPos p = master.above(c);
            BlockState s = base.setValue(CELL, c);
            level.setBlock(p, s, Block.UPDATE_ALL);
            // a cell formed from a pre-fix loose block may still hold the old proxy entity: make it a tower entity
            if (!(level.getBlockEntity(p) instanceof TowerBlockEntity)) {
                level.removeBlockEntity(p);
                level.setBlockEntity(new TowerBlockEntity(p, s));
            }
        }
        if (level.getBlockEntity(master) instanceof TowerBlockEntity t) {
            // the console faces whoever formed the core (the nearest player): input on its left, output on its right
            var pl = level.getNearestPlayer(master.getX() + 0.5, master.getY() + 0.5, master.getZ() + 0.5, 16, false);
            if (pl != null) {
                double dx = pl.getX() - (master.getX() + 0.5), dz = pl.getZ() - (master.getZ() + 0.5);
                t.facing = net.minecraft.core.Direction.getApproximateNearest(dx, 0, dz);
            }
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
        for (int c = 0; c < CELLS; c++) {
            BlockPos p = master.above(c);
            BlockState s = level.getBlockState(p);
            if (!s.is(this) || !formed(s)) continue;
            level.setBlock(p, loose, Block.UPDATE_ALL);
        }
    }

    /** A core block of a ruined tower (coreDrop < 1, Baughn: 0 in cottages) mostly breaks into scrap instead. */
    @Override
    protected java.util.List<net.minecraft.world.item.ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        if (params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY) instanceof TowerBlockEntity t
            && t.coreDrop < 1 && params.getLevel().getRandom().nextDouble() >= t.coreDrop)
            return java.util.List.of(new net.minecraft.world.item.ItemStack(StasisRegistry.SCRAP.get(), 2 + params.getLevel().getRandom().nextInt(3)));
        return super.getDrops(state, params);
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
        // the console (CONSOLE-PLAN.md C) on a formed core; sneaking: the old status line in chat
        if (level.isClientSide()) {
            if (formed(state) && !player.isShiftKeyDown()) StasisNet.consoleOpener.accept(master(pos, state));
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        if (formed(state) && !player.isShiftKeyDown()) return net.minecraft.world.InteractionResult.SUCCESS_SERVER;
        if (!formed(state)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "Stasis tower core block: place two, one on the other, to form a tower core."));
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
