package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Static: a small, dense knot of static that comes for suited players and feeds on their charge (Baughn: smaller and
 * denser than the Puppeteer; that's how you tell them apart). Within {@link #REACH} blocks each one adds the suit's own draw
 * again ({@link Freezing}), so a rift capacitor takes it like the weather's surcharge.
 * <p>
 * It doesn't walk or fly: it <b>blinks</b>, a few blocks at a time, hanging still in the air between (the tendril stutter,
 * Baughn: time is stopped for us, not necessarily for them). Walking it stuck in riftstone pits; flying it sank, drifted
 * off and hung high (AdventurAgent). A blink lands only on a free spot in stopped land, so the field wall holds.
 * No model: the client draws it as a knot of static pixels ({@link #clientStatic}).
 */
public class RiftStatic extends RiftFauna {
    /** Reach of the drain, in blocks. */
    public static final double REACH = 4;
    /** It notices players this far off. */
    static final double SENSE = 24;
    /** It hangs this high over the ground (or over the player's feet). */
    static final double HOVER = 0.6;

    private int blinkIn = 20;

    public RiftStatic(EntityType<? extends RiftStatic> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 6.0).add(Attributes.MOVEMENT_SPEED, 0.0)
            .add(Attributes.FOLLOW_RANGE, SENSE).add(Attributes.ATTACK_DAMAGE, 0.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 12f));
    }

    /** How many Static feed on this player's suit now (within REACH, at most 3; Freezing multiplies the draw by 1 + this). */
    public static int feeding(ServerLevel level, Player p) {
        int n = level.getEntitiesOfClass(RiftStatic.class, p.getBoundingBox().inflate(REACH), s -> s.isAlive() && s.distanceToSqr(p) <= REACH * REACH).size();
        return Math.min(3, n);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            clientStatic();
            return;
        }
        if (isRemoved() || !(level() instanceof ServerLevel level)) return;
        setDeltaMovement(Vec3.ZERO);
        if (--blinkIn > 0) return;
        Player prey = level.getNearestPlayer(getX(), getY(), getZ(), SENSE, net.minecraft.world.entity.EntitySelector.NO_CREATIVE_OR_SPECTATOR);
        // faster with dread (blinks come sooner and reach further; at most 2x: λ-Nero)
        float d = dread();
        blinkIn = (int) ((prey != null ? 10 + random.nextInt(16) : 40 + random.nextInt(60)) / (1 + d));
        if (prey != null) {
            double dist = Math.sqrt(distanceToSqr(prey));
            if (dist < 1.8) return;
            double step = Math.min(dist - 1.5, (2.5 + random.nextDouble()) * (1 + d));
            Vec3 to = prey.position().add(0, HOVER, 0).subtract(position()).normalize().scale(step);
            blink(level, position().add(to));
        } else {
            double a = random.nextDouble() * Math.PI * 2;
            blink(level, position().add(Math.cos(a) * 1.5, 0, Math.sin(a) * 1.5));
        }
    }

    /** Blink to near {@code target}: a free spot in stopped land, HOVER over the ground if there is ground under it. */
    boolean blink(ServerLevel level, Vec3 target) {
        for (int i = 0; i < 6; i++) {
            Vec3 t = i == 0 ? target : target.add((random.nextDouble() - 0.5) * 2, (random.nextDouble() - 0.5) * 2, (random.nextDouble() - 0.5) * 2);
            t = settle(level, t);
            BlockPos at = BlockPos.containing(t);
            AABB box = getDimensions(getPose()).makeBoundingBox(t);
            if (!inStopped(level, at) || !inStopped(level, BlockPos.containing(t.x, t.y + 0.7, t.z)) || !level.noCollision(this, box)) continue;
            setPos(t.x, t.y, t.z);
            if (random.nextInt(3) == 0) playAmbientSound();
            return true;
        }
        return false;
    }

    /** Drop to HOVER over ground within 3 blocks below; with no ground that near (a pit's edge, the open air), stay at height. */
    private static Vec3 settle(ServerLevel level, Vec3 t) {
        BlockPos p = BlockPos.containing(t);
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos below = p.below(dy + 1);
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty())
                return new Vec3(t.x, below.getY() + 1 + HOVER * 0.5, t.z);
        }
        return t;
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return net.minecraft.sounds.SoundEvents.SCULK_CLICKING;   // (a Geiger-ish crackle)
    }

    @Override
    public int getAmbientSoundInterval() {
        return 12;   // (AdventurAgent: 2-3 clicks in 11 s was too few to notice)
    }

    @Override
    protected float getSoundVolume() {
        return 0.7f;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return net.minecraft.sounds.SoundEvents.SCULK_CLICKING_STOP;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return net.minecraft.sounds.SoundEvents.SCULK_CLICKING_STOP;
    }

    /** It doesn't hit: it feeds on the suit from up to REACH away (Freezing). */
    @Override
    public boolean doHurtTarget(ServerLevel level, net.minecraft.world.entity.Entity target) {
        return false;
    }

    /**
     * A dense knot of static, about 0.8 blocks across, flickering round its centre. Black and white mostly, so it reads in
     * the grey (AdventurAgent: sparse grey specks were nearly invisible, the violet washed out); dense and big enough to
     * catch the eye at 10 blocks. In stopped land the specks hang where they appeared and fade (ParticleGroupMixin), so it
     * leaves a short trail. End-rod glints are full-bright, for rift darkness.
     */
    void clientStatic() {
        for (int i = 0; i < 10; i++) {
            double dx = (random.nextDouble() - 0.5) * 0.9, dy = (random.nextDouble() - 0.5) * 0.9, dz = (random.nextDouble() - 0.5) * 0.9;
            if (dx * dx + dy * dy + dz * dz > 0.2) continue;
            int kind = random.nextInt(10);
            level().addParticle(new net.minecraft.core.particles.DustParticleOptions(kind < 5 ? 0x050508 : kind < 9 ? 0xF4F4FF : 0xB060FF,
                0.9f + random.nextFloat() * 0.6f), getX() + dx, getY() + 0.4 + dy, getZ() + dz, 0, 0, 0);
        }
        if (random.nextInt(2) == 0)
            level().addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD, getX() + (random.nextDouble() - 0.5) * 0.6,
                getY() + 0.4 + (random.nextDouble() - 0.5) * 0.6, getZ() + (random.nextDouble() - 0.5) * 0.6, 0, 0, 0);
    }
}
