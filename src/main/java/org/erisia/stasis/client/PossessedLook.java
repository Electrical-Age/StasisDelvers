package org.erisia.stasis.client;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.erisia.stasis.RiftFauna;
import org.erisia.stasis.StasisRegistry;

/**
 * A Puppeteer's host (Possession): two glowing purple eyes (λ-Nero) and static hanging round it. Models differ, so the
 * eyes sit where most heads have them, not on each model's own eye pixels.
 */
public final class PossessedLook {
    private PossessedLook() {
    }

    /**
     * Where a host's two eyes are now (world coordinates): eye height, on the front of the head by its yaw (drawn by the
     * post shader after the desaturation: sparks and quads came out grey in stopped land; AdventurAgent).
     */
    static double[][] eyes(LivingEntity le, float partial) {
        boolean still = FrozenEntities.frozen(le);
        var pos = still ? le.position() : le.getPosition(partial);
        float yawDeg = still ? le.yHeadRot : net.minecraft.util.Mth.rotLerp(partial, le.yHeadRotO, le.yHeadRot);
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = Math.cos(yaw), rz = Math.sin(yaw);
        // (a narrow one, a humanoid: the face is just in front of the box's middle; a wide one, a quadruped: the head sticks
        // out in front of the body, about a box width ahead; AdventurAgent saw a cow's eyes mid-body at 0.55 w)
        double w = le.getBbWidth();
        double ahead = w < 0.8 ? Math.max(0.3, w * 0.55) : w * 0.95, apart = Math.max(0.09, w * 0.17);
        double y = pos.y + le.getEyeHeight();
        return new double[][]{
            {pos.x + fx * ahead - rx * apart, y, pos.z + fz * ahead - rz * apart},
            {pos.x + fx * ahead + rx * apart, y, pos.z + fz * ahead + rz * apart}};
    }

    /** Static round a host, also while it stands frozen between bursts (so this runs before FrozenEntities cancels). */
    static void haze(EntityTickEvent.Pre e) {
        var en = e.getEntity();
        if (!en.level().isClientSide() || !(en instanceof LivingEntity le) || !en.getData(StasisRegistry.POSSESSED.get())) return;
        var r = en.getRandom();
        for (int i = 0; i < 6; i++)
            en.level().addParticle(new net.minecraft.core.particles.DustParticleOptions(r.nextInt(5) < 3 ? 0x08080C : 0xE0E0EA, 0.9f + r.nextFloat() * 0.5f),
                en.getX() + (r.nextDouble() - 0.5) * en.getBbWidth() * 1.3, en.getY() + r.nextDouble() * en.getBbHeight(),
                en.getZ() + (r.nextDouble() - 0.5) * en.getBbWidth() * 1.3, 0, 0, 0);
    }
}
