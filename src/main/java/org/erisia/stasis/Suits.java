package org.erisia.stasis;

import mods.eln.item.electricalinterface.IItemEnergyBattery;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Suits that let a player move in stopped land: a full set of {@link TimeSuit} pieces, each with charge left. */
final class Suits {
    private Suits() {
    }

    static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Is the player wearing a full charged suit? If so, draw one tick's power from each piece. */
    static boolean protect(ServerPlayer p) {
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (!(s.getItem() instanceof TimeSuit t) || !(s.getItem() instanceof IItemEnergyBattery b)) return false;
            if (b.getEnergy(s) < t.stasisDraw(s) / 20.0) return false;
        }
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            IItemEnergyBattery b = (IItemEnergyBattery) s.getItem();
            b.setEnergy(s, b.getEnergy(s) - ((TimeSuit) s.getItem()).stasisDraw(s) / 20.0);
        }
        return true;
    }
}
