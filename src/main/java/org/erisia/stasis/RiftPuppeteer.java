package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Puppeteer (notes/fauna/PLAN.md, F3): a ground-hugging cloud of static 2-3 blocks wide, with some height, that pours
 * into a frozen mob and drives it ({@link Possession}). Baughn: wider and less dense than Static; that's how you tell them
 * apart. On its own it does no harm: it creeps (short low blinks, like Static's) to the nearest frozen mob within
 * {@link #SEEK} blocks, pours into it over {@link #POUR_TICKS}, and is gone into it. With no host about it drifts after
 * players, keeping its distance.
 */
public class RiftPuppeteer extends RiftFauna {
    static final double SEEK = 16;
    static final int POUR_TICKS = 30;
    /** The host it's pouring into (entity id; -1: none), for the client's stream. */
    static final EntityDataAccessor<Integer> POURING = SynchedEntityData.defineId(RiftPuppeteer.class, EntityDataSerializers.INT);

    private int moveIn = 10, pour, stunned;

    public RiftPuppeteer(EntityType<? extends RiftPuppeteer> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 10.0).add(Attributes.MOVEMENT_SPEED, 0.0)
            .add(Attributes.FOLLOW_RANGE, 24.0).add(Attributes.ATTACK_DAMAGE, 0.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(POURING, -1);
    }

    @Override
    protected void registerGoals() {
    }

    /** Freshly burst out of a host: still for a while. */
    void stun(int ticks) {
        stunned = ticks;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            clientCloud();
            return;
        }
        if (isRemoved() || !(level() instanceof ServerLevel level)) return;
        setDeltaMovement(Vec3.ZERO);
        if (stunned > 0) {
            stunned--;
            return;
        }
        int pourId = entityData.get(POURING);
        if (pourId >= 0) {
            Entity host = level.getEntity(pourId);
            if (!Possession.canHost(host) || host.distanceToSqr(this) > 9) {
                entityData.set(POURING, -1);
                pour = 0;
                return;
            }
            if (++pour >= POUR_TICKS) {
                Possession.start(level, (Mob) host);
                discard();
            }
            return;
        }
        if (--moveIn > 0) return;
        moveIn = 6 + random.nextInt(6);
        Mob host = nearestHost(level);
        if (host != null) {
            if (host.distanceToSqr(this) < 2.5 * 2.5) {
                entityData.set(POURING, host.getId());
                pour = 0;
                return;
            }
            creep(level, host.position(), 1 + dread());
            return;
        }
        Player p = level.getNearestPlayer(getX(), getY(), getZ(), 24, net.minecraft.world.entity.EntitySelector.NO_CREATIVE_OR_SPECTATOR);
        if (p != null && p.distanceToSqr(this) > 8 * 8) creep(level, p.position(), 0.6);
    }

    Mob nearestHost(ServerLevel level) {
        Mob best = null;
        double bd = SEEK * SEEK;
        for (Mob m : level.getEntitiesOfClass(Mob.class, getBoundingBox().inflate(SEEK), Possession::canHost)) {
            double d = m.distanceToSqr(this);
            if (d < bd) {
                bd = d;
                best = m;
            }
        }
        return best;
    }

    /** A low blink of ~0.8 blocks (x speed) towards {@code to}, onto free stopped ground. */
    private void creep(ServerLevel level, Vec3 to, double speed) {
        Vec3 d = to.subtract(position());
        if (d.lengthSqr() < 0.01) return;
        Vec3 t = position().add(d.normalize().scale(Math.min(d.length(), 0.8 * speed)));
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            Vec3 c = new Vec3(t.x, Math.floor(t.y) + dy, t.z);
            BlockPos at = BlockPos.containing(c);
            if (!inStopped(level, at) || !FaunaSpawner.standable(level, at)) continue;
            AABB box = getDimensions(getPose()).makeBoundingBox(c);
            if (level.getBlockCollisions(null, box).iterator().hasNext()) continue;
            setPos(c.x, c.y, c.z);
            return;
        }
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        return false;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return net.minecraft.sounds.SoundEvents.SCULK_CLICKING;
    }

    @Override
    public float getVoicePitch() {
        return 0.55f;   // (lower than Static's)
    }

    @Override
    public int getAmbientSoundInterval() {
        return 30;
    }

    /**
     * A low, wide cloud: specks in a flattened ellipsoid ~2.4 across and ~0.8 high, sparser than Static's. Pouring, a column
     * of specks from the cloud into the host.
     */
    void clientCloud() {
        for (int i = 0; i < 8; i++) {
            double dx = (random.nextDouble() - 0.5) * 2.4, dz = (random.nextDouble() - 0.5) * 2.4, dy = random.nextDouble() * 0.8;
            if (dx * dx + dz * dz > 1.44) continue;
            int kind = random.nextInt(10);
            level().addParticle(new net.minecraft.core.particles.DustParticleOptions(kind < 6 ? 0x111116 : kind < 9 ? 0xC8C8D0 : 0x9050E0,
                0.8f + random.nextFloat() * 0.5f), getX() + dx, getY() + 0.1 + dy, getZ() + dz, 0, 0, 0);
        }
        int pourId = entityData.get(POURING);
        Entity host = pourId >= 0 ? level().getEntity(pourId) : null;
        if (host != null) {
            Vec3 a = position().add(0, 0.4, 0), b = host.position().add(0, host.getBbHeight() * 0.6, 0);
            for (int i = 0; i < 6; i++) {
                double f = random.nextDouble();
                Vec3 q = a.lerp(b, f);
                level().addParticle(staticDust(random.nextInt(3)), q.x + (random.nextDouble() - 0.5) * 0.3, q.y + (random.nextDouble() - 0.5) * 0.3,
                    q.z + (random.nextDouble() - 0.5) * 0.3, 0, 0, 0);
            }
        }
    }
}
