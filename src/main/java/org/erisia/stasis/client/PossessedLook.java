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
     * Static round a host and its eyes, also while it stands frozen between bursts (so this runs before FrozenEntities
     * cancels). The eyes are reverse-portal sparks (purple, full-bright) at eye height on the front of the head, by its
     * yaw: rendered quads didn't show (AdventurAgent), and in stopped land the sparks hang a moment, so it leaves a purple
     * trail as it moves.
     */
    static void haze(EntityTickEvent.Pre e) {
        var en = e.getEntity();
        if (!en.level().isClientSide() || !(en instanceof LivingEntity le) || !en.getData(StasisRegistry.POSSESSED.get())) return;
        var r = en.getRandom();
        for (int i = 0; i < 6; i++)
            en.level().addParticle(new net.minecraft.core.particles.DustParticleOptions(r.nextInt(5) < 3 ? 0x08080C : 0xE0E0EA, 0.9f + r.nextFloat() * 0.5f),
                en.getX() + (r.nextDouble() - 0.5) * en.getBbWidth() * 1.3, en.getY() + r.nextDouble() * en.getBbHeight(),
                en.getZ() + (r.nextDouble() - 0.5) * en.getBbWidth() * 1.3, 0, 0, 0);
        float yaw = (float) Math.toRadians(le.getYHeadRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = Math.cos(yaw), rz = Math.sin(yaw);
        double ahead = Math.max(0.3, en.getBbWidth() * 0.55), apart = Math.max(0.09, en.getBbWidth() * 0.17);
        double y = en.getY() + en.getEyeHeight();
        for (int side = -1; side <= 1; side += 2)
            en.level().addParticle(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL, en.getX() + fx * ahead + rx * apart * side, y,
                en.getZ() + fz * ahead + rz * apart * side, 0, 0, 0);
    }
}
