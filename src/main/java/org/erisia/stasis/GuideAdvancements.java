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

    /** The ruined towers' reach for The Last Chance (λ-Nero: within 5 blocks or so of it; some generate without the
     *  cottage, just the tower): this far from the core. */
    static final double RUIN_REACH = 8;

    /** Within RUIN_REACH of a ruin's tower core (TowerBlockEntity.ruined), in the chunks round the player. */
    static void lastChance(ServerPlayer p) {
        var adv = p.level().getServer().getAdvancements().get(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "guide/the_last_chance"));
        if (adv == null || p.getAdvancements().getOrStartProgress(adv).isDone() || !(p.level() instanceof ServerLevel level)) return;
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        for (int cx = pcx - 1; cx <= pcx + 1; cx++)
            for (int cz = pcz - 1; cz <= pcz + 1; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (var be : chunk.getBlockEntities().values())
                    if (be instanceof TowerBlockEntity t && t.ruined && t.isMaster()
                        && p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(be.getBlockPos())) <= RUIN_REACH * RUIN_REACH) {
                        p.getAdvancements().award(adv, "done");
                        return;
                    }
            }
    }

    /** Once a second (Suits.tick): Sealed Suit (all four Delver pieces, charged), Fortified Suit (all four Stasis-Proof). */
    static void suits(ServerPlayer p) {
        if (p.tickCount % 20 != 0) return;
        if (p.tickCount % 40 == 0) lastChance(p);
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
