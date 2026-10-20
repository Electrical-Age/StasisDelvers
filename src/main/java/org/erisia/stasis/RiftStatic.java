package org.erisia.stasis;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
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
    public RiftStatic(EntityType<? extends RiftStatic> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 6.0).add(Attributes.MOVEMENT_SPEED, 0.23)
            .add(Attributes.FOLLOW_RANGE, 24.0).add(Attributes.ATTACK_DAMAGE, 0.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, true));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6));
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
        return 40;
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

    /** A dense knot of static pixels, about 0.8 blocks across, flickering round its centre. */
    void clientStatic() {
        for (int i = 0; i < 3; i++) {
            double dx = (random.nextDouble() - 0.5) * 0.8, dy = (random.nextDouble() - 0.5) * 0.8, dz = (random.nextDouble() - 0.5) * 0.8;
            if (dx * dx + dy * dy + dz * dz > 0.16) continue;
            level().addParticle(staticDust(random.nextInt(3)), getX() + dx, getY() + 0.5 + dy, getZ() + dz, 0, 0, 0);
        }
    }
}
