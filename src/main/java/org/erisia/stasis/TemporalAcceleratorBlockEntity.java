package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** The temporal accelerator's lever (AcceleratorLevers): which face it's on. The setting is the block's STEP. */
public class TemporalAcceleratorBlockEntity extends BlockEntity implements AcceleratorLevers.Lever {
    int leverFace = AcceleratorLevers.DEFAULT_FACE;

    public TemporalAcceleratorBlockEntity(BlockPos pos, BlockState state) {
        super(StasisRegistry.TEMPORAL_ACCELERATOR_ENTITY.get(), pos, state);
    }

    @Override
    public int leverFace() {
        return leverFace;
    }

    @Override
    public void setLeverFace(int face) {
        leverFace = face;
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("lever", leverFace);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        leverFace = in.getIntOr("lever", AcceleratorLevers.DEFAULT_FACE);
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }
}
