package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * What players may use inside vanilla spawn protection (Baughn 2026-10-17: it must not stop EA or charging a suit):
 * Electrical Age and Stasis blocks, and blocks tagged stasisdelvers:usable_at_spawn, as with an empty hand.
 */
public final class SpawnUse {
    private SpawnUse() {
    }

    public static final TagKey<Block> USABLE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "usable_at_spawn"));

    /** Set while a use packet goes through under spawn protection: only the block's own empty-hand use runs. */
    public static final ThreadLocal<Boolean> BLOCK_ONLY = ThreadLocal.withInitial(() -> false);

    public static boolean usable(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        String ns = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace();
        return ns.equals("eln") || ns.equals(StasisDelvers.MODID) || state.is(USABLE);
    }
}
