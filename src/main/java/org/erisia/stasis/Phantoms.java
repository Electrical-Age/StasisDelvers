package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.Vec3;

/**
 * Phantoms are creatures of stopped time (Baughn 2026-10-25: nobody likes them). They are unbound (#stasisdelvers:unbound:
 * they move in stopped land), spawn only over a player out in stopped land, never target anyone inside a field (or
 * other running land: the spawn area), and steer clear of fields.
 */
final class Phantoms {
    private Phantoms() {
    }

    /** How far ahead (ticks of the phantom's own motion) it looks for running land to turn away from. */
    static final double LOOKAHEAD = 10;
    /** How far away from the field it then circles (blocks). */
    static final double RETREAT = 24;

    /** Vanilla's phantom spawner: only for a player out in stopped land of a level where time stops. */
    static void spawn(net.neoforged.neoforge.event.entity.player.PlayerSpawnPhantomsEvent e) {
        var p = e.getEntity();
        if (!(p.level() instanceof ServerLevel level) || !Stasis.stops(level) || Stasis.spawnsHere(level, p.blockPosition()))
            e.setResult(net.neoforged.neoforge.event.entity.player.PlayerSpawnPhantomsEvent.Result.DENY);
    }

    /** A phantom spawn anywhere time runs (in a field, in a dimension that doesn't stop): cancelled. */
    static boolean refused(ServerLevel level, double x, double y, double z) {
        return !Stasis.stops(level) || Stasis.spawnsHere(level, BlockPos.containing(x, y, z));
    }

    /** No target in running land. */
    static void target(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent e) {
        if (e.getEntity() instanceof Phantom ph && ph.level() instanceof ServerLevel level && e.getNewAboutToBeSetTarget() != null
            && Stasis.stops(level) && Stasis.spawnsHere(level, e.getNewAboutToBeSetTarget().blockPosition()))
            e.setCanceled(true);
    }

    /** Every tick: drop a target that went into running land; turn away from running land ahead. */
    static void tick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof Phantom ph) || !(ph.level() instanceof ServerLevel level) || !Stasis.stops(level)) return;
        var t = ph.getTarget();
        if (t != null && Stasis.spawnsHere(level, t.blockPosition())) ph.setTarget(null);
        Vec3 v = ph.getDeltaMovement();
        Vec3 ahead = ph.position().add(v.scale(LOOKAHEAD));
        boolean here = Stasis.spawnsHere(level, ph.blockPosition());
        if (here && !Stasis.spawnsHere(level, BlockPos.containing(ph.xo, ph.yo, ph.zo)))
            ph.setPos(ph.xo, ph.yo, ph.zo);   // (a wall, as for rift fauna: it doesn't get in)
        if (!here && !Stasis.spawnsHere(level, BlockPos.containing(ahead))) return;
        // away from the field holding (or nearest) the point ahead, else back the way it came
        Vec3 from = null;
        double best = Double.MAX_VALUE;
        for (Field f : FieldData.get(level).fields()) {
            double d = f.norm(ahead.x - f.x(), ahead.y - f.y(), ahead.z - f.z());
            if (d < best) {
                best = d;
                from = new Vec3(f.x(), f.y(), f.z());
            }
        }
        Vec3 away = from == null || best > 2 ? new Vec3(-v.x, 0, -v.z) : new Vec3(ph.getX() - from.x, 0, ph.getZ() - from.z);
        if (away.lengthSqr() < 1e-6) away = new Vec3(1, 0, 0);
        away = away.normalize();
        Vec3 to = ph.position().add(away.scale(RETREAT));
        ph.anchorPoint = BlockPos.containing(to.x, Math.max(ph.getY(), to.y), to.z);
        ph.moveTargetPoint = new Vec3(to.x, ph.getY(), to.z);
        ph.setTarget(null);
        ph.setDeltaMovement(away.scale(Math.max(0.3, v.horizontalDistance())).add(0, v.y, 0));
    }
}
