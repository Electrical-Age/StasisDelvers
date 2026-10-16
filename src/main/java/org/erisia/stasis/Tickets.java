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

    /** Chunk radius currently forced per tower (by base position), to release exactly what was forced. */
    private static final Map<String, int[]> FORCED = new HashMap<>();

    static void register(RegisterTicketControllersEvent e) {
        e.register(CONTROLLER);
    }

    static void update(ServerLevel level, TowerBlockEntity t) {
        BlockPos pos = t.getBlockPos().immutable();
        String key = level.dimension().identifier() + "|" + pos.asLong();
        int[] old = FORCED.remove(key);
        if (old != null) force(level, pos, old[0], false);
        if (t.state() != TowerBlockEntity.State.OFF) {
            // (the larger of now and the target: tickets don't follow the retreat cycle in and out)
            int cr = (int) Math.ceil(Math.max(t.radius(), t.target()) / 16.0);
            force(level, pos, cr, true);
            FORCED.put(key, new int[]{cr});
        }
    }

    private static void force(ServerLevel level, BlockPos pos, int cr, boolean add) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        for (int dx = -cr; dx <= cr; dx++)
            for (int dz = -cr; dz <= cr; dz++) CONTROLLER.forceChunk(level, pos, cx + dx, cz + dz, add, true);
    }
}
