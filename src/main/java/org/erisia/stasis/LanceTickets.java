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
 * An active lance keeps its rift's core chunk loaded (LANCE-v2 point 9), and its own core's chunk: the core block entity
 * drives the gate, so if its chunk unloaded with the players gone, the gate would stop mid-breach or mid-hold. The same
 * pattern as the towers' Tickets: NeoForge saves forced chunks, the validation callback reads them back into FORCED, so
 * a lance that stopped across a restart releases what it forced before.
 */
public final class LanceTickets {
    private LanceTickets() {
    }

    static final TicketController CONTROLLER = new TicketController(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "lance"), LanceTickets::loaded);

    /** Per level, per lance core: the chunks it forces now. Server thread only. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, LongSet>> FORCED = new HashMap<>();

    static void register(RegisterTicketControllersEvent e) {
        e.register(CONTROLLER);
    }

    /** Level load: remember who forced what (lances force without natural spawning; anything else is dropped). */
    private static void loaded(ServerLevel level, TicketHelper tickets) {
        Map<BlockPos, LongSet> m = new HashMap<>();
        tickets.getBlockTickets().forEach((pos, t) -> {
            for (long c : t.naturalSpawning()) tickets.removeTicket(pos, c, true);
            if (!t.normal().isEmpty()) m.put(pos.immutable(), new LongOpenHashSet(t.normal()));
        });
        FORCED.put(level.dimension(), m);
    }

    static void clear() {
        FORCED.clear();
    }

    /** Force exactly these chunks for the lance at core (none: release them all). */
    static void update(ServerLevel level, BlockPos core, LongSet now) {
        BlockPos pos = core.immutable();
        Map<BlockPos, LongSet> byLance = FORCED.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        LongSet old = byLance.remove(pos);
        if (old != null)
            for (long c : old) if (!now.contains(c)) CONTROLLER.forceChunk(level, pos, ChunkPos.getX(c), ChunkPos.getZ(c), false, false);
        for (long c : now) if (old == null || !old.contains(c)) CONTROLLER.forceChunk(level, pos, ChunkPos.getX(c), ChunkPos.getZ(c), true, false);
        if (!now.isEmpty()) byLance.put(pos, new LongOpenHashSet(now));
    }

    /** Tests: the chunks the lance at core forces now. */
    public static LongSet forced(ServerLevel level, BlockPos core) {
        return FORCED.getOrDefault(level.dimension(), Map.of()).getOrDefault(core, new LongOpenHashSet());
    }
}
