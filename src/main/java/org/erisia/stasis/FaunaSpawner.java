package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rift fauna spawning (notes/fauna/PLAN.md, F1). Vanilla's spawner can't do it (stopped land vetoes natural spawns and
 * its caps are per category), so this runs its own pass, rare on purpose (Argon's encounter budget, adopted):
 * <ul>
 *   <li>Every {@link #EVERY} ticks, for each player in a stopping dimension (not spectators), at most once per roll
 *       cooldown (45-90 s): a few spots 16-48 blocks out; a spot spawns with probability faunaChance x dread.</li>
 *   <li>Caps: {@code faunaPerPlayer} (2) within 64 blocks, {@code faunaPerPlayerStorm} (3) in a storm.</li>
 *   <li>After an encounter (a fauna within {@link #ENCOUNTER} blocks of the player) no new ones for 10-15 minutes, 2-3 in a
 *       storm.</li>
 *   <li>When a player freezes, the fauna near them dissolve and none come for a while (no camping statues: drained, a
 *       statue, back for the gear, drained again; Argon).</li>
 * </ul>
 * Memory only: a restart forgets the cooldowns, which errs towards a quiet start anyway (they start at a fresh roll delay).
 */
public final class FaunaSpawner {
    private FaunaSpawner() {
    }

    static final int EVERY = 40;
    static final double ENCOUNTER = 8;
    static final int TRIES = 4;

    static final class State {
        long nextRoll;        // game time of the next roll
        long quietUntil;      // after an encounter or a freeze
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    /** GameTests: spawn rolls ignore the cooldowns and use this chance factor when set (NaN: off). */
    public static volatile double testChance = Double.NaN;
    /** GameTests: where spawn tries land (the test floor; the land 16-48 blocks out may be void there). */
    public static volatile java.util.function.Function<ServerPlayer, BlockPos> testSpot = null;

    static State state(ServerPlayer p) {
        return STATES.computeIfAbsent(p.getUUID(), k -> new State());
    }

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        if (!StasisConfig.fauna || server.getTickCount() % EVERY != 0) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || !(p.level() instanceof ServerLevel level) || !Stasis.stops(level)) continue;
            step(level, p, level.getGameTime());
        }
    }

    /** One pass for one player (public for tests). Returns how many fauna it spawned. */
    public static int step(ServerLevel level, ServerPlayer p, long now) {
        State s = state(p);
        if (s.nextRoll == 0) s.nextRoll = now + 900;   // (a fresh player, or after a restart: not at once)
        boolean test = !Double.isNaN(testChance);
        boolean storm = Dread.storm(level, p.getBlockX(), p.getBlockY(), p.getBlockZ());
        var near = level.getEntitiesOfClass(RiftFauna.class, new AABB(p.blockPosition()).inflate(64));
        for (RiftFauna f : near)
            if (f.distanceToSqr(p) < ENCOUNTER * ENCOUNTER && now >= s.quietUntil)
                s.quietUntil = now + minutes(level, storm ? StasisConfig.faunaStormQuietMinutes : StasisConfig.faunaQuietMinutes);
        if (!test && (now < s.nextRoll || now < s.quietUntil)) return 0;
        s.nextRoll = now + 20L * (45 + level.getRandom().nextInt(46));
        int cap = storm ? StasisConfig.faunaPerPlayerStorm : StasisConfig.faunaPerPlayer;
        int room = cap - near.size();
        double chance = test ? testChance : StasisConfig.faunaChance;
        int made = 0;
        for (int i = 0; i < TRIES && made < room; i++) {
            BlockPos spot = testSpot != null ? testSpot.apply(p) : spot(level, p);
            if (spot == null) continue;
            float dread = Dread.at(level, spot.getX(), spot.getY(), spot.getZ());
            if (dread <= 0 || level.getRandom().nextDouble() >= chance * dread) continue;
            if (spawn(level, spot) != null) made++;
        }
        return made;
    }

    /** A standable spot 16-48 blocks out, near the player's height, in stopped land. */
    static BlockPos spot(ServerLevel level, ServerPlayer p) {
        var r = level.getRandom();
        double ang = r.nextDouble() * Math.PI * 2, dist = 16 + r.nextDouble() * 32;
        int x = p.getBlockX() + (int) Math.round(Math.cos(ang) * dist), z = p.getBlockZ() + (int) Math.round(Math.sin(ang) * dist);
        if (!level.hasChunkAt(new BlockPos(x, 0, z))) return null;
        for (int y = p.getBlockY() + 8; y >= p.getBlockY() - 16; y--) {
            BlockPos q = new BlockPos(x, y, z);
            if (standable(level, q)) return RiftFauna.inStopped(level, q) && !Stasis.isRunning(level, q) ? q : null;
        }
        return null;
    }

    static boolean standable(ServerLevel level, BlockPos q) {
        return level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)
            && level.getBlockState(q).getCollisionShape(level, q).isEmpty()
            && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty();
    }

    /** Spawn one fauna at a spot (F1: Static is the only kind). Public for tests. */
    public static RiftFauna spawn(ServerLevel level, BlockPos spot) {
        RiftStatic f = StasisRegistry.RIFT_STATIC.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (f == null) return null;
        f.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0f);
        level.addFreshEntity(f);
        return f;
    }

    /** A player froze: the fauna near them dissolve, and none come for a while. */
    static void froze(ServerLevel level, ServerPlayer p) {
        for (RiftFauna f : level.getEntitiesOfClass(RiftFauna.class, new AABB(p.blockPosition()).inflate(48)))
            f.dissolve(level);
        state(p).quietUntil = level.getGameTime() + minutes(level, StasisConfig.faunaQuietMinutes);
    }

    static long minutes(ServerLevel level, double m) {
        return (long) (m * 1200 * (0.85 + 0.3 * level.getRandom().nextDouble()));
    }

    static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) {
        STATES.remove(e.getEntity().getUUID());
    }
}
