package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The accelerators' in-world control (Baughn: in-world, with an animated lever): a right-click on either accelerator
 * raises its setting by half a step (round from 10x back to the lowest), a sneak-right-click lowers it, with anything in
 * hand (RightClickBlock: vanilla doesn't use a block for a sneaking player holding something, so a sneak-click never
 * reached the block, AdventurAgent). The lever moves to the face that was clicked; AcceleratorLeverRenderer swings it to
 * the setting.
 */
public final class AcceleratorLevers {
    private AcceleratorLevers() {
    }

    /** A block entity carrying a lever: the face it's on (3D value). */
    public interface Lever {
        int leverFace();

        void setLeverFace(int face);
    }

    static void click(PlayerInteractEvent.RightClickBlock e) {
        BlockState st = e.getLevel().getBlockState(e.getPos());
        boolean temporal = st.getBlock() instanceof TemporalAcceleratorBlock, chronal = st.getBlock() instanceof ChronalAcceleratorBlock;
        if (!temporal && !chronal) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getLevel() instanceof ServerLevel sl)) return;
        BlockPos pos = e.getPos();
        boolean down = e.getEntity().isSecondaryUseActive();
        int step;
        if (temporal) {
            int s = st.getValue(TemporalAcceleratorBlock.STEP);
            step = down ? Math.max(0, s - 1) : (s + 1) % 19;
            sl.setBlock(pos, st.setValue(TemporalAcceleratorBlock.STEP, step), Block.UPDATE_ALL);
        } else {
            int s = st.getValue(ChronalAcceleratorBlock.STEP);
            step = down ? Math.max(0, s - 1) : (s + 1) % 19;
            sl.setBlock(pos, st.setValue(ChronalAcceleratorBlock.STEP, step), Block.UPDATE_ALL);
        }
        if (sl.getBlockEntity(pos) instanceof Lever l && e.getFace() != null) {
            l.setLeverFace(e.getFace().get3DDataValue());
            sl.sendBlockUpdated(pos, st, sl.getBlockState(pos), Block.UPDATE_CLIENTS);
        }
        sl.playSound(null, pos, net.minecraft.sounds.SoundEvents.LEVER_CLICK, net.minecraft.sounds.SoundSource.BLOCKS, 0.5f, 0.6f + 0.05f * step);
        String msg;
        if (temporal) {
            msg = String.format("Temporal accelerator: %.1fx", TemporalAcceleratorBlock.rate(step))
                + (TemporalAcceleratorBlock.coreBeside(sl, pos) != null ? "" : " (not against a tower core)");
        } else {
            var be = sl.getBlockEntity(pos) instanceof ChronalAcceleratorBlockEntity a ? a : null;
            if (be != null) be.refill(sl);
            msg = String.format("Chronal accelerator: %.1fx. ", ChronalAcceleratorBlock.rate(step)) + (be == null ? "" : be.status());
        }
        e.getEntity().sendOverlayMessage(Component.literal(msg));
    }

    /** The lever's face for a new block entity: up. */
    public static final int DEFAULT_FACE = Direction.UP.get3DDataValue();
}
