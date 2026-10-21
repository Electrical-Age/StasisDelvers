package org.erisia.stasis;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * The "Stasis Delvers" guide tab (data/stasisdelvers/advancement/guide; hints by λ-Nero, 2026-10-21): the ones the game
 * grants from code (impossible triggers in their JSON). The rest are vanilla triggers (joining, items, locations).
 */
public final class GuideAdvancements {
    private GuideAdvancements() {
    }

    public static void award(ServerPlayer p, String path) {
        var adv = p.level().getServer().getAdvancements().get(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, path));
        if (adv != null) p.getAdvancements().award(adv, "done");
    }

    /** Every player in the field (a tower's: Safe Harbour as it forms, Lights Out as it collapses). */
    static void awardInside(ServerLevel level, Field f, String path) {
        for (ServerPlayer p : level.players())
            if (!p.isSpectator() && f.containsPoint(p.getX(), p.getY() + 0.1, p.getZ())) award(p, path);
    }

    /** Every player within r of (x, y, z) (Breach: those who saw the gate open). */
    static void awardNear(ServerLevel level, double x, double y, double z, double r, String path) {
        for (ServerPlayer p : level.players())
            if (!p.isSpectator() && p.distanceToSqr(x, y, z) <= r * r) award(p, path);
    }

    /** Once a second (Suits.tick): Sealed Suit (all four Delver pieces, charged), Fortified Suit (all four Stasis-Proof). */
    static void suits(ServerPlayer p) {
        if (p.tickCount % 20 != 0) return;
        boolean delver = true, stasis = true;
        for (EquipmentSlot slot : Suits.ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (!(s.getItem() instanceof DelverSuitItem d) || d.getEnergy(s) <= 0) delver = false;
            if (!(s.getItem() instanceof StasisArmorItem)) stasis = false;
        }
        if (delver) award(p, "guide/sealed_suit");
        if (stasis) award(p, "guide/fortified_suit");
    }
}
