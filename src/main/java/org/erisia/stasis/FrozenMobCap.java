package org.erisia.stasis;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Frozen mobs take at most half the mob cap (Baughn 2026-10-25: in a frozen world they filled it, and nothing new
 * spawned where time runs). Every 10 s, per level where time stops and per category that despawns (monsters and the
 * like: those vanilla counts), the frozen ones over half the global cap go, the farthest from any player first; none
 * within PLAYER_REACH of a player, and nothing named or kept (persistence: vanilla doesn't count those either).
 */
public final class FrozenMobCap {
    private FrozenMobCap() {
    }

    static final int EVERY = 200;
    /** Blocks: a frozen mob this close to a player stays, whatever the count. */
    static final double PLAYER_REACH = 64;
    /** NaturalSpawner.MAGIC_NUMBER: the global cap is perChunk x spawnable chunks / 17^2. */
    static final int MAGIC = 17 * 17;

    static void tick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % EVERY != 0) return;
        for (ServerLevel level : e.getServer().getAllLevels()) {
            if (!Stasis.stops(level)) continue;
            var state = level.getChunkSource().getLastSpawnState();
            if (state == null) continue;
            for (MobCategory c : MobCategory.values()) {
                if (c.isPersistent() || c == MobCategory.MISC) continue;
                int cap = c.getMaxInstancesPerChunk() * state.getSpawnableChunkCount() / MAGIC;
                trim(level, c, cap / 2);
            }
        }
    }

    /** Does this mob count, frozen, against the cap here (as NaturalSpawner counts: not kept, not named)? */
    public static boolean counts(Mob m) {
        return !m.isPersistenceRequired() && !m.requiresCustomPersistence() && !m.hasCustomName() && !m.isPassenger() && !m.isVehicle()
            && !(m instanceof RiftFauna) && !Possession.possessed(m)   // (rift fauna and possessed hosts have their own lives)
            && Stasis.isFrozen(m);
    }

    static List<Mob> frozen(ServerLevel level, MobCategory c) {
        List<Mob> out = new ArrayList<>();
        for (var en : level.getAllEntities())
            if (en instanceof Mob m && m.getType().getCategory() == c && counts(m)) out.add(m);
        return out;
    }

    static int trim(ServerLevel level, MobCategory c, int keep) {
        return trim(level, frozen(level, c), keep);
    }

    /** Keep at most `keep` of these frozen mobs: the others go, farthest from players first, none near one. Returns how
     *  many went (public for tests). */
    public static int trim(ServerLevel level, List<Mob> fs, int keep) {
        return trim(fs, keep, level.players());
    }

    /** trim against these players (tests: their own, not the other tests'). */
    public static int trim(List<Mob> fs, int keep, List<? extends Player> players) {
        if (fs.size() <= keep) return 0;
        java.util.Map<Mob, Double> near = new java.util.HashMap<>();
        for (Mob m : fs) {
            double best = Double.MAX_VALUE;
            for (Player p : players) best = Math.min(best, p.distanceToSqr(m));
            near.put(m, best);
        }
        List<Mob> order = new ArrayList<>(fs);
        order.sort(Comparator.comparingDouble((Mob m) -> near.get(m)).reversed());
        int gone = 0, over = order.size() - keep;
        for (Mob m : order) {
            if (gone >= over || near.get(m) < PLAYER_REACH * PLAYER_REACH) break;
            m.discard();
            gone++;
        }
        return gone;
    }
}
