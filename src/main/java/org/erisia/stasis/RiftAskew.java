package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The Askew (notes/fauna/OPTIONS.md; Baughn: "like endermen with their limbs quite askew"; "try the Askew"): an
 * enderman gone wrong, joints bent the wrong way and its head turned too far (client: AskewRenderer). Time stops for it
 * while it is watched: while any player looks at it, it can't move (the vanilla enderman is angered by a stare; this one
 * is held by it). Unwatched it teleports towards the nearest survival player in short jumps, leaving an afterimage; next
 * to them it doesn't hurt, it displaces: the player is thrown 6-12 blocks off (preferring the weather's side) and the
 * Askew is gone in a teleport. Rare: the spawner makes one only in strong dread.
 */
public class RiftAskew extends RiftFauna {
    static final double SENSE = 32, REACH = 2.2;
    /** How closely a look must point at it to hold it (cosine of ~16°), within this range. */
    static final double STARE = 0.96, STARE_RANGE = 48;

    private int jumpIn = 20, displaceCooldown;

    public RiftAskew(EntityType<? extends RiftAskew> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 30.0).add(Attributes.MOVEMENT_SPEED, 0.0)
            .add(Attributes.FOLLOW_RANGE, SENSE).add(Attributes.ATTACK_DAMAGE, 0.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 24f));
    }

    /** Is any player looking at it (a stare, with a clear line of sight)? */
    public boolean watched(ServerLevel level) {
        Vec3 eye = getEyePosition();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !p.isAlive() || p.distanceToSqr(this) > STARE_RANGE * STARE_RANGE) continue;
            Vec3 to = eye.subtract(p.getEyePosition());
            double len = to.length();
            if (len < 1e-3) continue;
            // (its head or its body: a look at its feet holds it too)
            Vec3 toBody = position().add(0, getBbHeight() * 0.5, 0).subtract(p.getEyePosition());
            double c = Math.max(p.getLookAngle().dot(to.normalize()), p.getLookAngle().dot(toBody.normalize()));
            if (c > STARE && p.hasLineOfSight(this)) return true;
        }
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level) || isRemoved()) return;
        // (purple eyes drawn after the grey, and static round it, as a Puppeteer's host: PossessedLook, StasisPost)
        if (!getData(StasisRegistry.POSSESSED.get())) setData(StasisRegistry.POSSESSED.get(), true);
        setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
        if (displaceCooldown > 0) displaceCooldown--;
        if (watched(level)) return;   // (held: not a twitch)
        Player prey = level.getNearestPlayer(getX(), getY(), getZ(), SENSE, EntitySelector.NO_CREATIVE_OR_SPECTATOR);
        if (prey == null) return;
        if (displaceCooldown == 0 && distanceToSqr(prey) < REACH * REACH && prey instanceof ServerPlayer sp) {
            displace(level, sp);
            return;
        }
        if (--jumpIn > 0) return;
        jumpIn = (int) ((15 + random.nextInt(16)) / (1 + dread()));
        double dist = Math.sqrt(distanceToSqr(prey));
        double step = Math.min(dist - 1.2, 3 + random.nextDouble() * 3);
        if (step < 0.5) return;
        Vec3 dir = prey.position().subtract(position()).normalize();
        Vec3 sideways = new Vec3(-dir.z, 0, dir.x).scale((random.nextDouble() - 0.5) * 2);
        jumpTo(level, position().add(dir.scale(step)).add(sideways));
    }

    /** Teleport near {@code target} onto standable stopped ground with room for it; an afterimage where it stood. */
    boolean jumpTo(ServerLevel level, Vec3 target) {
        for (int i = 0; i < 8; i++) {
            Vec3 t = i == 0 ? target : target.add((random.nextDouble() - 0.5) * 3, 0, (random.nextDouble() - 0.5) * 3);
            for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3}) {
                BlockPos q = BlockPos.containing(t.x, Math.floor(t.y) + dy, t.z);
                if (!FaunaSpawner.standable(level, q) || !inStopped(level, q) || !inStopped(level, q.above(2))
                    || !level.getBlockState(q.above(2)).getCollisionShape(level, q.above(2)).isEmpty()) continue;
                afterimage(level);
                setPos(q.getX() + 0.5, q.getY(), q.getZ() + 0.5);
                level.playSound(null, blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.6f, 0.5f);
                return true;
            }
        }
        return false;
    }

    /** A column of dark specks where it stood (they hang in stopped land, so the afterimage stays a moment). */
    void afterimage(ServerLevel level) {
        for (int i = 0; i < 18; i++)
            level.sendParticles(staticDust(i % 2 == 0 ? 0 : 2), getX(), getY() + random.nextDouble() * getBbHeight(), getZ(), 1, 0.15, 0.1, 0.15, 0);
    }

    /**
     * Throw the player 6-12 blocks off onto standable stopped ground, towards dread if there is some (the weather's
     * side), and leave in a teleport. No damage.
     */
    void displace(ServerLevel level, ServerPlayer p) {
        Vec3 best = null;
        float bestDread = -1;
        for (int i = 0; i < 24; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = 6 + random.nextDouble() * 6;
            Vec3 t = p.position().add(Math.cos(a) * d, 0, Math.sin(a) * d);
            for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4}) {
                BlockPos q = BlockPos.containing(t.x, Math.floor(t.y) + dy, t.z);
                if (!FaunaSpawner.standable(level, q) || !inStopped(level, q)) continue;
                float dr = Dread.at(level, q.getX(), q.getY(), q.getZ());
                if (dr > bestDread) {
                    bestDread = dr;
                    best = Vec3.atBottomCenterOf(q);
                }
                break;
            }
        }
        displaceCooldown = 200;
        if (best == null) return;
        level.playSound(null, p.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f, 0.4f);
        p.teleportTo(level, best.x, best.y, best.z, java.util.Set.of(), p.getYRot(), p.getXRot(), false);
        level.playSound(null, p.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f, 0.4f);
        jumpTo(level, position().add((random.nextDouble() - 0.5) * 16, 0, (random.nextDouble() - 0.5) * 16));
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, net.minecraft.world.entity.Entity target) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return SoundEvents.ENDERMAN_AMBIENT;
    }

    @Override
    public float getVoicePitch() {
        return 0.45f;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 160;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return SoundEvents.ENDERMAN_HURT;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return SoundEvents.ENDERMAN_DEATH;
    }
}
