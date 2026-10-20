package org.erisia.stasis;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Static: a small, dense knot of static that drifts towards suited players and feeds on their charge (Baughn: smaller and
 * denser than the Puppeteer; that's how you tell them apart): within {@link #REACH} blocks each one adds the suit's own draw
 * again ({@link Freezing}), so a rift capacitor takes it like the weather's surcharge.
 * No model: the client draws it as a knot of static pixels ({@link #clientStatic}).
 */
public class RiftStatic extends RiftFauna {
    /**
     * It floats, a low cloud (AdventurAgent: walking, it took 3.5 min to cross 8 blocks of rugged riftstone and then stuck in
     * a pit for good).
     */
    public RiftStatic(EntityType<? extends RiftStatic> type, Level level) {
        super(type, level);
        this.moveControl = new net.minecraft.world.entity.ai.control.FlyingMoveControl<>(this, 20, true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 6.0).add(Attributes.MOVEMENT_SPEED, 0.2)
            .add(Attributes.FLYING_SPEED, 0.35).add(Attributes.FOLLOW_RANGE, 24.0).add(Attributes.ATTACK_DAMAGE, 0.0);
    }

    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        var nav = new net.minecraft.world.entity.ai.navigation.FlyingPathNavigation(this, level) {
            @Override
            protected net.minecraft.world.level.pathfinder.PathFinder createPathFinder(int maxVisitedNodes) {
                this.nodeEvaluator = new StoppedOnlyFly();
                return new net.minecraft.world.level.pathfinder.PathFinder(this.nodeEvaluator, maxVisitedNodes);
            }
        };
        nav.setCanOpenDoors(false);
        nav.setCanFloat(true);
        return nav;
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, true));
        goalSelector.addGoal(5, new net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal(this, 0.6));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 12f));
        targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, false));
    }

    /** Reach of the drain, in blocks. */
    public static final double REACH = 4;

    /** How many Static feed on this player's suit now (within REACH, at most 3; Freezing multiplies the draw by 1 + this). */
    public static int feeding(net.minecraft.server.level.ServerLevel level, Player p) {
        int n = level.getEntitiesOfClass(RiftStatic.class, p.getBoundingBox().inflate(REACH), s -> s.isAlive() && s.distanceToSqr(p) <= REACH * REACH).size();
        return Math.min(3, n);
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
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.Entity target) {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) clientStatic();
    }

    /**
     * A dense knot of static, about 0.8 blocks across, flickering round its centre. Black and white mostly, so it reads in
     * the grey (AdventurAgent: sparse grey specks were nearly invisible, the violet washed out); dense and big enough to
     * catch the eye at 10 blocks. In stopped land the specks hang where they appeared and fade (ParticleGroupMixin), so it
     * leaves a short trail.
     */
    void clientStatic() {
        for (int i = 0; i < 10; i++) {
            double dx = (random.nextDouble() - 0.5) * 0.9, dy = (random.nextDouble() - 0.5) * 0.9, dz = (random.nextDouble() - 0.5) * 0.9;
            if (dx * dx + dy * dy + dz * dz > 0.2) continue;
            int kind = random.nextInt(10);
            level().addParticle(new net.minecraft.core.particles.DustParticleOptions(kind < 5 ? 0x050508 : kind < 9 ? 0xF4F4FF : 0xB060FF,
                0.9f + random.nextFloat() * 0.6f), getX() + dx, getY() + 0.55 + dy, getZ() + dz, 0, 0, 0);
        }
        // a few bright sparks: full-bright, so it shows in rift darkness (AdventurAgent: on black riftstone in the dark the
        // dust was invisible)
        if (random.nextInt(3) == 0)
            level().addParticle(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, getX() + (random.nextDouble() - 0.5) * 0.6,
                getY() + 0.55 + (random.nextDouble() - 0.5) * 0.6, getZ() + (random.nextDouble() - 0.5) * 0.6, 0, 0, 0);
    }
}
