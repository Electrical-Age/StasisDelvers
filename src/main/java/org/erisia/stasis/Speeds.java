package org.erisia.stasis;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rate acceleration fields (Field.fast: rate above 1) achieve: every extra tick is real server work, so once a second
 * each one counts what it holds (block entities and entities) and runs at 1 + min(rate - 1, budget / count), with the
 * extra ticks of all of them together held to the server budget (StasisConfig accelBudget, accelServerBudget). Kept
 * here, not on the Field: a change to FieldData re-syncs, re-meshes and relights. Clients get the fields held below
 * their rate (StasisNet.FieldSpeeds) for their own extra ticks.
 */
public final class Speeds {
    private Speeds() {
    }

    /** Per level: field id to {achieved rate, what it held at the last count}. Rebuilt whole each second and read
     *  without a lock (speedAt asks per block-entity and entity tick). */
    private static volatile Map<Level, Int2ObjectOpenHashMap<double[]>> state = Map.of();

    /** The client's copy: field id to achieved rate, for the fields held below their rate (FieldSpeeds). */
    private static volatile Map<Integer, Double> client = Map.of();

    static void clientAchieved(Map<Integer, Double> m) {
        client = Map.copyOf(m);
    }

    /** The rate field f runs its blocks at now (the budgeted rate; the client has it from the server). */
    public static double achieved(Level level, Field f) {
        if (!(level instanceof ServerLevel)) {
            Double v = client.get(f.id());
            return v == null ? f.rate() : Math.min(f.rate(), v);
        }
        var m = state.get(level);
        double[] v = m == null ? null : m.get(f.id());
        return v == null ? f.rate() : Math.min(f.rate(), v[0]);
    }

    /** What field f held at the last count (block entities plus entities), or -1 if it hasn't been counted. */
    public static double held(Level level, Field f) {
        var m = state.get(level);
        double[] v = m == null ? null : m.get(f.id());
        return v == null ? -1 : v[1];
    }

    /** ServerTickEvent.Post: once a second, recount. */
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        if (server.getTickCount() % 20 != 0) return;
        recount(server);
    }

    /** Count every level's acceleration fields and set their achieved rates (also from the GameTests). */
    public static void recount(MinecraftServer server) {
        record Want(ServerLevel level, Field f, int count) {
        }
        List<Want> wants = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (!Stasis.hasFast(level)) continue;
            for (Field f : Stasis.fields(level)) if (f.fast()) wants.add(new Want(level, f, count(level, f)));
        }
        // per field: its own budget; then all of them together within the server's, in proportion
        double extra = 0;
        double[] a = new double[wants.size()];
        for (int i = 0; i < a.length; i++) {
            Want w = wants.get(i);
            a[i] = 1 + Math.min(w.f().rate() - 1, StasisConfig.accelBudget / (double) Math.max(1, w.count()));
            extra += (a[i] - 1) * w.count();
        }
        double scale = extra > StasisConfig.accelServerBudget ? StasisConfig.accelServerBudget / extra : 1;
        Map<Level, Int2ObjectOpenHashMap<double[]>> next = new IdentityHashMap<>();
        for (int i = 0; i < a.length; i++) {
            Want w = wants.get(i);
            next.computeIfAbsent(w.level(), k -> new Int2ObjectOpenHashMap<>()).put(w.f().id(), new double[]{1 + (a[i] - 1) * scale, w.count()});
        }
        state = next;
        // the clients: each level's fields held below their rate, sent each second while there are any (and once more,
        // empty, when they're gone)
        for (ServerLevel level : server.getAllLevels()) {
            Map<Integer, Double> held = new java.util.HashMap<>();
            var m = next.get(level);
            if (m != null)
                for (Field f : Stasis.fields(level))
                    if (f.fast() && m.containsKey(f.id()) && m.get(f.id())[0] < f.rate() - 1e-9) held.put(f.id(), m.get(f.id())[0]);
            if (!held.isEmpty() || SENT_HELD.contains(level)) StasisNet.sendFieldSpeeds(level, held);
            if (held.isEmpty()) SENT_HELD.remove(level);
            else SENT_HELD.add(level);
        }
    }

    /** Levels whose clients were last sent a non-empty FieldSpeeds. */
    private static final java.util.Set<Level> SENT_HELD = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** The block entities and entities (not players) whose whole block field f holds, in loaded chunks. */
    static int count(ServerLevel level, Field f) {
        int n = 0;
        int x0 = (int) Math.floor(f.minX()) >> 4, x1 = (int) Math.floor(f.maxX()) >> 4;
        int z0 = (int) Math.floor(f.minZ()) >> 4, z1 = (int) Math.floor(f.maxZ()) >> 4;
        for (int cx = x0; cx <= x1; cx++)
            for (int cz = z0; cz <= z1; cz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                if (c == null) continue;
                for (var p : c.getBlockEntities().keySet()) if (f.containsBlock(p.getX(), p.getY(), p.getZ())) n++;
            }
        double ry = f.column() ? 4096 : f.radius();
        AABB box = new AABB(f.minX(), f.y() - ry, f.minZ(), f.maxX(), f.y() + ry, f.maxZ());
        for (Entity en : level.getEntities((Entity) null, box, en -> !(en instanceof Player)))
            if (f.containsBlock(en.getBlockX(), en.getBlockY(), en.getBlockZ())) n++;
        return n;
    }
}
