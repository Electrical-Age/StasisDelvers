package org.erisia.stasis;

import mods.eln.item.electricalinterface.IItemEnergyBattery;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The Delver suit: cheap, no armour at all, keeps its wearer moving in stopped land on battery power. A full set
 * (four pieces) is needed. Charged in Electrical Age's battery chargers like any EA electrical item.
 */
public class DelverSuitItem extends Item implements IItemEnergyBattery, TimeSuit {
    private final double storage, chargePower, draw;

    public DelverSuitItem(Properties properties, double storage, double chargePower, double draw) {
        super(properties);
        this.storage = storage;
        this.chargePower = chargePower;
        this.draw = draw;
    }

    @Override
    public double stasisDraw(ItemStack stack) {
        return draw;
    }

    @Override
    public void setEnergy(ItemStack stack, double value) {
        TimeSuit.setEnergy(stack, Math.min(storage, value));
    }

    @Override
    public double getEnergy(ItemStack stack) {
        return TimeSuit.energy(stack);
    }

    @Override
    public double getEnergyMax(ItemStack stack) {
        return storage;
    }

    @Override
    public double getChargePower(ItemStack stack) {
        return chargePower;
    }

    @Override
    public double getDischagePower(ItemStack stack) {
        return 0;
    }

    @Override
    public int getPriority(ItemStack stack) {
        return 0;
    }

    @Override
    public void electricalItemUpdate(ItemStack stack, double time) {
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return (int) Math.round(13 * Math.min(1, getEnergy(stack) / storage));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x3fd0c8;
    }
}
