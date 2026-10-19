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
 * CLIENT: the client has no map or P yet (the next step sends rank tiles and P): until then {@link #client} is empty and
 * the client treats the overworld as running everywhere but rifts, and the spread as not complete.
 */
public final class Spread {
    private Spread() {
    }

    /** One side's view: the map (null until built or received), the progress, hard mode, complete. */
    record State(SpreadRanks map, int count, int p, boolean hard, boolean complete) {
        static final State EMPTY = new State(null, 0, 0, false, false);
    }

    /** The server's state (the overworld of the running server). */
    static volatile State server = State.EMPTY;
    /** The client's state: CLIENT STEP fills this from the server's tiles and P (rank tiles as a SpreadRanks). */
    public static volatile State client = State.EMPTY;

    /** Tests: the clock leaves the state alone while a test map is installed. */
    private static volatile boolean testing;

    static State state(Level level) {
        return level.isClientSide() ? client : server;
    }

    /** Is the spread done (c = 1), so everything follows the base rules? Only in spreading mode. */
    static boolean complete(Level level) {
        return state(level).complete();
    }

    /** Does the spread decide this level's columns now: spreading mode, the overworld, not complete? */
    static boolean active(Level level) {
        return StasisConfig.spreading && level.dimension() == Level.OVERWORLD && !state(level).complete();
    }

    /** Spreading mode before completion: the Nether and the End run (Baughn: decision 1). */
    static boolean holdsOff(Level level) {
        return StasisConfig.spreading && level.dimension() != Level.OVERWORLD && !state(level).complete();
    }

    /**
     * Does column (x, z) still run (its rank is at or above P)? Only while active(level). No map yet (being built, or
     * on the client before the tiles come): everything runs.
     */
    static boolean runs(Level level, int x, int z) {
        State s = state(level);
        return s.map() == null || !SpreadMap.stilled(s.map(), x, z, s.p());
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
     * time tHard) it rises from the cap to 1 over hardSpreadHours.
     */
    static double coverage(double t, boolean hard, double tHard, double hours, double hardHours, double cap) {
        if (!hard) return Math.min(cap, cap * Math.max(0, t) / (hours * 3600));
        return cap + (1 - cap) * Math.min(1, Math.max(0, t - tHard) / (hardHours * 3600));
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
        return coverage(k.seconds, k.hard, k.hardAt, StasisConfig.spreadHours, StasisConfig.hardSpreadHours, StasisConfig.spreadCap);
    }

    /** ServerTickEvent.Post: run the online clock and move P. */
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (!StasisConfig.spreading || testing) return;
        MinecraftServer srv = e.getServer();
        Clock k = Clock.get(srv.overworld());
        long now = System.nanoTime();
        double dt = tickSeconds(lastNanos, now, srv.getPlayerCount());
        lastNanos = now;
        if (dt > 0) {
            k.seconds += dt;
            k.setDirty();
        }
        update(srv, k);
    }

    /** Recompute P and completion from the clock; P changes bump Stasis.version, completion invalidates every level. */
    static void update(MinecraftServer srv, Clock k) {
        State s = server;
        double c = coverage(k);
        int p = s.map() == null ? 0 : progress(c, s.count());
        boolean complete = s.map() != null && c >= 1;
        if (p == s.p() && complete == s.complete() && k.hard == s.hard()) return;
        // completion stops the Nether and the End: players there who were safe get the usual grace (as a field going)
        Map<ServerLevel, List<ServerPlayer>> safe = new HashMap<>();
        if (complete != s.complete()) for (ServerLevel l : srv.getAllLevels()) safe.put(l, Freezing.running(l));
        server = new State(s.map(), s.count(), p, k.hard, complete);
        // nothing on the server caches a P-dependent answer (the field index is fields only; StaticDeeps' section
        // cache is geometry): the bump is for version() readers. CLIENT STEP: don't re-mesh the world on each P
        // change; re-mesh the chunks whose [chunkMin1024, chunkMax1024] P crossed.
        Stasis.bumpVersion();
        // flame light is cached in the light engine: relight the loaded chunks the front just crossed (chunks loaded
        // later relight on load, Flames.chunkLoad)
        if (p != s.p() && s.map() != null) {
            long lo = (long) Math.min(p, s.p()) * SpreadMap.SCALE, hi = (long) Math.max(p, s.p()) * SpreadMap.SCALE;
            SpreadRanks m = s.map();
            relightNearPlayers(srv, srv.overworld(), (cx, cz) -> SpreadMap.chunkMin1024(m, cx, cz) < hi && SpreadMap.chunkMax1024(m, cx, cz) >= lo);
        }
        if (complete != s.complete()) {
            for (ServerLevel l : srv.getAllLevels()) Stasis.invalidate(l);
            for (ServerLevel l : srv.getAllLevels()) if (l.dimension() != Level.OVERWORLD) relightNearPlayers(srv, l, (cx, cz) -> true);
            safe.forEach(Freezing::graceIfStopped);
            StasisDelvers.LOG.info("The stillness is complete: every dimension follows the base rules now");
        }
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
            install(level.getServer(), new SpreadMap(d.spawnX, d.spawnZ, d.radius, d.ranks));
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
        if (server.map() != null) {
            update(e.getServer(), Clock.get(ow));
            return;
        }
        int seed = FieldData.get(ow).deepSeed();
        var spawn = ow.getRespawnData().pos();
        long t0 = System.nanoTime();
        SpreadMap m = SpreadMap.build(seed, spawn.getX(), spawn.getZ(), StasisConfig.spreadRadius);
        StasisDelvers.LOG.info("Spread map built in {} ms: {} cells of {} blocks within {} of spawn {} {}", (System.nanoTime() - t0) / 1_000_000,
            m.count, SpreadMap.CELL, m.radius, spawn.getX(), spawn.getZ());
        MapData d = MapData.get(ow);
        d.set(m);
        install(e.getServer(), m);
    }

    private static void install(MinecraftServer srv, SpreadMap m) {
        server = new State(m, m.count, 0, false, false);
        lastNanos = 0;
        update(srv, Clock.get(srv.overworld()));
    }

    static void serverStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        server = State.EMPTY;
        lastNanos = 0;
        testing = false;
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
            if (c < cap) return "In hard mode the coverage is at least the cap (" + cap + ")";
            k.seconds = k.hardAt + (cap >= 1 ? 0 : (c - cap) / (1 - cap) * StasisConfig.hardSpreadHours * 3600);
        }
        k.setDirty();
        update(srv, k);
        return null;
    }

    /** /stasis spread hardmode: enter hard mode now (later steps: the dragon's death). Once only. */
    static boolean enterHardMode(MinecraftServer srv) {
        Clock k = Clock.get(srv.overworld());
        if (k.hard) return false;
        k.hard = true;
        k.hardAt = k.seconds;
        k.setDirty();
        update(srv, k);
        StasisDelvers.LOG.info("Hard mode at {} h online", k.seconds / 3600);
        return true;
    }

    /** For /stasis spread status. */
    static String status(MinecraftServer srv, ServerPlayer p) {
        Clock k = Clock.get(srv.overworld());
        State s = server;
        StringBuilder b = new StringBuilder();
        b.append("mode ").append(StasisConfig.spreading ? "spreading" : "stopped");
        if (!StasisConfig.spreading) return b.toString();
        double c = coverage(k);
        b.append(String.format(java.util.Locale.ROOT, ", coverage %.4f, P %d / %d, online %.2f h, hard mode %s%s", c, s.p(), s.count(),
            k.seconds / 3600, k.hard ? "since " + String.format(java.util.Locale.ROOT, "%.2f h", k.hardAt / 3600) : "no",
            s.complete() ? ", complete" : ""));
        if (s.map() == null) return b.append(", no map yet").toString();
        if (p != null && p.level().dimension() == Level.OVERWORLD) {
            long r = SpreadMap.rank1024(s.map(), p.getBlockX(), p.getBlockZ());
            double rank = r / (double) SpreadMap.SCALE;
            b.append(String.format(java.util.Locale.ROOT, "; here: rank %.1f, %s", rank, r < (long) s.p() * SpreadMap.SCALE ? "stilled" : "running"));
            if (r >= (long) s.p() * SpreadMap.SCALE) {
                // the coverage at which this column stills, and the online time that takes at the current pace
                double cAt = Math.ceil(rank) / s.count();
                double cap = StasisConfig.spreadCap;
                double tAt = !k.hard && cAt <= cap ? cAt / cap * StasisConfig.spreadHours * 3600
                    : k.hard ? k.hardAt + (cAt - cap) / Math.max(1e-9, 1 - cap) * StasisConfig.hardSpreadHours * 3600 : Double.NaN;
                if (Double.isNaN(tAt)) b.append(" (stills only after hard mode)");
                else b.append(String.format(java.util.Locale.ROOT, " (stills in ~%.2f online h)", Math.max(0, tAt - k.seconds) / 3600));
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
        server = new State(m, m.count, p, false, false);
        Stasis.bumpVersion();
    }

    public static void testReset() {
        if (!testing) return;
        server = saved;
        StasisConfig.spreading = savedMode;
        testing = false;
        Stasis.bumpVersion();
    }

    // ---- saved data ------------------------------------------------------------------------------------------------

    /** The online clock (overworld, stasisdelvers:spread_clock): dirty all the time, so apart from the big map. */
    public static final class Clock extends SavedData {
        static final Codec<Clock> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.optionalFieldOf("seconds", 0.0).forGetter(k -> k.seconds),
            Codec.BOOL.optionalFieldOf("hard", false).forGetter(k -> k.hard),
            Codec.DOUBLE.optionalFieldOf("hard_at", 0.0).forGetter(k -> k.hardAt)
        ).apply(i, Clock::new));
        static final SavedDataType<Clock> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "spread_clock"), Clock::new, CODEC);

        /** Online seconds (real time while at least one player is on). */
        double seconds;
        boolean hard;
        /** Online seconds when hard mode began. */
        double hardAt;

        public Clock() {
        }

        private Clock(double seconds, boolean hard, double hardAt) {
            this.seconds = seconds;
            this.hard = hard;
            this.hardAt = hardAt;
        }

        static Clock get(ServerLevel overworld) {
            return overworld.getDataStorage().computeIfAbsent(TYPE);
        }
    }

    /** The map (overworld, stasisdelvers:spread_map): written once. */
    public static final class MapData extends SavedData {
        static final Codec<MapData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("algorithm", SpreadMap.ALGORITHM).forGetter(d -> d.algorithm),
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
