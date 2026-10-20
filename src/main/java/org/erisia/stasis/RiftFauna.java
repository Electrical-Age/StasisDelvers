package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

/**
 * Rift fauna: what crawls out where time is stopped (notes/fauna/PLAN.md, F1). Time doesn't stop for them (tag
 * {@code #stasisdelvers:rift_fauna}, inside {@code unbound}), but running time does:
 * <ul>
 *   <li>A healthy field is a wall (λ-Nero: an EA-powered field is safety and nothing violates that). A fauna that finds
 *       itself where time runs is put back where it last stood in stopped land. A <b>failing</b> field (failing > 0.5)
 *       can be entered (λ-Nero).</li>
 *   <li>Nowhere to go back to (a field grew over it, a deep drifted off running land): half the time it escapes outward,
 *       otherwise it dissolves (Baughn: a field expanding over them kills them, with a chance to escape).</li>
 *   <li>Its paths treat running land as blocked.</li>
 *   <li>Speed: 1 + dread (at most 2x; λ-Nero).</li>
 *   <li>It belongs to the weather: dread 0 under it for {@link #FADE_TICKS} and it dissolves. No drops (Baughn's default:
 *       pure hazard).</li>
 * </ul>
 */
public abstract class RiftFauna extends Monster {
    static final Identifier DREAD_SPEED = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "dread_speed");
    /** Ticks without dread before it dissolves. */
    public static final int FADE_TICKS = 200;
    /** A failing field this far gone lets fauna in. */
    static final double FAILING_ENTRY = 0.5;

    private BlockPos lastStopped;
    private int calmTicks;
    private float dread;
    private boolean near = true;

    protected RiftFauna(EntityType<? extends RiftFauna> type, Level level) {
        super(type, level);
        this.xpReward = 0;
    }

    public float dread() {
        return dread;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level) || isRemoved() || isDeadOrDying()) return;
        if (tickCount % 10 == 0) {
            dread = Dread.at(level, getBlockX(), getBlockY(), getBlockZ());
            for (var attr : java.util.List.of(Attributes.MOVEMENT_SPEED, Attributes.FLYING_SPEED)) {
                AttributeInstance speed = getAttribute(attr);
                if (speed != null)
                    speed.addOrUpdateTransientModifier(new AttributeModifier(DREAD_SPEED, Math.min(dread, 1f), AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
        }
        if (!barrier(level)) return;
        if (tickCount % 20 == 0) near = dread > 0 || nearDread(level);
        calmTicks = near ? 0 : calmTicks + 1;
        if (calmTicks >= FADE_TICKS) dissolve(level);
    }

    /** Some dread within a few blocks (the edge of a deep, a pocket next door): not yet calm. */
    private boolean nearDread(ServerLevel level) {
        for (int dx = -4; dx <= 4; dx += 4)
            for (int dz = -4; dz <= 4; dz += 4)
                if (Dread.at(level, getBlockX() + dx, getBlockY(), getBlockZ() + dz) > 0) return true;
        return false;
    }

    /** Stopped, or in a failing field (fauna may enter those). */
    static boolean inStopped(ServerLevel level, BlockPos p) {
        if (!Stasis.isRunning(level, p)) return true;
        for (Field f : FieldData.get(level).fields())
            if (f.failing() > FAILING_ENTRY && f.containsBlock(p.getX(), p.getY(), p.getZ())) return true;
        return false;
    }

    /** The wall: false if it was removed. */
    private boolean barrier(ServerLevel level) {
        BlockPos here = blockPosition();
        if (inStopped(level, here)) {
            lastStopped = here;
            return true;
        }
        if (lastStopped != null && inStopped(level, lastStopped)) {
            setPos(lastStopped.getX() + 0.5, lastStopped.getY(), lastStopped.getZ() + 0.5);
            setDeltaMovement(Vec3.ZERO);
            getNavigation().stop();
            return true;
        }
        // nowhere to step back to: a field grew over it (or a deep drifted off running land)
        BlockPos out = random.nextBoolean() ? escape(level, here) : null;
        if (out != null) {
            setPos(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
            setDeltaMovement(Vec3.ZERO);
            getNavigation().stop();
            lastStopped = out;
            return true;
        }
        dissolve(level);
        return false;
    }

    /** The nearest stopped spot within 12 blocks, searched outward in 8 directions, standable. */
    static BlockPos escape(ServerLevel level, BlockPos from) {
        for (int r = 2; r <= 12; r++)
            for (int a = 0; a < 8; a++) {
                double ang = a * Math.PI / 4;
                BlockPos p = from.offset((int) Math.round(Math.cos(ang) * r), 0, (int) Math.round(Math.sin(ang) * r));
                for (int dy = 2; dy >= -3; dy--) {
                    BlockPos q = p.above(dy);
                    if (inStopped(level, q) && level.getBlockState(q.below()).isFaceSturdy(level, q.below(), net.minecraft.core.Direction.UP)
                        && level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty())
                        return q;
                }
            }
        return null;
    }

    /** Gone in a burst of static. */
    public void dissolve(ServerLevel level) {
        for (int i = 0; i < 24; i++)
            level.sendParticles(staticDust(random.nextInt(3)), getX(), getY() + getBbHeight() * 0.5, getZ(), 1,
                getBbWidth(), getBbHeight() * 0.5, getBbWidth(), 0.02);
        discard();
    }

    /** The particles static is made of: black, white or violet pixels. */
    public static DustParticleOptions staticDust(int kind) {
        int colour = switch (kind) {
            case 0 -> 0x0A0A0E;
            case 1 -> 0xE8E8F0;
            default -> 0x8A3CF0;
        };
        return new DustParticleOptions(colour, 0.6f);
    }

    @Override
    protected boolean shouldDropLoot(ServerLevel level) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return true;
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new GroundPathNavigation(this, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                this.nodeEvaluator = new StoppedOnly();
                return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
            }
        };
    }

    /** ... for flying ones (Static). */
    static final class StoppedOnlyFly extends net.minecraft.world.level.pathfinder.FlyNodeEvaluator {
        @Override
        public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) {
            PathType t = super.getPathTypeOfMob(context, x, y, z, mob);
            if (t != PathType.BLOCKED && mob.level() instanceof ServerLevel level && !inStopped(level, new BlockPos(x, y, z)))
                return PathType.BLOCKED;
            return t;
        }
    }

    /** Paths through stopped land (and failing fields) only. */
    static final class StoppedOnly extends WalkNodeEvaluator {
        @Override
        public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) {
            PathType t = super.getPathTypeOfMob(context, x, y, z, mob);
            if (t != PathType.BLOCKED && mob.level() instanceof ServerLevel level && !inStopped(level, new BlockPos(x, y, z)))
                return PathType.BLOCKED;
            return t;
        }
    }
}
