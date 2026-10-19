package org.erisia.stasis;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

import java.util.HashMap;
import java.util.Map;

/**
 * Towers keep their field's chunks loaded (and ticking) while the field is up (Baughn: players load their bases
 * anyway; here it's part of the mechanism, and an absent player's base freezes when nothing keeps it loaded).
 * NeoForge saves forced chunks with the level and forces them again at load; the validation callback reads them back
 * into FORCED, so a tower that shrinks or stops after a restart releases what it forced before it.
 */
final class Tickets {
    private Tickets() {
    }

    static final TicketController CONTROLLER = new TicketController(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "tower"), Tickets::loaded);

    /** Per level, per tower (by base position): the chunks it forces now. Server thread only. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, LongSet>> FORCED = new HashMap<>();

    static void register(RegisterTicketControllersEvent e) {
        e.register(CONTROLLER);
    }

    /**
     * Level load, before the saved tickets come back into force: remember who forced what. Towers force only with
     * natural spawning on (update), and update releases that kind only, so a plain ticket under our controller (none
     * should exist) is dropped here rather than adopted: update could never release it and it would stay forced.
     */
    private static void loaded(ServerLevel level, TicketHelper tickets) {
        Map<BlockPos, LongSet> m = new HashMap<>();
        tickets.getBlockTickets().forEach((pos, t) -> {
            for (long c : t.normal()) tickets.removeTicket(pos, c, false);
            if (!t.naturalSpawning().isEmpty()) m.put(pos.immutable(), new LongOpenHashSet(t.naturalSpawning()));
        });
        FORCED.put(level.dimension(), m);
    }

    /** The server stopped: the next one (another singleplayer world) starts empty. */
    static void clear() {
        FORCED.clear();
    }

    static void update(ServerLevel level, TowerBlockEntity t) {
        BlockPos pos = t.getBlockPos().immutable();
        Map<BlockPos, LongSet> byTower = FORCED.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        LongSet old = byTower.remove(pos), now = new LongOpenHashSet();
        if (t.state() != TowerBlockEntity.State.OFF) {
            // (the larger of now and the target: tickets don't follow the retreat cycle in and out); chunks east,
            // south, west and north, as far as the field reaches that way (booms stretch it: Field.Stretch)
            double r = Math.max(t.radius(), t.target());
            var st = t.stretch;
            int e = (int) Math.ceil(r * st.e() / 16.0), s = (int) Math.ceil(r * st.s() / 16.0),
                w = (int) Math.ceil(r * st.w() / 16.0), n = (int) Math.ceil(r * st.n() / 16.0);
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            for (int dx = -w; dx <= e; dx++)
                for (int dz = -n; dz <= s; dz++) now.add(ChunkPos.pack(cx + dx, cz + dz));
        }
        if (old != null)
            for (long c : old) if (!now.contains(c)) CONTROLLER.forceChunk(level, pos, ChunkPos.getX(c), ChunkPos.getZ(c), false, true);
        for (long c : now) if (old == null || !old.contains(c)) CONTROLLER.forceChunk(level, pos, ChunkPos.getX(c), ChunkPos.getZ(c), true, true);
        if (!now.isEmpty()) byTower.put(pos, now);
    }
}
