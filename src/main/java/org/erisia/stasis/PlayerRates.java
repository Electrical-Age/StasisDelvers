package org.erisia.stasis;

import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.TickRateManager;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Players in a slow field run slow: their client is sent the field's tick rate (vanilla's ticking-state packet, 20 x
 * rate), so movement, animations, mining and eating all slow down together on their screen, and the server counts the
 * player's own countdowns (mining progress, item use, attack cooldown) only in the ticks that rate runs in (mixins
 * ServerPlayerGameModeMixin, LivingEntityMixin), so it agrees with the client. Folded in from ErisiAgent's tickzone
 * 0.2.0 "client" mode.
 * <p>
 * The player's rate is Stasis.rateAt at their feet block (the whole-block rule Freezing uses). Stopped land counts as
 * full speed: suited players there act at 20 TPS as before (Freezing, Mining Fatigue), so walking from a slow field into
 * stopped land speeds a suited player back up. Re-sent every second while slow, because vanilla broadcasts the global
 * state to everyone on any /tick change (and on respawn); the global state goes back once on leaving.
 */
public final class PlayerRates {
    private PlayerRates() {
    }

    private record Sent(float rate, boolean frozen) {
    }

    private static final Map<UUID, Sent> SENT = new HashMap<>();
    private static int counter;

    /** The player's tick rate in (0, 1]: their field's rate, 1 elsewhere (stopped land too). */
    public static double rate(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) return 1.0;
        double r = Stasis.rateAt(level, p.blockPosition());
        return r <= 0.0 || r >= 1.0 ? 1.0 : r;
    }

    /** Does the player's own clock run in this game tick? */
    public static boolean ticksNow(ServerPlayer p) {
        double r = rate(p);
        return r >= 1.0 || Stasis.runs(r, p.level().getGameTime());
    }

    static void tick(ServerTickEvent.Post e) {
        tick(e.getServer());
    }

    static void tick(MinecraftServer server) {
        boolean refresh = ++counter % 20 == 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) update(server, p, refresh);
    }

    /** Send the player their rate if it changed (or, while slow, on every refresh). Public for tests. */
    public static void update(MinecraftServer server, ServerPlayer p, boolean refresh) {
        TickRateManager global = server.tickRateManager();
        double r = rate(p);
        Sent want = new Sent(r >= 1.0 ? global.tickrate() : (float) Math.max(1.0, global.tickrate() * r), global.isFrozen());
        Sent had = SENT.get(p.getUUID());
        if (had == null && r >= 1.0) return;   // never slowed: vanilla's state is what the client has
        if (want.equals(had) && !(refresh && r < 1.0)) return;
        if (r >= 1.0) SENT.remove(p.getUUID());
        else SENT.put(p.getUUID(), want);
        if (p.connection != null) p.connection.send(new ClientboundTickingStatePacket(want.rate(), want.frozen()));
    }

    /** For tests: the tick rate last sent to this player, or 0 if they have the server's. */
    public static float sent(ServerPlayer p) {
        Sent s = SENT.get(p.getUUID());
        return s == null ? 0f : s.rate();
    }

    static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        SENT.remove(e.getEntity().getUUID());
    }
}
