package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;

/**
 * What players may use inside vanilla spawn protection (Baughn 2026-10-17: it must not stop EA or charging a suit):
 * Electrical Age and Stasis blocks, and blocks tagged stasisdelvers:usable_at_spawn, as with an empty hand.
 */
public final class SpawnUse {
    private SpawnUse() {
    }

    public static final TagKey<Block> USABLE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "usable_at_spawn"));

    public static boolean usable(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        String ns = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace();
        return ns.equals("eln") || ns.equals(StasisDelvers.MODID) || state.is(USABLE);
    }

    /**
     * Is this use one that spawn protection would have refused, but that goes through as the block's own empty-hand
     * use (SpawnUseMixin lets the packet through)? Worked out afresh at each hook, so no state is carried between them.
     * Fake players never came through the packet handler, so they're left to vanilla.
     */
    public static boolean blockOnly(Entity entity, ServerLevel level, BlockPos pos) {
        return entity instanceof Player p && !(p instanceof FakePlayer)
            && level.getServer().isUnderSpawnProtection(level, pos, p) && usable(level, pos);
    }

    /**
     * Under spawn protection the held item does nothing and the block is used as with an empty hand (NeoForge's three
     * use phases in ServerPlayerGameMode.useItemOn: item first, block, item after).
     */
    static void useOn(UseItemOnBlockEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !blockOnly(e.getPlayer(), level, e.getPos())) return;
        e.cancelWithResult(e.getUsePhase() == UseItemOnBlockEvent.UsePhase.BLOCK ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS);
    }
}
