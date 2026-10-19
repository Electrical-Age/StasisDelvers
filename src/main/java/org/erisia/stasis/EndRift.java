package org.erisia.stasis;

import com.mojang.math.Transformation;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The End's island breaks up into the End rift (spreading mode, spread-DESIGN.md step 5; Baughn: "the core island
 * stays until the dragon is dead, but destruction starts as the end crystals are broken: they're stabilizing the
 * problem"). The geometry is EndRiftPlan's; this is the server side that carries it out.
 * <ul>
 * <li>Stages: each pillar crystal destroyed (by any means: EntityLeaveLevelEvent with a destroying removal; vanilla's
 * pillar crystals show their bottom, a player's don't, so placed ones are ignored; once per pillar) lifts that pillar's
 * sector of the island: its end stone goes, column by column, the outer chunks first, each chunk warned a moment ahead
 * (particles, a crumbling sound) and no column under a player's feet before they had {@link #UNDER_FOOT_TICKS} of
 * warning. Some surface blocks fly as block displays (an arc with a tumble) to their places in the islands forming
 * round the rift, where the real block appears when they land; then the sector's islands fill in.</li>
 * <li>The finale (HardMode.explodeEnd, the dragon's death): until the flash the core heaves (more shards fly out);
 * after it (it sends everyone home) every part not yet gone is lifted and every island placed, at the faster pace
 * while nobody is there (a creative visitor slows it to the stage pace), force-loading the chunks it needs one or two
 * at a time under its own ticket controller (released after; dropped on load, the work forces again what it needs).</li>
 * <li>Saved per End level ({@link Data}): which pillars broke, which parts are lifted and placed, and per part the
 * chunks done, so a restart resumes and nothing is applied twice (both steps only remove the island's own blocks and
 * only fill air, so a chunk redone after a crash changes nothing). The shards are cosmetic: discarded on load.</li>
 * </ul>
 * Writes skip neighbour and shape updates (Block.UPDATE_KNOWN_SHAPE, no UPDATE_NEIGHBORS): players' blocks on the
 * island stay where they were, floating, rather than popping off as their support goes.
 */
public final class EndRift {
    private EndRift() {
    }

    /** Block writes per tick while players are in the End (a stage), and with nobody there (the finale's build). */
    static final int STAGE_WRITES = 512, BUILD_WRITES = 4096;
    /** Island blocks placed per tick while players watch: the islands grow in over a few seconds. */
    static final int STAGE_PLACES = 96;
    /** Flying shards at once, and per part (the rest of a part's blocks just go and appear). */
    static final int MAX_SHARDS = 320, SHARDS_PER_PART = 260;
    /** A chunk is warned this long before its columns go (players present). */
    static final int WARN_TICKS = 30;
    /** A column under a player waits this long, with a warning, before it goes. */
    static final int UNDER_FOOT_TICKS = 60;
    /** A shard's flight: segments, ticks per segment (the last arrives within FLIGHT_MAX). */
    static final int SEGMENTS = 4;
    public static final int FLIGHT_MAX = 2 + 4 * 22;
    static final String SHARD_TAG = "stasisdelvers_end_shard";
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    /** GameTests: no work in the End (tests that enter hard mode would otherwise rebuild the test End for minutes). */
    public static volatile boolean testOff;
    /** GameTests: stages are recorded and queued, but no block is written (the test End stays as it is). */
    public static volatile boolean testPaused;
    /** GameTests: writes per tick instead of the usual (0: the usual), to catch the work midway. */
    public static volatile int testWrites;

    static final TicketController TICKETS = new TicketController(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "end_rift"), (level, helper) -> {
        // forced only while a chunk is worked: whatever was saved is stale, the work forces again what it needs
        helper.getBlockTickets().forEach((pos, t) -> {
            for (long c : t.normal()) helper.removeTicket(pos, c, false);
            for (long c : t.naturalSpawning()) helper.removeTicket(pos, c, true);
        });
    });

    static void registerTickets(RegisterTicketControllersEvent e) {
        e.register(TICKETS);
    }

    // ---- saved state

    public static final class Data extends SavedData {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("broken", 0).forGetter(d -> d.broken),
            Codec.INT.optionalFieldOf("lifted", 0).forGetter(d -> d.lifted),
            Codec.INT.optionalFieldOf("placed", 0).forGetter(d -> d.placed),
            Codec.BOOL.optionalFieldOf("finale", false).forGetter(d -> d.finale),
            Codec.LONG.listOf().optionalFieldOf("lift_done", List.of()).forGetter(d -> new ArrayList<>(d.liftDone)),
            Codec.LONG.listOf().optionalFieldOf("place_done", List.of()).forGetter(d -> new ArrayList<>(d.placeDone))
        ).apply(i, Data::new));
        static final SavedDataType<Data> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "end_rift"), Data::new, CODEC);

        /** Bit per pillar (EndSpikeFeature's index) whose crystal broke; parts lifted; parts' islands placed. */
        int broken, lifted, placed;
        /** The dragon died: every part goes. */
        boolean finale;
        /** Per part and chunk ({@link #key}): lifted, placed. */
        final Set<Long> liftDone = new HashSet<>(), placeDone = new HashSet<>();

        public Data() {
        }

        Data(int broken, int lifted, int placed, boolean finale, List<Long> liftDone, List<Long> placeDone) {
            this.broken = broken;
            this.lifted = lifted;
            this.placed = placed;
            this.finale = finale;
            this.liftDone.addAll(liftDone);
            this.placeDone.addAll(placeDone);
        }

        static Data get(ServerLevel end) {
            return end.getDataStorage().computeIfAbsent(TYPE);
        }

        boolean done(int parts) {
            int all = (1 << parts) - 1;
            return finale && (lifted & all) == all && (placed & all) == all;
        }
    }

    static long key(int part, int cx, int cz) {
        return (long) part << 48 | ((long) cx & 0xFFFFFF) << 24 | ((long) cz & 0xFFFFFF);
    }

    // ---- memory

    /** A lift or place of one part, worked through its columns or blocks chunk by chunk. */
    static final class Job {
        final int part;
        final boolean lift;
        /** Only after the flash (the finale's parts: players stand on them until it sends them home). */
        final boolean waitEmpty;
        long notBefore;
        int idx;
        /** The chunk being lifted (its key), to warn the next ones as it begins. */
        long chunk = -1;
        int shards;
        /** Columns put off under a player's feet: column -> first tick. */
        final Map<Long, Long> deferred = new HashMap<>();
        /** Chunks warned (lift with players present): chunk key -> tick. */
        final Map<Long, Long> warned = new HashMap<>();

        Job(int part, boolean lift, boolean waitEmpty, long notBefore) {
            this.part = part;
            this.lift = lift;
            this.waitEmpty = waitEmpty;
            this.notBefore = notBefore;
        }
    }

    /** A flying shard: a block display on its way to an island block. */
    static final class Shard {
        final Display.BlockDisplay e;
        final Vector3f delta;
        final Vector3f axis;
        final float spin, arc;
        final long born;
        final int seg;
        /** Where the real block appears when it lands (-1: nowhere, a cosmetic shard), and what. */
        final long target;
        final BlockState state;

        Shard(Display.BlockDisplay e, Vector3f delta, Vector3f axis, float spin, float arc, long born, int seg, long target, BlockState state) {
            this.e = e;
            this.delta = delta;
            this.axis = axis;
            this.spin = spin;
            this.arc = arc;
            this.born = born;
            this.seg = seg;
            this.target = target;
            this.state = state;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();
    private static final List<Shard> SHARDS = new ArrayList<>();
    private static final Set<Long> FORCED = new HashSet<>();
    private static boolean started, warmed;
    private static long heaveUntil;

    static void serverStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        JOBS.clear();
        SHARDS.clear();
        FORCED.clear();
        started = false;
        warmed = false;
        heaveUntil = 0;
        StaticDeeps.clearEndRifts();
    }

    // ---- the plan for a live End

    static EndRiftPlan plan(ServerLevel end) {
        List<int[]> spikes = new ArrayList<>();
        for (EndSpikeFeature.EndSpike s : EndSpikeFeature.getSpikesForLevel(end)) spikes.add(new int[]{s.getCenterX(), s.getCenterZ()});
        return EndRiftPlan.of(FieldData.get(end).deepSeed(), spikes);
    }

    static boolean active() {
        return StasisConfig.spreading && !testOff;
    }

    /** Has the island begun to break (a pillar's stage, or the finale)? The End event's start (HardMode.endEventOn). */
    static boolean begun(ServerLevel end) {
        if (!active()) return false;
        Data d = Data.get(end);
        return d.broken != 0 || d.finale;
    }

    // ---- stages

    /**
     * Pillar idx's crystal broke: its sector goes (once). False if it had gone, or outside spreading mode, or after
     * the dragon's death (the finale takes everything then). From the crystal event and /stasis spread endstage.
     */
    public static boolean crystalBroken(ServerLevel end, int idx) {
        if (!active() || end.dimension() != Level.END || Spread.hard(end.getServer())) return false;
        EndRiftPlan pl = plan(end);
        if (idx < 0 || idx >= pl.core) return false;
        Data d = Data.get(end);
        if ((d.broken & 1 << idx) != 0) return false;
        d.broken |= 1 << idx;
        d.setDirty();
        ensureStarted(end);
        queuePart(end, d, idx, false);
        StasisDelvers.LOG.info("End rift: pillar {}'s crystal broke; its part of the island lifts ({} of {})", idx, Integer.bitCount(d.broken), pl.core);
        long[] cols = pl.columns(idx);
        if (cols.length > 0) {
            BlockPos c = BlockPos.of(cols[cols.length / 2]);
            int y = end.getHeight(Heightmap.Types.WORLD_SURFACE, c.getX(), c.getZ());
            end.playSound(null, c.getX(), y, c.getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 10f, 0.45f);
            end.playSound(null, c.getX(), y, c.getZ(), SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 3f, 0.6f);
        }
        for (ServerPlayer p : end.players())
            p.sendOverlayMessage(Component.literal("The island shudders: that crystal was holding part of it together.").withStyle(ChatFormatting.DARK_PURPLE));
        Spread.refresh(end.getServer());   // the End event begins (the clients' fog)
        return true;
    }

    private static void queuePart(ServerLevel end, Data d, int part, boolean waitEmpty) {
        for (Job j : JOBS) if (j.part == part) return;
        long now = end.getGameTime();
        if ((d.lifted & 1 << part) == 0) JOBS.add(new Job(part, true, waitEmpty, now));
        else if ((d.placed & 1 << part) == 0) JOBS.add(new Job(part, false, waitEmpty, now));
    }

    /** EntityLeaveLevelEvent: a pillar's own crystal destroyed (any cause) is a stage. */
    static void entityLeave(EntityLeaveLevelEvent e) {
        if (!(e.getEntity() instanceof EndCrystal c) || !(e.getLevel() instanceof ServerLevel end) || end.dimension() != Level.END || !active()) return;
        var why = c.getRemovalReason();
        // (vanilla's pillar crystals show their bottom; a player's never do: EndCrystalItem)
        if (why == null || !why.shouldDestroy() || !c.showsBottom()) return;
        List<EndSpikeFeature.EndSpike> spikes = EndSpikeFeature.getSpikesForLevel(end);
        for (int i = 0; i < spikes.size(); i++)
            if (spikes.get(i).getTopBoundingBox().inflate(1).contains(c.position())) {
                crystalBroken(end, i);
                return;
            }
    }

    /** EntityJoinLevelEvent: shards are cosmetic and memory-only; a saved one loading again is dropped. */
    static void entityJoin(EntityJoinLevelEvent e) {
        if (e.loadedFromDisk() && e.getEntity() instanceof Display.BlockDisplay && e.getEntity().entityTags().contains(SHARD_TAG)) e.setCanceled(true);
    }

    // ---- the finale (the dragon's death)

    /** HardMode.explodeEnd: every part goes; the core heaves until the flash, the rest is built once the End is empty. */
    static void finale(ServerLevel end) {
        if (!active()) return;
        Data d = Data.get(end);
        StaticDeeps.addEndRift(FieldData.get(end).deepSeed());
        if (d.finale) return;
        d.finale = true;
        d.setDirty();
        ensureStarted(end);
        EndRiftPlan pl = plan(end);
        for (int p = 0; p < pl.parts; p++) queuePart(end, d, p, true);
        heaveUntil = end.getGameTime() + HardMode.flashTicks();
        StasisDelvers.LOG.info("End rift: the dragon died; the rest of the island goes ({})", pl);
    }

    /** HardMode's flash: the shards in the air are dropped (the End stills, nobody is left to see them). */
    static void flash(ServerLevel end) {
        for (Shard s : SHARDS) s.e.discard();
        SHARDS.clear();
        heaveUntil = 0;
    }

    /** A restart: the jobs come back from the saved state. */
    private static void ensureStarted(ServerLevel end) {
        if (started) return;
        started = true;
        Data d = Data.get(end);
        EndRiftPlan pl = plan(end);
        for (int p = 0; p < pl.parts; p++) {
            boolean stage = p < pl.core && (d.broken & 1 << p) != 0;
            if (stage || d.finale) queuePart(end, d, p, !stage);
        }
    }

    // ---- the tick

    static void tick(ServerTickEvent.Post e) {
        MinecraftServer srv = e.getServer();
        if (!SHARDS.isEmpty()) flyShards(srv.overworld().getGameTime());
        if (!active()) return;
        ServerLevel end = srv.getLevel(Level.END);
        if (end == null) return;
        if (Spread.hard(srv)) StaticDeeps.addEndRift(FieldData.get(end).deepSeed());
        if (!started) {
            // (the plan takes a moment to build: when someone arrives, not at the first crystal mid-fight)
            if (!warmed && !end.players().isEmpty()) {
                warmed = true;
                plan(end);
            }
            Data d = Data.get(end);
            if (d.broken == 0 && !d.finale) return;
            ensureStarted(end);
        }
        long now = end.getGameTime();
        boolean watched = end.players().stream().anyMatch(p -> !p.isSpectator());
        if (watched && now < heaveUntil) heave(end, now);
        if (JOBS.isEmpty() || testPaused) return;
        EndRiftPlan pl = plan(end);
        Data d = Data.get(end);
        int[] budget = {testWrites > 0 ? testWrites : watched ? STAGE_WRITES : BUILD_WRITES};
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext() && budget[0] > 0; ) {
            Job j = it.next();
            if (now < j.notBefore || j.waitEmpty && HardMode.holdsEndOpen()) continue;
            boolean finished = j.lift ? lift(end, pl, d, j, now, watched, budget) : place(end, pl, d, j, watched, budget);
            if (!finished) continue;
            it.remove();
            if (j.lift) {
                d.lifted |= 1 << j.part;
                // its islands once the shards have landed
                JOBS.add(new Job(j.part, false, j.waitEmpty, now + (j.shards > 0 ? FLIGHT_MAX + 5 : 0)));
                StasisDelvers.LOG.info("End rift: part {} lifted", j.part);
            } else {
                d.placed |= 1 << j.part;
                StasisDelvers.LOG.info("End rift: part {}'s islands placed", j.part);
            }
            d.setDirty();
            break;   // (the list changed)
        }
        if (JOBS.isEmpty()) {
            releaseAll(end);
            if (d.done(pl.parts)) StasisDelvers.LOG.info("End rift: built ({} island blocks)", pl.totalBlocks());
        }
    }

    /** Make chunk (cx, cz) available: true if loaded; else force it (once) and wait. */
    private static boolean loaded(ServerLevel end, int cx, int cz) {
        if (end.hasChunk(cx, cz)) return true;
        long k = ChunkPos.pack(cx, cz);
        if (FORCED.add(k)) TICKETS.forceChunk(end, BlockPos.ZERO, cx, cz, true, false);
        return false;
    }

    private static void release(ServerLevel end, int cx, int cz) {
        if (FORCED.remove(ChunkPos.pack(cx, cz))) TICKETS.forceChunk(end, BlockPos.ZERO, cx, cz, false, false);
    }

    private static void releaseAll(ServerLevel end) {
        for (long k : FORCED) TICKETS.forceChunk(end, BlockPos.ZERO, ChunkPos.getX(k), ChunkPos.getZ(k), false, false);
        FORCED.clear();
    }

    /** Work a lift: the part's columns from where it stood. True when every column is gone. */
    private static boolean lift(ServerLevel end, EndRiftPlan pl, Data d, Job j, long now, boolean watched, int[] budget) {
        long[] cols = pl.columns(j.part);
        while (budget[0] > 0 && j.idx < cols.length) {
            long c = cols[j.idx];
            int x = BlockPos.getX(c), z = BlockPos.getZ(c), cx = x >> 4, cz = z >> 4;
            long key = key(j.part, cx, cz);
            if (d.liftDone.contains(key)) {
                j.idx++;
                continue;
            }
            if (!loaded(end, cx, cz)) return false;
            if (watched) {
                Long w = j.warned.get(key);
                if (w == null) {
                    warnChunk(end, pl, j, j.idx, now);
                    return false;
                }
                if (now - w < WARN_TICKS) return false;
                if (j.chunk != key) {
                    // this chunk begins: warn the next two now, so the front doesn't stop at each
                    j.chunk = key;
                    int k = j.idx, seen = 0;
                    long last = ChunkPos.pack(cx, cz);
                    while (++k < cols.length && seen < 2) {
                        long ck = ChunkPos.pack(BlockPos.getX(cols[k]) >> 4, BlockPos.getZ(cols[k]) >> 4);
                        if (ck != last) {
                            last = ck;
                            seen++;
                            if (!j.warned.containsKey(key(j.part, ChunkPos.getX(ck), ChunkPos.getZ(ck)))) warnChunk(end, pl, j, k, now);
                        }
                    }
                }
                if (underFoot(end, x, z)) {
                    Long first = j.deferred.putIfAbsent(c, now);
                    if (first == null || now - first < UNDER_FOOT_TICKS) {
                        crumbleWarning(end, x, z, first == null);
                        j.idx++;
                        budget[0]--;
                        finishChunk(end, d, j, cols, key, cx, cz);
                        continue;
                    }
                }
            }
            budget[0] -= 2 + liftColumn(end, pl, j, x, z, watched, now);
            j.deferred.remove(c);
            j.idx++;
            finishChunk(end, d, j, cols, key, cx, cz);
        }
        if (j.idx < cols.length) return false;
        // the columns put off under players: again, until each goes (a player still there after the warning falls)
        for (Iterator<Map.Entry<Long, Long>> it = j.deferred.entrySet().iterator(); it.hasNext() && budget[0] > 0; ) {
            var en = it.next();
            int x = BlockPos.getX(en.getKey()), z = BlockPos.getZ(en.getKey());
            if (!loaded(end, x >> 4, z >> 4)) return false;
            if (watched && underFoot(end, x, z) && now - en.getValue() < UNDER_FOOT_TICKS) {
                if ((now - en.getValue()) % 10 == 0) crumbleWarning(end, x, z, false);
                continue;
            }
            budget[0] -= 2 + liftColumn(end, pl, j, x, z, watched, now);
            it.remove();
            // its chunk is done once no other column there waits
            boolean more = false;
            for (long o : j.deferred.keySet()) more |= BlockPos.getX(o) >> 4 == x >> 4 && BlockPos.getZ(o) >> 4 == z >> 4;
            if (!more) {
                d.liftDone.add(key(j.part, x >> 4, z >> 4));
                d.setDirty();
                release(end, x >> 4, z >> 4);
            }
        }
        return j.deferred.isEmpty();
    }

    /** After column j.idx - 1: if it was its chunk's last (and none there was put off), the chunk is done. */
    private static void finishChunk(ServerLevel end, Data d, Job j, long[] cols, long key, int cx, int cz) {
        if (j.idx < cols.length && BlockPos.getX(cols[j.idx]) >> 4 == cx && BlockPos.getZ(cols[j.idx]) >> 4 == cz) return;
        for (long c : j.deferred.keySet()) if (BlockPos.getX(c) >> 4 == cx && BlockPos.getZ(c) >> 4 == cz) return;
        d.liftDone.add(key);
        d.setDirty();
        release(end, cx, cz);
        if (j.idx < cols.length) loaded(end, BlockPos.getX(cols[j.idx]) >> 4, BlockPos.getZ(cols[j.idx]) >> 4);   // (the next, on its way)
    }

    /** Is a player standing on column (x, z) (or about to)? */
    private static boolean underFoot(ServerLevel end, int x, int z) {
        for (ServerPlayer p : end.players()) {
            if (p.isSpectator()) continue;
            double dx = p.getX() - (x + 0.5), dz = p.getZ() - (z + 0.5);
            if (dx * dx + dz * dz < 2.0 * 2.0) return true;
        }
        return false;
    }

    /** Is this block the island's own, to go? End stone everywhere; in the core also the pillars and the fountain. */
    static boolean terrain(BlockState st, boolean core, int x, int z) {
        if (st.is(Blocks.END_STONE)) return true;
        if (!core) return false;
        if (st.is(Blocks.OBSIDIAN) || st.is(Blocks.BEDROCK) || st.is(Blocks.IRON_BARS) || st.is(Blocks.FIRE) || st.is(Blocks.END_PORTAL)
            || st.is(Blocks.END_GATEWAY) || st.is(Blocks.DRAGON_EGG)) return true;
        return (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)) && x * x + z * z <= 16;   // the fountain's
    }

    /** Lift column (x, z) of job j's part: its island blocks go; some of its top end stone flies (players watching). */
    private static int liftColumn(ServerLevel end, EndRiftPlan pl, Job j, int x, int z, boolean watched, long now) {
        BlockPos[] stone = new BlockPos[1];
        int n = liftColumn(end, x, z, j.part == pl.core, end.getMinY(), pl::isIsland, stone);
        if (j.part == pl.core)
            // a pillar's own crystal still standing (the dragon died first) goes with its pillar, not left in the air
            for (EndSpikeFeature.EndSpike sp : EndSpikeFeature.getSpikesForLevel(end))
                if (sp.getCenterX() == x && sp.getCenterZ() == z)
                    for (EndCrystal c : end.getEntitiesOfClass(EndCrystal.class, sp.getTopBoundingBox(), EndCrystal::showsBottom)) c.discard();
        if (!watched || stone[0] == null) return n;
        long[] cols = pl.columns(j.part);
        float chance = Math.min(1f, SHARDS_PER_PART / (float) Math.max(1, cols.length));
        if (Noise.hash32(x, 1, z, pl.seed ^ 0x5A4D) < chance && SHARDS.size() < MAX_SHARDS && end.areEntitiesLoaded(ChunkPos.pack(x >> 4, z >> 4))) {
            int[] t = pl.targetIndex(j.part, j.idx, cols.length);
            if (t != null) {
                // the real block lands with it if it belongs to this part (not the core's, which may hang over ground
                // still standing: that shard is cosmetic, its block comes with the core's islands)
                boolean real = t[0] == j.part;
                spawnShard(end, stone[0], Blocks.END_STONE.defaultBlockState(), BlockPos.of(pl.blocks(t[0])[t[1]]), real ? state(pl.kind(t[0], t[1])) : null, now);
                j.shards++;
            }
        }
        if (Noise.hash32(x, 2, z, pl.seed) < 0.12f)
            end.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.END_STONE.defaultBlockState()), x + 0.5, stone[0].getY() + 0.5, z + 0.5,
                6, 0.4, 0.4, 0.4, 0.1);
        return n;
    }

    /**
     * Remove column (x, z)'s island blocks (terrain) but those `keep` says are the rift's islands; stone[0] gets the
     * topmost end stone removed. Returns blocks written.
     */
    static int liftColumn(ServerLevel level, int x, int z, boolean core, int minY, java.util.function.LongPredicate keep, BlockPos[] stone) {
        LevelChunk ch = level.getChunk(x >> 4, z >> 4);
        int top = ch.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int n = 0;
        for (int y = top; y >= minY; y--) {
            BlockState st = ch.getBlockState(p.set(x, y, z));
            if (st.isAir() || !terrain(st, core, x, z) || keep.test(p.asLong())) continue;
            if (stone[0] == null && st.is(Blocks.END_STONE)) stone[0] = p.immutable();
            level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
            n++;
        }
        return n;
    }

    static BlockState state(EndRiftPlan.Kind k) {
        return switch (k) {
            case END_STONE -> Blocks.END_STONE.defaultBlockState();
            case OBSIDIAN -> Blocks.OBSIDIAN.defaultBlockState();
            // dead (age 5): it doesn't grow, and the End stills soon anyway
            case CHORUS -> Blocks.CHORUS_FLOWER.defaultBlockState().setValue(ChorusFlowerBlock.AGE, ChorusFlowerBlock.DEAD_AGE);
        };
    }

    /** A chunk of the lift's front is about to go: dust and a crumbling sound over its columns. */
    private static void warnChunk(ServerLevel end, EndRiftPlan pl, Job j, int from, long now) {
        long[] cols = pl.columns(j.part);
        int cx = BlockPos.getX(cols[from]) >> 4, cz = BlockPos.getZ(cols[from]) >> 4;
        j.warned.put(key(j.part, cx, cz), now);
        if (!end.hasChunk(cx, cz)) return;
        int shown = 0;
        double sx = 0, sy = 0, sz = 0;
        for (int k = from; k < cols.length && shown < 14; k += 7) {
            int x = BlockPos.getX(cols[k]), z = BlockPos.getZ(cols[k]);
            if (x >> 4 != cx || z >> 4 != cz) break;
            int y = end.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            end.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.END_STONE.defaultBlockState()), x + 0.5, y + 0.1, z + 0.5, 5, 0.5, 0.05, 0.5, 0.05);
            sx += x;
            sy += y;
            sz += z;
            shown++;
        }
        if (shown > 0)
            end.playSound(null, sx / shown, sy / shown, sz / shown, SoundEvents.DEEPSLATE_BREAK, SoundSource.BLOCKS, 4f, 0.5f);
    }

    /** The ground under a player is about to go. */
    private static void crumbleWarning(ServerLevel end, int x, int z, boolean tell) {
        int y = end.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        end.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.END_STONE.defaultBlockState()), x + 0.5, y + 0.1, z + 0.5, 10, 0.6, 0.05, 0.6, 0.05);
        if (!tell) return;
        end.playSound(null, x + 0.5, y, z + 0.5, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 2f, 0.5f);
        for (ServerPlayer p : end.players()) {
            double dx = p.getX() - (x + 0.5), dz = p.getZ() - (z + 0.5);
            if (dx * dx + dz * dz < 4)
                p.sendOverlayMessage(Component.literal("The ground crumbles under your feet!").withStyle(ChatFormatting.RED));
        }
    }

    /** Work a place: the part's island blocks, into air only. True when every one is placed. */
    private static boolean place(ServerLevel end, EndRiftPlan pl, Data d, Job j, boolean watched, int[] budget) {
        long[] bs = pl.blocks(j.part);
        int slow = watched ? STAGE_PLACES : Integer.MAX_VALUE;
        while (budget[0] > 0 && slow > 0 && j.idx < bs.length) {
            BlockPos b = BlockPos.of(bs[j.idx]);
            int cx = b.getX() >> 4, cz = b.getZ() >> 4;
            long key = key(j.part, cx, cz);
            if (d.placeDone.contains(key)) {
                j.idx++;
                continue;
            }
            if (!loaded(end, cx, cz)) return false;
            if (end.getBlockState(b).isAir()) {
                end.setBlock(b, state(pl.kind(j.part, j.idx)), FLAGS);
                budget[0]--;
                slow--;
            } else budget[0] -= 1;
            j.idx++;
            if (j.idx >= bs.length || BlockPos.getX(bs[j.idx]) >> 4 != cx || BlockPos.getZ(bs[j.idx]) >> 4 != cz) {
                d.placeDone.add(key);
                d.setDirty();
                release(end, cx, cz);
                if (j.idx < bs.length) loaded(end, BlockPos.getX(bs[j.idx]) >> 4, BlockPos.getZ(bs[j.idx]) >> 4);   // (the next, on its way)
            }
        }
        return j.idx >= bs.length;
    }

    // ---- shards

    /**
     * A block display from `from` flying to `to` on an arc with a tumble; when it lands, `place` (if not null) is set
     * there if that's air, and the display goes. The display stays at `from`; its transformation carries it, interpolated
     * by the client over each segment (Display's transformation interpolation), so the server sends a few updates only.
     */
    static void spawnShard(ServerLevel level, BlockPos from, BlockState look, BlockPos to, BlockState place, long now) {
        Display.BlockDisplay e = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
        e.setPos(from.getX(), from.getY(), from.getZ());
        e.setBlockState(look);
        e.setViewRange(5f);   // (64 x 5 blocks: the scene spans the island and the shell)
        e.addTag(SHARD_TAG);
        if (!level.addFreshEntity(e)) return;
        int h = Noise.hashInt(from.getX(), from.getY(), from.getZ(), 0x5A4E);
        int seg = 12 + Math.floorMod(h, 11);
        Vector3f axis = new Vector3f((h & 0xFF) / 255f - 0.5f, ((h >> 8) & 0xFF) / 255f - 0.5f, ((h >> 16) & 0xFF) / 255f - 0.5f);
        if (axis.lengthSquared() < 1e-4f) axis.set(0, 1, 0);
        axis.normalize();
        Vector3f delta = new Vector3f(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
        float arc = 6 + 0.25f * (float) Math.hypot(delta.x, delta.z);
        float spin = (float) Math.toRadians(70 + Math.floorMod(h >> 4, 50));
        SHARDS.add(new Shard(e, delta, axis, spin, arc, now, seg, place == null ? -1 : to.asLong(), place));
    }

    /** Each shard: its next segment when due; when it lands, its block and its end. */
    private static void flyShards(long now) {
        for (Iterator<Shard> it = SHARDS.iterator(); it.hasNext(); ) {
            Shard s = it.next();
            long age = now - s.born;
            if (age < 2) continue;
            long k = (age - 2) / s.seg;
            if (k >= SEGMENTS) {
                // landed
                if (s.target != -1 && s.e.level() instanceof ServerLevel level) {
                    BlockPos t = BlockPos.of(s.target);
                    if (level.hasChunk(t.getX() >> 4, t.getZ() >> 4) && level.getBlockState(t).isAir()) level.setBlock(t, s.state, FLAGS);
                }
                s.e.discard();
                it.remove();
                continue;
            }
            if ((age - 2) % s.seg != 0 || s.e.isRemoved()) continue;
            float u = (k + 1) / (float) SEGMENTS;
            Vector3f tr = new Vector3f(s.delta).mul(u).add(0, s.arc * 4 * u * (1 - u), 0);
            Quaternionf q = new Quaternionf().rotateAxis(s.spin * (k + 1), s.axis);
            Matrix4f m = new Matrix4f().translate(tr).translate(0.5f, 0.5f, 0.5f).rotate(q).translate(-0.5f, -0.5f, -0.5f);
            s.e.setTransformationInterpolationDuration(s.seg);
            s.e.setTransformationInterpolationDelay(0);
            s.e.setTransformation(new Transformation(m));
        }
    }

    /** The finale before the flash: the core's surface heaves, shards thrown out towards the rift's islands. */
    private static void heave(ServerLevel end, long now) {
        if (SHARDS.size() >= MAX_SHARDS) return;
        EndRiftPlan pl = plan(end);
        long[] cols = pl.columns(pl.core);
        if (cols.length == 0) return;
        var rnd = end.getRandom();
        for (int i = 0; i < 4; i++) {
            int ci = rnd.nextInt(cols.length);
            int x = BlockPos.getX(cols[ci]), z = BlockPos.getZ(cols[ci]);
            if (!end.hasChunk(x >> 4, z >> 4) || !end.areEntitiesLoaded(ChunkPos.pack(x >> 4, z >> 4))) continue;
            int y = end.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            BlockPos from = new BlockPos(x, y, z);
            if (!end.getBlockState(from).is(Blocks.END_STONE)) continue;
            long t = pl.target(pl.core, rnd.nextInt(1000), 1000);
            if (t == -1) return;
            spawnShard(end, from, Blocks.END_STONE.defaultBlockState(), BlockPos.of(t), null, now);
        }
        if (now % 5 == 0)
            end.sendParticles(ParticleTypes.REVERSE_PORTAL, 0.5, StaticDeeps.END_RIFT_Y, 0.5, 60, 6, 3, 6, 0.4);
    }

    // ---- reports, commands, tests

    /** /stasis spread endstatus. */
    public static String status(ServerLevel end) {
        EndRiftPlan pl = plan(end);
        Data d = Data.get(end);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("End rift: pillars broken %d of %d (%s), parts lifted %s, placed %s, finale %s%s; jobs %d, shards %d, forced chunks %d; %s",
            Integer.bitCount(d.broken), pl.core, Integer.toBinaryString(d.broken), Integer.toBinaryString(d.lifted), Integer.toBinaryString(d.placed),
            d.finale, d.done(pl.parts) ? " (built)" : "", JOBS.size(), SHARDS.size(), FORCED.size(), pl));
        for (Job j : JOBS) {
            int n = j.lift ? pl.columns(j.part).length : pl.blocks(j.part).length;
            sb.append(String.format("\n  part %d %s: %d of %d%s%s", j.part, j.lift ? "lift" : "place", j.idx, n,
                j.waitEmpty ? " (once the End is empty)" : "", j.deferred.isEmpty() ? "" : ", " + j.deferred.size() + " under players"));
        }
        return sb.toString();
    }

    /** Tests: the saved state's codec, through NBT and back. */
    public static boolean testCodecRoundTrip() {
        Data d = new Data(0b101, 0b1, 0b0, true, List.of(key(3, -2, 5), key(10, 1, -1)), List.of(key(0, 0, 0)));
        var tag = Data.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, d).getOrThrow();
        Data back = Data.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).getOrThrow();
        Data empty = Data.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, new net.minecraft.nbt.CompoundTag()).getOrThrow();
        return back.broken == 0b101 && back.lifted == 1 && back.placed == 0 && back.finale && back.liftDone.equals(d.liftDone)
            && back.placeDone.equals(d.placeDone) && empty.broken == 0 && !empty.finale && empty.liftDone.isEmpty();
    }

    /** Tests: pillars broken (bits). */
    public static int testBroken(ServerLevel end) {
        return Data.get(end).broken;
    }

    /** Tests: forget the End's event state (and the jobs it queued). */
    public static void testReset(ServerLevel end) {
        Data d = Data.get(end);
        d.broken = d.lifted = d.placed = 0;
        d.finale = false;
        d.liftDone.clear();
        d.placeDone.clear();
        d.setDirty();
        JOBS.clear();
        for (Shard s : SHARDS) s.e.discard();
        SHARDS.clear();
        releaseAll(end);
        started = false;
    }

    /** Tests: lift one column down to minY (a pillar's sector's or the core's rule), returning blocks removed. */
    public static int testLiftColumn(ServerLevel level, int x, int z, boolean core, int minY) {
        return liftColumn(level, x, z, core, minY, q -> false, new BlockPos[1]);
    }

    /** Tests: a shard from `from` to `to` that places `place` when it lands; its flight's length in ticks. */
    public static int testShard(ServerLevel level, BlockPos from, BlockPos to, BlockState place) {
        spawnShard(level, from, Blocks.END_STONE.defaultBlockState(), to, place, level.getGameTime());
        return FLIGHT_MAX + 2;
    }

    /** Tests: the jobs queued, as status lines ("part 3 lift: ..."). */
    public static String testJobs(ServerLevel end) {
        return status(end);
    }

    /** Tests: as a restart would leave it: the memory gone (jobs, shards, tickets), the saved state kept. */
    public static void testRestart(ServerLevel end) {
        JOBS.clear();
        for (Shard s : SHARDS) s.e.discard();
        SHARDS.clear();
        releaseAll(end);
        started = false;
    }

    /** Tests: {broken, lifted, placed} bits, the chunks forced now, and the chunks lifted (saved). */
    public static int[] testState(ServerLevel end) {
        Data d = Data.get(end);
        return new int[]{d.broken, d.lifted, d.placed, FORCED.size(), d.liftDone.size()};
    }

    /** Tests: the dragon's death, as HardMode.explodeEnd calls it; and whether the rift is built. */
    public static void testFinale(ServerLevel end) {
        finale(end);
    }

    public static boolean testBuilt(ServerLevel end) {
        return Data.get(end).done(plan(end).parts);
    }

    /** Tests: the live End's plan. */
    public static EndRiftPlan testPlan(ServerLevel end) {
        return plan(end);
    }

    /** Tests: shards in the air. */
    public static int testShards() {
        return SHARDS.size();
    }
}
