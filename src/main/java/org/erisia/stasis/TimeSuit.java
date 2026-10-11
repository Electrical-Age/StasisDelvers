package org.erisia.stasis;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * A suit piece that keeps its wearer going in stopped land, on stored energy (J). Energy lives in the stack's custom
 * data, so Electrical Age chargers (IItemEnergyBattery) and the suit share one number.
 */
public interface TimeSuit {
    /** Power the piece draws while it protects its wearer (W). */
    double stasisDraw(ItemStack stack);

    static double energy(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d == null ? 0 : d.copyTag().getDoubleOr("stasisdelvers_energy", 0);
    }

    static void setEnergy(ItemStack stack, double value) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, (CompoundTag t) -> t.putDouble("stasisdelvers_energy", Math.max(0, value)));
    }
}
