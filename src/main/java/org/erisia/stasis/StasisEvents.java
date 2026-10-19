package org.erisia.stasis;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Entity ticks and natural spawning, where NeoForge has events for them. */
public final class StasisEvents {
    private StasisEvents() {
    }

    static void entityTick(EntityTickEvent.Pre e) {
        // a mob killed in stopped land still finishes dying (else it lies there forever at 0 health: Baughn's pigs)
        if (e.getEntity() instanceof net.minecraft.world.entity.LivingEntity le && le.isDeadOrDying()) return;
        boolean frozen = Stasis.isFrozen(e.getEntity());
        if (frozen) {
            e.setCanceled(true);
            leashed(e.getEntity());
        }
        // (tell the client, which then holds it still: no guessing, no judder)
        var en = e.getEntity();
        if (!en.level().isClientSide() && !(en instanceof net.minecraft.world.entity.player.Player)
            && en.getData(StasisRegistry.FROZEN.get()) != frozen) {
            en.setData(StasisRegistry.FROZEN.get(), frozen);
            // (and where it stopped, now: items sync position only every 20 ticks, so the client held them short of it)
            en.needsSync = true;
        }
    }

    /** A frozen entity being pushed (by a furnace cart, a player walking into it, an explosion) moves as far as one tick
     *  of that push takes it, and keeps no momentum (λ-Nero: carts at the border blocked furnace carts; Baughn). */
    private static final ThreadLocal<Boolean> PUSHING = ThreadLocal.withInitial(() -> false);

    public static boolean pushFrozen(net.minecraft.world.entity.Entity en, double x, double y, double z) {
        if (en.level().isClientSide() || PUSHING.get() || !Stasis.isFrozen(en) || en.isRemoved()) return false;
        PUSHING.set(true);
        try {
            en.setDeltaMovement(x, y, z);
            en.setOldPosAndRot();   // (as Level.tickNonPassenger does: a cart turns by where it was, and one that never ran had 0 0 0)
            en.tick();
            en.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        } finally {
            PUSHING.set(false);
        }
        return true;
    }

    /**
     * A frozen mob on a lead held by a player is dragged along (λ-Nero: leads should let players drag mobs through
     * stopped land): the lead's pull for this tick, moved at once, no momentum kept, nothing else of the mob's tick.
     */
    static void leashed(net.minecraft.world.entity.Entity en) {
        if (!(en instanceof net.minecraft.world.entity.Leashable l) || !(en.level() instanceof net.minecraft.server.level.ServerLevel level)
            || !(l.getLeashHolder() instanceof net.minecraft.world.entity.player.Player) || en.isRemoved()) return;
        net.minecraft.world.entity.Leashable.tickLeash(level, (net.minecraft.world.entity.Entity & net.minecraft.world.entity.Leashable) en);
        var v = en.getDeltaMovement();
        if (v.lengthSqr() > 1e-6) {
            en.setOldPosAndRot();
            en.move(net.minecraft.world.entity.MoverType.SELF, v);
            en.needsSync = true;
        }
        en.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    }

    /** No natural spawns where time is stopped (chunk generation still places its animals). */
    static void spawnCheck(MobSpawnEvent.PositionCheck e) {
        if (e.getSpawnType() == EntitySpawnReason.CHUNK_GENERATION) return;
        if (e.getLevel().getLevel() instanceof ServerLevel level
            && !Stasis.spawnsHere(level, net.minecraft.core.BlockPos.containing(e.getX(), e.getY(), e.getZ())))
            e.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
    }

    /**
     * Spawns that skip the position check (phantoms, patrols, wandering traders, other mods' spawners, reinforcements):
     * none in stopped land either (λ-Nero: lots of things spawned out there). Worldgen, eggs, commands, breeding,
     * conversions and the like stay allowed.
     */
    static void finalizeSpawn(net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent e) {
        var why = e.getSpawnType();
        if (why != EntitySpawnReason.NATURAL && why != EntitySpawnReason.PATROL && why != EntitySpawnReason.EVENT
            && why != EntitySpawnReason.REINFORCEMENT && why != EntitySpawnReason.JOCKEY && why != EntitySpawnReason.SPAWNER
            && why != EntitySpawnReason.TRIAL_SPAWNER) return;
        if (e.getLevel().getLevel() instanceof ServerLevel level && Stasis.stops(level)
            && !Stasis.spawnsHere(level, net.minecraft.core.BlockPos.containing(e.getX(), e.getY(), e.getZ())))
            e.setSpawnCancelled(true);
    }

