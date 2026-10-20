package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * A Puppeteer inside a frozen mob (notes/fauna/PLAN.md, F3; Baughn: it pours into a mob and controls it). The host moves in
 * <b>stutter bursts</b>: it runs 2-8 ticks, then stands frozen 5-40 (shorter with dread), its own goals switched off and
 * its path set on the nearest survival player. Its eyes glow purple (λ-Nero; client: {@code PossessedEyes}) and static
 * hangs round it. It keeps the fauna wall: never into running land unless a field there is failing.
 * <ul>
 *   <li>Damage to the host goes to the Puppeteer's grip instead ({@link #GRIP}); at 0 the static bursts out (a Puppeteer,
 *       stunned for a moment) and the host freezes again where it stands, in its new pose.</li>
 *   <li>No prey for {@link #LOST_TICKS}, or no dread around it for {@link RiftFauna#FADE_TICKS}: the Puppeteer leaves
 *       (pours out, or dissolves with the weather).</li>
 *   <li>Memory only: a host whose chunk unloads, or a restart, is just a frozen mob again.</li>
 * </ul>
 */
public final class Possession {
    private Possession() {
    }

    /** The grip, in hits worth at least {@link #HIT_WORTH} each (AdventurAgent: fists did 1 a hit, and 7 hits didn't free
     *  the cow when the grip was 8 damage). */
    public static final float GRIP = 6, HIT_WORTH = 2;
    static final int LOST_TICKS = 400;
    static final double SENSE = 32;
    static final float HIT = 3;

    static final class Hold {
        float grip = GRIP;
        long burstUntil, nextBurst;   // game times: running while now < burstUntil
        int lostTicks, calmTicks, hitCooldown;
        long lastHurt = -100;
        BlockPos lastStopped;
        float dread;
    }

    /** Server thread only. Weak: a host removed for any reason just drops out. */
    private static final Map<Mob, Hold> HOSTS = new WeakHashMap<>();
    /** Hosts just let go: game time until which no Puppeteer takes them again (AdventurAgent: it poured straight back in). */
    private static final Map<Mob, Long> RESTING = new WeakHashMap<>();
    static final int REST_TICKS = 1200;

    public static boolean possessed(Entity e) {
        return e instanceof Mob m && HOSTS.containsKey(m);
    }

    /** Is this host in a burst (so it ticks, not frozen)? Server side (Stasis.isFrozen). */
    static boolean running(Entity e) {
        if (!(e instanceof Mob m)) return false;
        Hold h = HOSTS.get(m);
        return h != null && e.level().getGameTime() < h.burstUntil;
    }

    /** Can a Puppeteer take this one: a frozen mob, not fauna, not a boss, not already taken. */
    static boolean canHost(Entity e) {
        return e instanceof Mob m && m.isAlive() && !(m instanceof RiftFauna) && !HOSTS.containsKey(m) && Stasis.isFrozen(m)
            && m.level().getGameTime() >= RESTING.getOrDefault(m, Long.MIN_VALUE)
            && !(m instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) && !(m instanceof net.minecraft.world.entity.boss.wither.WitherBoss);
    }

    /** The Puppeteer is in: take the host. */
    public static void start(ServerLevel level, Mob host) {
        Hold h = new Hold();
        h.nextBurst = level.getGameTime() + 10;
        h.lastStopped = host.blockPosition();
        HOSTS.put(host, h);
        for (Goal.Flag f : Goal.Flag.values()) {
            host.goalSelector.disableControlFlag(f);
            host.targetSelector.disableControlFlag(f);
        }
        host.setData(StasisRegistry.POSSESSED.get(), true);
    }

    /** Let go of the host: it freezes where it is. {@code burst}: the static bursts out as a Puppeteer; else it's gone. */
    public static void release(ServerLevel level, Mob host, boolean burst) {
        if (HOSTS.remove(host) == null) return;
        RESTING.put(host, level.getGameTime() + REST_TICKS);
        for (Goal.Flag f : Goal.Flag.values()) {
            host.goalSelector.enableControlFlag(f);
            host.targetSelector.enableControlFlag(f);
        }
        host.getNavigation().stop();
        host.setData(StasisRegistry.POSSESSED.get(), false);
        for (int i = 0; i < 20; i++)
            level.sendParticles(RiftFauna.staticDust(i % 3), host.getX(), host.getY() + host.getBbHeight() * 0.6, host.getZ(), 1,
                host.getBbWidth() * 0.6, host.getBbHeight() * 0.4, host.getBbWidth() * 0.6, 0.02);
        if (burst) {
            RiftPuppeteer p = StasisRegistry.RIFT_PUPPETEER.get().create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
            if (p != null) {
                p.snapTo(host.getX(), host.getY(), host.getZ(), host.getYRot(), 0f);
                p.stun(60, host);
                level.addFreshEntity(p);
            }
        }
    }

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (HOSTS.isEmpty()) return;
        for (Mob host : java.util.List.copyOf(HOSTS.keySet())) {
            Hold h = HOSTS.get(host);
            if (h == null) continue;
            if (!host.isAlive() || host.isRemoved() || !(host.level() instanceof ServerLevel level)) {
                HOSTS.remove(host);
                continue;
            }
            step(level, host, h);
        }
    }

    private static void step(ServerLevel level, Mob host, Hold h) {
        long now = level.getGameTime();
        if (now % 10 == 0) h.dread = Dread.at(level, host.getBlockX(), host.getBlockY(), host.getBlockZ());
        // the wall
        if (RiftFauna.inStopped(level, host.blockPosition())) h.lastStopped = host.blockPosition();
        else {
            BlockPos back = h.lastStopped;
            if (!RiftFauna.inStopped(level, back)) {
                // a field grew over it, where it last stood too: as fauna, half the time out, else the Puppeteer goes
                // (advisor: a host left in running land ticked every tick and chased the player inside the field)
                back = level.getRandom().nextBoolean() ? RiftFauna.escape(level, host.blockPosition()) : null;
                if (back == null) {
                    release(level, host, false);
                    return;
                }
                h.lastStopped = back;
            }
            host.setPos(back.getX() + 0.5, back.getY(), back.getZ() + 0.5);
            host.getNavigation().stop();
            host.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        }
        // the weather left: it goes with it
        if (now % 20 == 0) h.calmTicks = h.dread > 0 ? 0 : h.calmTicks + 20;
        if (h.calmTicks >= RiftFauna.FADE_TICKS) {
            release(level, host, false);
            return;
        }
        Player prey = level.getNearestPlayer(host.getX(), host.getY(), host.getZ(), SENSE, EntitySelector.NO_CREATIVE_OR_SPECTATOR);
        h.lostTicks = prey == null ? h.lostTicks + 1 : 0;
        if (h.lostTicks >= LOST_TICKS) {
            release(level, host, true);   // (it pours out and looks elsewhere)
            return;
        }
        if (h.hitCooldown > 0) h.hitCooldown--;
        if (now >= h.burstUntil && now >= h.nextBurst) {
            // a burst: 2-8 ticks running, then 5-40 frozen (both shorter with dread; at most 2x: λ-Nero)
            var r = level.getRandom();
            float speed = 1 + Math.min(h.dread, 1f);
            h.burstUntil = now + 2 + r.nextInt(7);
            h.nextBurst = h.burstUntil + (long) ((5 + r.nextInt(36)) / speed);
            if (prey != null) {
                host.getNavigation().moveTo(prey, 1.3 * speed);
                host.getLookControl().setLookAt(prey, 60f, 60f);
            }
        }
        if (prey != null && now < h.burstUntil && h.hitCooldown == 0
            && host.distanceToSqr(prey) < Math.pow(1.4 + host.getBbWidth() / 2, 2)) {
            var v = prey.getDeltaMovement();
            prey.hurtServer(level, level.damageSources().mobAttack(host), HIT);
            prey.setDeltaMovement(v);   // (no knockback: AdventurAgent was knocked off a ledge, "doomed to fall by Cow")
            prey.needsSync = true;
            h.hitCooldown = 30;
        }
    }

    /** Damage to a host goes to the grip; at 0 the static bursts out. */
    static void hurt(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof Mob host) || !(host.level() instanceof ServerLevel level)) return;
        Hold h = HOSTS.get(host);
        if (h == null) return;
        // /kill and the void go through (AdventurAgent: a host survived /kill); the Puppeteer goes with it
        if (e.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            release(level, host, false);
            return;
        }
        e.setCanceled(true);
        if (level.getGameTime() - h.lastHurt < 8) return;   // (one swing, one hit)
        h.lastHurt = level.getGameTime();
        h.grip -= Math.max(HIT_WORTH, e.getAmount());
        level.playSound(null, host.blockPosition(), net.minecraft.sounds.SoundEvents.SCULK_CLICKING_STOP, net.minecraft.sounds.SoundSource.HOSTILE, 1f, 0.6f);
        if (h.grip <= 0) release(level, host, true);
    }

    /** A player froze near these hosts: the Puppeteers leave (no camping a fresh statue; FaunaSpawner.froze). */
    static void releaseNear(ServerLevel level, net.minecraft.world.phys.Vec3 at, double r) {
        for (Mob host : java.util.List.copyOf(HOSTS.keySet()))
            if (host.level() == level && host.distanceToSqr(at) < r * r) release(level, host, false);
    }

    /** GameTests: how many hosts are held. */
    public static int count() {
        return HOSTS.size();
    }
}
