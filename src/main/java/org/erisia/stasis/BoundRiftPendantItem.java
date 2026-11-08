package org.erisia.stasis;

import net.minecraft.world.item.ItemStack;

/**
 * The bound rift pendant (λ-Nero's ask; Baughn 2026-11-08 picked option A: a charged shard set in a small EA cell): a
 * rift pendant that recharges in Electrical Age's battery chargers instead of crumbling. Baughn: it must cost more
 * electricity for the same protection than the Delver suit, "about 20% less efficient": worn alone it draws 4 x 12 W
 * where the suit draws 4 x 10 W. Its store holds MINUTES of that; it is made empty.
 */
public class BoundRiftPendantItem extends RiftPendantItem {
    /** W, a piece's worth (the Delver piece's 10 W, +20%). */
    static final double BOUND_DRAW = 12;
    static final int MINUTES = 15;
    /** Charges at the Delver suit's 400 W. */
    static final double CHARGE_W = 400;

    public BoundRiftPendantItem(Properties properties) {
        super(properties, MINUTES);
    }

    @Override
    double storage() {
        return 4 * BOUND_DRAW * 60 * MINUTES;
    }

    @Override
    public double stasisDraw(ItemStack stack) {
        return BOUND_DRAW;
    }

    @Override
    public boolean crumbles() {
        return false;
    }

    @Override
    public double getEnergy(ItemStack stack) {
        return Math.min(storage(), TimeSuit.energy(stack));
    }

    @Override
    public void setEnergy(ItemStack stack, double value) {
        TimeSuit.setEnergy(stack, Math.max(0, Math.min(storage(), value)));
    }

    @Override
    public double getChargePower(ItemStack stack) {
        return CHARGE_W;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<net.minecraft.network.chat.Component> out, net.minecraft.world.item.TooltipFlag flag) {
        super.appendHoverText(stack, context, display, out, flag);
        out.accept(net.minecraft.network.chat.Component.translatable("item.stasisdelvers.bound_rift_pendant.tooltip")
            .withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
