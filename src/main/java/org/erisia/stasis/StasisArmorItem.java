package org.erisia.stasis;

import mods.eln.item.electricalitem.ElectricalArmor;
import net.minecraft.world.item.ItemStack;

/**
 * The expensive suit: Electrical Age's E-Coal armour (energy absorbs damage) that also keeps its wearer moving in
 * stopped land. Shares E-Coal's energy store with the time protection.
 */
public class StasisArmorItem extends ElectricalArmor implements TimeSuit {
    private final double draw;

    public StasisArmorItem(Properties properties, double energyStorage, double chargePower, double ratioMax, double ratioMaxEnergy,
                           double energyPerDamage, double draw) {
        super(properties, energyStorage, chargePower, ratioMax, ratioMaxEnergy, energyPerDamage);
        this.draw = draw;
    }

    @Override
    public double stasisDraw(ItemStack stack) {
        return draw;
    }
}
