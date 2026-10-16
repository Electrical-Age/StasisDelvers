package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Rift crystal growth (rift stage 2, notes/rifts/STAGE2-PLAN.md C). Nothing ticks in stopped land, so buds grow
 * analytically: a bud grows only while its strata pocket is in mist, violet or rift (Pockets.exposure, closed form)
 * and no field covers it. Every bud (a face of a budding block) has an accumulator: exposure seconds E and the time
 * it was last brought up to date. Loaded strata chunks are swept on load and every 30 s; the closed form bridges the
 * time a chunk was unloaded (no tower can run there then). A chunk's first sweep stamps its generated buds with their
 * stage's worth of exposure, so worldgen's mix stays. A bud that went missing (mined, blown up) starts over from 0.
 * Stages: small after 1 stageTime of exposure, then medium, large, cluster (config crystalStageSeconds).
 */
public final class Crystals {
    private Crystals() {
    }

    /** Share of a budding block's open faces that can grow a bud (worldgen buds 60% of cavity cells). */
    static final float FACE_SHARE = 0.6f;

    static List<Block> stages() {
        return List.of(StasisRegistry.RIFT_CRYSTAL_SMALL.get(), StasisRegistry.RIFT_CRYSTAL_MEDIUM.get(),
            StasisRegistry.RIFT_CRYSTAL_LARGE.get(), StasisRegistry.RIFT_CRYSTAL_CLUSTER.get());
    }

    /** The stage (0-3) of a bud block facing dir, or -1 if the block isn't one. */
    static int stageOf(BlockState st, Direction dir) {
        List<Block> s = stages();
        for (int i = 0; i < 4; i++)
            if (st.is(s.get(i))) return st.getValue(AmethystClusterBlock.FACING) == dir ? i : -2;
        return -1;
    }

    /** The stage an exposure gives: -1 (nothing yet), 0 small ... 3 cluster. */
    public static int stageFor(double e) {
        return (int) Math.min(3, Math.floor(e / StasisConfig.crystalStageSeconds) - 1);
    }

    // ---- per-level data

    static final class Bud {
        byte dir;
        double t, e;
        byte stage;

        Bud(int dir, double t, double e, int stage) {
            this.dir = (byte) dir;
            this.t = t;
            this.e = e;
            this.stage = (byte) stage;
        }
    }

