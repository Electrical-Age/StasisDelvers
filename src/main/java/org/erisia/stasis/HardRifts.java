package org.erisia.stasis;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Hard mode's stronghold rewrite (spread-DESIGN.md step 4b; Baughn's decision 6: "Carve the full rift. It's intended as
 * a cataclysm"). Every portal room PendingGates recorded becomes what the stopped mode generates, a gate room
 * (GateRoom.shatter) with its gate rift registered (Gates), plus the gate rift's carve (DeepFeature.apply), written
 * into the chunks that exist.
 * <ul>
 * <li>At the instant ({@link #begin}) each room's gate is registered (saved, StaticDeeps, sent to clients) before any
 * block is written: isGate decides its destination (the End) for the shell's scatter. Players the new rift stops get
 * the usual grace (Freezing.graceIfStopped) and players near it a chat warning.</li>
 * <li>Per chunk: chunks loaded then are queued at once, the rest when they next load (ChunkEvent.Load); the queue is
 * worked CHUNKS_PER_TICK a tick, so the instant doesn't stall the server. Each chunk is written from the recorded box,
 * orientation and centre, through the same pure functions as worldgen, touching only its own columns: no waiting for
 * the room's neighbouring chunks (Argon's other option), and the order doesn't matter. Progress per room and chunk is
 * saved (PendingGates.Room: shattered, carved), so a restart resumes and nothing is written twice.</li>
 * <li>Writes use flags UPDATE_CLIENTS | UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS: the chunk's light and its live heightmaps
 * follow each setBlock (LevelChunk.setBlockState), and the clients get the changes batched per section and tick by the
 * chunk holder, with the light (cheaper than resending whole chunks). Block entities in the way are removed, so EA's
 * NodeBlockEntity.setRemoved removes its node; container contents go with their blocks, not dropped (the cheaper safe
 * option: no item floods in a rift). EA's ghost blocks (multiblock parts) break through affectNeighborsAfterRemoval,
 * which needs UPDATE_NEIGHBORS, so they get it.</li>
 * </ul>
 * Rooms generated after the instant don't come here: the portal room mixin gives them stopped mode's room at worldgen
 * (and, as in stopped mode, no carve). A stronghold straddling the instant has its later chunks' rooms written at
 * worldgen; PendingGates notes them as shattered, and only their carve is done here.
 */
public final class HardRifts {
    private HardRifts() {
    }

    /** Chunks rewritten per server tick (each a few ms: the carve of one 16 x 16 column block). */
    static final int CHUNKS_PER_TICK = 2;
    /** Players this close (horizontally) to a portal room hear it tear open. */
    static final double WARN_RADIUS = 64;

    /** Loaded chunks (ChunkEvent.Load, any thread) for the server tick to look at. */
    private static final Map<ServerLevel, List<Long>> LOADS = new WeakHashMap<>();
    /** Chunks to rewrite, in order (server thread). */
    private static final Map<ServerLevel, LinkedHashSet<Long>> WORK = new WeakHashMap<>();
    /** Whether this server run has made sure every recorded room is active (a restart in hard mode). */
    private static boolean started;

    /** HardMode.enter: every recorded room of every level becomes a gate room and a gate rift. */
    static void begin(MinecraftServer srv) {
        PendingGates.drain(srv);   // (rooms worldgen queued just before the instant: recorded and activated here)
        int n = 0;
        for (ServerLevel level : srv.getAllLevels())
            for (PendingGates.Room r : PendingGates.list(level)) {
                activate(level, r);
                n++;
            }
        started = true;
        StasisDelvers.LOG.info("Hard mode: {} stronghold portal room(s) tear open into gate rifts", n);
    }

    /**
     * A room's gate rift stands from now on: register it (once), queue its loaded chunks, and, the first time, warn the
     * players near it and give those it stopped the usual grace.
     */
    static void activate(ServerLevel level, PendingGates.Room r) {
        List<ServerPlayer> wereSafe = Freezing.running(level);
        boolean fresh = Gates.register(level, r.centre());
        LinkedHashSet<Long> work = WORK.computeIfAbsent(level, l -> new LinkedHashSet<>());
        for (long k : chunks(r))
            if (!done(r, k) && level.hasChunk(ChunkPos.getX(k), ChunkPos.getZ(k))) work.add(k);
        if (!fresh) return;
        BlockPos c = r.centre();
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - c.getX() - 0.5, dz = p.getZ() - c.getZ() - 0.5;
            if (dx * dx + dz * dz > WARN_RADIUS * WARN_RADIUS) continue;
            p.sendSystemMessage(Component.literal("The ground heaves: the stronghold's portal room nearby tears open into a rift.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        }
        Freezing.graceIfStopped(level, wereSafe, "A rift tore open where you stand: time stops here. Get out!", true);
    }

    /** The chunks room r's rewrite touches: those the gate rift's carve reaches (the room and its debris lie within). */
    static List<Long> chunks(PendingGates.Room r) {
        StaticDeeps.Deep d = r.deep();
        int reach = (int) Math.ceil(d.radius() * 1.5 + 24) + 16;
        List<Long> out = new ArrayList<>();
        for (int cx = (int) Math.floor((d.x() - reach) / 16); cx <= (int) Math.floor((d.x() + reach) / 16); cx++)
            for (int cz = (int) Math.floor((d.z() - reach) / 16); cz <= (int) Math.floor((d.z() + reach) / 16); cz++)
                if (covers(r, cx << 4, cz << 4)) out.add(ChunkPos.pack(cx, cz));
        return out;
    }

    static boolean covers(PendingGates.Room r, int x0, int z0) {
        return DeepFeature.reaches(r.deep(), x0, z0, false);
    }

    /** Does chunk (cx, cz) lie within 16 blocks of r's centre, where GateRoom.shatter may add entities? */
    static boolean nearRoom(PendingGates.Room r, int cx, int cz) {
        BlockPos c = r.centre();
        return (cx << 4) + 15 >= c.getX() - 16 && (cx << 4) <= c.getX() + 16 && (cz << 4) + 15 >= c.getZ() - 16 && (cz << 4) <= c.getZ() + 16;
    }

    static boolean done(PendingGates.Room r, long k) {
        return r.shattered().contains(k) && r.carved().contains(k);
    }

    /**
     * Rewrite chunk k for room r: the frame ring cleared and the room shattered (unless worldgen or an earlier pass did),
     * then the gate rift carved (unless done). Gate rifts have no strata anywhere (StaticDeeps.isGate: StrataMask,
     * Pockets, Crystals skip them), so none are written here either.
     */
    static void apply(ServerLevel level, PendingGates.Room r, long k) {
        int x0 = ChunkPos.getX(k) << 4, z0 = ChunkPos.getZ(k) << 4;
        BoundingBox chunkBB = new BoundingBox(x0, level.getMinY(), z0, x0 + 15, level.getMaxY(), z0 + 15);
        DeepFeature.Writer w = writer(level);
        if (!r.shattered().contains(k)) {
            var piece = new StrongholdPieces.PortalRoom(1, r.box(), r.dir());
            // the frame ring (x 3..7, z 8..12 at y 3) and the portal inside it, as the mixin clears them at worldgen
            for (int x = 3; x <= 7; x++)
                for (int z = 8; z <= 12; z++) {
                    BlockPos p = piece.getWorldPos(x, 3, z).immutable();
                    if (!chunkBB.isInside(p)) continue;
                    var st = level.getBlockState(p);
                    if (st.is(Blocks.END_PORTAL_FRAME) || st.is(Blocks.END_PORTAL)) w.set(p, Blocks.AIR.defaultBlockState());
                }
            GateRoom.shatter(level, w, piece, chunkBB);
            // a chunk first generated after the instant has the new-chunk grace: its lava and debris would settle first
            // (the mixin's stopped path exempts the room's chunks the same way)
            if (nearRoom(r, ChunkPos.getX(k), ChunkPos.getZ(k))) NewChunkGrace.exempt(level, k);
            r.shattered().add(k);
        }
        if (!r.carved().contains(k)) {
            DeepFeature.apply(level, w, Heightmap.Types.WORLD_SURFACE, r.deep(), x0, z0, false);
            r.carved().add(k);
        }
        PendingGates.Data.get(level).setDirty();
    }

    /** Live writes (see the class comment for the flags). */
    static DeepFeature.Writer writer(ServerLevel level) {
        return (p, st) -> {
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
            if (level.getBlockState(p).getBlock() instanceof mods.eln.ghost.GhostBlock) flags |= Block.UPDATE_NEIGHBORS;
            level.setBlock(p, st, flags);
        };
    }

    static void chunkLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !StasisConfig.spreading || !Spread.hardNow()) return;
        synchronized (LOADS) {
            LOADS.computeIfAbsent(level, l -> new ArrayList<>()).add(e.getChunk().getPos().pack());
        }
    }

    /** ServerTickEvent.Post: record what worldgen queued, take in loaded chunks, rewrite a few. */
    static void tick(ServerTickEvent.Post e) {
        MinecraftServer srv = e.getServer();
        PendingGates.drain(srv);
        Map<ServerLevel, List<Long>> loads;
        synchronized (LOADS) {
            if (LOADS.isEmpty() && WORK.isEmpty() && started) return;
            loads = new HashMap<>(LOADS);
            LOADS.clear();
        }
        if (!StasisConfig.spreading || !Spread.hard(srv)) return;
        if (!started) {
            // a restart in hard mode: make sure every room's gate stands (activate is idempotent) and queue its chunks
            started = true;
            for (ServerLevel level : srv.getAllLevels()) for (PendingGates.Room r : PendingGates.list(level)) activate(level, r);
        }
        loads.forEach((level, ks) -> {
            List<PendingGates.Room> rooms = PendingGates.list(level);
            if (rooms.isEmpty()) return;
            LinkedHashSet<Long> work = WORK.computeIfAbsent(level, l -> new LinkedHashSet<>());
            for (long k : ks)
                for (PendingGates.Room r : rooms)
                    if (!done(r, k) && covers(r, ChunkPos.getX(k) << 4, ChunkPos.getZ(k) << 4)) {
                        work.add(k);
                        break;
                    }
        });
        int budget = CHUNKS_PER_TICK;
        for (Iterator<Map.Entry<ServerLevel, LinkedHashSet<Long>>> li = WORK.entrySet().iterator(); li.hasNext() && budget > 0; ) {
            var en = li.next();
            ServerLevel level = en.getKey();
            for (Iterator<Long> it = en.getValue().iterator(); it.hasNext() && budget > 0; ) {
                long k = it.next();
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                if (!level.hasChunk(cx, cz)) {   // unloaded meanwhile: it comes back on its next load
                    it.remove();
                    continue;
                }
                List<PendingGates.Room> rooms = new ArrayList<>();
                boolean shards = false;
                for (PendingGates.Room r : PendingGates.list(level))
                    if (!done(r, k) && covers(r, cx << 4, cz << 4)) {
                        rooms.add(r);
                        shards |= !r.shattered().contains(k) && nearRoom(r, cx, cz);
                    }
                // the room's shards and silverfish are entities: added to a chunk whose entities haven't loaded yet they
                // could be lost, or the chunk's own saved over, so such a chunk waits (the carve alone adds none)
                if (shards && !level.areEntitiesLoaded(k)) continue;
                it.remove();
                for (PendingGates.Room r : rooms) apply(level, r, k);
                if (!rooms.isEmpty()) budget--;
            }
            if (en.getValue().isEmpty()) li.remove();
        }
    }

    static void serverStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        synchronized (LOADS) {
            LOADS.clear();
        }
        WORK.clear();
        started = false;
    }

    /** Tests: chunks waiting to be rewritten in level. */
    public static int testQueued(ServerLevel level) {
        Set<Long> s = WORK.get(level);
        return s == null ? 0 : s.size();
    }

    /** Tests: as if every chunk of room r loaded again (a restart): queue them all. */
    public static void testReload(ServerLevel level, PendingGates.Room r) {
        synchronized (LOADS) {
            LOADS.computeIfAbsent(level, l -> new ArrayList<>()).addAll(chunks(r));
        }
    }

    /** Tests: the chunks room r's rewrite touches. */
    public static List<Long> testChunks(PendingGates.Room r) {
        return chunks(r);
    }
}
