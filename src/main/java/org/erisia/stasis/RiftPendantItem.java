package org.erisia.stasis;

import mods.eln.item.electricalinterface.IItemEnergyBattery;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * A rift crystal on a string, worn in the necklace slot (Curios; Baughn 2026-10-22): it works as a Delver chest piece
 * does, without the armour. Alone it's enough in the plain Grey and weaker in deeps, as a lone chest piece is. It
 * stands in for the chest piece only when none is worn (Suits.piece). It holds MINUTES of the Delver piece's draw and
 * never recharges. When it's spent, the crystal crumbles and the string is left (Suits.crumble). Made full: the
 * stack records what has been used, not what's left.
 */
public class RiftPendantItem extends Item implements IItemEnergyBattery, TimeSuit {
    /** A Delver piece's draw (W, StasisRegistry.delver). */
    static final double DRAW = 10;
    final int minutes;

    public RiftPendantItem(Properties properties, int minutes) {
        super(properties);
        this.minutes = minutes;
    }

    /** Worn alone it pays the whole suit's draw (Suits.share: 4 x a piece's; AdventurAgent saw it last a quarter of
     *  its time), so it holds MINUTES of that. */
    double storage() {
        return 4 * DRAW * 60 * minutes;
    }

    static double used(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d == null ? 0 : d.copyTag().getDoubleOr("stasisdelvers_used", 0);
    }

    @Override
    public double stasisDraw(ItemStack stack) {
        return DRAW;
    }

    @Override
    public double getEnergy(ItemStack stack) {
        return Math.max(0, storage() - used(stack));
    }

    @Override
    public void setEnergy(ItemStack stack, double value) {
        double used = storage() - Math.max(0, Math.min(storage(), value));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, (CompoundTag t) -> t.putDouble("stasisdelvers_used", used));
    }

    @Override
    public double getEnergyMax(ItemStack stack) {
        return storage();
    }

    @Override
    public double getChargePower(ItemStack stack) {
        return 0;   // (a crystal, not a battery: it doesn't recharge)
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
        return used(stack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return (int) Math.round(13 * Math.min(1, getEnergy(stack) / storage()));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0xb46cf0;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<net.minecraft.network.chat.Component> out, net.minecraft.world.item.TooltipFlag flag) {
        int left = (int) Math.ceil(getEnergy(stack) / (4 * DRAW));   // (seconds, worn alone)
        out.accept(net.minecraft.network.chat.Component.translatable("item.stasisdelvers.rift_pendant.tooltip", left / 60, String.format("%02d", left % 60))
            .withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
