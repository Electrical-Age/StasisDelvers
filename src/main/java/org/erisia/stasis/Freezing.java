package org.erisia.stasis;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Players in stopped land without a working suit start to freeze: a warning that builds up over
 * {@link StasisConfig#freezeTicks} ticks (enough to put a suit on), walking slows, and at the end they become a
 * {@link Statue} holding their things and wake at their bed. Never right after login, respawn or a dimension change
 * (a grace period), and never in creative or spectator mode. Ticks are counted, not wall time, so lag can't skip the
 * warning.
 */
public final class Freezing {
    private Freezing() {
    }

    static final Identifier SLOW = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "freezing");

    private static final class State {
        int ticks;
        int counted;
        long graceUntil;
        float sent;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private static State state(ServerPlayer p) {
        return STATES.computeIfAbsent(p.getUUID(), u -> new State());
    }

    /** Can this player stand in stopped land? Creative/spectator, or a charged suit (Suits, T2b). */
    static boolean isProtected(ServerPlayer p) {
        return p.isCreative() || p.isSpectator() || Suits.protect(p);
    }

    static void tick(PlayerTickEvent.Post e) {
        if (e.getEntity() instanceof ServerPlayer p) tick(p);
    }

    /** One tick of a player's freezing (public for tests, whose mock players get no tick events). */
    public static void tick(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) return;
        State s = state(p);
        s.counted++;
        boolean stopped = p.isAlive() && !Stasis.isRunning(level, p.blockPosition());
        boolean grace = level.getGameTime() < s.graceUntil;
        boolean suited = false;
        if (stopped && !grace && !(suited = isProtected(p))) s.ticks++;
        else s.ticks = Math.max(0, s.ticks - 3);
        // a suited player digs slowly in stopped land (Mining Fatigue I; the EA mining drill ignores it: onBreakSpeed)
        if (stopped && suited && !p.isCreative() && !p.isSpectator()) {
            var fatigue = p.getEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE);
            if (fatigue == null || fatigue.getDuration() < 20)
                p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MINING_FATIGUE, 40, 0, true, true));
        }
        if (stopped && !grace && !p.isCreative() && !p.isSpectator() && s.counted % 20 == 0) {
            if (suited) {
                double left = Suits.secondsLeft(p);
                if (left < 120) p.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                    String.format("Suit charge: %d:%02d left", (int) left / 60, (int) left % 60))
                    .withStyle(left < 30 ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GOLD));
            } else {
                String why = Suits.problem(p);
                if (why != null) p.sendOverlayMessage(net.minecraft.network.chat.Component.literal(why).withStyle(net.minecraft.ChatFormatting.RED));
            }
        }
        int max = StasisConfig.freezeTicks;
        float progress = Math.min(1f, s.ticks / (float) max);
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            if (progress > 0) speed.addOrUpdateTransientModifier(
                new AttributeModifier(SLOW, -0.85 * progress, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            else speed.removeModifier(SLOW);
        }
        if (Math.abs(progress - s.sent) >= 0.01f || (progress == 0f && s.sent != 0f)) {
            s.sent = progress;
            StasisNet.sendFreeze(p, progress);
        }
        if (s.ticks >= max) freeze(level, p, s);
    }

    /** The EA mining drill digs at full speed in stopped land: undo Mining Fatigue I's x0.3 there. */
    static void onBreakSpeed(net.neoforged.neoforge.event.entity.player.PlayerEvent.BreakSpeed e) {
        var p = e.getEntity();
        if (!(p.getMainHandItem().getItem() instanceof mods.eln.generic.IDescriptorItem di)
            || !(di.getEaDescriptor() instanceof mods.eln.item.electricalitem.ElectricalPickaxe)) return;
        var fatigue = p.getEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE);
        if (fatigue != null && fatigue.getAmplifier() == 0 && !Stasis.isRunning(p.level(), p.blockPosition()))
            e.setNewSpeed(e.getNewSpeed() / 0.3f);
    }

    /** Leave a statue with the player's inventory and experience, and kill the player. */
    static void freeze(ServerLevel level, ServerPlayer p, State s) {
        var source = level.damageSources().source(StasisRegistry.FROZEN_IN_TIME);
        if (p.isInvulnerableTo(level, source)) return;      // e.g. still loading in: don't take the inventory
        Statue statue = Statue.of(level, p);
        level.addFreshEntity(statue);
        s.ticks = 0;
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SLOW);
        StasisDelvers.LOG.info("{} froze in time at {} ({} stacks, {} xp in the statue)", p.getName().getString(),
            p.blockPosition().toShortString(), statue.items().size(), statue.xp());
        p.hurtServer(level, source, Float.MAX_VALUE);
    }

    /** Players sent to world spawn instead of a respawn point in stopped land (told so once they're back). */
    private static final java.util.Set<UUID> REDIRECTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * No death loop (softlock audit 1): an unprotected player whose bed or anchor is in stopped land respawns at world
     * spawn instead (the spawn area runs), else they would freeze again and again at the bed.
     */
    static void respawnPosition(net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.isCreative() || p.isSpectator()) return;
        var t = e.getTeleportTransition();
        if (Stasis.isRunning(t.newLevel(), net.minecraft.core.BlockPos.containing(t.position())) || Suits.protect(p)) return;
        var spawn = net.minecraft.world.level.portal.TeleportTransition.createDefault(p, t.postTeleportTransition());
        if (!Stasis.isRunning(spawn.newLevel(), net.minecraft.core.BlockPos.containing(spawn.position()))) return;   // no safe place either
        e.setTeleportTransition(spawn);
        REDIRECTED.add(p.getUUID());
        StasisDelvers.LOG.info("{}'s respawn point {} is in stopped land: respawning at world spawn", p.getName().getString(),
            net.minecraft.core.BlockPos.containing(t.position()).toShortString());
    }

    /** The players of a level who stand where time runs (before a field shrinks or goes: see graceIfStopped). */
    static java.util.List<ServerPlayer> running(ServerLevel level) {
        return level.players().stream().filter(p -> Stasis.isRunning(level, p.blockPosition())).toList();
    }

    /**
     * A field shrank or vanished at once (tower broken, mast cut, /stasis field remove|clear): players it left in
     * stopped land get the grace period, not just the 5 s freeze (softlock audit 4). A collapse is gradual and warned.
     */
    static void graceIfStopped(ServerLevel level, java.util.List<ServerPlayer> wereSafe) {
        for (ServerPlayer p : wereSafe) {
            if (p.isRemoved() || Stasis.isRunning(level, p.blockPosition())) continue;
            State s = state(p);
            s.ticks = 0;
            s.graceUntil = Math.max(s.graceUntil, level.getGameTime() + StasisConfig.graceTicks);
            p.sendOverlayMessage(net.minecraft.network.chat.Component.literal("The field around you is gone: time stops here. Get to safety!"));
        }
    }

    /** A fresh start in a new place: no freezing for a while. */
    static void grace(PlayerEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && p.level() instanceof ServerLevel level) {
            State s = state(p);
            s.ticks = 0;
            s.graceUntil = level.getGameTime() + StasisConfig.graceTicks;
            StasisNet.sendFields(p);
            if (REDIRECTED.remove(p.getUUID()))
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Your respawn point is in stopped land, where you would freeze without a charged suit. You woke at world spawn."));
        }
    }

    static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        STATES.remove(e.getEntity().getUUID());
    }

    /** For tests: end the player's grace period now. */
    public static void endGrace(ServerPlayer p) {
        state(p).graceUntil = 0;
    }

    /** For tests: how many ticks were counted for this player (do tick events reach it?). */
    public static int counted(ServerPlayer p) {
        return state(p).counted;
    }

    /** For tests: the player's current freeze ticks. */
    public static int ticks(ServerPlayer p) {
        return state(p).ticks;
    }
}
