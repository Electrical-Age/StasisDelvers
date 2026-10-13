package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A non-master cell of the tower core: holds nothing, but lets energy overlays and cables find the tower's store on
 *  every face (the energy capability resolves to the master's store). */
public class TowerCellBlockEntity extends BlockEntity {
    public TowerCellBlockEntity(BlockPos pos, BlockState state) {
        super(StasisRegistry.TOWER_CELL_ENTITY.get(), pos, state);
    }
}
