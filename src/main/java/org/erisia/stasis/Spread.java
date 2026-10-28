package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * The spreading mode at run time (Baughn 2026-10-19; notes/timestop/spread-DESIGN.md, docs/spreading-mode.md): the
 * world starts running and the stillness spreads from the rifts. An online clock (real seconds while someone is on)
 * gives the coverage c and the progress P = floor(c N) into the overworld's SpreadMap; a column whose rank is below P
 * is stilled and follows the base rules (fields, towers, rifts), one at or above it runs at rate 1 (rifts still stop).
 * The Nether and the End run until the spread is complete (c = 1); then everything behaves as the "stopped" mode.
 * <p>
 * Stasis.rateAt, sectionState, mayRunInColumn and stops() ask here; everything else goes through them. State lives in
 * static volatiles (rateAt is read from the light engine and mesh threads); the saved data (the map, the clock) is
 * only touched on the server thread.
 * <p>
 * CLIENT: the server sends the client N, P, hard mode and completion (StasisNet.SpreadState, at most once a second as P
 * moves) and the rank tiles around the player (SpreadTile, SpreadForget; SpreadSync); {@link #client} holds them, with
 * a SpreadTiles as its map, so the client's rateAt gives the server's answer for every column near the player. Before
 * the state comes (or while the server has no map) the client treats the overworld as running everywhere but rifts.
 */
public final class Spread {
    private Spread() {
    }

    /**
     * One side's view: the map (null until built or received), the progress, hard mode, complete; endStill: the End
     * follows the base rules (stopped) since hard mode's End event teleported everyone out (HardMode); stormOrigin: the
     * storm schedule's origin (game seconds at the hard-mode instant; NaN before hard mode: no storms; Storms.origin);
     * endEvent: the End is breaking up, from the first pillar's stage until the flash (HardMode.endEventOn: the client
     * pushes the End's fog out so the show can be seen, AdventurAgent).
     */
    public record State(SpreadRanks map, int count, int p, boolean hard, boolean complete, boolean endStill, boolean endEvent,
                        double stormOrigin) {
        static final State EMPTY = new State(null, 0, 0, false, false, false, false, Double.NaN);
    }

    /** The server's state (the overworld of the running server). */
    static volatile State server = State.EMPTY;
    /** The client's state, from the server's SpreadState; its map is {@link #clientTiles} (or null: no map). */
    public static volatile State client = State.EMPTY;
    /** The rank tiles the server sent this client (the client's map once the server says it has one). */
    public static final SpreadTiles clientTiles = new SpreadTiles();

    /** What the client needs to hear (set by client/StasisClient): to re-mesh and relight where the answers changed. */
    public interface ClientListener {
        /** P moved: columns whose rank1024 lies in [lo, hi) changed (stilled now if P grew). */
        void moved(long lo1024, long hi1024);

        /** Tile (tx, tz) arrived: its columns were MISSING (running) until now. */
        void tile(int tx, int tz);
    }

    public static volatile ClientListener clientListener;

    // ---- the client (StasisNet handlers, main thread)

    static void clientState(boolean reset, boolean hasMap, int count, int p, boolean hard, boolean complete, boolean endStill, boolean endEvent,
                            double stormOrigin) {
        State old = client;
        if (reset || !hasMap) clientTiles.clear();
        client = new State(hasMap ? clientTiles : null, count, p, hard, complete, endStill, endEvent, stormOrigin);
        // answers cached per section (FrozenSprites) re-ask; chunk meshes and flame light are redone where P moved
        Stasis.bumpVersion();
        ClientListener l = clientListener;
        if (l != null && !reset && hasMap && old.map() != null && old.p() != p)
            l.moved((long) Math.min(p, old.p()) * SpreadMap.SCALE, (long) Math.max(p, old.p()) * SpreadMap.SCALE);
    }

    static void clientTile(int tx, int tz, int[] ranks) {
        clientTiles.put(tx, tz, ranks);
        Stasis.bumpVersion();
        ClientListener l = clientListener;
        if (l != null && client.map() != null) l.tile(tx, tz);
    }

    static void clientForget(java.util.List<Long> keys) {
        for (long k : keys) clientTiles.remove(k);
        Stasis.bumpVersion();
    }

    /** Leaving the server: forget everything. */
    public static void clientClear() {
        clientTiles.clear();
        client = State.EMPTY;
        Stasis.bumpVersion();
    }

    /** Tests: the clock leaves the state alone while a test map is installed. */
    static volatile boolean testing;

    static State state(Level level) {
        return level.isClientSide() ? client : server;
    }

    /** Is the spread done (c = 1), so everything follows the base rules? Only in spreading mode. */
    static boolean complete(Level level) {
        return state(level).complete();
    }

    /** Does the spread decide this level's columns now: spreading mode, the overworld, not complete? */
    public static boolean active(Level level) {
        return StasisConfig.spreading && level.dimension() == Level.OVERWORLD && !state(level).complete();
    }

    /**
     * Spreading mode before completion: the Nether and the End run (Baughn: decision 1). The End only until hard mode's
     * End event has teleported everyone out (step 4a: the End becomes the rift world, step 5); not at the very instant
     * of the kill, or the players there would turn to statues (5 s) before the teleport at the flash peak.
     */
    static boolean holdsOff(Level level) {
        if (!StasisConfig.spreading || level.dimension() == Level.OVERWORLD) return false;
        State s = state(level);
        if (s.complete()) return false;
        return level.dimension() != Level.END || !s.endStill();
    }

    /**
     * Does column (x, z) still run (its rank is at or above P)? Only while active(level). No map yet (being built, or
     * on the client before the tiles come): everything runs.
     */
    static boolean runs(Level level, int x, int z) {
        State s = state(level);
        return s.map() == null || !SpreadMap.stilled(s.map(), x, z, s.p());
    }

    /** The stillness field at column (x, z): rank1024 - P x SCALE (stilled where < 0), for the client's edge curtain
     *  (StillEdge: its zero line, smooth across columns); Long.MIN_VALUE without a map. */
    public static long edgeField(Level level, int x, int z) {
        State s = state(level);
        if (s.map() == null) return Long.MIN_VALUE;
        return SpreadMap.rank1024(s.map(), x, z) - (long) s.p() * SpreadMap.SCALE;
    }

    /** Chunk (cx, cz) in spreading mode: 1 every column runs, 0 every column is stilled, 2 some of each. */
    static int chunk(Level level, int cx, int cz) {
        State s = state(level);
        if (s.map() == null) return 1;
        long p = (long) s.p() * SpreadMap.SCALE;
        if (SpreadMap.chunkMin1024(s.map(), cx, cz) >= p) return 1;
        if (SpreadMap.chunkMax1024(s.map(), cx, cz) < p) return 0;
        return 2;
    }

    // ---- the clock -------------------------------------------------------------------------------------------------

    /** Most real time one server tick may add (a stalled server mustn't jump the front). */
    static final double MAX_TICK_SECONDS = 1.0;

    /**
     * The coverage at online time t (s): spreadCap * t / spreadHours up to the cap; after hard mode (entered at online
     * time tHard, with coverage cHard then) it rises from cHard to 1 over hardSpreadHours. cHard is the coverage at the
     * instant, so a dragon killed before the cap is reached doesn't jump the front to the cap (step 4a: the stillness
     * goes on from where it was, only on a steeper course).
     */
    static double coverage(double t, boolean hard, double tHard, double cHard, double hours, double hardHours, double cap) {
        if (!hard) return Math.min(cap, cap * Math.max(0, t) / (hours * 3600));
        return cHard + (1 - cHard) * Math.min(1, Math.max(0, t - tHard) / (hardHours * 3600));
    }

    /**
     * The online seconds, from online time t, until the column of rank r1024 stills (front warnings, /stasis spread
     * status; Argon: the time until this column stills, not the distance to the front, so a dead-end cave whose mouth
     * stills first is still warned by its own column). The column stills at the first P with r1024 < P * SCALE, so
     * P = k = r1024 / SCALE + 1, coverage k / n. 0 if it is stilled already; +Infinity if the coverage it needs lies
     * above the cap and hard mode hasn't begun (P stands still at the cap: nothing more stills).
     */
    static double secondsUntilStill(long r1024, int n, double t, boolean hard, double tHard, double cHard, double hours, double hardHours, double cap) {
        long k = Math.floorDiv(r1024, SpreadMap.SCALE) + 1;
        if (k <= progress(coverage(t, hard, tHard, cHard, hours, hardHours, cap), n)) return 0;
        double cAt = k / (double) n;
        double tAt;
        // (whether P reaches k before hard mode: with progress's own rounding, as cap * n can fall a hair below k)
        if (k <= progress(cap, n) && !hard) tAt = cap <= 0 ? 0 : cAt / cap * hours * 3600;
        else if (!hard) return Double.POSITIVE_INFINITY;
        else tAt = tHard + (cHard >= 1 ? 0 : Math.max(0, cAt - cHard) / (1 - cHard) * hardHours * 3600);
        return Math.max(0, tAt - t);
    }

    /** secondsUntilStill for column (x, z) of the server's map now, or NaN without a map. */
    static double secondsUntilStill(MinecraftServer srv, int x, int z) {
        State s = server;
        if (s.map() == null) return Double.NaN;
        Clock k = Clock.get(srv.overworld());
        return secondsUntilStill(SpreadMap.rank1024(s.map(), x, z), s.count(), k.seconds, k.hard, k.hardAt, k.hardCoverage(),
            StasisConfig.spreadHours, StasisConfig.hardSpreadHours, StasisConfig.spreadCap);
    }

    /** P for coverage c of n cells. */
    static int progress(double c, int n) {
        return (int) Math.min(n, Math.floor(c * n));
    }

    /** The real seconds a tick adds: the wall-clock gap since the last tick, capped; none while nobody is on. */
    static double tickSeconds(long lastNanos, long nowNanos, int players) {
        if (players <= 0 || lastNanos == 0) return 0;
        return Math.max(0, Math.min(MAX_TICK_SECONDS, (nowNanos - lastNanos) / 1e9));
    }

    private static long lastNanos;

    static double coverage(Clock k) {
        return coverage(k.seconds, k.hard, k.hardAt, k.hardCoverage(), StasisConfig.spreadHours, StasisConfig.hardSpreadHours, StasisConfig.spreadCap);
    }

    /** ServerTickEvent.Post: run the online clock and move P. */
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (!StasisConfig.spreading || testing) return;
        MinecraftServer srv = e.getServer();
        ServerLevel ow = srv.overworld();
        Clock k = Clock.get(ow);
        long now = System.nanoTime();
        double dt = tickSeconds(lastNanos, now, srv.getPlayerCount());
        lastNanos = now;
        if (dt > 0) {
            k.seconds += dt;
            k.setDirty();
        }
        update(ow, k);
    }

    /** Recompute P and completion from the clock; P changes bump Stasis.version, completion invalidates every level. */
    static void update(ServerLevel ow, Clock k) {
        MinecraftServer srv = ow.getServer();
        State s = server;
        double c = coverage(k);
        int p = s.map() == null ? 0 : progress(c, s.count());
        boolean complete = s.map() != null && c >= 1;
        // the storms' origin: game time at the hard-mode instant (a save in hard mode from before it was kept starts
        // storms now: they were off until now, so tau doesn't jump either)
        if (k.hard && k.hardGame < 0) {
            k.hardGame = ow.getGameTime() / 20.0;
            k.setDirty();
        }
        double origin = k.hard ? k.hardGame : Double.NaN;
        boolean endStill = k.hard && !HardMode.holdsEndOpen();
        boolean endEvent = !endStill && (k.hard ? HardMode.holdsEndOpen() : srv.getLevel(Level.END) instanceof ServerLevel end0 && EndRift.begun(end0));
        if (p == s.p() && complete == s.complete() && k.hard == s.hard() && endStill == s.endStill() && endEvent == s.endEvent()
            && Double.compare(origin, s.stormOrigin()) == 0) return;
        // completion stops the Nether and the End, and the End event's end the End: players there who were safe get the
        // usual grace (as a field going)
        Map<ServerLevel, List<ServerPlayer>> safe = new HashMap<>();
        if (complete != s.complete()) for (ServerLevel l : srv.getAllLevels()) safe.put(l, Freezing.running(l));
        ServerLevel end = srv.getLevel(Level.END);
        if (endStill != s.endStill() && end != null) safe.put(end, Freezing.running(end));
        // the front passing over a player who stood in running land: the same grace as a field going (Baughn: leeway,
        // AdventurAgent check 6: a player 2.5 blocks inside stilled land dies in ~5 s without it)
        List<ServerPlayer> overrun = p > s.p() && !safe.containsKey(ow) ? Freezing.running(ow) : null;
        server = new State(s.map(), s.count(), p, k.hard, complete, endStill, endEvent, origin);
        // nothing on the server caches a P-dependent answer (the field index is fields only, StaticDeeps' section cache
        // is geometry, and Stasis.version is read by client chunk meshing only), so no version bump here: a global bump
        // every second would only make clients re-ask. The clients hear of P at most once a second (SpreadSync) and
        // re-mesh just the chunks whose [chunkMin1024, chunkMax1024] it crossed.
        SpreadSync.stateChanged(complete != s.complete() || k.hard != s.hard() || p < s.p() || endStill != s.endStill() || endEvent != s.endEvent()
            || Double.compare(origin, s.stormOrigin()) != 0);
        // flame light is cached in the light engine: relight the loaded chunks the front just crossed (chunks loaded
        // later relight on load, Flames.chunkLoad)
        if (p != s.p() && s.map() != null) {
            long lo = (long) Math.min(p, s.p()) * SpreadMap.SCALE, hi = (long) Math.max(p, s.p()) * SpreadMap.SCALE;
            SpreadRanks m = s.map();
            relightNearPlayers(srv, ow, (cx, cz) -> SpreadMap.chunkMin1024(m, cx, cz) < hi && SpreadMap.chunkMax1024(m, cx, cz) >= lo);
        }
        // (in chat: the grace countdown takes the action bar at once and would hide it)
        if (overrun != null) Freezing.graceIfStopped(ow, overrun, "The stillness has reached you: time stops here. Get back to running land!", true);
        if (complete != s.complete()) {
            for (ServerLevel l : srv.getAllLevels()) Stasis.invalidate(l);
            for (ServerLevel l : srv.getAllLevels()) if (l.dimension() != Level.OVERWORLD) relightNearPlayers(srv, l, (cx, cz) -> true);
            StasisDelvers.LOG.info("The stillness is complete: every dimension follows the base rules now");
        } else if (endStill != s.endStill() && end != null) {
            Stasis.invalidate(end);
            relightNearPlayers(srv, end, (cx, cz) -> true);
            StasisDelvers.LOG.info("The End {}", endStill ? "is stilled: it follows the base rules now" : "runs");
        }
        safe.forEach(Freezing::graceIfStopped);
    }

    /** Flames.relightChunk on the loaded chunks within view distance of the level's players that pass the test. */
    private static void relightNearPlayers(MinecraftServer srv, ServerLevel level, java.util.function.BiPredicate<Integer, Integer> test) {
        if (level.players().isEmpty()) return;
        int r = srv.getPlayerList().getViewDistance() + 1;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet done = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (ServerPlayer pl : level.players()) {
            int pcx = pl.getBlockX() >> 4, pcz = pl.getBlockZ() >> 4;
            for (int cx = pcx - r; cx <= pcx + r; cx++)
                for (int cz = pcz - r; cz <= pcz + r; cz++)
                    if (done.add(net.minecraft.world.level.ChunkPos.pack(cx, cz)) && test.test(cx, cz)) Flames.relightChunk(level, cx, cz, null);
        }
    }

    // ---- life cycle ------------------------------------------------------------------------------------------------

    /** Overworld load: an existing map comes back before any chunk asks rateAt. */
    static void levelLoad(ServerLevel level) {
        if (!StasisConfig.spreading || level.dimension() != Level.OVERWORLD) return;
        MapData d = MapData.get(level);
        if (d.ranks == null) return;
        try {
            // a map made by an older algorithm is kept as it is: it is the world's map (its spawn is pinned, and
            // rebuilding would move the front under existing bases); only new worlds get the current algorithm
            if (d.algorithm != SpreadMap.ALGORITHM)
                StasisDelvers.LOG.info("Keeping this world's spread map of algorithm {} (current {})", d.algorithm, SpreadMap.ALGORITHM);
            install(level, new SpreadMap(d.spawnX, d.spawnZ, d.radius, d.ranks));
        } catch (IllegalArgumentException ex) {
            StasisDelvers.LOG.error("Spread map unreadable ({}); the overworld runs until it is rebuilt", ex.getMessage());
        }
    }

    /**
     * Server started: a world's first start in spreading mode builds its map, around the spawn as it is now (pinned:
     * a later /setworldspawn doesn't move the guarantee), after the deep seed exists (made at level load). Until it is
     * built the overworld counts as running everywhere, which is what a new world is anyway.
     */
    static void serverStarted(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        if (!StasisConfig.spreading) return;
        ServerLevel ow = e.getServer().overworld();
        if (server.map() == null) levelLoad(ow);   // (a saved map is never rebuilt: its spawn is pinned)
        if (server.map() != null) {
            update(ow, Clock.get(ow));
            return;
        }
        int seed = FieldData.get(ow).deepSeed();
        var spawn = ow.getRespawnData().pos();
        long t0 = System.nanoTime();
        SpreadMap m = SpreadMap.build(seed, spawn.getX(), spawn.getZ(), StasisConfig.spreadRadius, StasisConfig.spreadConnectedUntil);
        StasisDelvers.LOG.info("Spread map built in {} ms: {} cells of {} blocks within {} of spawn {} {}", (System.nanoTime() - t0) / 1_000_000,
            m.count, SpreadMap.CELL, m.radius, spawn.getX(), spawn.getZ());
        MapData d = MapData.get(ow);
        d.set(m);
        install(ow, m);
    }

    private static void install(ServerLevel ow, SpreadMap m) {
        server = new State(m, m.count, 0, false, false, false, false, Double.NaN);
        SpreadSync.mapInstalled();
        lastNanos = 0;
        update(ow, Clock.get(ow));
    }

    static void serverStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        server = State.EMPTY;
        SpreadSync.clear();
        StabilityCompassItem.clearCache();
        lastNanos = 0;
        testing = false;
        SpawnMove.reset();
    }

    // ---- commands --------------------------------------------------------------------------------------------------

    /** /stasis spread set: move the clock so the coverage is c (tests). Returns an error, or null. */
    static String setCoverage(MinecraftServer srv, double c) {
        Clock k = Clock.get(srv.overworld());
        double cap = StasisConfig.spreadCap;
        if (!k.hard) {
            if (c > cap) return "Coverage above the cap (" + cap + ") needs hard mode: /stasis spread hardmode";
            k.seconds = cap <= 0 ? 0 : c / cap * StasisConfig.spreadHours * 3600;
        } else {
            double ch = k.hardCoverage();
            if (c < ch) return "In hard mode the coverage is at least what it was when hard mode began (" + ch + ")";
            k.seconds = k.hardAt + (ch >= 1 ? 0 : (c - ch) / (1 - ch) * StasisConfig.hardSpreadHours * 3600);
        }
        k.setDirty();
        update(srv.overworld(), k);
        return null;
    }

    /**
     * Hard mode's clock (HardMode.enter, the one entry point: the dragon's death and /stasis spread hardmode). Once
     * only: false if hard mode had begun. Keeps the online time, the coverage (the course goes on from it) and the game
     * time (the storms' origin) of the instant; the caller updates the state.
     */
    static boolean startHardClock(MinecraftServer srv) {
        ServerLevel ow = srv.overworld();
        Clock k = Clock.get(ow);
        if (k.hard) return false;
        k.hardCoverage = coverage(k);   // (before hard is set: the coverage as it stands, the course goes on from it)
        k.hard = true;
        k.hardAt = k.seconds;
        k.hardGame = ow.getGameTime() / 20.0;
        k.setDirty();
        StasisDelvers.LOG.info("Hard mode at {} h online, coverage {}, storms from game time {} s", k.seconds / 3600, k.hardCoverage, k.hardGame);
        return true;
    }

    /** Recompute the state from the clock now (HardMode: after entry, and when the End event lets the End still). */
    static void refresh(MinecraftServer srv) {
        update(srv.overworld(), Clock.get(srv.overworld()));
    }

    /**
     * Has hard mode begun, as the server's state last said (any thread: StrongholdPortalRoomMixin on worldgen threads).
     * It follows the clock from the hard-mode instant on (HardMode.enter refreshes it); false before the server started.
     */
    public static boolean hardNow() {
        return server.hard();
    }

    /** Is the End event on, as the server's state last said (the clients' fog; GameTests)? */
    public static boolean endEventNow() {
        return server.endEvent();
    }

    /** Has hard mode begun (server; the saved clock, also while a test map is installed)? */
    public static boolean hard(MinecraftServer srv) {
        return Clock.get(srv.overworld()).hard;
    }

    /** For /stasis spread status. */
    public static String status(MinecraftServer srv, ServerPlayer p) {
        Clock k = Clock.get(srv.overworld());
        State s = server;
        StringBuilder b = new StringBuilder();
        b.append("mode ").append(StasisConfig.spreading ? "spreading" : "stopped");
        if (!StasisConfig.spreading) return b.toString();
        double c = coverage(k);
        b.append(String.format(java.util.Locale.ROOT, ", coverage %.4f, P %d / %d, online %.2f h, hard mode %s%s", c, s.p(), s.count(),
            k.seconds / 3600, k.hard ? String.format(java.util.Locale.ROOT, "since %.2f h (from coverage %.4f)", k.hardAt / 3600, k.hardCoverage()) : "no",
            s.complete() ? ", complete" : ""));
        if (s.map() == null) return b.append(", no map yet").toString();
        b.append("; ").append(SpawnMove.status(k));
        if (p != null && p.level().dimension() == Level.OVERWORLD) {
            long r = SpreadMap.rank1024(s.map(), p.getBlockX(), p.getBlockZ());
            double rank = r / (double) SpreadMap.SCALE;
            b.append(String.format(java.util.Locale.ROOT, "; here: rank %.1f, %s", rank, r < (long) s.p() * SpreadMap.SCALE ? "stilled" : "running"));
            if (r >= (long) s.p() * SpreadMap.SCALE) {
                // the online time until this column stills at the current pace (FrontWarnings' estimate)
                double left = secondsUntilStill(srv, p.getBlockX(), p.getBlockZ());
                if (Double.isInfinite(left)) b.append(" (stills only after hard mode)");
                else b.append(String.format(java.util.Locale.ROOT, " (stills in ~%.2f online h)", left / 3600));
            }
        }
        return b.toString();
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    private static State saved;
    private static boolean savedMode;

    /** GameTests: spreading mode with this map at progress p (the clock leaves it alone) until testReset. */
    public static void testInstall(SpreadMap m, int p) {
        if (!testing) {
            saved = server;
            savedMode = StasisConfig.spreading;
        }
        testing = true;
        StasisConfig.spreading = true;
        server = new State(m, m.count, p, false, false, false, false, Double.NaN);
        SpreadSync.mapInstalled();
        Stasis.bumpVersion();
    }

    public static void testReset() {
        if (!testing) return;
        server = saved;
        StasisConfig.spreading = savedMode;
        testing = false;
        SpawnMove.testArm(false);
        SpreadSync.mapInstalled();
        Stasis.bumpVersion();
    }

    /**
     * GameTests: set the online clock (and move P to match it on the installed map); returns {seconds, hard, hardAt,
     * hardCoverage, hardGame} as it was. Hard mode set here starts from the coverage its instant had (continuity).
     */
    public static double[] testClock(MinecraftServer srv, double seconds, boolean hard, double hardAt) {
        Clock k = Clock.get(srv.overworld());
        double[] was = {k.seconds, k.hard ? 1 : 0, k.hardAt, k.hardCoverage, k.hardGame};
        k.seconds = seconds;
        k.hard = hard;
        k.hardAt = hardAt;
        k.hardCoverage = coverage(hardAt, false, 0, 0, StasisConfig.spreadHours, StasisConfig.hardSpreadHours, StasisConfig.spreadCap);
        k.setDirty();
        update(srv.overworld(), k);
        return was;
    }

    /** GameTests: the server's state now. */
    public static State testServerState() {
        return server;
    }

    /** GameTests: put the clock back as testClock returned it. */
    public static void testClockRestore(MinecraftServer srv, double[] was) {
        Clock k = Clock.get(srv.overworld());
        k.seconds = was[0];
        k.hard = was[1] != 0;
        k.hardAt = was[2];
        k.hardCoverage = was[3];
        k.hardGame = was[4];
        k.setDirty();
        update(srv.overworld(), k);
    }

    /** GameTests: the online seconds until column (x, z) stills (NaN without a map). */
    public static double testSecondsUntilStill(MinecraftServer srv, int x, int z) {
        return secondsUntilStill(srv, x, z);
    }

    // ---- saved data ------------------------------------------------------------------------------------------------

    /** The online clock (overworld, stasisdelvers:spread_clock): dirty all the time, so apart from the big map. */
    public static final class Clock extends SavedData {
        static final Codec<Clock> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.optionalFieldOf("seconds", 0.0).forGetter(k -> k.seconds),
            Codec.BOOL.optionalFieldOf("hard", false).forGetter(k -> k.hard),
            Codec.DOUBLE.optionalFieldOf("hard_at", 0.0).forGetter(k -> k.hardAt),
            // the coverage when hard mode began (-1: a save from before it was kept, which went on from the cap)
            Codec.DOUBLE.optionalFieldOf("hard_c", -1.0).forGetter(k -> k.hardCoverage),
            // the game time (s, Deeps.realTime) when hard mode began: the storm schedule's origin (-1: not yet known)
            Codec.DOUBLE.optionalFieldOf("hard_game", -1.0).forGetter(k -> k.hardGame),
            // the world spawn's one move (SpawnMove): 0 not moved, 1 moved, 2 no site found; from (old x, z) to (x, y, z)
            Codec.INT.optionalFieldOf("spawn_move", SpawnMove.NONE).forGetter(k -> k.spawnMove),
            Codec.INT.optionalFieldOf("spawn_old_x", 0).forGetter(k -> k.spawnOldX),
            Codec.INT.optionalFieldOf("spawn_old_z", 0).forGetter(k -> k.spawnOldZ),
            Codec.INT.optionalFieldOf("spawn_x", 0).forGetter(k -> k.spawnX),
            Codec.INT.optionalFieldOf("spawn_y", 0).forGetter(k -> k.spawnY),
            Codec.INT.optionalFieldOf("spawn_z", 0).forGetter(k -> k.spawnZ)
        ).apply(i, Clock::new));
        static final SavedDataType<Clock> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "spread_clock"), Clock::new, CODEC);

        /** Online seconds (real time while at least one player is on). */
        double seconds;
        boolean hard;
        /** Online seconds when hard mode began. */
        double hardAt;
        /** The coverage when hard mode began (-1: unknown, an older save: the cap, as it then was). */
        double hardCoverage = -1;
        /** Game time (s) when hard mode began: the storms' origin (Storms; -1 unknown). */
        double hardGame = -1;
        /** The world spawn's move (SpawnMove; once only, Baughn 2026-10-19). */
        int spawnMove, spawnOldX, spawnOldZ, spawnX, spawnY, spawnZ;

        public Clock() {
        }

        private Clock(double seconds, boolean hard, double hardAt, double hardCoverage, double hardGame, int spawnMove, int spawnOldX, int spawnOldZ,
                      int spawnX, int spawnY, int spawnZ) {
            this.seconds = seconds;
            this.hard = hard;
            this.hardAt = hardAt;
            this.hardCoverage = hardCoverage;
            this.hardGame = hardGame;
            this.spawnMove = spawnMove;
            this.spawnOldX = spawnOldX;
            this.spawnOldZ = spawnOldZ;
            this.spawnX = spawnX;
            this.spawnY = spawnY;
            this.spawnZ = spawnZ;
        }

        /** GameTests: the spawn move's state, to put back afterwards ({spawnMove, oldX, oldZ, x, y, z}). */
        public static int[] testSpawnMove(MinecraftServer srv, int[] set) {
            Clock k = get(srv.overworld());
            int[] was = {k.spawnMove, k.spawnOldX, k.spawnOldZ, k.spawnX, k.spawnY, k.spawnZ};
            if (set != null) {
                k.spawnMove = set[0];
                k.spawnOldX = set[1];
                k.spawnOldZ = set[2];
                k.spawnX = set[3];
                k.spawnY = set[4];
                k.spawnZ = set[5];
                k.setDirty();
            }
            return was;
        }

        /** The coverage hard mode went on from: as saved, or the cap for a save from before it was kept. */
        double hardCoverage() {
            return hardCoverage < 0 ? StasisConfig.spreadCap : hardCoverage;
        }

        static Clock get(ServerLevel overworld) {
            return overworld.getDataStorage().computeIfAbsent(TYPE);
        }
    }

    /** The map (overworld, stasisdelvers:spread_map): written once. */
    public static final class MapData extends SavedData {
        static final Codec<MapData> CODEC = RecordCodecBuilder.create(i -> i.group(
            // a missing field is algorithm 1: DFU leaves out a field equal to its default, and 1 was the default while
            // it was the only one, so every v1 save lacks it
            Codec.INT.optionalFieldOf("algorithm", 1).forGetter(d -> d.algorithm),
            Codec.INT.optionalFieldOf("spawn_x", 0).forGetter(d -> d.spawnX),
            Codec.INT.optionalFieldOf("spawn_z", 0).forGetter(d -> d.spawnZ),
            Codec.INT.optionalFieldOf("radius", 0).forGetter(d -> d.radius),
            Codec.INT_STREAM.xmap(IntStream::toArray, IntStream::of).optionalFieldOf("ranks", new int[0])
                .forGetter(d -> d.ranks == null ? new int[0] : d.ranks)
        ).apply(i, MapData::new));
        static final SavedDataType<MapData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "spread_map"), MapData::new, CODEC);

        int algorithm = SpreadMap.ALGORITHM, spawnX, spawnZ, radius;
        /** null: not built. */
        int[] ranks;

        public MapData() {
        }

        private MapData(int algorithm, int spawnX, int spawnZ, int radius, int[] ranks) {
            this.algorithm = algorithm;
            this.spawnX = spawnX;
            this.spawnZ = spawnZ;
            this.radius = radius;
            this.ranks = ranks.length == 0 ? null : ranks;
        }

        void set(SpreadMap m) {
            algorithm = SpreadMap.ALGORITHM;
            spawnX = m.spawnX;
            spawnZ = m.spawnZ;
            radius = m.radius;
            ranks = m.ranks;
            setDirty();
        }

        static MapData get(ServerLevel overworld) {
            return overworld.getDataStorage().computeIfAbsent(TYPE);
        }
    }
}
