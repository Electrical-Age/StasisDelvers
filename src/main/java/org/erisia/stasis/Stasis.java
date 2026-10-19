package org.erisia.stasis;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The rule: how fast does time run where? Full speed everywhere in dimensions not in the config's stopped list; in
 * stopped dimensions full speed in the spawn area, the field's rate inside fields (whole blocks; the fastest of
 * overlapping fields) and not at all elsewhere ({@link #rateAt}; stopped = rate 0). Lookups go through a per-level index of which fields touch
 * which chunk column, rebuilt when the fields change. Works on both sides: the server reads FieldData, the client the
 * fields the server sent it (ClientFields), so collisions with stopped water agree on both. In spreading mode (Spread)
 * the overworld's columns the stillness hasn't reached yet run at rate 1 (rifts excepted) before any of that.
 */
public final class Stasis {
    private Stasis() {
    }

    /** Entity types time doesn't stop (endermen, the dragon, the warden): data/stasisdelvers/tags/entity_type/unbound. */
    public static final TagKey<EntityType<?>> UNBOUND =
        TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "unbound"));

    private static final Field[] NONE = new Field[0];

    private static final class Index {
        final Long2ObjectOpenHashMap<Field[]> columns = new Long2ObjectOpenHashMap<>();
    }

    private static final Map<Level, Index> INDEX = new WeakHashMap<>();

    /**
     * The client level's index, readable without the lock: chunk meshing asks from its worker threads for every block
     * with an animated texture (FrozenTextures), and they shouldn't queue up on index().
     */
    private record ClientIndex(Level level, Index index) {
    }

    private static volatile ClientIndex clientIndex;

    /** Counts invalidations: whatever caches an answer of rateAt (chunk meshing per section) compares this. */
    private static volatile int version;

    public static int version() {
        return version;
    }

    /** A change that index() doesn't hold (the spread's progress, Spread): readers of version() re-ask. */
    static synchronized void bumpVersion() {
        version++;
    }

    public static synchronized void invalidate(Level level) {
        INDEX.remove(level);
        ClientIndex c = clientIndex;
        if (c != null && c.level() == level) clientIndex = null;
        version++;
    }

    /** The fields of a level: saved data on the server, the last sync on the client. */
    public static List<Field> fields(Level level) {
        return level instanceof ServerLevel sl ? FieldData.get(sl).fields() : ClientFields.fields();
    }

    private static Index index(Level level) {
        ClientIndex c = clientIndex;
        if (c != null && c.level() == level) return c.index();
        return indexLocked(level);
    }

    private static synchronized Index indexLocked(Level level) {
        Index idx = INDEX.get(level);
        if (idx == null) {
            // off the main thread (the light engine) the server's saved data can't be loaded: use what's loaded
            List<Field> fields;
            if (level instanceof ServerLevel sl && !sl.getServer().isSameThread()) {
                fields = FieldData.peek(sl);
                if (fields == null) fields = List.of();   // not loaded yet: don't cache this
                else INDEX.put(level, idx = new Index());
                if (idx == null) idx = new Index();
            } else {
                fields = fields(level);
                INDEX.put(level, idx = new Index());
            }
            Long2ObjectOpenHashMap<List<Field>> lists = new Long2ObjectOpenHashMap<>();
            for (Field f : fields) {
                int x0 = (int) Math.floor(f.minX()) >> 4, x1 = (int) Math.floor(f.maxX()) >> 4;
                int z0 = (int) Math.floor(f.minZ()) >> 4, z1 = (int) Math.floor(f.maxZ()) >> 4;
                for (int cx = x0; cx <= x1; cx++)
                    for (int cz = z0; cz <= z1; cz++) lists.computeIfAbsent(ChunkPos.pack(cx, cz), k -> new ArrayList<>()).add(f);
            }
            for (var e : lists.long2ObjectEntrySet()) idx.columns.put(e.getLongKey(), e.getValue().toArray(NONE));
        }
        if (level.isClientSide()) clientIndex = new ClientIndex(level, idx);
        return idx;
    }

    /**
     * Is time stopped anywhere in this level (enabled, and the dimension is a stopped one)? In spreading mode the Nether
     * and the End run until the spread is complete (Spread.holdsOff; Baughn 2026-10-19).
     */
    public static boolean stops(Level level) {
        return StasisConfig.enabled && StasisConfig.stopped.contains(level.dimension()) && !Spread.holdsOff(level);
    }

    private static boolean inSpawn(Level level, int cx, int cz) {
        int r = StasisConfig.spawnRadius;
        if (r < 0) return false;
        LevelData.RespawnData spawn = level.getRespawnData();
        if (spawn.dimension() != level.dimension()) return false;
        BlockPos p = spawn.pos();
        return Math.abs(cx - (p.getX() >> 4)) <= r && Math.abs(cz - (p.getZ() >> 4)) <= r;
    }

    /** The spawn area's block bounds {minX, minZ, maxX, maxZ}, or null if this level has none. */
    public static int[] spawnArea(Level level) {
        int r = StasisConfig.spawnRadius;
        if (r < 0) return null;
        LevelData.RespawnData spawn = level.getRespawnData();
        if (spawn.dimension() != level.dimension()) return null;
        int cx = spawn.pos().getX() >> 4, cz = spawn.pos().getZ() >> 4;
        return new int[]{(cx - r) << 4, (cz - r) << 4, ((cx + r) << 4) + 15, ((cz + r) << 4) + 15};
    }

    /**
     * The tick rate at the whole block (x, y, z), as a share of the server's rate: 1 where time runs normally
     * (dimensions that don't stop, the spawn area), 0 in stopped land, and inside fields the rate of the fastest field
     * that holds the whole block (collapsed blocks don't count). This is the one rule: stopped means rate 0.
     */
    public static double rateAt(Level level, int x, int y, int z) {
        return rateAt(level, x, y, z, true, true, true);
    }

    /**
     * The rate without eddies (Eddies): for what must not churn as pockets drift past (EA nodes, the tower) and to tell
     * an eddy from stopped land (Freezing holds players in eddies). Drifting deeps over running spread land still stop
     * here (Baughn 2026-10-19: EA nodes in a passing deep stop like everything else).
     */
    public static double rateAtSteady(Level level, int x, int y, int z) {
        return rateAt(level, x, y, z, false, true, true);
    }

    /**
     * The rate of the land itself: no eddies and no drifting deeps over running spread land, so nothing passing by
     * changes it. For caches that nobody refreshes as weather drifts by (flame light in the light engine, chunk meshing,
     * map overlays), and safe off the main thread (it never reads a heightmap). In stopped mode it is rateAtSteady.
     */
    public static double rateAtLand(Level level, int x, int y, int z) {
        return rateAt(level, x, y, z, false, true, false);
    }

    /**
     * Does time run here for spawning? As isRunning, but a new chunk's grace doesn't count (λ-Nero: monsters and
     * animals spawned out in the grey: natural spawning ran during the few seconds a new chunk settles).
     */
    public static boolean spawnsHere(Level level, BlockPos pos) {
        return rateAt(level, pos.getX(), pos.getY(), pos.getZ(), true, false, true) > 0;
    }

    /**
     * Is column (x, z) land the spreading stillness hasn't reached yet (spreading mode, the overworld, before
     * completion)? There the rate is 1 whatever the fields, except in rifts and drifting deeps (rateAt).
     */
    public static boolean spreadRuns(Level level, int x, int z) {
        return stops(level) && Spread.active(level) && Spread.runs(level, x, z);
    }

    static double rateAt(Level level, int x, int y, int z, boolean eddies, boolean grace, boolean deeps) {
        if (!stops(level)) return 1.0;
        // spreading mode: a column the stillness hasn't reached runs at full rate, fields or not; rifts stop as ever, and
        // so does a drifting deep where no field (or the spawn area) holds it off (Baughn 2026-10-19). Stilled columns
        // go on to the base rules below. The client answers from its rank tiles (Spread.client).
        if (Spread.active(level) && Spread.runs(level, x, z)) {
            if (StaticDeeps.inRift(level, x, y, z)) return 0.0;
            return deeps && Deeps.stopsRunningLand(level, x, y, z) && !heldOff(level, x, y, z) ? 0.0 : 1.0;
        }
        int cx = x >> 4, cz = z >> 4;
        if (inSpawn(level, cx, cz) || grace && NewChunkGrace.active(level, ChunkPos.pack(cx, cz))) return 1.0;
        Field[] fs = index(level).columns.get(ChunkPos.pack(cx, cz));
        if (fs == null) return 0.0;
        double rate = 0.0;
        for (Field f : fs) {
            if (f.rate() > rate && f.containsBlock(x, y, z) && !(eddies && Eddies.stopped(level, f, x, y, z))) {
                rate = f.rate();
                if (rate >= 1.0) break;
            }
        }
        // no field holds a rift (Baughn): a stopped, dark hole through it
        if (rate > 0 && StaticDeeps.inRift(level, x, y, z)) return 0.0;
        return rate;
    }

    /**
     * Does a field or the spawn area hold the block against a drifting deep (as fields do in stopped land)? Also
     * FrontWarnings: a player standing where it holds keeps running when the column stills, so gets no warning.
     */
    static boolean heldOff(Level level, int x, int y, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (inSpawn(level, cx, cz)) return true;
        Field[] fs = index(level).columns.get(ChunkPos.pack(cx, cz));
        if (fs == null) return false;
        for (Field f : fs) if (f.containsBlock(x, y, z)) return true;
        return false;
    }

    /** sectionState: every block of the section is stopped (rateAtLand 0), every one runs, or it varies. */
    public static final int SECTION_STOPPED = 0, SECTION_RUNNING = 1, SECTION_MIXED = 2;

    /**
     * Does time run (rateAtLand above 0) in all, none or some of the 16-block section (sx, sy, sz)? A cheap
     * conservative test (MIXED when unsure) so chunk meshing only asks per block where a field's edge, a collapse or a
     * rift may cross the section. The land's answer: drifting deeps over running spread land don't count (they pass).
     */
    public static int sectionState(Level level, int sx, int sy, int sz) {
        if (!stops(level)) return SECTION_RUNNING;
        // spreading mode: every column running -> RUNNING unless a rift may cut it; some running -> at least MIXED
        int spread = Spread.active(level) ? Spread.chunk(level, sx, sz) : 0;
        if (spread == 1) {
            int seed = Deeps.seed(level);
            return seed == 0 || !StaticDeeps.sectionMayHave(seed, sx, sy, sz) ? SECTION_RUNNING : SECTION_MIXED;
        }
        int base = baseSectionState(level, sx, sy, sz);
        return spread == 2 && base == SECTION_STOPPED ? SECTION_MIXED : base;
    }

    /** sectionState by the base rules (fields, spawn area, grace, rifts). */
    private static int baseSectionState(Level level, int sx, int sy, int sz) {
        if (inSpawn(level, sx, sz) || NewChunkGrace.active(level, ChunkPos.pack(sx, sz))) return SECTION_RUNNING;
        Field[] fs = index(level).columns.get(ChunkPos.pack(sx, sz));
        if (fs == null) return SECTION_STOPPED;
        double x0 = sx << 4, y0 = sy << 4, z0 = sz << 4, x1 = x0 + 16, y1 = y0 + 16, z1 = z0 + 16;
        boolean touched = false;
        for (Field f : fs) {
            // nearest and farthest point of the section's box, in the field's unit-sphere space (Field.norm: the
            // stretch is monotone per axis, so the box's ends map to the ends); a column field ignores y
            double[] ex = f.normSpan(0, x0, x1), ey = f.normSpan(1, y0, y1), ez = f.normSpan(2, z0, z1);
            if (ex[0] * ex[0] + ey[0] * ey[0] + ez[0] * ez[0] > 1) continue;   // no block of the section can be whole inside
            touched = true;
            // every block's farthest corner is inside: the whole section runs, unless a collapse or a rift cuts it
            if (ex[1] * ex[1] + ey[1] * ey[1] + ez[1] * ez[1] <= 1 && f.collapse() <= 0) {
                int seed = Deeps.seed(level);
                if (seed == 0 || !StaticDeeps.sectionMayHave(seed, sx, sy, sz)) return SECTION_RUNNING;
            }
        }
        return touched ? SECTION_MIXED : SECTION_STOPPED;
    }

    public static boolean isRunningSteady(Level level, BlockPos pos) {
        return rateAtSteady(level, pos.getX(), pos.getY(), pos.getZ()) > 0.0;
    }

    public static boolean isRunningLand(Level level, BlockPos pos) {
        return rateAtLand(level, pos.getX(), pos.getY(), pos.getZ()) > 0.0;
    }

    public static double rateAt(Level level, BlockPos pos) {
        return rateAt(level, pos.getX(), pos.getY(), pos.getZ());
    }

    /** Does time run in the whole block at (x, y, z) at all (rate above 0)? */
    public static boolean isRunning(Level level, int x, int y, int z) {
        return rateAt(level, x, y, z) > 0.0;
    }

    public static boolean isRunning(Level level, BlockPos pos) {
        return isRunning(level, pos.getX(), pos.getY(), pos.getZ());
    }

    /** Does the block at pos get this game tick (its rate says it runs now)? */
    public static boolean ticksNow(Level level, BlockPos pos) {
        return runs(rateAt(level, pos), level.getGameTime());
    }

    /**
     * Rates are counted in thousandths so the gate is exact integer arithmetic (a double floor(t * rate) misrounds
     * once game time gets large). Game tick t runs at rate r iff floor(t r) steps up at t: at 0.5 every other tick,
     * at 0.6 three ticks in five. Stateless and the same for every block of that rate: overlapping fields, blocks
     * and scheduled ticks all agree, and a restart doesn't shift anything.
     */
    static long milli(double rate) {
        return Math.max(0, Math.min(1000, Math.round(rate * 1000)));
    }

    public static boolean runs(double rate, long gameTime) {
        long m = milli(rate);
        if (m >= 1000) return true;
        if (m <= 0) return false;
        return Math.floorDiv(gameTime * m, 1000) > Math.floorDiv((gameTime - 1) * m, 1000);
    }

    /**
     * The game tick a scheduled tick with this delay falls due at: `delay` running ticks after now, so it always lands
     * on a tick that runs. Rate 1 (or 0, or no delay): now + delay as vanilla (stopped blocks retry each tick through
     * the tickBlock gate; LevelTicks keeps whole stopped columns waiting).
     */
    public static long dueTick(double rate, long now, int delay) {
        long m = milli(rate);
        if (m >= 1000 || m <= 0 || delay <= 0) return now + delay;
        long k = Math.floorDiv(now * m, 1000) + delay;
        return Math.ceilDiv(k * 1000, m);
    }

    /** dueTick for a block of this level (ServerLevelMixin.createTick). */
    public static long dueTick(Level level, BlockPos pos, int delay) {
        long now = level.getGameTime();
        if (delay <= 0 || !stops(level)) return now + delay;
        return dueTick(rateAt(level, pos), now, delay);
    }

    /** Could time run anywhere in this chunk column? False means the whole column is stopped. */
    public static boolean mayRunInColumn(Level level, long chunkKey) {
        if (!stops(level)) return true;
        int cx = ChunkPos.getX(chunkKey), cz = ChunkPos.getZ(chunkKey);
        if (Spread.active(level) && Spread.chunk(level, cx, cz) != 0) return true;
        return inSpawn(level, cx, cz) || NewChunkGrace.active(level, chunkKey) || index(level).columns.containsKey(chunkKey);
    }

    /**
     * Block entities that tick even in stopped land: tower bases (or no tower could ever start), End gateways and EA's
     * light blocks (both time out in their tick; see below). EA's energy exporters
     * near a tower used to be here too, as their FE push into the tower ran in their block-entity tick; it runs in EA's
     * simulation now, which NodeFreezer keeps going near towers (NodeFreezer.infrastructure).
     */
    public static boolean exemptBlockEntity(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        if (be instanceof TowerBlockEntity t && t.isMaster()) return true;
        // an End gateway's cooldown counts down in its tick: frozen, a gateway would work once (softlock audit 3)
        if (be instanceof net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity) return true;
        // EA's light blocks (lamps, flashlights) time out in their tick: frozen, a flashlight sweep would leave its
        // light behind for good. Their sources refresh them only while they run, so stopped lamps go dark.
        return be instanceof mods.eln.sixnode.lampsocket.LightBlockEntity;
    }

    /** How far from a tower core (its 2 blocks) EA infrastructure keeps running in stopped land (Baughn: 6). */
    public static final int TOWER_YARD = 6;

    /**
     * Is pos within TOWER_YARD blocks (per axis) of a formed tower core (its 2 blocks)? Only loaded chunks are looked at (an unloaded
     * one counts as having no tower). Replaces the old "in the tower's chunk", which a 3x3x2 core next to a chunk line
     * made a lottery.
     */
    public static boolean nearTower(ServerLevel level, BlockPos pos) {
        int r = TOWER_YARD + 1;
        for (int cx = (pos.getX() - r) >> 4; cx <= (pos.getX() + r) >> 4; cx++)
            for (int cz = (pos.getZ() - r) >> 4; cz <= (pos.getZ() + r) >> 4; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (var be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof TowerBlockEntity t) || !t.isMaster()) continue;
                    BlockPos m = be.getBlockPos();
                    int dx = Math.abs(pos.getX() - m.getX());   // the core is 1x1x2
                    int dz = Math.abs(pos.getZ() - m.getZ());
                    int dy = pos.getY() < m.getY() ? m.getY() - pos.getY() : Math.max(pos.getY() - (m.getY() + TowerBlock.HEIGHT - 1), 0);
                    if (Math.max(dx, Math.max(dy, dz)) <= TOWER_YARD) return true;
                }
            }
        return false;
    }

    /** Is this entity frozen? Players never are here (they freeze into statues: T2); unbound types never are. */
    public static boolean isFrozen(Entity e) {
        if (!(e.level() instanceof ServerLevel level) || e instanceof net.minecraft.world.entity.player.Player) return false;
        if (!stops(level) || e.typeHolder().is(UNBOUND)) return false;
        return !isRunning(level, e.blockPosition());
    }
}
