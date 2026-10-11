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
import net.minecraft.world.level.storage.LevelData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The rule: where does time run? Everywhere in dimensions not in the config's stopped list; in stopped dimensions
 * only in the spawn area and inside fields (whole blocks). Lookups go through a per-level index of which fields touch
 * which chunk column, rebuilt when the fields change.
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

    private static final Map<ServerLevel, Index> INDEX = new WeakHashMap<>();

    static synchronized void invalidate(ServerLevel level) {
        INDEX.remove(level);
    }

    private static synchronized Index index(ServerLevel level) {
        Index idx = INDEX.get(level);
        if (idx == null) {
            idx = new Index();
            Long2ObjectOpenHashMap<List<Field>> lists = new Long2ObjectOpenHashMap<>();
            for (Field f : FieldData.get(level).fields()) {
                int x0 = (int) Math.floor(f.x() - f.radius()) >> 4, x1 = (int) Math.floor(f.x() + f.radius()) >> 4;
                int z0 = (int) Math.floor(f.z() - f.radius()) >> 4, z1 = (int) Math.floor(f.z() + f.radius()) >> 4;
                for (int cx = x0; cx <= x1; cx++)
                    for (int cz = z0; cz <= z1; cz++) lists.computeIfAbsent(ChunkPos.pack(cx, cz), k -> new ArrayList<>()).add(f);
            }
            for (var e : lists.long2ObjectEntrySet()) idx.columns.put(e.getLongKey(), e.getValue().toArray(NONE));
            INDEX.put(level, idx);
        }
        return idx;
    }

    /** Is time stopped anywhere in this level (enabled, and the dimension is a stopped one)? */
    public static boolean stops(ServerLevel level) {
        return StasisConfig.enabled && StasisConfig.stopped.contains(level.dimension());
    }

    private static boolean inSpawn(ServerLevel level, int cx, int cz) {
        int r = StasisConfig.spawnRadius;
        if (r < 0) return false;
        LevelData.RespawnData spawn = level.getRespawnData();
        if (spawn.dimension() != level.dimension()) return false;
        BlockPos p = spawn.pos();
        return Math.abs(cx - (p.getX() >> 4)) <= r && Math.abs(cz - (p.getZ() >> 4)) <= r;
    }

    /** Does time run in the whole block at (x, y, z)? */
    public static boolean isRunning(ServerLevel level, int x, int y, int z) {
        if (!stops(level)) return true;
        int cx = x >> 4, cz = z >> 4;
        if (inSpawn(level, cx, cz)) return true;
        Field[] fs = index(level).columns.get(ChunkPos.pack(cx, cz));
        if (fs == null) return false;
        for (Field f : fs) if (f.containsBlock(x, y, z)) return true;
        return false;
    }

    public static boolean isRunning(ServerLevel level, BlockPos pos) {
        return isRunning(level, pos.getX(), pos.getY(), pos.getZ());
    }

    /** Could time run anywhere in this chunk column? False means the whole column is stopped. */
    public static boolean mayRunInColumn(ServerLevel level, long chunkKey) {
        if (!stops(level)) return true;
        int cx = ChunkPos.getX(chunkKey), cz = ChunkPos.getZ(chunkKey);
        return inSpawn(level, cx, cz) || index(level).columns.containsKey(chunkKey);
    }

    /** Is this entity frozen? Players never are here (they freeze into statues: T2); unbound types never are. */
    public static boolean isFrozen(Entity e) {
        if (!(e.level() instanceof ServerLevel level) || e instanceof net.minecraft.world.entity.player.Player) return false;
        if (!stops(level) || e.typeHolder().is(UNBOUND)) return false;
        return !isRunning(level, e.blockPosition());
    }
}
