package org.erisia.stasis;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import java.util.HashMap;
import java.util.Map;

/**
 * Towers keep their field's chunks loaded (and ticking) while the field is up (Baughn: players load their bases
 * anyway; here it's part of the mechanism, and an absent player's base freezes when nothing keeps it loaded).
 */
final class Tickets {
    private Tickets() {
    }

    static final TicketController CONTROLLER = new TicketController(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "tower"));

    /** Chunk reach (east, south, west, north) currently forced per tower (by base position), to release exactly what was forced. */
    private static final Map<String, int[]> FORCED = new HashMap<>();

    static void register(RegisterTicketControllersEvent e) {
        e.register(CONTROLLER);
    }

    static void update(ServerLevel level, TowerBlockEntity t) {
        BlockPos pos = t.getBlockPos().immutable();
        String key = level.dimension().identifier() + "|" + pos.asLong();
        int[] old = FORCED.remove(key);
        if (old != null) force(level, pos, old, false);
        if (t.state() != TowerBlockEntity.State.OFF) {
            // (the larger of now and the target: tickets don't follow the retreat cycle in and out); chunks east,
            // south, west and north, as far as the field reaches that way (booms stretch it: Field.Stretch)
            double r = Math.max(t.radius(), t.target());
            var st = t.stretch;
            int[] cr = {(int) Math.ceil(r * st.e() / 16.0), (int) Math.ceil(r * st.s() / 16.0), (int) Math.ceil(r * st.w() / 16.0),
                (int) Math.ceil(r * st.n() / 16.0)};
            force(level, pos, cr, true);
            FORCED.put(key, cr);
        }
    }

    private static void force(ServerLevel level, BlockPos pos, int[] cr, boolean add) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        int e = cr[0], s = cr.length > 1 ? cr[1] : cr[0], w = cr.length > 1 ? cr[2] : cr[0], n = cr.length > 1 ? cr[3] : cr[0];
        for (int dx = -w; dx <= e; dx++)
            for (int dz = -n; dz <= s; dz++) CONTROLLER.forceChunk(level, pos, cx + dx, cz + dz, add, true);
    }
}
