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
 * wear a time suit in a stopping dimension, showing its emptiest piece. Near a rift core it flickers purple, more
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
        if (pieces == 0) return;
        int w = g.guiWidth(), h = g.guiHeight();
        int x = w / 2 + 96, y = h - 22, bw = 40;
        float strain = StasisPost.lastStrain;
        boolean flick = strain > 0.05f && Math.random() < strain * 0.6;
        int frame = flick ? 0xFF8030C0 : 0xFF303038;
        g.fill(x - 1, y - 1, x + bw + 1, y + 6, frame);
        g.fill(x, y, x + bw, y + 5, 0xFF101014);
        int col = flick ? 0xFFB060FF : share < 0.15 ? 0xFFD05030 : share < 0.4 ? 0xFFD0A030 : 0xFF50A0D0;
        g.fill(x, y, x + (int) (bw * Math.max(0, Math.min(1, share))), y + 5, col);
        if (pieces < 4) g.text(mc.font, "!", x + bw + 3, y - 2, 0xFFFF5050);   // an incomplete suit doesn't protect
    }
}
