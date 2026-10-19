package org.erisia.stasis;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spreading mode's hard mode (spread-DESIGN.md step 4a; Baughn's brief: killing the dragon starts it). One entry point,
 * {@link #enter}, for the dragon's death (mixin/EnderDragonFightMixin, which also keeps the exit portal, the gateway and
 * the egg from appearing) and /stasis spread hardmode. Once only: the spread clock's hard flag says so, and a respawned
 * dragon can't come (EnderDragonFightMixin cancels tryRespawn).
 * <p>
 * At the instant: the spread's course goes on from the coverage of the moment (Spread.startHardClock), the storms' schedule
 * starts with its origin here (Storms.origin; tau doesn't jump), test storm overrides are dropped, and the End event
 * begins: {@link #explodeEnd} (step 5's explosion and end-stone rift; a stand-in effect for now) plays for
 * hardModeTeleportSeconds, then at the flash everyone in the End is sent to their respawn point (Freezing.safeRespawn's
 * rule) with the "Good job breaking it, hero" advancement. Only then is the End stilled (Spread.holdsOff): stopping it at
 * the kill would turn the players there into statues before the flash. For END_WINDOW after the kill a death in the End
 * is a teleport instead. After hard mode the way into the End is closed ({@link #endClosed}) until the lance (later).
 * <p>
 * The event is memory only: a restart during it finds hard mode set and the End stilled; players who log in there are
 * sent out as above (login).
 */
public final class HardMode {
    private HardMode() {
    }

    /** "Good job breaking it, hero" (data/stasisdelvers/advancement/breaking_it.json: an impossible trigger, granted here). */
    public static final Identifier ADVANCEMENT = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "breaking_it");
    /** For this long after the kill a death in the End is a teleport instead (ticks). */
    static final int END_WINDOW = 30 * 20;

    /** The End event: the game tick it began, and whether the flash (the teleport) has come. Server thread only. */
    private static final class EndEvent {
        final long start;
        boolean flashed;

        EndEvent(long start) {
            this.start = start;
        }
    }

    private static EndEvent event;

    /**
     * Enter hard mode now (the dragon's death, or the command). False if not in spreading mode or hard mode had begun.
     * cause: for the log and the broadcast.
     */
    public static boolean enter(MinecraftServer srv, String cause) {
        if (!StasisConfig.spreading || !Spread.startHardClock(srv)) return false;
        Storms.clearOverrides(srv);
        event = new EndEvent(srv.overworld().getGameTime());
        ServerLevel end = srv.getLevel(Level.END);
        if (end != null) explodeEnd(end);
        Spread.refresh(srv);   // the storms' origin to every client; the End keeps running until the flash
        HardRifts.begin(srv);   // the strongholds' portal rooms tear open into gate rifts (step 4b)
        StasisDelvers.LOG.info("Hard mode begins ({})", cause);
        srv.getPlayerList().broadcastSystemMessage(Component.literal(
            "Something broke in the End. The stillness stirs again, and storms will rise from the rifts.").withStyle(ChatFormatting.DARK_PURPLE), false);
        return true;
    }

    /**
     * The End's main island explodes into the End rift (step 5, EndRift). Called at the hard-mode instant, before the
     * teleport; the flash (the teleport) comes hardModeTeleportSeconds later (Argon: shake, shockwave, shards and flash
     * over ~5-8 s, teleport at the flash's peak, then build the rift paced while nobody is there). Until the flash the
     * core heaves (EndRift's shards) under {@link #tick}'s explosions and flashes; after it EndRift lifts what is left of
     * the island and places the rift's islands.
     */
    public static void explodeEnd(ServerLevel end) {
        end.playSound(null, 0, 70, 0, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 16f, 0.5f);
        EndRift.finale(end);
    }

    /** Ticks from the kill to the flash (the teleport). */
    public static long flashTicks() {
        return Math.round(StasisConfig.hardModeTeleportSeconds * 20);
    }

    /** Does the End event keep the End running (from the kill until the flash)? */
    static boolean holdsEndOpen() {
        EndEvent e = event;
        return e != null && !e.flashed;
    }

    /**
     * The End event as players see it: from the first pillar's stage (EndRift) until the flash. Then a fall into the
     * void is a rescue ({@link #voidRescue}) and the client pushes the fog out (Spread's endEvent).
     */
    public static boolean endEventOn(MinecraftServer srv) {
        if (!StasisConfig.spreading) return false;
        if (Spread.server.hard()) return holdsEndOpen();
        ServerLevel end = srv.getLevel(Level.END);
        return end != null && EndRift.begun(end);
    }

    /** PlayerTickEvent.Post: a player falling out of the island during the End event is rescued. */
    static void playerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post e) {
        if (e.getEntity() instanceof ServerPlayer p && p.getY() < p.level().getMinY()) voidRescue(p);
    }

    /**
     * A survival or adventure player below the End's floor during the End event (before the flash: after it everyone is
     * sent home anyway) is thrown back to the core island's top by the fountain, with a moment of slow falling
     * (AdventurAgent: standing in a crumbling sector they fell into the void and died). True if rescued (public for tests).
     */
    public static boolean voidRescue(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel end) || end.dimension() != Level.END || p.isCreative() || p.isSpectator()
            || p.getY() >= end.getMinY() || !endEventOn(end.getServer())) return false;
        Vec3 to = heart(end);
        if (p.isPassenger()) p.stopRiding();
        p.teleportTo(to.x, to.y, to.z);
        p.setDeltaMovement(Vec3.ZERO);
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
        p.resetFallDistance();
        p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 100, 0, false, false, true));
        p.sendSystemMessage(Component.literal("The island gives way; you're thrown back to its heart.").withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    /** The core island's top near the fountain (the core goes only after the flash), or the arrival platform. */
    static Vec3 heart(ServerLevel end) {
        for (int r = 6; r <= 30; r += 4)
            for (int k = 0; k < 12; k++) {
                double a = k * Math.PI / 6;
                int x = (int) Math.floor(Math.cos(a) * r), z = (int) Math.floor(Math.sin(a) * r);
                end.getChunk(x >> 4, z >> 4);
                int y = end.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                if (y > end.getMinY() + 1 && end.getBlockState(new net.minecraft.core.BlockPos(x, y - 1, z)).isSolid())
                    return new Vec3(x + 0.5, y, z + 0.5);
            }
        net.minecraft.core.BlockPos spawn = ServerLevel.END_SPAWN_POINT;
        if (!end.getBlockState(spawn.below()).isSolid())
            net.minecraft.world.level.levelgen.feature.EndPlatformFeature.createEndPlatform(end, spawn.below(), true);
        return new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
    }

    /**
     * LivingIncomingDamageEvent: from the kill until the flash nothing hurts a player in the End (AdventurAgent took
     * 10.5 damage just before the flash; the show is sounds, particles and displays only, with no real explosion, but
     * a dragon still alive after /stasis spread hardmode, a fall or a crystal can still hit). The void during the End
     * event is a rescue instead.
     */
    static void damage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.level().dimension() != Level.END) return;
        if (e.getSource().is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD) && voidRescue(p)) e.setCanceled(true);
        else if (shields(p, e.getSource())) e.setCanceled(true);
    }

    /** Is this damage to a player turned away by the finale (kill to flash, in the End; /kill still works)? Public for tests. */
    public static boolean shields(ServerPlayer p, net.minecraft.world.damagesource.DamageSource s) {
        return holdsEndOpen() && p.level().dimension() == Level.END && !s.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    /** Within END_WINDOW of the kill (deaths in the End become teleports)? */
    static boolean inWindow() {
        return event != null;
    }

    /** After hard mode no way leads into the End but the lance (Baughn: decision 7; Lance); creative and spectators pass. */
    static boolean endClosed(MinecraftServer srv) {
        return StasisConfig.spreading && Spread.server.hard();
    }

    /** ServerTickEvent.Post: the stand-in effect, the flash, the window's end. */
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        EndEvent ev = event;
        if (ev == null) return;
        MinecraftServer srv = e.getServer();
        long t = srv.overworld().getGameTime() - ev.start;
        ServerLevel end = srv.getLevel(Level.END);
        if (!ev.flashed) {
            long flash = flashTicks();
            if (end != null) standIn(end, t, flash);
            if (t >= flash) {
                ev.flashed = true;
                int n = 0;
                if (end != null) for (ServerPlayer p : List.copyOf(end.players())) {
                    if (p.isSpectator()) continue;
                    rescue(p);
                    n++;
                }
                StasisDelvers.LOG.info("Hard mode's flash: {} player(s) sent out of the End", n);
                if (end != null) {
                    EndRift.flash(end);
                    endFight(end);
                }
                Spread.refresh(srv);   // the End stills now
            }
        } else if (t >= END_WINDOW) event = null;
    }

    /**
     * At the flash the dragon fight ends as a kill would end it (AdventurAgent: after /stasis spread hardmode the dragon
     * was still alive): every dragon in the End is discarded (no death animation, so no drops and no flood of XP), and
     * the fight is marked killed with its boss bar gone, as EnderDragonFightMixin does for a real kill. Otherwise the
     * fight would look for its dragon and spawn a new one. A dragon in a chunk not loaded now is dropped when it loads
     * (dragonJoin).
     */
    static void endFight(ServerLevel end) {
        int n = 0;
        for (EnderDragon d : end.getEntities(net.minecraft.world.entity.EntityTypes.ENDER_DRAGON, x -> true)) {
            d.discard();
            n++;
        }
        EnderDragonFight f = end.getDragonFight();
        if (f != null && !f.dragonKilled) {
            f.dragonEvent.setProgress(0.0F);
            f.dragonEvent.setVisible(false);
            f.dragonEvent.removeAllPlayers();
            f.dragonKilled = true;
            f.hasPreviouslyKilledDragon = true;
            f.setDirty();
        }
        if (n > 0) StasisDelvers.LOG.info("Hard mode's flash: {} dragon(s) still alive removed", n);
    }

    /** EntityJoinLevelEvent: after the flash no dragon comes back into the End (one saved in a chunk loading later). */
    static void dragonJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (e.getEntity() instanceof EnderDragon && e.getLevel().dimension() == Level.END && e.getLevel() instanceof ServerLevel end
            && StasisConfig.spreading && Spread.hard(end.getServer()) && !holdsEndOpen()) e.setCanceled(true);
    }

    /** The stand-in for step 5's animation: explosions over the island growing denser, white flashes at the end. */
    private static void standIn(ServerLevel end, long t, long flash) {
        if (end.players().isEmpty()) return;
        var rnd = end.getRandom();
        int every = t < flash / 2 ? 10 : 4;
        if (t % every == 0) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 20 + rnd.nextDouble() * 60;
            double x = Math.cos(a) * r, z = Math.sin(a) * r, y = 55 + rnd.nextDouble() * 20;
            end.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0, 0, 0, 0);
            end.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 8f, 0.6f + rnd.nextFloat() * 0.3f);
        }
        if (t >= flash - 10)
            for (ServerPlayer p : end.players()) {
                Vec3 eye = p.getEyePosition().add(p.getLookAngle().scale(2));
                end.sendParticles(p, ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), true, false, eye.x, eye.y, eye.z, 4, 0.5, 0.5, 0.5, 0);
                if (t == flash - 10)
                    end.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 4f, 0.7f);
            }
    }

    /** Send a player out of the End to their respawn point (the stopped-land rule applies), with the advancement. */
    static void rescue(ServerPlayer p) {
        award(p);
        TeleportTransition t = p.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
        if (t.newLevel().dimension() == Level.END) t = TeleportTransition.createDefault(p, TeleportTransition.DO_NOTHING);
        t = Freezing.safeRespawn(p, t);
        if (p.isPassenger()) p.stopRiding();
        p.setDeltaMovement(Vec3.ZERO);
        p.resetFallDistance();
        p.teleport(t);   // (a dimension change: Freezing.grace gives the usual short grace)
        p.sendSystemMessage(Component.literal("The island broke apart around you, and the flash threw you home.").withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    /** Grant "Good job breaking it, hero". */
    static void award(ServerPlayer p) {
        var holder = p.level().getServer().getAdvancements().get(ADVANCEMENT);
        if (holder == null) {
            StasisDelvers.LOG.warn("Advancement {} missing", ADVANCEMENT);
            return;
        }
        for (String c : p.getAdvancements().getOrStartProgress(holder).getRemainingCriteria()) p.getAdvancements().award(holder, c);
    }

    /** LivingDeathEvent: in the window a player dying in the End is sent home instead (Nero's brief: not killed by it). */
    static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !inWindow() || p.level().dimension() != Level.END) return;
        e.setCanceled(true);
        // (a cancelled death leaves health at 0, and the player would die next tick anyway)
        p.setHealth(p.getMaxHealth());
        p.clearFire();
        rescue(p);
    }

    private static final Map<UUID, Long> TOLD = new HashMap<>();

    /**
     * EntityTravelToDimensionEvent: after hard mode nothing goes into the End (a stronghold portal not yet rewritten:
     * HardRifts) but a lance trip (Argon: the guard must let lance arrivals through).
     */
    static void travel(net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent e) {
        if (Lance.travelling(e.getEntity())) return;
        if (e.getDimension() != Level.END || !(e.getEntity().level() instanceof ServerLevel level) || !endClosed(level.getServer())) return;
        if (e.getEntity() instanceof ServerPlayer p) {
            if (p.isCreative() || p.isSpectator()) return;
            long now = level.getGameTime();
            Long last = TOLD.get(p.getUUID());
            if (last == null || now - last > 100 || now < last) {
                TOLD.put(p.getUUID(), now);
                p.sendOverlayMessage(Component.literal("The way to the End is broken: nothing is there to arrive at.").withStyle(ChatFormatting.DARK_PURPLE));
            }
        }
        e.setCanceled(true);
    }

    /**
     * PlayerLoggedInEvent: someone who logs in in the End after the flash (a restart mid-event, a long absence) goes home;
     * not a player who came in by lance (Argon: they'd be evicted from their End base at every login).
     */
    static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && evictsOnLogin(p)) rescue(p);
    }

    /** Would login send this player out of the End (public for tests)? */
    public static boolean evictsOnLogin(ServerPlayer p) {
        if (p.isCreative() || p.isSpectator() || p.level().dimension() != Level.END || Lance.arrivedByLance(p)) return false;
        return endClosed(p.level().getServer()) && !holdsEndOpen();
    }

    static void serverStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        event = null;
        TOLD.clear();
    }

    /** GameTests: forget the End event (after a test entered hard mode). */
    public static void testReset() {
        event = null;
    }

    /** GameTests: has the flash come (null: no event)? */
    public static Boolean testFlashed() {
        EndEvent ev = event;
        return ev == null ? null : ev.flashed;
    }
}