    /**
     * Friendly mobs stay inside their field (λ-Nero: they walked into its edge and froze there): a peaceful mob whose
     * step would take it from running land into stopped land is put back and its path dropped. Monsters may wander out
     * (and freeze).
     */
    static void keepFriendlyInside(net.neoforged.neoforge.event.tick.EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof net.minecraft.world.entity.Mob mob) || mob instanceof net.minecraft.world.entity.monster.Enemy) return;
        if (!(mob.level() instanceof ServerLevel level) || !Stasis.stops(level) || mob.isPassenger() || mob.isLeashed()) return;
        var now = mob.blockPosition();
        var was = net.minecraft.core.BlockPos.containing(mob.xo, mob.yo, mob.zo);
        if (now.equals(was) || Stasis.isRunning(level, now) || !Stasis.isRunning(level, was)) return;
        mob.setPos(mob.xo, mob.yo, mob.zo);
        mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        mob.getNavigation().stop();
    }

    /** Load the fields on the main thread (the light engine reads them from its own: FieldData.peek). */
    static void levelLoad(LevelEvent.Load e) {
        if (e.getLevel() instanceof ServerLevel level) {
            FieldData.get(level).deepSeed();   // made now, on the main thread
            Gates.publish(level);
        }
    }

    static void levelUnload(LevelEvent.Unload e) {
        if (e.getLevel() instanceof ServerLevel level) Stasis.invalidate(level);
    }

    /**
     * Items in stopped land don't tick, so their pickup delay never runs out: without this, nothing dropped, mined or
     * crumbled out of a statue there could ever be picked up (softlock audit 2).
     */
    /** When frozen items appeared (server thread; weak, so removed items go). */
    private static final java.util.Map<net.minecraft.world.entity.item.ItemEntity, Long> ITEM_BORN = new java.util.WeakHashMap<>();

    static void itemJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (e.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item && !e.getLevel().isClientSide())
            ITEM_BORN.put(item, e.getLevel().getGameTime());
    }

    static void pickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre e) {
        var item = e.getItemEntity();
        if (item.level().isClientSide() || Stasis.isRunning(item.level(), item.blockPosition())) return;
        // /give's pickup-animation copy (never pickable, gone next tick): frozen it would hang there, and taking it is a dupe
        if (item.getAge() >= item.getItem().getEntityLifespan(item.level()) - 1) {
            item.discard();
            e.setCanPickup(net.minecraft.util.TriState.FALSE);
            return;
        }
        int delay = item.pickupDelay;
        if (delay == 32767) return;   // never pickable
        // the delay still applies, counted in level time from when the item appeared (else a Q-dropped item jumps
        // straight back into the hand)
        Long born = ITEM_BORN.get(item);
        long now = item.level().getGameTime();
        if (born == null) ITEM_BORN.put(item, born = now);
        if (now - born < delay) return;
        if (item.hasPickUpDelay() && !item.level().isClientSide() && !Stasis.isRunning(item.level(), item.blockPosition())
            && (item.getTarget() == null || item.getTarget().equals(e.getPlayer().getUUID())))
            e.setCanPickup(net.minecraft.util.TriState.TRUE);
    }

    /** When frozen mobs were last hit (server thread; weak). */
    private static final java.util.Map<net.minecraft.world.entity.LivingEntity, Long> LAST_HIT = new java.util.WeakHashMap<>();

    /**
     * A frozen mob's hit cooldown counts down in its tick, which never comes: after one hit it could never be hit again
     * (AdventurAgent: a pig stuck at 2 HP). Count it down in level time instead, as items' pickup delay.
     */
    static void incomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent e) {
        var le = e.getEntity();
        if (le.level().isClientSide() || !Stasis.isFrozen(le)) return;
        long now = le.level().getGameTime();
        Long last = LAST_HIT.put(le, now);
        if (last == null) return;
        int elapsed = (int) Math.min(Integer.MAX_VALUE, now - last);
        le.damageCooldownTime = Math.max(0, le.damageCooldownTime - elapsed);
        le.hurtTime = Math.max(0, le.hurtTime - elapsed);
    }

    /** Diagnostics (Baughn: animals in stopped land found dead): log every non-player death there, with its cause. */
    static void deathLog(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        var le = e.getEntity();
        if (le.level().isClientSide() || le instanceof net.minecraft.world.entity.player.Player) return;
        if (Stasis.isRunning(le.level(), le.blockPosition())) return;
        var src = e.getSource();
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder trace = new StringBuilder();
        for (int k = 2; k < Math.min(st.length, 16); k++) trace.append("\n    at ").append(st[k]);
        StasisDelvers.LOG.warn("Stopped-land death: {} at {} by {} (direct {}, attacker {}){}", le.getType().getDescriptionId(),
            le.blockPosition().toShortString(), src.getMsgId(), src.getDirectEntity(), src.getEntity(), trace);
    }

}
