package org.erisia.stasis;

import mods.eln.item.electricalinterface.IItemEnergyBattery;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Suits that let a player move in stopped land: {@link TimeSuit} pieces with charge left. In the plain Grey a chest
 * piece is enough (λ-Nero, Baughn 2026-10-18); in mist, deeps and near rifts each missing piece makes it worse
 * (Freezing: faster drain, and the freeze creeps in).
 */
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
                if (b.getEnergy(s) < share(p) * t.stasisDraw(s) / 20.0 && empty == null) empty = s.getHoverName().getString();
            } else if (missing == null) missing = slot.getName();
        }
        if (pieces == 0) return null;
        if (!(p.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof TimeSuit)) return "Suit incomplete: no chest piece";
        if (empty != null) return "Suit piece out of charge: " + empty;
        return null;
    }

    /** Each worn piece carries the draw of the missing ones too (Baughn: the same or worse drain in all cases; a partial
     *  suit is for other armour and enchantments, not to economise): 4 / worn pieces. */
    static double share(ServerPlayer p) {
        int worn = 4 - missing(p);
        return worn == 0 ? 1 : 4.0 / worn;
    }

    /** Suit pieces not worn (0..4; a chest piece alone: 3). */
    static int missing(ServerPlayer p) {
        int n = 0;
        for (EquipmentSlot slot : ARMOR) if (!(p.getItemBySlot(slot).getItem() instanceof TimeSuit)) n++;
        return n;
    }

    /** Seconds of protection left: the emptiest piece (a full charged suit assumed). */
    static double secondsLeft(ServerPlayer p) {
        double left = Double.MAX_VALUE;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (s.getItem() instanceof TimeSuit t && s.getItem() instanceof IItemEnergyBattery b && t.stasisDraw(s) > 0)
                left = Math.min(left, b.getEnergy(s) / (t.stasisDraw(s) * share(p)));
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

    /** What a player could feed a tower: carried batteries and the worn suit pieces, J. */
    static double available(ServerPlayer p) {
        double sum = 0;
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack src = inv.getItem(i);
            if (!src.isEmpty() && !(src.getItem() instanceof TimeSuit) && src.getItem() instanceof IItemEnergyBattery sb) sum += sb.getEnergy(src);
        }
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (s.getItem() instanceof TimeSuit && s.getItem() instanceof IItemEnergyBattery b) sum += b.getEnergy(s);
        }
        return sum;
    }

    /**
     * Take up to maxJ from a player (a tower console's feed, Baughn: a jump-start from the suit): carried batteries first
     * (at their discharge power), then the worn suit pieces, evenly, down to nothing. Returns the J taken.
     */
    static double drain(ServerPlayer p, double maxJ) {
        return drain(p, maxJ, 0);
    }

    /** ... leaving each suit piece reserve (a share of its store). */
    static double drain(ServerPlayer p, double maxJ, double reserve) {
        double left = maxJ;
        var inv = p.getInventory();
        for (int i = 0; i < 36 && left > 1e-6; i++) {
            ItemStack src = inv.getItem(i);
            if (src.isEmpty() || src.getItem() instanceof TimeSuit || !(src.getItem() instanceof IItemEnergyBattery sb)) continue;
            double take = Math.min(left, Math.min(sb.getDischagePower(src), sb.getEnergy(src)));
            if (take <= 0) continue;
            sb.setEnergy(src, sb.getEnergy(src) - take);
            left -= take;
        }
        java.util.List<ItemStack> pieces = new java.util.ArrayList<>();
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (s.getItem() instanceof TimeSuit && s.getItem() instanceof IItemEnergyBattery b && b.getEnergy(s) > reserve * b.getEnergyMax(s) + 1e-6) pieces.add(s);
        }
        // in equal shares, so the pieces run down together
        while (left > 1e-6 && !pieces.isEmpty()) {
            double share = left / pieces.size();
            for (var it = pieces.iterator(); it.hasNext(); ) {
                ItemStack s = it.next();
                IItemEnergyBattery b = (IItemEnergyBattery) s.getItem();
                double floor = reserve * b.getEnergyMax(s);
                double take = Math.min(share, b.getEnergy(s) - floor);
                b.setEnergy(s, b.getEnergy(s) - take);
                left -= take;
                if (b.getEnergy(s) <= floor + 1e-6) it.remove();
            }
        }
        return maxJ - left;
    }

    /** Would the suit protect (a chest piece, every worn piece charged), without drawing power? */
    static boolean protectsWithoutDraw(ServerPlayer p) {
        if (!(p.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof TimeSuit)) return false;
        double f = share(p);
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (!(s.getItem() instanceof TimeSuit t) || !(s.getItem() instanceof IItemEnergyBattery b)) continue;
            if (b.getEnergy(s) < f * t.stasisDraw(s) / 20.0) return false;
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

    /** Is the player wearing a charged suit (a chest piece; every worn piece charged)? If so, draw one tick's power
     *  (x factor: deeps) from each worn piece. */
    static boolean protect(ServerPlayer p, double factor) {
        if (!(p.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof TimeSuit)) return false;
        factor *= share(p);
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (!(s.getItem() instanceof TimeSuit t) || !(s.getItem() instanceof IItemEnergyBattery b)) continue;
            if (b.getEnergy(s) < factor * t.stasisDraw(s) / 20.0) return false;
        }
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getItemBySlot(slot);
            if (!(s.getItem() instanceof TimeSuit)) continue;
            IItemEnergyBattery b = (IItemEnergyBattery) s.getItem();
            b.setEnergy(s, b.getEnergy(s) - factor * ((TimeSuit) s.getItem()).stasisDraw(s) / 20.0);
        }
        return true;
    }
}
