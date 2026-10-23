package org.erisia.stasis;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sealed riftstone rooms (λ-Nero, variant B of the acceleration fields; notes/timestop/ACCEL-DESIGN.md): the space a
 * chronal accelerator's flood fill reached inside walls of riftstone. Time runs inside a working room (Stasis.rateAt
 * floors it at 1, in stopped land too) at the room's rate (Stasis.speedAt). Kept apart from the fields, as the lulls
 * are: a room is a set of blocks, not a sphere (a deliberate change from "a field of a new kind", 2026-10-23). Not
 * saved: each accelerator registers its room again once it has run its fill after a load. Synced to clients
 * (StasisNet.RoomsSync) so their rule agrees.
 */
public final class Rooms {
    private Rooms() {
    }

    /**
     * A room: its accelerator's position (the key), the cells' box (min corner, size), one bit per cell (x fastest,
     * then z, then y), and the rate it runs at (1 or more).
     */
    public record Room(long key, int x0, int y0, int z0, int sx, int sy, int sz, long[] bits, double rate) {
        public boolean contains(int x, int y, int z) {
            int dx = x - x0, dy = y - y0, dz = z - z0;
            if (dx < 0 || dy < 0 || dz < 0 || dx >= sx || dy >= sy || dz >= sz) return false;
            int i = (dy * sz + dz) * sx + dx;
            return (bits[i >> 6] >>> (i & 63) & 1) != 0;
        }

        public int volume() {
            int n = 0;
            for (long b : bits) n += Long.bitCount(b);
            return n;
        }

        boolean sameShape(Room o) {
            return o != null && x0 == o.x0 && y0 == o.y0 && z0 == o.z0 && sx == o.sx && sy == o.sy && sz == o.sz && java.util.Arrays.equals(bits, o.bits);
        }
    }

    /** A level's rooms and their column index. */
    private record State(List<Room> rooms, Long2ObjectOpenHashMap<Room[]> columns) {
        static final State EMPTY = new State(List.of(), new Long2ObjectOpenHashMap<>());

        static State of(List<Room> rooms) {
            Long2ObjectOpenHashMap<List<Room>> lists = new Long2ObjectOpenHashMap<>();
            for (Room r : rooms)
                for (int cx = r.x0() >> 4; cx <= (r.x0() + r.sx() - 1) >> 4; cx++)
                    for (int cz = r.z0() >> 4; cz <= (r.z0() + r.sz() - 1) >> 4; cz++)
                        lists.computeIfAbsent(ChunkPos.pack(cx, cz), k -> new ArrayList<>()).add(r);
            Long2ObjectOpenHashMap<Room[]> cols = new Long2ObjectOpenHashMap<>();
            for (var e : lists.long2ObjectEntrySet()) cols.put(e.getLongKey(), e.getValue().toArray(new Room[0]));
            return new State(List.copyOf(rooms), cols);
        }
    }

    /** Copy-on-write, read without a lock (the rule runs off the main thread too). */
    private static volatile Map<Level, State> server = Map.of();
    private static volatile Level clientLevel;
    private static volatile State client = State.EMPTY;

    private static State of(Level level) {
        if (level.isClientSide()) return level == clientLevel ? client : State.EMPTY;
        State s = server.get(level);
        return s == null ? State.EMPTY : s;
    }

    /** The working room holding the block, or null. */
    public static Room at(Level level, int x, int y, int z) {
        State s = of(level);
        if (s.rooms().isEmpty()) return null;
        Room[] rs = s.columns().get(ChunkPos.pack(x >> 4, z >> 4));
        if (rs == null) return null;
        for (Room r : rs) if (r.contains(x, y, z)) return r;
        return null;
    }

    /** Does a room touch this chunk column? */
    public static boolean inColumn(Level level, long chunkKey) {
        State s = of(level);
        return !s.rooms().isEmpty() && s.columns().containsKey(chunkKey);
    }

    /** Is any room of this level faster than 1x? */
    public static boolean anyFast(Level level) {
        for (Room r : of(level).rooms()) if (r.rate() > 1) return true;
        return false;
    }

    public static List<Room> all(Level level) {
        return of(level).rooms();
    }

    /** Does a room touch the 16-block section (sx, sy, sz)? */
    public static boolean touchesSection(Level level, int sx, int sy, int sz) {
        Room[] rs = of(level).columns().get(ChunkPos.pack(sx, sz));
        if (rs == null) return false;
        int y0 = sy << 4, y1 = y0 + 15;
        for (Room r : rs) if (r.y0() <= y1 && r.y0() + r.sy() - 1 >= y0) return true;
        return false;
    }

    /** An accelerator's room formed, changed or stopped (null): the rule, the clients and the light follow. */
    static void put(ServerLevel level, long key, Room room) {
        State s = of(level);
        Room old = null;
        List<Room> next = new ArrayList<>();
        for (Room r : s.rooms()) {
            if (r.key() == key) old = r;
            else next.add(r);
        }
        if (room == null && old == null) return;
        if (room != null && old != null && room.sameShape(old) && room.rate() == old.rate()) return;
        if (room != null) {
            next.add(room);
            if (room.rate() > 1) Stasis.noteFast();
        }
        Map<Level, State> m = new IdentityHashMap<>(server);
        if (next.isEmpty()) m.remove(level);
        else m.put(level, State.of(next));
        server = m;
        Stasis.bumpVersion();
        StasisNet.sendRooms(level, next);
        // where a room appears or goes in stopped land, the flames inside light or go dark (Flames)
        boolean shape = room == null || old == null || !room.sameShape(old);
        if (shape && Stasis.stops(level)) {
            Room r = room != null ? room : old;
            int[] ys = {r.y0(), r.y0() + r.sy() - 1};
            for (int cx = r.x0() >> 4; cx <= (r.x0() + r.sx() - 1) >> 4; cx++)
                for (int cz = r.z0() >> 4; cz <= (r.z0() + r.sz() - 1) >> 4; cz++) Flames.relightChunk(level, cx, cz, ys);
        }
    }

    /** Level unload: forget its rooms (their accelerators register them again when they run). */
    static void unload(Level level) {
        if (!server.containsKey(level)) return;
        Map<Level, State> m = new IdentityHashMap<>(server);
        m.remove(level);
        server = m;
    }

    /** Client hook: rooms changed (old, now) in the client's level (re-mesh and relight; StasisClient). */
    public static volatile java.util.function.BiConsumer<List<Room>, List<Room>> clientOnChange = (a, b) -> {
    };

    /** StasisNet.RoomsSync on the client (main thread). */
    static void clientSet(Level level, List<Room> rooms) {
        List<Room> old = level == clientLevel ? client.rooms() : List.of();
        clientLevel = level;
        client = rooms.isEmpty() ? State.EMPTY : State.of(rooms);
        for (Room r : rooms) if (r.rate() > 1) Stasis.noteFast();
        Stasis.bumpVersion();
        clientOnChange.accept(old, client.rooms());
    }
}
