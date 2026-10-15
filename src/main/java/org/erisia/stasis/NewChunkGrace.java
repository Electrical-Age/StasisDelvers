package org.erisia.stasis;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Baughn: a chunk generated for the first time runs for a few seconds before the field stops it, so what worldgen
 * leaves for the game to settle dynamically gets settled (animals generated on top of each other walk apart, sand and
 * fluids settle). Stasis.rateAt treats a column in its grace as running at full rate.
 */
public final class NewChunkGrace {
    private NewChunkGrace() {
    }

    /** Off once GameTests run: test structures sit in new chunks, and the tests grant grace themselves. */
    static boolean onLoad = true;

    /** The first GameTest: stop granting grace on load, and drop what the test chunks got. */
    public static synchronized void testsStarted(Level level) {
        if (!onLoad) return;
        onLoad = false;
        GRACE.remove(level);
    }

    /** Game ticks a new chunk runs (3.5 s). */
    public static final int TICKS = 70;

    /** Per level: chunk key -> game tick its grace ends. Read from any thread (rateAt), written on the server thread. */
    private static final Map<Level, Map<Long, Long>> GRACE = new java.util.WeakHashMap<>();

    private static synchronized Map<Long, Long> of(Level level, boolean create) {
        Map<Long, Long> m = GRACE.get(level);
        if (m == null && create) GRACE.put(level, m = new ConcurrentHashMap<>());
        return m;
    }

    static void chunkLoad(ChunkEvent.Load e) {
        if (!onLoad || !e.isNewChunk() || !(e.getLevel() instanceof ServerLevel level) || !Stasis.stops(level)) return;
        grant(level, e.getChunk().getPos().pack(), TICKS);
    }

    /** Give a column a grace of `ticks` from now (chunkLoad; GameTests). */
    public static void grant(Level level, long chunkKey, int ticks) {
        of(level, true).put(chunkKey, level.getGameTime() + ticks);
    }

    /** Is this chunk column still in its first seconds? */
    public static boolean active(Level level, long chunkKey) {
        if (level.isClientSide()) return false;   // grace is the server's (and of() locks: chunk meshing asks off-thread)
        Map<Long, Long> m = of(level, false);
        if (m == null || m.isEmpty()) return false;
        Long end = m.get(chunkKey);
        if (end == null) return false;
        if (level.getGameTime() < end) return true;
        m.remove(chunkKey, end);
        return false;
    }
}
