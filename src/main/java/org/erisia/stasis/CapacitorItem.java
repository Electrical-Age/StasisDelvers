package org.erisia.stasis;

import mods.eln.item.electricalinterface.IItemEnergyBattery;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A rift capacitor (λ-Nero, notes/rifts/CAPACITOR-SPEC.md): carried, it takes a stopped world's harm meant for its
 * wearer (Capacitors). What it has taken is stored as missing charge, so venting it costs real EA power: any EA item
 * charger fills it back up. Capacity in seconds of protection, at Capacitors.J_PER_SECOND.
 */
public class CapacitorItem extends Item implements IItemEnergyBattery {
    final double seconds;

    public CapacitorItem(Properties properties, double seconds) {
        super(properties);
        this.seconds = seconds;
    }

    double storage() {
        return seconds * Capacitors.J_PER_SECOND;
    }

    @Override
    public void setEnergy(ItemStack stack, double value) {
        TimeSuit.setEnergy(stack, Math.min(storage(), value));
    }

    @Override
    public double getEnergy(ItemStack stack) {
        return Math.min(storage(), TimeSuit.energy(stack));
    }

    @Override
    public double getEnergyMax(ItemStack stack) {
        return storage();
    }

    @Override
    public double getChargePower(ItemStack stack) {
        return Capacitors.VENT_W;
    }

    @Override
    public double getDischagePower(ItemStack stack) {
        return 0;   // (it powers nothing: suits mustn't drain it as a battery)
    }

    @Override
    public int getPriority(ItemStack stack) {
        return 0;
    }

    @Override
    public void electricalItemUpdate(ItemStack stack, double time) {
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<net.minecraft.network.chat.Component> out, net.minecraft.world.item.TooltipFlag flag) {
        double s = getEnergy(stack) / Capacitors.J_PER_SECOND;
        out.accept(net.minecraft.network.chat.Component.literal(String.format("Takes %d:%02d more (%.0f of %.0f kJ); an EA charger vents it",
            (int) s / 60, (int) s % 60, getEnergy(stack) / 1000, storage() / 1000)).withStyle(net.minecraft.ChatFormatting.GRAY));
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
        return 0xb070ff;
    }
}
