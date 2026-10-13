package org.erisia.stasis;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.jspecify.annotations.Nullable;

/** The tower core item: places the 3x3x2 core in front of the player, the clicked spot being its near edge's middle. */
public class CoreItem extends BlockItem {
    public CoreItem(TowerBlock block, Properties properties) {
        super(block, properties);
    }

    @Override
    public @Nullable BlockPlaceContext updatePlacementContext(BlockPlaceContext ctx) {
        return BlockPlaceContext.at(ctx, TowerBlock.masterFor(ctx), ctx.getClickedFace());
    }
}
