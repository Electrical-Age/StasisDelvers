package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * No mob spawns inside a tower's field (Baughn 2026-11-08: "Tower fields should block mob spawns entirely ... It's
 * currently unplayable"), unless a wild resonator sits against the tower's core (WildResonatorBlock): then the field
 * spawns by vanilla's rules, by its own clock (FieldDaylight; Baughn: #300 "still applies to anything with the new
 * mob-spawner block"). Each running tower reports its field here every tick (memory only: towers that run keep their
 * chunks loaded and report again after a restart); StasisEvents refuses natural, patrol, event, reinforcement and jockey
 * spawns at a block inside a blocking one. Spawners, eggs, breeding, commands and worldgen stay allowed.
 */
public final class TowerSpawns {
    private TowerSpawns() {
    }

    /** What a tower's field does about spawns. */
    public enum Mode { NONE, BLOCK, WILD }

    static final Map<ResourceKey<Level>, Set<Integer>> FIELDS = new ConcurrentHashMap<>();
    /** Fields of running towers with a wild resonator: spawning (and daylight) by the field's clock. */
    static final Map<ResourceKey<Level>, Set<Integer>> WILD = new ConcurrentHashMap<>();

    /** A tower's field id and what it does about spawns (tick). */
    static void report(ServerLevel level, int fieldId, Mode mode) {
        if (fieldId == 0) return;
        Set<Integer> b = FIELDS.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet());
        Set<Integer> w = WILD.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet());
        if (mode == Mode.BLOCK) b.add(fieldId);
        else b.remove(fieldId);
        if (mode == Mode.WILD) w.add(fieldId);
        else w.remove(fieldId);
    }

    /** Is the block inside a tower's field that blocks spawns? Public for tests. */
    public static boolean blocks(ServerLevel level, BlockPos pos) {
        return inside(level, FIELDS.get(level.dimension()), pos) != null;
    }

    /** The wild field (a running tower's, with a resonator) holding the block, or null. Main thread only. */
    public static Field wildAt(ServerLevel level, BlockPos pos) {
        return inside(level, WILD.get(level.dimension()), pos);
    }

    /** Is there any wild field in the level (the cheap test before wildAt)? */
    static boolean anyWild(Level level) {
        Set<Integer> s = WILD.get(level.dimension());
        return s != null && !s.isEmpty();
    }

    private static Field inside(ServerLevel level, Set<Integer> s, BlockPos pos) {
        if (s == null || s.isEmpty()) return null;
        FieldData fd = FieldData.get(level);
        for (int id : s) {
            var f = fd.get(id);
            if (f.isEmpty()) {
                s.remove(id);
                continue;
            }
            if (f.get().containsBlock(pos.getX(), pos.getY(), pos.getZ())) return f.get();
        }
        return null;
    }
}
