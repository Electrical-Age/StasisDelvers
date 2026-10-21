package org.erisia.stasis;

import mods.eln.item.electricalinterface.IItemEnergyBattery;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The Delver suit: cheap, no armour at all, keeps its wearer moving in stopped land on battery power. A chest piece
 * is enough in the plain Grey; in mist, deeps and near rifts each missing piece makes it worse (Suits). Charged in Electrical Age's battery chargers like any EA electrical item.
 */
public class DelverSuitItem extends Item implements IItemEnergyBattery, TimeSuit {
    private final double chargePower, draw;

    /** The store follows the config (StasisConfig.delverSuitMinutes: Baughn, 30 minutes): draw x the time. */
    public DelverSuitItem(Properties properties, double chargePower, double draw) {
        super(properties);
        this.chargePower = chargePower;
        this.draw = draw;
    }

    double storage() {
        return draw * 60 * StasisConfig.delverSuitMinutes;
    }

    @Override
    public double stasisDraw(ItemStack stack) {
        return draw;
    }

    @Override
    public void setEnergy(ItemStack stack, double value) {
        TimeSuit.setEnergy(stack, Math.min(storage(), value));
    }

    @Override
    public double getEnergy(ItemStack stack) {
        return Math.min(storage(), TimeSuit.energy(stack));   // (suits charged before a shorter setting hold no more)
    }

    @Override
    public double getEnergyMax(ItemStack stack) {
        return storage();
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
        return (int) Math.round(13 * Math.min(1, getEnergy(stack) / storage()));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x3fd0c8;
    }

    /** The chest piece, the one that's enough in the plain Grey (Baughn: "Not ideal, but sufficient"). */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<net.minecraft.network.chat.Component> out, net.minecraft.world.item.TooltipFlag flag) {
        if (stack.is(StasisRegistry.DELVER_CHEST.get()))
            out.accept(net.minecraft.network.chat.Component.translatable("item.stasisdelvers.delver_chestplate.tooltip").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
