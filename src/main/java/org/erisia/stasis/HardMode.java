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
import net.minecraft.world.level.Level;
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

    /** Within END_WINDOW of the kill (deaths in the End become teleports)? */
    static boolean inWindow() {
        return event != null;
    }

    /** After hard mode no way leads into the End until the lance (Baughn: decision 7); creative and spectators pass. */
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
                if (end != null) EndRift.flash(end);
                Spread.refresh(srv);   // the End stills now
            }
        } else if (t >= END_WINDOW) event = null;
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

    /** EntityTravelToDimensionEvent: after hard mode nothing goes into the End (a stronghold portal not yet rewritten: HardRifts). */
    static void travel(net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent e) {
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

    /** PlayerLoggedInEvent: someone who logs in in the End after the flash (a restart mid-event, a long absence) goes home. */
    static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.isCreative() || p.isSpectator() || p.level().dimension() != Level.END) return;
        if (!endClosed(p.level().getServer()) || holdsEndOpen()) return;
        rescue(p);
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