    public static final class Data extends SavedData {
        record Entry(long pos, byte dir, double t, double e, byte stage) {
            static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
                Codec.BYTE.fieldOf("dir").forGetter(Entry::dir),
                Codec.DOUBLE.fieldOf("t").forGetter(Entry::t),
                Codec.DOUBLE.fieldOf("e").forGetter(Entry::e),
                Codec.BYTE.fieldOf("stage").forGetter(Entry::stage)
            ).apply(i, Entry::new));
        }

        static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
            Entry.CODEC.listOf().fieldOf("buds").forGetter(Data::entries),
            Codec.LONG.listOf().fieldOf("swept").forGetter(d -> new ArrayList<>(d.swept))
        ).apply(i, Data::new));

        static final SavedDataType<Data> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "rift_crystals"), Data::new, CODEC);

        /** chunk -> bud position -> bud */
        final Map<Long, Map<Long, Bud>> buds = new HashMap<>();
        /** chunks whose generated buds have been stamped */
        final Set<Long> swept = new HashSet<>();

        public Data() {
        }

        private Data(List<Entry> entries, List<Long> swept) {
            for (Entry e : entries)
                buds.computeIfAbsent(ChunkPos.pack(BlockPos.of(e.pos())), k -> new HashMap<>()).put(e.pos(), new Bud(e.dir(), e.t(), e.e(), e.stage()));
            this.swept.addAll(swept);
        }

        List<Entry> entries() {
            List<Entry> l = new ArrayList<>();
            buds.values().forEach(m -> m.forEach((k, b) -> l.add(new Entry(k, b.dir, b.t, b.e, b.stage))));
            return l;
        }

        public static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }
    }

    // ---- sweeping

    private static final Map<ServerLevel, Set<Long>> LOADED = new WeakHashMap<>();
    private static final Map<ServerLevel, List<Long>> PENDING = new WeakHashMap<>();

    /** Can any strata reach into chunk (cx, cz)? */
    static boolean strataChunk(int seed, int cx, int cz) {
        float x = cx * 16 + 8, z = cz * 16 + 8;
        for (StaticDeeps.Deep d : StaticDeeps.near(x, 100, z, 300, seed)) {
            double r = Strata.bounds(d).radius() + 12;
            if ((x - d.x()) * (x - d.x()) + (z - d.z()) * (z - d.z()) <= r * r) return true;
        }
        return false;
    }

    static void chunkLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !Stasis.stops(level)) return;
        synchronized (PENDING) {
            PENDING.computeIfAbsent(level, l -> new ArrayList<>()).add(e.getChunk().getPos().pack());
        }
    }

    static void chunkUnload(ChunkEvent.Unload e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        synchronized (PENDING) {
            Set<Long> s = LOADED.get(level);
            if (s != null) s.remove(e.getChunk().getPos().pack());
        }
    }

    static void tick(ServerTickEvent.Post e) {
        boolean all = e.getServer().getTickCount() % 600 == 0;
        for (ServerLevel level : e.getServer().getAllLevels()) {
            if (!Stasis.stops(level)) continue;
            int seed = Deeps.seed(level);
            if (seed == 0) continue;
            List<Long> loads;
            Set<Long> loaded;
            synchronized (PENDING) {
                loads = PENDING.remove(level);
                loaded = LOADED.computeIfAbsent(level, l -> new HashSet<>());
                if (loads != null)
                    for (long k : loads)
                        if (strataChunk(seed, ChunkPos.unpack(k).x(), ChunkPos.unpack(k).z())) loaded.add(k);
            }
            List<Long> todo = all ? new ArrayList<>(loaded) : loads;
            if (todo == null) continue;
            for (long k : todo)
                if (loaded.contains(k)) sweep(level, ChunkPos.unpack(k));
        }
    }

    /** Bring chunk cp's buds up to date (public for tests and /stasis). */
    public static void sweep(ServerLevel level, ChunkPos cp) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x(), cp.z());
        if (chunk == null) return;
        Data data = Data.get(level);
        long key = cp.pack();
        double t = Deeps.time(level);
        Map<Long, Bud> buds = data.buds.computeIfAbsent(key, k -> new HashMap<>());
        if (data.swept.add(key)) {
            stamp(level, chunk, buds, t);
            data.setDirty();
        }
        if (buds.isEmpty()) {
            data.buds.remove(key);
            return;
        }
        Block budding = StasisRegistry.BUDDING_RIFT_CRYSTAL.get();
        List<Block> stages = stages();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        var it = buds.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            BlockPos pos = BlockPos.of(en.getKey());
            Bud b = en.getValue();
            Direction dir = Direction.from3DDataValue(b.dir);
            if (!level.getBlockState(m.setWithOffset(pos, dir.getOpposite())).is(budding)) {
                it.remove();   // the budding block is gone
                data.setDirty();
                continue;
            }
            BlockState here = level.getBlockState(pos);
            int now = stageOf(here, dir);
            if (b.stage >= 0 && now != b.stage) {   // mined or blown away: start over
                b.e = 0;
                b.stage = -1;
            }
            boolean open = here.isAir() || now >= 0;
            if (open && !Stasis.isRunning(level, pos)) {
                int id = Pockets.strataId(level, pos.getX(), pos.getY(), pos.getZ());
                if (id >= 0) {
                    int px = Math.floorDiv(pos.getX(), Pockets.POCKET), py = Math.floorDiv(pos.getY(), Pockets.POCKET), pz = Math.floorDiv(pos.getZ(), Pockets.POCKET);
                    b.e += Pockets.exposure(id, px, py, pz, t) - Pockets.exposure(id, px, py, pz, b.t);
                }
            }
            b.t = t;
            int want = stageFor(b.e);
            if (open && want > b.stage) {
                level.setBlock(pos, stages.get(want).defaultBlockState().setValue(AmethystClusterBlock.FACING, dir), Block.UPDATE_CLIENTS);
                b.stage = (byte) want;
            }
            data.setDirty();
        }
    }

    /** Tests: forget that chunk cp was stamped, and its buds. */
    public static void testForget(ServerLevel level, ChunkPos cp) {
        Data d = Data.get(level);
        d.swept.remove(cp.pack());
        d.buds.remove(cp.pack());
    }

    /** Tests: the bud at pos (exposure seconds, or NaN), and moving its last update back by dt seconds. */
    public static double testExposure(ServerLevel level, BlockPos pos, double dt) {
        var m = Data.get(level).buds.get(ChunkPos.containing(pos).pack());
        Bud b = m == null ? null : m.get(pos.asLong());
        if (b == null) return Double.NaN;
        b.t -= dt;
        return b.e;
    }

    /** A chunk's first sweep: every budding block's open faces (a hashed 60%) and generated buds get an entry. */
    private static void stamp(ServerLevel level, LevelChunk chunk, Map<Long, Bud> buds, double t) {
        Block budding = StasisRegistry.BUDDING_RIFT_CRYSTAL.get();
        LevelChunkSection[] sections = chunk.getSections();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection s = sections[si];
            if (s.hasOnlyAir() || !s.maybeHas(st -> st.is(budding))) continue;
            int y0 = level.getSectionYFromSectionIndex(si) << 4;
            for (int ly = 0; ly < 16; ly++)
                for (int lz = 0; lz < 16; lz++)
                    for (int lx = 0; lx < 16; lx++) {
                        if (!s.getBlockState(lx, ly, lz).is(budding)) continue;
                        BlockPos bp = new BlockPos(x0 + lx, y0 + ly, z0 + lz);
                        for (Direction dir : Direction.values()) {
                            m.setWithOffset(bp, dir);
                            BlockState n = level.getBlockState(m);
                            int st = stageOf(n, dir);
                            long k = m.asLong();
                            if (buds.containsKey(k)) continue;
                            if (st >= 0) buds.put(k, new Bud(dir.get3DDataValue(), t, (st + 1) * StasisConfig.crystalStageSeconds, st));
                            else if (n.isAir() && Noise.hash32(m.getX(), m.getY(), m.getZ(), dir.get3DDataValue() ^ 0xB0D) < FACE_SHARE)
                                buds.put(k, new Bud(dir.get3DDataValue(), t, 0, -1));
                        }
                    }
        }
    }

    // ---- the charged-shard loot condition

    /** True when the block being broken sits in a violet strata pocket in stopped land (Baughn's tier 2). */
    public record PocketViolet() implements LootItemCondition {
        public static final MapCodec<PocketViolet> CODEC = MapCodec.unit(new PocketViolet());

        @Override
        public MapCodec<PocketViolet> codec() {
            return CODEC;
        }

        @Override
        public boolean test(LootContext ctx) {
            var o = ctx.getOptional(LootContextParams.ORIGIN);
            if (o == null) return false;
            BlockPos pos = BlockPos.containing(o);
            ServerLevel level = ctx.getLevel();
            if (Stasis.isRunning(level, pos)) return false;
            Pockets.Weather w = Pockets.at(level, pos.getX(), pos.getY(), pos.getZ());
            return w != null && w.state() == Pockets.State.VIOLET;
        }
    }
}
