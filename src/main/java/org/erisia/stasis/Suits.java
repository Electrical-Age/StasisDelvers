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

    /**
     * Why a worn suit doesn't protect (null: it does, or no suit pieces at all). Shown to the player in stopped land, so
     * a missing or empty piece isn't a silent death (softlock audit 7).
     */
    static String problem(ServerPlayer p) {
        int pieces = 0;
        String missing = null, empty = null;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (s.getItem() instanceof TimeSuit t && s.getItem() instanceof IItemEnergyBattery b) {
                pieces++;
                if (b.getEnergy(s) < t.stasisDraw(s) / 20.0 && empty == null) empty = s.getHoverName().getString();
            } else if (missing == null) missing = slot.getName();
        }
        if (pieces == 0) return null;
        if (missing != null) return "Suit incomplete: no " + missing + " piece";
        if (empty != null) return "Suit piece out of charge: " + empty;
        return null;
    }

    /** Seconds of protection left: the emptiest piece (a full charged suit assumed). */
    static double secondsLeft(ServerPlayer p) {
        double left = Double.MAX_VALUE;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (s.getItem() instanceof TimeSuit t && s.getItem() instanceof IItemEnergyBattery b && t.stasisDraw(s) > 0)
                left = Math.min(left, b.getEnergy(s) / t.stasisDraw(s));
        }
        return left;
    }

    /** J per durability point the advanced (stasis) suit spends to repair itself (Baughn: self-repair on power, as E-Coal). */
    static final double REPAIR_J = 500;

    /**
     * Once a second: suit pieces top themselves up from batteries carried in the inventory (EA portable batteries and
     * the like; Baughn: both suits run off inventory batteries), and the advanced suit repairs itself on power.
     */
    static void tick(ServerPlayer p) {
        if (p.tickCount % 20 != 0 || p.isSpectator()) return;
        var inv = p.getInventory();
        // what the pieces want this second, and what the inventory's batteries can give
        java.util.List<ItemStack> pieces = new java.util.ArrayList<>();
        double[] want = new double[4];
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (!(s.getItem() instanceof TimeSuit) || !(s.getItem() instanceof IItemEnergyBattery b)) continue;
            want[pieces.size()] = Math.max(0, Math.min(b.getEnergyMax(s) - b.getEnergy(s), b.getChargePower(s)));
            pieces.add(s);
        }
        double supply = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack src = inv.getItem(i);
            if (!src.isEmpty() && !(src.getItem() instanceof TimeSuit) && src.getItem() instanceof IItemEnergyBattery sb)
                supply += Math.max(0, Math.min(sb.getDischagePower(src), sb.getEnergy(src)));
        }
        // share it evenly (water-filling), so every piece gets charge at once: a suit protects only when all have some
        double[] give = new double[pieces.size()];
        double left = supply;
        for (int round = 0; round < 4 && left > 1e-6; round++) {
            int open = 0;
            for (int k = 0; k < give.length; k++) if (want[k] - give[k] > 1e-6) open++;
            if (open == 0) break;
            double share = left / open;
            for (int k = 0; k < give.length; k++) {
                double g = Math.min(share, want[k] - give[k]);
                if (g > 0) { give[k] += g; left -= g; }
            }
        }
        double draw = supply - left;
        for (int i = 0; i < 36 && draw > 1e-6; i++) {
            ItemStack src = inv.getItem(i);
            if (src.isEmpty() || src.getItem() instanceof TimeSuit || !(src.getItem() instanceof IItemEnergyBattery sb)) continue;
            double take = Math.min(draw, Math.min(sb.getDischagePower(src), sb.getEnergy(src)));
            if (take <= 0) continue;
            sb.setEnergy(src, sb.getEnergy(src) - take);
            draw -= take;
        }
        for (int k = 0; k < pieces.size(); k++) {
            ItemStack s = pieces.get(k);
            IItemEnergyBattery b = (IItemEnergyBattery) s.getItem();
            b.setEnergy(s, b.getEnergy(s) + give[k]);
            if (s.getItem() instanceof StasisArmorItem && s.isDamaged() && b.getEnergy(s) >= REPAIR_J) {
                s.setDamageValue(s.getDamageValue() - 1);
                b.setEnergy(s, b.getEnergy(s) - REPAIR_J);
            }
        }
    }

    /** Would the suit protect (full and charged), without drawing power? */
    static boolean protectsWithoutDraw(ServerPlayer p) {
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (!(s.getItem() instanceof TimeSuit t) || !(s.getItem() instanceof IItemEnergyBattery b)) return false;
            if (b.getEnergy(s) < t.stasisDraw(s) / 20.0) return false;
        }
        return true;
    }

    static boolean protect(ServerPlayer p) {
        return protect(p, 1);
    }

    /** Only Delver pieces (no stasis suit piece): the weaker suit, not fully protective in a deep (Deeps). */
    static boolean delverOnly(ServerPlayer p) {
        for (EquipmentSlot slot : ARMOR) if (p.getItemBySlot(slot).getItem() instanceof StasisArmorItem) return false;
        return true;
    }

    /** Is the player wearing a full charged suit? If so, draw one tick's power (x factor: deeps) from each piece. */
    static boolean protect(ServerPlayer p, double factor) {
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (!(s.getItem() instanceof TimeSuit t) || !(s.getItem() instanceof IItemEnergyBattery b)) return false;
            if (b.getEnergy(s) < factor * t.stasisDraw(s) / 20.0) return false;
        }
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            IItemEnergyBattery b = (IItemEnergyBattery) s.getItem();
            b.setEnergy(s, b.getEnergy(s) - factor * ((TimeSuit) s.getItem()).stasisDraw(s) / 20.0);
        }
        return true;
    }
}
