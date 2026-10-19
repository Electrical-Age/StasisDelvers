package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flames (Baughn, 2026-10-14): torches, lanterns, campfires, candles and jack o'lanterns (data/stasisdelvers/tags/block/
 * flames) are unreliable, to make EA lighting worth it.
 * <ul>
 * <li><b>The Grey:</b> in stopped land a flame gives no light (the fire doesn't move either). EA lamps still do.</li>
 * <li><b>Guttering:</b> where time runs, a random tick sometimes dims a flame to 4-7 for 5-30 s, each on its own
 * timer, so a few torches on a spawner rarely dip together; and not while two or more flames right next to it gutter. With the overworld's monster_spawn_block_light_limit at 7
 * (data/minecraft/dimension_type/overworld.json) a dip can let a mob spawn.</li>
 * </ul>
 * The light is position-dependent through NeoForge's getLightEmission(state, level, pos) (BlockMixin), so vanilla
 * blocks keep their states. Both sides compute it: the client knows the fields and gets the gutters (StasisNet.Gutters),
 * so its own relighting agrees with the server's. The server relights flames when fields change and when chunks load.
 */
public final class Flames {
    private Flames() {
    }

    public static final TagKey<Block> TAG = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "flames"));

    /** Server gutters per dimension (pos -> dimmed light); read from the light threads. */
    private static final Map<Level, Map<Long, Byte>> SERVER = new WeakHashMap<>();
    /** The client's gutters for the level it is in. */
    static final Map<Long, Byte> CLIENT = new ConcurrentHashMap<>();

    public static boolean isFlame(BlockState state) {
        return state.is(TAG);
    }

    /**
     * The client's chunk meshing reads a flame's own light through a region copy (smooth lighting samples it, so a
     * dark flame must say 0 there too, or the floor around it glows); StasisClient sets this to find its level.
     */
    public static volatile java.util.function.Function<BlockGetter, Level> clientResolver = g -> null;

    /** The light a flame gives at pos (BlockMixin); base = the state's own emission. */
    public static int emission(BlockState state, BlockGetter getter, BlockPos pos) {
        int base = state.getLightEmission();
        if (base == 0) return base;
        Level level = getter instanceof Level l ? l : getter instanceof LevelChunk c ? c.getLevel() : clientResolver.apply(getter);
        if (level == null) return base;
        if (StaticDeeps.inRift(level, pos.getX(), pos.getY(), pos.getZ())) return 0;   // no light in a rift, of any kind
        if (!state.is(TAG)) return base;
        if (!Stasis.isRunningLand(level, pos)) return 0;   // eddies (Baughn) and passing deeps don't relight flames
        Byte dim = dims(level).get(pos.asLong());
        return dim == null ? base : Math.min(base, dim);
    }

    private static Map<Long, Byte> dims(Level level) {
        if (level.isClientSide()) return CLIENT;
        synchronized (SERVER) {
            return SERVER.computeIfAbsent(level, l -> new ConcurrentHashMap<>());
        }
    }

    // ---------------------------------------------------------------- guttering (server)

    /** A random tick on a flame (in running land: stopped land has none). */
    public static void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        tryGutter(state, level, pos, random, StasisConfig.gutterChance);
    }

    /** Gutter with this chance (public for tests). */
    public static void tryGutter(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, double chance) {
        // fire random-ticks anyway (spreading): it goes dark in the Grey but doesn't gutter
        if (!state.is(TAG) || state.is(net.minecraft.tags.BlockTags.FIRE) || random.nextDouble() >= chance) return;
        Data d = Data.get(level);
        long k = pos.asLong();
        if (d.until.containsKey(k)) return;
        // Baughn: not if two or more flames right next to it (diagonals too) are guttering already
        int near = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if ((dx | dy | dz) != 0 && d.until.containsKey(BlockPos.offset(k, dx, dy, dz)) && ++near >= 2) return;
        int light = 4 + random.nextInt(4);
        int min = StasisConfig.gutterMinTicks, max = Math.max(min, StasisConfig.gutterMaxTicks);
        long until = level.getGameTime() + min + random.nextInt(max - min + 1);
        set(level, d, pos, (byte) light, until);
        level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 6, 0.1, 0.1, 0.1, 0.01);
        level.playSound(null, pos, SoundEvents.CANDLE_EXTINGUISH, SoundSource.BLOCKS, 0.3F, 0.6F + random.nextFloat() * 0.3F);
    }

    public static boolean guttering(Level level, BlockPos pos) {
        return dims(level).containsKey(pos.asLong());
    }

    /** Gutter pos now (tests, commands). */
    public static void gutter(ServerLevel level, BlockPos pos, int light, int ticks) {
        set(level, Data.get(level), pos, (byte) light, level.getGameTime() + ticks);
    }

    private static void set(ServerLevel level, Data d, BlockPos pos, byte light, long until) {
        long k = pos.asLong();
        d.until.put(k, until);
        d.light.put(k, light);
        d.setDirty();
        dims(level).put(k, light);
        level.getChunkSource().getLightEngine().checkBlock(pos);
        send(level, ChunkPos.containing(pos), Map.of(k, light));
    }

    private static void clear(ServerLevel level, Data d, long k) {
        d.until.remove(k);
        d.light.remove(k);
        d.setDirty();
        dims(level).remove(k);
        BlockPos pos = BlockPos.of(k);
        level.getChunkSource().getLightEngine().checkBlock(pos);
        send(level, ChunkPos.containing(pos), Map.of(k, (byte) 0));
    }

    private static final Map<ServerLevel, List<ChunkPos>> PENDING_LOADS = new WeakHashMap<>();

    static void tick(ServerTickEvent.Post e) {
        for (ServerLevel level : e.getServer().getAllLevels()) {
            List<ChunkPos> loads;
            synchronized (PENDING_LOADS) {
                loads = PENDING_LOADS.remove(level);
            }
            Data d = level.getDataStorage().get(Data.TYPE);
            if (loads != null && Stasis.stops(level)) {
                if (d != null && !d.light.isEmpty()) {   // saved gutters of the loaded chunks
                    java.util.Set<ChunkPos> set = new java.util.HashSet<>(loads);
                    Map<Long, Byte> m = dims(level);
                    d.light.forEach((k, v) -> {
                        if (set.contains(ChunkPos.containing(BlockPos.of(k)))) m.put(k, v);
                    });
                }
                for (ChunkPos cp : loads) relightChunk(level, cp.x(), cp.z(), null);
            }
            if (e.getServer().getTickCount() % 10 != 0) continue;
            if (d == null || d.until.isEmpty()) continue;
            long now = level.getGameTime();
            for (long k : new ArrayList<>(d.until.keySet())) {
                BlockPos pos = BlockPos.of(k);
                if (!level.isLoaded(pos)) continue;   // recovers when its chunk is back
                if (d.until.get(k) <= now || !level.getBlockState(pos).is(TAG)) clear(level, d, k);
            }
        }
    }

    /** A chunk loaded: its saved light may predate a field change or this feature; relight its flames next tick. */
    static void chunkLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !Stasis.stops(level)) return;
        synchronized (PENDING_LOADS) {
            PENDING_LOADS.computeIfAbsent(level, l -> new ArrayList<>()).add(e.getChunk().getPos());
        }
    }

    /** A player starts watching a chunk: send its gutters. */
    static void watch(ChunkWatchEvent.Watch e) {
        Data d = e.getLevel().getDataStorage().get(Data.TYPE);
        if (d == null || d.light.isEmpty()) return;
        Map<Long, Byte> m = new HashMap<>();
        d.light.forEach((k, v) -> {
            if (ChunkPos.containing(BlockPos.of(k)).equals(e.getPos())) m.put(k, v);
        });
        if (!m.isEmpty()) StasisNet.sendGutters(e.getPlayer(), m);
    }

    private static void send(ServerLevel level, ChunkPos cp, Map<Long, Byte> m) {
        for (ServerPlayer p : level.getChunkSource().chunkMap.getPlayers(cp, false)) StasisNet.sendGutters(p, m);
    }

    private static java.lang.ref.WeakReference<Level> clientLevel = new java.lang.ref.WeakReference<>(null);

    /** Client: the player is in this level (ClientFields.set); a new level drops the old one's gutters (true). */
    static boolean clientLevel(Level level) {
        if (level != null && clientLevel.get() != level) {
            CLIENT.clear();
            RECHECK.clear();
            clientLevel = new java.lang.ref.WeakReference<>(level);
            return true;
        }
        return false;
    }

    /** Client: gutters from the server (0 = recovered); the client relights them itself. */
    static void clientGutters(Map<Long, Byte> m, Level level) {
        // a new level must be noticed here too: at login the gutters can come before the fields, and the fields'
        // "new level" would then drop them (AdventurAgent: after a relog most dim flames were full again)
        clientLevel(level);
        m.forEach((k, v) -> {
            if (v == 0) CLIENT.remove(k);
            else CLIENT.put(k, v);
            if (level != null) {
                level.getChunkSource().getLightEngine().checkBlock(BlockPos.of(k));
                RECHECK.put(k, level.getGameTime() + 5);   // again once the chunk's light is surely in
            }
        });
    }

    /**
     * Client: flames to check again at a game time. At login the gutters can arrive before their chunk, or the chunk's
     * light data can land after the check (AdventurAgent: a dim torch kept full light until its next gutter).
     */
    private static final Map<Long, Long> RECHECK = new ConcurrentHashMap<>();

    /** Client: a chunk arrived; check its gutters a few ticks later. */
    public static void clientChunkLoad(Level level, ChunkPos cp) {
        long due = level.getGameTime() + 5;
        for (long k : CLIENT.keySet())
            if (ChunkPos.containing(BlockPos.of(k)).equals(cp)) RECHECK.put(k, due);
    }

    /** Client tick. */
    public static void clientTick(Level level) {
        if (level == null || RECHECK.isEmpty()) return;
        long now = level.getGameTime();
        var it = RECHECK.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue() > now) continue;
            BlockPos pos = BlockPos.of(e.getKey());
            if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null)
                level.getChunkSource().getLightEngine().checkBlock(pos);
            it.remove();
        }
    }

    // ---------------------------------------------------------------- relighting on field changes (server)

    /**
     * The fields of a level changed from old to now (FieldData on the server, ClientFields on the client): relight
     * the flames where running land changed shape. Both sides: the client's light engine only relights on its own
     * block changes.
     */
    static void fieldsChanged(Level level, List<Field> old, List<Field> now) {
        if (!Stasis.stops(level)) return;
        for (Field f : Field.changedShapes(old, now)) relight(level, f);
    }

    private static void relight(Level level, Field f) {
        int x0 = (int) Math.floor(f.minX()) >> 4, x1 = (int) Math.floor(f.maxX()) >> 4;
        int z0 = (int) Math.floor(f.minZ()) >> 4, z1 = (int) Math.floor(f.maxZ()) >> 4;
        int[] ys = f.column() ? null : new int[]{(int) Math.floor(f.y() - f.radius()), (int) Math.floor(f.y() + f.radius())};
        for (int cx = x0; cx <= x1; cx++)
            for (int cz = z0; cz <= z1; cz++) relightChunk(level, cx, cz, ys);
    }

    /** checkBlock every flame in a loaded chunk (within the y range {min, max} if given). */
    public static int relightChunk(Level level, int cx, int cz, int[] ys) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return 0;
        LevelChunkSection[] sections = chunk.getSections();
        var light = level.getChunkSource().getLightEngine();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int n = 0;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir() || !s.maybeHas(st -> st.is(TAG))) continue;
            int by = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            if (ys != null && (by + 15 < ys[0] || by > ys[1])) continue;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (s.getBlockState(x, y, z).is(TAG)) {
                            light.checkBlock(pos.set((cx << 4) + x, by + y, (cz << 4) + z));
                            n++;
                        }
        }
        return n;
    }

    // ---------------------------------------------------------------- saved gutters

    /** The gutters of one level, saved with it (a dim torch must recover after a restart, or its light sticks). */
    public static final class Data extends SavedData {
        record Entry(long pos, byte light, long until) {
            static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
                Codec.BYTE.fieldOf("light").forGetter(Entry::light),
                Codec.LONG.fieldOf("until").forGetter(Entry::until)
            ).apply(i, Entry::new));
        }

        static final Codec<Data> CODEC = Entry.CODEC.listOf().xmap(Data::new, d -> {
            List<Entry> l = new ArrayList<>();
            d.until.forEach((k, u) -> l.add(new Entry(k, d.light.getOrDefault(k, (byte) 7), u)));
            return l;
        });

        static final SavedDataType<Data> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "gutters"), Data::new, CODEC);

        final Map<Long, Long> until = new HashMap<>();
        final Map<Long, Byte> light = new HashMap<>();

        public Data() {
        }

        private Data(List<Entry> entries) {
            for (Entry e : entries) {
                until.put(e.pos(), e.until());
                light.put(e.pos(), e.light());
            }
        }

        static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }
    }
}
