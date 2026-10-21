package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;

/**
 * Where an unprotected player respawns (Baughn 2026-10-21, "extra safety rule"): never into stopped time if anywhere
 * else will do. In order:
 * <ol>
 *   <li>their respawn point, if time runs there (or a charged suit protects them);</li>
 *   <li>world spawn, if time runs there;</li>
 *   <li>the nearest working field above ground: a tower's (radius at least {@link #WORKING_RADIUS}, not failing or
 *       collapsing; querns and lance cores hold small fields that end) whose field holds the surface;</li>
 *   <li>any field with room to stand;</li>
 *   <li>the nearest lull (Lulls, the rare running-time weather): the surface at its centre.</li>
 * </ol>
 * Failing all of them, the respawn point as vanilla gives it.
 */
public final class SafeRespawn {
    private SafeRespawn() {
    }

    static final double WORKING_RADIUS = 8;
    /** Tests: as if world spawn were in stopped land (the test world's may run). */
    public static volatile boolean testStoppedSpawn;

    /** Why the player woke somewhere else (told after the respawn), by player. */
    static final java.util.Map<java.util.UUID, String> NOTES = new java.util.concurrent.ConcurrentHashMap<>();

    public static TeleportTransition choose(ServerPlayer p, TeleportTransition t) {
        if (p.isCreative() || p.isSpectator()) return t;
        if (Stasis.isRunning(t.newLevel(), BlockPos.containing(t.position())) || Suits.protect(p)) return t;
        var spawn = TeleportTransition.createDefault(p, t.postTeleportTransition());
        if (!testStoppedSpawn && Stasis.isRunning(spawn.newLevel(), BlockPos.containing(spawn.position())))
            return note(p, t, spawn, "Your respawn point is in stopped land, where you would freeze without a charged suit. You woke at world spawn.");
        TeleportTransition into = intoFields(p, t, FieldData.get(t.newLevel()).fields());
        if (into != null) return into;
        BlockPos at = intoLull(t.newLevel(), BlockPos.containing(t.position()));
        if (at != null) return note(p, t, to(t.newLevel(), at, t),
            "Your respawn point and world spawn are in stopped land, and there is no field. You woke in a lull, where time still runs. It drifts slowly: don't wander off.");
        return t;
    }

    /** How far to look for a lull (about one within 1.5 km). */
    static final double LULL_RANGE = 4096;

    /** The surface at the nearest lull's centre (or the nearest standable spot in its core), or null. */
    public static @Nullable BlockPos intoLull(ServerLevel level, BlockPos from) {
        Lulls.Lull l = Lulls.nearest(level, from.getX() + 0.5, from.getZ() + 0.5, LULL_RANGE);
        if (l == null) return null;
        int cx = (int) Math.floor(l.x()), cz = (int) Math.floor(l.z());
        int core = (int) Math.floor(l.radius() * Lulls.CORE);
        for (int ring = 0; ring <= core; ring++)
            for (int dx = -ring; dx <= ring; dx++)
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = cx + dx, z = cz + dz;
                    BlockPos q = new BlockPos(x, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    if (standable(level, q) && Stasis.isRunning(level, q) && Stasis.isRunning(level, q.above())) return q;
                }
        return null;
    }

    /** Steps 3 and 4 over these fields (tests pass their own), or null. */
    public static @Nullable TeleportTransition intoFields(ServerPlayer p, TeleportTransition t, java.util.List<Field> candidates) {
        ServerLevel level = t.newLevel();
        BlockPos from = BlockPos.containing(t.position());
        var fields = new java.util.ArrayList<>(candidates);
        fields.sort(Comparator.comparingDouble(f -> horizontal(f, from)));
        for (Field f : fields) {
            if (!working(f)) continue;
            BlockPos at = surfaceIn(level, f);
            if (at != null) return note(p, t, to(level, at, t),
                "Your respawn point and world spawn are in stopped land. You woke in the nearest working field.");
        }
        for (Field f : fields) {
            BlockPos at = anywhereIn(level, f);
            if (at != null) return note(p, t, to(level, at, t),
                "Your respawn point and world spawn are in stopped land. You woke in the only field there was.");
        }
        return null;
    }

    static double horizontal(Field f, BlockPos p) {
        return Math.hypot(f.x() - (p.getX() + 0.5), f.z() - (p.getZ() + 0.5));
    }

    /** A tower's field at work: big enough, not failing or collapsing. */
    static boolean working(Field f) {
        return f.radius() >= WORKING_RADIUS && f.failing() < 0.25 && f.collapse() <= 0 && f.rate() > 0;
    }

    /** A standable spot under the open sky inside the field (above ground), nearest its centre, or null. */
    public static @Nullable BlockPos surfaceIn(ServerLevel level, Field f) {
        return spotIn(level, f, true);
    }

    /** Any standable spot inside the field (top down from its centre's column outwards), or null. */
    public static @Nullable BlockPos anywhereIn(ServerLevel level, Field f) {
        return spotIn(level, f, false);
    }

    /** The highest standable spot in each column, rings out from the centre's column; under the open sky if asked. */
    static @Nullable BlockPos spotIn(ServerLevel level, Field f, boolean sky) {
        int cx = (int) Math.floor(f.x()), cy = (int) Math.floor(f.y()), cz = (int) Math.floor(f.z());
        int r = (int) Math.floor(f.radius());
        for (int ring = 0; ring <= r; ring++)
            for (int dx = -ring; dx <= ring; dx++)
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (int y = cy + r; y >= cy - r; y--) {
                        BlockPos q = new BlockPos(cx + dx, y, cz + dz);
                        if (!standable(level, q) || !f.containsBlock(q.getX(), y, q.getZ()) || !f.containsBlock(q.getX(), y + 1, q.getZ())
                            || !Stasis.isRunning(level, q) || !Stasis.isRunning(level, q.above())) continue;
                        if (!sky || level.canSeeSky(q)) return q;
                        break;   // (the column's top spot is under cover: not above ground)
                    }
                }
        return null;
    }

    static boolean standable(ServerLevel level, BlockPos q) {
        return level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)
            && level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getFluidState(q).isEmpty()
            && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty();
    }

    static TeleportTransition to(ServerLevel level, BlockPos at, TeleportTransition t) {
        return new TeleportTransition(level, Vec3.atBottomCenterOf(at), Vec3.ZERO, t.yRot(), t.xRot(), t.postTeleportTransition());
    }

    static TeleportTransition note(ServerPlayer p, TeleportTransition was, TeleportTransition now, String why) {
        NOTES.put(p.getUUID(), why);
        StasisDelvers.LOG.info("{}'s respawn point {} is in stopped land: respawning at {}", p.getName().getString(),
            BlockPos.containing(was.position()).toShortString(), BlockPos.containing(now.position()).toShortString());
        return now;
    }
}
