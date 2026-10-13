package org.erisia.stasis;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;

/**
 * Local time of day, visual only (Baughn + λ-Nero, notes/timestop/LOCAL-TIME-PLAN.md): stopped land shows the time it
 * froze at (a chunk remembers it; never-run land shows a sunset), a field shows its own clock, which skews at 50-200%
 * towards the world's time until it matches. The client draws sky, sun and light from the local time where the camera is.
 */
public final class LocalTime {
    private LocalTime() {
    }

    public static final double DAY = 24000, DEFAULT_TOD = 12500;   // a sunset

    /** (to - from) wrapped into (-DAY/2, DAY/2]. */
    public static double wrapDiff(double from, double to) {
        double d = ((to - from) % DAY + DAY) % DAY;
        return d > DAY / 2 ? d - DAY : d;
    }

    public static double wrap(double t) {
        return ((t % DAY) + DAY) % DAY;
    }

    // ---------------------------------------------------------------- server

    /** Chunk -> the time of day it froze at (written when a field over it goes). */
    public static final class ChunkClocks extends SavedData {
        public static final Codec<ChunkClocks> CODEC = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)
            .xmap(ChunkClocks::new, c -> {
                Map<String, Double> m = new HashMap<>();
                c.clocks.forEach((k, v) -> m.put(Long.toString(k), v));
                return m;
            });
        public static final SavedDataType<ChunkClocks> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "chunk_clocks"), ChunkClocks::new, CODEC);

        final Map<Long, Double> clocks = new HashMap<>();

        public ChunkClocks() {
        }

        ChunkClocks(Map<String, Double> m) {
            m.forEach((k, v) -> clocks.put(Long.parseLong(k), v));
        }

        public static ChunkClocks get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }

        public double at(long chunk) {
            return clocks.getOrDefault(chunk, DEFAULT_TOD);
        }
    }

    /** Field id -> its clock (time of day), per level; kept with FieldData. */
    static double fieldClock(ServerLevel level, Field f) {
        FieldData fd = FieldData.get(level);
        return fd.clocks.computeIfAbsent(f.id(), id -> ChunkClocks.get(level).at(ChunkPos.pack(BlockPos.containing(f.x(), f.y(), f.z()))));
    }

    /** Server tick: every field's clock skews towards the world's time; clients hear about it every 40 ticks. */
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        for (ServerLevel level : e.getServer().getAllLevels()) {
            if (!Stasis.stops(level)) continue;
            FieldData fd = FieldData.get(level);
            if (fd.fields().isEmpty()) continue;
            double global = wrap(level.getDefaultClockTime());
            Map<Integer, double[]> sync = new HashMap<>();
            for (Field f : fd.fields()) {
                double c = fieldClock(level, f);
                double d = wrapDiff(c, global);
                double step;
                if (Math.abs(d) < 1.0) {
                    c = global;
                    step = f.rate();
                } else {
                    step = f.rate() * (d > 0 ? 2.0 : 0.5);
                    c = wrap(c + step);
                }
                fd.clocks.put(f.id(), c);
                sync.put(f.id(), new double[]{c, step});
            }
            fd.clocks.keySet().removeIf(id -> fd.get(id).isEmpty());
            fd.setDirty();
            if (level.getGameTime() % 40 == 0) StasisNet.sendFieldClocks(level, sync);
        }
    }

    /** For tests: a field's clock (NaN if none yet). */
    public static double clockOf(ServerLevel level, int id) {
        return FieldData.get(level).clocks.getOrDefault(id, Double.NaN);
    }

    /** A field goes: the chunks it covered keep the time it showed. */
    static void fieldGone(ServerLevel level, Field f) {
        Double c = FieldData.get(level).clocks.remove(f.id());
        if (c == null) return;
        ChunkClocks cc = ChunkClocks.get(level);
        Map<Long, Double> changed = new HashMap<>();
        int r = (int) Math.ceil(f.radius());
        int cx0 = ((int) Math.floor(f.x() - r)) >> 4, cx1 = ((int) Math.floor(f.x() + r)) >> 4;
        int cz0 = ((int) Math.floor(f.z() - r)) >> 4, cz1 = ((int) Math.floor(f.z() + r)) >> 4;
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++) {
                double px = (cx << 4) + 8, pz = (cz << 4) + 8;
                if (f.containsPoint(px, f.y(), pz)) {
                    long k = ChunkPos.pack(cx, cz);
                    cc.clocks.put(k, c);
                    changed.put(k, c);
                }
            }
        cc.setDirty();
        if (!changed.isEmpty()) StasisNet.sendChunkClocks(level, changed);
    }

    /** A player starts watching a chunk: its frozen time, if it has one. */
    static void watch(net.neoforged.neoforge.event.level.ChunkWatchEvent.Watch e) {
        ChunkClocks cc = ChunkClocks.get(e.getLevel());
        long k = e.getPos().pack();
        Double c = cc.clocks.get(k);
        if (c != null) StasisNet.sendChunkClocks(e.getPlayer(), Map.of(k, c));
    }

    // ---------------------------------------------------------------- client

    /** Client side: field clocks (clock, step per tick, game time received) and chunk clocks, as last sent. */
    public static final class Client {
        private Client() {
        }

        static final Map<Integer, double[]> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
        static final Map<Long, Double> CHUNKS = new java.util.concurrent.ConcurrentHashMap<>();

        static void fieldClocks(Map<Integer, double[]> m, Level level) {
            long now = level == null ? 0 : level.getGameTime();
            FIELDS.clear();
            m.forEach((id, v) -> FIELDS.put(id, new double[]{v[0], v[1], now}));
        }

        static void chunkClocks(Map<Long, Double> m) {
            CHUNKS.putAll(m);
        }

        public static void clear() {
            FIELDS.clear();
            CHUNKS.clear();
        }

        /** The time of day to draw at this position (sky, sun, light), with a 3-block blend at field edges. */
        public static double timeOfDay(Level level, double x, double y, double z, float partial) {
            double outside = CHUNKS.getOrDefault(ChunkPos.pack(BlockPos.containing(x, y, z)), DEFAULT_TOD);
            double best = 0, inside = outside;
            for (Field f : ClientFields.fields()) {
                double[] c = FIELDS.get(f.id());
                if (c == null) continue;
                double dx = x - f.x(), dy = f.column() ? 0 : y - f.y(), dz = z - f.z();
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double w = Math.max(0, Math.min(1, (f.radius() - d) / 3.0)) * (1 - f.collapse());
                if (w > best) {
                    best = w;
                    inside = wrap(c[0] + (level.getGameTime() - c[2] + partial) * c[1]);
                }
            }
            return wrap(outside + wrapDiff(outside, inside) * best);
        }
    }
}
