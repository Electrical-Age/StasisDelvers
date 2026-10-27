package org.erisia.stasis.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.erisia.stasis.Stasis;
import org.erisia.stasis.TimeSuit;

/**
 * The suit's charge on the HUD (λ-Nero's "Stasis HUD elements"): a small gauge above the hotbar's right end while you
 * wear a time suit in a stopping dimension, showing its emptiest piece; under it, green, the worn battery belt's
 * batteries (the two bars slimmer when both show). Near a rift core it flickers purple, more
 * the nearer (the strain on reality, StasisPost slot 1596).
 */
public final class SuitGauge {
    private SuitGauge() {
    }

    static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** {the emptiest time-suit piece's charge share (2 if none), the number of pieces}: what the gauge shows (bug
     *  reports read it too, so the two agree). */
    static double[] share(net.minecraft.world.entity.player.Player player) {
        double share = 2;
        int pieces = 0;
        for (EquipmentSlot s : ARMOR) {
            ItemStack st = player.getItemBySlot(s);
            if (!(st.getItem() instanceof TimeSuit) || !(st.getItem() instanceof mods.eln.item.electricalinterface.IItemEnergyBattery b)) continue;
            pieces++;
            share = Math.min(share, b.getEnergy(st) / Math.max(1, b.getEnergyMax(st)));
        }
        return new double[]{share, pieces};
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker dt) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !Stasis.stops(mc.level)) return;
        double[] sp = share(mc.player);
        double share = sp[0];
        int pieces = (int) sp[1];
        // the worn battery belt's batteries (Baughn 2026-10-27): a second bar, the two stacked and slimmer
        ItemStack belt = org.erisia.stasis.BatteryBeltItem.worn(mc.player);
        double beltShare = belt.isEmpty() ? Double.NaN : org.erisia.stasis.BatteryBeltItem.batteryShare(belt);
        boolean suit = pieces > 0, beltBar = !Double.isNaN(beltShare);
        if (!suit && !beltBar) return;
        int w = g.guiWidth(), h = g.guiHeight();
        int x = w / 2 + 96, bw = 40;
        boolean both = suit && beltBar;
        int bh = both ? 3 : 5;
        float strain = StasisPost.lastStrain;
        boolean flick = strain > 0.05f && Math.random() < strain * 0.6;
        if (suit) {
            int y = both ? h - 26 : h - 22;
            bar(g, x, y, bw, bh, share, flick, flick ? 0xFFB060FF : colour(share));
            if (pieces < 4) g.text(mc.font, "!", x + bw + 3, y - 2, 0xFFFF5050);   // an incomplete suit doesn't protect
        }
        if (beltBar) bar(g, x, both ? h - 21 : h - 22, bw, bh, beltShare, flick, 0xFF60C080);
    }

    static int colour(double share) {
        return share < 0.15 ? 0xFFD05030 : share < 0.4 ? 0xFFD0A030 : 0xFF50A0D0;
    }

    static void bar(GuiGraphicsExtractor g, int x, int y, int bw, int bh, double share, boolean flick, int col) {
        g.fill(x - 1, y - 1, x + bw + 1, y + bh + 1, flick ? 0xFF8030C0 : 0xFF303038);
        g.fill(x, y, x + bw, y + bh, 0xFF101014);
        g.fill(x, y, x + (int) (bw * Math.max(0, Math.min(1, share))), y + bh, col);
    }
}
