package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Survey pole item. Used on a pole it extends the line, like scaffolding:
 * <ul>
 * <li>on the mast: places at the top of the mast, so you can build it from the ground;</li>
 * <li>on a boom: places at the boom's outer end (the end away from the mast);</li>
 * <li>sneaking: goes out from the clicked face instead, which starts a boom on the mast, or extends the boom
 * already there.</li>
 * </ul>
 * Anywhere else it is a plain block.
 */
public class PoleItem extends BlockItem {
    /** How far along a line it will look for the free end. */
    static final int MAX_RUN = 128;

    public PoleItem(TowerPoleBlock block, Properties properties) {
        super(block, properties);
    }

    @Override
    public @Nullable BlockPlaceContext updatePlacementContext(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        BlockState clicked = level.getBlockState(pos);
        if (!clicked.is(getBlock())) {
            // on the side of a core's top block: a boom from the core (checked against the rules like any boom)
            Direction f = ctx.getClickedFace();
            BlockPos behind = pos.relative(f.getOpposite());
            if (f.getAxis().isHorizontal() && TowerBlock.isCoreTop(level.getBlockState(behind)) && refused(ctx, pos, f)) return null;
            return ctx;
        }
        Direction.Axis axis = clicked.getValue(TowerPoleBlock.AXIS);
        Direction dir;
        if (ctx.isSecondaryUseActive()) dir = ctx.isInside() ? ctx.getClickedFace().getOpposite() : ctx.getClickedFace();
        else if (axis == Direction.Axis.Y) dir = Direction.UP;
        else dir = outward(level, pos, axis, ctx.getHorizontalDirection());

        BlockPos.MutableBlockPos p = pos.mutable().move(dir);
        for (int i = 0; i < MAX_RUN; i++) {
            if (!level.isInWorldBounds(p)) {
                if (!level.isClientSide() && ctx.getPlayer() instanceof ServerPlayer sp && p.getY() > level.getMaxY())
                    sp.sendBuildLimitMessage(true, level.getMaxY());
                return null;
            }
            BlockState s = level.getBlockState(p);
            // walk along poles of the line's own axis; anything else ends it
            if (!(s.is(getBlock()) && s.getValue(TowerPoleBlock.AXIS) == dir.getAxis())) {
                if (!s.canBeReplaced(ctx) || s.is(getBlock())) return null;
                if (dir.getAxis().isHorizontal() && refused(ctx, p.immutable(), dir)) return null;
                return BlockPlaceContext.at(ctx, p.immutable(), dir);
            }
            p.move(dir);
        }
        return null;
    }

    /** A boom pole that would break the tower's rules is refused, with the reason in the action bar. */
    static boolean refused(BlockPlaceContext ctx, BlockPos p, Direction dir) {
        String why = TowerStructure.boomRefusal(ctx.getLevel(), p, dir);
        if (why == null) return false;
        if (ctx.getPlayer() != null && !ctx.getLevel().isClientSide())
            ctx.getPlayer().sendOverlayMessage(net.minecraft.network.chat.Component.literal(why));
        return true;
    }

    /** A boom's outer direction: away from the mast pole at its root. A free-floating boom extends the way the player faces. */
    static Direction outward(Level level, BlockPos pos, Direction.Axis axis, Direction facing) {
        Direction pos1 = Direction.get(Direction.AxisDirection.POSITIVE, axis);
        Direction neg = pos1.getOpposite();
        boolean rootNeg = root(level.getBlockState(end(level, pos, neg).relative(neg)));
        boolean rootPos = root(level.getBlockState(end(level, pos, pos1).relative(pos1)));
        if (rootNeg != rootPos) return rootNeg ? pos1 : neg;
        return facing.getAxis() == axis ? facing : pos1;
    }

    /** What a boom grows from: a mast pole or the core's top block. */
    static boolean root(BlockState s) {
        return TowerPoleBlock.isMast(s) || TowerBlock.isCoreTop(s);
    }

    /** The last pole of the given axis from pos in direction d. */
    static BlockPos end(Level level, BlockPos pos, Direction d) {
        BlockPos p = pos;
        for (int i = 0; i < MAX_RUN; i++) {
            BlockState s = level.getBlockState(p.relative(d));
            if (!(s.is(StasisRegistry.TOWER_SEGMENT.get()) && s.getValue(TowerPoleBlock.AXIS) == d.getAxis())) break;
            p = p.relative(d);
        }
        return p;
    }

    @Override
    protected boolean mustSurvive() {
        return false;
    }
}
