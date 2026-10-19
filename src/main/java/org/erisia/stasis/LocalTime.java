package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Local time, visual only (Baughn + λ-Nero, notes/timestop/LOCAL-TIME-PLAN.md): all stopped land shows one frozen time
 * (a sunset on day 0, so the moon never changes there; Baughn 2026-10-17: land a field leaves takes the grey's time, no
 * per-chunk memory), a field shows its own clock, which skews at 50-200% towards the world's time of day until it
 * matches. Field clocks are absolute (day count included: the moon phase is the field's own), and sleeping in a field
 * moves only that field's clock to the next morning (FieldSleep). The client draws sky, sun, moon and light from the
 * local time where the camera is.
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

    /** Field id -> its clock (absolute ticks: day count and time of day), per level; kept with FieldData. A new field
     *  starts from the grey's time. */
    static double fieldClock(ServerLevel level, Field f) {
        FieldData fd = FieldData.get(level);
        return fd.clocks.computeIfAbsent(f.id(), id -> DEFAULT_TOD);
    }

    /** Sets a field's clock (absolute) and tells the clients at once. */
    static void setFieldClock(ServerLevel level, Field f, double c) {
        FieldData fd = FieldData.get(level);
        fd.clocks.put(f.id(), c);
        fd.setDirty();
        Map<Integer, double[]> sync = new HashMap<>();
        for (Field g : fd.fields()) sync.put(g.id(), new double[]{fieldClock(level, g), g.rate()});
        StasisNet.sendFieldClocks(level, sync);
    }

    /** Is it night at this time of day (when a bed can be used; vanilla's clear-weather span)? */
    public static boolean night(double t) {
        double tod = wrap(t);
        return tod >= 12542 && tod <= 23459;
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
                double d = wrapDiff(wrap(c), global);
                double step;
                if (Math.abs(d) < 1.0) {
                    c += d;
                    step = f.rate();
                } else {
                    step = f.rate() * (d > 0 ? 2.0 : 0.5);
                    c += step;
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

    /** A field goes: its clock goes with it (the land shows the grey's time again). */
    static void fieldGone(ServerLevel level, Field f) {
        FieldData.get(level).clocks.remove(f.id());
    }

    // ---------------------------------------------------------------- client

    /** Client side: field clocks (clock, step per tick, game time received), as last sent. */
    public static final class Client {
        private Client() {
        }

        static final Map<Integer, double[]> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

        static void fieldClocks(Map<Integer, double[]> m, Level level) {
            long now = level == null ? 0 : level.getGameTime();
            FIELDS.clear();
            m.forEach((id, v) -> FIELDS.put(id, new double[]{v[0], v[1], now}));
        }

        public static void clear() {
            FIELDS.clear();
        }

        /**
         * The time to draw at this position (absolute: sky, sun, moon, light), with a 3-block blend at field edges.
         * global: the world's time (absolute), as the caller read it raw (reading the clock here would recurse into the
         * clock mixin: AdventurAgent's StackOverflowError).
         */
        public static double time(Level level, double x, double y, double z, float partial, double global) {
            double outside = DEFAULT_TOD;
            // land the spreading stillness hasn't reached runs at rate 1, fields or not: the world's clock; a drifting
            // deep over it stops it (Baughn 2026-10-19), unless a field holds the deep off
            if (Stasis.spreadRuns(level, (int) Math.floor(x), (int) Math.floor(z)))
                return Stasis.isRunning(level, BlockPos.containing(x, y, z)) ? global : outside;
            double best = 0, inside = outside;
            for (Field f : ClientFields.fields()) {
                double[] c = FIELDS.get(f.id());
                if (c == null) continue;
                double dx = x - f.x(), dy = f.column() ? 0 : y - f.y(), dz = z - f.z();
                // blocks to the edge, along the line from the centre (stretched fields: Field.norm)
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz), n = f.norm(dx, dy, dz);
                double edge = n < 1e-6 ? f.radius() : (1 - n) * d / n;
                double w = Math.max(0, Math.min(1, edge / 3.0)) * (1 - f.collapse());
                if (w > best) {
                    best = w;
                    inside = c[0] + (level.getGameTime() - c[2] + partial) * c[1];
                }
            }
            if (best <= 0) {
                // running land that isn't a field (the spawn area) shows the world's clock (AdventurAgent)
                return Stasis.isRunning(level, BlockPos.containing(x, y, z)) ? global : outside;
            }
            // the field's day (and moon) with the time of day blended towards the grey's at the edge
            return inside - wrapDiff(wrap(outside), wrap(inside)) * (1 - best);
        }
    }
}
