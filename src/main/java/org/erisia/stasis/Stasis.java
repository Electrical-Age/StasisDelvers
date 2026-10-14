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
 * fields the server sent it (ClientFields), so collisions with stopped water agree on both.
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

    public static synchronized void invalidate(Level level) {
        INDEX.remove(level);
    }

    /** The fields of a level: saved data on the server, the last sync on the client. */
    public static List<Field> fields(Level level) {
        return level instanceof ServerLevel sl ? FieldData.get(sl).fields() : ClientFields.fields();
    }

    private static synchronized Index index(Level level) {
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
                int x0 = (int) Math.floor(f.x() - f.radius()) >> 4, x1 = (int) Math.floor(f.x() + f.radius()) >> 4;
                int z0 = (int) Math.floor(f.z() - f.radius()) >> 4, z1 = (int) Math.floor(f.z() + f.radius()) >> 4;
                for (int cx = x0; cx <= x1; cx++)
                    for (int cz = z0; cz <= z1; cz++) lists.computeIfAbsent(ChunkPos.pack(cx, cz), k -> new ArrayList<>()).add(f);
            }
            for (var e : lists.long2ObjectEntrySet()) idx.columns.put(e.getLongKey(), e.getValue().toArray(NONE));
        }
        return idx;
    }

    /** Is time stopped anywhere in this level (enabled, and the dimension is a stopped one)? */
    public static boolean stops(Level level) {
        return StasisConfig.enabled && StasisConfig.stopped.contains(level.dimension());
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
        if (!stops(level)) return 1.0;
        int cx = x >> 4, cz = z >> 4;
        if (inSpawn(level, cx, cz)) return 1.0;
        Field[] fs = index(level).columns.get(ChunkPos.pack(cx, cz));
        if (fs == null) return 0.0;
        double rate = 0.0;
        for (Field f : fs) {
            if (f.rate() > rate && f.containsBlock(x, y, z)) {
                rate = f.rate();
                if (rate >= 1.0) break;
            }
        }
        return rate;
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
        return inSpawn(level, cx, cz) || index(level).columns.containsKey(chunkKey);
    }

    /**
     * Block entities that tick even in stopped land: tower bases (or no tower could ever start), and EA's energy
     * exporters near a tower (they feed it).
     */
    public static boolean exemptBlockEntity(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        if (be instanceof TowerBlockEntity t && t.isMaster()) return true;
        // an End gateway's cooldown counts down in its tick: frozen, a gateway would work once (softlock audit 3)
        if (be instanceof net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity) return true;
        // EA's light blocks (lamps, flashlights) time out in their tick: frozen, a flashlight sweep would leave its
        // light behind for good. Their sources refresh them only while they run, so stopped lamps go dark.
        if (be instanceof mods.eln.sixnode.lampsocket.LightBlockEntity) return true;
        return be instanceof mods.eln.simplenode.energyconverter.EnergyConverterElnToOtherEntity && nearTower(level, pos);
    }

    /** How far from a tower core (its 3x3x2 blocks) EA infrastructure keeps running in stopped land (Baughn: 6). */
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
