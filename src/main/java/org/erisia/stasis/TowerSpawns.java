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
 * currently unplayable"). Each running tower reports its field here every tick (memory only: towers that run keep their
 * chunks loaded and report again after a restart); StasisEvents refuses natural, patrol, event, reinforcement and jockey
 * spawns at a block inside one. Spawners, eggs, breeding, commands and worldgen stay allowed.
 */
public final class TowerSpawns {
    private TowerSpawns() {
    }

    static final Map<ResourceKey<Level>, Set<Integer>> FIELDS = new ConcurrentHashMap<>();

    /** A tower's field id, blocking spawns or not (tick). */
    static void report(ServerLevel level, int fieldId, boolean blocking) {
        if (fieldId == 0) return;
        Set<Integer> s = FIELDS.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet());
        if (blocking) s.add(fieldId);
        else s.remove(fieldId);
    }

    /** Is the block inside a tower's field that blocks spawns? Public for tests. */
    public static boolean blocks(ServerLevel level, BlockPos pos) {
        Set<Integer> s = FIELDS.get(level.dimension());
        if (s == null || s.isEmpty()) return false;
        FieldData fd = FieldData.get(level);
        for (int id : s) {
            var f = fd.get(id);
            if (f.isEmpty()) {
                s.remove(id);
                continue;
            }
            if (f.get().containsBlock(pos.getX(), pos.getY(), pos.getZ())) return true;
        }
        return false;
    }
}
