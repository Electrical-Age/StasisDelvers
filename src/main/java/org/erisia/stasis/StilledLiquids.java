package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Stilled liquids (Baughn): in stopped land water and lava are solid, and a shovel (or the EA drill) takes a block of
 * one as "stilled water" or "stilled lava"; a bucket can't (buckets are for liquids). Placed where time runs, it turns
 * back into its liquid in a moment; in stopped land it stays a solid block until a field arrives. Stilled lava stacks
 * only to 4 (it's very portable heat). Only source blocks and falling columns give one (no flowing-edge farming).
 */
public final class StilledLiquids {
    private StilledLiquids() {
    }

    /** A stilled liquid block: solid while time is stopped here; it thaws back into its liquid where time runs. */
    public static final class StilledBlock extends Block {
        final boolean lava;

        public StilledBlock(BlockBehaviour.Properties p, boolean lava) {
            super(p);
            this.lava = lava;
        }

        @Override
        protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
            // (scheduled ticks don't run in stopped land: there it waits for a field)
            if (!level.isClientSide()) level.scheduleTick(pos, this, 30);
        }

        @Override
        protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            if (!Stasis.isRunning(level, pos)) return;
            level.setBlockAndUpdate(pos, (lava ? Blocks.LAVA : Blocks.WATER).defaultBlockState());
        }

        @Override
        protected boolean isRandomlyTicking(BlockState state) {
            return true;
        }

        @Override
        protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            tick(state, level, pos, random);
        }
    }

    static boolean isTool(ItemStack st) {
        if (st.is(ItemTags.SHOVELS)) return true;
        return st.getItem() instanceof mods.eln.generic.IDescriptorItem di
            && di.getEaDescriptor() instanceof mods.eln.item.electricalitem.ElectricalPickaxe;
    }

    /** Right-click with a shovel at a stopped liquid: take a stilled block of it. */
    static void use(PlayerInteractEvent.RightClickItem e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level) || !isTool(e.getItemStack())) return;
        var hit = Item.getPlayerPOVHitResult(level, p, ClipContext.Fluid.ANY);
        if (hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof LiquidBlock) || Stasis.isRunning(level, pos)) return;
        var fs = st.getFluidState();
        boolean lava = fs.is(net.minecraft.tags.FluidTags.LAVA);
        if (!fs.isSource() && !(st.getValue(LiquidBlock.LEVEL) >= 8)) return;   // sources and falling columns only
        if (!level.mayInteract(p, pos)) return;
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        ItemStack got = new ItemStack(lava ? StasisRegistry.STILLED_LAVA.get() : StasisRegistry.STILLED_WATER.get());
        if (!p.getInventory().add(got)) level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, p.getX(), p.getY(), p.getZ(), got));
        e.getItemStack().hurtAndBreak(1, p, e.getHand());
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_BREAK, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, lava ? 0.6f : 1.3f);
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
    }
}
