package org.erisia.stasis;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Entity ticks and natural spawning, where NeoForge has events for them. */
final class StasisEvents {
    private StasisEvents() {
    }

    static void entityTick(EntityTickEvent.Pre e) {
        if (Stasis.isFrozen(e.getEntity())) e.setCanceled(true);
    }

    /** No natural spawns where time is stopped (chunk generation still places its animals). */
    static void spawnCheck(MobSpawnEvent.PositionCheck e) {
        if (e.getSpawnType() == EntitySpawnReason.CHUNK_GENERATION) return;
        if (e.getLevel().getLevel() instanceof ServerLevel level
            && !Stasis.isRunning(level, net.minecraft.core.BlockPos.containing(e.getX(), e.getY(), e.getZ())))
            e.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
    }

    static void levelUnload(LevelEvent.Unload e) {
        if (e.getLevel() instanceof ServerLevel level) Stasis.invalidate(level);
    }

    /**
     * Items in stopped land don't tick, so their pickup delay never runs out: without this, nothing dropped, mined or
     * crumbled out of a statue there could ever be picked up (softlock audit 2).
     */
    static void pickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre e) {
        var item = e.getItemEntity();
        if (item.hasPickUpDelay() && !item.level().isClientSide() && !Stasis.isRunning(item.level(), item.blockPosition())
            && (item.getTarget() == null || item.getTarget().equals(e.getPlayer().getUUID())))
            e.setCanPickup(net.minecraft.util.TriState.TRUE);
    }
}
