package org.erisia.stasis;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server's side of the spread's client sync (docs/spreading-mode.md): the state (N, P, hard, complete) to every
 * player when it changes (P at most once a second: it moves about once a second at 8 km and 20 times a second at 30 km,
 * and the clients re-mesh what it crossed), and the rank tiles (SpreadTiles) round each overworld player, nearest
 * first, a few a tick, so the client's rateAt answers like the server's wherever the player has chunks. Tiles far from
 * the player are dropped on both sides (the server says which), so the server's list of a player's tiles is the
 * client's. Main thread only.
 */
public final class SpreadSync {
    private SpreadSync() {
    }

    /**
     * The blocks round a player whose columns the client must answer: the view distance plus a chunk and a margin, and
     * at least the shader's rank window (2048 blocks square, re-centred 384 blocks off; StasisPost.SPREAD_WINDOW), so the
     * grey over Distant Horizons' far terrain has its ranks (Nero; without them it showed running: AdventurAgent).
     * 1-4 more tiles of ~3 KB a player.
     */
    public static int range(MinecraftServer srv) {
        return Math.max((srv.getPlayerList().getViewDistance() + 1) * 16 + 32, DISTANT);
    }

    static final int DISTANT = 1024 + 384;

    /** Tiles beyond the needed ones by this many are dropped (so walking along a tile edge doesn't resend it). */
    static final int KEEP_MARGIN = 1;
    /** At most this many tiles to a player per tick (each ~3 KB). */
    static final int PER_TICK = 4;
    /** P changes reach the clients at most this often (ticks). */
    static final int STATE_EVERY = 20;

    private static final class Tracked {
        final LongOpenHashSet sent = new LongOpenHashSet();
        /** Send the state with reset (and forget the tiles) on the next tick. */
        boolean reset = true;
    }

    private static final Map<UUID, Tracked> PLAYERS = new HashMap<>();
    private static boolean stateDirty, stateForce;
    private static int sinceState;

    /** Spread.update: the state changed (force: completion, hard mode or P going back: send at once). */
    static void stateChanged(boolean force) {
        stateDirty = true;
        stateForce |= force;
    }

    /** A map was installed (or a test map removed): every client starts over. */
    static void mapInstalled() {
        for (Tracked t : PLAYERS.values()) t.reset = true;
        stateDirty = true;
        stateForce = true;
    }

    static void clear() {
        PLAYERS.clear();
        stateDirty = stateForce = false;
    }

    /** Login, respawn, dimension change: the client starts over (its tiles go with its level). */
    static void join(PlayerEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) PLAYERS.computeIfAbsent(p.getUUID(), u -> new Tracked()).reset = true;
    }

    static void leave(PlayerEvent.PlayerLoggedOutEvent e) {
        PLAYERS.remove(e.getEntity().getUUID());
    }

    static StasisNet.SpreadState state(boolean reset) {
        Spread.State s = Spread.server;
        return new StasisNet.SpreadState(reset, s.map() != null, s.count(), s.p(), s.hard(), s.complete(), s.endStill(), s.endEvent(), s.stormOrigin());
    }

    /** ServerTickEvent.Post (after Spread.tick): the state where it changed, then each overworld player's tiles. */
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (!StasisConfig.spreading) return;
        MinecraftServer srv = e.getServer();
        sinceState++;
        boolean sendState = stateDirty && (stateForce || sinceState >= STATE_EVERY);
        StasisNet.SpreadState plain = sendState ? state(false) : null;
        if (sendState) {
            stateDirty = stateForce = false;
            sinceState = 0;
        }
        Spread.State s = Spread.server;
        int range = range(srv);
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            Tracked t = PLAYERS.computeIfAbsent(p.getUUID(), u -> new Tracked());
            if (t.reset) {
                t.reset = false;
                t.sent.clear();
                StasisNet.send(p, state(true));
            } else if (plain != null) StasisNet.send(p, plain);
            if (s.map() == null || s.complete() || p.level().dimension() != Level.OVERWORLD) continue;
            tiles(p, t, s.map(), range);
        }
    }

    /** Sends the missing tiles round the player (nearest first, PER_TICK at most) and drops the far ones. */
    static void tiles(ServerPlayer p, Tracked t, SpreadRanks map, int range) {
        int x = p.getBlockX(), z = p.getBlockZ();
        int[] tx = SpreadTiles.tilesFor(x - range, x + range), tz = SpreadTiles.tilesFor(z - range, z + range);
        List<Long> forget = null;
        for (var it = t.sent.iterator(); it.hasNext(); ) {
            long k = it.nextLong();
            int kx = SpreadTiles.keyX(k), kz = SpreadTiles.keyZ(k);
            if (kx < tx[0] - KEEP_MARGIN || kx > tx[1] + KEEP_MARGIN || kz < tz[0] - KEEP_MARGIN || kz > tz[1] + KEEP_MARGIN) {
                it.remove();
                if (forget == null) forget = new ArrayList<>();
                forget.add(k);
            }
        }
        if (forget != null) StasisNet.send(p, new StasisNet.SpreadForget(forget));
        // the player's own tile first, then by distance
        int ptx = Math.floorDiv(Math.floorDiv(x - SpreadMap.HALF, SpreadMap.CELL), SpreadTiles.TILE);
        int ptz = Math.floorDiv(Math.floorDiv(z - SpreadMap.HALF, SpreadMap.CELL), SpreadTiles.TILE);
        List<long[]> want = new ArrayList<>();
        for (int i = tx[0]; i <= tx[1]; i++)
            for (int k = tz[0]; k <= tz[1]; k++)
                if (!t.sent.contains(SpreadTiles.key(i, k))) want.add(new long[]{(long) (i - ptx) * (i - ptx) + (long) (k - ptz) * (k - ptz), i, k});
        if (want.isEmpty()) return;
        want.sort((a, b) -> Long.compare(a[0], b[0]));
        for (int n = 0; n < Math.min(PER_TICK, want.size()); n++) {
            int i = (int) want.get(n)[1], k = (int) want.get(n)[2];
            t.sent.add(SpreadTiles.key(i, k));
            StasisNet.send(p, new StasisNet.SpreadTile(i, k, SpreadTiles.cut(map, i, k)));
        }
    }

    /** For tests and /stasis spread status: the tiles the server believes this player has. */
    public static int tilesSent(ServerPlayer p) {
        Tracked t = PLAYERS.get(p.getUUID());
        return t == null ? 0 : t.sent.size();
    }
}
